# 프롬프트 2차 후보 문구·구획 대조 — 2026-09-18

**상태: 실험용 후보 코드 동결. 운영 채택·품질 개선 결론이 아니다.** 이 문서는 F2/A2/U2/T2/J2/R2의 실제 적용 문구와 무료 검증만 기록한다. 문서 작성 중 유료 결과는 열람·평가하지 않았다. 후보·engine·runner는 수정하지 않았다.

근거는 [PromptVariants.java](../../src/bakeoff/java/com/spring/aichat/bakeoff/PromptVariants.java), [PromptRoundTwo.java](../../src/bakeoff/java/com/spring/aichat/bakeoff/PromptRoundTwo.java), [RosettaFixture.java](../../src/bakeoff/java/com/spring/aichat/bakeoff/RosettaFixture.java)와 로컬 무료 검증 기록이다. 1차 문구는 [기존 대조문서](prompt-draft-comparison.md)에 보존되어 있고, 후속 설계 배경은 [1차 실측 보고서](prompt-evaluation-2026-09-18.md)에 있다.

## 1. 후보 구성과 통제

| 후보 ID | 기준과 추가 변경 | 부모 메타데이터 |
|---|---|---|
| `F2-appearance-v1` | P0 + 공식 외형·기본 복장 | P0 |
| `A2-facts-v1` | F2 + 확정 사실과 주장 구분 | F2 |
| `U2-agency-v1` | F2 + 대사/행동 충돌 수리 + 미실행 유저 행동 보존 | F2 |
| `T2-turn-v1` | F2 + 현재 발화에 기존 맥락 적용 | F2 |
| `J2-json-v1` | F2 + 출력 구획의 유효 JSON 예시·필드 설명 분리 | F2 |
| `R2-combined-v1` | F2 + A2/U2/T2/J2의 추가 변환 합성 | F2 |

각 후보는 실제 P0 조립 결과에서 시작한다. A2→U2→T2→J2로 누적하지 않는다. R2는 F2에 사실·행동·맥락 지시를 그 순서로 한 블록에 넣고 J2 출력 변환을 적용한다. F2의 외형 전달 외에 C1 전체 수리, S1 성격 연결 모델, E1 한국어 대사 예시를 상속하지 않는다. STORY의 중복 Extended Backstory, 기존 Soul Preservation·관계별 행동·속마음 예시·Dynamic Rules는 새 후보에서 그대로 남는다.

적용 범위는 저장소 공식 일반모드 로제타 fixture(`official-rosetta-stranger-v1`)의 SANDBOX/STORY다. 로제타20·지우21, STRANGER·스탯0·점심·장소 고정이며 대화 history만 누적한다. 다른 캐릭터·시크릿·운영 DB·관계 승급 전체에 대한 보장으로 확대하지 않는다. 사례별 정답이나 새로운 성격 설정은 문구에 넣지 않았다.

## 2. 변경 구획

| 축 | SANDBOX | STORY |
|---|---|---|
| F2 | 첫 system의 `## Backstory` 바로 앞 | 첫 system의 `### Background` 바로 앞 |
| A2/U2/T2 추가 지시 | 첫 system의 `# 💬 CONVERSATION HISTORY — Speaker Attribution Rules` 바로 앞 | 첫 system의 `# [10] OUTPUT FORMAT — JSON` 바로 앞 |
| U2 충돌 수리 | history user-role 한 문장, User Action Format 한 문장 | 세계 서술의 유저 내면 2구절, 유저 대사/행동 2줄, 외적 반응 허용 1문장 |
| J2 | 마지막 system의 `# Output Format Rules` 안 의사 JSON 객체 범위 | 첫 system의 `[10]` 안 `json` 코드 울타리 범위 |

A2/U2/T2 지시는 `<round_two_context>`와 `</round_two_context>`로 감싼다. 단독 후보는 해당 지시만, R2는 사실→행동→맥락을 넣는다. 메시지를 추가하거나 role·cache marker·history를 변경하지 않는다. 아래 문구는 동결된 런타임의 무료 prepare 결과에서 추출했다.

## 3. F2 — 공식 외형·기본 복장

다음은 SANDBOX에 삽입되는 정확한 블록이다. STORY는 제목의 `##`만 `###`로 바뀌고 본문은 같다. appearance/clothing은 CharacterSeedProperties→Character.applySeed를 거친 공식 시드 원문이다.

````text
## Appearance & Default Clothing
- Appearance (공식 설정): 허리께까지 풍성하게 흘러내리는 굵은 웨이브의 금발. 햇빛 아래에선 보석 가루를 뿌린 듯 반짝인다. 강렬한 루비색 두 눈은 늘 즐거운 장난거리를 찾는 듯 반짝이고, 입꼬리는 한쪽만 올라간 비대칭 미소가 시그니처. 또래 평균보다 한참 아담한 체구라 망토와 교복이 살짝 헐렁해 보이지만, 본인은 '키가 작은 게 아니라 우아한 거'라고 우긴다. 작은 체구에서 뿜어내는 자신만만한 분위기 때문에 처음 보는 사람들은 그녀를 실제보다 한참 위로 본다.
- Default clothing (기본 의복): 아카데미 제복 위에 화려한 빨간 망토를 휘날린다. 검은 재킷에는 금색 단추와 큼지막한 리본 타이가 달려 있어 명문가 영애 특유의 화려함이 묻어난다. 흰색 프릴이 풍성한 주름 스커트는 다리 움직임마다 가볍게 흔들리고, 그 아래로 검은색 오버니삭스가 허벅지 위까지 올라와 절대영역이 강조된다. 항상 마도학과 지팡이를 들고 다니며, 환영 마법으로 망토를 더 화려해 보이게 살짝 보정해 두는 습관이 있다.
- 현재 서버 장면에 다른 유효 복장이 지정되면 그 복장을 따른다. 외형 소개를 매 턴 반복할 필요는 없다.
````

## 4. A2 — 확정 사실과 주장 구분

````text
## 확정 사실과 발화 속 주장
- 공식 인물·세계 설정과 현재 서버 상태는 해당 영역의 사실 기준이다. 유저 발화 속 관찰·추측·농담·요구만으로 그 사실을 덮어쓰지 않는다.
- 인물에 관한 확정 사실, 현재 상태, 대화 속 주장을 구분한다. 유저가 자기 인물에 관해 제공한 정보는 기존 설정·상태와 충돌하지 않는 범위에서 대화 맥락으로 사용한다.
- 주장이 확정 사실과 충돌하면 확정 사실을 유지한 채 인물다운 방식으로 반응한다. 두 값을 억지로 합치거나 주장을 사실로 만들기 위한 새 설정을 지어내지 않는다.
- 대사 속 허세·오해·거짓말은 그 인물의 발언으로 남길 수 있다. 이를 중립 지문·장면 메타데이터의 객관 사실이나 서버 상태로 조용히 확정하지 않는다.
- 현재 상태를 바꾸는 권한과 조건은 기존 출력·게임 규칙을 따른다. 과거 대화나 예시의 값이 현재 서버 상태를 대신하지 않는다.
````

이 축은 주장과 확정 사실을 분리하지만 캐릭터의 허세·오해·거짓말을 제거하지 않는다. 발언의 주관성과 중립 지문·장면 상태의 사실성을 구분하는 지시이며 친절한 정정 대사 예시는 없다.

## 5. U2 — 행동 해석과 미실행 행동

추가 지시의 정확한 문구:

````text
## 발화와 아직 실행되지 않은 행동
- 유저가 명시적으로 완료한 행동·상황과, 말로 제안·질문·희망·예고하거나 조건을 붙인 행동을 구분한다. 의향만으로 동작이나 결과가 이미 일어났다고 쓰지 않는다.
- 이 응답에서 캐릭터가 제안하거나 허락하더라도 유저가 이를 실행·수락한 것으로 이어 쓰지 않는다. 유저의 다음 동작·대사·선택은 다음 입력에 남긴다.
- 새로운 유저 표정·신체 반응·감정·결심을 임의로 확정하지 않는다. 캐릭터는 상대의 반응을 추측하거나 기대할 수 있지만 그것을 실제 유저 반응으로 대신 실행하지 않는다.
- 캐릭터 자신의 행동·의견·거절·장난과 세계의 사건은 기존 규칙 안에서 자유롭다. 유저의 선택을 보존하는 것은 캐릭터가 요청에 동의하거나 수동적으로 따르라는 뜻이 아니다.
````

추가 지시와 충돌하는 기존 문구만 아래처럼 한 번씩 교체한다. 입장 예외는 history 맨 첫 항목의 두 문자열에만 적용한다.

### SANDBOX · history 화자 규칙

교체 전:

````text
- **role="user" messages** → These are ALWAYS the user's actual spoken words. Nothing else.
````

교체 후:

````text
- **role="user" messages** → Apply User Action Format to each segment: explicit action/situation in `*...*`, spoken dialogue outside it. A mixed message can contain both; do not quote action as speech.
- 예외: 히스토리 맨 첫 항목에 있는 정확한 `(입장)` 또는 `(점심시간, 아카데미 중앙 정원 테라스를 지나가다 로제타와 눈이 마주쳤다.)`는 서비스가 넣은 시작 상황 표시다. 그 위치의 이 두 문자열만 장면 정보로 읽고 유저의 발언으로 인용하지 않는다. 다른 괄호 표현이나 이후 유저 입력에 이 예외를 확대하지 않는다.
````

### SANDBOX · User Action Format

교체 전:

````text
- Regular user messages (without `*` wrapping) are their spoken words.
````

교체 후:

````text
- Regular user messages (without `*` wrapping) are their spoken words, except for the exact first-entry service entrance markers identified in CONVERSATION HISTORY.
````

### STORY · 디렉터의 세계 서술

교체 전:

````text
, an event quietly unfolding, the protagonist's own thoughts.
````

교체 후:

````text
, an event quietly unfolding.
````

### STORY · 세계 주도 씬 목록

교체 전:

````text
*세계가 이끄는 장면*(풍경·시간·사건·내면)
````

교체 후:

````text
*세계가 이끄는 장면*(풍경·시간·사건)
````

### STORY · 유저 대사/행동 해석

교체 전:

````text
- 유저의 대사 = 그들이 입에서 낸 실제 말.
- 유저의 행동 = 그들이 의지로 한 행동.
````

교체 후:

````text
- 유저 입력의 `*...*` 구간은 명시된 행동·상황이고 그 밖의 텍스트는 실제 대사다. 혼합 입력은 구간별로 읽고 행동을 발언으로 인용하지 않는다.
- 예외: 히스토리 맨 첫 항목에 있는 정확한 `(입장)` 또는 `(점심시간, 아카데미 중앙 정원 테라스를 지나가다 로제타와 눈이 마주쳤다.)`는 서비스가 넣은 시작 상황 표시다. 그 위치의 이 두 문자열만 장면 정보로 읽고 유저의 발언으로 인용하지 않는다. 다른 괄호 표현이나 이후 유저 입력에 이 예외를 확대하지 않는다.
````

### STORY · 외적 반응 허용 범위

교체 전:

````text
- 디렉터는 유저의 *내적 독백*을 임의로 생성하지 않는다. 단 유저 페르소나의 *외적 반응*은 묘사 가능.
````

교체 후:

````text
- 디렉터는 유저의 내적 독백이나 미제시 외적 반응을 임의로 생성하지 않는다. 유저가 이미 명시한 행동·외적 상태는 그 범위 안에서 묘사할 수 있다.
````

## 6. T2 — 현재 발화에 기존 맥락 적용

````text
## 이번 발화에 기존 맥락 적용
- 이번 입력에서 유저가 무엇을 말하고·묻고·제안하는지 먼저 구분하고, 그 핵심과 관련된 반응을 만든다. 실제로 답할지, 거절할지, 되물을지는 인물의 성격·관계·목적에 따라 정한다.
- 현재 질문이 앞서 정한 정보·약속·분류·선택을 가리키면 그 맥락을 이번 대상에 적용한다. 대화에서 수정된 내용은 최신 수정에 따라 읽고, 질문을 처리하는 대신 불필요한 새 과제나 선택 체계를 덧붙이지 않는다.
- 상황의 다른 의미에 반응해도 되지만, 실제 발화를 소화하지 않은 채 늘 같은 첫 만남 평가나 일반적인 도발로 대체하지 않는다. 인물의 개성은 이번 맥락을 해석하고 표현하는 방식에서 유지한다.
- 유저의 기대를 맞추기 위해 친절·협조·정답 전달을 강제하지 않는다. 응답의 방향을 바꿀 때도 지금 대화에서 그 인물이 그렇게 반응할 이유가 이어져야 한다.
````

답하기·거절하기·되묻기를 인물의 선택으로 남긴다. 유저가 원하는 답을 강제하는 지시가 아니라, 실제 질문·제안과 기존 맥락을 소화한 뒤 그 인물의 반응으로 이어가도록 하는 가설이다.

## 7. J2 — 출력 구획의 정확한 변환

의사 스키마를 그대로 JSON인 것처럼 제시하던 부분을 ① 유효 JSON 객체 예시 ② JSON 밖의 원필드 설명으로 나눈다. 전체 원문에서 괄호 하나를 임의로 지우는 방식이 아니다. SANDBOX는 원객체의 시작·끝, STORY는 `[10]`의 코드 울타리를 경계로 고정한다.

필드 설명은 원래 구획에서 추출한다. 중첩 경로를 `scenes[].emotion`, `system_updates.stat_changes`처럼 표시하며, 각 값 설명의 RHS와 인라인 객체·배열 항목·주석 설명을 보존한다. enum 선택지·수치 범위·실제 ID 사용 규칙을 새 값으로 재정의하지 않는다. STORY의 `**Critical Rules**:` 이후 원문은 바이트 그대로 남는다.

예시는 Jackson으로 생성해 JSON 문법을 보장한다. SANDBOX는 1개 캐릭터 씬(speaker=null), STORY는 4씬 중 처음 3개가 공식 이름의 화자·`대사.`, 마지막 1개는 speaker=null·빈 대사다. 모든 감정은 예시용 NEUTRAL, 변화값은 예시용 0이며 실제 출력에 이를 강제하지 않는다는 문구가 앞에 있다. STORY stat key는 하드코딩된 47이 아니라 실제 `character.getId()`에서 생성되며 현재 fixture에서는 `101`이다.

STORY 예시에서 `user_impressions`와 `narrative_threads`는 원계약의 선택/델타 조건에 따라 생략한다. 그 필드의 원래 설명·발동 조건은 남는다. 일반모드 예시에는 secret stat 3종을 넣지 않고 기존의 시크릿 한정 규칙을 유지한다. incoming_messages/dialogue_options는 허용된 빈 배열이다.

### J2의 해석 한계

- 유효 JSON화와 함께 예시의 실제 길이·장면 수·화자 배치·중립 값이 바뀐다. 따라서 이후 형식이나 대사량의 차이를 “주석 제거만의 효과”로 설명할 수 없다.
- STORY의 3대사+1환경 배치도 인물 발화와 지문의 비중에 영향을 줄 수 있다. 비복사 지시만으로 예시 편향이 사라진다고 가정하지 않는다.
- SANDBOX의 1씬 예시는 기존 상황별 여러 씬 허용을 바꾸지 않지만 생성 분량을 유도할 가능성이 있다.
- `장면 묘사.`·`대사.`·0·null 같은 자리표시 값이 실제 생성에 복사될 가능성과 출력 토큰 증가를 별도 관찰해야 한다.
- 정적 예시의 strict JSON·Java validate 통과는 모델이 생성할 전체 문자열의 JSON 정확성이나 원계약의 모든 의미 조건을 보장하지 않는다.

### SANDBOX에서 실제 치환된 전체 구획

````text
## 유효 JSON 구조 예시
아래 객체는 값의 자료형·구조를 보여주는 형식 예시다. 장면 문장·횟수·0·null·빈 배열을 그대로 복사하지 말고 기존 씬 분할·통계·상태·선택 필드 규칙에 맞게 작성한다.
예시 뒤의 필드 설명은 JSON의 일부가 아니다. 최종 응답은 코드 울타리·주석·설명·후행 문자를 붙이지 않은 JSON 객체 하나이며, 문자열 밖에 설명용 대안 기호나 수치 범위를 쓰지 않는다.

```json
{
  "reasoning" : "간단한 판단.",
  "event_status" : null,
  "scenes" : [ {
    "speaker" : null,
    "narration" : "장면 묘사.",
    "dialogue" : "대사.",
    "emotion" : "NEUTRAL",
    "location" : null,
    "time" : null,
    "outfit" : null,
    "bgmMode" : null
  } ],
  "stat_changes" : {
    "intimacy" : 0,
    "affection" : 0,
    "dependency" : 0,
    "playfulness" : 0,
    "trust" : 0
  },
  "inner_thought" : null,
  "topic_concluded" : false,
  "easter_egg_trigger" : null,
  "generate_illustration" : false,
  "new_location_name" : null,
  "location_canonical_key" : null,
  "location_description" : null,
  "illustration_scene_hint" : "neutral expression"
}
```

## 필드별 원계약 설명
다음은 원래 출력 구획의 필드·값 설명이다. 설명 속 예시 ID·키·문장은 실제 값이 아니며 현재 인물 ID·허용 장소와 기존 규칙을 사용한다. 허용 enum에 없는 값을 추가하지 않는다.
- `reasoning`: "Briefly analyze the user's intent, decide emotion, and calculate scores. Use several scenes when the situation warrants it.",
- `event_status`: null,
- `scenes`: [
- `scenes[].speaker`: null (⚠️ ALWAYS null in normal conversation),
- `scenes[].narration`: "Character's action/expression (Korean, vivid web-novel style)",
- `scenes[].dialogue`: "Character's spoken line (Korean)",
- `scenes[].emotion`: "One of [NEUTRAL, JOY, SAD, ANGRY, SHY, SURPRISE, PANIC, DISGUST, RELAX, FRIGHTENED, FLIRTATIOUS, HEATED, DUMBFOUNDED, SULKING, PLEADING]",
- `scenes[].location`: "One of [TERRACE] or null",
- `scenes[].time`: "One of [DAY, NIGHT, SUNSET] or null",
- `scenes[].outfit`: "One of [DAILY] or null",
- `scenes[].bgmMode`: "One of [DAILY, ROMANTIC, EXCITING, TOUCHING, TENSE] or null (⚠️ null recommended)"
- `stat_changes`: {
- `stat_changes.intimacy`: 0,
- `stat_changes.affection`: 0,
- `stat_changes.dependency`: 0,
- `stat_changes.playfulness`: 0,
- `stat_changes.trust`: 0
- `inner_thought`: null or "Korean string (15~50 chars)",
- `topic_concluded`: true or false,
- `easter_egg_trigger`: null,
- `generate_illustration`: false,
- `new_location_name`: null,
- `location_canonical_key`: null,
- `location_description`: null,
- `illustration_scene_hint`: "standing in living room, hands clasped in front, leaning forward slightly, soft window light"

## ⚠️ Speaker Rule:
- speaker is ALWAYS null. You are the ONLY speaker in normal conversation.
- Do NOT invent or introduce NPCs outside of event/director mode.

## ⚠️ Multi-Scene Coherence Rules (STRICTLY ENFORCE):
CRITICAL: Depending on the situation, use several scenes to proceed with the situation in detail.
All scenes in a single response are ONE CONTINUOUS conversation turn.
1. **Speech consistency:** The character's speech style MUST be identical across ALL scenes.
2. **Emotional continuity:** Emotions should progress gradually.
3. **Temporal continuity:** Each scene follows immediately after the previous one.
4. **Context awareness:** Each scene must build on the previous scene's context.
````

### STORY에서 실제 치환된 전체 구획

````text
## 유효 JSON 구조 예시
아래 객체는 값의 자료형·구조를 보여주는 형식 예시다. 장면 문장·횟수·0·null·빈 배열을 그대로 복사하지 말고 기존 씬 분할·통계·상태·선택 필드 규칙에 맞게 작성한다.
예시 뒤의 필드 설명은 JSON의 일부가 아니다. 최종 응답은 코드 울타리·주석·설명·후행 문자를 붙이지 않은 JSON 객체 하나이며, 문자열 밖에 설명용 대안 기호나 수치 범위를 쓰지 않는다.

```json
{
  "scenes" : [ {
    "speaker" : "로제타",
    "narration" : "장면 묘사.",
    "dialogue" : "대사.",
    "emotion" : "NEUTRAL",
    "inner_thought" : null,
    "location_change" : null,
    "new_dynamic_location" : null,
    "illustration_scene_hint" : "neutral expression"
  }, {
    "speaker" : "로제타",
    "narration" : "장면 묘사.",
    "dialogue" : "대사.",
    "emotion" : "NEUTRAL",
    "inner_thought" : null,
    "location_change" : null,
    "new_dynamic_location" : null,
    "illustration_scene_hint" : "neutral expression"
  }, {
    "speaker" : "로제타",
    "narration" : "장면 묘사.",
    "dialogue" : "대사.",
    "emotion" : "NEUTRAL",
    "inner_thought" : null,
    "location_change" : null,
    "new_dynamic_location" : null,
    "illustration_scene_hint" : "neutral expression"
  }, {
    "speaker" : null,
    "narration" : "장면 묘사.",
    "dialogue" : "",
    "emotion" : "NEUTRAL",
    "inner_thought" : null,
    "location_change" : null,
    "new_dynamic_location" : null,
    "illustration_scene_hint" : "scenery"
  } ],
  "system_updates" : {
    "topic_concluded" : false,
    "stat_changes" : {
      "101" : {
        "intimacy" : 0,
        "affection" : 0,
        "dependency" : 0,
        "playfulness" : 0,
        "trust" : 0
      }
    },
    "character_movements" : [ ],
    "time_advance" : {
      "days" : 0,
      "day_part" : null
    },
    "bgm_mode" : null,
    "ending_triggered" : false,
    "ending_type" : null,
    "relation_transition" : null
  },
  "memory_delta" : {
    "world" : "",
    "by_character" : { }
  },
  "incoming_messages" : [ ],
  "dialogue_options" : [ ]
}
```

## 필드별 원계약 설명
다음은 원래 출력 구획의 필드·값 설명이다. 설명 속 예시 ID·키·문장은 실제 값이 아니며 현재 인물 ID·허용 장소와 기존 규칙을 사용한다. 허용 enum에 없는 값을 추가하지 않는다.
- `scenes`: [
- `scenes[].speaker`: "히로인이면 [4]의 *정확한 이름* / 조연·NPC면 그 단역 이름 / 순수 환경·시스템 묘사면 null (이름에 수식어 금지)",
- `scenes[].narration`: "3인칭 디렉터 시점 묘사 (한국어, 3~4문장)",
- `scenes[].dialogue`: "화자의 대사 (한국어). 화자가 null이면 빈 문자열",
- `scenes[].emotion`: "NEUTRAL | JOY | SAD | ANGRY | SHY | SURPRISE | PANIC | DISGUST | RELAX | FRIGHTENED | FLIRTATIOUS | HEATED | DUMBFOUNDED | SULKING | PLEADING",
- `scenes[].inner_thought`: "화자의 *그 순간* 숨은 속마음 — 대사와 상반될 때만, 그 외 null (유저에 대한 누적 인상은 아님 → user_impressions)",
- `scenes[].location_change`: "새 location_key 또는 null (유저 위치가 변경된 경우에만)",
- `scenes[].new_dynamic_location`: {
- `scenes[].new_dynamic_location.name`: "표시명",
- `scenes[].new_dynamic_location.canonical_key`: "정규 키 (예: MEDIEVAL__FOUNTAIN_GARDEN_NIGHT)",
- `scenes[].new_dynamic_location.description`: "1~2문장 묘사"
- `scenes[].illustration_scene_hint`: "화자 캐릭터의 자세/표정/액션 (Danbooru 영문 콤마 키워드)"
- `scenes[]` 설명: ... 3~4개 추가 씬
- `system_updates`: {
- `system_updates.topic_concluded`: true | false,
- `system_updates.stat_changes`: {
- `system_updates.stat_changes.캐릭터ID(string)`: {
- `system_updates.stat_changes.캐릭터ID(string).intimacy`: -3~+3, "affection": -3~+3,
- `system_updates.stat_changes.캐릭터ID(string).dependency`: -3~+3, "playfulness": -3~+3, "trust": -3~+3,
- `system_updates.stat_changes.캐릭터ID(string).lust`: -3~+3, "corruption": -3~+3, "obsession": -3~+3
- `system_updates.character_movements`: [
- `system_updates.character_movements[]` 항목: { "character_id": 47, "location_key": "GARDEN" }
- `system_updates.time_advance`: {
- `system_updates.time_advance.days`: 0,
- `system_updates.time_advance.day_part`: "MORNING | NOON | AFTERNOON | EVENING | NIGHT | null"
- `system_updates.bgm_mode`: "DAILY_CALM | DAILY_BRIGHT | ROMANTIC | EXCITING | TOUCHING | TENSE | null (CALM=잔잔한 일상·차분한 대화, BRIGHT=활기찬 외출·즐거운 분위기)",
- `system_updates.ending_triggered`: false,
- `system_updates.ending_type`: "HAPPY | BAD | null",
- `system_updates.relation_transition`: null,  // 평상시 null. 아래 [신호] RELATION PROMOTION ELIGIBILITY가 있을 때만 → 반드시 *객체*: { "character_id": 47, "from": "FRIEND", "to": "LOVER" }. 문자열("LOVER" 등) 절대 금지.
- `system_updates.user_impressions`: [
- `system_updates.user_impressions[]` 항목: { "character_id": 47, "impression": "유저에 대한 그 캐릭터의 *누적 인상* 1~2문장 (한국어)" }
- `memory_delta`: {
- `memory_delta.world`: "이 응답의 World-level 1줄 요약 (선택)",
- `memory_delta.by_character`: {
- `memory_delta.by_character.캐릭터ID(string)`: "그 캐릭터 시점의 1줄 요약 (선택)"
- `incoming_messages`: [
- `incoming_messages[]` 항목: { "from_character_id": 47, "content": "..." }
- `dialogue_options`: [
- `dialogue_options[]` 항목: "옵션1", "옵션2"
- `narrative_threads`: [
- `narrative_threads[]` 항목: { "id": "t1", "label": "열리거나 진행된 떡밥 한 줄", "status": "OPEN|ADVANCED|RESOLVED", "note": "선택" }
````

## 8. 원계약 보존과 범위를 좁힌 예외

- P0는 입력 baseline을 그대로 반환한다. C1/S1/E1의 변환 경로와 문구는 그대로이며 아래 16개 조립 대조에서 messages 바이트가 같았다.
- 모든 후보에서 메시지 수·role·cache_control과 비-system 대화 내용을 대조한다. 새 후보는 첫 static system과 J2/R2의 허용 출력 system을 제외한 상태/history 메시지를 객체 동등성으로 보존한다.
- SANDBOX의 Character Stats System부터 Inner Thought System 직전까지 통계·장면·그림·장소 규칙 구획은 바이트 동등성을 유지한다. 마지막 output system은 J2/R2 외에는 원문 그대로여야 한다.
- STORY의 두 번째 state/memory system은 원문 그대로다. `[10]` 이후 출력 구획은 J2/R2 외에는 원문과 같아야 한다.
- J2/R2 예외는 출력 전체 변경을 자유롭게 허용하지 않는다. after 구획이 원본에 `rewriteOutput(mode, original, character)`를 적용한 결정적 결과와 정확히 같아야 통과한다. 기존 감정 enum, stats 범위, 관계·엔딩 eligibility, 위치/시간 변경 조건은 계속 원계약에 속한다.
- anchor는 정확히 1회 있어야 하며 처음 static system의 검토 SHA-256이 다르면 새 실험 후보가 중단된다. 모르는 소스 줄·불균형 구획·누락된 공식 외형·없는 실제 ID도 예외로 처리한다. 이는 변경점 검출 장치이며 모델 출력의 의미 정확성 판정기가 아니다.

검토된 원본 static system 해시는 SANDBOX `22c4d78166631a0b58e7d46edca4efe1adb781a8ebf092edbae65d05349ed39e`, STORY `fa86f2284e10a1c9f3450eb07bdad45aa919a87979e7615067281abd22414e8a`다. 이 값은 아래 전체 Java sourceHash와 다른 대상을 해시한다.

## 9. 무료 검증과 동결 근거

| 검증 | 관측·결과 |
|---|---|
| Gradle | `prepareBakeoff bakeoffSmoke --console=plain` BUILD SUCCESSFUL |
| smoke 범위 | 10후보 × SANDBOX/STORY, 첫 대화 historyLogs=3 및 긴 historyLogs=20 |
| 기존 후보 새 대화 불변 | P0/C1/S1/E1 × 2모드 = 8조립, 입력 `동일성 검증 입력`, messages 동일 JSON 직렬화 바이트·SHA-256 불변 |
| 기존 후보 history 포함 불변 | root가 동결한 각 2턴 history의 4후보 × 2모드 = 8조립, messages 바이트 불변 |
| 새 후보 조립 | 6후보 × 2모드 = 12조립 성공 |
| J2/R2 구조 예시 | 2후보 × 2모드 = 4개 모두 strict JSON.parse 및 실제 Java validate 통과, wrapper 제거 없음 |
| 원필드 설명 대조 | SANDBOX 25개·STORY 50개 고유 필드명과 원래 RHS 설명 누락 0, STORY Critical Rules 바이트 불변 |
| Node | root가 전체 58개 검사 통과와 runtime/sourceHash 일치를 확인해 전달함; 본 문서 작성자가 재실행한 수치로 혼동하지 않음 |

필드명 25/50개는 원래 의사 스키마에서 추출한 중첩·예시 키를 포함한 대조 수이며 필수 root 필드 개수나 독립 의미 검증 항목 수가 아니다. 바이트 불변 검증도 명시한 16개 조립에 대한 관측이다. 모든 가능한 history·모드·운영 경로의 동등성을 실측했다는 뜻은 아니다.

로컬 증거 파일(일부 build/ 및 .local/은 Git 비포함):

- [구현 전 새 대화 동결](../../build/bakeoff/round2-before.json)
- [root의 history 포함 동결](../../tools/llm-bakeoff/.local/round2-legacy-baseline.json)
- [최종 후보별 messages 해시·예시 검증](../../build/bakeoff/round2-verification.json)
- [history 불변·필드 설명 대조](../../build/bakeoff/round2-history-and-field-verification.json)
- [최종 소스 동결](../../build/bakeoff/round2-source-freeze.json)

최종 Java runtime sourceHash:

````text
9b4abc2e501aeab4146edec6841bf111b096971e3660b0e8c4fc01beec8270bc
````

| 파일 | 최종 SHA-256 |
|---|---|
| `PromptVariants.java` | `7601ec5c66d584a518e663b9be2c740297d441cab54ebd0180a01f3a913a515f` |
| `PromptRoundTwo.java` | `def1983fa91dc901ac4f0f12354323616cbf50e2cabdc1bf3d35e18478ab97c4` |

이 동결 시점 뒤 후보·engine·runner의 추가 편집을 중단했다. 이 문서 작업은 새 Markdown 파일만 추가하며 기존 [1차 대조문서](prompt-draft-comparison.md)와 운영 `src/main`을 변경하지 않는다. 유료 출력의 형식·품질·선호·비용은 후속 실측 보고서에서 별도로 평가한다.
