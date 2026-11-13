import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdirSync, mkdtempSync, readFileSync, rmSync } from 'node:fs';
import { join } from 'node:path';
import { randomUUID } from 'node:crypto';
import { createApp } from '../src/app.js';
import { loadConfig } from '../src/config.js';
import { AuthStore } from '../src/auth.js';
import { DeepSeekProvider, type AiProvider } from '../src/provider.js';
import { assertContext, parseInsight, parseProposal, type AiRequest } from '../src/schema.js';

class TestProvider implements AiProvider {
  calls=0; content='{"text":"Выполнен один рабочий подход.","evidenceIds":["s1"],"sourceIds":["ace-leg"]}';
  async complete(){this.calls++;return {text:this.content,usage:{total_tokens:25}};}
  async stream(_request:AiRequest,signal:AbortSignal,onDelta:(s:string)=>Promise<void>){this.calls++;if(signal.aborted)throw new Error('aborted');await onDelta('Короткий ');await onDelta('ответ.');return {usage:{total_tokens:10}};}
}
function fixture(overrides:Record<string,string>={}){
  mkdirSync('.test-state',{recursive:true});const directory=mkdtempSync(join(process.cwd(),'.test-state','trenirovki-'));let now=1000000;
  const config=loadConfig({STATE_FILE:join(directory,'auth.json'),...overrides}),provider=new TestProvider();
  const result=createApp(config,{provider,now:()=>now});
  return {...result,provider,config,directory,advance:(ms:number)=>{now+=ms;},cleanup:async()=>{await result.app.close();rmSync(directory,{recursive:true,force:true});}};
}
const request=():AiRequest=>({requestId:randomUUID(),context:{expectedVersion:3,scope:'session',exerciseIds:['leg-press'],evidenceIds:['s1'],sourceIds:['ace-leg']},messages:[],prompt:'Разбери занятие',fingerprint:'facts-v1'});
async function connect(f:ReturnType<typeof fixture>){const code=f.auth.issueCode();const response=await f.app.inject({method:'POST',url:'/v1/pair',payload:{code:code.code,deviceName:'Тестовый телефон'}});assert.equal(response.statusCode,200);return response.json() as {accessToken:string,refreshToken:string,expiresAt:number};}

test('health and real provider report missing key without fake answers',async()=>{
  const f=fixture();try {const {app}=createApp(f.config,{now:()=>1000000});try {const h=await app.inject('/health');assert.equal(h.json().aiConfigured,false);const code=f.auth.issueCode();const auth=await app.inject({method:'POST',url:'/v1/pair',payload:{code:code.code,deviceName:'phone'}});const response=await app.inject({method:'POST',url:'/v1/chat',headers:{authorization:'Bearer '+auth.json().accessToken},payload:request()});assert.equal(response.statusCode,503);assert.equal(response.json().error.code,'AI_NOT_CONFIGURED');}finally{await app.close();}}finally{await f.cleanup();}
});
test('one-time code, access expiry, refresh rotation, revoke, hashed persisted secrets',async()=>{
 const f=fixture();try {const code=f.auth.issueCode(),tokens=f.auth.pair(code.code,'phone');assert.throws(()=>f.auth.pair(code.code,'phone'));assert.ok(f.auth.authorize('Bearer '+tokens.accessToken));const saved=readFileSync(f.config.STATE_FILE,'utf8');for(const secret of [tokens.accessToken,tokens.refreshToken,code.code])assert.equal(saved.includes(secret),false);f.advance(901000);assert.throws(()=>f.auth.authorize('Bearer '+tokens.accessToken));const rotated=f.auth.refresh(tokens.refreshToken);assert.throws(()=>f.auth.refresh(tokens.refreshToken));const id=f.auth.authorize('Bearer '+rotated.accessToken);f.auth.revoke(id);assert.throws(()=>f.auth.refresh(rotated.refreshToken));assert.throws(()=>f.auth.authorize('Bearer '+rotated.accessToken));}finally{await f.cleanup();}
});
test('expired pair and refresh are rejected, store survives restart',async()=>{
 const f=fixture({REFRESH_TTL_DAYS:'1'});try {const code=f.auth.issueCode();f.advance(301000);assert.throws(()=>f.auth.pair(code.code,'phone'));const valid=f.auth.issueCode();const tokens=f.auth.pair(valid.code,'phone');const reopened=new AuthStore(f.config,()=>1301000);assert.ok(reopened.authorize('Bearer '+tokens.accessToken));f.advance(86401000);assert.throws(()=>f.auth.refresh(tokens.refreshToken));}finally{await f.cleanup();}
});
test('revoke by refresh works with expired access and is idempotent',async()=>{
 const f=fixture();try {const tokens=await connect(f);f.advance(901000);const headers={authorization:'Bearer '+tokens.accessToken},payload={refreshToken:tokens.refreshToken};assert.equal((await f.app.inject({method:'POST',url:'/v1/session/revoke',headers,payload})).statusCode,200);assert.throws(()=>f.auth.refresh(tokens.refreshToken));assert.equal((await f.app.inject({method:'POST',url:'/v1/session/revoke',headers,payload})).statusCode,200);}finally{await f.cleanup();}
});
test('pair brute-force rate limiting includes invalid schema attempts',async()=>{
 const f=fixture({PAIR_ATTEMPTS_PER_10_MINUTES:'2'});try {for(let i=0;i<2;i++)assert.equal((await f.app.inject({method:'POST',url:'/v1/pair',payload:{code:'bad',deviceName:'phone'}})).statusCode,400);const valid=f.auth.issueCode();assert.equal((await f.app.inject({method:'POST',url:'/v1/pair',payload:{code:valid.code,deviceName:'phone'}})).statusCode,429);}finally{await f.cleanup();}
});
test('AI auth, request rate, idempotency, unchanged facts cache, changed context no false cache',async()=>{
 const f=fixture({REQUESTS_PER_MINUTE:'2'});try {assert.equal((await f.app.inject({method:'POST',url:'/v1/insights/workout',payload:request()})).statusCode,401);const tokens=await connect(f),headers={authorization:'Bearer '+tokens.accessToken},payload=request();const first=await f.app.inject({method:'POST',url:'/v1/insights/workout',headers,payload});assert.equal(first.statusCode,200);assert.equal(first.json().usage.total_tokens,25);const repeat=await f.app.inject({method:'POST',url:'/v1/insights/workout',headers,payload});assert.deepEqual(repeat.json(),first.json());assert.equal(f.provider.calls,1);const cached=await f.app.inject({method:'POST',url:'/v1/insights/workout',headers,payload:{...payload,requestId:randomUUID()}});assert.equal(cached.statusCode,200);assert.equal(f.provider.calls,1);const changed={...payload,requestId:randomUUID(),context:{...payload.context,expectedVersion:4}};assert.equal((await f.app.inject({method:'POST',url:'/v1/insights/workout',headers,payload:changed})).statusCode,200);assert.equal(f.provider.calls,2);assert.equal((await f.app.inject({method:'POST',url:'/v1/insights/period',headers,payload:request()})).statusCode,429);assert.equal((await f.app.inject({method:'POST',url:'/v1/insights/workout',headers,payload:{...payload,prompt:'changed'}})).statusCode,409);}finally{await f.cleanup();}
});
test('context bounds and private fields reject before provider',()=>{
 assert.throws(()=>assertContext({...request(),context:{accessToken:'secret'}},24000));assert.throws(()=>assertContext({...request(),context:{notes:'a'.repeat(25000)}},24000));assert.doesNotThrow(()=>assertContext(request(),24000));
});
test('structured JSON rejects blank, truncated, unknown operation/exercise, unknown evidence/source',()=>{
 const r=request();for(const text of ['', '   ', '{"summary":', '{"text":"ok","evidenceIds":["invented"],"sourceIds":[]}', '{"text":"ok","evidenceIds":[],"sourceIds":["fake-url"]}'])assert.throws(()=>parseInsight(text,r));
 for(const operation of [{type:'delete',exerciseId:'leg-press',previous:'3',value:'2'},{type:'reduceSets',exerciseId:'unknown',previous:'3',value:'2'}])assert.throws(()=>parseProposal(JSON.stringify({summary:'a',reason:'b',evidenceIds:[],operations:[operation]}),r));
});
test('proposal service owns version, scope, expiry, ID; invalid replies never mutate phone facts',async()=>{
 const f=fixture();try {const tokens=await connect(f),headers={authorization:'Bearer '+tokens.accessToken};f.provider.content=JSON.stringify({summary:'Уменьшить объём',reason:'Осталось мало времени',evidenceIds:['s1'],operations:[{type:'reduceSets',exerciseId:'leg-press',previous:'3',value:'2'}]});const response=await f.app.inject({method:'POST',url:'/v1/proposals',headers,payload:request()});assert.equal(response.statusCode,200);const value=response.json();assert.equal(value.expectedVersion,3);assert.equal(value.status,'proposed');assert.equal(value.scope,'session');assert.ok(value.expiresAt>1000000);assert.match(value.proposalId,/^[a-f0-9-]{36}$/);f.provider.content='{';const bad=request();bad.fingerprint='changed';assert.equal((await f.app.inject({method:'POST',url:'/v1/proposals',headers,payload:bad})).statusCode,502);assert.equal((await f.app.inject({method:'POST',url:'/v1/proposals',headers,payload:bad})).statusCode,409);}finally{await f.cleanup();}
});
test('SSE delta/done and duplicate prevention',async()=>{
 const f=fixture();try {const tokens=await connect(f),payload=request(),headers={authorization:'Bearer '+tokens.accessToken};const response=await f.app.inject({method:'POST',url:'/v1/chat',headers,payload});assert.equal(response.statusCode,200);assert.match(response.headers['content-type'] as string,/text\/event-stream/);assert.match(response.body,/event: delta/);assert.match(response.body,/event: done/);assert.match(response.body,/total_tokens/);assert.equal((await f.app.inject({method:'POST',url:'/v1/chat',headers,payload})).statusCode,409);}finally{await f.cleanup();}
});
test('real DeepSeek adapter forwards cancellation and checks completion/truncated JSON',async()=>{
 const config=loadConfig({DEEPSEEK_API_KEY:'test-key'});let captured:RequestInit|undefined;
 const provider=new DeepSeekProvider(config,async(_url,init)=>{captured=init;return new Response(JSON.stringify({choices:[{finish_reason:'length',message:{content:'{"text":'}}]}));});
 await assert.rejects(()=>provider.complete(request(),'workout',new AbortController().signal));assert.equal((JSON.parse(captured?.body as string)).model,'deepseek-flash');assert.deepEqual((JSON.parse(captured?.body as string)).response_format,{type:'json_object'});
 const controller=new AbortController();let aborted=false;
 const slow=new DeepSeekProvider(config,async(_url,init)=>new Promise((_resolve,reject)=>{init?.signal?.addEventListener('abort',()=>{aborted=true;reject(new DOMException('Aborted','AbortError'));});}));
 const pending=slow.complete(request(),'workout',controller.signal);controller.abort();await assert.rejects(pending);assert.equal(aborted,true);
});
test('real streaming adapter rejects missing terminator and empty stream; emits only content',async()=>{
 const config=loadConfig({DEEPSEEK_API_KEY:'test-key'}),chunks:string[]=[];
 const stream='data: {"choices":[{"delta":{"reasoning_content":"hidden","content":"Привет"},"finish_reason":null}]}\n\ndata: {"choices":[{"delta":{},"finish_reason":"stop"}],"usage":{"total_tokens":8}}\n\ndata: [DONE]\n\n';
 const provider=new DeepSeekProvider(config,async()=>new Response(stream));const result=await provider.stream(request(),new AbortController().signal,async s=>{chunks.push(s);});assert.deepEqual(chunks,['Привет']);assert.equal(result.usage?.total_tokens,8);
 for(const body of ['',stream.replace('data: [DONE]','')])await assert.rejects(()=>new DeepSeekProvider(config,async()=>new Response(body)).stream(request(),new AbortController().signal,async()=>{}));
});
test('HTTP from remote address rejected',async()=>{
 const f=fixture();try {const remote=await f.app.inject({method:'GET',url:'/health',remoteAddress:'192.168.1.3'});assert.equal(remote.statusCode,426);}finally{await f.cleanup();}
});
test('technical logs exclude messages, notes, tokens and pairing codes',async()=>{
 const f=fixture(),records:Record<string,unknown>[]=[];const {app,auth}=createApp(f.config,{provider:f.provider,now:()=>1000000,technicalLog:r=>records.push(r)});
 try {const code=auth.issueCode(),tokens=auth.pair(code.code,'phone'),payload=request();payload.prompt='PRIVATE-QUESTION';payload.context.notes='PRIVATE-NOTE';await app.inject({method:'POST',url:'/v1/insights/workout',headers:{authorization:'Bearer '+tokens.accessToken},payload});const log=JSON.stringify(records);assert.ok(records.length>=2);for(const value of [payload.prompt,'PRIVATE-NOTE',code.code,tokens.accessToken,tokens.refreshToken])assert.equal(log.includes(value),false);assert.match(log,/total_tokens/);assert.match(log,/latencyMs/);}finally{await app.close();await f.cleanup();}
});
test('malformed JSON rejected as client error before provider',async()=>{
 const f=fixture();try {const response=await f.app.inject({method:'POST',url:'/v1/pair',headers:{'content-type':'application/json'},payload:'{"code":'});assert.equal(response.statusCode,400);assert.equal(f.provider.calls,0);}finally{await f.cleanup();}
});

