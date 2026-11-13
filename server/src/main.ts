import { createApp } from './app.js';
import { loadConfig } from './config.js';
const config=loadConfig();
const {app}=createApp(config,{technicalLog:record=>process.stdout.write(JSON.stringify(record)+'\n')});
try {await app.listen({host:config.HOST,port:config.PORT});process.stdout.write(JSON.stringify({status:'listening',host:config.HOST,port:config.PORT,aiConfigured:!!config.DEEPSEEK_API_KEY})+'\n');}
catch {process.stderr.write('Не удалось запустить сервер. Проверьте конфигурацию и порт.\n');process.exitCode=1;}
for(const signal of ['SIGINT','SIGTERM'] as const)process.once(signal,()=>void app.close());
