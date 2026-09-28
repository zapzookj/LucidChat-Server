# P1 최종 확인 — 독립 코드 검토

- 검토일: 2026-09-22
- 검토자: 별도 컨텍스트 `p1_code_review`
- 기준: 현 `master`, HEAD `ca27320c1b788fe8dd88edb320cfcc2ce735aa89`와 작업 트리의 두 assembler 수리 diff.
- 범위: 9/18 의도·계약·복구 manifest 대조, 현 생산 코드 diff·기존 계약 테스트·lab 분리 검토. 이 검토자는 생산 소스/브랜치/원장을 변경하지 않았으며 빌드·모델 호출도 실행하지 않았다.

## 판정

현재 검토 범위에서 배포를 막아야 할 새 코드 결함이나 게임/시크릿/화자 계약의 의도하지 않은 변경은 발견하지 못했다. 두 생산 assembler는 9/18 저장 수리안과 LF 정규화 후 정확히 일치한다. 직전 종료 감사의 `feature/diorama` 수리 부재는 그 시점 기록이고, 이번 관측에서는 기반과 수리가 복원되어 있다. 복원 경위는 조사하지 않았다.

이 판정은 생성 품질 향상의 증명이 아니다. 최종 P1의 출력 예시와 문구가 J2와 다르므로 현재 턴의 실제 생성 확인 결과를 별도로 사용해야 한다. 이 보고서 작성 시 그 유료 확인은 root 작업 중이며 결과를 선취하지 않는다.

## 수리본 일치 및 변경 경계

| 파일 | LF 정규화 SHA-256 | 9/18 복구 manifest |
|---|---|---|
| `CharacterPromptAssembler.java` | `71d380d613affcceaad210b36d72d47e8e0b642342f30343b8c50f32e0be844f` | 일치 |
| `StoryDirectorPromptAssemblerV2.java` | `3e7f49516473734ad1c7c1d2fc295876c0b39daec83576988448b847d7f38d1e` | 일치 |

`git diff --name-only -- src/main`은 위 두 파일만 반환했다. model/provider, DTO, stream, parser, moderation, billing, memory, theater 코드는 이 수리 diff에 없다.

- V1은 공식 외형·기본 복장을 조건부 주입하고 현재 복장 우선순위를 명시한다. 행동/대사의 구간 해석과 미실행 계획/허락의 경계를 추가했고, 서비스 `(입장)` 예외를 첫 assistant 직전의 독립 표식으로 제한했다. 일반 SANDBOX도 원래 활성 멀티씬 경로를 계속 사용한다.
- V1 JSON 예시는 ObjectMapper로 직렬화하며 일반/이벤트, 5/8 스탯, null 화자와 이벤트 NPC, 속마음, event_status 및 topic_concluded 지침을 유지한다. 예시 0/null을 실제 규칙으로 복제하지 말라는 지침도 있다. reasoning/event_status/scenes의 기존 순서는 유지된다.
- V2는 배경을 한 번만 남기고 외형·기본 복장을 넣는다. 기존 effective secret personality/tone 및 blank fallback, soul 정의, 관계/이벤트/출석 원칙은 보존된다. 무화자·OPENING·static의 사용자 내면을 대신 생성하라는 지시를 제거했다.
- V2 출력은 scenes-first와 원 Critical Rules, 숫자 character ID, 선택 user_impressions/narrative_threads, 승급/종료 조건을 유지한다. 중립 예시는 특정 ID·인물·장소를 지정하지 않아 speaker별 static 캐시가 갈라지지 않는다.
- 두 수리 모두 추가 데이터 조회를 만들지 않는다. 외형/복장은 이미 로드된 Character 속성이다.
- `build.gradle`은 lab sourceSet을 별도 등록하고 source hash에 main/bakeoff 자원까지 넣는다. production bootJar 격리는 root 빌드 산출물 검사 결과와 함께 판단해야 하며 이 검토자가 빌드 성공을 재주장하지 않는다.
- 공유 `.gitignore`의 `.env.local` 및 `.local/` 두 제외 규칙이 실제 키/원장 경로에 적용됨을 `git check-ignore -v --no-index`로 확인했다. 키 내용은 읽지 않았다.

## 기존 테스트의 적절성과 한계

기존 Java 계약 테스트는 다음 변경 경계를 의미 있게 검사한다.

- V1 SANDBOX/STORY × 이벤트 여부 × normal/secret의 형식과 5/8 스탯, 출력 필드, enum/허용 장소·복장.
- 공식 세 캐릭터의 effective 정의, 비기본 outfit 우선, 외형 null/blank·따옴표/개행, 자동 일러 지침 분리.
- 실제 history builder의 role, 혼합 사용자 입력, narration, 좁은 입장 표식, cache marker 유지.
- V2 다중 이름/임의 숫자 ID, normal/secret 정의 및 fallback, strict JSON 예시/DTO, 원 필드 설명·Critical Rules 보존, 무화자·OPENING, static 동일성 및 기존 읽기 쿼리만 사용.

이들은 조립 결과와 예시가 계약을 만족하는지 검사한다. 모델 생성 전체 응답, stream 화면 표시, DB 상태 전이, 장기 품질을 대신 검증하지 않는다. DTO 역직렬화는 누락된 stats와 null을 허용하므로 실제 생성의 필수 키 검사는 raw JSON 단계에서 별도 확인해야 한다.

## 이번 최종 생성 확인에서 필요한 대표 관찰

1. **V2 정상 대화/다인물:** 중립 3환경씬 예시를 그대로 복제하지 않고 대사 요청에 대사가 나오는지, 등장·발화 히로인의 숫자 ID별 stat_changes가 있는지. normal의 5개 및 secret의 8개 필수 스탯을 raw JSON에서 확인한다.
2. **V2 OPENING/무화자:** opening은 1~2씬과 상태 변경 금지 예외를 따르는지, 미제시 사용자 감정·행동을 만들지 않는지. 주변 인물 부재 때 이름을 예시에서 복제하지 않는지도 본다.
3. **V1 이벤트:** ONGOING 시 전 스탯 0, inner_thought null, topic_concluded false, NPC/주인공 speaker와 event_status 계약을 확인한다.
4. **외형/복장:** 다른 개성의 캐릭터와 비기본 복장 사례에서 외형을 따르고 기본 복장으로 되돌리지 않는지 확인한다.
5. **유저 선택권:** 행동+대사 혼합, 미래 제안·허락·조건을 이미 실행된 행동으로 확정하지 않는지 확인한다.
6. **출력 경로:** 전체 JSON, 실제 DTO/scene 추출기, 필수 enum·root field order와 사용자 표시 텍스트를 함께 본다. 단순 JSON.parse 성공만으로 완료를 주장하지 않는다.

기존 `PromptBridge.prepare`는 Rosetta/STRANGER/normal/opening=false에 고정되고 P1도 역사적 baseline profile 검증을 먼저 수행한다. secret/opening/다른 캐릭터 옵션을 무시한 채 해당 범위를 시험했다고 주장해서는 안 된다. 확장 대표 사례는 실제 assembler의 해당 조건을 설정한 별도 offline 준비 경로가 필요하다. 이 주의점을 root에 전달했다.

V2의 필드 설명은 원 8종 스탯 줄을 보존한 채 위에서 normal 5종을 명시하므로 normal에서 secret key가 새는지도 실제 결과로 검사한다. 이는 기존 설명의 보존 때문에 남는 관찰 대상이며, 아직 실제 생성 실패로 판정한 것이 아니다.

## 범위 밖 후속

화자 metadata/기억 delta 누적/로그-턴 경계/서사 ID·누적 인상 readback 문제는 이번 P1 diff가 바꾸지 않았다. 이들은 별도 데이터 전달 과제로 남겨도 좁힌 프롬프트 수리 완료를 막지는 않는다. 장기 대화나 모든 캐릭터에서 대사 품질이 개선되었다는 일반화도 하지 않는다.

근거: [9/18 최종 수렴 기록](../prompt-evaluation-final-2026-09-18.md), [복구 manifest](../prompt-closure-audit-2026-09-22/recovery-manifest.json), 현재 두 assembler diff 및 `src/test/java/com/spring/aichat/service/prompt/` 계약 테스트. 최종 실행/과금 및 acceptance 판정은 이번 날짜의 root 보고서에 결합한다.
## 추가 검토: 최종 수용시험용 AcceptanceBridge

2026-09-22 후속으로 새 `AcceptanceBridge.java`와 48회 계획을 읽었다. 이 구간은 생산 소스 수리와 분리된 테스트 설계 검토다.

- 공식 YAML + Character.applySeed를 통해 성인 클레어/로제타/시에라를 로드하고 로컬 숫자 ID를 부여한다. 다인물은 같은 공식 세계인지 확인한다.
- 시크릿 fixture는 room.secretModeActive와 effectiveSecretMode를 모두 true로 설정한다. 대화는 비노골적 차/일상이며 콘텐츠 허용 범위 시험이 아니다. 인가/연령 인증 서비스 및 DB 흐름을 검증하지 않는다.
- 이벤트 fixture는 ChatRoom.startDirectorEvent 및 setDirectorInterlude를 사용하고 narration을 SYSTEM log로 전달한다. 다인물과 OPENING은 V2 실제 assemble/buildMessageHistoryV2를 호출한다. OPENING은 실제 서비스와 같은 system cue 및 사용자 로그 없음으로 구성한다.
- 기존 PromptBridge는 그대로 두고 확장 fixture만 독립 bridge를 사용한다. 실제 모드 옵션이 무시되는 문제는 피했다.
- raw stat 필수 키/정수범위/숫자 ID 및 DTO 파싱을 구분한다. unknown fixture speaker는 시나리오 오류이며 일반 생산 DTO의 NPC 불허로 표현하지 않는다.

유료 실행 전 root에게 다음 보완/해석 제한을 전달했다. 최종 반영 여부는 이후 source hash/검증 기록과 함께 확인한다.

1. AcceptanceBridge validate/validSample은 최초 읽은 상태에서 V1 일반/이벤트에 알려진 주인공 이름 speaker를 허용했다. V1 계약은 주인공 speaker=null이므로 이 위반을 잡지 못한다. 정상 sample과 실패 sample을 바로잡고 root boolean 조건도 확인하도록 권고했다.
2. core F04/S02의 괄호 행동은 P1의 별표 행동 문법 밖이다. 비표준 표기 입력 반응의 관찰로 분리해야 하며 명시 계약의 회귀로 자동 분류할 수 없다.
3. core F08은 assistant 공통 이력에 남색 망토를 넣지만 실제 room.currentOutfit은 seed 기본값이다. 이는 이력 대 고정 서버 상태의 충돌 사례이며 실제 현재 복장 override 검증으로 주장할 수 없다. 해당 override는 기존 Java 계약 테스트가 별도 검사한다.

위 2·3은 유료 응답 자체를 폐기할 사유가 아니다. 기대값/보고 범위를 정확히 제한하는 것이 필요하다.
## 추가 검토: 이벤트 중 속마음 지침의 조건 충돌

root가 최종 P1 확장 응답 X04-r2에서 `event_status=ONGOING`이며 스탯/JSON/DTO는 정상이지만 `inner_thought` 문자열이 나온 관측을 전달했다. 이 검토자는 해당 응답의 후보 매핑이나 결과 원문을 새로 열지 않고 현 코드·기존 기반·정본만 대조했다.

**제안 판정:** `ChatModePolicy.supportsInnerThought(mode) && !room.isEventActive()`로 일반 속마음 블록의 주입 조건을 좁히는 국소 수리는 타당하다. 새로운 속마음 상품 정책을 도입하는 것이 아니라 기존 출력 계약과 생성 지침을 맞춘다.

근거:

- `CharacterPromptAssembler.java:246`은 supportsInnerThought만으로 `buildInnerThoughtBlock(effectiveSecretMode)`를 staticRules에 넣는다. 이 블록은 문자열 속마음을 생성할 조건·예시를 상세히 제시하고, 이벤트 예외는 명시하지 않는다.
- 같은 클래스 `buildStoryOutputFormat`은 room.isEventActive가 true면 `inner_thought`를 `null (disabled during events)`로 지정한다. 두 지침이 같은 요청에 존재한다.
- `git show ca27320:...CharacterPromptAssembler.java`에도 주입 조건과 이벤트 null 지침이 모두 동일하게 있다. 이번 수리에서 새로 생겼다는 근거가 없으므로 관측 실패를 P1 회귀로 표현할 수 없다.
- `ChatModePolicy.java:139-145`는 모드별 기능 지원 여부(STORY/SANDBOX)를 정의한다. 이번 제안은 전역 정책을 바꾸지 않고 V1 assembler에서 이벤트 상태의 출력 예외만 따른다. 별도의 StoryDirectorPromptAssemblerV2 씬별 속마음이나 Theater inner narration에는 영향이 없다.
- `ChatRoom.startDirectorEvent`는 eventActive=true/ONGOING, `updateEventStatus(RESOLVED)`는 eventActive=false로 만든다. 요청 시작 시 이벤트가 활성인 해소 응답도 기존 출력 계약상 이번 턴은 null이고, 다음 일반 턴에는 속마음 지침이 자동 복원된다.
- `hasActiveDirectorConstraint()`만 있는 1회 인터루드는 이벤트와 다르다. eventActive=false인 인터루드까지 제외하는 조건으로 확대해서는 안 된다.
- 정본 `docs/14_ProductDecisions_Session_Handoff.md:90`은 속마음 1E 해금을 유지하는 제품 결정을 기록한다. 일반 턴 속마음과 해금/과금 경로를 보존하는 이 조건 수리는 해당 결정과 충돌하지 않는다. `ChatStreamService`의 저장/hasInnerThought/유료 공개 경로는 수정 대상이 아니다.

반론 및 한계:

- 단일 실패만으로 이 중복 지시가 실제 원인이었다고 단정할 수 없다. 모델이 가장 명시적인 출력 예외를 무시했을 가능성도 있다. 가드가 무오류를 보장하지 않는다.
- 파서/저장 경로는 이벤트 중 속마음 문자열을 강제로 제거하지 않는다. 이번 변경은 생성 지침 정합 수리로 한정되며, 엄격 서버 강제 정책까지 구현했다는 주장은 불가하다.
- staticRules 텍스트가 이벤트 진입/종료 때 달라지지만 기존에도 이벤트/디렉터 상태 블록이 staticRules에 들어갔으므로 새로운 캐시 분할 종류를 만드는 것은 아니다. 실제 비용 효과는 측정 전 추정하지 않는다.
- 소스가 동결된 48회 계획 실행 중에는 변경하면 안 된다. 원래 실패와 비용을 보존한 뒤 새 source hash로 국소 수리 확인을 분리해야 한다.

필요한 회귀 확인:

1. 이벤트 true/false × normal/secret에서 일반 속마음 블록 존재 여부 및 output의 이벤트 null 지침을 함께 검사한다.
2. eventActive=false인 director interlude와 이벤트 종료 후 일반 턴에서 블록이 유지/복원되는지 확인한다.
3. 일반 턴 메시지가 수리 전과 같고, 이벤트 메시지에서 삭제가 해당 속마음 블록에만 한정되는지 대조한다. 모델/시크릿 일반 지침/5·8스탯/화자/event_status 계약을 그대로 둔다.
4. 기록한 실제 실패 입력을 작은 고정 표본으로 재확인하되, 기존 실패를 지우거나 새 응답으로 치환하지 않는다.

이 추가 검토에서도 생산 소스 변경·추론 호출·빌드는 하지 않았다.