import { existsSync, readFileSync, writeFileSync, renameSync, mkdirSync, unlinkSync, readdirSync } from 'node:fs';
import { join, resolve, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { randomInt, createHash } from 'node:crypto';
import { PromptBridge } from './bridge.mjs';
import { Catalog, Ledger, Engine, PROMPT_VARIANTS, hash, requestFor, prepareComparison, cleanError } from './engine.mjs';

const here = dirname(fileURLToPath(import.meta.url)), root = resolve(here,'../..');
const sessionId = 'prompt-eval-20260918';
const directory = join(here,'.local'), sessionDir = join(directory,'experiments',sessionId);
export const atomicJson = (file,value) => { writeFileSync(file+'.tmp',JSON.stringify(value,null,2)); renameSync(file+'.tmp',file); };
function sourceHash() {
  const files=[];
  const walk=p=>{for(const e of readdirSync(p,{withFileTypes:true})) { const f=join(p,e.name); e.isDirectory()?walk(f):files.push(f); }};
  for (const d of ['src/main/java','src/main/resources','src/bakeoff/java']) walk(join(root,d));
  const h=createHash('sha256');
  for(const f of files.sort()) h.update(f.slice(root.length+1).replaceAll('\\','/')).update('\0').update(readFileSync(f));
  return h.digest('hex');
}
// Preflight both budgets. A failed write, unknown charge or interrupted dispatch keeps reservations.
export async function budgetedGenerate({globalLedger,sessionLedger,id,reserve,generate,persist}) {
  if(globalLedger.snapshot().held || sessionLedger.snapshot().held) throw new Error('Unresolved reservation; reconcile before further paid calls');
  if(reserve>globalLedger.snapshot().available || reserve>sessionLedger.snapshot().available) throw new Error('Budget insufficient for conservative reservation');
  const [globalEntry]=globalLedger.reserve(id,[reserve]);
  const [sessionEntry]=sessionLedger.reserve(id,[reserve]);
  persist({state:'reserved',globalReservation:globalEntry,sessionReservation:sessionEntry});
  const result=await generate();
  // Save received evidence before settling either ledger; disk failure stops the experiment.
  persist({state:'received',result,globalReservation:globalEntry,sessionReservation:sessionEntry});
  globalLedger.settle(globalEntry.id,result.costUsd,result.status);
  sessionLedger.settle(sessionEntry.id,result.costUsd,result.status);
  persist({state:'settled',result,globalReservation:globalEntry,sessionReservation:sessionEntry});
  if(result.costUsd===null || !Number.isFinite(result.costUsd) || result.costUsd<0) throw new Error('Unknown charge; reservation retained and experiment stopped');
  if(result.costUsd>reserve) throw new Error('Reported charge exceeded reservation; stop');
  if(result.status!=='ok') throw new Error(`Generation ${result.status}; inspect before continuing`);
  return result;
}
const shuffle=a=>{a=[...a];for(let i=a.length-1;i>0;i--){const j=randomInt(i+1);[a[i],a[j]]=[a[j],a[i]];}return a;};
function assertResult(result,reserve,model,endpoint,reviewedInvalid=false) {
  if((result.status!=='ok' && !(reviewedInvalid&&result.status==='invalid'))||!Number.isFinite(result.costUsd)||result.costUsd<0||result.costUsd>reserve) throw new Error('Result not safe to resume');
  if(result.actualModel && result.actualModel!==model) throw new Error('Actual model mismatch');
  if(result.actualProvider && result.actualProvider!==endpoint.name) throw new Error('Actual provider mismatch');
  if(result.actualTier && result.actualTier!==endpoint.tier) throw new Error('Actual tier mismatch');
}
async function main() {
  const phaseFile=process.argv[2];
  if(!phaseFile) throw new Error('Usage: node experiment.mjs <phase-plan.json> [--run-paid]');
  const paid=process.argv.includes('--run-paid');
  const plan=JSON.parse(readFileSync(resolve(phaseFile),'utf8'));
  if(!/^[a-z0-9-]+$/.test(plan.id) || !Array.isArray(plan.cases)) throw new Error('Invalid phase plan');
  const runtimeFile=join(root,'build/bakeoff/runtime.json'), runtime=JSON.parse(readFileSync(runtimeFile,'utf8'));
  const currentHash=sourceHash();
  if(currentHash!==runtime.sourceHash) throw new Error('Runtime source drift');
  const labHash=hash(['engine.mjs','bridge.mjs','experiment.mjs'].map(f=>[f,readFileSync(join(here,f),'utf8')]));
  mkdirSync(sessionDir,{recursive:true});
  const lock=join(directory,'server.lock');
  if(existsSync(lock)) {
    const pid=Number(readFileSync(lock,'utf8')); if(!Number.isInteger(pid)||pid<=0) throw new Error('Invalid lock');
    let live=true; try{process.kill(pid,0);}catch(e){if(e.code==='ESRCH')live=false;}
    if(live) throw new Error('Lab/runner still running'); unlinkSync(lock);
  }
  writeFileSync(lock,String(process.pid),{flag:'wx'});
  let bridge;
  const controller=new AbortController();
  const cleanup=()=>{bridge?.close();try{if(readFileSync(lock,'utf8')===String(process.pid))unlinkSync(lock);}catch{}};
  process.on('exit',cleanup); process.on('SIGINT',()=>controller.abort()); process.on('SIGTERM',()=>controller.abort());
  try {
    if(existsSync(join(here,'.env.local'))) process.loadEnvFile(join(here,'.env.local'));
    const globalLedger=new Ledger(directory,Number(process.env.BAKEOFF_BUDGET_USD??5));
    const sessionLedger=new Ledger(sessionDir,3);
    const manifestFile=join(sessionDir,'manifest.json');
    let manifest;
    if(existsSync(manifestFile)) {
      manifest=JSON.parse(readFileSync(manifestFile,'utf8'));
      if(manifest.javaHash!==currentHash || manifest.labHash!==labHash) throw new Error('Frozen source drift');
    } else {
      manifest={id:sessionId,startedAt:new Date().toISOString(),limit:3,javaHash:currentHash,labHash,
        globalBaseline:structuredClone(globalLedger.entries),promptVariants:PROMPT_VARIANTS,
        model:'google/gemini-3-flash-preview',provider:'google-ai-studio',reasoning:'default',
        stateFrozen:true,cacheCondition:'uncontrolled-observe-usage',automaticRetry:false};
      atomicJson(manifestFile,manifest);
    }
    if(hash(globalLedger.entries.slice(0,manifest.globalBaseline.length))!==hash(manifest.globalBaseline)) throw new Error('Existing ledger changed');
    if(globalLedger.snapshot().held || sessionLedger.snapshot().held) throw new Error('Unresolved reservation');
    const phaseDir=join(sessionDir,plan.id); mkdirSync(phaseDir,{recursive:true});
    const phaseManifestFile=join(phaseDir,'plan.json');
    let phase;
    if(existsSync(phaseManifestFile)) {
      phase=JSON.parse(readFileSync(phaseManifestFile,'utf8'));
      if(phase.planHash!==hash(plan)) throw new Error('Phase plan drift');
    } else {
      phase={plan,planHash:hash(plan),createdAt:new Date().toISOString(),jobs:[]};
      for(const c of plan.cases) {
        if(!/^[A-Za-z0-9-]+$/.test(c.id) || !['SANDBOX','STORY'].includes(c.mode)) throw new Error('Invalid case');
        for(let rep=1;rep<=(c.repeats??1);rep++) for(let turn=0;turn<(c.inputs?.length??1);turn++)
          for(const v of shuffle(PROMPT_VARIANTS)) phase.jobs.push({id:`${c.id}-r${rep}-t${turn+1}-${v.id.split('-')[0]}`,caseId:c.id,rep,turn,promptVersion:v.id});
      }
      atomicJson(phaseManifestFile,phase);
    }
    bridge=new PromptBridge(runtimeFile);
    const catalog=new Catalog(), model=await catalog.model(manifest.model);
    const endpoint=model.endpoints.find(e=>e.tag===manifest.provider);
    if(!endpoint) throw new Error('Pinned provider unavailable');
    const engine=new Engine({bridge,catalog,globalLedger,directory:phaseDir,apiKey:process.env.OPENROUTER_API_KEY??''});
    if(paid && !engine.apiKey) throw new Error('Missing key');
    console.log(JSON.stringify({phase:plan.id,paid,jobs:phase.jobs.length,endpoint,budget:sessionLedger.snapshot()}));
    for(const job of phase.jobs) {
      if(controller.signal.aborted) throw new Error('Interrupted');
      const file=join(phaseDir,job.id+'.json');
      if(existsSync(file)) {
        const previous=JSON.parse(readFileSync(file,'utf8'));
        if(previous.state!=='settled') throw new Error('Unfinished existing job; no implicit retry');
        const reviewsFile=join(sessionDir,'reviewed-invalid-jobs.json');
        const reviews=existsSync(reviewsFile)?JSON.parse(readFileSync(reviewsFile,'utf8')):{};
        const review=reviews[`${plan.id}/${job.id}`];
        const globalEntry=globalLedger.entries.find(e=>e.id===previous.globalReservation.id);
        const sessionEntry=sessionLedger.entries.find(e=>e.id===previous.sessionReservation.id);
        const reviewedInvalid=!!review?.reason && review.fileHash===hash(previous) &&
          previous.result.status==='invalid' && globalEntry?.status==='invalid' && sessionEntry?.status==='invalid' &&
          globalEntry.actual===previous.result.costUsd && sessionEntry.actual===previous.result.costUsd;
        assertResult(previous.result,previous.globalReservation.reserved,manifest.model,previous.result.endpointSnapshot,reviewedInvalid);
        continue;
      }
      const c=plan.cases.find(c=>c.id===job.caseId), history=structuredClone(c.history??[]);
      for(let t=0;t<job.turn;t++) {
        const prior=JSON.parse(readFileSync(join(phaseDir,`${c.id}-r${job.rep}-t${t+1}-${job.promptVersion.split('-')[0]}.json`),'utf8'));
        if(prior.result.status!=='ok') throw new Error('Invalid branch history');
        history.push({input:c.inputs[t],raw:prior.result.raw});
      }
      const input=c.inputs?.[job.turn]??c.input;
      // Reuse the established shared-state/role assertions; P0 is the reference in every request.
      const candidate={model:manifest.model,provider:manifest.provider,reasoning:manifest.reasoning,promptVersion:job.promptVersion};
      const prepared=(await prepareComparison(bridge,{comparison:'prompt',mode:c.mode,input,history,candidates:[candidate,candidate,candidate]})).variants[0];
      if(Buffer.byteLength(JSON.stringify(prepared.messages),'utf8')>100000) throw new Error('Input exceeds 100KB');
      const condition={candidate,endpoint,...requestFor(prepared,candidate,endpoint)};
      if(!paid) { console.log(JSON.stringify({id:job.id,dryRun:true,promptHash:prepared.promptHash})); continue; }
      const reserve=endpoint.context/1e6+prepared.maxTokens*5/1e6;
      const evidence={job,input,mode:c.mode,history,prepared,sourceHash:currentHash,planHash:phase.planHash};
      const result=await budgetedGenerate({globalLedger,sessionLedger,id:`${sessionId}/${plan.id}/${job.id}`,reserve,
        generate:()=>engine.generate(condition,prepared,'anonymous',120000,controller.signal,()=>{}),
        persist:value=>atomicJson(file,{...evidence,...value})});
      console.log(JSON.stringify({id:job.id,status:result.status,cost:result.costUsd,spent:sessionLedger.snapshot().known,ttfsMs:result.ttfsMs,actualModel:result.actualModel,actualProvider:result.actualProvider}));
      assertResult(result,reserve,manifest.model,endpoint);
      if(sourceHash()!==currentHash) throw new Error('Source changed during experiment');
    }
    console.log(JSON.stringify({complete:true,phase:plan.id,session:sessionLedger.snapshot(),global:globalLedger.snapshot()}));
  } finally {cleanup();}
}
if(process.argv[1]&&resolve(process.argv[1])===fileURLToPath(import.meta.url)) main().catch(e=>{console.error(cleanError(e,process.env.OPENROUTER_API_KEY));process.exitCode=1;});
