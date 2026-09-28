import {readFileSync,writeFileSync,readdirSync,mkdirSync} from 'node:fs';
const dir='tools/llm-bakeoff/.local/experiments/prompt-eval-final-20260918/review';
const out='docs/28_assets/prompt-evaluation-final-2026-09-18';mkdirSync(out,{recursive:true});
const mapping=JSON.parse(readFileSync(dir+'/private-mapping.json','utf8'));
const groups=[],revisionLogs=[];
for(const file of readdirSync(dir).filter(f=>f.endsWith('-judgement.json'))) {
 const x=JSON.parse(readFileSync(dir+'/'+file,'utf8')), gs=Array.isArray(x)?x:x.groups;
 if(!Array.isArray(gs))throw new Error('Invalid judgment groups '+file);
 if(x.revisionLog)revisionLogs.push({source:file,entries:x.revisionLog});
 for(const g of gs) {
  if(groups.some(x=>x.id===g.id))throw new Error('Duplicate group '+g.id);
  const m=mapping[g.id];if(!m||g.answers.length!==Object.keys(m).length||new Set(g.answers.map(a=>a.label)).size!==g.answers.length)throw new Error('Incomplete judgment '+g.id);
  const answers=g.answers.map(a=>{
   if(!m[a.label]||['facts','agency','intent'].some(k=>!['pass','fail','ambiguous','not_applicable'].includes(a[k]))||['characterVoice','naturalness'].some(k=>!Number.isInteger(a[k])||a[k]<1||a[k]>5))throw new Error('Invalid rating '+g.id+'/'+a.label);
   return {...a,variant:m[a.label].version.split('-')[0]};
  });groups.push({...g,answers,source:file});
 }
}
const aggregate=answers=>({n:answers.length,...Object.fromEntries(['facts','agency','intent'].map(k=>[k,Object.fromEntries(['pass','fail','ambiguous','not_applicable'].map(v=>[v,answers.filter(a=>a[k]===v).length]))]))});
const byVariant=Object.fromEntries(Array.from(Map.groupBy(groups.flatMap(g=>g.answers),a=>a.variant),([k,as])=>[k,aggregate(as)]));
const byPhaseVariant=Object.fromEntries(Array.from(Map.groupBy(groups.flatMap(g=>g.answers.map(a=>({...a,phase:g.id.split('/')[0]}))),a=>a.phase+'/'+a.variant),([k,as])=>[k,aggregate(as)]));
const pairs={};
for(const g of groups) {
 const base=g.answers.find(a=>a.variant==='J2');if(!base)continue;
 for(const a of g.answers.filter(a=>a.variant!=='J2')) {
  const key=g.id.split('/')[0]+'/'+a.variant+'-vs-J2';
  const p=pairs[key]??={n:0,voiceAndNaturalness:{higher:0,tied:0,lower:0},factFailureRemoved:0,factFailureAdded:0,agencyFailureRemoved:0,agencyFailureAdded:0,intentFailureRemoved:0,intentFailureAdded:0};p.n++;
  const diff=a.characterVoice+a.naturalness-base.characterVoice-base.naturalness;p.voiceAndNaturalness[diff>0?'higher':diff<0?'lower':'tied']++;
  for(const [metric,prefix] of [['facts','fact'],['agency','agency'],['intent','intent']]) {
   if(base[metric]==='fail'&&a[metric]==='pass')p[prefix+'FailureRemoved']++;
   if(base[metric]==='pass'&&a[metric]==='fail')p[prefix+'FailureAdded']++;
  }
 }
}
if(groups.length!==Object.keys(mapping).length)throw new Error('Incomplete final review coverage');
const result={limitation:'Anonymous AI judgments, not creator/user preference. Exploratory repeats are correlated. Phases1/2 share input/history within each group; phase3 uses each candidate own branch, so turns2–4 are trajectory comparisons, not matched-history causal pairs.',groups,byVariant,byPhaseVariant,pairs,revisionLogs};
writeFileSync(out+'/judgements.json',JSON.stringify(result,null,2));console.log(JSON.stringify({groups:groups.length,answers:groups.reduce((n,g)=>n+g.answers.length,0),byPhaseVariant,pairs}));
