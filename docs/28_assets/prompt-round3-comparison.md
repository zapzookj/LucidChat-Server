# D3 씬 경계 후보 — 정확한 변경 구획과 검증

- 작성일: 2026-09-18, 한국 시간.
- 상태: 초안 검토 후 root가 기존 128회 완료를 알리고 별도 구현을 승인했다. 아래 정확한 문구를 `D3-turn-boundary-v1`로 구현했으며, 이 작업에서 유료 실행은 하지 않았다.
- 기준 후보: 동결된 `R2-combined-v1`.
- 부모 R2 동결 당시 sourceHash: `9b4abc2e501aeab4146edec6841bf111b096971e3660b0e8c4fc01beec8270bc`.
- 근거 구분: root는 U2의 허락 뒤 미실행 유저 동작 확정 2회, T2의 기존 분류 질문 대신 새 기호 제안 2회, 이후 R2에서도 미실행 보존 1/2 및 분류 적용 대신 새 표시 제안이 남았다고 보고했다. 정당한 거절·대체 제안일 해석 여지는 별개다. 이 문서는 유료 결과를 직접 읽거나 재평가하지 않았다.

## 구조적 타당성과 적용 범위

STORY의 현행 SCENE SPLITTING은 한 응답을 4~5씬으로 늘리고, ‘질문 → 답 → 추가’를 분기점으로 들며, 예시에서 감정 심화·환경 전환·화자의 마무리를 연달아 보여 준다. 이는 유저가 아직 제공하지 않은 다음 반응까지 진행하거나 필요 없는 감정·새 주제를 추가하는 압력과 양립한다. 다만 ‘질문 → 답 → 추가’는 같은 화자의 내용 분기 설명이므로, 직접 유저 답을 생성하라는 명령이라고 판정하지는 않는다. **턴 경계를 명확히 하는 수정은 타당하지만, 보고된 실패의 원인이라는 인과 결론은 아직 낼 수 없다.**

D3는 R2의 나머지를 그대로 두고 아래 구획만 교체하는 가설이다. STORY에서 한 입력에 대한 NPC와 세계의 현재 반응을 여러 씬으로 나누되, 다음 유저 입력을 전제로 하는 진행은 남겨 둔다. NPC의 자율적 행동·거절·장난과 세계 사건은 기존 규칙 안에서 계속 가능하다.

SANDBOX 일반 대화 Multi-Scene Coherence에도 같은 원리를 적용할 수 있다. 이미 한 턴의 연속성을 선언하고 있으므로, 누락된 유저 턴을 채우지 않는다는 경계와 감정 유지 가능성을 더 명확히 하는 수정이다. 두 모드의 원래 씬 수 계약은 서로 다르므로 STORY의 최소 3·최대 5를 SANDBOX에 새로 부과하지 않는다.

## STORY: 정확한 교체 경계

원본: [StoryDirectorPromptAssemblerV2.java](C:/Users/zapza/Desktop/MuseLab/aichat/src/main/java/com/spring/aichat/service/prompt/StoryDirectorPromptAssemblerV2.java:575)의 `buildSection9Principles`. 실제 로컬 Java bridge로 조립한 R2 첫 번째 system 메시지에서도 동일 구획을 확인했다.

- 시작 anchor, 포함: `# 🎬 SCENE SPLITTING — 한 응답에 4~5 씬` 뒤 LF.
- 끝 anchor, 미포함: `# 🚫 SOUL PRESERVATION RULES (디렉터의 작가 윤리)` 뒤 LF.
- 각각 유일한 anchor여야 하며, 아래 변경 전 전체와 일치할 때만 교체한다. 주변 DIRECTOR PRINCIPLES·SOUL PRESERVATION·DIRECTOR OPTIONS는 그대로 둔다.
- 위쪽 ‘세계가 이끄는 장면’에서 R2가 이미 제거한 ‘내면’ 표현도 그대로 유지한다. P0를 재조립하며 R2 수리를 되돌리지 않는다.

변경 전:

```text
# 🎬 SCENE SPLITTING — 한 응답에 4~5 씬

한 응답은 **4~5개의 씬으로 분할**되어 출력된다 (배열 형식). 각 씬은 *호흡 단위*로 잘게 쪼개라:

**씬 단위 기준**:
- 한 씬 = *한 호흡의 묘사* (3~4 문장 narration + 0~1 대사)
- 화자 변경, 환경 변화, 시간 흐름 같은 *전환점*마다 새 씬 시작
- 같은 화자가 길게 말하는 경우에도 *내용의 분기점*(질문 → 답 → 추가)마다 씬 분할

**씬 분할 예시 (4 씬)**:
<pre>
  [Scene 1] 환경 + 화자의 첫 반응
    narration: 정원에 바람이 분다. 클레어는 시선을 잠시 떨군 채 침묵한다.
    speaker: 클레어
    dialogue: "...왜 그런 말씀을 하시는 거예요?"

  [Scene 2] 같은 화자의 후속 — 감정 심화
    narration: 그녀의 손가락이 미세하게 떨린다. 답을 들으려 하지만 듣고 싶지 않은 표정.
    speaker: 클레어
    dialogue: "저는... 그 말의 무게를 안 보일 만큼 가볍지 않아요."

  [Scene 3] 환경 전환 — 오프스크린 신호
    narration: 멀리 성당 종소리가 울려퍼진다. 곧 저녁 미사다.
    speaker: null
    dialogue: ""

  [Scene 4] 화자의 마무리
    narration: 클레어가 천천히 일어선다. 이미 결심한 사람의 걸음걸이다.
    speaker: 클레어
    dialogue: "오늘은 여기서 마쳐도 될까요. 다음에 다시 뵐 수 있길."
</pre>

**씬 갯수 가이드**:
- 평이한 일상 대화: 4 씬
- 감정 깊은 모멘트 / 환경 전환 동반: 5 씬
- 너무 짧은 응답(2~3 씬)은 시청자를 빈약하게 만들고, 너무 길면(6+) 유저 개입 호흡을 깨뜨린다.
- **씬 갯수는 LLM의 자율 판단** — 위 가이드는 권장이지 강제가 아니다. 단 *항상 최소 3 씬 이상*.

```

변경 후(D3 정확한 문구):

```text
# 🎬 SCENE SPLITTING — 한 응답에 4~5 씬

한 응답은 한 번의 유저 입력에 대한 현재 반응이다. 이를 **권장 4~5 씬, 최소 3·최대 5 씬**의 배열로 나눈다.
- 한 씬은 한 호흡의 묘사(3~4 문장 narration + 0~1 대사)다. 화자 변경이나 실제 내용·환경·시간의 전환에서 나눈다. 한 씬엔 한 화자만 둔다.
- 씬이 바뀌어도 새 유저 턴이 생기지 않는다. 유저의 다음 대답·동작·선택·반응을 생략된 턴처럼 채우지 않는다.
- 다음 유저 입력을 전제로 하는 후속 진행은 그 입력이 온 뒤에 한다. 이번 응답은 그 경계에서 끝내고, 남은 씬을 채우려고 경계를 넘지 않는다.
- NPC의 자율적인 말·행동과 세계의 사건은 기존 규칙 안에서 가능하다. 유저 선택을 남기는 것은 NPC의 동의·협조·수동성을 요구하지 않는다.
- 입력이나 이미 성립한 사건이 감정 변화를 뒷받침할 때만 변화시킨다. 변화가 없으면 같은 감정과 태도를 유지해도 되며, 씬마다 감정 심화나 새 사건을 의무적으로 추가하지 않는다.

```

특정 캐릭터·좌석·분류·눈 색·정답 행동을 예시로 주지 않는다. 최소 3씬과 narration 분량은 유지하므로, 다음 유저 입력 뒤에 속할 진행을 만들어 씬 수를 맞추는 대신 경계 앞의 현재 반응을 원래 분량 안에서 나눠야 한다. 짧은 출력에 대한 새 예외가 아니다.

## SANDBOX: 일반 대화의 대응 구획

원본: [CharacterPromptAssembler.java](C:/Users/zapza/Desktop/MuseLab/aichat/src/main/java/com/spring/aichat/service/prompt/CharacterPromptAssembler.java:803)의 실제 `buildOutputFormat → buildStoryOutputFormat` 경로, `isEvent == false` 분기. 함수 이름과 달리 일반 SANDBOX에서도 이 경로를 쓴다.

- 시작 anchor, 포함: `## ⚠️ Multi-Scene Coherence Rules (STRICTLY ENFORCE):` 뒤 LF.
- 끝 anchor, 포함: `4. **Context awareness:** Each scene must build on the previous scene's context.` 뒤 LF.
- 아래 변경 전 전체를 정확히 한 번 일치시킨다. 항상 다음 heading이 있거나 메시지가 여기서 끝난다고 가정하지 않는다. 현재 fixture에서는 끝이지만, 설정에 따라 뒤에 Scene Illustration 안내가 붙을 수 있으므로 뒷내용은 보존한다.
- 앞의 Speaker Rule(일반 대화는 speaker=null, 새 NPC 도입 금지), JSON 예시·필드 설명은 교체 범위 밖이다.
- 이벤트의 Event Scene Coherence, 2~4씬 안내, ONGOING/RESOLVED 상태 규칙은 이 초안 범위 밖이다.

변경 전:

```text
## ⚠️ Multi-Scene Coherence Rules (STRICTLY ENFORCE):
CRITICAL: Depending on the situation, use several scenes to proceed with the situation in detail.
All scenes in a single response are ONE CONTINUOUS conversation turn.
1. **Speech consistency:** The character's speech style MUST be identical across ALL scenes.
2. **Emotional continuity:** Emotions should progress gradually.
3. **Temporal continuity:** Each scene follows immediately after the previous one.
4. **Context awareness:** Each scene must build on the previous scene's context.
```

변경 후(D3 정확한 문구):

```text
## ⚠️ Multi-Scene Coherence Rules (STRICTLY ENFORCE):
Use several scenes when useful, all within your response to ONE user input.
1. Keep the same speech style and immediate time/context continuity.
2. Do not fill an omitted user turn with the user's reply, action, choice, or reaction.
3. Leave progress that depends on the next user input until it arrives; your own initiative remains allowed.
4. Emotion may stay stable. Change it only when the input or established events warrant it, never merely to fill scenes.
```

기존의 ‘상황에 따라 여러 씬’ 안내와 영어 지시 언어를 유지하고 새 숫자 제한은 추가하지 않는다. 같은 말투·즉각적인 시간 연속성·앞 씬 맥락을 압축해 남기며, 인물의 주도권을 막지 않는다.

## 보존 계약

교체 구간의 길이 비교는 LF 기준 Unicode code point 수/UTF-8 byte 수다. 토큰 수나 과금 예측이 아니다. STORY 구간 끝의 빈 줄도 포함한다.

| 구획 | 변경 전 문자 / byte | D3 문자 / byte |
|---|---:|---:|
| STORY SCENE SPLITTING | 992 / 1813 | 526 / 1158 |
| SANDBOX Multi-Scene Coherence | 544 / 548 | 517 / 521 |

정확한 교체 구간 SHA-256:

- STORY 전: `4b3fb7233ed068d12380d1878ca62945c6d52f448261eb2f4ca4c96a20c93c18`
- STORY 후: `7cc7ec2cbb17aeb96f6f6e434bd149436dcdc0f111e94f95a7e10f6a45b76ae8`
- SANDBOX 전: `0316167c1963b9bb51775ceae760b56f0c764b2b9dd5205b89972afd48ac209a`
- SANDBOX 후: `f3d60d47e5198ca2134f20c67ad80b2d4fe74030a53595145daa18265246a818`

보존 대상:

- STORY: 권장 4~5씬, 최소 3·최대 5씬, 씬당 narration 3~4문장·대사 0~1개, 한 씬 한 화자, 환경 씬의 speaker=null/빈 dialogue, 발화 가능한 캐릭터 이름·ID를 비롯한 원래 화자 규칙.
- SANDBOX 일반 대화: speaker=null, 단일 인물, 원래 상황별 여러 씬 원칙. STORY 숫자 제한은 새로 넣지 않는다.
- R2의 F2/A2/U2/T2/J2 변경 전부: 공식 설정·외형·의복·관계·개성, 서버 사실과 유저 주장 구분, 대사/행동 및 서비스 입장 해석, 미제시 유저 내면·외적 반응 금지, 현재 발화에 기존 맥락 적용, 유효 JSON 예시·원필드 설명.
- 메시지 역할·순서·history·캐시 경계·상태 메시지, JSON 키·타입·enum·캐릭터 ID·상태 변화 조건·선택지·시간·장소·비밀모드 범위.
- 명시적인 MOVE/TIME_ADVANCE/NEXT_SCENE 처리, NPC와 세계의 기존 자율성. 다음 유저 입력 의존 진행만 남기며 모든 자율 진행을 막지 않는다.

## 효과와 한계

1. **행동 침범에는 비교적 직접적인 가설이다.** U2의 경계 지시와 씬 구성 안내를 같은 방향으로 맞추므로 허락·제안 뒤 미실행 행동을 다음 씬에서 실행하는 오류를 줄일 여지가 있다. 어느 씬에서든 미제시 유저 대사·동작·감정·반응을 확정했는지 따로 봐야 한다.
2. **현재 질문 이탈에는 간접적이다.** 씬을 채우려 새 체계나 과제를 만드는 압력은 줄 수 있지만, 앞서 정한 분류를 기억하고 현재 질문에 적용하는 능력은 별개다. 맥락에 맞는 인물다운 거절·되묻기까지 실패로 세면 안 된다. 순종적 정답 전달을 요구하지 않는다.
3. **분량 압력은 남는다.** 최소 3씬, 권장 4~5씬, 씬당 narration 분량과 [10] OUTPUT FORMAT은 그대로다. 짧은 입력에서 반복·군더더기가 계속될 수 있으며, 최소 씬 수 자체의 효과는 검증하지 못한다.
4. **한 구획 교체에도 효과가 섞인다.** 유저 턴 경계 명시, 감정 안정 허용, 클레어의 감정 심화 예시 제거, 지시 길이 축약이 함께 일어난다. 개선되어도 무엇이 원인인지 단정하지 않는다. J2의 4씬 예시 배치 등 다른 구획 영향도 남는다.
5. **일반화 범위가 좁다.** 현재 관측은 root 보고 범위이며 전체 128회 결과는 여기서 열람하지 않았다. 이벤트·다인물·특수 사용자 액션·비밀모드는 이 초안으로 검증되지 않았다. SANDBOX와 STORY 효과도 나눠 본다.
6. **비교 기준은 R2다.** R2와 D3를 같은 상태·history·입력·생성 설정에서 비교해야 이 구획 차이의 증거가 된다. U2/T2와 바로 비교하면 R2의 다른 합성 요소까지 섞인다. 반복 편차·동률·유의미한 차이 없음도 허용한다. 짧음·친절함·감정 억제 자체를 가점으로 삼지 않는다.

## 구현과 무료 검증 기록

초안 때 소스와 로컬 R2 prepare 결과의 구획 일치·anchor 유일성·문구 길이, 당시 후보 Java 파일의 freeze 일치를 확인했다. 이후 승인된 구현은 기존 `PromptVariants.apply(R2, ...)`를 먼저 호출하므로 기존 R2 검사를 통과한 결과만 받는다. 신규 `PromptRoundThree`는 위 BEFORE 전체가 정확히 한 번 나타날 때만 치환하고, 결과의 역할·캐시 및 교체 외 모든 content를 원래 R2와 정확히 비교한다. 기존 `assertPreserved`의 JSON 예외는 확장하지 않았다.

- 구현 전 기존 10후보 × 2모드 × history 0/2/20턴의 60개 메시지를 [round3-legacy-baseline.json](C:/Users/zapza/Desktop/MuseLab/aichat/tools/llm-bakeoff/.local/round3-legacy-baseline.json)에 동결했다.
- 구현 후 60개 모두 JSON 직렬화 바이트가 같았다. D3의 2모드 × history 0/2/20턴 6개는 허용 구간을 역치환하면 R2의 전체 메시지와 동일했다. 결과는 [round3-verification.json](C:/Users/zapza/Desktop/MuseLab/aichat/build/bakeoff/round3-verification.json)에 저장했다.
- 독립 정적 검토에서 부모 경로·anchor 실패 처리·역할/캐시/history/출력 보존·모드별 씬 수·인물 자율성에 관한 구현 결함은 발견되지 않았다. 독립 검토자가 유료 결과는 열람하지 않았다.
- 원래 R2 동결 당시 PromptVariants SHA-256은 `7601ec5c66d584a518e663b9be2c740297d441cab54ebd0180a01f3a913a515f`였다. D3 등록으로 이 파일은 변경되며, PromptRoundTwo는 `def1983fa91dc901ac4f0f12354323616cbf50e2cabdc1bf3d35e18478ab97c4`를 유지한다.

모델이 씬 수와 유저 경계를 실제로 준수하는지는 아직 확인되지 않았다. 무료 조립·guard 검증과 이후 실제 생성 평가는 구분한다. 이 작업에서는 운영 src/main, engine, runner, 기존 round 2 비교 문서 및 유료 결과를 변경하지 않았다.

관련 기준: [round 2 변경문구 및 검증 기록](C:/Users/zapza/Desktop/MuseLab/aichat/docs/28_assets/prompt-round2-comparison.md). 이 문서는 새 후보 채택이나 성능 향상을 결론내리지 않는다.

## 최종 검증 완료 기록

- `.\gradlew.bat prepareBakeoff bakeoffSmoke --console=plain` 통과. 11후보 × 2모드 × 초기/20로그의 기존 smoke와 D3 guard 4조합이 통과했다.
- guard는 정상 R2 부모를 수용하고, old 구획의 내부 변경·누락·중복, 메시지 수·role·cache 변경, 모든 비대상 history/state 변경, 대상 구획 밖 앞뒤 문구·JSON key 변경을 실제로 거부했다.
- 최종 빌드 후 기존 60건 바이트 일치, D3 6건 역치환 일치 및 양 모드 JSON 예시의 JSON.parse/Java validate를 다시 확인했다. STORY 예시는 4씬(로제타 3 + 환경 1), ID 101을 유지하고 SANDBOX 예시는 1씬·speaker=null을 유지한다.
- Node 전체 75개 통과는 root가 실행·보고했다. 이 하위 작업에서는 중복 실행하지 않았다.
- D3 구현·smoke를 포함한 최종 sourceHash: `a1ac928fd2046dad8cfeb4477ba352469375118b76b672823483859c1db2b5c4`. [동결 파일](C:/Users/zapza/Desktop/MuseLab/aichat/build/bakeoff/round3-source-freeze.json).
- PromptVariants.java: `5f20936cdcb288ba8b9df3742d309b90cf0ec2f4d04a6d8ece82fbac180473cf`
- PromptRoundTwo.java: `def1983fa91dc901ac4f0f12354323616cbf50e2cabdc1bf3d35e18478ab97c4`
- PromptRoundThree.java: `fa2123f3386076570685a5c55e6fb0cc0443d3d5d401ebff007dafd15ccd7f68`
- PromptBridge.java: `fe8d65c0e6571eb5405b85bbb0c15b022fd3b85de857cab6ec6735e10b456831`

문구·구획·바이트 보존과 guard 발동은 검증했지만, D3의 생성 품질·실제 유저 선택 보존 개선은 후속 유료 비교 전까지 미확인이다. 후보 소스는 이 상태로 동결한다.
