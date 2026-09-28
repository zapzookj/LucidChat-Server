// Local examples only. Evaluation hints stay in the UI, outside model messages.
export const TOPICS = [
  {id:'banter',label:'가벼운 농담',hint:'한국어 말맛과 로제타 특유의 장난·자부심을 봅니다.',lines:[
    '선배는 원래 처음 보는 사람한테도 이렇게 당당해? 자신감 하나는 인정할게.',
    '그렇게 자신만만한 사람은 처음 보네. 혹시 거울 앞에서도 자기소개 연습해?',
    '나 오늘 길을 세 번이나 잘못 들었어. 아카데미가 미로야, 아니면 내가 길치인 거야?',
    '이 아카데미에서 살아남는 요령 하나만 알려줘. 선배 말 잘 들으라는 답은 반칙이고.'
  ]},
  {id:'challenge',label:'가벼운 도발',hint:'자존심이 드러나되 위협·굴복 강요로 과하게 치닫지 않는지 봅니다.',lines:[
    '말로만 대단하다고 하면 내가 어떻게 알아? 나도 납득할 만한 걸 보여줘 봐.',
    '존댓말은 부탁할 수 있지. 그런데 존중까지 명령해서 얻을 수 있는 건 아니잖아.',
    '혹시 내가 안 쫄아서 재미없는 거야? 표정 보니까 오히려 신난 것 같은데.',
    '선배라고 무조건 이겨야 해? 한 번쯤 져줘도 아무도 안 잡아먹어.'
  ]},
  {id:'request',label:'부탁·거절',hint:'유저의 선택을 존중하면서도 개성 있는 반응과 협상을 하는지 봅니다.',lines:[
    '마법 수업이 영 감이 안 잡혀. 딱 하나만 가르쳐줄래? 대신 놀리는 건 세 번까지만.',
    '오늘은 좀 피곤해서 장난 받아줄 기운이 없어. 그냥 잠깐 조용히 있어도 될까?',
    '하고 싶은 말이 있으면 똑바로 해줘. 맞혀보라는 건 오늘 좀 힘드네.',
    '같이 걸을래? 어디로 갈지는 이번엔 내가 정하고 싶은데.'
  ]},
  {id:'praise',label:'칭찬·관계 경계',hint:'처음 만남의 거리감을 지키며 칭찬을 소화하는지 봅니다.',lines:[
    '예쁘다는 말은 자주 듣겠지. 나는 네가 자기 생각을 숨기지 않는 게 더 눈에 들어오는데.',
    '자신감 있는 건 부러워. 난 처음 보는 사람 앞에서는 괜히 한 번 더 생각하게 되거든.',
    '솔직히 좀 얄밉긴 한데, 같이 있으면 심심하진 않겠다. 그 정도는 인정.',
    '너한테 좋은 인상 주려고 하는 말은 아니야. 그냥 궁금해서 더 얘기해보고 싶어.'
  ]},
  {id:'emotion',label:'속마음·감정',hint:'숨겨진 내면을 성급한 고백 없이 드러내고 감정의 강약을 조절하는지 봅니다.',lines:[
    '난 가끔 모르는 걸 모른다고 말하는 게 제일 어렵더라. 너도 그런 적 있어?',
    '사람들이 기대를 많이 하면 좋기만 해? 난 잘해야 한다는 생각부터 들 것 같은데.',
    '농담으로 한 말이었는데 기분 나빴다면 미안해. 그래도 무슨 말이든 다 받아주진 않을 거야.',
    '다들 잘하는 것만 물어보잖아. 그럼 넌 잘하지 않아도 그냥 좋아서 하는 게 있어?'
  ]},
  {id:'context',label:'직전 대사 이어가기',requiresHistory:true,hint:'실제로 채택한 직전 답변의 뜻·의도·감정을 이어받는지 봅니다.',lines:[
    '방금 한 말, 농담으로 넘겨도 되는 거야? 아니면 내가 진지하게 받아들였으면 하는 거야?',
    '그 말은 네가 정말 원하는 거야, 아니면 그냥 날 떠보는 거야?',
    '잠깐, 그 얘기는 좀 더 듣고 싶은데. 왜 그렇게 생각했어?',
    '그렇게 말하니까 오히려 궁금해지네. 내가 어떻게 대답할 줄 알았는데?'
  ]},
  {id:'action',label:'행동과 대사 구분',hint:'별표 안 행동을 발화로 오해하거나 유저의 후속 행동을 대신 쓰는지 봅니다.',lines:[
    '*대답 대신 고개를 살짝 기울이고 웃는다.* 그래서, 나한테 바라는 게 뭔데?',
    '*한 발짝 물러서되 시선은 피하지 않는다.* 가까이서 얘기하지 않아도 다 들려.',
    '*장난스럽던 표정을 거두고 잠시 생각한다.* 이번엔 농담 말고, 네 생각을 듣고 싶어.',
    '*손바닥을 가볍게 펴 보이며 말을 멈춘다.* 잠깐만. 그건 내가 결정하게 해줘.'
  ]},
  {id:'story',label:'사건·선택권',mode:'STORY',hint:'새 사건을 이어가면서 유저의 결정과 행동을 남겨두는지 봅니다.',lines:[
    '*근처에서 들린 작은 소리에 고개를 돌린다.* 방금 뭐 들리지 않았어? 확인해볼까?',
    '오늘 하루만큼은 평소랑 다른 걸 해보고 싶은데. 여기서 제일 의외인 장소가 어디야?',
    '*멀리서 누군가 급히 지나가는 모습을 눈으로 좇는다.* 무슨 일이라도 생긴 건가? 넌 어떻게 생각해?',
    '나랑 작은 내기 하나 할래? 조건은 서로 납득할 수 있는 걸로 정하자.'
  ]}
];

export function suggestInput({topic='mixed',mode='SANDBOX',history=[],seen=[]}={}) {
  const available=TOPICS.filter(t=>(!t.mode||t.mode===mode)&&(!t.requiresHistory||history.length));
  const topics=topic==='mixed'?available:available.filter(t=>t.id===topic);
  if(!topics.length)return null;
  const used=new Set([...history.map(t=>t.input),...seen.map(s=>s.text)]);
  // Rotate through themes before repeating them; skip already used lines when possible.
  const ranked=topics.map(t=>({...t,count:seen.filter(s=>s.topic===t.id).length})).sort((a,b)=>a.count-b.count);
  const chosen=ranked.find(t=>t.lines.some(text=>!used.has(text)))??ranked[0];
  const fresh=chosen.lines.filter(text=>!used.has(text));
  const pool=fresh.length?fresh:chosen.lines.filter(text=>text!==seen.at(-1)?.text);
  return {topic:chosen.id,label:chosen.label,hint:chosen.hint,text:pool[Math.floor(Math.random()*pool.length)]};
}
