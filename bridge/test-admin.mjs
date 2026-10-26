import assert from 'node:assert/strict';
import {EventEmitter} from 'node:events';
import {randomUUID,randomBytes} from 'node:crypto';
import fs from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import {AdminJobManager} from './admin-agent.mjs';
import {createBridge} from './craftgpt-codex-bridge.mjs';
import {CodexAppServerSession} from './codex-transport.mjs';

class FakeSession {
 constructor(){this.events=new EventEmitter();this.threadId=randomUUID();this.closed=false;}
 async initialize(){}
 async request(method,params){
  if(method==='model/list')return {data:[{id:'fixture',model:'fixture',supportedReasoningEfforts:[{reasoningEffort:'high'}]}]};
  if(method==='config/read')return {config:{mcp_servers:{external:{enabled:true}},plugins:{external:{enabled:true}}}};
  if(method==='thread/start'){
   assert.equal(params.permissions,':read-only');assert.equal(params.sandbox,undefined);
   assert.equal(params.config.features.shell_tool,false);
   assert.equal(params.config.mcp_servers.external.enabled,false);
   assert.equal(params.config.plugins.external.enabled,false);
   assert.ok(params.dynamicTools.some(tool=>tool.name==='server_edit_file'));
   assert.match(params.developerInstructions,/no command allowlist/);
   assert.match(params.developerInstructions,/Prefer a simple console command/);
   assert.match(params.developerInstructions,/multiple complicated commands/);
   assert.match(params.developerInstructions,/minecraft:give/);
   assert.match(params.developerInstructions,/temporary=true/);
   assert.match(params.developerInstructions,/including failure or cancellation/);
   assert.ok(params.dynamicTools.some(tool=>tool.name==='server_delete_temporary_file'));
   const create=params.dynamicTools.find(tool=>tool.name==='server_create_file');
   assert.equal(create.inputSchema.properties.temporary.type,'boolean');
   assert.ok(create.inputSchema.required.includes('temporary'));
   assert.ok(!params.developerInstructions.includes('Configured console command roots:'));
   return {thread:{id:this.threadId}};
  }
  if(method==='turn/start'){
   queueMicrotask(async()=>{
    try {
     const result=await this.handler({threadId:this.threadId,tool:'server_list',arguments:{path:'.'}});
     assert.equal(result.success,true);assert.ok(result.contentItems[0].text.includes('fixture.yml'));
     const turn={status:'completed',items:[{type:'agentMessage',id:'answer',text:'Listed fixture.yml.'}]};
     this.events.emit('notification',{method:'turn/completed',params:{threadId:this.threadId,turn}});
    }catch(error){this.events.emit('notification',{method:'bridge/error',params:{error:error.message}});}
   });
   return {turn:{status:'inProgress'}};
  }
  throw new Error('Unexpected method '+method);
 }
 registerDynamicToolHandler(id,handler){this.handler=handler;}
 unregisterDynamicToolHandler(){}
 close(){this.closed=true;this.events.emit('notification',{method:'bridge/error',params:{error:'Closed'}});}
}
const root=await fs.mkdtemp(path.join(os.tmpdir(),'craftgpt-admin-test-'));
await fs.mkdir(path.join(root,'plugins/CraftGPT'),{recursive:true});
const key=randomBytes(32).toString('hex');
await fs.writeFile(path.join(root,'plugins/CraftGPT/admin-bridge.key'),key);
const sessions=[];
const manager=new AdminJobManager({rootResolver:async()=>root,sessionFactory:()=>{const s=new FakeSession();sessions.push(s);return s;}});
const server=createBridge({adminManager:manager});
await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
const base='http://127.0.0.1:'+server.address().port;
const headers={'Authorization':'Bearer '+key,'Content-Type':'application/json'};
const body=id=>({id,prompt:'List fixtures.',model:'fixture',effort:'high',timeout_ms:30000,max_tool_calls:5});
const waitFor=async predicate=>{for(let i=0;i<100;i++){if(predicate())return;await new Promise(r=>setTimeout(r,5));}throw new Error('Fixture did not settle');};
try {
 const unauthorized=await fetch(base+'/admin/jobs',{method:'POST',body:JSON.stringify(body(randomUUID()))});
 assert.equal(unauthorized.status,401);
 const invalid=await fetch(base+'/admin/jobs',{method:'POST',headers,body:JSON.stringify({id:'bad'})});
 assert.equal(invalid.status,400);
 const id=randomUUID();
 assert.equal((await fetch(base+'/admin/jobs',{method:'POST',headers,body:JSON.stringify(body(id))})).status,202);
 await waitFor(()=>manager.status(id).tool);
 assert.equal((await fetch(base+'/admin/jobs',{method:'POST',headers,body:JSON.stringify(body(randomUUID()))})).status,409);
 const status=await (await fetch(base+'/admin/jobs/'+id,{headers})).json();
 assert.equal(status.tool.name,'server_list');
 const resultUrl=base+'/admin/jobs/'+id+'/tools/'+status.tool.id;
 const result={success:true,result:{entries:['fixture.yml']}};
 assert.equal((await fetch(resultUrl,{method:'POST',headers,body:JSON.stringify({success:true})})).status,400);
 assert.ok(manager.status(id).tool,'Malformed acknowledgements must preserve pending work');
 assert.equal((await fetch(resultUrl,{method:'POST',headers,body:JSON.stringify(result)})).status,200);
 await manager.jobs.get(id).finished;
 assert.equal(manager.status(id).status,'completed');
 assert.equal(manager.status(id).summary,'Listed fixture.yml.');
 assert.equal((await fetch(resultUrl,{method:'POST',headers,body:JSON.stringify(result)})).status,200,'Tool acknowledgements are idempotent');
 assert.ok(sessions[0].closed);
 const second=randomUUID();manager.start(body(second),root);
 await waitFor(()=>manager.status(second).tool);
 assert.throws(()=>manager.enqueue(manager.jobs.get(second),{tool:'shell',arguments:{command:'stop'}}),/Only server/);
 const command=manager.enqueue(manager.jobs.get(second),{tool:'server_command',arguments:{command:'time set day'}});
 command.catch(()=>{});
 assert.throws(()=>manager.enqueue(manager.jobs.get(second),{tool:'server_command',arguments:{command:'minecraft:time  set day'}}),/already attempted/);
 assert.equal((await fetch(base+'/admin/jobs/'+second,{method:'DELETE',headers})).status,200);
 await manager.jobs.get(second).finished;
 assert.equal(manager.status(second).status,'cancelled');
 assert.ok(sessions[1].closed);
 // Exercise the real transport callback: synchronous policy errors must become
 // tool failures, rather than escaping the readline callback and killing Node.
 const replies=[];
 const transport=Object.create(CodexAppServerSession.prototype);
 transport.dynamicToolHandlers=new Map();
 transport.send=reply=>replies.push(reply);
 transport.registerDynamicToolHandler('sync-failure',()=>{throw new Error('fixture validation failure');});
 assert.doesNotThrow(()=>transport.handleDynamicToolCall({id:71,params:{threadId:'sync-failure'}}));
 await waitFor(()=>replies.length===1);
 assert.equal(replies[0].result.success,false);
 assert.match(replies[0].result.contentItems[0].text,/fixture validation failure/);
 const budget=randomUUID();manager.start({...body(budget),max_tool_calls:1},root);
 await waitFor(()=>manager.status(budget).tool);
 transport.registerDynamicToolHandler(sessions.at(-1).threadId,params=>manager.enqueue(manager.jobs.get(budget),params));
 assert.doesNotThrow(()=>transport.handleDynamicToolCall({id:72,params:{threadId:sessions.at(-1).threadId,tool:'server_list',arguments:{path:'.'}}}));
 await waitFor(()=>replies.length===2);
 await manager.jobs.get(budget).finished;
 assert.equal(manager.status(budget).status,'failed');
 assert.match(manager.status(budget).error,/tool-call budget exhausted/);
 assert.equal(replies[1].result.success,false);
 assert.equal((await fetch(base+'/health')).status,200,'Budget exhaustion must leave the bridge available');
 const afterBudget=randomUUID();
 manager.start(body(afterBudget),root);
 await waitFor(()=>manager.status(afterBudget).tool);
 const pending=manager.status(afterBudget).tool;
 manager.result(afterBudget,pending.id,{success:true,result:{entries:['fixture.yml']}});
 await manager.jobs.get(afterBudget).finished;
 assert.equal(manager.status(afterBudget).status,'completed','A later task must still work after budget exhaustion');
 console.log('PASS: authenticated admin HTTP jobs, dynamic tools, restricted config, cancellation, exclusive jobs, command replay protection, and idempotent results. No live server or Codex calls.');
}finally{
 manager.close();await Promise.all([...manager.jobs.values()].map(job=>job.finished));
 await new Promise(resolve=>server.close(resolve));
 await fs.rm(root,{recursive:true,force:true});
}