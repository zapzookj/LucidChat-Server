import {readFileSync,writeFileSync,readdirSync,mkdirSync,existsSync} from 'node:fs';
import {join} from 'node:path';
import {randomInt} from 'node:crypto';
const dir='tools/llm-bakeoff/.local/experiments/prompt-eval-20260918';
const phases=readdirSync(dir,{withFileTypes:true}).filter(d=>d.isDirectory()&&d.name.startsWith('phase')).map(d=>d.name);
const records=[];
for(const phase of phases) for(const f of readdirSync(join(dir,phase)).filter(f=>f.endsWith('.json')&&f!=='plan.json')) {
 const r=JSON.parse(readFileSync(join(dir,phase,f),'utf8')); if(r.state==='settled')records.push({...r,phase,file:f});
}
const visible=(raw,validation)=>{try{const x=JSON.parse(raw);return {scenes:x.scenes,inner_thought:x.inner_thought};}catch{return validation?.scenes?{scenes:validation.scenes,strictJsonInvalid:true}:{invalidRaw:raw};}};
const groups=Map.groupBy(records,r=>`${r.phase}/${r.job.caseId}/r${r.job.rep}/t${r.job.turn+1}`);
const reviewDir=join(dir,'review');mkdirSync(reviewDir,{recursive:true});
const mappingFile=join(reviewDir,'private-mapping.json');
const mapping=existsSync(mappingFile)?JSON.parse(readFileSync(mappingFile,'utf8')):{};
const packet=[];
for(const [id,rs] of groups) {
 if(rs.length!==4)continue;
 if(!mapping[id]){
  const order=[...rs];for(let i=order.length-1;i>0;i--){const j=randomInt(i+1);[order[i],order[j]]=[order[j],order[i]];}
  mapping[id]=Object.fromEntries(order.map((r,i)=>[String.fromCharCode(65+i),{promptVersion:r.job.promptVersion,file:r.file}]));
 }
 packet.push({id,mode:rs[0].mode,input:rs[0].input,initialGreeting:rs[0].prepared.fixtureProfile.firstGreeting,
  answers:Object.entries(mapping[id]).map(([label,m])=>{const r=rs.find(r=>r.file===m.file);return {label,
   history:r.history.map(h=>({input:h.input,response:visible(h.raw)})),...visible(r.result.raw,r.result.validation)};})});
}
writeFileSync(mappingFile,JSON.stringify(mapping,null,2));
for(const phase of phases)writeFileSync(join(reviewDir,phase+'-blind.json'),JSON.stringify(packet.filter(g=>g.id.startsWith(phase+'/')),null,2));
const med=a=>{a=a.filter(Number.isFinite).sort((a,b)=>a-b);return a.length?(a[Math.floor((a.length-1)/2)]+a[Math.ceil((a.length-1)/2)])/2:null;};
const summary={calls:records.length,costUsd:records.reduce((n,r)=>n+(r.result.costUsd??0),0),groups:packet.length,
 variants:Array.from(Map.groupBy(records,r=>r.job.promptVersion),([variant,rs])=>({variant,n:rs.length,costUsd:rs.reduce((n,r)=>n+r.result.costUsd,0),
  ttfsMedianMs:med(rs.map(r=>r.result.ttfsMs)),totalMedianMs:med(rs.map(r=>r.result.totalMs)),promptTokensMedian:med(rs.map(r=>r.result.usage?.prompt_tokens)),
  completionTokensMedian:med(rs.map(r=>r.result.usage?.completion_tokens)),statuses:Object.fromEntries(Array.from(Map.groupBy(rs,r=>r.result.status),([k,v])=>[k,v.length]))}))};
writeFileSync(join(reviewDir,'summary.json'),JSON.stringify(summary,null,2));
console.log(JSON.stringify(summary));
if(process.argv.includes('--text'))for(const [id,rs]of groups){console.log('\n'+id+' INPUT '+rs[0].input);for(const r of rs)console.log(r.job.promptVersion.split('-')[0],JSON.stringify(visible(r.result.raw,r.result.validation)));}
