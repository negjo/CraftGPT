import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import path from 'node:path';
import {convertInput, createBridge} from './craftgpt-codex-bridge.mjs';
const temp=path.resolve('diagnostics/bridge-test-images');
try {
  assert.deepEqual(await convertInput('hello',temp),[{type:'text',text:'hello'}]);
  const parts=await convertInput([{role:'user',content:[{type:'input_text',text:'Review this'},{type:'input_image',image_url:'data:image/png;base64,aGVsbG8='}]}],temp);
  assert.equal(parts[1].type,'localImage');
  assert.equal(await fs.readFile(parts[1].path,'utf8'),'hello');
  await assert.rejects(convertInput([{type:'input_image',image_url:'file:///private.png'}],temp),/inline/);
  await assert.rejects(convertInput([{type:'unsupported'}],temp),/Unsupported/);
  const server=createBridge();
  await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
  try {
    const base=`http://127.0.0.1:${server.address().port}`;
    const health=await (await fetch(base+'/health')).json();
    assert.equal(health.service,'craftgpt-codex-bridge');
    const bad=await fetch(base+'/v1/responses',{method:'POST',body:'not json'});
    assert.equal(bad.status,400);
    const streaming=await fetch(base+'/v1/responses',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({stream:true})});
    assert.equal(streaming.status,400);
  } finally { await new Promise(resolve=>server.close(resolve)); }
  console.log('PASS: Responses text/image translation, input rejection, health, and HTTP errors.');
} finally { await fs.rm(temp,{recursive:true,force:true}); }
