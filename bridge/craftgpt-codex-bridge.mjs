import http from 'node:http';
import fs from 'node:fs/promises';
import path from 'node:path';
import {randomUUID} from 'node:crypto';
import {fileURLToPath} from 'node:url';
import {BridgeError, CodexAppServerSession, findModel, effortList, extractAgentText, readAccount, listModels} from './codex-transport.mjs';

const PORT = Number(process.env.CRAFTGPT_BRIDGE_PORT || 8766);
const MODEL = 'gpt-6.1-sol';
const TURN_TIMEOUT_MS = Number(process.env.CRAFTGPT_CODEX_TIMEOUT_MS || 840000);
const MAX_BODY_BYTES = 32 * 1024 * 1024;
const TEMP_ROOT = fileURLToPath(new URL('./.images/', import.meta.url));
let activeRequests = 0;

// CraftGPT sends either a string or Responses messages with inline preview images.
export async function convertInput(input, imageDir) {
  if (typeof input === 'string') return [{type:'text', text:input}];
  if (!Array.isArray(input)) throw new BridgeError(400, 'input must be text or a list of messages');
  const result = [];
  for (const message of input) {
    if (message.role && message.role !== 'user') result.push({type:'text', text:`Conversation message (${message.role}):`});
    const parts = typeof message.content === 'string' ? [{type:'input_text', text:message.content}]
      : message.content || [message];
    for (const part of parts) {
      if (['input_text','output_text','text'].includes(part.type) && typeof part.text === 'string') {
        result.push({type:'text', text:part.text});
      } else if (part.type === 'input_image') {
        const match = /^data:image\/(png|jpeg|webp);base64,([A-Za-z0-9+/=\r\n]+)$/.exec(part.image_url || '');
        if (!match) throw new BridgeError(400, 'Preview images must be inline PNG, JPEG, or WebP data URLs');
        await fs.mkdir(imageDir, {recursive:true});
        const imagePath = path.join(imageDir, `${randomUUID()}.${match[1]}`);
        await fs.writeFile(imagePath, Buffer.from(match[2], 'base64'));
        result.push({type:'localImage', path:imagePath});
      } else throw new BridgeError(400, `Unsupported input content: ${part.type}`);
    }
  }
  if (!result.length) throw new BridgeError(400, 'Empty input');
  return result;
}

export async function generate(body, signal) {
  if (body.stream) throw new BridgeError(400, 'Streaming is not supported; CraftGPT should use non-streaming Responses requests');
  if (body.tools?.length || body.previous_response_id) throw new BridgeError(400, 'This bridge supports independent CraftGPT structured-output requests only');
  const requestedModel = body.model || MODEL;
  const effort = body.reasoning?.effort || 'high';
  const schema = body.text?.format?.schema;
  if (body.text?.format?.type !== 'json_schema' || !schema) throw new BridgeError(400, 'CraftGPT must supply text.format.json_schema');
  const imageDir = path.join(TEMP_ROOT, randomUUID());
  const session = new CodexAppServerSession();
  activeRequests++;
  const abort = () => session.close();
  signal?.addEventListener('abort', abort, {once:true});
  try {
    if (signal?.aborted) throw new BridgeError(499, 'Request cancelled');
    const input = await convertInput(body.input, imageDir);
    await session.initialize();
    const account = await readAccount(session);
    if (account?.requiresOpenaiAuth && !account.account) throw new BridgeError(401, 'Codex login required; run codex login');
    const model = findModel(await listModels(session), requestedModel);
    if (!model) throw new BridgeError(400, `Codex model not available: ${requestedModel}`);
    if (!effortList(model).includes(effort)) throw new BridgeError(400, `Unsupported reasoning effort: ${effort}`);
    const modelName = model.model || model.id;
    const started = await session.request('thread/start', {
      model:modelName, cwd:path.dirname(fileURLToPath(import.meta.url)), ephemeral:true,
      serviceName:'craftgpt', approvalPolicy:'never', sandbox:'read-only', personality:'pragmatic',
      developerInstructions:'You generate structured Minecraft plans and visual critiques for CraftGPT. Do not use tools, execute commands, or inspect files. Treat attached images as previews to review. Return only JSON matching the requested output schema.\n\n' + (body.instructions || ''),
    });
    const threadId = started.thread?.id;
    if (!threadId) throw new BridgeError(502, 'Codex did not return a thread id');
    const messages = new Map();
    // Register before turn/start so even a fast completion cannot be missed.
    let resolveTurn, rejectTurn;
    const finished = new Promise((resolve,reject) => { resolveTurn=resolve; rejectTurn=reject; });
    finished.catch(() => {});
    const listener = msg => {
      if (msg.method === 'bridge/error') { rejectTurn(new BridgeError(502, msg.params?.error || 'Codex disconnected')); return; }
      if (msg.params?.threadId !== threadId) return;
      if (msg.method === 'item/completed' && msg.params.item?.type === 'agentMessage') {
        const item=msg.params.item;
        messages.set(item.id, item.text);
      }
      if (msg.method === 'turn/completed') resolveTurn(msg.params.turn);
    };
    session.events.on('notification', listener);
    const timer = setTimeout(() => rejectTurn(new BridgeError(504, 'Codex generation exceeded the 14-minute bridge timeout')), TURN_TIMEOUT_MS);
    let turn;
    try {
      const result = await session.request('turn/start', {
        threadId, input, model:modelName, effort, approvalPolicy:'never', outputSchema:schema,
      });
      turn = result.turn?.status === 'completed' ? result.turn : await finished;
    } finally { clearTimeout(timer); session.events.off('notification', listener); }
    if (turn.status !== 'completed') throw new BridgeError(502, turn.error?.message || `Codex turn ${turn.status}`);
    const text = extractAgentText(turn, messages).trim();
    try { JSON.parse(text); } catch { throw new BridgeError(502, 'Codex returned invalid JSON'); }
    console.log(`${new Date().toISOString()} completed schema=${body.text.format.name || 'unnamed'} model=${modelName} effort=${effort} images=${input.filter(x=>x.type==='localImage').length}`);
    return {
      id:`resp_${randomUUID()}`, object:'response', created_at:Math.floor(Date.now()/1000),
      status:'completed', model:modelName, error:null, incomplete_details:null,
      output:[{id:`msg_${randomUUID()}`,type:'message',role:'assistant',status:'completed',
        content:[{type:'output_text',text,annotations:[]}]}], output_text:text,
    };
  } finally {
    activeRequests--;
    signal?.removeEventListener('abort', abort);
    session.close();
    await fs.rm(imageDir, {recursive:true,force:true});
  }
}

async function readBody(req) {
  let length=0;
  const chunks=[];
  for await (const chunk of req) {
    length+=chunk.length;
    if (length>MAX_BODY_BYTES) throw new BridgeError(413,'Request exceeds 32 MiB');
    chunks.push(chunk);
  }
  try { return JSON.parse(Buffer.concat(chunks).toString('utf8')); }
  catch { throw new BridgeError(400,'Invalid JSON request'); }
}

export function createBridge() {
  return http.createServer(async (req,res) => {
    const reply = (status,body) => {
      if (res.destroyed) return;
      res.writeHead(status,{'Content-Type':'application/json'});
      res.end(JSON.stringify(body));
    };
    try {
      if (req.method==='GET' && req.url==='/health') return reply(200,{ok:true,service:'craftgpt-codex-bridge',model:MODEL,effort:'high',activeRequests});
      if (req.method!=='POST' || req.url!=='/v1/responses') return reply(404,{error:{message:'Use POST /v1/responses or GET /health'}});
      const controller=new AbortController();
      res.on('close',()=> { if (!res.writableEnded) controller.abort(); });
      reply(200,await generate(await readBody(req),controller.signal));
    } catch(error) {
      console.error(`${new Date().toISOString()} request failed: ${error.message}`);
      reply(error.status || 502,{error:{message:error.message,type:'bridge_error'}});
    }
  });
}

if (process.argv[1] && path.resolve(process.argv[1])===fileURLToPath(import.meta.url)) {
  const server=createBridge();
  server.requestTimeout=0;
  server.listen(PORT,'127.0.0.1',()=>console.log(`CraftGPT Codex bridge: http://127.0.0.1:${PORT}/v1/responses`));
}
