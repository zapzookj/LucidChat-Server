import {readFileSync,readdirSync,writeFileSync} from 'node:fs';
import {join} from 'node:path';
const dir='tools/llm-bakeoff/.local/experiments/prompt-eval-20260918';
const phases=readdirSync(dir,{withFileTypes:true}).filter(d=>d.isDirectory()&&d.name.startsWith('phase')).map(d=>d.name);
const rs=phases.flatMap(phase=>readdirSync(join(dir,phase)).filter(f=>f.endsWith('.json')&&f!=='plan.json').map(f=>({...JSON.parse(readFileSync(join(dir,phase,f))),phase}))).filter(r=>r.state==='settled');
const med=a=>{a=a.filter(Number.isFinite).sort((a,b)=>a-b);return a.length?(a[Math.floor((a.length-1)/2)]+a[Math.ceil((a.length-1)/2)])/2:null;};
const stats=a=>({n:a.length,costUsd:a.reduce((n,r)=>n+r.result.costUsd,0),ttfsMedianMs:med(a.map(r=>r.result.ttfsMs)),
 totalMedianMs:med(a.map(r=>r.result.totalMs)),inputTokensMedian:med(a.map(r=>r.result.usage.prompt_tokens)),
 outputTokensMedian:med(a.map(r=>r.result.usage.completion_tokens)),cachedTokensMedian:med(a.map(r=>r.result.usage.prompt_tokens_details?.cached_tokens)),
 parserInvalid:a.filter(r=>r.result.status!=='ok').length,strictJsonInvalid:a.filter(r=>{try{JSON.parse(r.result.raw);return false;}catch{return true;}}).length});
const by=(a,key)=>Object.fromEntries(Array.from(Map.groupBy(a,key),([k,v])=>[k,stats(v)]));
const result={at:new Date().toISOString(),total:stats(rs),byPhase:by(rs,r=>r.phase),byVariant:by(rs,r=>r.job.promptVersion.split('-')[0]),
 byModeAndVariant:by(rs,r=>r.mode+'/'+r.job.promptVersion.split('-')[0]),
 initialUniqueByModeAndVariant:by(rs.filter(r=>['phase1-development','phase2-novel'].includes(r.phase)),r=>r.mode+'/'+r.job.promptVersion.split('-')[0]),
 failures:rs.flatMap(r=>{let strict=true;try{JSON.parse(r.result.raw);}catch{strict=false;}return strict&&r.result.status==='ok'?[]:[{phase:r.phase,id:r.job.id,status:r.result.status,strictJson:strict,issues:r.result.validation?.issues??[],cost:r.result.costUsd}];})};
const manifest=JSON.parse(readFileSync(join(dir,'manifest.json')));
const global=JSON.parse(readFileSync('tools/llm-bakeoff/.local/budget.json')).entries;
const local=JSON.parse(readFileSync(join(dir,'budget.json'))).entries;
const added=global.filter(e=>!manifest.globalBaseline.some(b=>b.id===e.id));
const sum=a=>a.reduce((n,e)=>n+(e.actual??0),0);
result.reconciliation={baselineUnchanged:JSON.stringify(global.slice(0,manifest.globalBaseline.length))===JSON.stringify(manifest.globalBaseline),
 baselineN:manifest.globalBaseline.length,globalNewN:added.length,sessionN:local.length,recordsN:rs.length,
 uniqueRunIds:new Set(local.map(e=>e.runId)).size,globalNewKnown:sum(added),sessionKnown:sum(local),resultsKnown:result.total.costUsd,
 globalHeld:global.filter(e=>e.actual===null).reduce((n,e)=>n+e.reserved,0),sessionHeld:local.filter(e=>e.actual===null).reduce((n,e)=>n+e.reserved,0),
 allResultCostsMatch:rs.every(r=>global.find(e=>e.id===r.globalReservation.id)?.actual===r.result.costUsd&&local.find(e=>e.id===r.sessionReservation.id)?.actual===r.result.costUsd)};
writeFileSync(join(dir,'review/statistics.json'),JSON.stringify(result,null,2));
console.log(JSON.stringify(result));
