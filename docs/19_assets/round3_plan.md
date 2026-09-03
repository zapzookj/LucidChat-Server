# 3차 버그픽스 착수 계획

> 근거: 6묶음 조사·적대적 검증 + 완결성/배포 감사 (2026-09-03). **검증 verdict가 정본이다** — 조사만 있고 검증이 반박한 항목은 §1에 넣지 않았다.
> 좌표는 전부 master(`4bc77eb`) 실측값. **문서(레지스터·델타) 좌표는 낡았다 — 착수 시 심볼 grep으로 재확정하라.**
> 신규 마이그레이션은 **V36부터** (실측 확인: `V35__subscription_round_snapshot.sql`이 최신).

---

> ## §-2. 종원 확정 (2026-09-03, 준비 세션)
>
> | # | 안건 | **확정** |
> |---|---|---|
> | 1 | aichat 워킹트리가 `feature/diorama` | **종원이 직접 master로 되돌린다** — 나는 워킹트리를 건드리지 않는다 |
> | 2 | 미푸시 커밋(BE 7 · FE 1 · Admin 1) 처분 | **푸시·배포 먼저** (§0 추천안 그대로) → 프로드 실측 → 3차 픽스 착수 |
> | 3 | 3차 세션 스코프 | **P0·P1 + 자산손실 P2까지** = §1의 32건 · 배치 5개. P2/P3 잔여 ~120건 전수 재판정은 별도 세션으로 이월 |
>
> 나머지 결정 7건(§4)은 배치 1·2 진행에 영향이 없어 **진행하면서 확인**한다.


---

> ## §-1. 준비 세션의 실측 대조 (에이전트 보고를 그대로 믿지 않았다)
>
> 계획서에서 **배치 구성을 바꾸는 4개 주장**을 내가 직접 master 소스로 대조했다. 전부 성립한다.
>
> | 주장 | 대조 | 결과 |
> |---|---|---|
> | **D-5.6** prefetch가 `@Async`인데 `@Transactional`이 없다 | `TheaterService.java:179-224` | ✅ `@Async("theaterPrefetchExecutor")`만 있고 `@Transactional` 없음. `catch`가 `e.getMessage()`만 찍어 **스택트레이스가 안 남는다**(:218-220) — 두 세션을 살아남은 이유. OSIV는 요청 스레드에만 바인딩되므로 async 스레드는 세션이 없다. **단 "100% 실패"는 코드만으로 단정 불가** → §6-1 ① 프로드 로그 30초 grep으로 확정할 것 |
> | **INT-1** `/next-batch` 캐시 HIT 재과금 | `TheaterService.java:136-146` | ✅ 캐시 HIT 분기가 `chargeBatchEnergy(username)`를 **무조건** 호출. `TheaterState:333`의 `markBatchPaid`는 이미 `batchId > lastPaidBatchId` 술어를 갖고 있으므로 **같은 술어를 과금 앞에 두면 끝**(ONE_LINE 확정). ★ 1차 B-5.1이 착취를 막느라 **정상 유저에게 재과금을 얹은 회귀**다 — §D 교훈의 재발 |
> | **E-4.4를 V36 없이 닫을 수 있다** | `TheaterState.completeChapter():352-353` | ✅ `scenesInCurrentChapter = 0`으로 리셋한다. `scenesInCurrentChapter <= 0` 술어가 '이미 마감함'을 정확히 식별. 마이그레이션·엔티티 변경 0 |
> | **델타가 '잔존 P0'로 둔 것이 이미 고쳐져 있다** | `UgcTextLimits.requireMax` · `VerificationController` | ✅ D-3.6은 `UgcCharacterService:106-108`에서 호출됨(조용한 절삭 → 400 완료). C-1.5의 `VerificationController` 존재. **델타는 더 이상 상태 정본이 아니다**(§2-2) |
>
> 그 외 좌표·판정은 조사·검증 에이전트의 보고이며, 각 배치 착수 시 §1-2 규율대로 **심볼 grep으로 재확정**해야 한다.


---

## §0. 배포 선행 — **판정: 푸시·배포를 먼저 한다 (조건부 GO)**

### 판정 근거

| # | 근거 | 무게 |
|---|---|---|
| 1 | **3차 세션 항목 다수의 블로커가 "프로드 실측"이다.** D-5.6(PREFETCH 로그 grep 30초) · D-2.k(ModelsLab status 집합 · 잔존 PENDING 행) · D-18(`MODELSLAB_WEBHOOK_SECRET`·`LUCID_WEBHOOK_BASE` 실제 값) · 9-A′(secret_eligible / 공식월드 UGC 캐스트) · D-5.4(Mongo 중복 씬로그 존재 여부). **프로드가 구 코드로 도는 동안 재는 실측은 3차 픽스의 전제로 못 쓴다.** | 결정적 |
| 2 | 미푸시 7커밋에 **P0 픽스가 들어 있다**(에너지 분할 환불 V32, UGC 좀비 잡, 구독 정합 V33·V35, 지급 실패 결제 V34). 프로드는 아직 이 결함들을 그대로 안고 돈다. 3차 픽스보다 이쪽이 유저 피해가 크다. | 결정적 |
| 3 | 3차 배치가 건드리는 파일(`ChatStreamService`·`TheaterService`·`UserSubscription` 인접)이 미푸시 커밋과 겹친다. 배포하지 않고 쌓으면 **롤백 단위가 13커밋으로 뭉쳐** §5 규약(롤백 단위 분리)이 무의미해진다. | 큼 |
| 4 | 감사 판정도 조건부 GO — V32~V35는 전부 멱등, 제약 이름 미가정, `flyway.enabled: true` 유지(§2-3 함정 없음), **신규 필수 ENV 0건**. | 큼 |

**결론: §0 체크리스트 → 푸시·배포 → 배포 후 실측(§6 표) → 3차 픽스 착수.** 배포 없이 착수하면 묶음 B 전체(7건)와 D-2.k·D-18·9-A′의 심각도조차 확정할 수 없다.

### 배포 전 체크리스트 (순서 엄수)

| # | 항목 | 명령/행동 | 실패 시 |
|---|---|---|---|
| 0-1 | **UGC 좀비 잡 백로그 실측** (감사 F-1) | `SELECT status,count(*) FROM character_creation_jobs WHERE status IN ('CONCEPT_PROCESSING','BASE_PROCESSING','EMOTIONS_PROCESSING','REVIEW_WAIT','POSTPROCESSING','BINDING') AND updated_at < now()-interval '30 minutes' GROUP BY 1;` | 두 자릿수↑면 `.env`에 `UGC_JOB_STALE_SWEEP_MINUTES=480`로 첫 배포 후 단계 하향 |
| 0-2 | **중복 활성 구독 실측** (감사 F-3) | `SELECT user_id,count(*),array_agg(id\|\|':'\|\|type\|\|':'\|\|expires_at) FROM user_subscriptions WHERE active GROUP BY 1 HAVING count(*)>1;` | 1건↑면 실유저 여부 확인 후 종원에게 보상 여부 질의. **V33·V35의 UPDATE는 비가역** |
| 0-3 | **V35 로컬 실기동 검증** (감사 F-4 — 인계문서가 약속했으나 기록 없음) | master 체크아웃 → `JWT_SECRET_BASE64=$(node -e "console.log(Buffer.alloc(32,7).toString('base64'))") ./gradlew bootRun` → `Started AichatApplication in` | prod는 `validate` — 스냅샷 4컬럼 부재 시 **부팅 실패** |
| 0-4 | **수동 백업 1회** (감사 F-5) | `/opt/lucid/backup.sh` | deploy.sh에 사전 백업·자동 롤백 없음 |
| 0-5 | **현재 이미지 sha 기록** | `docker inspect lucid-app --format '{{.Config.Image}}'` | 헬스 90s 실패 시 구 컨테이너는 이미 제거됨 |
| 0-6 | **`.env` 확인** | `PORTONE_WEBHOOK_SECRET`(prod fail-closed) · `MODELSLAB_WEBHOOK_SECRET`(기본값 없음) | 부팅·웹훅 전체가 여기 걸림 |
| 0-7 | **푸시 순서 못 박기** (감사 F-6) | **FE → Admin → aichat(BE)**. 두 프론트는 순수 가산 변경이라 구 BE와 호환. 역순이면 유저가 '지급 대기'를 '결제 실패'로 보고 재구매 | — |

### 배포 후 실측 (§G-2 ③ 확장)

`flyway_schema_history` v35 · `orders_status_check` 6값 · `uq_sub_user_active` 존재 · **`\d user_subscriptions` 스냅샷 4컬럼** · `SELECT id,free_energy,paid_energy FROM users WHERE free_energy<0 OR paid_energy<0` 0건 · 첫 1시간 `[UGC-WORKER] 소실 주입` / `[UNDELIVERED]` 로그량.

### ★ 롤백 절차 정정 (§G-2 ②를 이걸로 교체 — 감사 F-2)

인계문서는 "롤백 전 PAID_UNDELIVERED가 0인지 **확인**"이라 적었으나, **구 코드로는 그 행을 지급도 환불도 할 수 없다**(`markPaid`는 PENDING만, 구 `markRefunded`는 PAID만). 확인이 아니라 **소진**이다.

1. **신 코드 가동 중에** `POST /admin/payments/orders/{uid}/redeliver` 또는 환불로 `PAID_UNDELIVERED`를 0으로 소진
2. `DROP INDEX IF EXISTS uq_sub_user_active;`
3. 이미지 태그 되돌리기 (0-5의 sha)

> 롤백 시 500 나는 범위는 어드민 목록·감시 스캔만이 아니라 **유저의 `/payments/confirm` 재시도와 주문 이력**까지다(`findByMerchantUidForUpdate` · `findByUser_IdOrderByCreatedAtDesc`).
> V33이 끈 중복 구독 행과 V35의 tier 덮어쓰기는 **되돌아가지 않는다**.

---

## §1. 확정 잔여 목록 (CONFIRMED / PARTIALLY BROKEN)

### 1-A. 자산 손실 · 착취면 (최우선)

| ID | 제목 | 심각도 | 규모 | 대표 좌표 | MIG | 종원 결정 |
|---|---|---|---|---|---|---|
| **F1(신규)** | BRANCH auto-respond가 directive 발급 여부 미검증 + `chosenIndex` 생략 시 4E 카드가 1E | P1 | SMALL | `StoryController.java:95-113` / `ChatStreamService.java:1383-1385` | — | △ (400 vs 최대가 폴백) |
| **INT-1(신규)** | `/next-batch` 캐시 HIT 재과금 — 배치 중 새로고침 1회에 1E 추가 차감 | P1 | ONE_LINE | `TheaterService.java:136-145` | — | — |
| **D-2.a** | V2 STORY 스트림 최외곽 catch 무보상 — 2~10E 무보상 소멸 + 고아 USER 로그 | P1 | SMALL | `ChatStreamServiceV2.java:283-286` | — | — |
| **D-2.g** | 이벤트 카드(BRANCH) 최외곽 catch 무보상 — 최대 4E 소멸, FE는 환불된 듯 표시 | P1 | SMALL | `ChatStreamService.java:1556-1559` | — | — |
| **E-4.4** | `/chapter-end` 서버 멱등 가드 부재 — 챕터 무한 스킵 + 인터미션 스태미나 무한 리필 | P1 | SMALL | `TheaterService.java:345-482` | **불요**(대안) | ○ 17-② |
| **INT-2(신규)** | `/batch-consumed` 멱등 부재 — 호감도 델타·씬수 이중 적용, FE 연타로 정상 유저도 도달 | P1 | SMALL | `TheaterService.java:229-338` | — | ○ 17-② |
| **E-7.1.a** | OAuth email UNIQUE 충돌 → 500, 해당 provider **영구 로그인 불가** | P1 | MEDIUM | `OAuth2LoginSuccessHandler.java:132-140/163-172/193-202` | — | 확정(안건19 B) |
| **E-1.2b** | V2 SSE가 없는 `localStorage.refreshToken`을 읽어 **401 시 100% 강제 로그아웃** | P1 | SMALL | `UseStoryV2Stream.js:212-234` | — | — |
| **E-1.2** | axios·SSE 별개 뮤텍스 → 동시 401 시 RTR 오탐 → **전 기기 강제 로그아웃** | P1 | SMALL | `UseChatStream.js:299-326` + `axios.js:22-34` | — | — |
| **E-1.1** | 극장 finalizeChapter 실패 시 `chapterEnding` 영구 잔류 — **완전 무증상 정지** | P1 | SMALL | `useTheaterStream.js:160-166` | — | — |
| **F2(신규)** | 로비·극장 상점이 `onRequestAdultVerify` 미전달 — `SECRET_PRODUCTS_ENABLED` 켜는 날 미드나잇 패스 구매 **완전 무반응** | P1(잠복) | SMALL | `LobbyShell.jsx:325-336` · `TheaterPortalPage.jsx:592-606` | — | — |

### 1-B. 데이터 유실 · 컴플라이언스

| ID | 제목 | 심각도 | 규모 | 대표 좌표 | MIG | 종원 결정 |
|---|---|---|---|---|---|---|
| **D-6.5** | `saveAssistantLog`가 retry·deadletter 없이 직접 save + 속마음 게이트 우회 (3경로 공통) | P1 | SMALL | `ChatStreamService.java:1047-1057` | — | — |
| **D-6.4** | BRANCH 경로 ASSISTANT 로그 유실 → 나레이션만 남는 히스토리 (D-6.5에 흡수) | P1 | ONE_LINE | `ChatStreamService.java:1548` | — | — |
| **E-5.1.b** | BRANCH 나레이션이 길이·인젝션 가드 없이 **visible SYSTEM**으로 영구 저장 → 매 턴 system 롤 재주입 | P1 | MEDIUM | `ChatStreamService.java:1419-1425` + `:1277-1278` | — | 확정(안건13) |
| **E-5.2.a** | `PATCH /ugc/characters/{id}/texts`가 미성년 하드 키워드 게이트 우회 (형제 6곳엔 전부 있음) | P1 | SMALL | `UgcCharacterService.java:38-42/100-124` | — | — |
| **E-6.4** | 어드민 SPA에 **공개 철회 호출처 0건** — 부적절 공개 UGC를 UI로 못 내림 | P1 | SMALL | `CharactersPage.jsx:20-89` · `CharacterAdminResponse.java:5-19` | — | — |
| **E-4.3** | 극장 감독 명령어 LLM 분류기가 판정값을 계산하고 **버림** — 거부 게이트 무력 | P1 | ONE_LINE | `TheaterCommandClassifier.java:319-325` | — | — |
| **D-18** | 웹훅 시크릿 fail-open + 웹훅 URL에 시크릿 미부착 (SSRF·CDN 오염 표면) | P1 | SMALL | `IllustrationWebhookController.java:93-97` · `S3StorageService.java:124-165` | — | ○ §G-6 |
| **9-A′** | V2 STORY 4경로가 캐릭터 시크릿 자격 미조회 — 어드민 setter가 `storyAvailable` 불변식을 뚫음 | P2 | SMALL | `SecretModeService.java:134-142` · `AdminCharacterService.java:37` | — | — |

### 1-C. 정상 유저 UX · 결제 전환

| ID | 제목 | 심각도 | 규모 | 대표 좌표 | MIG | 종원 결정 |
|---|---|---|---|---|---|---|
| **D-5.6** | prefetch가 `@Transactional` 없는 `@Async`라 **LazyInitializationException으로 전량 실패** → latency 마스킹 0% | P1 | SMALL | `TheaterService.java:179-222` · `TheaterDirectorEngine.java:93/218` | — | ○ 살릴까/죽일까 |
| **안건 16** | 씬 일러 '씬당 1회' 게이트가 turnIndex 좌표계에 묶여 리셋 후 5E 구매 잠김 + FE 4곳 축 불일치 | P2 | MEDIUM | `SceneRequestService.java:76-86` · `useSceneIllustrations.js:154` | — | 확정((b)) |
| **E-4.16** | 동적 배경 백필이 `@Deprecated` 2인자 해시 사용 → 새로고침 후 배경 **영구 미표시** | P2 | ONE_LINE | `ChatService.java:274` | — | — |
| **INT-3(신규)** | 극장 에너지 차감 2곳에 `evictUserProfile` 누락 → `/users/me`가 차감 전 잔량 반환 | P2 | ONE_LINE×2 | `TheaterIntermissionService.java:109-111` · `TheaterBranchService.java:626` | — | — |
| **INT-4(신규)** | `TheaterPlayPage.onError`가 토스트를 안 띄움 — E-1.1·B-5.2 자가치유 실패가 **전부 무증상** | P1 | ONE_LINE | `TheaterPlayPage.jsx:273-283` | — | — |
| **D-23** | TX-2 커밋 후 배경/캐시 실패 시 `sendFinalResult` 미전송 + TX-1~TX-2 무보상 창 (정책 주석이 catch를 '결론 난 곳'으로 위장) | P1 | SMALL(축소판) | `ChatStreamService.java:369/391-450/487-494` · `RedisCacheService.java:63-65` | — | — |
| **안건 18** | 승급 세리머니가 경계 스탯 진동(39↔40)마다 무제한 반복 | P3 | SMALL | `ChatStreamService.java:807-844` | **V36** | △ (peak 1컬럼) |
| **D-29b** | `v2DerivedRoomInfo` 死 memo (소비처 0건) | P3 | ONE_LINE | `ChatPageV2.jsx:338-353` | — | — |
| **D-29c** | 극장 난입 API — FE 호출부 0건인데 2E 차감 + Redis TTL 1h 만료 후 복구 불능 | P3 | SMALL | `TheaterInterventionController.java:23-48` | — | ○ 17-② |
| **INT-5(신규)** | `/auth/refresh` 실패가 401이 아닌 **500** — RT 만료라는 정상 상황이 에러율 지표 오염 | P3 | ONE_LINE | `AuthController.java:72` · `GlobalExceptionHandler` | — | — |

### 1-D. 잠복 (현재 미발현 — prefetch 되살릴 때만 발현)

| ID | 제목 | 조건 | MIG |
|---|---|---|---|
| D-5.1 / 5.2 / 5.3 / 5.4 / 5.7 | targetBatchId 미배선 · 가드 키 불일치 · 소비 확정 오염 · 씬로그 이중기록 · **1E 감독 명령어 파괴적 소비** | D-5.6을 ①안(소생)으로 처분할 때만 | — |

> ★ 이 5건은 **D-5.6이 참인 한 실행 경로가 없다**. prefetch를 노브로 끄면 코드 변경 0으로 전부 도달 불가가 된다. §3 배치 4 참조.

### 1-E. 이번 세션 이월 (PARTIALLY지만 실익 낮음)

| ID | 판정 | 이월 사유 |
|---|---|---|
| **D-9** (final_result에 서버 확정 cost·잔량 없음) | PARTIALLY | D-21 `overlayFreshEnergy` + FE `/users/me` 재조회가 이미 덮는다. 자산 손실 0. **(c) onError에만 재조회 3줄**로 축소해 배치 2에 넣거나 이월 |
| **D-24** (배경 생성 실패 복구 0) | CONFIRMED | 피해 = '배경 없음, 0E'. 재시도 도입은 `pollModelsLabUntilComplete` 반환타입 변경 필요 + 하드실패는 결정론적이라 재제출 무익. **주석/private화만** |
| **D-2.k(B)** (비-success→FAILED) | PARTIALLY | 실측 3건 미충족. **실측 없이 손대면 정상 생성을 죽인다** |
| **D-3.4③** (StudioCreateFlow 낙관 잠금) | PARTIALLY | 2.5초 폴링이 이미 자가치유. 낙관 잠금은 영구 잠김 리스크만 추가 → **WONTFIX 권고** |
| **B-6.1** (극장 리롤 무과금) | PARTIALLY(성격 변화) | §4 참조 — 착취 이득이 **0**임이 판명. 주석 정정만 |

---

## §2. 기각 · 문서 정정

### 2-1. REFUTED — 3차에서 손대지 마라

| ID | 검증 판정 | 근거 |
|---|---|---|
| **E-5.2.b** (승인 후 무제한 교체) | ALREADY_FIXED | `Character.java:884-892`에 안건 20 (A) 회귀 불변식 구현 완료. PENDING_PUBLIC은 소유자만 열람(`LobbyService.java:197`) → 착취 시나리오 불성립 |
| **D-5.5** (prefetch 자동노트·CG 중복) | NOT_BROKEN | CG 지출은 안건 10 (c)로 차단(`IllustrationService.java:216-219`, yml:89 기본 false). 잔존 텍스트 노트도 D-5.6 때문에 **현재 0건**. `sceneRefId`는 쓰기만 하고 읽는 곳 0 |
| **D-29a** (createSandbox 死코드) | ALREADY_FIXED | 1차(2026-08-26) 제거 완료. 주석 3줄만 남음 |
| **D-29d** (자동 씬 일러 제거) | GATED_OFF | `trigger: manual` 기본값. 제거 표면이 **7곳**(문서의 3곳 아님)이고 E-2.15b 답 없이는 착수 불가. §1-1 과절단 사고 유형 |
| **안건 11 후속 (a)** | NOT_A_DEFECT | (a)는 완료(Location 5종 + V31 + yml 5쌍 교정). (c)는 **확정이 아니라 배제된 선택지** |

### 2-2. ★ 델타(`rejudgment_delta.md`)가 정본이 아니다 — 착수 전 반드시 정정

감사가 실측한 결과, **델타에 '잔존 P0/P1'로 남아 있으나 이미 수정 완료**인 항목:

```
B-3.2(P0) · B-10.1/10.2(P0/P1) · B-11.1 · B-12 · C-1.5(P0) · D-3.6(P0)
C-1.3/C-2.l · E-3.④.10 · B-9.9/B-9.10 · D-6.6 · E-1.12a/b · E-1.13a
정책 종결: D-2.b(D-23) · C-2.d/C-2.f(PaymentModal 삭제) · F-1.d(중복)
```

> 이대로 두면 3차 세션이 **이미 고친 P0를 다시 파거나(비용), 살아 있는 것을 고쳤다고 믿는(위험)** 양쪽이 다 일어난다.

### 2-3. 개별 문서 정정 항목

| 대상 | 현재 서술 | 정정 |
|---|---|---|
| 델타 D-2.k 행 | "★게이트 뒤가 아니다 / IllustrationService에 legacy 참조 0건" | **거짓**. `IllustrationService.java:216`에 `isTheaterAutoCgEnabled` 게이트 존재(안건 10 (c)). "FAILED 전이 경로 0개"도 **수동 경로 한정** — 자동은 `:576-598`에 3개 있음 |
| 델타 D-2.k 좌표 | — | 수동 경로에는 서버측 완료 감시가 **아예 없다**(`checkStatus` 라이브 폴링 `:294-306` 주석 처리) — 신규 등재 |
| 델타 E-2.10 | 잔존 | GATED_OFF (안건 10 (c)) |
| 델타 E-5.2.b | 잔존 | **수정됨** (안건 20 (A) · `Character.java:889-892`) |
| decision_agenda #13 | "극장 branchToken 설계 1회로 두 트랙" | 극장 branchToken은 1차 완료(`TheaterBranchService.java:327-329`) → 디렉터 트랙만 |
| decision_agenda #16 | "소비처 3곳" | **4곳** — `sceneHistoryMap.js:47-51` 누락 |
| decision_agenda #441 (D-18) | "시크릿 필수화는 어느 노선에서도 유효" | **반증됨** — URL에 시크릿을 실을 수단 자체가 없다. 좌표도 `IllustrationController` → `IllustrationWebhookController` |
| decisions_confirmed §A #3 | "V28 부분 유니크 인덱스" | **V33** (`uq_sub_user_active`) |
| decision_agenda §D D-9 | "재동기화 경로가 없어 새로고침 전까지 복구 안 됨" | **반증됨** — D-21 + FE `/users/me` 재조회 |
| handoff §E-2 | "안건 11 동적 배경 일원화 후속 미착수" | "(a) 완료 · (c)는 배제된 선택지 — 재론하려면 신규 안건" |
| handoff §G-4 | E-7.2 잔여 | BE는 닫힘(`UgcTextLimits.requireMax`). **FE maxLength 미확인** — 빼기 전 실측 |
| **handoff §G-4 신설** | — | **"레거시 노브 부활 선행 조건"** 절: 엔딩 노브(`LEGACY_ENDING_DIALOGUE_ENABLED`)를 켜려면 B-9.1(영속) · B-8.1(재발동 가드) · B-8.3 · B-9.4/9.5/9.7 **6건 묶음**이 선행. E-4.9만 D-5로 충족된 비대칭 상태 |
| **좌표 드리프트** | — | D-2.g `1749/1526→1556` · D-6.4 `1741/1518→1548` · D-6.5 `1209/1032→1047` · D-2.a `280→283` · E-4.4 `224-347→345-482` · `ChatLogPersister`는 `service/persistence/`가 아니라 **`service/stream/`** · `Character.isAccessibleBy` `782→815` · `TheaterState.restoreFromSnapshot` `553-578→553-586` · `SCENE_ILLUST_TRIGGER` yml `162→217` |

---

## §3. 커밋 배치 제안

### 배치 1 — FE 인증·잠금 해제 (프론트 2리포, 서버 무변경)

| 항목 | 값 |
|---|---|
| **포함** | E-1.2, E-1.2b, E-1.1, INT-4(onError 토스트), F2(E-1.13b), D-29b |
| **파일** | FE 6개(`refreshLock.js` 신규, `axios.js`, `UseChatStream.js`, `UseStoryV2Stream.js`, `useTheaterStream.js`, `TheaterPlayPage.jsx`, `LobbyShell.jsx`, `TheaterPortalPage.jsx`, `ChatPageV2.jsx`) |
| **MIG** | 없음 |
| **커밋** | ① refreshLock 단일 뮤텍스(E-1.2+E-1.2b) ② 극장 잠금 해제+토스트(E-1.1+INT-4) ③ 성인인증 배선(F2) ④ 死코드(D-29b) |
| **규모** | 중간 |

**리뷰 관점**
- `axios.js:51` `const { accessToken } = res.data` → boolean 계약으로 반드시 함께 수정. **이 한 줄이 유일한 회귀면** — 안 고치면 모든 401 재시도가 죽는다. `grep -n "res.data" src/api/axios.js` = 0 확인
- 완료 조건: `grep -rn "refreshToken" src/` = **0건** (조사가 적은 "axios.js:16만 남는다"는 틀림 — 그 문자열은 `/auth/refresh`다)
- `_ssePost` 재귀에 `_retried` 1회 제한을 **양쪽 파일에** 추가(E-1.2b가 이 경로를 처음 활성화한다)
- E-1.1은 **finally 한 줄로는 부족하다** — `onBatchConsumed`가 `advanceBatch()`를 이미 커밋해, 재시도하면 `STALE_CLIENT_STATE` 400이다. `pendingFinalizeRef`로 finalize만 재시도하는 5줄 형태 필수
- D-29b 삭제 범위는 **:338-353**(:339-354로 자르면 다음 주석 첫 줄이 잘린다)

**회귀 위험 / 완화**
| 위험 | 완화 |
|---|---|
| E-1.2 인터셉터 계약 변경으로 401 재시도 전멸 | 위 grep + `npm run dev` 실제 로드 확인 |
| named export 삭제를 rollup이 경고만 내고 exit 0 (§3 함정) | `npm run build 2>&1 \| grep -i "not exported"` 필수 |
| **다중 탭 401은 여전히 뚫린다** (모듈 스코프 뮤텍스 한계) | "닫았다"고 보고하지 말 것 — 잔여로 남기고 서버측 RT 유예창은 §4 안건으로 |

---

### 배치 2 — BE 저비용 독립 (마이그레이션 없음, 파일 전부 상이)

| 항목 | 값 |
|---|---|
| **포함** | E-4.3, E-4.16(+오버로드 제거), D-18(2단계만), E-5.2.a, INT-3, INT-1, INT-5, E-6.4, 안건 16 BE분 |
| **파일** | BE 10개 내외 + Admin FE 2개 |
| **MIG** | 없음 |
| **커밋** | ① 분류기 판정값 반영(E-4.3) ② 배경 해시 정합 + 오버로드 제거(E-4.16) ③ 웹훅 fail-closed(D-18) ④ UGC 텍스트 게이트(E-5.2.a) ⑤ 극장 캐시 정합·재과금(INT-1+INT-3) ⑥ 씬 일러 게이트 좌표계 비의존(안건 16 BE) ⑦ 어드민 공개 철회 BE DTO / ⑧ Admin FE(별도 리포) |
| **규모** | 중간 |

**리뷰 관점**
- **E-4.3**: `REJECTED_UNCLEAR` 다운그레이드를 llmClassify 안이 아니라 **classify() 레벨 한 곳**으로 모아라 — 지금 제안대로면 `:145`(LLM 호출 실패 시 거부)와 비대칭이 남는다. 검증 명령은 `grep -c "ALLOWED_OTHER"`가 아니라 `:325`가 verdict 변수를 넘기는지 직접 확인
- ★ **E-5.2.a는 5필드 통째 join 금지** — `StudioPage.jsx:586-603`이 editForm을 기존 값으로 **프리필해 통째 PATCH**한다. personality·firstGreeting은 LLM 산출물이라 생성 게이트를 통과한 적이 없다. `"중학생 때부터 알던 소꿉친구"` 같은 문장이 이미 저장돼 있으면 **그 캐릭터는 영원히 편집 불가**가 되고, 400 문구는 어느 필드가 문제인지도 안 알려준다. → **변경된 값만 검사**(`changedOnly` 헬퍼, `Character.updateUgcTexts:870-882`와 같은 규칙)
- **D-18은 2단계(fail-closed)만.** ①(URL에 `?secret=` 부착)은 **한 번도 실행된 적 없는 경로를 켜는 변경**이고, ModelsLab이 쿼리스트링을 보존하는지 미실측이다(전역 규약 위반). 소비처 0건이므로 fail-closed만으로 표면이 완전히 닫힌다. `verifySecret`를 `PaymentController.java:200-217` 형식(prod fail-closed + `MessageDigest.isEqual`)으로. **`:70-74` 비-success 분기는 절대 건드리지 마라**
- **D-18 대안(더 나음)**: 컨트롤러 진입부를 `legacy-cg-enabled` 노브로 404 처리 — SSRF 표면 전체가 한 줄로 사라진다(§2-4 "코드 보존, 진입만 차단")
- ★ **안건 16 BE 게이트에 "로그 0건" 구멍**: 제안된 `.orElse(false)`는 방 로그가 0이면 `exists=false → !false = true → 차단`이다. **리셋 직후가 정확히 그 상태** — 종류만 바뀐 같은 버그가 된다. `countByRoomId == 0`이면 무조건 개방하는 가드 필수
- 안건 16 사정권은 V2만이 아니다 — `ChatService.deleteChatRoom:330-336`(V1 '모든 기억 지우기')도 동형
- **E-4.16**: 3인자 교체 시 `ChatRoom.updateDynamicBackground`도 3인자로 바꾸되 `room.getCurrentDynamicCanonicalKey()`를 되넘겨야 값이 null로 덮이지 않는다
- **E-6.4**: 버튼 조건은 `ugc && visibility !== 'PRIVATE'`(열거 대신 부정형). `Character.isAccessibleBy`는 **:815**
- **INT-1**: `if (state.getLastPaidBatchId() == null || batchId > state.getLastPaidBatchId())` — 워터마크가 이미 정보를 들고 있다. 1차 B-5.1이 착취를 막느라 정상 유저에게 과금을 얹은 회귀다

**회귀 위험 / 완화**
| 위험 | 완화 |
|---|---|
| E-5.2.a가 레거시 텍스트를 가진 캐릭터를 영구 편집 불가로 | 델타 검사 방식(위) — 회귀면 0 |
| 안건 16 게이트가 리셋 직후 FAB를 잠금 | 로그 0건 개방 가드 + 리셋 직후 수동 재현 |
| 파생 쿼리(`existsByRoomIdAndCreatedAtGreaterThan` 등)는 컴파일 통과 후 **부팅 시** 죽는다 | §3 기동 검증 필수 — `Started AichatApplication in` |
| D-18 fail-closed로 웹훅 401 고정 | 소비처 0건이라 무해. 로그만 시끄러움 |

---

### 배치 3 — 스트림 보상·로그·나레이션 (`ChatStreamService` 집중, **순서 엄수**)

| 항목 | 값 |
|---|---|
| **포함** | D-2.g → D-6.5+D-6.4 → D-2.a(+V2 오프닝) → E-5.1.b/E-5.1.a → D-23(축소판) → F1 |
| **파일** | `ChatStreamService.java`(1680줄), `ChatStreamServiceV2.java`, `DirectorService.java`, `StoryController.java`, `RedisCacheService.java`, FE 2개 |
| **MIG** | 없음 |
| **규모** | 큼 — **이 배치는 단독 세션으로 잡아라** |

**커밋 순서 (역전 금지)**

| # | 내용 | 왜 이 순서인가 |
|---|---|---|
| ① | D-2.g + D-2.a + V2 오프닝(:376-378) — 최외곽 보상 | `committed = true;` 삽입이 이후 라인을 밀어낸다 |
| ② | D-6.5 + D-6.4 — ASSISTANT 로그 일원화 | ①이 밀어낸 `:1548`을 **여기서 재측정** |
| ③ | E-5.1.b + E-5.1.a — 서버 확정 나레이션 + hiddenSystem | F1과 같은 진입부 |
| ④ | F1 — directive 발급 검증 + chosenIndex 폴백 | ③의 옵션 스냅샷 캐시를 재사용 |
| ⑤ | D-23 축소판 — `RedisCacheService.evict` 삼킴 + `:249` 보상 + `:369~:450` 랩핑 | 독립 |

**리뷰 관점 — ★ 조사안의 컴파일 에러 2건**

> **`jpa`를 밖으로 호이스팅하면 컴파일되지 않는다.** TX-2 람다가 `jpa`를 캡처한다 — `ChatStreamService.java:1463`(`jpa.room().isStoryMode()`), `:330`(`processEasterEgg(..., jpa.userId())`). effectively final 상실.
> **대안: `rollbackCtx`만 호이스팅**(TX-1 직후 savedLogId=null로 생성 → 로그 저장 후 재대입). 공통 헬퍼도 `finalizeOnUnexpected(RollbackContext ctx, boolean committed)`로 — jpa를 인자로 받으면 4곳 중 2곳이 컴파일 에러.

- `committed = true;` 위치: V1은 **:1483 다음 줄**(`consumeBranchPricing` 앞), V2는 **:258 다음 줄**(조사가 적은 :257이 아니다 — :257은 `return;`)
- ★ **캐시 역직렬화 함정**: `RedisCacheService.get(key, List.class)`는 원소를 `LinkedHashMap`으로 돌려준다. `List<BranchOption>`을 캐싱하면 `ClassCastException` → 방어 catch에 걸려 **항상 `Optional.empty()`** → 나레이션이 100% 클라 폴백. **`List<String>` details로 캐싱**하고 `String.valueOf(...)`로 읽어라(`resolveBranchCost`가 `((Number)...).intValue()`로 방어한 이유가 이것)
- `hiddenSystem(roomId, narration)` — `"[NARRATION] "` 접두어를 붙이지 마라(`:1277`이 주입 시점에 붙여 이중이 된다)
- `:1420` 조건에 `isBlank()` 누락 — 빈 문자열이 visible SYSTEM 로그로 영구 삽입된다
- D-2.b의 **`:487-494` 계약 주석은 사실과 다르다** — 그 catch가 감싸는 try는 `:176`에서 시작해 TX-1·evict·injection·`resolveSecretMode`를 전부 포함한다. `committed=true 이후만 면제`로 정정하지 않으면 다음 세션이 "결정으로 종결됨"으로 스킵한다
- **D-23 ①(지연 역참조 정리)은 하지 마라 — 전제가 반증됐다.** `ChatRoomRepository:51-52`가 `@EntityGraph({"user","character"})`라 detached여도 LazyInitializationException이 불가능하다. 11곳 기계 치환은 순수 churn이고 §1-1 과절단 유형
- **D-23 ③의 더 싼 대안**: 3상태 catch 대신 `RedisCacheService.evict()`(:63-65)를 `setBackgroundCache`처럼 예외 삼킴으로 한 줄 고쳐라 — `:218/:369/:450/:526/:593/:654/:718/:772/:1403/:1549` + V2 `:203`의 동일 노출이 전부 닫힌다. 남는 건 `:249` 하나
- **`recordInjection`은 이미 예외를 삼킨다**(`ModerationEventService:52-60`) — 무보상 창 3축 중 하나는 성립하지 않는다
- FE 동반: `ChatPageV2.jsx:1770-1787` `onError`에 `+2` 하드코딩은 **부스트 유저에게 새 거짓말**을 만든다(서버는 최대 10E). `refreshUser()` 재동기화 또는 `:2461-2464`의 `baseCost*5` 보정 재사용

**회귀 위험 / 완화**
| 위험 | 완화 |
|---|---|
| 4곳 동형 가정 → 2곳 컴파일 에러 | rollbackCtx만 호이스팅 (다행히 조용한 실패는 아니다) |
| `committed`를 catch 블록 **안**에 삽입 | 커밋 후 `grep -n "committed = true"` 위치 눈으로 확인(§1-2) |
| 옵션 스냅샷이 조용히 무효화 | `List<String>` + 배포 후 "폴백 사용" 로그 카운트 |
| **(나) hiddenSystem은 '[Bug Fix A] 새로고침 시 히스토리 표시'를 되돌린다** | 종원 확정 사항이나 **커밋 메시지에 "되돌린 것"임을 명시** — 안 하면 다음 감사가 회귀로 재보고 |
| 회귀 테스트 확장이 세션을 잡아먹음 | `ChatStreamServiceV2CompensationTest`는 `sendMessageStream`을 태울 수 없는 구조(@Mock 5개, 6케이스 전부 헬퍼 직접 호출). **헬퍼 단위 + 수동 시나리오**로 범위 확정 |

---

### 배치 4 — 극장 착취 차단 + prefetch 처분 (마이그레이션 없음)

| 항목 | 값 |
|---|---|
| **포함** | D-5.6(②안: 노브 off), E-4.4(무마이그레이션 대안), INT-2, D-29c |
| **파일** | `TheaterService.java`, `application.yml`, `useTheaterStream.js`, `TheaterInterventionController.java`(또는 `LegacyFeatureProperties`) |
| **MIG** | **없음** (아래 대안 채택 시) |
| **선행** | ★ 프로드 로그 grep으로 D-5.6 확정 (30초) |
| **규모** | 작음 |

**리뷰 관점**

- ★ **D-5.6은 ②안(비활성)이 압도적으로 옳다.** ①안(`@Transactional` 부여)은 **정상 유저를 막는다**:
  - HikariCP 기본 max-pool 10 vs `theaterPrefetchExecutor` maxPoolSize 8 → 극장 동시 8세션이면 커넥션 8개가 LLM 호출 60초간 묶여 **채팅·결제가 풀 대기 타임아웃**. RejectedExecutionHandler 미지정(AbortPolicy)이라 백프레셔도 없음
  - `TheaterState`에 `@Version` 존재 → prefetch tx가 `markMajorBranchDoneInChapter()`로 더티가 되어 먼저 커밋하면 유저의 `/batch-consumed`가 **OptimisticLockingFailure 500**
  - ②안은 유저 체감 변화 0(이미 안 되고 있다) + **D-5.1/5.2/5.3/5.4/5.5/5.7을 전부 도달 불가로** 만든다 → 커밋 1(생성기 대수술) 통째 이월
  - 필요한 것: `theater.prefetch-enabled:${THEATER_PREFETCH_ENABLED:false}` 1줄 + `TheaterService:181` 조기 return + `useTheaterStream.js:136-138` 트리거 제거
  - **`catch(:218-220)`를 `log.warn(..., e)`로** — 스택트레이스 부재가 이 결함이 두 세션을 살아남은 이유
- ★ **E-4.4는 V36 없이 닫힌다.** `completeChapter():353`이 `scenesInCurrentChapter = 0`으로 리셋하므로:
  ```java
  if (state.isInIntermission()) throw ...;
  if (state.getScenesInCurrentChapter() <= 0) throw ...;   // 이미 마감했다
  ```
  목표치를 보지 않으므로 조사가 배제한 '목표 씬 미달 거부'와 다르다. 인터미션 루프(perform→finish→chapter-end)도 함께 막힌다. **마이그레이션 0 · 엔티티 0 · NULL grandfather 0 · 배포 창 리스크 0**
- ★ **E-4.4에 FE 복구 경로 필수.** `BadRequestException`은 `useTheaterStream.js:170-191` catch에서 UNPAID_BATCH가 아니므로 `onError` → `TheaterPlayPage.jsx:273-283` **`console.error`만**. B-5.2를 관측 모드로 내보낸 바로 그 이유다. → 전용 `ErrorCode(CHAPTER_ALREADY_FINALIZED)` + FE catch에 `refetch → inIntermission이면 인터미션 화면` 분기. (INT-4 토스트는 배치 1에서 선행)
- **D-29c는 컨트롤러 삭제가 아니라 노브**로 — 삭제는 공개 API 표면 제거라 안건 17-② 왕복을 만든다. 노브는 §2-4 관례 그대로이고 되살릴 때 비용 0. `TheaterState`의 intervention 컬럼은 **절대 건드리지 마라**(§2-1)
- D-29c 실제 위험은 '영구 잠금'이 아니다 — `startIntervention`이 `checkpointToken`을 반환하므로(`:113-117`) 호출자는 자력 해제 가능. `@PreAuthorize` 소유자 한정이라 자해 경로. **Redis TTL 1h 경과 후**에만 복구 불능

**회귀 위험 / 완화**
| 위험 | 완화 |
|---|---|
| D-5.6 ①안을 먼저 배포하면 잠복하던 D-5.1 덮어쓰기가 실제로 발생 | ②안 채택 시 순서 문제 자체가 소멸 |
| E-4.4 가드 오탐 시 유저가 마지막 씬에 고정 | 전용 ErrorCode + FE 자가치유 + INT-4 토스트 |
| §2-5 극장 무변경 | 유저 체감 동작 0 변화(착취·잠복만 차단). **안건 17-② 1줄 확인 권장** |

---

### 배치 5 — V36 + 승급 세리머니 + OAuth + 9-A′ 구조 봉쇄

| 항목 | 값 |
|---|---|
| **포함** | 안건 18(**V36**), E-7.1.a/b + FE LoginPage, 9-A′【C】【D】 |
| **MIG** | **V36** = `V36__chat_rooms_peak_relation_status.sql` (단독 커밋) |
| **커밋** | ① V36 마이그레이션 단독 ② 승급 세리머니 코드 ③ OAuth BE(`SocialUserUpsertService` 신설) ④ OAuth FE ⑤ 9-A′ 구조 봉쇄 |
| **규모** | 중간 |

**리뷰 관점**
- **V36 CHECK를 붙이지 마라** — Flyway가 컬럼을 먼저 만들면 Hibernate update는 CHECK를 덧붙이지 않고 prod validate는 CHECK를 안 본다. 넣으면 §2-7 드리프트 지점만 는다. `IF to_regclass IS NOT NULL` 가드는 V35 스타일 그대로
- 안건 18 게이트 위치: `room.updateStatusLevel(newStatus)`(`:818`)는 **게이트보다 앞** — 단계는 계속 오르고 연출만 억제
- `markPeakRelationIfFirst`에 `requireSandbox()` 대신 **`if (!isSandboxMode()) return true;`** — `:697`(시간 넘기기)에는 모드 게이트가 코드상 바로 옆에 없어, 나중에 확장되면 즉시 500
- 리셋 3곳(`resetSandboxFields:951` · `resetAffection:714` · `restoreFromSnapshot`)에 초기화. **`restoreFromSnapshot`은 :553-586**
- ★ **E-7.1.a: `catch(DataIntegrityViolationException)` 후 같은 TX에서 `findByEmail` 재조회는 터진다** — 예외 난 TX는 rollback-only로 마킹돼 이후 쿼리가 죽는다. 재조회는 **트랜잭션 밖 또는 REQUIRES_NEW**
- E-7.1.a 인코딩: `:85-89` 패턴은 `.build(true)`(이미 인코딩됨). 헬퍼는 `.queryParam(k,v).build()`로. **이메일을 쿼리스트링에 절대 싣지 마라**
- FE는 `LoginPage.jsx`에 `useSearchParams` **신설**(현재 쿼리 파싱 0건). `:74`의 상대경로 리다이렉트도 함께 정정(SPA와 API 오리진이 다르다)
- **9-A′는 【C】【D】만.** 【B】(캐스트 전수 판정)는 공식 캐릭터가 시드에서 `secretEligible=true`라 **사실상 no-op**이고, 실효 케이스는 【C】【D】가 더 확실히 막는다. 4경로 시그니처 변경은 SSE 핵심 경로 수술이다. 【B】는 §6 실측 후로

**회귀 위험 / 완화**
| 위험 | 완화 |
|---|---|
| V36 백필 누락 → 기존 방이 다음 승급에서 한 번 더 세리머니 | **현행 동작과 동일**이라 회귀 아님 |
| 신규 `@Service`가 컴포넌트 스캔 범위 밖 | §3 기동 검증 필수(MongoConfig 사고 유형) |
| 9-A′【C】가 기존 방을 깨는가 | 생성 경로만 막으므로 신규만. 단 §6 실측 ②가 비어 있지 않으면 데이터 정리 선행 |

---

### 배치 요약

| 배치 | 결함 수 | 파일 | MIG | 규모 | 선행 |
|---|---|---|---|---|---|
| 1 · FE 인증·잠금 | 6 | ~9 | — | 중 | 배포 완료 |
| 2 · BE 저비용 독립 | 9 | ~12 | — | 중 | — |
| 3 · 스트림(단독 세션) | 6 | ~7 | — | **대** | 배치 1(FE 대조) |
| 4 · 극장 착취·prefetch | 4 | ~4 | — | 소 | §6 실측 ① · 17-② |
| 5 · V36·승급·OAuth·9-A′ | 4 | ~8 | **V36** | 중 | §6 실측 ④⑤ |

**이월**: D-5.1~5.4/5.7(prefetch 소생 시) · D-9 · D-24(주석만) · D-2.k(B) · 9-A′【B】 · 안건 16 FE분 · D-3.4③(WONTFIX 권고) · D-29d(E-2.15b 대기) · 안건 11(c)

---

## §4. 종원 결정 대기 목록

### 결정 1 — 안건 17-① : 극장 스탯 리롤 과금 ★전제가 무너졌다

| 항목 | 내용 |
|---|---|
| **질문** | 극장 스탯 리롤에 과금을 붙일 것인가 |
| **★ 전제 정정** | 조사·레지스터가 놓친 `TheaterLobbyService.java:517` `validateInitialStats(user, newDistribution)` — 리롤은 **방 생성과 동일한 티어 캡**을 통과해야 한다. 미드나잇 캡 500/100 = `AvatarStat.clamp[0,100]` × 5축 만렙이고 FE 생성 화면이 이미 그 캡을 연다(`TheaterCreateFlow.jsx:137`). **즉 리롤로 얻을 수 있는 것이 생성 시점에 이미 전부 가능하다 — 착취 이득 0.** 게다가 FE 호출부가 **0건**이라 라이브 선례도 없다 |
| **선택지** | (a) 3E + 첫 1회 무료 + 세션당 3회 · (b) 리롤권 상품 · (c) 기능 제거 · (c-lite) 노브 차단 · **(0) 주석만 정정** |
| **비용** | (a) V36/V37 + 엔티티 + yml 3노브 + **FE 목업/컨펌/구현** · (b) LARGE(§2-7 ProductType CHECK + 인벤토리 + 환불 회수) · (c) 되돌릴 수 없음 + §C#6 · (c-lite) 노브 부채 1개 · (0) 2줄 |
| **★ 숨은 위험** | (a)로 리롤 UI를 만들면 **FREE(캡 0/0)·LUCID_PASS(20/10) 유저에게 "3E 내고 내 스탯을 0으로 만드는 버튼"**이 된다(`applyStatChange`에 하한 보호 없음). '현재 총합 하회 금지' 술어가 **반드시** 선행 |
| **추천** | **(0)** — 이번 배치엔 javadoc `:70-81`(40/20→500/100)과 컨트롤러 `:166`("유료 아이템 전제"→"무과금·BM 미정") 주석 정정만. 리롤 UI를 런칭 범위에 넣기로 할 때 (a)+하한 가드를 한 세트로. **P1에서 강등하고 런칭 크리티컬 패스에서 빼라** |

### 결정 2 — 안건 17-② : §C#6 "극장 무변경" 경계 명문화

| 항목 | 내용 |
|---|---|
| **질문** | "유저 체감 동작 무변경" 원칙에서 버그픽스·과금·스키마·엔드포인트 제거는 예외인가 |
| **선택지** | (가) 예외 명문화 · (나) 극장 전면 동결 |
| **비용** | (가) 0 (1·2차 선례를 문서화) · (나) 배치 4 전부 중단 → E-4.4·INT-1·INT-2 착취면 존치 |
| **추천** | **(가)** — 1·2차가 이미 V30(극장 스키마)·극장 가드를 버그픽스 명목으로 변경했다. 다만 (가) 안에서도 **엔드포인트 삭제는 금지, 노브 차단만 허용**으로 좁히면 D-29c 논쟁이 사라진다 |

### 결정 3 — 구독 다운그레이드: '거부'(2차 구현) vs '경고 후 허용'

| 항목 | 내용 |
|---|---|
| **질문** | 2차에서 구현한 '거부'를 유지할 것인가 |
| **비용** | 유지 0 / 전환은 잔여 보존·이월 산식 재설계 |
| **추천** | **유지.** 배포 후 CS 문의 빈도를 1~2주 관측한 뒤 재론. 지금 뒤집으면 V33·V35 정합 로직을 다시 판다 |

### 결정 4 (신규) — §G-6 레거시 CG 트랙 영구 동결 여부

| 항목 | 내용 |
|---|---|
| **질문** | ModelsLab 캐릭터 CG 트랙(`legacy-cg-enabled`·`theater-auto-cg-enabled`)을 영구 동결할 것인가 |
| **영향** | **영구 동결이면 D-2.k(B)가 MOOT**로 종결되고(스테이징 재현·D-2.h 환불 설비 V36 불요) 잔존 PENDING 정리 스크립트만 남는다. D-18 2·3단계도 불요 |
| **비용** | 동결 0 / 부활 시 D-2.k(B)+D-2.h+D-18 전체 + **수동 경로 서버측 완료 감시 신설**(`submitGeneration`이 폴링을 안 부르고 `checkStatus` 라이브 폴링은 주석 처리 — 켜는 순간 "웹훅 유실 = 10E 영구 PENDING" 100% 재현) |
| **추천** | **런칭 전까지 동결 유지.** 잔존 PENDING 행 일회성 정리(24h 초과 → FAILED + 환불)만 별도 집행 |

### 결정 5 (신규) — 안건 18 구현 형태: peak 1컬럼 vs 단계별 이력

| 항목 | 내용 |
|---|---|
| **질문** | 확정 문구는 "단계별 세리머니 1회 기록"인데 제안은 **최고 도달 1컬럼**이다. 등가가 아니다 |
| **차이** | peak 방식은 '건너뛴 중간 단계'(STRANGER→FRIEND 직행 후 하락→재상승 시 ACQUAINTANCE)에 영구히 연출을 주지 않는다 |
| **추천** | **peak 1컬럼** — 더 강하고 단순하다. 다만 확정 문구와 다르므로 1줄 확인 필요 |

### 결정 6 (신규) — 로비·극장 시크릿 구매 동선 소멸 (감사 F3)

| 항목 | 내용 |
|---|---|
| **질문** | 안건 8('시크릿 탭 숨김')의 부작용으로 `currentCharacterId` 없는 진입점(로비·극장)에서 **시크릿 상품 구매 동선이 통째로 사라졌다**. 시크릿 접근 판정은 서버에서 user-global인데 구매는 채팅방 안에서만 가능한 비대칭 |
| **선택지** | (a) 로비/극장에서 targetCharacterId를 서버가 임의/null 허용으로 채워 탭 개방 · (b) 채팅방 업셀(D-26)로만 유도 |
| **비용** | (a) 서버 지급 트래킹 의미 약화 · (b) 0 |
| **추천** | 어느 쪽이든 **`LucidStore.jsx:214-220` 폴백을 `(!on \|\| !currentCharacterId)`로 넓혀** '탭 버튼 없이 본문만 뜨는' 창을 먼저 막을 것. docs/16이 시크릿을 핵심 BM으로 올린 것과 어긋나므로 (a) 쪽 검토 권장 |

### 결정 7 (신규) — RT 재사용 감지 유예창 (E-1.2 잔여)

| 항목 | 내용 |
|---|---|
| **질문** | 모듈 스코프 뮤텍스로는 **다중 탭 동시 401**을 못 막는다. 서버측에서 '직전 회전 RT를 30초 유예창 안에 재제시한 경우'를 탈취가 아닌 경합으로 볼 것인가 |
| **비용** | 보안 정책 변경. B-10.2로 구 RT jti 블랙리스트가 이미 있어 판별 재료는 갖춰져 있음 |
| **추천** | 배치 1에 끼워 넣지 말고 **별도 안건**으로. 배포 후 'All sessions revoked' 로그 빈도를 먼저 관측 |

### 기타 확인 1줄 항목

| # | 확인 |
|---|---|
| 8 | 안건 13 **(나) hiddenSystem 복귀는 '[Bug Fix A] 새로고침 시 히스토리 표시'를 되돌린다**(새로고침 후 과거 BRANCH 나레이션 소실). 확정 사항이나 UX 회귀임을 재확인 |
| 9 | 안건 13 (a) 옵션 스냅샷 캐시 키 설계를 **안건 14 (c)**(가격표를 TTL 아닌 directiveId 멱등 키로)와 **한 번에** 정할 것 — 따로 하면 두 키를 다시 옮긴다 |
| 10 | F1의 `chosenIndex` 생략 시 폴백: `orElse(1)` 유지 vs '캐시된 최대가' vs 400. **400은 구 FE 세션을 잠글 수 있다** — FE 복구 경로 동반 검토 |
| 11 | 안건 20 재질의(PENDING_PUBLIC 편집 잠금) — **권고: 하지 마라**. 회귀가 자동으로 PENDING_PUBLIC을 만들므로 '첫 저장 성공, 두 번째부터 무조건 400'이 된다 |
| 12 | E-2.15b: 인밴드 자동 씬 일러(trigger=auto)를 되살릴 계획이 있는가 → D-29d 착수 가부 |
| 13 | 안건 11 (c) 재론 여부 → **런칭 후 권장**(현재 유저 이득 0, 착수는 LARGE) |
| 14 | D-5.4 Mongo 중복 씬로그 정리 → **D-5.6이 참이면 정리할 중복이 애초에 없다**. 종원 안건 하나가 통째로 사라진다 |

---

## §5. 하지 말아야 할 것

### 5-1. 검증이 "정상 유저를 막는다"고 지적한 수정안

| 대상 | 왜 안 되는가 | 대신 |
|---|---|---|
| **E-5.2.a 5필드 통째 join 검사** | `StudioPage.jsx:586-603`이 기존 값을 프리필해 통째 PATCH. LLM 생성 텍스트에 `"중학생"` 등이 있으면 **그 캐릭터는 영원히 편집 불가** | 변경된 값만 검사 |
| **안건 16 게이트 `.orElse(false)`** | 로그 0건(리셋 직후) 방을 차단 — 종류만 바뀐 같은 버그 | `countByRoomId == 0` 개방 가드 |
| **E-4.4 씬 수 미달 거부** | `chapterEndAfter`는 LLM 산출값이라 목표 미달 정상 종료가 실재 | `scenesInCurrentChapter <= 0` |
| **E-4.4 `BadRequestException` 단독** | FE catch가 `console.error`만 → 무증상 정지 | 전용 ErrorCode + FE 자가치유 + 토스트 |
| **D-5.6 `@Transactional` 부여** | 커넥션 풀 고갈(8세션×60초) + `@Version` 낙관락 역류로 유저 `/batch-consumed` 500 | 노브로 끈다(②안) |
| **D-5.4 `existsBy...` 스킵 가드** | 세이브 로드 후 재진행 시 **정상 신규 로그를 억제** | delete-then-insert (또는 애초에 이월) |
| **D-18 `!"PENDING".equals(...)`** | `GENERATING` 상태 행의 정상 완료 콜백을 버린다 | `illust.isPending()` 또는 배제형 |
| **B-6.1 (a)를 하한 가드 없이** | FREE/LUCID_PASS 유저에게 자해 버튼 | '현재 총합 하회 금지' 선행 |
| **D-3.4③ 낙관 잠금** | 해제 경로 2개 중 하나만 새도 버튼 영구 잠김. 2.5초 폴링이 이미 자가치유 | WONTFIX 또는 `refresh` catch 즉시 재시도 3줄 |
| **안건 20 PENDING_PUBLIC 편집 잠금** | 회귀가 자동으로 PENDING_PUBLIC을 만들어 두 번째 저장부터 무조건 400 | 넣지 마라 |
| **E-4.3 원안(모든 verdict 강제)** | LLM `REJECTED_UNCLEAR`가 정상 명령어를 차단 | UNCLEAR 통과, 단 classify() 한 곳으로 통일 |

### 5-2. 실측 없이 손대면 안 되는 것

| 대상 | 왜 |
|---|---|
| **D-2.k (B) 비-success → FAILED 전이** | `processing`/`queued` 중간 상태가 정상 생성을 죽이고 환불까지 나간다. status 집합 미실측 |
| **D-18 ①(URL에 `?secret=`)** | ModelsLab이 쿼리스트링을 보존하는지 미실측. 한 번도 실행된 적 없는 경로를 켜는 변경 |
| **9-A′【B】(캐스트 전수 판정)** | 공식 캐릭터에 `secret_eligible=false`가 하나라도 있으면 **그 월드 시크릿이 통째로 죽는다**(유료 해금 유저가 산 것을 잃음) |
| **9-A′ age null 비유예** | 기존 승인 UGC 전원 age=null → 시크릿 전면 차단. 안건 9-E는 'null 유예 유지' 확정 |
| **E-5.1.b 길이 상한 300** | 정상 카드를 자른다. 프로드 `chat_logs` `[NARRATION] ` 길이 분포 실측 전엔 500 + 로그 관측 |
| **V33·V35 데이터 정리** | 비가역. 중복 활성 구독 실측 전 배포 금지(§0-2) |

### 5-3. 극장 무변경(§2-5) 경계

- ✅ 배치 4는 **동기 경로 무변경 + 착취/잠복만 차단** 형태로 좁혀 놓았다 — 17-②가 어느 쪽으로 확정되든 통과
- ❌ **엔드포인트 삭제 금지** — D-29c는 컨트롤러 제거가 아니라 노브
- ❌ **`TheaterState`의 intervention 컬럼 제거 금지**(§2-1)
- ❌ D-5.4의 '씬로그를 소비 시점으로 통째 이동' / D-5.5의 '자동 노트 소비 시점 이동' — 유저 체감이 바뀐다
- ⚠ D-5.6 ①안(소생)만이 유일하게 체감을 바꾼다(배치 전환이 빨라짐 — 개선 방향이지만 보고 필요)

### 5-4. 기타 금지

- `ChatStreamService.java`를 배치 3과 배치 5(안건 18)에서 **같은 세션에 만지지 마라** — 라인이 상호 밀린다
- `ChatPageV2.jsx`(4500줄+)는 배치 1에서 `v2DerivedRoomInfo` 삭제 외에 손대지 말 것
- **D-23 ① 지연 역참조 정리** — 전제(`@EntityGraph`)가 반증됐다. 순수 churn + §1-1 과절단 유형
- **D-29d 자동 씬 일러 제거** — 표면 7곳, E-2.15b 답 없이 착수 금지
- 마이그레이션 착수 직전 `git ls-files src/main/resources/db/migration | sort -V | tail -3` **+ `flyway_schema_history` 둘 다** 확인(V31 중복 사고)
- `sendFinalResult` 오버로드 삭제 등 §2-6 작업 시 **하위호환 오버로드를 남기지 마라** — 컴파일러가 유일한 검증 수단

---

## §6. 이번 계획의 불확실성 — 실측해야 할 것

### 6-1. 프로드 실측 (배포 후 즉시)

| # | 항목 | 명령 | 무엇이 갈리는가 |
|---|---|---|---|
| ① | **D-5.6 확정** ★30초 | `docker compose logs app \| grep -c '🎭 \[PREFETCH\] Done'` vs `grep -c 'Failed'`, 후자에 `could not initialize proxy`/`no Session` | **묶음 B 전체(7건)의 심각도·우선순위.** 참이면 D-5.1~5.5/5.7이 전부 '잠복'으로 재분류되고 배치 4가 노브 1줄로 끝난다 |
| ② | 잔존 PENDING 일러 | `SELECT count(*), min(created_at) FROM user_illustrations WHERE status='PENDING';` | D-2.k 정리 스크립트 필요 여부. 10E 소각 규모 |
| ③ | ModelsLab status 집합 | `[ILLUST] Status changed: {} → {}` (배경 시크릿 트랙이 살아 있으므로 **트랙을 켜지 않고** 수집 가능) | D-2.k(B) 착수 가부. 되면 스테이징 재현 불요 |
| ④ | 공식 캐릭터 secretEligible | `SELECT id,slug,secret_eligible FROM characters WHERE source='OFFICIAL' AND secret_eligible=false;` | 9-A′【B】 착수 가부(0건이어야 안전) |
| ⑤ | 공식 월드 UGC 캐스트 오염 | `SELECT id,slug,world_id FROM characters WHERE source='UGC' AND world_id IS NOT NULL AND story_available=true;` | 9-A′【C】 무회귀 여부. 비어 있지 않으면 데이터 정리 선행 |
| ⑥ | 웹훅 시크릿 실제 값 | Vultr compose env `MODELSLAB_WEBHOOK_SECRET` · `LUCID_WEBHOOK_BASE`가 **빈 문자열인가** | D-18이 '현재 인증 없음'인지 '현재 웹훅 전량 401'인지. base가 빈 값이면 ModelsLab이 애초에 콜백하지 않아 후자 시나리오가 공허해진다 |
| ⑦ | 나레이션 길이 분포 | `chat_logs`의 `[NARRATION] ` 접두 SYSTEM 문서 길이 p99 | E-5.1.b `BRANCH_DETAIL_MAX` 확정(실측 전엔 500 + 로그) |
| ⑧ | Mongo 중복 씬로그 | `db.theater_scene_logs` roomId+globalSceneSeq 중복 그룹 | D-5.4 정리 스크립트 필요 여부. ①이 참이면 **불요** |

### 6-2. 코드로 확정 못 한 것

| # | 항목 | 어떻게 확인 |
|---|---|---|
| ⑨ | **THEATER 방이 `/director/auto-respond`에 닿을 수 있는가** | 컨트롤러에 모드 가드가 없다(소유권 검사뿐). 닿는다면 D-6.5의 속마음 게이트 적용이 §2-5 저촉 |
| ⑩ | **`SendChatResponse` 하위호환 생성자 개수** | 조사는 7개/18-17-15 arity라 했으나 실측 **8개 / 17·16·14·4·5·7·10·12**(카노니컬 18). D-9 착수 시 그대로 따르면 컴파일 에러 |
| ⑪ | **`GenerateParams` 생성자 사용처** | cluster_notes는 "6-인자 사용처 0"이라 했으나 실측 **2곳 모두 6-인자**. 0인 것은 5-인자 |
| ⑫ | **E-7.2 FE 잔여** | `StudioCreateFlow`/`StudioPage`의 tone 입력에 `maxLength`가 있는가. 없으면 서버 400은 생겼어도 '다 쓰고 나서 거부'는 남는다 |
| ⑬ | **D-9 `/users/me` 재조회 제거 시 free/paid 표시** | 삭제 대상 블록이 `setFreeEnergy`·`setPaidEnergy`도 갱신한다. `energyRemaining` 1필드로는 대체 불가 |
| ⑭ | **파생 쿼리 부팅 검증** | 배치 2의 `existsByRoomIdAndCreatedAtGreaterThan`(Mongo)·`findTopBy...OrderByIdDesc`(JPA)는 이름이 틀리면 **컴파일 통과 후 부팅 시** 죽는다. `Started AichatApplication in` 필수 |

### 6-3. 계획 자체의 취약점

1. **묶음 B는 실측 ① 하나에 전부 걸려 있다.** ①을 안 하고 배치 4에 들어가면 노브를 끌지 살릴지 결정할 수 없고, ①안(소생)을 잘못 고르면 D-5.1의 덮어쓰기·D-5.7의 1E 명령어 소각이 **실제로 발생한다**.
2. **자동 테스트가 잡아주는 범위가 좁다** — 21클래스 순수 유닛, 통합·리포지토리·컨트롤러 테스트 0건. 배치 3·4의 의미적 검증은 **수동 재현 시나리오**에 의존한다. 각 배치마다 재현 절차를 커밋 본문에 남겨라.
3. **`ChatStreamService.java`가 5개 결함군의 교차점**이다. 배치 3을 단독 세션으로 잡지 않으면 좌표 드리프트로 Edit 매칭이 어긋난다(CRLF §1-1).
4. **델타 정정(§2-2)을 먼저 하지 않으면** 이 계획 자체가 다음 세션에서 다시 오독된다 — 문서 커밋 1건을 배치 1과 함께 넣을 것.