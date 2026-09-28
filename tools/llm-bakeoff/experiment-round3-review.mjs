import {readFileSync,writeFileSync,readdirSync,mkdirSync,existsSync} from 'node:fs';
import {join} from 'node:path';
import {randomInt} from 'node:crypto';
const dir='tools/llm-bakeoff/.local/experiments/prompt-eval-round3-20260918';
const review=join(dir,'review');mkdirSync(review,{recursive:true});
const visible=raw=>{
 try {const x=JSON.parse(raw);return {scenes:x.scenes,inner_thought:x.inner_thought};} catch {}
 const start=raw.indexOf('{');let depth=0,quoted=false,escaped=false;
 for(let i=start;i>=0&&i<raw.length;i++) {const c=raw[i];if(escaped){escaped=false;continue;}if(quoted&&c==='\\'){escaped=true;continue;}if(c==='"'){quoted=!quoted;continue;}if(quoted)continue;if(c==='{')depth++;if(c==='}'&&--depth===0){try{const x=JSON.parse(raw.slice(start,i+1));return {scenes:x.scenes,inner_thought:x.inner_thought,strictJsonInvalid:true};}catch{break;}}}
 return {scenes:[],strictJsonInvalid:true};
};
const mapFile=join(review,'private-mapping.json'),mapping=existsSync(mapFile)?JSON.parse(readFileSync(mapFile,'utf8')):{};
let groups=0,answers=0;
for(const p of readdirSync(dir,{withFileTypes:true}).filter(d=>d.isDirectory()&&d.name.startsWith('phase'))) {
 const phase=JSON.parse(readFileSync(join(dir,p.name,'plan.json'),'utf8'));
 const records=readdirSync(join(dir,p.name)).filter(f=>f.endsWith('.json')&&f!=='plan.json').map(f=>({...JSON.parse(readFileSync(join(dir,p.name,f),'utf8')),file:f})).filter(r=>r.state==='settled');
 const packet=[];
 for(const [id,rs] of Map.groupBy(records,r=>`${p.name}/${r.job.caseId}/r${r.job.rep}/t${r.job.turn+1}`)) {
  const c=phase.plan.cases.find(c=>c.id===rs[0].job.caseId);
  const expected=c.variants??phase.plan.variants;if(rs.length!==expected.length)continue;
  if(!mapping[id]) {const shuffled=[...rs];for(let i=shuffled.length-1;i>0;i--){const j=randomInt(i+1);[shuffled[i],shuffled[j]]=[shuffled[j],shuffled[i]];}mapping[id]=Object.fromEntries(shuffled.map((r,i)=>[String.fromCharCode(65+i),{version:r.job.promptVersion,file:r.file}]));}
  packet.push({id,mode:rs[0].mode,axis:c.axis??'heldout',input:rs[0].input,initialGreeting:rs[0].prepared.fixtureProfile.firstGreeting,
   evaluation:{positiveMarkers:c.positiveMarkers,negativeMarkers:c.negativeMarkers,focus:c.focus,limitation:c.limitation},
   answers:Object.entries(mapping[id]).map(([label,m])=>{const r=rs.find(r=>r.file===m.file);return {label,history:r.history.map(h=>({input:h.input,...visible(h.raw)})),...visible(r.result.raw)};})});
 }
 writeFileSync(join(review,p.name+'-blind.json'),JSON.stringify(packet,null,2));groups+=packet.length;answers+=packet.reduce((n,g)=>n+g.answers.length,0);
}
writeFileSync(mapFile,JSON.stringify(mapping,null,2));console.log(JSON.stringify({groups,answers}));
