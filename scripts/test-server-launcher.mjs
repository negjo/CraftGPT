import assert from 'node:assert/strict';
import {RestartSupervisor} from './server-launcher.mjs';
let count=0;
let supervisor;
supervisor=new RestartSupervisor(async()=> {
  count++;
  if(count===1) { supervisor.requestRestart(); return {code:0,signal:null}; }
  return {code:0,signal:null};
},()=>{});
assert.equal(await supervisor.run(),0);
assert.equal(count,2,'Ctrl+C requests exactly one restart after shutdown completes');
count=0;
const native=new RestartSupervisor(async()=>({code:++count===1?130:0,signal:null}),()=>{});
assert.equal(await native.run(),0);
assert.equal(count,2,'JVM interrupt exit restarts even if native handler wins the race');
count=0;
const normal=new RestartSupervisor(async()=> {count++; return {code:0,signal:null};},()=>{});
await normal.run();
assert.equal(count,1,'Normal stop must exit instead of restarting');
const failed=new RestartSupervisor(async()=>({code:1,signal:null}),()=>{});
assert.equal(await failed.run(),1,'Startup errors must not cause an infinite restart loop');
console.log('PASS: requested restart, native interrupt, normal stop, and crash behavior. No Minecraft process started.');