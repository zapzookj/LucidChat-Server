// Read-only response audit. These are acceptance observations, not changes to production parsing.
import {readFileSync,writeFileSync,readdirSync,mkdirSync} from 'node:fs';
import {join} from 'node:path';
import {firstScene,hash} from './engine.mjs';
const sessionIds=['p1-acceptance-20260922','p1-event-repair-20260922'];
const dirs=sessionIds.map(id=>join('tools/llm-bakeoff/.local/experiments',id));

const out='docs/28_assets/p1-acceptance-2026-09-22';
const records=dirs.flatMap((dir,i)=>readdirSync(dir,{withFileTypes:true}).filter(d=>d.isDirectory()&&d.name.startsWith('phase')).flatMap(d=>
  readdirSync(join(dir,d.name)).filter(f=>f.endsWith('.json')&&f!=='plan.json').map(f=>({...JSON.parse(readFileSync(join(dir,d.name,f),'utf8')),phase:d.name,sessionId:sessionIds[i]})))).filter(r=>r.state==='settled');
const normal=['affection','dependency','intimacy','playfulness','trust'];
const same=(a,b)=>JSON.stringify(a)===JSON.stringify(b);
function firstRoot(raw) {
 const start=raw.indexOf('{');let depth=0,quoted=false,escaped=false;
 for(let i=start;i>=0&&i<raw.length;i++) {
  const ch=raw[i];if(escaped){escaped=false;continue;}
  if(quoted&&ch==='\\'){escaped=true;continue;}if(ch==='"'){quoted=!quoted;continue;}if(quoted)continue;
  if(ch==='{')depth++;if(ch==='}'&&--depth===0)return JSON.parse(raw.slice(start,i+1));
 }
 return {};
}
const audited=records.map(r=>{
 const issues=[],observations=[];let x,strict=true;
 try{x=JSON.parse(r.result.raw);}catch{strict=false;issues.push('strict-json');try{x=firstRoot(r.result.raw);}catch{x={};}}
 const scenes=x.scenes??[],opening=r.prepared.flags?.openingMode===true,secret=r.prepared.flags?.effectiveSecretMode===true,event=r.prepared.flags?.eventActive===true;
 const expected=[...normal,...(secret?['corruption','lust','obsession']:[])].sort();
 const statObjects=r.mode==='STORY'?Object.entries(x.system_updates?.stat_changes??{}):[['free',x.stat_changes]];
 if(r.mode==='SANDBOX') {
  if(typeof x.topic_concluded!=='boolean')issues.push('topic-concluded-not-boolean');
  if(scenes.some(s=>s.speaker!==null))issues.push('free-speaker-not-null');
 } else {
  if(typeof x.system_updates?.topic_concluded!=='boolean')issues.push('topic-concluded-not-boolean');
  if(scenes.some(s=>(s.speaker===null||s.speaker==='')&&s.dialogue?.trim()))issues.push('environment-dialogue');
 }
 for(const [id,values] of statObjects) {
  if(!values||!same(Object.keys(values).sort(),expected))issues.push(`stat-key-set:${id}`);
  if(values&&Object.values(values).some(n=>!Number.isInteger(n)||n < -3||n > 3))issues.push(`stat-range:${id}`);
 }
 if(opening&&(scenes.length<1||scenes.length>2||statObjects.length!==0))issues.push('opening-contract');
 if(event&&(x.event_status!=='ONGOING'||statObjects.some(([,s])=>Object.values(s??{}).some(v=>v!==0))))issues.push('event-ongoing-contract');
 if(event&&x.inner_thought!==null)issues.push('event-inner-thought-not-null');
 const dialogueScenes=scenes.filter(s=>s.dialogue?.trim()).length;
 if(!opening&&dialogueScenes===0)issues.push('no-character-dialogue');
 if(!same(firstScene(r.result.raw),scenes[0]))issues.push('stream-first-scene-mismatch');
 if(!Number.isFinite(r.result.ttfsMs))issues.push('no-first-scene-time');
 if(!same(r.prepared.messages,r.result.request.messages)||hash(r.prepared.messages)!==r.result.promptHash)issues.push('request-mismatch');
 if(!scenes.length)issues.push('no-scenes');
 if(r.result.status!=='ok')issues.push('existing-validation:'+r.result.status);
 const nonZeroStats=statObjects.flatMap(([,s])=>Object.entries(s??{})).filter(([,v])=>v!==0).length;
 if(nonZeroStats===0)observations.push('all-stats-zero');
 return {sessionId:r.sessionId,sourceHash:r.sourceHash,phase:r.phase,id:r.job.id,variant:r.job.promptVersion,fixture:r.fixture??r.prepared.fixture,mode:r.mode,strict,
  sceneCount:scenes.length,dialogueScenes,nonZeroStats,opening,secret,event,issues,observations};
});
const result={at:new Date().toISOString(),scope:'Whole raw response/first-scene/service-contract audit; not actual browser/DB integration',
 total:audited.length,issues:audited.filter(x=>x.issues.length),
 byVariant:Object.fromEntries(Array.from(Map.groupBy(audited,r=>r.variant),([variant,rs])=>[variant,{n:rs.length,issueResponses:rs.filter(r=>r.issues.length).length,strictInvalid:rs.filter(r=>!r.strict).length,noDialogue:rs.filter(r=>!r.opening&&r.dialogueScenes===0).length,nonZeroStatResponses:rs.filter(r=>r.nonZeroStats>0).length}])),records:audited};
mkdirSync(out,{recursive:true});writeFileSync(join(out,'contract-audit.json'),JSON.stringify(result,null,2));
console.log(JSON.stringify({total:result.total,byVariant:result.byVariant,issues:result.issues}));
