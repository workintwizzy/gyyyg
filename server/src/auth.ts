import { createHash, randomBytes, randomInt, randomUUID, timingSafeEqual } from 'node:crypto';
import { existsSync, mkdirSync, readFileSync, renameSync, writeFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { z } from 'zod';
import type { Config } from './config.js';
import { ApiError } from './errors.js';

const sessionSchema = z.object({id:z.string(), deviceName:z.string(), accessHash:z.string(),refreshHash:z.string(),accessExpiresAt:z.number(),refreshExpiresAt:z.number()});
const stateSchema = z.object({version:z.literal(1),codes:z.array(z.object({hash:z.string(),expiresAt:z.number()})),sessions:z.array(sessionSchema)});
type Session = z.infer<typeof sessionSchema>;
const hash = (s: string) => createHash('sha256').update(s).digest('hex');
const matches = (value:string, stored:string) => { const a=Buffer.from(hash(value),'hex'), b=Buffer.from(stored,'hex'); return a.length===b.length && timingSafeEqual(a,b); };
export class AuthStore {
  private path:string;
  constructor(private config:Config, private now:()=>number=Date.now) { this.path=resolve(config.STATE_FILE); }
  private read() {
    const state=existsSync(this.path) ? stateSchema.parse(JSON.parse(readFileSync(this.path,'utf8'))) : {version:1 as const,codes:[],sessions:[]};
    const count=state.codes.length+state.sessions.length;
    state.codes=state.codes.filter(c=>c.expiresAt>this.now());
    state.sessions=state.sessions.filter(s=>s.refreshExpiresAt>this.now());
    if(state.codes.length+state.sessions.length!==count)this.write(state);
    return state;
  }
  private write(state:z.infer<typeof stateSchema>) {
    mkdirSync(dirname(this.path),{recursive:true,mode:0o700});
    const temporary=this.path+'.'+randomUUID()+'.tmp';
    writeFileSync(temporary,JSON.stringify(state),{mode:0o600}); renameSync(temporary,this.path);
  }
  issueCode() {
    const alphabet='ABCDEFGHJKLMNPQRSTUVWXYZ23456789';
    const code=Array.from({length:10},()=>alphabet[randomInt(alphabet.length)]).join('');
    const state=this.read(); const expiresAt=this.now()+this.config.PAIR_TTL_SECONDS*1000;
    state.codes.push({hash:hash(code),expiresAt}); state.codes=state.codes.slice(-5); this.write(state);
    return {code,expiresAt};
  }
  private tokens(session:Session) {
    const accessToken=randomBytes(32).toString('base64url'),refreshToken=randomBytes(48).toString('base64url');
    session.accessHash=hash(accessToken); session.refreshHash=hash(refreshToken);
    session.accessExpiresAt=this.now()+this.config.ACCESS_TTL_SECONDS*1000;
    return {accessToken,refreshToken,expiresAt:session.accessExpiresAt};
  }
  pair(code:string,deviceName:string) {
    const state=this.read(), index=state.codes.findIndex(c=>matches(code,c.hash));
    if (index<0) throw new ApiError(401,'INVALID_PAIR_CODE','Код неверен или истёк.');
    state.codes.splice(index,1);
    const session:Session={id:randomUUID(),deviceName,accessHash:'',refreshHash:'',accessExpiresAt:0,refreshExpiresAt:this.now()+this.config.REFRESH_TTL_DAYS*86400000};
    const result=this.tokens(session); state.sessions.push(session); this.write(state); return result;
  }
  refresh(token:string) {
    const state=this.read(),session=state.sessions.find(s=>matches(token,s.refreshHash));
    if (!session) throw new ApiError(401,'SESSION_INVALID','Подключение истекло. Подключите устройство заново.');
    const result=this.tokens(session); this.write(state); return result;
  }
  authorize(header:string|undefined) {
    const token=header?.startsWith('Bearer ')?header.slice(7):'';
    if (!token || token.length>256) throw new ApiError(401,'SESSION_INVALID','Требуется подключение устройства.');
    const session=this.read().sessions.find(s=>s.accessExpiresAt>this.now() && matches(token,s.accessHash));
    if (!session) throw new ApiError(401,'SESSION_INVALID','Сессия истекла или отозвана.');
    return session.id;
  }
  revoke(id:string) {const state=this.read();state.sessions=state.sessions.filter(s=>s.id!==id);this.write(state);}
  revokeByRefresh(token:string) {const state=this.read();state.sessions=state.sessions.filter(s=>!matches(token,s.refreshHash));this.write(state);}
  revokeAll() {this.write({version:1,codes:[],sessions:[]});}
  prune() {this.read();}
}
export class Limiter {
  private entries=new Map<string,{count:number,end:number}>();
  constructor(private now:()=>number=Date.now) {}
  take(key:string,limit:number,windowMs:number) {
    for (const [k,v] of this.entries) if(v.end<=this.now())this.entries.delete(k);
    if(this.entries.size>10000)throw new ApiError(429,'RATE_LIMIT','Слишком много запросов. Повторите позже.');
    const entry=this.entries.get(key)??{count:0,end:this.now()+windowMs};entry.count++;this.entries.set(key,entry);
    if(entry.count>limit)throw new ApiError(429,'RATE_LIMIT','Лимит запросов исчерпан. Повторите позже.');
  }
}
