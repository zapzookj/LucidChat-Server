import {readFileSync,readdirSync,writeFileSync} from 'node:fs';
import {join} from 'node:path';
const dir='tools/llm-bakeoff/.local/experiments/prompt-eval-final-20260918';
const phases=readdirSync(dir,{withFileTypes:true}).filter(d=>d.isDirectory()&&d.name.startsWith('phase')).map(d=>d.name);
const plans=Object.fromEntries(phases.map(p=>[p,JSON.parse(readFileSync(join(dir,p,'plan.json'),'utf8')).plan]));
const rs=phases.flatMap(phase=>readdirSync(join(dir,phase)).filter(f=>f.endsWith('.json')&&f!=='plan.json').map(f=>({...JSON.parse(readFileSync(join(dir,phase,f))),phase}))).filter(r=>r.state==='settled');
const med=a=>{a=a.filter(Number.isFinite).sort((a,b)=>a-b);return a.length?(a[Math.floor((a.length-1)/2)]+a[Math.ceil((a.length-1)/2)])/2:null;};
const parsed=r=>{try{return JSON.parse(r.result.raw);}catch{return null;}};
const structure=a=>{
 const xs=a.map(parsed).filter(Boolean), scenes=xs.map(x=>x.scenes??[]);
 const stats=xs.map(x=>x.stat_changes??x.system_updates?.stat_changes??null).filter(Boolean).map(x=>Object.values(x).flatMap(v=>typeof v==='object'&&v!==null?Object.values(v):[v]).filter(v=>typeof v==='number'));
 return {strictParsedN:xs.length,sceneCountMedian:med(scenes.map(s=>s.length)),dialogueSceneCountMedian:med(scenes.map(s=>s.filter(x=>x.dialogue?.trim()).length)),
 dialogueCharsMedian:med(scenes.map(s=>s.reduce((n,x)=>n+(x.dialogue?.length??0),0))),
 allScenesNeutralN:scenes.filter(s=>s.length&&s.every(x=>x.emotion==='NEUTRAL')).length,
 statsObservedN:stats.length,allStatsZeroN:stats.filter(s=>s.length&&s.every(v=>v===0)).length};
};
const stats=a=>({n:a.length,costUsd:a.reduce((n,r)=>n+r.result.costUsd,0),ttfsMedianMs:med(a.map(r=>r.result.ttfsMs)),
 totalMedianMs:med(a.map(r=>r.result.totalMs)),inputTokensMedian:med(a.map(r=>r.result.usage.prompt_tokens)),
 outputTokensMedian:med(a.map(r=>r.result.usage.completion_tokens)),cachedTokensMedian:med(a.map(r=>r.result.usage.prompt_tokens_details?.cached_tokens)),
 parserInvalid:a.filter(r=>r.result.status!=='ok').length,strictJsonInvalid:a.filter(r=>{try{JSON.parse(r.result.raw);return false;}catch{return true;}}).length,...structure(a)});
const by=(a,key)=>Object.fromEntries(Array.from(Map.groupBy(a,key),([k,v])=>[k,stats(v)]));
const result={at:new Date().toISOString(),total:stats(rs),byPhase:by(rs,r=>r.phase),byVariant:by(rs,r=>r.job.promptVersion.split('-')[0]),
 byPhaseAndVariant:by(rs,r=>r.phase+'/'+r.job.promptVersion.split('-')[0]),
 byPhaseAxisAndVariant:by(rs,r=>r.phase+'/'+plans[r.phase].cases.find(c=>c.id===r.job.caseId).axis+'/'+r.job.promptVersion.split('-')[0]),
 byModeAndVariant:by(rs,r=>r.mode+'/'+r.job.promptVersion.split('-')[0]),
 newCasesByModeAndVariant:by(rs.filter(r=>r.phase==='phase2-heldout'),r=>r.mode+'/'+r.job.promptVersion.split('-')[0]),
 failures:rs.flatMap(r=>{let strict=true;try{JSON.parse(r.result.raw);}catch{strict=false;}return strict&&r.result.status==='ok'?[]:[{phase:r.phase,id:r.job.id,status:r.result.status,strictJson:strict,issues:r.result.validation?.issues??[],cost:r.result.costUsd}];})};
const manifest=JSON.parse(readFileSync(join(dir,'manifest.json')));
const global=JSON.parse(readFileSync('tools/llm-bakeoff/.local/budget.json')).entries;
const local=JSON.parse(readFileSync(join(dir,'budget.json'))).entries;
const added=global.filter(e=>e.runId?.startsWith(manifest.id+'/'));
const sum=a=>a.reduce((n,e)=>n+(e.actual??0),0);
result.reconciliation={baselineUnchanged:JSON.stringify(global.slice(0,manifest.globalBaseline.length))===JSON.stringify(manifest.globalBaseline),
 baselineN:manifest.globalBaseline.length,globalNewN:added.length,sessionN:local.length,recordsN:rs.length,
 uniqueRunIds:new Set(local.map(e=>e.runId)).size,globalNewKnown:sum(added),sessionKnown:sum(local),resultsKnown:result.total.costUsd,
 globalHeld:global.filter(e=>e.actual===null).reduce((n,e)=>n+e.reserved,0),sessionHeld:local.filter(e=>e.actual===null).reduce((n,e)=>n+e.reserved,0),
 allResultCostsMatch:rs.every(r=>global.find(e=>e.id===r.globalReservation.id)?.actual===r.result.costUsd&&local.find(e=>e.id===r.sessionReservation.id)?.actual===r.result.costUsd)};
writeFileSync(join(dir,'review/statistics.json'),JSON.stringify(result,null,2));
console.log(JSON.stringify(result));
