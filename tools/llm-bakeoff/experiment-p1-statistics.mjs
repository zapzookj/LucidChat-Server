import {readFileSync,readdirSync,writeFileSync} from 'node:fs';
import {join} from 'node:path';
const sessionIds=['p1-acceptance-20260922','p1-event-repair-20260922'];
const dirs=sessionIds.map(id=>join('tools/llm-bakeoff/.local/experiments',id));
const phases=dirs.flatMap((dir,i)=>readdirSync(dir,{withFileTypes:true}).filter(d=>d.isDirectory()&&d.name.startsWith('phase')).map(d=>({dir,phase:d.name,sessionId:sessionIds[i]})));
const rs=phases.flatMap(({dir,phase,sessionId})=>readdirSync(join(dir,phase)).filter(f=>f.endsWith('.json')&&f!=='plan.json').map(f=>({...JSON.parse(readFileSync(join(dir,phase,f))),phase,sessionId}))).filter(r=>r.state==='settled');
const med=a=>{a=a.filter(Number.isFinite).sort((a,b)=>a-b);return a.length?(a[Math.floor((a.length-1)/2)]+a[Math.ceil((a.length-1)/2)])/2:null;};
const parsed=r=>{try{return JSON.parse(r.result.raw);}catch{return null;}};
const structure=a=>{
 const xs=a.map(parsed).filter(Boolean), scenes=xs.map(x=>x.scenes??[]);
 const stats=xs.map(x=>x.stat_changes??x.system_updates?.stat_changes??null).filter(Boolean).map(x=>Object.values(x).flatMap(v=>typeof v==='object'&&v!==null?Object.values(v):[v]).filter(v=>typeof v==='number'));
 return {strictParsedN:xs.length,sceneCountMedian:med(scenes.map(s=>s.length)),dialogueSceneCountMedian:med(scenes.map(s=>s.filter(x=>x.dialogue?.trim()).length)),
 dialogueCharsMedian:med(scenes.map(s=>s.reduce((n,x)=>n+(x.dialogue?.length??0),0))),
 allScenesNeutralN:scenes.filter(s=>s.length&&s.every(x=>x.emotion==='NEUTRAL')).length,
 noDialogueN:scenes.filter(s=>s.every(x=>!x.dialogue?.trim())).length,
 statsObservedN:stats.length,allStatsZeroN:stats.filter(s=>s.length&&s.every(v=>v===0)).length};
};
const stats=a=>({n:a.length,costUsd:a.reduce((n,r)=>n+r.result.costUsd,0),ttfsMedianMs:med(a.map(r=>r.result.ttfsMs)),
 totalMedianMs:med(a.map(r=>r.result.totalMs)),inputTokensMedian:med(a.map(r=>r.result.usage.prompt_tokens)),
 outputTokensMedian:med(a.map(r=>r.result.usage.completion_tokens)),cachedTokensMedian:med(a.map(r=>r.result.usage.prompt_tokens_details?.cached_tokens)),
 validationInvalid:a.filter(r=>r.result.status!=='ok').length,strictJsonInvalid:a.filter(r=>{try{JSON.parse(r.result.raw);return false;}catch{return true;}}).length,...structure(a)});
const by=(a,key)=>Object.fromEntries(Array.from(Map.groupBy(a,key),([k,v])=>[k,stats(v)]));
const result={structureScope:'Scene/stat descriptive medians use strict JSON only; use contract-audit.json for all records including first-root parsed P0 outputs. validationInvalid includes fixture semantics and is not a production DTO parse failure.',at:new Date().toISOString(),total:stats(rs),byPhase:by(rs,r=>r.phase),byVariant:by(rs,r=>r.job.promptVersion.split('-')[0]),
 byPhaseAndVariant:by(rs,r=>r.phase+'/'+r.job.promptVersion.split('-')[0]),
 byModeAndVariant:by(rs,r=>r.mode+'/'+r.job.promptVersion.split('-')[0]),
 extendedByFixture:by(rs.filter(r=>r.fixture),r=>r.fixture),
 coreByModeAndVariant:by(rs.filter(r=>r.phase==='phase-core'),r=>r.mode+'/'+r.job.promptVersion.split('-')[0]),
 failures:rs.flatMap(r=>{let strict=true;try{JSON.parse(r.result.raw);}catch{strict=false;}return strict&&r.result.status==='ok'?[]:[{phase:r.phase,id:r.job.id,status:r.result.status,strictJson:strict,issues:r.result.validation?.issues??[],cost:r.result.costUsd}];})};
const reconciliations=dirs.map((dir,i)=>{
const manifest=JSON.parse(readFileSync(join(dir,'manifest.json')));
const sessionRs=rs.filter(r=>r.sessionId===sessionIds[i]);
const global=JSON.parse(readFileSync('tools/llm-bakeoff/.local/budget.json')).entries;
const local=JSON.parse(readFileSync(join(dir,'budget.json'))).entries;
const added=global.filter(e=>e.runId?.startsWith(manifest.id+'/'));
const sum=a=>a.reduce((n,e)=>n+(e.actual??0),0);
return {sessionId:sessionIds[i],baselineUnchanged:JSON.stringify(global.slice(0,manifest.globalBaseline.length))===JSON.stringify(manifest.globalBaseline),
 baselineN:manifest.globalBaseline.length,globalNewN:added.length,sessionN:local.length,recordsN:sessionRs.length,
 uniqueRunIds:new Set(local.map(e=>e.runId)).size,globalNewKnown:sum(added),sessionKnown:sum(local),resultsKnown:sessionRs.reduce((n,r)=>n+r.result.costUsd,0),
 globalHeld:global.filter(e=>e.actual===null).reduce((n,e)=>n+e.reserved,0),sessionHeld:local.filter(e=>e.actual===null).reduce((n,e)=>n+e.reserved,0),
 allResultCostsMatch:sessionRs.every(r=>global.find(e=>e.id===r.globalReservation.id)?.actual===r.result.costUsd&&local.find(e=>e.id===r.sessionReservation.id)?.actual===r.result.costUsd)};
});
result.reconciliations=reconciliations;
writeFileSync('docs/28_assets/p1-acceptance-2026-09-22/statistics.json',JSON.stringify(result,null,2));
console.log(JSON.stringify(result));
