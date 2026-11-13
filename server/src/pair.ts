import { AuthStore } from './auth.js';
import { loadConfig } from './config.js';
const store=new AuthStore(loadConfig());
if(process.argv.includes('--revoke-all')){store.revokeAll();console.log('Все подключения и коды отозваны.');}
else {const code=store.issueCode();console.log(`Одноразовый код: ${code.code}\nДействителен до: ${new Date(code.expiresAt).toISOString()}\nВведите его только на своём устройстве. Код не передавайте в логи сервера.`);}
