# 19-assets. 결정 확정 기록 (2026-08-21, 종원)

> [`decision_agenda.md`](decision_agenda.md) 22건에 대한 종원 답변. **이 표가 확정 정본**이다 — 안건 문서의 '추천'은 제안이고, 여기 적힌 것이 결정이다.
> 미답 항목은 명시했다. 결정 불요 33건은 [`decision_agenda.md`](decision_agenda.md) §D 그대로 유효하다.

## A. 착수 전 필수

| # | 안건 | **확정** | 비고 |
|---|---|---|---|
| 1 | 부팅 블로커 · 극장 엔딩 후퇴 기준선 | **해소** | `MongoConfig` 한 줄 픽스 적용 + 로컬 `bootRun` 실기동 검증 완료(`Started AichatApplication in 22.86s`). 후퇴 기준선 불요. 단 docs/18 §2-A에 0단계 '컨텍스트 기동 확인' 추가는 유효 |
| 2 | 엔딩 게이트 구멍 + 잠긴 방 처분 | **(b)+(c)** · 데이터 마이그레이션 **불요** | ★종원 확인: 프로드의 엔딩 도달 데이터는 실유저가 아니라 **베타·본인 테스트분**이다. 선행 프로드 쿼리도 불요. V28에서 이 항목 제외 |
| 3 | 구독 중복 활성 행 정리 | **V28 부분 유니크 인덱스만** | 같은 이유로 기존 행 정리 산식·보상 불요 |
| 4 | 지급 실패 결제 처분 | **(b)+(c)** | `markPaid` 커밋 분리 + `PAID_UNDELIVERED` 상태 + 감사로그. 재지급 큐 UI는 런칭 후 |
| 5 | 구독 티어 이월 산식 | **(b) 금액 비례 환산** | 잔여일 × 14,900/24,900을 상위 티어 30일에 가산. 다운그레이드는 범위 밖 |
| 6 | 환불 시 회수 실패 처리 | **(c)+(나)** | 최근 회차만 환불 허용 + 회수 실패는 예외·감사로그로 승격 |
| 7 | 시크릿 노출 토글 범위 | **(b)** | 미드나잇 패스 카드도 같은 env 토글에 묶어 심사 중 미노출. 해제 시점은 '승인 PG의 성인 콘텐츠 정책 확인 이후'로 명문화 |
| 8 | 시크릿 상점 진입면 | **전 캐릭터 확장 + 대상 선택 UI 제거** | = (a)+(b). 시크릿 해금을 계정 단위로 확정(코드의 user-global 판정과 정합), 극장 상점 시크릿 탭 숨김 |
| 9 | UGC 나이 필드 | **위임 → 아래 §C 판정** | |
| 10 | 레거시 CG 트랙 처분 | **(c) 전용 노브 분리** | `legacy.illustration.theater-auto-cg-enabled` 신설(기본 off) + `IllustrationService.generateAutoIllustration` 진입부 게이트. 화면 결과는 (a) 완전 동결과 같되 **되돌릴 수 있고** 판정이 '소멸'이 아니라 '게이트차단'으로 남는다. 설명은 [`../19_Register_Rejudgment.md`](../19_Register_Rejudgment.md) §H |
| 11 | V1·V2 장소 어휘 | **(a) enum 확장 + V2는 흡수** | ①.11 '옛 사당'은 V2와 어휘를 맞춰 `ANCIENT_SHRINE`으로 통일 |
| 12 | §G-5 시크릿 전용 복장 | **성인인증 게이트 불요** (실물 수위 확인) → 아래 §E | ★종원: "복장 시스템 자체가 레거시" — 재판정 결과 §E |
| 13 | BRANCH `eventContext` 신뢰 경계 | **(나) hiddenSystem 복귀 + (a) branchToken + (c) 임시 400** | |
| 14 | BRANCH 과금 폴백 | **(c) 우선, 불가 시 (a)** | 가격표를 TTL이 아니라 directiveId/turn 멱등 키로 묶어 '만료' 개념 제거 |

## B. 배치 중 필요

| # | 안건 | **확정** |
|---|---|---|
| 15 | legacy 게이트 상태 클라이언트 전달 | **(a) features 플래그** — "되켤 일은 없으리라 보지만 혹시 모르니" |
| 16 | 씬 일러 좌표계·보존 | **(b) 좌표계 비의존 판정 + playthroughSeq 보존** (마이그레이션 불요) |
| 17 | 극장 리롤 과금 · §C#6 경계 · 미드나잇 초기 스탯 | ③ **500/100 유지** — 스탯에 따른 스토리 변화가 크고 그걸 자유롭게 풀고 싶다는 판단. ⚠ ①(리롤 과금액)·②(§C#6 경계) **미답** |
| 18 | 승급 세리머니 진동 완충 | **(b) 단계별 세리머니 1회 기록** (도달 이력 컬럼, V28~) |
| 19 | 동일인 다계정·CI 중복 | **(B)+(나)** — provider 안내 리다이렉트 + CI 중복만 사유 구분 |
| 20 | 승인 후 텍스트 수정 정책 | **(A)** 수정 허용 + 심사 대상 필드 변경 시 `PENDING_PUBLIC` 자동 회귀. 캐릭터 텍스트·월드 lore·월드 장소 3경로 동시 적용 |
| 21 | UGC 좀비잡 회수·보상 | **① 누끼 재개 + (b) 서버가 실패로 마킹한 잡만 환불** |
| 22 | `dynamicRelationTag` 존폐 | 미답 (나중) |

## C. 안건 9 — UGC 나이 필드 (위임받아 판정)

**종원 제안**: 나이 필드 도입 + 19세 미만은 하드 거부 대신 '시크릿 모드 심사 요청'만 차단.

**판정: choke point 선택은 옳고, 게이트 위치는 바꾼다. 19세 미만 제작 개방은 이미지 아웃바운드 필터가 생길 때까지 보류를 권한다.** 근거는 §F.

확정 실행 항목(현행 19+ 정책 유지 전제이므로 개방 여부와 무관하게 지금 가능):
- **A.** `SecretModeService.isCharacterSecretEligible`(:96-100)에 `age != null && age >= 19` 조건 추가 — **단 이것은 V1 SANDBOX·엔딩·V1 씬일러 축만 덮는다**(§F 참조).
- **A′.** V2 STORY 축은 별도 — `ChatStreamServiceV2:176`·`:1157`, `StoryV2Service:847`, `SceneRequestService:230`이 1-arg + `world.secretAllowed`만 검사하고 캐릭터 자격을 조회하지 않는다. 캐스트 전수 나이 검사를 별도 항목으로 세운다.
- **B.** `AdminUgcReviewService.review()`의 `secretApprove` 분기(:187-198)에 age 조건 + 400. 지금은 전제조건이 **하나도 없다**(바로 위 `publishApprove`는 `PENDING_PUBLIC`을 강제하는데 비대칭).
- **C.** `requestSecretReview` 차단은 넣되 **"게이트가 아니라 안내"**로 문서·커밋에 명시.
- **D.** `age`를 `UgcCharacterSpec`·`createUgc`에 배선(Stage 0이 이미 산출하고 위저드가 표시까지 하는데 바인딩에서 버려진다) + `Age: null` 리터럴 억제.
- **E.** 기존 UGC 캐릭터는 age가 전부 null → **게이트를 켜는 순간 기존 승인 캐릭터의 시크릿이 전면 차단**된다. 백필 또는 `null` 유예 정책을 A 배포와 **같은 릴리즈**에 넣어야 유료 해금 보유 유저가 영향받지 않는다.

## D. 안건 10 — 재질의 (설명은 [`19_Register_Rejudgment.md`](../19_Register_Rejudgment.md) §H)

## E. 안건 12 후속 — 복장 시스템

**"복장 시스템이 레거시"는 절반만 맞다** — 실측으로 두 층이 갈린다.

- **해금 문법(`baseOutfits`/`acquaintanceUnlock…`/`friendUnlock…`/`loverUnlockOutfits` + `getAllowedOutfits` 계열)은 진짜 레거시** — 시드가 3캐릭(airi·taeri·luna)뿐이고 나머지 7캐릭은 전부 주석, UGC는 복장 1종이라 문법이 성립하지 않으며, `legacy.unlock.relation-gated=false`로 LOCK이 이미 죽었다. **폐지 가능.**
- **`outfit` 값 자체는 레거시가 아니라 에셋 네임스페이스 키다** — `CharacterDisplay.jsx:31`이 `{outfit}_{emotion}.png`로 스탠딩 URL을 조립한다. 값을 없애면 공식 10캐릭 + 전 UGC S3 자산 리네임이 따라오고, 극장 5경로가 outfit을 실어 나르므로 §C#6 무변경 제약에도 걸린다. **유지.**
- **씬 일러(생존 트랙)는 복장을 전혀 읽지 않는다** — `service/illustration/scene/` 7파일 전수 grep에서 `outfit` **0건**. 복장은 시크릿 BM 축에서 값어치가 0이고, 복장 폐지는 씬 일러에 무영향이다.

**NEGLIGEE 성인인증 판단** — 종원 판단이 실물과 일치한다. `airi/negligee_*.png` 12장(airi 전용), 흰 레이스 베이비돌 + 반투명 가운, 가슴골·배꼽 노출, **유두·성기·교접부 노출 없음**. 같은 캐릭터 비키니(`swimsuit_*`)와 동등하거나 피부 노출이 오히려 적다. → **성인인증 하드 게이트는 과잉.** 안건 12는 (b)가 아니라 **'해금 문법 폐지로 흡수'**로 종결한다 — `availableOutfits`를 '스프라이트가 실존하는 목록'으로 재정의하면 안건 자체가 소멸한다.

**레지스터 미등재 신규 3건(FE 자산)** — ① `Outfit.SWIMWEAR` ↔ 자산 `swimsuit_*.png` 불일치로 airi·taeri 수영복 30장이 **완전 사문**이고 프리로더가 방마다 404를 15건 낸다(E-2.4의 FE 쌍둥이). ② `airi/date_surpirse.png` 파일명 오타 → DATE·SURPRISE 스탠딩 404. ③ negligee·pajama가 12장뿐(dumbfounded·pleading·sulking 부재)이라 15감정 프리로드 시 복장당 3건 추가 404.

## F. 안건 9 판정의 근거 — 실측

**① 현행은 이미 19세 미만을 하드 거부한다. 제안은 완화다.**
`UgcModerationService.java:50 MIN_CHARACTER_AGE = 19` · `:121-123 age < 19 → blocked("UNDERAGE_CHARACTER")`, 그리고 `ConceptStructuringService.java:55`가 Stage 0 프롬프트에 **"반드시 19 이상의 성인"**을 명령한다. 즉 오늘의 UGC 캐릭터는 전부 '19+ 선언'으로 태어난다. 제안은 구멍을 닫는 안이 아니라 **제작 정책을 여는 안**이다.

**② '심사 요청 차단'은 게이트가 아니다.**
`AdminUgcReviewService.java:187-198`의 `secretApprove` 분기에 상태 전제조건이 없다(주석이 "신청 없는 캐릭터에도 부여 가능"을 명시). 신청을 막아도 `POST /admin/characters/ugc/{id}/review {secretApprove:true}` 한 방이면 `secretEligible=true`가 된다. **게이트는 서버측 최종 판정에 있어야 한다**는 이 저장소의 성문 규약(CLAUDE.md §2-4, beta-activate 사고)과 같은 형태다.

**③ 개방의 실익이 거의 없고, 리스크는 '인식 입증'이다.**
하드 거부를 유지해도 창작자가 잃는 것은 "나이 칸에 17을 쓸 자유"뿐이다 — 교복·학원 배경·앳된 외모는 **지금도 만들어진다**(외형 태그·복장·서사 어디에도 나이 제약이 없고, 하드 키워드에 **"고등학생"·"여고생"조차 없다** — src 전체 grep 0건). 반대로 허용하면 `age=16` 행과 무검열 시크릿 이미지가 같은 시스템에 남는다. 아청법 맥락에서 그 행은 방어 자료가 아니라 **인식(認識) 입증 자료**다. docs/16 §B가 스스로 못박은 대로 이 사업의 안전지대는 법전이 아니라 'de facto 선 + 집행 관용'인데, 그 위에서 자발적으로 미성년 레코드를 만들 이유가 약하다.

**④ 종원의 탈옥 우려는 코드상 근거가 있다. 그리고 나이 필드로는 닫히지 않는다.**
- 노말 모드 프롬프트에 **성적 묘사 금지 지시가 없다** — `buildSecretModeBlock`(:371-392)은 시크릿일 때 *허가를 추가*할 뿐이고 대응하는 금지 블록이 없다. 텍스트 수위를 실제로 억제하는 것은 **업스트림 모델 정렬뿐**이고, 모델을 바꾸면 조용히 사라진다.
- 이미지는 반대로 서버 강제가 있다(`ScenePromptAssembler.java:40-42` SFW_BAN) — **텍스트만 무방비**라는 비대칭.
- 채팅 모더레이션 기본 off(`application.yml:61`), 켜더라도 시크릿이면 즉시 통과, 임계는 **유저 입력만** 검사 — 모델 출력 검사는 어느 경로에도 없다.
- 이미지 네거티브 3종 전부에 loli/child/underage 계열이 **없다**. docs/16 §C의 minor-appearance 필터는 미구현이다.
- `UgcVlmPrefilterService`는 자문·비동기·기본 off이고 트리거가 `requestPublish`뿐 — **secret-request에는 붙어 있지 않다**.

**⑤ 그래도 개방하겠다면 최소 조건 3개** (disagree & commit 대비)
① `age < 19`면 `isCharacterSecretEligible` 영구 false — 심사 자체를 자격 밖으로. ② **이미지 트랙 SFW 강제** — 캐스트에 19세 미만이 하나라도 있으면 방 시크릿 상태와 무관하게 `sfw=true` 고정. ③ **age는 생성 후 불변**(편집 경로를 열면 세탁된다). 이 셋을 넣어도 ④의 텍스트 표면은 그대로 남는다.

**⑥ 잔여 리스크 — 추천안을 택해도 남는 것**
자기신고 나이는 방어가 아니라 표기다("19세 여고생"은 전부를 통과한다) · 시크릿 씬 일러는 **심사 이후 런타임에 매번 새로 생성**되므로 심사 대상이 아닌 이미지가 무제한 생성되는 구조 · 창작자 텍스트 편집이 심사를 무력화(안건 20 (A)로 닫힌다) · 극장 `TheaterDirectorNoteService.java:169`가 raw `isSecretModeActive()`로 모더레이션을 우회(현재 무해한 이유는 극장 시크릿이 리터럴 false 고정 + 모더레이션 off라는 **두 우연의 곱**).
