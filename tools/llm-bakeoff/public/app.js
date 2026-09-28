import { TOPICS, suggestInput } from './suggestions.js';
const $ = selector => document.querySelector(selector);
const GEMINI = 'google/gemini-3-flash-preview';
const DEFAULT_VARIANTS = ['P0-service-fixed-state-v1','P1-service-repairs-v1','J2-json-v1'];
let suggestions=[];
const token = $('meta[name="lab-capability"]').content;
const state = {fixture:null,models:[],variants:[],cases:[],history:[],results:{},run:null,revealed:false,keyReady:false,busy:false,controller:null,adopted:false,adopting:false,ratings:{},savedRatings:null,mode:$('#mode').value,comparison:$('#comparison').value};
const axes = [['korean','한국어'],['character','캐릭터성'],['context','맥락'],['story','서사·선택권']];
const say = text => { $('#message').textContent=text; };
const el = (tag,text,className) => { const node=document.createElement(tag); if(text!=null)node.textContent=text; if(className)node.className=className; return node; };
const promptComparison=()=>$('#comparison').value==='prompt';
async function api(path,data) {
  const response=await fetch(path,{method:data===undefined?'GET':'POST',headers:{'X-Lab-Capability':token,...(data===undefined?{}:{'Content-Type':'application/json'})},...(data===undefined?{}:{body:JSON.stringify(data)})});
  const value=await response.json(); if(!response.ok)throw new Error(value.error??'요청 실패'); return value;
}
function tab(name) { for(const n of ['compare','prompt','guide'])$('#'+n).hidden=n!==name; document.querySelectorAll('[data-tab]').forEach(b=>b.setAttribute('aria-pressed',String(b.dataset.tab===name))); }
document.querySelectorAll('[data-tab]').forEach(b=>b.onclick=()=>tab(b.dataset.tab));
function budget(b) { $('#budget').textContent=`예산 $${b.limit.toFixed(2)} · 확정 $${b.known.toFixed(4)} · 미확정 예약 $${b.held.toFixed(4)} · 가용 $${b.available.toFixed(4)}`; }
function ratingStatus() {
  $('#ratingStatus').textContent=!state.run?'아직 실행 결과가 없습니다.':JSON.stringify(state.ratings)===state.savedRatings?'이번 턴 평가 저장됨 · 이후 수정하면 다시 저장하세요.':'이번 턴 평가 미저장 · 다음 실행·새 대화 전에 자동 저장됩니다.';
}
function controls() {
  $('#settings').disabled=state.busy||state.adopting; $('#cancel').disabled=!state.busy||!state.controller;
  const ready=!!state.fixture&&(!promptComparison()||state.variants.length>0);
  $('#run').disabled=!ready||!state.keyReady||state.busy||state.models.length===0;
  $('#prepare').disabled=!ready;
  $('#suggest').disabled=!state.fixture;
  $('#useCase').disabled=!state.fixture||!$('#evaluationCase').value;
  $('#run').textContent=promptComparison()?'3개 프롬프트 비교 실행':'3개 모델 비교 실행';
  for(const option of $('#suggestTopic').options){const topic=TOPICS.find(t=>t.id===option.value);option.disabled=!!topic&&((topic.mode&&topic.mode!==$('#mode').value)||(topic.requiresHistory&&!state.history.length));}
  if($('#suggestTopic').selectedOptions[0]?.disabled)$('#suggestTopic').value='mixed';
  for(const id of ['reveal','save','export'])$('#'+id).disabled=state.busy||state.adopting||!state.run;
  $('#reveal').disabled ||= state.revealed;
  document.querySelectorAll('.adopt').forEach(b=>b.disabled=state.busy||state.adopting||state.adopted||!state.run||state.results[b.dataset.slot]?.status!=='ok');
  document.querySelectorAll('.result select, .result textarea').forEach(node=>node.disabled=state.busy||state.adopting);
  ratingStatus();
}
async function status() {
  const s=await api('/api/status'); state.keyReady=s.keyReady;
  $('#connection').textContent=`로컬 연결됨 · API 키 ${s.keyReady?'설정됨':'없음'}`; $('#keyHelp').hidden=s.keyReady; budget(s.budget); controls(); return s;
}
function initialHistory() {
  const f=state.fixture;
  return f?`초기 입장: ${f.initialUser}\n\n${f.introNarration}\n${f.name}: ${f.firstGreeting}`:'';
}
function showFixture(f) {
  state.fixture=f;
  $('#fixtureNotice').textContent=`${f.name}(${f.age}) · ${f.world} · ${f.state}. 대화 기록만 이어지며 운영 DB·에너지·이미지 생성과 연결하지 않습니다.`;
  const content=$('#profileContent');content.replaceChildren(el('h3',`${f.name} · ${f.tagline}`));
  for(const [key,label]of [['role','역할'],['personality','성격'],['tone','말투'],['speechQuirks','말버릇'],['flaws','숨겨진 약점']]){
    content.append(el('strong',label),el('p',f[key],'profile-text'));
  }
  const detail=el('details');detail.append(el('summary','배경·가치관·관계별 행동 지침 원문'));
  for(const [key,label]of [['backstory','배경'],['coreValues','가치관'],['storyBehaviorGuide','관계별 행동 지침']])detail.append(el('h3',label),el('pre',f[key]));
  content.append(detail,el('p',`${f.userProfile}\n난이도: ${f.difficulty}\n출처: ${f.source}\n세계관·장소: ${f.worldSource}`,'muted profile-text'));
  $('#history').textContent=initialHistory();
  if(!$('#input').value)$('#input').value=f.defaultInput;
  controls();
}
const addOption=(select,value,label)=>{const option=el('option',label);option.value=value;select.append(option);};
function providerInfo(model,provider) {
  const e=state.models.find(m=>m.id===model)?.endpoints.find(e=>e.tag===provider);
  return e?`입력 $${e.input.toFixed(3)} / 출력 $${e.output.toFixed(3)} · 100만 토큰 기준 · ${e.tier}`:'가격 상한·JSON 출력 지원 조건을 만족하는 endpoint가 없습니다.';
}
function setProviderOptions(model,provider,reasoning) {
  const m=state.models.find(m=>m.id===model);
  provider.replaceChildren();reasoning.replaceChildren();
  for(const e of m?.endpoints??[])addOption(provider,e.tag,`${e.tag} · $${e.input.toFixed(3)}/${e.output.toFixed(3)}`);
  provider.value=(m?.endpoints.find(e=>e.tag===m.preferredProvider)??m?.endpoints.find(e=>e.tier==='default')??m?.endpoints[0])?.tag??'';
  for(const r of m?.reasoning??['default'])addOption(reasoning,r,r==='default'?'기본 (provider 기본값)':r);
}
function candidates() {
  const isPrompt=promptComparison();
  $('#commonSettings').hidden=!isPrompt;
  $('#comparisonHelp').textContent=isPrompt?'같은 모델과 실행 설정으로 프롬프트를 비교합니다. 같은 버전을 여러 후보에 선택하면 A/A 반복 비교가 됩니다.':'같은 P0 프롬프트를 각 모델에 보냅니다. 모델별 provider와 추론 설정을 선택하세요.';
  if(isPrompt){
    setProviderOptions(GEMINI,$('#commonProvider'),$('#commonReasoning'));
    const update=()=>$('#commonProviderInfo').textContent=providerInfo(GEMINI,$('#commonProvider').value);
    $('#commonProvider').onchange=update;update();
  }
  $('#candidates').replaceChildren();
  for(let i=0;i<3;i++){
    const card=el('div',null,'candidate');card.append(el('h3',`후보 ${i+1}`));
    const model=el('select');model.id=`model${i}`;
    const label=el('label','모델');label.htmlFor=model.id;card.append(label,model);
    for(const m of state.models)addOption(model,m.id,m.name+(m.endpoints.length?'':' · 사용 가능한 endpoint 없음'));
    model.value=isPrompt?GEMINI:state.models[i]?.id??'';model.disabled=isPrompt;
    if(isPrompt){
      const variant=el('select');variant.id=`variant${i}`;const variantLabel=el('label','프롬프트 버전');variantLabel.htmlFor=variant.id;
      for(const v of state.variants)addOption(variant,v.id,`${v.label} · ${v.id}`);
      variant.value=state.variants.some(v=>v.id===DEFAULT_VARIANTS[i])?DEFAULT_VARIANTS[i]:state.variants[0]?.id??'';
      const detail=el('p',null,'providerInfo');const update=()=>{const v=state.variants.find(v=>v.id===variant.value);detail.textContent=v?.description??'';};
      variant.onchange=update;update();card.append(variantLabel,variant,detail);
    }else{
      const provider=el('select');provider.id=`provider${i}`;const reasoning=el('select');reasoning.id=`reasoning${i}`;const info=el('p',null,'providerInfo');
      for(const [text,node]of [['Provider · tier',provider],['추론 effort',reasoning]]){const l=el('label',text);l.htmlFor=node.id;card.append(l,node);}
      const updateInfo=()=>{info.textContent=providerInfo(model.value,provider.value);};
      const update=()=>{setProviderOptions(model.value,provider,reasoning);updateInfo();};
      model.onchange=update;provider.onchange=updateInfo;card.append(info);update();
    }
    $('#candidates').append(card);
  }
  controls();
}
function selectedCandidates(){return[0,1,2].map(i=>promptComparison()?{model:GEMINI,provider:$('#commonProvider').value,reasoning:$('#commonReasoning').value,promptVersion:$(`#variant${i}`)?.value}:{model:$(`#model${i}`)?.value,provider:$(`#provider${i}`)?.value,reasoning:$(`#reasoning${i}`)?.value});}
function input() {return{comparison:$('#comparison').value,mode:$('#mode').value,input:$('#input').value,evaluationCaseId:state.evaluationCaseId??null,history:state.history.map(({input,raw})=>({input,raw}))};}
function showPrompt(value){
  const variants=value.comparison==='model'?null:value.variants??value.preparedVariants;
  const items=variants?.length?variants:[value.prepared??value];
  $('#promptInfo').textContent=[variants?'동일 상태의 후보별 전송본 · 설정 순서 표시 · A/B/C 매핑 비공개':'모든 모델에 보내는 공통 전송본',value.snapshotHash?`상태 스냅샷 SHA-256: ${value.snapshotHash}`:null].filter(Boolean).join('\n');
  $('#messages').replaceChildren();
  items.forEach((p,index)=>{
    const metadata=state.variants.find(v=>v.id===p.promptVersion);
    const panel=el('details',null,'prompt-variant');panel.open=items.length===1;
    panel.append(el('summary',`${variants?`후보 ${index+1} · `:''}${p.promptLabel??metadata?.label??p.promptVersion??'서비스 프롬프트'}`));
    panel.append(el('p',metadata?.description??'기존 서비스에서 조립한 프롬프트입니다.','muted'));
    if(p.changes?.length){const list=el('ul');for(const change of p.changes)list.append(el('li',change));panel.append(el('strong','P0 대비 적용된 변경'),list);}
    else if(p.promptVersion===DEFAULT_VARIANTS[0])panel.append(el('p','P0 기준 전송본 · 적용된 변경 없음','muted'));
    panel.append(el('pre',[`버전: ${p.promptVersion??'미제공'}`,metadata?.parent?`변경 기준: ${metadata.parent}`:null,p.promptHash?`전송본 SHA-256: ${p.promptHash}`:null,`프리셋: ${p.fixture??'미제공'} · ${p.historyLogs??'?'}개 대화 로그 · 출력 한도 ${p.maxTokens??'?'} · 상태 고정`].filter(Boolean).join('\n')));
    for(const [i,m]of(p.messages??[]).entries()){
      const box=el('article',null,'prompt-message');box.append(el('h3',`${i+1}. ${m.role}`));
      if(m.cache_control)box.append(el('p',`캐시 설정: ${JSON.stringify(m.cache_control)}`,'muted'));
      box.append(el('pre',typeof m.content==='string'?m.content:JSON.stringify(m.content,null,2)));panel.append(box);
    }
    $('#messages').append(panel);
  });
}
function scenes(container,list){container.replaceChildren();for(const s of list){const block=el('div',null,'scene');if(s.speaker)block.append(el('strong',s.speaker));if(s.narration)block.append(el('p',s.narration,'narration'));if(s.dialogue)block.append(el('p',s.dialogue,'dialogue'));container.append(block);}}
function emptyCards(){
  $('#results').replaceChildren();state.ratings={};state.savedRatings=null;
  for(const slot of ['A','B','C']){
    const rating={preference:'미평가',scores:{korean:0,character:0,context:0,story:0},note:''};state.ratings[slot]=rating;
    const card=el('article',null,'result');card.id=`card${slot}`;card.append(el('h3',slot));
    const status=el('p','대기 중','status');status.id=`status${slot}`;const response=el('div','응답이 여기에 표시됩니다.','response');response.id=`response${slot}`;
    const metrics=el('pre',null,'metrics');metrics.id=`metrics${slot}`;metrics.hidden=true;card.append(status,response,metrics);
    const preference=el('select');preference.id=`pref${slot}`;for(const p of ['미평가','승','무','패'])addOption(preference,p,p);preference.onchange=()=>{rating.preference=preference.value;ratingStatus();};const pl=el('label','이번 비교 평가');pl.htmlFor=preference.id;card.append(pl,preference);
    const scores=el('div',null,'scores');for(const [key,label]of axes){const box=el('div'),select=el('select');select.id=`${key}${slot}`;for(let n=0;n<=5;n++)addOption(select,String(n),n?`${n}점`:'미평가');select.onchange=()=>{rating.scores[key]=Number(select.value);ratingStatus();};const l=el('label',label);l.htmlFor=select.id;box.append(l,select);scores.append(box);}card.append(scores);
    const note=el('textarea');note.rows=2;note.maxLength=2000;note.id=`note${slot}`;note.placeholder='번역투, 한자 혼입, 반복, 유저 대사 침범 등';note.oninput=()=>{rating.note=note.value;ratingStatus();};const nl=el('label','평가 메모');nl.htmlFor=note.id;
    const adopt=el('button','이 답변으로 이어가기','adopt');adopt.type='button';adopt.dataset.slot=slot;adopt.disabled=true;adopt.onclick=()=>adoptResult(slot).catch(e=>say(e.message));card.append(nl,note,adopt);$('#results').append(card);
  }
}
function result(r){
  state.results[r.slot]=r;const labels={ok:'완료 · 기본 계약 검증 통과',invalid:'계약 위반 · 채택 불가',truncated:'출력 잘림 · 채택 불가',incomplete:'미완료 · 채택 불가',timeout:'시간 초과',cancelled:'중단됨',error:'요청 실패'};
  $(`#status${r.slot}`).textContent=labels[r.status]??r.status;
  if(r.validation?.scenes)scenes($(`#response${r.slot}`),r.validation.scenes);
  else $(`#response${r.slot}`).textContent=r.raw?'최종 검증을 통과하지 못했습니다. 원문은 결과 공개 후 확인하세요.':'표시할 응답이 없습니다.';
  if(r.validation?.issues?.length)$(`#response${r.slot}`).append(el('p',r.validation.issues.join('\n'),'error'));
  if(r.status==='ok'&&r.raw){
    try{JSON.parse(r.raw);}
    catch{$(`#response${r.slot}`).append(el('p','원문 전체 JSON 검사 실패 · 기본 파서가 추출한 장면을 표시합니다.','error'));}
  }
}
function reveal(){
  state.revealed=true;
  for(const r of Object.values(state.results)){
    const money=r.costUsd==null?'미확정':`$${r.costUsd.toFixed(6)}`;const ms=n=>n==null?'미측정':`${(n/1000).toFixed(2)}초`;
    const text=[r.requestedModel,r.promptVersion?`프롬프트: ${r.promptLabel??''} · ${r.promptVersion}`:null,r.promptHash?`전송본 SHA-256: ${r.promptHash}`:null,r.snapshotHash?`스냅샷 SHA-256: ${r.snapshotHash}`:null,`요청 provider: ${r.requestedProvider} / ${r.requestedTier}`,`실제 model: ${r.actualModel??'미제공'}`,`실제 provider: ${r.actualProvider??'미제공'} / tier: ${r.actualTier??'미제공'}`,`추론: ${r.reasoning} · 생략 옵션: ${r.omittedParameters?.join(', ')||'없음'}`,`첫 글자 ${ms(r.ttftMs)} · 첫 씬 ${ms(r.ttfsMs)}`,`수신 완료 ${ms(r.networkMs)} · 전체 ${ms(r.totalMs)}`,`비용 ${money} · 예약 $${r.reservationUsd.toFixed(4)}`,`토큰: ${JSON.stringify(r.usage??'미제공')}`,`종료 사유: ${r.finishReason??'미제공'}`,r.error??''].filter(v=>v!=null).join('\n');
    const metrics=$(`#metrics${r.slot}`);metrics.textContent=text;metrics.hidden=false;
    const details=el('details');details.append(el('summary','원본 응답 JSON'),el('pre',r.raw));$(`#response${r.slot}`).append(details);
  }
  controls();
}
async function saveRatings(reveal=false){
  if(!state.run)return;
  const ratings=JSON.parse(JSON.stringify(state.ratings));
  await api('/api/ratings',{id:state.run.id,ratings,revealed:state.revealed,reveal});
  state.savedRatings=JSON.stringify(ratings);ratingStatus();
}
async function adoptResult(slot){
  const r=state.results[slot];if(!state.run||state.busy||state.adopting||state.adopted||r?.status!=='ok')return;
  if(state.history.length>=40)throw new Error('40턴에 도달했습니다. 새 대화로 시작하세요.');
  state.adopting=true;controls();
  try{await saveRatings();state.history.push({input:state.run.input.input,raw:r.raw,slot,runId:state.run.id});state.adopted=true;}
  finally{state.adopting=false;controls();}
  $('#historyCount').textContent=`채택 ${state.history.length}턴`;$('#history').textContent=initialHistory()+'\n\n'+state.history.map((t,i)=>`${i+1}. 유저: ${t.input}\n채택: ${t.slot} (${t.runId})\n${t.raw}`).join('\n\n');
  $('#input').value='';$('#suggestHint').textContent='';$('#evaluationCase').value='';state.evaluationCaseId=null;caseHint();$('#input').focus();say(`${slot} 답변과 이번 턴 평가를 저장했습니다. 다음 입력으로 세 후보를 다시 비교할 수 있습니다.`);controls();
}
function reset(){state.history=[];state.results={};state.run=null;state.adopted=false;state.revealed=false;$('#historyCount').textContent='채택 0턴';$('#history').textContent=initialHistory();$('#input').value=state.fixture?.defaultInput??'';$('#runInfo').textContent='새 대화 · 다음 비교에서 A/B/C를 다시 배정합니다.';$('#promptInfo').textContent='';$('#messages').replaceChildren(el('p','대사 비교에서 ‘호출 없이 프롬프트 보기’를 누르세요.'));emptyCards();controls();}
function resetSuggestions(){suggestions=[];$('#suggestHint').textContent='';$('#suggestTopic').value='mixed';$('#evaluationCase').value='';state.evaluationCaseId=null;caseHint();}
async function newConversation(){
  if(state.busy||state.adopting)return;
  state.adopting=true;controls();
  try{await saveRatings();const changed=state.comparison!==$('#comparison').value;state.mode=$('#mode').value;state.comparison=$('#comparison').value;resetSuggestions();reset();if(changed)candidates();say('새 대화를 시작했습니다. 이전 턴이 있으면 현재 평가를 저장하며, 이전 결과 파일과 사용 예산은 유지됩니다.');}
  catch(e){$('#mode').value=state.mode;$('#comparison').value=state.comparison;say(`평가 저장 실패로 대화를 유지합니다: ${e.message}`);}
  finally{state.adopting=false;controls();}
}
$('#reset').onclick=newConversation;$('#mode').onchange=newConversation;$('#comparison').onchange=newConversation;
for(const topic of TOPICS)addOption($('#suggestTopic'),topic.id,topic.label);
$('#suggest').onclick=()=>{
  if(state.busy||state.adopting)return;
  const suggestion=suggestInput({topic:$('#suggestTopic').value,mode:$('#mode').value,history:state.history,seen:suggestions});
  if(!suggestion)return;
  suggestions.push(suggestion);$('#input').value=suggestion.text;$('#evaluationCase').value='';state.evaluationCaseId=null;caseHint();
  $('#suggestHint').textContent=`${suggestion.label} · ${suggestion.hint}`;
  say('무료 예문을 입력창에 넣었습니다. 흐름에 맞게 수정하거나 다시 눌러 다른 예문을 고르세요.');
};
function caseHint(){
  const c=state.cases.find(c=>c.id===$('#evaluationCase').value);
  if(!c){$('#caseHint').textContent='사례를 선택하면 확인할 항목을 보여줍니다. 입력 넣기는 자동 전송하거나 대화를 초기화하지 않습니다.';controls();return;}
  const modeLabel=c.mode==='STORY'?'스토리 · V2':'자유 대화 · V1';
  const focus=Array.isArray(c.focus)?c.focus.join(' · '):c.focus;
  $('#caseHint').textContent=[`${modeLabel} · 확인 항목: ${focus??'사례 참고'}`,c.mode!==$('#mode').value?`이 사례는 ${modeLabel}용입니다. 서비스 프롬프트를 먼저 바꾸면 새 대화가 시작됩니다.`:null,state.history.length?'현재 대화가 이어지고 있습니다. 독립 사례로 비교하려면 먼저 새 대화를 누르고 사례를 다시 선택하세요.':'현재 고정 초기 상태에서 실행할 수 있습니다.','확인 항목은 모델에 보내지 않습니다.'].filter(Boolean).join('\n');
  controls();
}
$('#evaluationCase').onchange=caseHint;
$('#useCase').onclick=()=>{const c=state.cases.find(c=>c.id===$('#evaluationCase').value);if(!c)return;if(c.mode!==$('#mode').value)return say('사례에 맞는 서비스 프롬프트 모드를 먼저 선택하세요. 모드를 바꾸면 현재 평가를 저장하고 새 대화를 시작합니다.');$('#input').value=c.input;state.evaluationCaseId=c.id;$('#suggestHint').textContent='';caseHint();say('평가 사례의 입력만 넣었습니다. 전송 전 대화 상태와 확인 항목을 확인하세요.');};
$('#input').oninput=()=>{$('#suggestHint').textContent='';};
$('#prepare').onclick=async()=>{
  if(state.busy||state.adopting)return;
  state.adopting=true;controls();
  try{say('서비스 프롬프트를 조립하고 있습니다…');const request=input();if(promptComparison())request.candidates=selectedCandidates();showPrompt(await api('/api/prepare',request));tab('prompt');say('외부 모델 호출 없이 조립했습니다.');}
  catch(e){say(e.message);}finally{state.adopting=false;controls();}
};
$('#run').onclick=async()=>{
  if(state.busy||state.adopting)return;
  const request={...input(),timeoutMs:Number($('#timeout').value),candidates:selectedCandidates()};
  if(!request.input.trim())return say('비교할 입력을 작성하세요.');if(request.candidates.some(c=>!c.provider))return say('모든 후보에 사용 가능한 provider를 선택하세요.');
  state.adopting=true;controls();
  try{await saveRatings();}catch(e){state.adopting=false;controls();return say(`이전 턴 평가 저장 실패로 새 실행을 중단했습니다: ${e.message}`);}
  state.adopting=false;state.busy=true;state.run=null;state.results={};state.revealed=false;state.adopted=false;state.controller=new AbortController();emptyCards();controls();say('카탈로그·예산을 확인한 뒤 세 요청을 실행합니다.');
  let completed=false;
  try{
    const response=await fetch('/api/run',{method:'POST',headers:{'X-Lab-Capability':token,'Content-Type':'application/json'},body:JSON.stringify(request),signal:state.controller.signal});
    if(!response.ok)throw new Error((await response.json()).error??'실행 실패');
    const reader=response.body.getReader(),decoder=new TextDecoder();let pending='';
    while(true){const {done,value}=await reader.read();if(done)break;pending+=decoder.decode(value,{stream:true});let end;while((end=pending.indexOf('\n'))!==-1){const event=JSON.parse(pending.slice(0,end));pending=pending.slice(end+1);
      if(event.type==='error')throw new Error(event.error);
      if(event.type==='start'){
        if(event.preparedVariants||event.variants||event.prepared)showPrompt(event);
        budget(event.budget);$('#runInfo').textContent=`실험 ${event.id} · ${request.comparison==='prompt'?'동일 모델의 프롬프트 비교':'같은 P0의 모델 비교'} · ${(event.snapshotHash??event.promptHash??'').slice(0,16)} · 상태 고정`;
        say('A/B/C 응답 생성 중입니다. 후보 배정은 공개 전까지 숨깁니다.');
      }
      if(event.type==='first_scene'){scenes($(`#response${event.slot}`),[event.scene]);$(`#status${event.slot}`).textContent='첫 씬 원문 · 최종 정제·검증 전';}
      if(event.type==='result')result(event.result);
      if(event.type==='complete'){state.run=event.run;if(event.run.preparedVariants||event.run.prepared)showPrompt(event.run);budget(event.run.budget);completed=true;say('비교를 완료했습니다. 이번 턴을 평가한 뒤 후보와 지표를 공개하세요.');}
    }}
    if(!completed)throw new Error('결과 스트림이 완료 전에 닫혔습니다. 로컬 결과 파일을 확인하세요.');
  }catch(e){say(e.name==='AbortError'?'중단을 요청했습니다. 원격 호출의 최종 비용이 없으면 예약액이 유지됩니다.':e.message);}
  finally{state.controller=null;state.busy=false;controls();try{const s=await status();if(s.busy){state.busy=true;controls();waitIdle();}}catch(e){say(e.message);}}
};
async function waitIdle(){try{const s=await status();if(s.busy){setTimeout(waitIdle,1500);return;}state.busy=false;controls();}catch(e){state.busy=false;controls();say(e.message);}}
$('#cancel').onclick=()=>{state.controller?.abort();$('#cancel').disabled=true;};
$('#reveal').onclick=async()=>{if(state.revealed||state.adopting)return;state.adopting=true;controls();try{await saveRatings(true);reveal();say('블라인드 평가를 저장했습니다. 모델·프롬프트 버전·비용·지연시간을 공개합니다.');}catch(e){say(e.message);}finally{state.adopting=false;controls();}};
$('#save').onclick=async()=>{if(state.adopting)return;state.adopting=true;controls();try{await saveRatings();say('이번 턴 평가를 저장했습니다.');}catch(e){say(e.message);}finally{state.adopting=false;controls();}};
$('#export').onclick=async()=>{if(state.adopting)return;state.adopting=true;controls();try{await saveRatings();const data=await api('/api/export');const url=URL.createObjectURL(new Blob([JSON.stringify(data,null,2)],{type:'application/json'}));const a=el('a');a.href=url;a.download=`bakeoff-${data.id}.json`;a.hidden=true;document.body.append(a);a.click();a.remove();setTimeout(()=>URL.revokeObjectURL(url),60000);say(`JSON 다운로드를 요청했습니다. 서버의 .local/${data.id}.json에도 저장되어 있습니다.`);}catch(e){say(e.message);}finally{state.adopting=false;controls();}};
window.addEventListener('beforeunload',event=>{if(state.run&&JSON.stringify(state.ratings)!==state.savedRatings){event.preventDefault();event.returnValue='';}});
emptyCards();
try{
  const s=await status();showFixture(await api('/api/fixture'));
  const loaded=await Promise.allSettled([api('/api/catalog'),api('/api/prompt-variants'),api('/api/evaluation-cases')]);
  const errors=[];
  if(loaded[0].status==='fulfilled')state.models=loaded[0].value.models;else errors.push(`모델 목록: ${loaded[0].reason.message}`);
  if(loaded[1].status==='fulfilled')state.variants=loaded[1].value.variants;else errors.push(`프롬프트 목록: ${loaded[1].reason.message}`);
  if(loaded[2].status==='fulfilled'){state.cases=loaded[2].value.cases;for(const c of state.cases)addOption($('#evaluationCase'),c.id,`${c.id} · ${(c.input??'').slice(0,46)}`);}else errors.push(`평가 사례: ${loaded[2].reason.message}`);
  candidates();state.busy=s.busy;controls();if(s.busy)waitIdle();else say(errors.length?errors.join('\n'):'준비되었습니다. 프롬프트 확인은 무료이며, 비교 실행은 API 사용량이 발생합니다.');
}catch(e){say(e.message);}
