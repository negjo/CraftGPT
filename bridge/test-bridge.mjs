import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import path from 'node:path';
import {convertInput, createBridge} from './craftgpt-codex-bridge.mjs';
import {checkSchema,validateOutput,structuredTurnInput} from './schema-validation.mjs';
const temp=path.resolve('diagnostics/bridge-test-images');
try {
  const schema={type:'object',additionalProperties:false,required:['name','points','enabled'],properties:{
    name:{type:'string',minLength:1,maxLength:4,pattern:'^[A-Z]+$'},
    points:{type:'array',minItems:1,maxItems:2,items:{anyOf:[{type:'integer',minimum:0,maximum:10},{type:'string',enum:['air']}]}},
    enabled:{type:'boolean'}}};
  checkSchema(schema);
  const valid={name:'AB',points:[1,'air'],enabled:true};
  validateOutput(valid,schema);
  for(const invalid of [null,[],{}, {...valid,extra:1},{...valid,name:''},{...valid,name:'AAAAA'},
    {...valid,name:'lower'},{...valid,points:[]},{...valid,points:[1,2,3]},{...valid,points:[-1]},
    {...valid,points:[11]},{...valid,points:[1.5]},{...valid,points:['water']},{...valid,enabled:1}]) {
    assert.throws(()=>validateOutput(invalid,schema));
  }
  assert.throws(()=>checkSchema({type:'string',unsupportedConstraint:true}),/Unsupported/);
  const plain=[{type:'text',text:'Plan a house'}];
  assert.equal(structuredTurnInput(plain,schema).outputSchema,schema);
  const visual=[...plain,{type:'localImage',path:'preview.png'}];
  const prepared=structuredTurnInput(visual,schema);
  assert.equal(prepared.outputSchema,undefined);
  assert.deepEqual(prepared.input.slice(0,2),visual);
  assert.ok(prepared.input.at(-1).text.includes(JSON.stringify(schema)));
  assert.equal(visual.length,2,'Preparing schema instructions must not mutate the original input');
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
  console.log('PASS: schema validation, image/native schema routing, Responses text/image translation, input rejection, health, and HTTP errors.');
} finally { await fs.rm(temp,{recursive:true,force:true}); }
