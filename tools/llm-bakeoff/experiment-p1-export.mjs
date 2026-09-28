import {readFileSync,writeFileSync,readdirSync,mkdirSync} from 'node:fs';
import {join} from 'node:path';
const sessionIds=['p1-acceptance-20260922','p1-event-repair-20260922'];
const dirs=sessionIds.map(id=>join('tools/llm-bakeoff/.local/experiments',id));
const out='docs/28_assets/p1-acceptance-2026-09-22';
mkdirSync(out,{recursive:true});
const records=dirs.flatMap((dir,i)=>readdirSync(dir,{withFileTypes:true}).filter(d=>d.isDirectory()&&d.name.startsWith('phase')).flatMap(d=>readdirSync(join(dir,d.name)).filter(f=>f.endsWith('.json')&&f!=='plan.json').map(f=>({...JSON.parse(readFileSync(join(dir,d.name,f))),phase:d.name,sessionId:sessionIds[i]})))).filter(r=>r.state==='settled');
const sceneData=(raw,validation)=>{
 try{const x=JSON.parse(raw);return {scenes:x.scenes,thought:x.inner_thought,strict:true};}
 catch{
  const start=raw.indexOf('{');let depth=0,quoted=false,escaped=false;
  for(let i=start;i>=0&&i<raw.length;i++){
   const c=raw[i];if(escaped){escaped=false;continue;}if(quoted&&c==='\\'){escaped=true;continue;}if(c==='"'){quoted=!quoted;continue;}if(quoted)continue;
   if(c==='{')depth++;if(c==='}'&&--depth===0){try{const x=JSON.parse(raw.slice(start,i+1));return {scenes:x.scenes,thought:x.inner_thought,strict:false};}catch{break;}}
  }
  return {scenes:validation?.scenes??[],strict:false};
 }
};
const groups=Array.from(Map.groupBy(records,r=>`${r.phase} / ${r.job.caseId} / 반복 ${r.job.rep} / 턴 ${r.job.turn+1}`),([id,rs])=>({id,mode:rs[0].mode,input:rs[0].input,
 answers:rs.map(r=>({variant:r.job.promptVersion.split('-')[0],sourceHash:r.sourceHash,sessionId:r.sessionId,id:r.job.id,status:r.result.status,cost:r.result.costUsd,ttfs:r.result.ttfsMs,
 issues:r.result.validation?.issues??[],...sceneData(r.result.raw,r.result.validation),history:r.history.map(h=>({input:h.input,...sceneData(h.raw)}))})).sort((a,b)=>['P0','P1'].indexOf(a.variant)-['P0','P1'].indexOf(b.variant))}));
writeFileSync(join(out,'responses.json'),JSON.stringify(groups,null,2));
const summary=JSON.parse(readFileSync(join(out,'statistics.json')));
const data=JSON.stringify(groups).replaceAll('<','\\u003c');
const html=`<!doctype html><html lang="ko"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>Lucid Chat 프롬프트 비교 기록</title>
<style>body{font-family:system-ui,sans-serif;color:#202124;background:#f6f7f9;max-width:1600px;margin:auto;padding:24px}h1{font-size:24px}p{line-height:1.7}label{display:block;margin:20px 0 8px;font-weight:700}select{width:100%;padding:12px;font:inherit}#input{white-space:pre-wrap;background:#e9edf4;padding:18px;border-radius:8px}.grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(300px,1fr));gap:14px}.card{background:white;padding:18px;border:1px solid #dce0e5;border-radius:8px;overflow-wrap:anywhere}.meta{font-size:12px;color:#62666d}.scene{border-top:1px solid #e5e5e5;padding-top:10px;margin-top:15px}.narration{color:#62666d;font-size:14px}.dialogue{white-space:pre-wrap}.thought{font-size:13px;color:#756451}.issue{color:#a12724;font-size:13px}summary{cursor:pointer}details{margin:14px 0}#scope{font-size:14px;color:#555}@media(max-width:1100px){.grid{grid-template-columns:repeat(2,minmax(0,1fr))}}@media(max-width:650px){.grid{grid-template-columns:1fr}body{padding:14px}}</style>
<h1>Lucid Chat 프롬프트 비교 기록</h1><p>2026-09-22 · Gemini 3 Flash · 최종 P1 수용 확인 · ${summary.total.n}회 · 실제 API 비용 $${summary.total.costUsd.toFixed(6)}</p>
<p id="scope">P0 수리 전 서비스 / P1 최종 서비스 수리본. 로제타16사례는 같은 입력·기록의 비교입니다. 다른 인물·오프닝·다인물·이벤트·비노골적 시크릿 경로와 연속3턴은 P1만 확인했습니다. 관계·상태는 고정하며 장기 기억 검증이 아닙니다. 실패 응답도 포함하고 모델 reasoning은 표시하지 않습니다. phase-event-repair 4회만 이벤트 속마음 지침 수리 후의 결과이며, 앞선 64회는 수리 전입니다. 비이벤트 요청 62개는 최종 코드와 완전히 같습니다.</p>
<label for="case">비교할 상황</label><select id="case"></select><p id="input"></p><main class="grid" id="answers"></main>
<script>const groups=${data};const sel=document.getElementById('case');const el=(tag,text,cls)=>{const e=document.createElement(tag);if(text!==undefined)e.textContent=text;if(cls)e.className=cls;return e;};
groups.forEach((g,i)=>{const o=el('option',g.id+' · '+g.mode);o.value=i;sel.append(o)});
function scenes(parent,data){for(const s of data.scenes??[]){const box=el('section',undefined,'scene');if(s.narration)box.append(el('p',s.narration,'narration'));if(s.dialogue)box.append(el('p',s.dialogue,'dialogue'));if(s.inner_thought)box.append(el('p','속마음: '+s.inner_thought,'thought'));parent.append(box)}if(data.thought)parent.append(el('p','속마음: '+data.thought,'thought'));}
function show(){const g=groups[Number(sel.value)];document.getElementById('input').textContent=g.input;const area=document.getElementById('answers');area.replaceChildren();for(const a of g.answers){const card=el('article',undefined,'card');card.append(el('h2',a.variant));card.append(el('p','첫 완성 씬 '+(a.ttfs/1000).toFixed(2)+'초 · $'+a.cost.toFixed(6),'meta'));card.append(el('p',a.sessionId+' · source '+a.sourceHash.slice(0,12),'meta'));if(!a.strict||a.status!=='ok')card.append(el('p',(!a.strict?'엄격 JSON 파싱 실패. ':'')+a.issues.join(', '),'issue'));if(a.history.length){const d=el('details');d.append(el('summary','이 후보의 앞선 대화 '+a.history.length+'턴'));for(const h of a.history){d.append(el('p','입력: '+h.input));scenes(d,h)}card.append(d)}scenes(card,a);area.append(card)}}sel.addEventListener('change',show);show();</script></html>`;
writeFileSync(join(out,'responses.html'),html);
console.log(JSON.stringify({exported:out,calls:records.length,groups:groups.length}));
