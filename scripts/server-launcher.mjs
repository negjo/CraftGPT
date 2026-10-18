import {spawn, spawnSync} from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import net from 'node:net';
import {fileURLToPath} from 'node:url';

export class RestartSupervisor {
  constructor(launch, log=console.log) { this.launch=launch; this.log=log; this.restart=false; this.active=false; }
  requestRestart() {
    if (!this.active) return;
    this.restart=true;
    this.log('Restart requested. Waiting for Minecraft to finish saving...');
  }
  async run() {
    for (;;) {
      this.restart=false;
      this.active=true;
      let result;
      try { result=await this.launch(); } finally { this.active=false; }
      const interrupted=result.signal==='SIGINT' || [130, -1073741510, 3221225786].includes(result.code);
      if (!this.restart && !interrupted) return result.code || 0;
      this.log('Server stopped. Restarting in the same console...');
    }
  }
}

function waitForExit(child) {
  return new Promise((resolve,reject)=> {
    child.once('error',reject);
    child.once('exit',(code,signal)=>resolve({code,signal}));
  });
}
async function portInUse(port) {
  return await new Promise(resolve=> {
    const socket=net.createConnection({host:'127.0.0.1',port});
    const finish=value=> { socket.destroy(); resolve(value); };
    socket.once('connect',()=>finish(true));
    socket.once('error',()=>finish(false));
    socket.setTimeout(500,()=>finish(false));
  });
}
async function health() {
  try { return await (await fetch('http://127.0.0.1:8766/health',{signal:AbortSignal.timeout(3000)})).json(); }
  catch { return null; }
}
async function main() {
  const root=path.resolve(process.argv[2] || path.dirname(fileURLToPath(import.meta.url)));
  const props=fs.readFileSync(path.join(root,'server.properties'),'utf8');
  const port=Number(/^server-port=(\d+)/m.exec(props)?.[1] || 25565);
  if (await portInUse(port)) { console.log(`A server is already running on localhost:${port}. No second instance started.`); return; }
  const init=spawnSync('powershell.exe',['-NoProfile','-ExecutionPolicy','Bypass','-Command',
    "$ErrorActionPreference='Stop'; $root=$env:CRAFTGPT_SERVER_DIR; $line=Get-Content (Join-Path $root 'server.properties') | Where-Object {$_ -match '^level-name='} | Select-Object -First 1; $level=if($line){$line -replace '^level-name=',''}else{'world'}; $lock=Join-Path (Join-Path $root $level) 'session.lock'; if(Test-Path -LiteralPath $lock){$probe=[IO.File]::Open($lock,[IO.FileMode]::Open,[IO.FileAccess]::ReadWrite,[IO.FileShare]::None);$probe.Dispose()}; . (Join-Path $root 'runtime-paths.ps1'); @{codex=$env:CODEX_BIN} | ConvertTo-Json -Compress"
  ],{cwd:root,env:{...process.env,CRAFTGPT_SERVER_DIR:root},encoding:'utf8',windowsHide:true});
  if (init.status!==0) throw new Error(init.stderr || 'Launcher initialization failed. Check that the world is not already in use.');
  const config=JSON.parse(init.stdout.trim());
  const java=String.raw`C:\Program Files\Java\jdk-25\bin\java.exe`;
  if (!fs.existsSync(java)) throw new Error('Java 25 was not found.');
  let bridge=null;
  try {
    let status=await health();
    if (!status) {
      fs.mkdirSync(path.join(root,'logs'),{recursive:true});
      const out=fs.openSync(path.join(root,'logs/bridge-out.log'),'a');
      const err=fs.openSync(path.join(root,'logs/bridge-error.log'),'a');
      try {
        // Keep the bridge in its own process group so Ctrl+C only restarts Java.
        bridge=spawn(process.execPath,['bridge/craftgpt-codex-bridge.mjs'],{
          cwd:root,detached:true,windowsHide:true,stdio:['ignore',out,err],
          env:{...process.env,CODEX_BIN:config.codex,CRAFTGPT_BRIDGE_PORT:'8766',CRAFTGPT_CODEX_TIMEOUT_MS:'840000'}
        });
        bridge.on('error',error=>console.error(`Bridge failed: ${error.message}`));
      } finally { fs.closeSync(out); fs.closeSync(err); }
      for(let i=0;i<20;i++) {
        await new Promise(resolve=>setTimeout(resolve,400));
        status=await health();
        if(status) break;
      }
    }
    if(!status?.ok || status.service!=='craftgpt-codex-bridge') throw new Error('CraftGPT bridge unavailable on port 8766.');
    console.log(`Minecraft: localhost:${port}. Ctrl+C = save and restart; type stop = save and exit.`);
    const supervisor=new RestartSupervisor(()=> {
      // Java inherits the console and receives its native Ctrl+C shutdown signal.
      // Never use child.kill() here: on Windows it can bypass Java's save hooks.
      const child=spawn(java,['-Xms1G','-Xmx2G','-jar','paper-26.3-205.jar','nogui'],{cwd:root,stdio:'inherit'});
      return waitForExit(child);
    });
    const interrupt=()=>supervisor.requestRestart();
    process.on('SIGINT',interrupt);
    try { process.exitCode=await supervisor.run(); }
    finally { process.off('SIGINT',interrupt); }
  } finally { if(bridge && bridge.exitCode===null) bridge.kill(); }
}
if(process.argv[1] && path.resolve(process.argv[1])===fileURLToPath(import.meta.url)) {
  main().catch(error=> { console.error(error.message); process.exitCode=1; });
}