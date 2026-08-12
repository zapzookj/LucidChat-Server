# 13. 전수 버그 스캔 — 확정 결함 리포트 (2026-08-07~09)

> 우연 발견에 의존해온 잠복 버그를 체계적으로 전수 탐지한 결과. 대상 3개 코드베이스 —
> 백엔드 `aichat`(Java 414파일) · 프론트 `LucidChat-Front`(118파일) · 어드민 `LucidChat-Admin`(24파일).
> 선행 문서: [`12_MaleBuilder_Polish_Deploy_Handoff.md`](12_MaleBuilder_Polish_Deploy_Handoff.md)
> **확정 104건 / 반박 10건 / 미확정 0건.** 수정 착수 전 상태.

## A. 방법론과 커버리지

멀티에이전트 스윕 — 차원별 파인더 병렬 탐지 → 기계적 dedup + 시드 대조 → **적대적 검증**(반박 우선, 재현 시나리오 필수) → 과금·크래시급은 **독립 2차 검증자가 재반박** → 완전성 감사로 차기 차원 설계.

**확정 기준**: 실코드 발췌 + 도달 가능한 실행 경로 + `전제 상태 → 행동 → 잘못된 결과` 재현 시나리오. 2차 검증에서 뒤집히면 미확정으로 강등.

| 라운드 | 범위 | 확정 |
|---|---|---|
| R1 | 파인더 13종(프론트 4·백엔드 7·계약 1·어드민 1) | 28 |
| 자체 | 설정·enum·카피 결정론 대조(에이전트 미투입) | 13 + 신규 1 |
| R2 | 미검증 리드 32건 재검증 + 파인더 6종(`be-prompt-scene` `be-v1-chat-core` `be-ugc-internals` `be-progression-notify` `fe-components-uncovered` `fe-secondary-pages-contract`) | 62 (중복 1 제외 시 61) |

커버 차원 19종. 미커버로 남은 곳: 프론트 `components/mobile`·`studio` 일부·`theater` 보조 모달(표적 grep은 수행, 라인 정독 미실시), 어드민 SPA의 `be-progression-notify` 교차분, `fe-secondary-pages-contract`(중도 실패).

### 기지 항목과의 관계

확정분 중 3건은 **이전 세션에서 이미 지목됐으나 수정되지 않은 채 남아 있던 항목**이다. 이번 스윕의 기여는 발견이 아니라 *익스플로잇 경로 확정·정량화*이므로 구분해 둔다.

| 결함 | 최초 지목 | 이번 스윕의 추가분 |
|---|---|---|
| 결제 웹훅 검증 부재 | 2026-07 "출시 차단급 리스크 ①" | `imp_uid` 재사용으로 1건 결제 → N건 지급, 타인 주문 확정까지 도달 가능함을 경로로 확정 |
| `beta-activate` | 2026-07-30 재분석 ⑤ ("존치 재결정 필수") | 자가 지급 + **구독 만료 후 무한 재지급** + 성인인증 우회 확정 |
| 유령 장소 키 | 2026-07-23 호환성 테스트 ③ | 11행 전수 + 캐릭터·시간대별 발생 확률 정량화 |

시드 14건은 전 파인더에 주입해 재보고를 차단했고, 동종 패턴의 *다른 위치*만 신규로 인정했다.

---

## B. P0-A — 착취 가능 (자금 · 권한)

### B-1. 결제 웹훅 imp_uid 재사용 — 결제 1건으로 주문 N건 지급
`PaymentController.java:98` · `PaymentService.verifyAndDeliver`

`/api/v1/payments/webhook`이 `SecurityConfig.java:61` **permitAll로 인터넷 공개**이고 서명·IP 검증이 없다. 더 근본적으로 PortOne 조회 응답의 `merchant_uid`를 주문의 것과 **대조하지 않으며** `imp_uid` 유니크 제약도 없다.

9,900원(ENERGY_T3) 1회 결제로 얻은 `imp_uid=X`로 같은 상품 주문 B1..BN을 만든 뒤 `{"imp_uid":"X","merchant_uid":"Bk"}`를 무인증 POST하면, PAID/EXPIRED/terminal 가드가 전부 통과되고 금액도 일치해 `markPaid`+`deliverProduct`가 N회 실행된다 → **250×N 에너지**. 동일 가격대 교차도 성립해 14,900원 LUCID_PASS 결제의 `imp_uid`로 SECRET_UNLOCK_PERMANENT 영구해금을 무상 취득할 수 있다. 웹훅이 무인증이라 merchant_uid만 알면 **로그인 없이 타인 주문도 확정** 가능.

→ PortOne 응답 `merchant_uid` ↔ `order.getMerchantUid()` 대조(웹훅·`/confirm` 공통 경로라 1곳). `Order.impUid` unique + 마이그레이션. 웹훅 서명/IP 검증.

### B-2. `/users/beta-activate` — 유료 구독·에너지·성인인증 자가 지급
`UserController.java:181`

신규 가입 계정 토큰만으로 본문 없이 POST하면 `is_adult=true`, `ci_hash="BETA_TESTER_{id}"`, `subscription_tier=LUCID_MIDNIGHT_PASS`, `paid_energy=+300`, 30일 활성 구독 행(대응 결제 없음)이 부여된다. 로비 로고 5회 클릭(`LobbyPage.jsx:834`)으로도 발사된다. `alreadyActivated` 가드가 세 조건 모두 참일 때만 차단하므로 **구독 만료 후 재호출하면 무한 재지급**된다.

→ 엔드포인트·`UserService.activateBetaTester` 삭제. 필요하면 `/admin/**`으로 옮겨 `hasRole("ADMIN")`+prod=false 이중 게이트, 성인인증 부여는 제외.

### B-3. 이벤트 선택 `energyCost`를 클라이언트가 지정 — 음수로 에너지 발행 (V1·V2 양쪽)
`StoryController.java:135` · `ChatStreamService.java:515`

`{"energyCost":-1000}` 전송 시 `consumeEnergy(-1000)`에서 `30 < -1000`이 false라 통과, `freeEnergy = 30-(-1000) = 1030`. dirty checking으로 커밋되고 캐시까지 무효화돼 `/users/me`가 1030을 반환한다. **V2 컨트롤러 경로와 V1 스트림 서비스 경로 양쪽에 동일하게 존재**한다.

→ `@PositiveOrZero`+`@Valid`, 근본 방어로 `User.consumeEnergy` 진입부 `amount < 0` 차단. 비용은 서버가 결정하도록.

### B-4. 극장 분기 확정이 클라이언트 `optionsSnapshot`을 전면 신뢰
`TheaterBranchController.java:94` · `TheaterBranchService:316,320,346,360`

서버 원본과 대조하지 않아 ⓐ `unlocked:false→true`로 **스탯 잠금 우회**, ⓑ `energyCost:0`으로 **MAJOR/CLIMAX 무과금**, ⓒ `heroineId` 변조로 **임의 히로인을 화자로 설정**, ⓓ `label`에 임의 문자열 주입 → `newBranchContext` 경유 **다음 배치 LLM 컨텍스트 프롬프트 주입**이 전부 반영된다.

→ 옵션 생성 시 서버 원본을 `branchToken` 키로 Redis에 영속하고 확정 시 `chosenIndex`로 재판정(스냅샷 무시).

### B-5. 극장 `next-batch`의 `prefetch` 플래그가 클라이언트 제어 — 전 배치 무과금
`TheaterService.java:98`

`{"prefetch":true}`면 과금 두 지점이 스킵되는데 **응답 본문은 유료 호출과 바이트 단위로 동일**하다. `batch-consumed`도 과금 여부를 보지 않아 호감도·스탯·분기·엔딩까지 정상 진행된다. **에너지 0 계정으로 Act 1~4 완주 가능.**

→ 클라이언트 플래그 제거(선행 생성은 전용 `POST /{roomId}/prefetch`로만) 또는 "과금 완료 batchId" 워터마크로 본문 반환·소비 차단.

### B-6. 스탯 리롤이 어떤 재화도 차감하지 않음
`TheaterLobbyService.java:490`

컨트롤러 주석은 '유료 아이템 사용 전제'인데 리롤권 아이템·인벤토리·구매 코드가 **백엔드에 아예 없다**. PREMIUM 유저가 게이트에 맞춰 무제한 재분배하면 세션 초반 50씬 내내 모든 `stat_gate`가 무력화된다.

### B-7. 업적을 코드 문자열만으로 즉시 해금
`AchievementService.java:143`

`unlockClientTriggered`가 업적 **조건을 서버에서 전혀 검증하지 않는다**. 본인 방 ID와 `{"code":"INVISIBLE_MAN"}`만 보내면 10분 대기 없이 해금되고 갤러리 카운트가 오른다.

→ 조건 증거를 서버가 보유(방별 마지막 활동 시각 등), 검증기 없는 CLIENT_TRIGGERED 코드는 fail-closed.

### B-8. 엔딩 종류를 클라이언트가 지정
`EndingController.java:34`

호감도 조건 없이 HAPPY 엔딩 확정·업적 해금이 가능하고, 잘못된 enum 값은 500. B-9와 결합하면 저장된 `ending_type`까지 뒤집힌다.

### B-9. `generateEnding`에 멱등성·과금·가드 전무
`EndingService.java:146`

'엔딩 다시 보기' 클릭마다 **LLM 3콜이 새로 발생**하고 에너지 차감은 0이다. 5회 클릭 = 15콜(간헐 실패 시 프론트 백오프 재시도로 1클릭당 최대 9콜). 게다가 `saveEndingTitle`이 매번 덮어써 **최초 엔딩 제목이 영구 소실**되고, `[ENDING:]` 시스템 로그가 중복 적재돼 이후 컨텍스트를 오염시킨다.

→ 진입부에 `if (room.isEndingReached())` 분기로 저장 결과 반환, `saveEndingTitle`은 최초 1회만, 레이트리밋 + `endingType` 서버 대조.

### B-10. 리프레시 토큰이 Bearer로 통과
`JwtTokenService.java:78`

RT가 AT와 **동일 서명·동일 디코더**로 발급돼 액세스 토큰 자리에 그대로 쓸 수 있고, 로그아웃으로도 무효화되지 않는다.

### B-11. 레이트리밋 키가 XFF 최좌측 값
`AuthController.java:169`

로그인·회원가입 레이트리밋이 클라이언트가 조작 가능한 `X-Forwarded-For` 최좌측 값을 키로 써 **브루트포스 우회**가 가능하다.

### B-12. 미게시 공지 본문이 일반 유저에게 노출
`NoticeService.java:31`

유저 대면 `GET /api/v1/notices/{id}`에 `published` 검사가 없어 작성 중인 초안이 노출된다.

### B-13. 알림 읽음 처리 IDOR
`StoryV2Controller.java:221`

`notificationId`가 `roomId`에 묶여 있지 않아 **타 유저 알림을 읽음 처리**할 수 있다.

---

## C. P0-B — 기능 전면 불능 (수익 · 컴플라이언스 직결)

### C-0. 로비의 '스토리 모드' 버튼이 공식 10종 전부에서 500 — 신규 진입 불가
`LobbyService.java:133` · `ChatRoom.java:354` · `LobbyPage.jsx:181,755`

체인 전체를 실코드로 추적해 확정했다(2차 검증자가 두 번 완주하지 못해 미확정으로 남았던 건).

1. `ChatRoom` 엔티티 기본값이 `storyAvailable = true`이고 공식 10종 시드가 모두 `story-available: true` → 로비 ModeSelectOverlay의 '스토리 모드' 버튼이 **전 캐릭터에서 활성**(`LobbyPage.jsx:183`).
2. 클릭 → `handleModeSelect("STORY")`. **THEATER만 조기 return으로 가로채고 STORY는 그대로 통과**해 `POST /lobby/rooms {chatMode:"STORY"}`를 보낸다(`:755`, 주석도 "STORY / SANDBOX").
3. 백엔드 `createOrGetRoom`: `storyAvailable` 가드 통과 → 접근 가드 통과 → 비UGC 통과 → 기존 방 없음 → `orElseGet`에서 `new ChatRoom(user, character, ChatMode.STORY)`.
4. 그 생성자는 첫 줄에서 **명시적으로 `IllegalArgumentException`을 던진다** — *"V2 STORY 방은 ChatRoom.createStoryV2(user, world, ...)로 생성하세요."*
5. `GlobalExceptionHandler`에 `IllegalArgumentException` 핸들러가 없어 `@ExceptionHandler(Exception.class)`로 떨어져 **500**. 프론트는 `alert("입장에 실패했습니다.")`.

즉 V2 피벗 때 STORY 생성이 `createStoryV2`로 옮겨졌는데 **V1 로비의 진입 배선만 그대로 남았다**. 해당 유저+캐릭터의 레거시 STORY 방이 이미 있으면 그건 정상 반환되므로, 기존 유저에게는 안 보이고 **신규 진입에서만 100% 재현**된다.

→ 최소 수정은 프론트에서 STORY도 THEATER처럼 V2 CreateFlow로 라우팅(또는 `/v2/story` 경로로). 백엔드는 방어적으로 STORY 요청에 `BadRequestException`(400 + 안내)을 반환해 500을 없앨 것.

### C-1~C-3 — axios/직렬화 계약 불일치

세 건 모두 **동일한 원인 클래스**다 — 커스텀 axios 인스턴스(baseURL이 이미 `…/api/v1`) 위에 `/api/v1`을 재부착하거나, 백엔드가 직렬화하지 않는 필드를 읽는다.

### C-1. 성인 인증이 영구 불능
`AdultVerificationModal.jsx:30,74`

`GET {baseURL}/api/v1/verify/token` → 실제 `…/api/v1/api/v1/verify/token`으로 **항상 404**. NICE 팝업이 열리지도 않고 즉시 'Verification Failed'. Retry해도 동일하다. `isAdultVerified`가 영구히 false로 남아 시크릿 모드와 성인 전용 상품 구매가 잠긴다.

**B-2와 합치면 성인 게이트가 양쪽으로 깨져 있다** — 정상 인증은 불가능하고, 우회(`beta-activate`)는 누구나 가능하다. 컴플라이언스 관점에서 가장 먼저 볼 조합이다.

### C-2. V2 인챗 결제 전면 불능
`PaymentModal.jsx:31,60,72`

ⓐ 경로 이중 접두사로 `ready`·`confirm` 전부 404(V1 `LucidStore.jsx:166`은 정상 — V1↔V2 비대칭). ⓑ 카탈로그가 폐기본: `LUCID_PASS_MONTHLY`는 `ProductType`에 없는 타입(실상품 `LUCID_PASS` 14,900원 — **19,900원으로 5,000원 오표시**), 시크릿 2종은 `targetCharacterId` 미전송으로 항상 400, `ENERGY_T3`의 `+Affection Potion`은 지급되지 않는 허위 표기, `PREMIUM_REQUIRED` 유저가 사야 할 `LUCID_MIDNIGHT_PASS`는 목록에 없음, 가맹점 코드는 `imp_YOUR_CODE` 플레이스홀더.

ChatPageV2는 에너지 부족·프리미엄 요구·상점 버튼·시크릿 진입 **4개 경로 전부**를 이 모달로 보낸다.

→ V2 진입점을 정상 카탈로그와 `targetCharacterId` 선택 UI를 가진 `LucidStore`로 교체하고 `PaymentModal` 제거.

### C-3. 이미 결제한 유저가 시크릿 모드를 켤 수 없음
`SecretModeFlow.jsx:80`

프론트가 `data.canAccess`를 읽는데 백엔드 `SecretModeStatus`는 그 키를 **직렬화하지 않는다**(`accessReason:"GRANTED"`만 내려옴). 영구 해금·미드나잇 패스·24시간 패스 보유자 전원이 `undefined → falsy`로 판정돼 '해금 필요' 업셀 화면을 보고, `onGranted`가 호출되지 않아 토글이 서버에 반영되지 않는다. **결제한 상품을 쓸 수 없고 매번 재결제를 유도받는다** — 환불 클레임 직결.

→ `data.accessReason === "GRANTED"` 비교로 변경(또는 백엔드에 `@JsonProperty("canAccess")`).

---

## D. P1 — 자산 손실 · 데이터 정합

### D-1. 환불이 유료 에너지를 무료 에너지로 변환 — 유료 자산 소각
`User.java:187`

`refundEnergy`가 `consumeEnergy`의 역연산이 아니라 **전액 free로 되돌린다**. paid 50에서 5를 쓰고 환불받으면 `free=5/paid=45`가 되는데, 회복 스케줄러가 free를 30까지만 채우므로 **결제로 산 5E가 순소멸**한다. 씬 일러 경로에서는 1회당 10E 규모다. 호출부 6곳 전부 해당.

→ `consumeEnergy`가 paid 차감분을 반환하고 `refundEnergy`가 paid를 먼저 복원하도록.

### D-2. 실패 경로 환불 부재 (V1·V2 대칭 결함)

| 결함 | 위치 | 소멸 |
|---|---|---|
| V2 스트림 최외곽 catch에 보상 부재 | `ChatStreamServiceV2.java:280` | 턴당 |
| **V1 동형** — `sendMessageStream` 최외곽 catch 보상 부재 + 고아 USER 로그 | `ChatStreamService.java:486` | 턴당 |
| 캐릭터 일러 수동 생성 실패 전 경로 환불 없음 | `IllustrationService.java:119` | 10E |
| 수동 일러 — 폴링 부재 + 실패 웹훅 폐기 → **영구 PENDING**, FAILED 전이 경로 자체가 없음 | `IllustrationService.java:284` | 10E |
| 유료 리롤 소진·복귀 시 리롤 과금 미환불 | `UgcPipelineWorker.java:477` | 2E/건 |

`ChatStreamServiceV2`는 `character`가 null인 `ChatRoomHeroine` 행이 있으면 `WorldRoutingService:90`에서 결정론적 NPE가 나 **재시도할 때마다 매번 차감되는 드레인**이 된다.

### D-3. 비동기 잡 좀비 · 중복 외부 지출

- **BINDING·POSTPROCESSING 스테일 스윕 부재** (`UgcJobScheduler.java:38`) — 20E 전액 낸 잡이 재배포 중 죽으면 BINDING 영구 잔류. 폴링 폴백·스윕·TTL·재호출 경로가 전부 비껴가고, `isActive`라 **신규 생성이 영구 차단**되며 탈출구 `abandon`은 무환불.
- **폴러 ERROR 스킵 ↔ 스윕 hasPendingExternal 스킵의 데드락** (`:93`) — 서버 30분 다운 시 RunPod 결과가 퍼지돼 `/status` 404 → 양쪽이 서로를 기다리며 CONCEPT_PROCESSING 영구 고착, 6E 몰수.
- **씬 렌더 터미널 가드 부재** (`SceneRenderWriteService.java:35`) — 스윕이 환불한 FAILED 행을 큐 대기 태스크가 `GENERATING→COMPLETED`로 부활. 순 5E로 **일러 2장** 수령, GPU 2회 지출.
- **감정 컷 리롤 in-flight 가드 부재** (`CharacterCreationService.java:429`) — **2E 이중 과금** + 파생 체인 중복 제출, `externalJobs` 키가 덮여 선발 체인 유실. 월드 트랙엔 있는 가드가 캐릭터 트랙에만 없다.
- **월드 장소 배경 재시도가 GENERATING에서도 통과** (`UgcWorldService.java:495`) — 무과금 외부 GPU/LLM 호출 무제한 중복.
- **프로필 초안 길이 상한 부재로 완주한 잡이 바인딩에서 FAILED** (`CharacterCreationService.java:303`) — 20E 지불 후 마지막 단계에서 통째로 실패.

### D-4. 구독 정합

- **갱신이 `now+30` 리셋** (`UserSubscription.java:80`) — 잔여 10일이 소멸(로그는 연장인 것처럼 기록). `merchantUid`가 덮여 이전 주문 환불 시 조회 실패.
- **환불 혜택 회수가 조용히 no-op** (`RefundService.java:95`) — 위 원인으로 갱신 후 과거 회차 환불 시 **돈만 나가고 구독은 유지**되며 예외·경고·감사 흔적이 없다.
- **'유저당 활성 1개' unique 부재** (`UserSubscription.java:23`) — 동시 결제 레이스로 활성 2행이 생기면 구독 조회 경로 전부 500이 **최대 30일 영구화**.

→ `renew()` 기준을 `max(now, expiresAt)`로, `merchantUid`는 이력 보존, Flyway V25로 `CREATE UNIQUE INDEX uq_sub_user_active ON user_subscriptions(user_id) WHERE active = true`.

### D-5. 극장 prefetch가 현재 배치를 덮어씀
`TheaterService.java:164`

prefetch가 N+1이 아니라 **현재 배치 ID N으로 생성·저장**한다. 소비 시 유저가 한 씬도 안 본 배치의 호감도·씬 수·화자가 영속되고 실제 감상분은 소실. Mongo에 이중 씬로그가 남고, 다음 요청은 캐시 미스로 **배치당 LLM 비용 2배**.

### D-6. 로그 영속 계층

- **4개 스트림 경로가 `ChatLogPersister`를 우회** (`ChatStreamService.java:1210`) — 이벤트·지켜보기·시간넘기기·자동디렉터의 ASSISTANT 저장이 retry/deadletter를 통째로 건너뛴다.
- **에너지 회복 벌크 업데이트가 프로필 캐시를 무효화하지 않음** (`EnergyRegenScheduler.java:31`) — 최대 30분간 회복분이 반영되지 않아 **0에너지 유저의 전송 차단이 지속**된다.

---

## E. P2 — 로직 · 진행 불능

### E-1. 프론트 상태·스트림

`useTheaterStream.js:162` `finalizeChapter` 실패 시 `chapterEnding=true` 영구 잔류(일시적 5xx 한 번으로 진행 잠김, 재시도 경로 없음 — **try/finally 한 줄**) · `UseChatStream.js:315` fetch/axios 트랙이 **별개 single-flight**라 동시 401 시 RTR 탈취 오탐 → **전 기기 강제 로그아웃** · `UseChatStream.js:130` 디렉터 fetch 3종에 401 갱신 부재 · `ChatPageV2.jsx:2731` 지켜보기·시간넘기기·자동응답 SSE가 중단 불가(abortController 미전달)로 새 전송과 동시 진행하며 상태 역행 · `ChatPageV2.jsx:1744` 멀티씬 턴 `parentLogId` 누락(**한 줄**) · `ChatPageV2.jsx:1896` 액션·오프닝 엔트리에 NPC 분류·emotionTag/outfit 누락 · `ChatPage.jsx:1029` V1 init 복원 스테일 클로저(초기 50개 화자명이 전부 "캐릭터") · `useSceneIllustrations.js:154` 씬 `turnIndex`(hidden 포함) vs `ordinal`(hidden 제외) 좌표계 불일치 · `sceneReplay.js:58` 리플레이가 `outfit`만 소비해 **과거 복장 + 현재 배경/시간/BGM 혼합** · `ChatPageV2.jsx:1700` 낙관적 차감 플랫 2 하드코딩(부스트 시 실제 10) · `ChatPageV2.jsx:2104` 상태창이 `heroine.stats`를 읽는데 백엔드는 평탄 `statX` → **스탯 전부 0·관계 STRANGER 고정**.

컴포넌트: `AdultVerificationModal.jsx:105` 팝업을 닫으면 낡은 step 클로저로 'Verification in progress…' 영구 고착 · `LucidStore.jsx:486` 시크릿 상품이 `characters=[]` 진입점에서 복구 불가 데드엔드 · `SupportPanel.jsx:435` 알림 탭이 읽음 처리로 스스로를 닫음 · `EndingCredits.jsx:305` SPECIAL THANKS 단계에 자동 진행 타이머가 없어 무조작 재생이 영구 정지.

### E-2. 일러 프롬프트 조립 — enum과 거의 무관한 맵

`IllustrationPromptAssembler`는 `room.getCurrentOutfit().name()`/`getCurrentLocation().name()`을 그대로 키로 쓴다.

- **LoRA가 4인 하드코딩** (`:272`) — 나머지 **공식 6인과 전 UGC가 아이리 LoRA로 생성**된다.
- **복장 default 분기가 AIRI 맵** (`:296`) — 기본복장 DAILY인 공식 6인이 전부 **메이드복**으로 조립.
- **감정 키 불일치** (`:138`) — `SURPRISED` vs enum `SURPRISE` 등, 15개 중 **10개가 무표정 폴백**.
- **`SWIMSUIT` vs enum `SWIMWEAR`** (`:82,89,96,103`) — 수영복 해금 후에도 기본 복장으로 렌더.
- **`PAJAMA`·`NEGLIGEE`가 어느 맵에도 없음** (`:78`) — 잠옷/네글리제 씬이 메이드복.
- **장소 맵 11키 중 enum과 겹치는 건 3개**(`BEDROOM`·`KITCHEN`·`BEACH`) (`:316`) — 나머지 11개 enum 값이 `"simple background"`, 맵의 8키는 도달 불가 사문.
- `CharacterPromptAssembler.java:134` UGC 캐릭터 프롬프트에 **`Age: null` 리터럴** 삽입(V2 어셈블러만 가드).
- `SceneRenderService.java:255` 씬 일러 유저 성별이 LLM 출력에만 의존, 방 페르소나 성별 스냅샷 미배선.

### E-3. 시드 데이터 — 존재하지 않는 키

**① V1 정적 장소 5종이 `Location` enum에 없음** → `parseLocationOrDefault`가 `ENTRANCE`(저택 현관)로 폴백. 클레어 `CATHEDRAL`(`characters.yml:599,616`) · 로제타 `TERRACE`(`:718,734`) · 강채린 `TERRACE`/`STREET`(`:832,847`) · 에델 `TERRACE`/`LIBRARY`(`:1056,1072`) · 류설아 `ABANDONED_SHRINE`(`:1175,1191`). 강채린·에델의 `default-location`은 로제타의 `TERRACE` 복붙이라 자기 `baseLocations`에도 없다.

**② V2 루틴 11건이 선언되지 않은 장소 키 참조** *(2026-07-23 지목 · 미수정)* — 시더가 문자열을 그대로 저장하고 `WorldRoutingService`가 `p.moveTo(ghostKey)`로 배치해 **도달 불가 장소에 히로인이 놓인다**.

| 캐릭터 | 유령 키 (실제 선언값) | 시간대 · 확률 |
|---|---|---|
| 연화 | `MOONLIT_FOREST` (→ `DEEP_FOREST`) | 오후 50% · 저녁 35% · 밤 55% |
| 로제타 | `GARDEN_OF_MIRRORS` (→ `GARDEN_OF_ACADEMY`) | 아침 20% · 정오 30% · 오후 45% · 저녁 25% · 밤 20% |
| 시에라 | `GARDEN_OF_MIRRORS` | 정오 30% · 오후 30% · 저녁 40% |

**③ `application-worlds.yml:49`** FANTASY_ACADEMY `default-bgm: MYSTERIOUS` — `BgmMode`에 없어 `DAILY`로 폴백. `application-v2.yml`은 219행 주석대로 교정됐고 **`worlds.yml`만 남았다**.

**④ 엔딩 시드 빈 문자열이 폴백을 무력화** — `applySeed`도 `getEffective*`도 `!= null` 검사라 `""`가 그대로 저장된다. `ending-role-desc` 복붙: 강채린(`:837`)·시에라(`:948`)·에델(`:1061`)이 전부 로제타의 *"a mesugaki noble mage…"*. 빈 문자열: 같은 3인의 엔딩 인용구 + 류설아(`:1180-1182`) **3필드 전부**. 결과적으로 엔딩 프롬프트가 `"You are 류설아, ."`로 파손되고, 나머지 3인은 **다른 캐릭터 페르소나로 엔딩을 생성**한다.

### E-4. 도메인 상태 · 게이트

`RelationStatusPolicy.java:94` `isUpgrade`의 ordinal 비교로 ENEMY 회복 전이가 전부 '승급 아님'(시드 #8 동종 타 위치) · `ChatStreamService.java:909` 승급 진행도가 **스탯 변화량 절댓값 합**이라 캐릭터를 모욕해도 승급 성공(실패 분기가 사문) · `TheaterCommandClassifier.java:325` LLM 분류 결과를 버리고 무조건 `ALLOWED_OTHER`(거부 게이트 전면 무력) · `TheaterService.java:225` `finalizeChapter` 중복 호출 가드 부재 → 챕터 스킵·스태미나 무한 리필 · `TheaterSaveLoadService.java:205` 세이브 로드가 `sessionStatus(ENDED)`·`majorBranchDone` 미복원 → 엔딩 후 모순 상태 · `StoryV2Service.java:749` 리셋이 월드 메모리 Redis 캐시를 안 지워 **최대 2시간 이전 회차 기억이 주입** · `:721` 리셋 cascade가 `scene_illustrations`를 안 지워 '씬당 1회' 게이트 오차단 · `:380` V2 히로인 검증에 `isHidden` 누락 · `EndingService.java:74` V2 STORY 방에서 `/ending/generate` 호출 시 `room.getCharacter()` NPE 500 · `EndingEligibilityService.java:112` **V2 STORY 엔딩 업적이 영구 미해금** · `TheaterEndingService.java:221` `resolveEndingModel`이 호출처 0건 사문(엔딩이 저비용 모델로 생성) · `:322` 기억 하이라이트가 최근 5개가 아니라 **최초 5개** · `OffscreenNotificationService.java:158` 토스트가 `respondedAt`/`expiresAt`를 무시해 소비·만료된 알림이 계속 재노출 · `AchievementController.java:28` 유저 스코프 갤러리가 방 스코프 URL로만 열림 · `BackgroundGenerationService.java:304` 시크릿 배경 웹훅 폴백이 구조적 사문 · `ChatService.java:278` 동적 배경 백필이 `@Deprecated` 해시를 써 새로고침 후 영구 미표시 · `ChatStreamService.java:562` 이벤트·자동디렉터 씬 상태 저장이 죽은 `isStoryMode()` 게이트 안에 갇힘.

### E-5. 모더레이션 · 심사 우회

`ChatStreamService.java:530` 이벤트 선택의 클라이언트 `detail` 텍스트가 **모더레이션·인젝션 가드를 전혀 통과하지 않고** LLM 컨텍스트에 영속 · `UgcCharacterService.java:88` 완성 캐릭터 텍스트 수정이 모더레이션·공개 심사 가드를 우회해 **승인 후 내용 교체 가능** · `UgcWorldService.java:462` 승인된 월드에 장소를 추가해도 `APPROVED` 유지 → **미검수 텍스트가 공개 캐릭터 프롬프트에 주입**.

### E-6. 어드민

`AdminUgcReviewService.java:252` 프롬프트 인스펙션이 V20 gender 미반영(남캐를 `1girl`로 재구성) · `SupportTicketService.java:106` 상태+유형 동시 필터 시 type 무시 · `AdminAuditController.java:42` 관리자+액션 동시 필터 시 action 무시 · `AdminCharacterController.java:72` P0 공개 철회(unpublish) 엔드포인트를 호출하는 화면이 전무 · `UserDetailPage.jsx:97` CS 대화 로그가 **가장 오래된 100건만** 로드.

### E-7. 크래시 2건

`OAuth2LoginSuccessHandler.java:138` — 세 upsert 경로 모두 `(provider, providerId)`로만 조회하고 없으면 provider email로 신규 INSERT하는데 `users.email`은 전역 UNIQUE다. **동일 이메일이 다른 provider나 LOCAL로 이미 있으면 `DataIntegrityViolationException` → 500**, 필터 단계라 `@RestControllerAdvice`도 못 잡고, providerId가 저장되지 않아 재시도해도 매번 실패한다(해당 유저는 두 번째 provider로 영구 로그인 불가).

`UgcCharacterService.java:90` — `PATCH .../texts`에 길이 검증이 전무한데 대상은 `name VARCHAR(50)`·`tagline VARCHAR(100)`·`tone VARCHAR(300)`이고 런타임은 PostgreSQL이라 조용한 절삭 없이 거부된다. 말투 4~5문장이면 300자를 넘겨 **400이 아니라 500**이 뜨고 어느 필드가 문제인지 안내도 없다. 형제 경로(`UgcWorldService.validateTextLimits`, `startCreation`)는 전부 검증이 있는데 이 경로만 누락.

---

## F. P3 — 카피 · 표시

| 결함 | 위치 | 증상 |
|---|---|---|
| Theater 업셀이 'Lucid Pass 최대 40 P' 광고 | `TheaterCreateFlow.jsx:741` | 실제 LUCID_PASS는 20P(미드나잇만 500P) |
| 인터미션 대성공 효과음 분기가 `"CRIT"` 비교 | `TheaterIntermissionPage.jsx:107` | 백엔드는 `"GREAT_SUCCESS"` → 무음 |
| 투명인간 `narrationMap` 공식 4인 하드코딩 | `useInvisibleMan.js:52` | 신규·UGC는 범용 폴백 (시드 #3 동종) |
| 폴백 나레이션 조사 `가` 하드코딩 | `useInvisibleMan.js:70` | "강채린가 가까이…" |
| FOURTH_WALL에 `Airi.exe` 하드코딩(캐릭터 게이팅 없음) | `EasterEggEffects.jsx:197` | 타 캐릭터 방에서 아이리 모듈명 노출 |
| 모더레이션 '단계'가 `step===1 ? '키워드' : 'OpenAI'` | `ModerationPage.jsx:86` | UGC VLM(3)·Stage0(4)이 'OpenAI'로 오표기 |
| 난입 시작 응답 히로인 이름이 lead 히로인 고정 | `TheaterInterventionService.java:112` | 현재 화자와 불일치 |
| 에너지 부족이 SSE에서 '예기치 않은 오류' | `ChatStreamService.java:488` | 전용 에러코드 소실 |

---

## G. 반박 · 미확정

**반박 10건** — 인용 코드는 실재하나 도달 불가하거나 상위 레이어가 방어. 대표: `legacy-v1-story-room-unknown-world-card`(전제 상태가 `ChatRoom` 생성 가드로 도달 불가), `oauth-upsert-transactional-self-invocation-noop`(자기호출은 사실이나 `SimpleJpaRepository`의 자체 트랜잭션으로 실피해 없음), `verify-age-year-boundary-mismatch`(만나이 계산이 오히려 정책 정합), `logout-requires-authorization-header`, `verify-session-getanddelete-not-atomic`(호출처 단일·경합 창 실질 부재), `chatlog-deadletter-written-to-same-failed-mongo`(D-6로 재분류), `redis-getstring-unguarded-authguard-fail-closed`(DB 폴백 존재), `v2-location-move-modal-uses-start-only-pool`, `ending-scene-location-hardcoded-mansion-set`.

**미확정 0건** — 유일하게 남았던 `lobby-v1-story-create-guaranteed-500`은 2차 검증자가 두 번 모두 완주하지 못해 강등됐으나, 수동으로 체인 전체(엔티티 기본값 → 시드 → 버튼 활성 조건 → 핸들러 분기 → 서비스 가드 → 생성자 throw → 예외 매핑)를 추적해 **확정**하고 C-0으로 승격했다.

**결함 아님** — UGC 게이트 3종 yml 기본값 `true`. javadoc·주석과 반대이나 [`docs/12` D절](12_MaleBuilder_Polish_Deploy_Handoff.md)에 *"종원 명시 수용"*으로 기록된 의도된 결정이다. 주석만 정리하면 된다.

---

## H. 수정 우선순위 제안

**배치 1 — 착취 차단 (백엔드 단독, 프론트 배포 불요)**
B-1 웹훅 `merchant_uid` 대조 + `impUid` unique → B-2 `beta-activate` 제거 → B-3 `energyCost` 검증(V1·V2 양쪽) → B-4 분기 스냅샷 서버 재판정 → B-5 prefetch 플래그 제거 → B-6 리롤 과금/플래그 → B-7 업적 조건 검증 → B-8·B-9 엔딩 멱등성·타입 대조 → B-12 공지 published 검사 → B-13 알림 IDOR. B-1·B-2는 현재 인터넷에 열려 있어 **가장 먼저**.

**배치 2 — 죽은 핵심 플로우 복구 (프론트 중심, 배치 1과 병행 가능)**
C-0 스토리 모드 진입(신규 유저가 스토리를 시작할 방법이 현재 없음 — 체감 영향 최대) → C-1 성인 인증 경로 접두사 → C-2 V2 결제 진입점을 `LucidStore`로 → C-3 `accessReason === "GRANTED"` 비교. **C-1·C-3은 한 줄, C-0·C-2는 라우팅 교체 수준**인데 각각 컴플라이언스·매출·환불 클레임에 직결한다.

**배치 3 — 자산 손실 정지**
D-1 `refundEnergy` paid 우선 복원(호출부 6곳) → D-2 환불 누락 5건(공통 `failFinalize`로 수렴) → D-3 좀비 잡·중복 지출 6건 → D-4 구독 정합 3건(Flyway V25) → D-5 prefetch batchId → D-6 로그·캐시 2건.

**배치 4 — 한 커밋짜리 문자열 교정 (저비용 고효과)**
`useTheaterStream` try/finally · `parentLogId` 한 줄 · `SWIMSUIT→SWIMWEAR` 4곳 · `worlds.yml` BGM · 유령 장소 키 11행 · `CRIT→GREAT_SUCCESS` · 감정 키 10종 · Theater 40P 카피 · 모더레이션 단계 라벨.

**배치 5 — 구조 수정**
E-2 일러 프롬프트 맵 전면 재작성(LoRA·복장·장소·감정 — DB 일반화와 함께) · E-3 ① Location enum 확장(마이그레이션 검토) · ④ 엔딩 시드 채우기 · E-1 나머지(SSE single-flight 통일, abortController 배선, 스테일 클로저, turnIndex 좌표계) · E-4 도메인 상태 17건 · E-5 모더레이션 우회 3건 · E-6 어드민 5건 · E-7 크래시 2건.
