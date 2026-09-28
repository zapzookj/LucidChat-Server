import {readFileSync,readdirSync,writeFileSync} from 'node:fs';
const dir='tools/llm-bakeoff/.local/experiments/prompt-eval-20260918/review/';
const mapping=JSON.parse(readFileSync(dir+'private-mapping.json'));
const cases=[];
for(const f of readdirSync(dir).filter(f=>f.endsWith('-judgement.json'))) {
 const doc=JSON.parse(readFileSync(dir+f));
 for(const c of doc.cases) cases.push({...c,reviewFile:f,bestCandidates:c.bestLabels.map(l=>mapping[c.id][l].promptVersion.split('-')[0]),
  answers:c.answers.map(a=>({...a,variant:mapping[c.id][a.label].promptVersion.split('-')[0]}))});
}
if(new Set(cases.map(c=>c.id)).size!==cases.length||cases.some(c=>c.answers.length!==4))throw new Error('Duplicate or incomplete judgement groups');
const phaseSummary=Object.fromEntries(Array.from(Map.groupBy(cases,c=>c.id.split('/')[0]),([p,cs])=>[p,{groups:cs.length,meaningful:cs.filter(c=>c.meaningfulDifference===true).length,noMeaningful:cs.filter(c=>c.meaningfulDifference===false).length}]));
const rows=cases.flatMap(c=>c.answers.map(a=>({phase:c.id.split('/')[0],id:c.id,...a,korean:a.scores.korean??a.scores.naturalness,character:a.scores.character,relevance:a.scores.relevance??a.scores.response})));
if(rows.some(r=>[r.korean,r.character,r.relevance].some(n=>!Number.isFinite(n))))throw new Error('Missing rubric score');
const pairs=Object.fromEntries([['P0','C1'],['C1','S1'],['S1','E1']].map(([a,b])=>{let wins=0,ties=0,losses=0;for(const c of cases.filter(c=>!c.id.startsWith('phase3')&&!c.id.startsWith('phase5'))){const scores=rows.filter(r=>r.id===c.id);const total=v=>{const s=scores.find(r=>r.variant===v);return s.korean+s.character+s.relevance;};const diff=total(b)-total(a);diff>0?wins++:diff<0?losses++:ties++;}return [b+' vs '+a,{wins,ties,losses,n:wins+ties+losses}];}));
const result={scope:'AI blind preferences; descriptive ordinal score sums only, no statistical or creator-validation claim. Self-history branches and adaptive diagnostic excluded from paired totals.',phaseSummary,adjacentScoreComparisons:pairs,cases};
writeFileSync(dir+'judgements-unblinded.json',JSON.stringify(result,null,2));
writeFileSync('docs/28_assets/prompt-evaluation-2026-09-18/judgements.json',JSON.stringify(result,null,2));
console.log(JSON.stringify({cases:cases.length,answers:rows.length,phaseSummary,pairs}));
