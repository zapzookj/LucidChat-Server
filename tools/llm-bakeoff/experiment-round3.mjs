import { existsSync, readFileSync, writeFileSync, renameSync, mkdirSync, unlinkSync, readdirSync } from 'node:fs';
import { join, resolve, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { randomInt, createHash } from 'node:crypto';
import { PromptBridge } from './bridge.mjs';
import {budgetedGenerate} from './experiment.mjs';
import { Catalog, Ledger, Engine, PROMPT_VARIANTS, hash, requestFor, prepareComparison, cleanError } from './engine.mjs';

const here = dirname(fileURLToPath(import.meta.url)), root = resolve(here,'../..');
const sessionId = 'prompt-eval-round3-20260918';
const previousSessionIds = ['prompt-eval-20260918','prompt-eval-round2-20260918'];
// Anchor the independently reconciled previous experiment; a new phase cannot reset its spent budget.
export const PRIOR_ANCHOR = {"count":276,"hash":"d192125275fd2c021a5fab68809f00507c8fb2f050df82fa590fdece07ec9f7a","spent":1.0308608333333336,"globalCount":318,"globalHash":"63ff3737ba09ee4b093d76fc193614d427b01ede7cfd91e3b8ee75386cd7e783"};
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
export function remainingAuthorization(entries, total=3) {
  if (!Array.isArray(entries) || !entries.length || new Set(entries.map(e=>e.id)).size!==entries.length || entries.some(e=>!Number.isFinite(e.actual)||e.actual<0||!Number.isFinite(e.reserved)||e.actual>e.reserved||!['ok','invalid'].includes(e.status)||!previousSessionIds.some(id=>e.runId?.startsWith(id+'/')))) throw new Error('Prior session must be fully reconciled');
  const spent=entries.reduce((sum,e)=>sum+e.actual,0);
  if(!Number.isFinite(total)||total!==3||spent>=total) throw new Error('Prior session exhausted authorization');
  return {total,spent,remaining:total-spent,count:entries.length,entriesHash:hash(entries)};
}
export function variantsFor(plan,c) {
  const ids=c.variants??plan.variants;
  if(!Array.isArray(ids)||ids.length<2||new Set(ids).size!==ids.length||ids.some(id=>!PROMPT_VARIANTS.some(v=>v.id===id))) throw new Error('Invalid explicit candidate list');
  return ids.map(id=>PROMPT_VARIANTS.find(v=>v.id===id));
}
export function validateManifest(manifest, authorization, javaHash, labHash) {
  if(manifest.javaHash!==javaHash || manifest.labHash!==labHash) throw new Error('Frozen source drift');
  if(hash(manifest.authorization)!==hash(authorization)||manifest.limit!==authorization.remaining) throw new Error('Prior budget or authorization drift');
  if(manifest.id!==sessionId||manifest.model!=='google/gemini-3-flash-preview'||manifest.provider!=='google-ai-studio'||manifest.reasoning!=='default'||manifest.stateFrozen!==true||manifest.automaticRetry!==false) throw new Error('Frozen generation conditions drift');
}
export function validatePriorAnchor(entries,globalEntries,anchor=PRIOR_ANCHOR) {
  const a=remainingAuthorization(entries);
  if(a.count!==anchor.count||a.entriesHash!==anchor.hash||Math.abs(a.spent-anchor.spent)>1e-10||hash(globalEntries.slice(0,anchor.globalCount))!==anchor.globalHash) throw new Error('Previous experiment anchor changed');
}
export function validateResume(globalEntries, sessionEntries, records, settings={}) {
  const globals=globalEntries.filter(e=>e.runId?.startsWith(sessionId+'/'));
  if(globals.length!==sessionEntries.length||records.length!==sessionEntries.length) throw new Error('Resume evidence count mismatch; no implicit retry');
  for(const list of [globals,sessionEntries]) if(new Set(list.map(e=>e.id)).size!==list.length||new Set(list.map(e=>e.runId)).size!==list.length) throw new Error('Duplicate ledger evidence');
  if(new Set(records.map(r=>r.globalReservation?.id)).size!==records.length) throw new Error('Duplicate result evidence');
  for(const r of records) {
    const g=globals.find(e=>e.id===r.globalReservation?.id), s=sessionEntries.find(e=>e.id===r.sessionReservation?.id);
    const cost=r.result?.costUsd, expected=`${sessionId}/${r.phase}/${r.job?.id}`;
    if(r.state!=='settled'||!g||!s||g.runId!==expected||s.runId!==expected||!Number.isFinite(cost)||cost<0||g.actual!==cost||s.actual!==cost||g.status!==r.result.status||s.status!==r.result.status||!['ok','invalid'].includes(r.result.status)||cost>g.reserved||cost>s.reserved||g.reserved!==s.reserved) throw new Error('Resume result/ledger mismatch');
    if(settings.javaHash && r.sourceHash!==settings.javaHash) throw new Error('Resume source drift');
    if(settings.model && (r.result.actualModel!==settings.model||r.result.actualProvider!==settings.provider||r.result.actualTier!==settings.tier)) throw new Error('Resume model/provider/tier mismatch');
    if(r.result.status==='invalid') {
      const {phase,...original}=r, review=settings.reviews?.[`${r.phase}/${r.job.id}`];
      if(!review?.reason||review.fileHash!==hash(original)) throw new Error('Unreviewed invalid result in prior phase');
    }
  }
}
export function expandJobs(plan, order=shuffle) {
  if(!Array.isArray(plan.cases)||!plan.cases.length||new Set(plan.cases.map(c=>c.id)).size!==plan.cases.length) throw new Error('Invalid or duplicate case IDs');
  const jobs=[];
  for(const c of plan.cases) {
    if(!/^[A-Za-z0-9-]+$/.test(c.id)||!['SANDBOX','STORY'].includes(c.mode)||!Number.isInteger(c.repeats??1)||(c.repeats??1)<1||(c.repeats??1)>4) throw new Error('Invalid case');
    const inputs=c.inputs??[c.input]; if(!Array.isArray(inputs)||!inputs.length||inputs.some(s=>typeof s!=='string'||!s.trim())) throw new Error('Invalid inputs');
    for(let rep=1;rep<=(c.repeats??1);rep++) for(let turn=0;turn<inputs.length;turn++)
      for(const v of order(variantsFor(plan,c))) jobs.push({id:`${c.id}-r${rep}-t${turn+1}-${v.id.split('-')[0]}`,caseId:c.id,rep,turn,promptVersion:v.id});
  }
  return jobs;
}
export function validatePhase(phase,plan) {
  const ordered=jobs=>[...jobs].sort((a,b)=>a.id.localeCompare(b.id));
  if(phase.planHash!==hash(plan)||phase.jobsHash!==hash(phase.jobs)||hash(ordered(phase.jobs))!==hash(ordered(expandJobs(plan,a=>a)))) throw new Error('Phase plan/jobs drift');
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
  if(!phaseFile) throw new Error('Usage: node experiment-round3.mjs <phase-plan.json> [--run-paid]');
  const paid=process.argv.includes('--run-paid');
  const plan=JSON.parse(readFileSync(resolve(phaseFile),'utf8'));
  if(!/^phase[a-z0-9-]+$/.test(plan.id) || !Array.isArray(plan.cases)) throw new Error('Invalid phase plan');
  const runtimeFile=join(root,'build/bakeoff/runtime.json'), runtime=JSON.parse(readFileSync(runtimeFile,'utf8'));
  const currentHash=sourceHash();
  if(currentHash!==runtime.sourceHash) throw new Error('Runtime source drift');
  const labHash=hash(['engine.mjs','bridge.mjs','experiment.mjs','experiment-round3.mjs'].map(f=>[f,readFileSync(join(here,f),'utf8')]));
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
    const readPreviousEntries=()=>previousSessionIds.flatMap(id=>JSON.parse(readFileSync(join(directory,'experiments',id,'budget.json'),'utf8')).entries);
    const previousEntries=readPreviousEntries();
    validatePriorAnchor(previousEntries,globalLedger.entries);
    const authorization=remainingAuthorization(previousEntries);
    const sessionLedger=new Ledger(sessionDir,authorization.remaining);
    const manifestFile=join(sessionDir,'manifest.json');
    let manifest;
    if(existsSync(manifestFile)) {
      manifest=JSON.parse(readFileSync(manifestFile,'utf8'));
      validateManifest(manifest,authorization,currentHash,labHash);
    } else {
      manifest={id:sessionId,startedAt:new Date().toISOString(),limit:authorization.remaining,authorization,javaHash:currentHash,labHash,
        globalBaseline:structuredClone(globalLedger.entries),promptVariants:PROMPT_VARIANTS,
        model:'google/gemini-3-flash-preview',provider:'google-ai-studio',reasoning:'default',
        stateFrozen:true,cacheCondition:'uncontrolled-observe-usage',automaticRetry:false};
      atomicJson(manifestFile,manifest);
    }
    if(hash(globalLedger.entries.slice(0,manifest.globalBaseline.length))!==hash(manifest.globalBaseline)) throw new Error('Existing ledger changed');
    if(globalLedger.snapshot().held || sessionLedger.snapshot().held) throw new Error('Unresolved reservation');
    if(globalLedger.entries.length!==manifest.globalBaseline.length+sessionLedger.entries.length) throw new Error('Unexpected global entries during frozen experiment');
    const records=[];
    for(const d of readdirSync(sessionDir,{withFileTypes:true}).filter(d=>d.isDirectory()&&d.name.startsWith('phase'))) {
      for(const f of readdirSync(join(sessionDir,d.name)).filter(f=>f.endsWith('.json')&&f!=='plan.json')) records.push({...JSON.parse(readFileSync(join(sessionDir,d.name,f),'utf8')),phase:d.name});
    }
    const reviewsFile=join(sessionDir,'reviewed-invalid-jobs.json');
    const reviews=existsSync(reviewsFile)?JSON.parse(readFileSync(reviewsFile,'utf8')):{};
    validateResume(globalLedger.entries,sessionLedger.entries,records,{javaHash:currentHash,model:manifest.model,provider:'Google AI Studio',tier:'default',reviews});
    if(authorization.spent+sessionLedger.snapshot().known+sessionLedger.snapshot().held>authorization.total+1e-10) throw new Error('Combined authorization exceeded');
    const phaseDir=join(sessionDir,plan.id); mkdirSync(phaseDir,{recursive:true});
    const phaseManifestFile=join(phaseDir,'plan.json');
    let phase;
    if(existsSync(phaseManifestFile)) {
      phase=JSON.parse(readFileSync(phaseManifestFile,'utf8'));
      validatePhase(phase,plan);
    } else {
      phase={plan,planHash:hash(plan),createdAt:new Date().toISOString(),jobs:expandJobs(plan)};
      phase.jobsHash=hash(phase.jobs);
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
      if(hash(readPreviousEntries())!==authorization.entriesHash) throw new Error('Prior session ledger changed during experiment');
    }
    console.log(JSON.stringify({complete:true,phase:plan.id,session:sessionLedger.snapshot(),global:globalLedger.snapshot()}));
  } finally {cleanup();}
}
if(process.argv[1]&&resolve(process.argv[1])===fileURLToPath(import.meta.url)) main().catch(e=>{console.error(cleanError(e,process.env.OPENROUTER_API_KEY));process.exitCode=1;});
