import Fastify, { type FastifyRequest } from 'fastify';
import { createHash, randomUUID } from 'node:crypto';
import { once } from 'node:events';
import { z } from 'zod';
import { AuthStore, Limiter } from './auth.js';
import type { Config } from './config.js';
import { ApiError } from './errors.js';
import { DeepSeekProvider, type AiProvider, type Usage } from './provider.js';
import { assertContext, parseInsight, parseProposal, requestSchema } from './schema.js';

type Cached={expiresAt:number,digest:string,value?:unknown,pending:boolean};
export function createApp(config:Config, options:{provider?:AiProvider,now?:()=>number,technicalLog?:(record:Record<string,unknown>)=>void}={}) {
  const now=options.now??Date.now,auth=new AuthStore(config,now),limiter=new Limiter(now),cache=new Map<string,Cached>();
  const provider=options.provider??new DeepSeekProvider(config),app=Fastify({logger:false,disableRequestLogging:true,bodyLimit:131072,trustProxy:config.TRUST_PROXY.split(',').filter(Boolean),requestTimeout:config.PROVIDER_TIMEOUT_MS+10000,genReqId:()=>randomUUID()});
  app.decorateRequest('sessionId','');
  const cleanup=setInterval(()=>{
    for(const [key,value] of cache)if(value.expiresAt<=now())cache.delete(key);
    try{auth.prune();}catch{options.technicalLog?.({status:'auth_cleanup_failed'});}
  },60000);cleanup.unref();
  app.addHook('onClose',async()=>{clearInterval(cleanup);cache.clear();});
  const started=new WeakMap<object,number>();
  app.addHook('onRequest',async request=>{
    started.set(request,now());
    const loopback=['127.0.0.1','::1','::ffff:127.0.0.1'].includes(request.socket.remoteAddress??'');
    if(request.protocol!=='https'&&!(config.ALLOW_LOCAL_HTTP==='true'&&loopback))throw new ApiError(426,'HTTPS_REQUIRED','Для подключения нужен HTTPS.');
    limiter.take('global:'+request.ip,120,60000);
  });
  app.addHook('onResponse',async(request,reply)=>{options.technicalLog?.({requestId:request.id,status:reply.statusCode,latencyMs:Math.max(0,now()-(started.get(request)??now()))});});
  app.setErrorHandler((error,request,reply)=>{
    if(error instanceof ApiError)return reply.code(error.status===499?400:error.status).send({error:{code:error.code,message:error.publicMessage},requestId:request.id});
    if(error instanceof z.ZodError)return reply.code(400).send({error:{code:'INVALID_REQUEST',message:'Проверьте формат и значения запроса.'},requestId:request.id});
    const status=(error as {statusCode?:number}).statusCode;
    const clientError=typeof status==='number'&&status>=400&&status<500;
    return reply.code(clientError?status:500).send({error:{code:status===413?'BODY_TOO_LARGE':clientError?'INVALID_REQUEST':'INTERNAL_ERROR',message:status===413?'Слишком большой запрос.':clientError?'Проверьте формат запроса.':'Не удалось обработать запрос.'},requestId:request.id});
  });
  const authorize=async(request:FastifyRequest)=>{request.sessionId=auth.authorize(request.headers.authorization);};
  const consume=(request:FastifyRequest)=>limiter.take('ai:'+request.sessionId,config.REQUESTS_PER_MINUTE,60000);
  app.get('/health',async()=>({status:'ok',provider:'deepseek',aiConfigured:!!config.DEEPSEEK_API_KEY,model:config.AI_MODEL}));
  app.post('/v1/pair',async request=>{
    limiter.take('pair:'+request.ip,config.PAIR_ATTEMPTS_PER_10_MINUTES,600000);
    const body=z.object({code:z.string().trim().toUpperCase().regex(/^[A-Z2-9]{10}$/),deviceName:z.string().trim().min(1).max(80)}).strict().parse(request.body);
    return auth.pair(body.code,body.deviceName);
  });
  app.post('/v1/session/refresh',async request=>{
    limiter.take('refresh:'+request.ip,30,600000);
    const body=z.object({refreshToken:z.string().min(32).max(256)}).strict().parse(request.body);return auth.refresh(body.refreshToken);
  });
  app.post('/v1/session/revoke',async request=>{
    limiter.take('revoke:'+request.ip,30,600000);
    if(request.body!==undefined) {
      const body=z.object({refreshToken:z.string().min(32).max(256)}).strict().parse(request.body);
      // Refresh itself authenticates revocation, so expired access cannot prevent disconnect.
      // Idempotent success also avoids disclosing whether a supplied refresh token existed.
      auth.revokeByRefresh(body.refreshToken);
    } else auth.revoke(auth.authorize(request.headers.authorization));
    return {revoked:true};
  });
  function begin(request:FastifyRequest,kind:string) {
    if(!config.DEEPSEEK_API_KEY&&!options.provider)throw new ApiError(503,'AI_NOT_CONFIGURED','Владелец сервера ещё не настроил ключ DeepSeek.');
    for(const [key,value] of cache)if(value.expiresAt<=now())cache.delete(key);
    const data=requestSchema.parse(request.body);assertContext(data,config.MAX_CONTEXT_BYTES);
    const key=request.sessionId+':'+data.requestId,digest=createHash('sha256').update(JSON.stringify({kind,...data})).digest('hex');
    const existing=cache.get(key);
    if(existing){if(existing.digest!==digest)throw new ApiError(409,'IDEMPOTENCY_CONFLICT','Действие с этим ID уже отличается.');if(existing.pending||kind==='chat'||existing.value===undefined)throw new ApiError(409,'REQUEST_ALREADY_SENT','Этот запрос уже отправлен.');return {data,key,cached:existing.value};}
    const contentDigest=createHash('sha256').update(JSON.stringify({context:data.context,prompt:data.prompt,messages:data.messages})).digest('hex');
    const fingerprintKey=request.sessionId+':'+kind+':'+data.fingerprint+':'+contentDigest;
    const previous=kind==='chat'?undefined:cache.get(fingerprintKey);
    if(previous&&!previous.pending)return {data,key,cached:previous.value};
    consume(request);
    if(cache.size>2000)throw new ApiError(429,'SERVER_BUSY','Сервер занят. Повторите позже.');
    cache.set(key,{expiresAt:now()+600000,digest,pending:true});
    return {data,key,fingerprintKey,digest};
  }
  const abortFor=(request:FastifyRequest)=>{const controller=new AbortController();request.raw.once('aborted',()=>controller.abort());return controller;};
  for(const kind of ['workout','period','proposal'] as const) {
    const path=kind==='proposal'?'/v1/proposals':'/v1/insights/'+kind;
    app.post(path,{preHandler:authorize},async(request,reply)=>{
      const state=begin(request,kind);if(state.cached)return state.cached;
      const controller=abortFor(request);reply.raw.once('close',()=>{if(!reply.raw.writableEnded)controller.abort();});
      try {
        const result=await provider.complete(state.data,kind,controller.signal);
        let value:unknown;
        if(kind==='proposal') {
          const candidate=parseProposal(result.text,state.data),version=state.data.context.expectedVersion??state.data.context.version;
          if(!Number.isSafeInteger(version)||Number(version)<0)throw new ApiError(400,'MISSING_VERSION','Не указана текущая версия плана или занятия.');
          value={...candidate,proposalId:randomUUID(),expectedVersion:version,scope:state.data.context.scope==='program'?'program':'session',expiresAt:now()+1800000,status:'proposed',...(result.usage?{usage:result.usage}:{})};
        }else value={...parseInsight(result.text,state.data),...(result.usage?{usage:result.usage}:{})};
        const cached={expiresAt:now()+600000,digest:state.digest!,value,pending:false};cache.set(state.key,cached);if(state.fingerprintKey)cache.set(state.fingerprintKey,cached);
        logUsage(request.id,result.usage);return value;
      }catch(error){cache.set(state.key,{expiresAt:now()+600000,digest:state.digest!,pending:false});throw error;}
    });
  }
  const logUsage=(requestId:string,usage:Usage|undefined)=>{if(usage)options.technicalLog?.({requestId,usage});};
  app.post('/v1/chat',{preHandler:authorize},async(request,reply)=>{
    const state=begin(request,'chat'),controller=abortFor(request);
    reply.raw.once('close',()=>{if(!reply.raw.writableEnded)controller.abort();});
    reply.hijack();reply.raw.writeHead(200,{'Content-Type':'text/event-stream; charset=utf-8','Cache-Control':'no-cache, no-transform','Connection':'keep-alive','X-Accel-Buffering':'no'});
    const send=async(event:string,value:unknown)=>{if(controller.signal.aborted)throw new ApiError(499,'CANCELLED','Запрос отменён.');if(!reply.raw.write(`event: ${event}\ndata: ${JSON.stringify(value)}\n\n`))await once(reply.raw,'drain',{signal:controller.signal});};
    const heartbeat=setInterval(()=>{if(!reply.raw.destroyed)reply.raw.write(': keep-alive\n\n');},15000);heartbeat.unref();
    try {const result=await provider.stream(state.data,controller.signal,text=>send('delta',{text}));await send('done',result);logUsage(request.id,result.usage);cache.set(state.key,{expiresAt:now()+600000,digest:state.digest!,pending:false});}
    catch(error){cache.set(state.key,{expiresAt:now()+600000,digest:state.digest!,pending:false});if(!controller.signal.aborted){const e=error instanceof ApiError?error:new ApiError(502,'PROVIDER_ERROR','Не удалось получить ответ ИИ.');await send('error',{code:e.code,message:e.publicMessage}).catch(()=>{});}}
    finally{clearInterval(heartbeat);reply.raw.end();}
  });
  return {app,auth};
}
declare module 'fastify' {interface FastifyRequest {sessionId:string}}
