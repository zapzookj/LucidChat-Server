# 17-assets. docs/13 확정 결함 — 현재 코드 검증 레지스터 (2026-08-20)

> [`13_BugSweep_Findings.md`](../13_BugSweep_Findings.md)의 확정 104건을 **원자 단위 245건**으로 분해하고, 블록 A·B 반영 후의 master HEAD(aichat `14fd094` · FE `55a4b78` · Admin `0188aba`) 실코드로 전수 재검증한 결과다.
> docs/13의 파일:라인은 상당수 낡았다 — **이 문서의 좌표가 정본**이다. 상위 판단·배치·결정 안건은 [`17_BugFix_Session_Readiness.md`](../17_BugFix_Session_Readiness.md).

> ⚠ **2026-08-21 재판정 — 상태와 좌표는 [`19_assets/rejudgment_delta.md`](../19_assets/rejudgment_delta.md)가 정본이다.**
> 블록 D(aichat `20c4cf9` · FE `b062997`) 반영 후 245건을 전수 재판정했다. **좌표 114건이 갱신**됐다 — `ChatStreamService.java`(-373줄)·`ChatRoom.java`(-137)·`CharacterPromptAssembler.java`(-130)·FE `ChatPage.jsx`(-307)·`ChatPageV2.jsx`(-330)·`BiometricStatusPanel.jsx`(전면 재작성)에 걸린 원자는 **이 문서의 라인 번호를 쓰지 마라.**
> **이 문서의 근거·수정안 본문은 그대로 유효**하다. 델타 표는 그 위에 덮는 상태·좌표 갱신분이다. 상위 판단은 [`19_Register_Rejudgment.md`](../19_Register_Rejudgment.md).

| 상태 | 작성 시(08-20) | **재판정 후(08-21)** |
|---|---|---|
| 🔴 잔존 | 236 | **197** |
| 🟡 게이트차단 (`legacy.*` 노브 기본 off — 켜면 부활) | — | **24** |
| ✅ 수정됨 | 6 | 13 |
| 🟠 부분수정 | 2 | 4 |
| ⚪ 소멸 | 1 | 6 |
| ↔ 재분류 | — | 1 (+D-3.6 → 08-26 기준 **2**) |

> ⚠ **2026-08-26 본문 정정 (D-33 문서 정정 묶음)** — 이 문서 본문에 직접 반영한 3건.
> 1. **마이그레이션 번호 전면 정정.** 본문 곳곳의 "V25/V26" 지시는 전부 낡았다. 실측 점유: **V25 블록 B**(페르소나) · **V26·V27 블록 D**(BPM·승급 NOT NULL 해제) · **V28 결제 정합**(`V28__orders_imp_uid_unique.sql`). 신규는 **V29부터**이며 배정 계획은 **`V29 구독 / V30 에너지 분할 / V31 일러·극장`**이다(docs/17 §B-3과 동일). **착수 전 `ls src/main/resources/db/migration/`로 다음 가용 번호를 매번 확인하라** — 같은 번호로 새 파일을 만들면 로컬은 checksum mismatch로 죽고 프로드는 조용히 통과해 스키마가 갈린다(CLAUDE.md §2-2).
> 2. **C-0.5 원 수정안 폐기 · 무수정 존치로 종결** — `storyAvailable`은 V1 STORY 잔재가 아니라 **V2 STORY 히로인 풀 필터**다. 시드를 일괄 false로 내리면 라이브 기능이 죽는다. 해당 절 머리말 참조.
> 3. **D-3.6 재분류: D절(P1) → B절(P0-A 착취)** — 순 0E 무한 GPU 드레인이 성립한다. 배치 3이 아니라 **배치 1(착취 차단)** 소관.

---

## B. P0-A 착취 가능 (자금·권한)  (39건)

### B-1.1. 결제 검증이 PortOne 응답의 merchant_uid를 주문과 대조하지 않음 — imp_uid 1개로 주문 N건 지급

**🔴 잔존** · P0 · SMALL · BE  
`aichat/src/main/java/com/spring/aichat/service/payment/PaymentService.java:175-205`

**근거**

verifyAndDeliver 전문 확인. 175행 `JsonNode paymentInfo = portOneClient.getPaymentInfo(impUid);` 이후 검증은 status와 금액 둘뿐이다:
  178: `if (!"paid".equals(portOneStatus)) { ... }`
  186: `if (paidAmount != order.getAmount()) { ... }`
  202: `order.markPaid(impUid); deliverProduct(order);`
`grep -n "merchant_uid" PaymentService.java` → 결과 0건. PortOne 응답 노드(`response.merchant_uid`)를 읽는 코드가 파일 전체에 없다. PortOneClient.java:75-102 `getPaymentInfo`도 응답 body의 `response` 노드를 그대로 반환할 뿐 대조하지 않는다.
금액 대조는 `order.getAmount()`(= ProductType.priceKrw) 기준이라 **동일가 교차상품이 통과**한다: ProductType.java:28 `SECRET_UNLOCK_PERMANENT("캐릭터 시크릿 영구 해금", 14900, ...)` 와 :31 `LUCID_PASS("루시드 패스", 14900, ...)` 가 같은 14,900원. 지급은 order.getProductType() 기준(PaymentService.java:222-254 deliverProduct)이므로 14,900원 구독 결제 1건의 imp_uid로 시크릿 영구해금을 무상 취득할 수 있다.
동일 상품 반복은 PENDING 주문을 N개 만들어 같은 imp_uid로 N회 확정하면 ENERGY_T3 기준 250×N 에너지(deliverProduct:227 `user.chargePaidEnergy(product.getEnergyAmount())`).

**수정안**

PaymentService.verifyAndDeliver(PaymentService.java:175 직후, status 체크보다 앞)에 merchant_uid 대조를 추가한다:
```java
String paidMerchantUid = paymentInfo.path("merchant_uid").asText(null);
if (paidMerchantUid == null || !paidMerchantUid.equals(order.getMerchantUid())) {
    log.error("[PAYMENT:{}] MERCHANT_UID MISMATCH! order={}, portone={}", caller, order.getMerchantUid(), paidMerchantUid);
    order.markFailed("merchant_uid mismatch: " + paidMerchantUid);
    orderRepository.save(order);
    throw new BusinessException(ErrorCode.PAYMENT_VERIFICATION_FAILED, "Payment/order mismatch");
}
```
※ 자동 환불(cancelPayment)은 호출하지 말 것 — 이 경로의 결제는 *타인/타주문의 정상 결제*이므로 환불하면 정상 유저 피해가 발생한다(금액 불일치 경로와 대칭으로 만들면 안 됨). verifyAndDeliver가 /confirm·/webhook 공통 경로라 이 1곳 수정으로 양쪽이 막힌다.

**제품 결정 연동**: 블록 D 무관(레거시 처분 대상 아님). 반대로 **docs/16으로 우선순위 상승**: docs/16 §D가 '유해매체물 표시 + PASS/NICE 성인인증 → 국내 정규 PG 결제 가능'을 피벗의 전제로 삼는데, 이 결함은 그 PG 결제망 자체의 정합성 결함이고 표적 상품이 하필 신규 핵심 BM인 SECRET_UNLOCK_PERMANENT다. 시크릿 상품 매출을 켜기 전에 반드시 선행 수리.

---

### B-1.2. Order.impUid에 unique 제약·인덱스 부재 — DB 레벨 재사용 차단 장치 없음

**🔴 잔존** · P0 · SMALL · BE/DB_MIGRATION  
`aichat/src/main/java/com/spring/aichat/domain/payment/Order.java:13-17, 26-27`

**근거**

Order.java:13-17 테이블 정의에 imp_uid 인덱스가 없다:
```java
@Table(name = "orders", indexes = {
    @Index(name = "idx_order_merchant_uid", columnList = "merchant_uid", unique = true),
    @Index(name = "idx_order_user_id", columnList = "user_id"),
    @Index(name = "idx_order_status", columnList = "status")
})
```
:23 merchant_uid는 `unique = true`인데 :26-27은 `@Column(name = "imp_uid", length = 50) private String impUid;` — unique 없음. `grep -rn "imp_uid" src/main/resources/db/migration/*.sql` → 0건(orders는 Flyway 관리 밖, ddl-auto 생성 테이블).
결과: B-1.1을 애플리케이션 레이어에서 고쳐도 동시성(같은 imp_uid 두 요청 병렬) 하에서는 최후 방어선이 없다. merchantUid 단위 비관적 락(`findByMerchantUidForUpdate`, PaymentService:93·111)은 *서로 다른* 주문 행을 잠그므로 imp_uid 재사용 레이스를 막지 못한다.

**수정안**

(1) Order.java:26에 `@Column(name = "imp_uid", length = 50, unique = true)` 부여 + @Table indexes에 `@Index(name="uk_order_imp_uid", columnList="imp_uid", unique=true)` 추가. (2) Flyway 마이그레이션 신설 — **V28**(2026-08-26 정정 · D-33 — V25는 블록 B 페르소나, V26·V27은 블록 D가 선점. 실제 파일 `V28__orders_imp_uid_unique.sql`): 기존 중복 imp_uid 정리 SELECT를 먼저 돌린 뒤 `CREATE UNIQUE INDEX uk_order_imp_uid ON orders (imp_uid);` (NULL 다중 허용되는 MySQL/MariaDB 기준 PENDING 주문의 NULL impUid는 문제없음 — DB 엔진 확인 후 적용). (3) PaymentService.verifyAndDeliver의 markPaid 저장 지점(:202-204)에서 DataIntegrityViolationException을 잡아 PAYMENT_ALREADY_PROCESSED로 매핑.

**제품 결정 연동**: 블록 D 무관. 단 **운영 주의**: 프로드는 ddl-auto=validate(application.yml:50 주석 명시)이므로 엔티티만 고치면 기동 실패한다 — 반드시 Flyway 마이그레이션과 세트로 배포. **신규 번호는 V28부터**(2026-08-26 정정 · D-33 — V25 블록 B / V26·V27 블록 D 선점). 착수 전 `ls src/main/resources/db/migration/`로 다음 가용 번호를 매번 재확인할 것.

---

### B-1.3. 결제 웹훅이 무인증 공개(permitAll) + 서명·IP 검증 전무 — 로그인 없이 타인 주문 확정 가능

**🔴 잔존** · P0 · MEDIUM · BE/YML/INFRA  
`aichat/src/main/java/com/spring/aichat/controller/PaymentController.java:98-121 · config/SecurityConfig.java:64`

**근거**

SecurityConfig.java:57-67 permitAll 목록에 `"/api/v1/payments/webhook",   // Phase 5: PortOne webhook (no JWT)` 가 그대로 있다.
PaymentController.java:98-110:
```java
@PostMapping("/webhook")
public ResponseEntity<Map<String, String>> handleWebhook(@RequestBody Map<String, Object> payload) {
    String impUid = (String) payload.get("imp_uid");
    String merchantUid = (String) payload.get("merchant_uid");
    ...
    paymentService.processWebhook(impUid, merchantUid);
```
서명 검증·IP 화이트리스트·공유 시크릿 어느 것도 없다. 같은 저장소의 다른 웹훅은 최소한의 시크릿 매칭을 한다는 점에서 비대칭이다 — IllustrationWebhookController.java:60 `if (!verifySecret(modelsLabProps.webhookSecret(), headerSecret, querySecret))`.
PaymentService.java:110-119 processWebhook은 merchantUid로 주문을 찾을 뿐 **호출자 신원을 전혀 보지 않는다**(반면 /confirm은 PaymentService.java:97-101에서 `order.getUser().getUsername().equals(username)` 소유권 검증). 즉 merchant_uid만 알면 타인 주문도 확정 가능.
RateLimiter도 미적용(PaymentController:52·72의 `rateLimiter.checkPayment`는 /prepare·/confirm에만).

**수정안**

B-1.1+B-1.2가 들어가면 '아무 imp_uid나 아무 주문에' 붙이는 착취는 봉쇄되지만, 무인증 표면 자체는 남는다. 단계적으로: (1) 즉시 — PaymentController.handleWebhook 진입부에 공유 시크릿 검증 추가(IllustrationWebhookController.verifySecret 패턴 재사용, `portone.webhook-secret: ${PORTONE_WEBHOOK_SECRET:}` yml 노브 신설, 미설정 시 skip이 아니라 **prod 프로필에서는 fail-closed**). (2) PortOne 콘솔의 웹훅 발신 IP 대역 화이트리스트를 ALB/보안그룹 또는 필터에서 적용. (3) 웹훅 경로에도 imp_uid 단위 레이트리밋 부여. 어느 경우든 webhook은 비200을 반환하지 않는 현행 계약(PaymentController:95-97 주석)을 지키되 검증 실패는 200 + status:"rejected"로 응답하고 WARN 로깅.

**제품 결정 연동**: 블록 D 무관. docs/16 §D의 PG 심사 관점에서 '결제 웹훅 무인증'은 심사 지적 사항이 될 수 있으니 시크릿 상품 오픈 전 처리 권장.

**❓ 결정 필요**: 현재 연동이 PortOne V1(api.iamport.kr — application.yml:73 `api-url: https://api.iamport.kr`)인데 V1 웹훅은 서명(Standard Webhooks)을 제공하지 않는다. (a) V1 유지 + 공유 시크릿/IP 화이트리스트로 갈지, (b) 이번에 PortOne V2로 전환해 정식 webhook-secret 검증을 쓸지 — PG 심사 일정과 묶인 인프라 결정이라 종원 판단 필요.

---

### B-2. `/users/beta-activate` 자가 지급 엔드포인트 — 이미 제거됨

**✅ 수정됨** · P0 · N/A · -  
`N/A (코드 삭제됨)`

**근거**

블록 B 커밋 `cab6b3e`에서 세트로 제거된 것을 3방향 확인.
(1) BE 엔드포인트 — UserController.java:157-159에 삭제 주석만 남음: `// [블록 B 선행 픽스 — docs/13 B-2·docs/14 §G-3] beta-activate 엔드포인트 제거. // NICE 없이 isAdult+미드나잇 구독을 부여하는 성인인증 우회로, 페르소나 나이 게이트까지 // 무력화하는 P0 착취면이었다. 프론트 트리거(로고 5회 클릭)와 세트로 제거.`
(2) BE 서비스 — UserService.java:157-158 `// [블록 B 선행 픽스 — docs/13 B-2] activateBetaTester 제거 — 성인인증 우회 세트 삭제`
(3) FE 트리거 — `grep -rn "beta-activate|betaActivate|베타 테스터"` 를 LucidChat-Front/src 와 LucidChat-Admin/src 전체에 실행 → 0건. docs/13이 지목한 LobbyPage.jsx 자체가 블록 A R2(`0e82296`)에서 소멸하고 pages/lobby/{LobbyShell,HomeTab,StoryTab,ArchiveTab,FirstMeetPage}.jsx 체계로 교체됨.
`grep -rn "BETA_TESTER"` 백엔드 전체 → 0건(상수·CI 해시 문자열도 잔존 없음).

**수정안**

조치 불필요. 단 잔존 데이터 점검 1건만 권장: 과거 이 엔드포인트로 발급된 `ci_hash LIKE 'BETA_TESTER_%'` 유저 행과 대응 결제 없는 subscription 행이 프로드 DB에 남아 있는지 SELECT로 확인(코드는 사라졌지만 부여된 성인인증·구독은 남아 있다). docs/16이 성인인증을 규제 요건으로 격상했으므로 가짜 CI 해시 보유 계정은 정리 대상.

**제품 결정 연동**: docs/14 §G-3(🔴삭제 '로고 5회 클릭 베타 이스터에그')·impl_spec_details §5 '세트 제거' 지시가 이미 이행 완료. docs/16 §D도 B-2를 C-1 동반 수리 대상으로 적었는데 이 항목은 선행 완료 상태다.

**❓ 결정 필요**: 과거 beta-activate로 생성된 가짜 성인인증(ci_hash=BETA_TESTER_*) 계정을 프로드에서 무효화할지 — 시크릿 BM 오픈 전 정리 여부는 정책 판단.

---

### B-3.1. 이벤트 선택 `/events/select`가 클라이언트 지정 energyCost를 그대로 차감 — 음수로 에너지 발행

**🔴 잔존** · P0 · SMALL · BE  
`aichat/src/main/java/com/spring/aichat/controller/StoryController.java:120-137, 176 · service/stream/ChatStreamService.java:499-521`

**근거**

엔드포인트 `POST /api/v1/story/rooms/{roomId}/events/select`(StoryController.java:34 클래스 매핑 + :120)가 살아 있다.
StoryController.java:135 `chatStreamService.sendEventSelectStream(roomId, request.detail(), request.energyCost(), emitter);`
StoryController.java:176 `public record SelectEventRequest(String detail, int energyCost) {}` — 제약 애너테이션 없음. :124 `@RequestBody SelectEventRequest request` — **`@Valid` 미부착**.
ChatStreamService.java:499 `public void sendEventSelectStream(Long roomId, String eventDetail, int energyCost, SseEmitter emitter)` → :517 `room.getUser().consumeEnergy(energyCost);` 가 txTemplate.execute 안에 있어 dirty checking으로 커밋되고, :523 `cacheService.evictUserProfile(jpa.username());`로 프로필 캐시까지 무효화되어 `/users/me`가 즉시 부풀린 값을 반환한다.
도달성: 가드는 :505-510 `ChatModePolicy.supportsEvents(modeCheck.getChatMode())`(ChatModePolicy.java:103-105 `return mode == ChatMode.SANDBOX;`)와 :513 UGC 접근 체크뿐. 자기 SANDBOX 방을 가진 모든 인증 유저가 도달 가능(StoryController:121 `@PreAuthorize checkRoomOwnership`은 소유자면 통과).
※ 현재 FE는 이 엔드포인트를 호출하지 않는다(ChatPage.jsx:1848-1888 handleSelectEvent가 `triggerAutoDirectorResponse("BRANCH", detail)` → 서버 하드코딩 cost=1 경로인 ChatStreamService.java:1585로 대체됨; sendEventSelectStream은 ChatPage.jsx:26·ChatPageV2.jsx:40에 import만 남은 사문). 그러나 **BE 엔드포인트는 삭제되지 않아 직접 HTTP 호출로 도달 가능**하므로 착취면은 그대로다.

**수정안**

택1이지만 (A) 권장. (A) 엔드포인트 폐기: StoryController.java:120-137 selectEvent + :176 SelectEventRequest + ChatStreamService.sendEventSelectStream(:499-598) 제거, FE의 죽은 import(ChatPage.jsx:26, ChatPageV2.jsx:40, api/UseChatStream.js:23-26) 동반 정리. FE가 이미 auto-respond로 대체됐으므로 기능 회귀 없음. (B) 존치할 경우: SelectEventRequest에 `@PositiveOrZero @Max(4) int energyCost` + 컨트롤러 `@RequestBody @Valid`를 걸고, 근본적으로는 서버가 비용을 결정해야 한다 — 이벤트 옵션 생성 시점(`POST /story/rooms/{roomId}/events`)에 옵션별 energyCost를 Redis에 optionToken 키로 저장하고 선택 시 토큰으로 재판정(B-4 제안과 동일 패턴). 어느 쪽이든 B-3.2 근본 가드는 별도로 반드시 넣을 것.

**제품 결정 연동**: **docs/14 §G #13이 직접 지시**: '디렉터 3분기 카드 — 골격 유지, 고정 3톤→맥락 가변 제안 + energyCost 서버 판정(docs/13 P0 픽스 세트)' → 수리는 승인된 방향. 한편 **§G #7(⚠게이트오프) 'V1 디렉터 잔여 — INTERLUDE/TRANSITION/AWAY 소비 경로 정리, 시간 넘기기만 존치'** 와 §G #2(V1 STORY 트랙 삭제)가 이 엔드포인트를 삭제 후보로 만든다. 단 이 경로는 STORY가 아니라 **SANDBOX 전용**(supportsEvents=SANDBOX)이라 §G #2의 사정거리에는 들어가지 않는다 — 블록 D 담당자가 §G #2로 착각해 지우거나, 반대로 SANDBOX라서 남겨두는 판단이 갈릴 수 있으므로 명시 필요.

**❓ 결정 필요**: `/events/select`(V1 SANDBOX 이벤트 카드)를 §G #7 'V1 디렉터 잔여 정리'에 포함해 **삭제**할지, 아니면 SANDBOX 페이싱 도구로 **존치하고 서버 비용 판정으로 수리**할지. 삭제면 픽스 비용이 0에 수렴하고 존치면 옵션 토큰 영속 설계가 필요하다.

---

### B-3.2. User.consumeEnergy에 음수 방어가 없음 — 모든 차감 경로의 근본 착취면

**🔴 잔존** · P0 · ONE_LINE · BE  
`aichat/src/main/java/com/spring/aichat/domain/user/User.java:166-179`

**근거**

```java
166:    public void consumeEnergy(int amount) {
167:        int total = this.freeEnergy + this.paidEnergy;
168:        if (total < amount) {
169:            throw new InsufficientEnergyException(...);
170:        }
171:        if (this.freeEnergy >= amount) {
172:            this.freeEnergy -= amount;
...
```
amount<0 가드가 없다. amount=-1000이면 168행 `30 < -1000` = false로 통과, 171행 `30 >= -1000` = true → `freeEnergy = 30 - (-1000) = 1030`. getFreeEnergyMax()(User.java:161-163, 비구독 30/구독 100) 상한도 적용되지 않는다.
대조군으로 역연산 메서드에는 가드가 있다 — User.java:187 `public void refundEnergy(int amount) { if (amount <= 0) return; ...`. 즉 방어 관례는 존재하는데 consumeEnergy만 누락된 상태.
호출부 22곳(`grep -rn "consumeEnergy" src`) 중 현재 클라이언트 값이 직접 흘러드는 곳은 ChatStreamService.java:517(B-3.1)과 TheaterBranchService.java:323(B-4.b) 두 곳이다.

**수정안**

User.java:167 진입부에 `if (amount < 0) throw new IllegalArgumentException("energy cost must be non-negative: " + amount);` 추가(또는 프로젝트 관례에 맞춰 BusinessException(ErrorCode.BAD_REQUEST)). amount==0은 오픈닝 경로 등에서 정상 no-op으로 쓰이므로(ChatStreamServiceV2.java:1297 주석 참조) **0은 허용하고 음수만 차단**할 것. 호출부를 전부 고치는 것보다 이 한 줄이 우선이며, B-3.1/B-4.b 픽스와 독립적으로 먼저 넣어도 안전하다(정상 경로 전부 amount>=0).

---

### B-3.3. docs/13이 주장한 'V2 경로에도 동일 결함' — 현재 V2는 서버 산정이라 해당 없음

**✅ 수정됨** · P0 · N/A · -  
`aichat/src/main/java/com/spring/aichat/service/story/ChatStreamServiceV2.java:191-199`

**근거**

docs/13 B-3은 'V2 컨트롤러 경로와 V1 스트림 서비스 경로 양쪽에 동일하게 존재'라고 적었으나, 현재 코드에서 V2 쪽 클라 지정 경로는 존재하지 않는다.
(1) StoryV2Controller(`@RequestMapping("/api/v1/v2/story")`)의 엔드포인트 전수: /worlds/{id}/create-context, /ugc-worlds, /rooms, /rooms/{id}, /rooms/{id}/reset, /rooms/{id}/messages/stream, /rooms/{id}/opening/stream, /rooms/{id}/notifications, /rooms/{id}/notifications/{nid}/read — **이벤트 선택 엔드포인트 자체가 없다**.
(2) V2 스트림의 비용 산정은 서버 측이다 — ChatStreamServiceV2.java:195 `int cost = boostModeResolver.resolveEnergyCost(room.getChatMode(), room.getUser());` → :196 `room.getUser().consumeEnergy(cost);`
(3) `grep -rn "events/select|EVENT_START" src` → StoryController/ChatStreamService 단일 계열만 히트. `apply-branch`(FE UseChatStream.js:157이 호출하는 `/story/rooms/{id}/director/apply-branch`)는 **백엔드에 존재하지 않는 죽은 호출**(`grep -rn "apply-branch" src` = 0건).
따라서 docs/13의 이 서술은 컨트롤러(StoryController:135)와 서비스(ChatStreamService:515)를 'V2/V1 두 경로'로 오귀속한 것으로 보인다. 수정 커밋을 특정할 수 없으므로 '문서 오귀속' 가능성을 함께 기록한다.

**수정안**

코드 조치 불필요. docs/13 B-3 문구에서 'V1·V2 양쪽' 서술을 정정하고, 부수적으로 FE의 죽은 API 함수 `sendDirectorBranchStream`(LucidChat-Front/src/api/UseChatStream.js:155-159, 백엔드 라우트 부재)을 데드코드 정리 대상(docs/14 §G #4)에 추가 등재할 것.

**제품 결정 연동**: docs/14 §G #4(데드코드 일괄 삭제)에 FE `sendDirectorBranchStream` 죽은 호출을 추가 편입 가능 — 블록 D 작업량 미미하게 증가.

---

### B-4.a. 극장 분기 ⓐ — 클라이언트가 보낸 `unlocked:true`를 그대로 믿어 스탯 잠금 우회

**🔴 잔존** · P0 · MEDIUM · BE/FE  
`aichat/src/main/java/com/spring/aichat/service/theater/TheaterBranchService.java:314-318`

**근거**

```java
311:        if (chosenIndex < 0 || chosenIndex >= optionsSnapshot.size()) {
312:            throw new BadRequestException("잘못된 선택 인덱스입니다.");
313:        }
314:        BranchOption chosen = optionsSnapshot.get(chosenIndex);
315:
316:        if (!chosen.unlocked()) {
317:            throw new BadRequestException("이 선택지는 아직 해금되지 않았습니다.");
318:        }
```
`chosen`은 요청 본문 `optionsSnapshot`에서 온 객체다(TheaterBranchController.java:74-79 `public record ConfirmBranchRequest(String level, int chosenIndex, String branchToken, List<BranchOption> optionsSnapshot)`, :96-97에서 그대로 전달).
서버 원본은 생성 시점에만 계산되고 **어디에도 저장되지 않는다** — parseBranchOptions(TheaterBranchService.java:271) `unlocked = state.getStat(stat) >= minValue;` 로 산출한 뒤 응답으로 내보내고 끝. 생성 시 Redis에 넣는 것은 컨텍스트 문자열뿐이다(:139-140 `batchCache.putBranchContext(roomId, branchToken, contextSummary)`).
따라서 `optionsSnapshot[i].unlocked=true`, `statGate=null`로 조작하면 stat_gate 30~70 요구치를 무시하고 잠긴 분기를 확정할 수 있다. FE 경로 실재 확인: LucidChat-Front/src/api/TheaterGameplayApi.js:24-30 `confirmBranchChoice(roomId, { level, chosenIndex, branchToken, optionsSnapshot })` → `POST /theater/rooms/${roomId}/branches/choose`.

**수정안**

근본 픽스는 ⓐ~ⓓ 공통이다(B-4.b/c/d와 한 덩어리로 처리 권장). TheaterBranchService.generateLocationBranch(:101-102)와 generateSceneBranch(:139-140)에서 branchToken 발급 시 **생성한 List<BranchOption> 원본 전체를 Redis에 함께 저장**한다(TheaterBatchCacheService에 putBranchOptions(roomId, token, json)/consumeBranchOptions 추가, TTL은 분기 유효시간 + 여유). applyBranchChoice(:306)는 시그니처에서 optionsSnapshot을 제거하고 `(roomId, username, level, chosenIndex, branchToken)`만 받아 Redis 원본을 로드해 `chosen`을 재구성한다. 원본 부재(토큰 만료/미발급)면 BadRequest로 fail-closed. FE는 TheaterGameplayApi.js:24-30에서 optionsSnapshot 전송을 제거(호출부 useTheaterStream 계열 동반 수정). 또한 generateBranchToken(:397-399)이 `level + "-" + roomId + "-" + System.currentTimeMillis()`로 **추측 가능**하므로 UUID 기반으로 교체할 것.

**제품 결정 연동**: docs/14 §C #6은 '극장 유지'이므로 게이트 오프 대상이 아니다. §G #13이 '골격 유지 + energyCost 서버 판정(docs/13 P0 픽스 세트)'을 명시하므로 이 재설계는 승인 방향과 정합. 극장 아바타 5축(매력/재치/대담/지성/공감)은 impl_spec_details §1 '극장 무변경 원칙'에 따라 블록 B 페르소나 렌즈와 무관하게 유지되므로 stat_gate 개념 자체는 살아 있다 — 즉 이 결함은 실효 결함이며 방치하면 극장의 유일한 성장 보상 구조가 무력화된다.

---

### B-4.b. 극장 분기 ⓑ — 클라이언트가 보낸 `energyCost:0`으로 MAJOR/CLIMAX 무과금

**🔴 잔존** · P0 · ONE_LINE · BE  
`aichat/src/main/java/com/spring/aichat/service/theater/TheaterBranchService.java:320-324`

**근거**

```java
320:        if (chosen.energyCost() > 0) {
321:            User user = userRepository.findByUsername(username)
322:                .orElseThrow(() -> new NotFoundException("유저를 찾을 수 없습니다."));
323:            user.consumeEnergy(chosen.energyCost());
324:        }
```
`chosen.energyCost()`가 클라이언트 스냅샷 값이므로 0을 보내면 조건문이 통째로 스킵된다.
서버 원본 비용은 enum 고정값이다 — TheaterBranchService.java:277 `level.getEnergyCost()`, BranchLevel.java:15-24 `MINOR(0,...)`, `MAJOR(1,...)`, `CLIMAX(2,...)`, `LOCATION(0,...)`. 즉 서버가 이미 정답을 알고 있는데 클라 값을 쓴다.
부수 확인: 음수 전송은 320행 `> 0` 조건에 걸려 consumeEnergy에 도달하지 않으므로 **이 경로로는 에너지 발행(minting)이 불가**하고 무과금까지만 성립한다(B-3과 심각도 성격이 다름).
영속 기록도 오염된다 — :341-342 `TheaterBranchChoice.record(..., chosenIndex, chosen.label(), chosen.heroineId(), chosen.energyCost())`로 조작값이 그대로 DB에 남아 사후 정산·감사가 불가능해진다.

**수정안**

B-4.a의 Redis 원본 재판정이 들어가면 자동 해소된다. 그 전에 **즉시 적용 가능한 단독 픽스**로는 TheaterBranchService.java:320-324를 클라 값 대신 enum 값으로 바꾸는 한 줄이면 된다:
```java
int cost = level.getEnergyCost();
if (cost > 0) { ...; user.consumeEnergy(cost); }
```
:342의 record 인자도 `chosen.energyCost()` → `cost`로 교체해 기록을 서버 진실로 통일할 것. (level 자체는 컨트롤러에서 valueOf 실패 시 MINOR로 폴백하므로 — TheaterBranchController.java:87-92 — 클라가 level="MINOR"를 보내 CLIMAX를 무과금 확정하는 우회가 남는다. 이건 B-4.e의 오퍼 검증으로 닫아야 한다.)

**제품 결정 연동**: docs/14 §G #13 'energyCost 서버 판정' 지시와 정확히 일치 — 승인된 픽스. 극장은 §C #6 '극장 유지'라 게이트 오프 대상 아님.

---

### B-4.c. 극장 분기 ⓒ — 클라이언트 `heroineId` 변조로 임의 캐릭터를 화자로 설정 + 무단 일러 생성 트리거

**🔴 잔존** · P0 · SMALL · BE  
`aichat/src/main/java/com/spring/aichat/service/theater/TheaterBranchService.java:346-347, 376-385`

**근거**

```java
346:        if (level == BranchLevel.LOCATION && chosen.heroineId() != null) {
347:            state.setCurrentHeroine(chosen.heroineId());
...
378:            Long speakerHeroineId = (level == BranchLevel.LOCATION && chosen.heroineId() != null)
379:                ? chosen.heroineId()
380:                : state.getCurrentHeroineId();
381:            Character speakerHeroine = speakerHeroineId != null
382:                ? characterRepository.findById(speakerHeroineId).orElse(null)
383:                : null;
384:            autoNoteService.captureBranchTaken(
385:                room, state, level.name(), chosen.label(), speakerHeroine);
```
검증이 전무하다: (1) TheaterState.java:304-306 `public void setCurrentHeroine(Long heroineId) { this.currentHeroineId = heroineId; }` — 소속 월드/방 히로인 여부를 보지 않는다. (2) :382 `characterRepository.findById(speakerHeroineId)` — **전역 캐릭터 ID 조회**라 방에 참여하지 않은 캐릭터, hidden 캐릭터, 타인 UGC 캐릭터를 지정할 수 있다. (3) 서버 원본에서 씬 분기의 heroineId는 항상 null이다(parseBranchOptions:278 `null, null, null,` — heroineId/heroineName/locationName 자리) — 즉 LOCATION 외 분기에 heroineId가 실려오는 것 자체가 위조 신호인데 걸러지지 않는다.
2차 피해: TheaterAutoNoteService.java:125-126 `if (shouldGenerateIllustration && speakerHeroine != null) { triggerIllustration(room, speakerHeroine, "BRANCH_TAKEN", note.getId()); }` — MINOR가 아닌 레벨이면 임의 지정한 캐릭터로 **일러스트 생성(외부 유료 호출)이 자동 발사**된다. 무과금(B-4.b)과 결합하면 0원으로 타인 캐릭터 일러를 무제한 생성시킬 수 있다.

**수정안**

B-4.a의 Redis 원본 재판정으로 근본 해소. 단독 픽스가 필요하면 :346 이전에 소속 검증을 넣는다 — `affectionRepository.findByRoom_Id(roomId)`에 등재된 히로인 ID 집합에 포함될 때만 setCurrentHeroine 허용(generateLocationBranch가 옵션을 만드는 원천이 바로 이 집합이다: TheaterBranchService.java:65-66 `affectionRepository.findByRoomOrderByAffectionDesc(roomId)`). 미포함이면 BadRequest. 아울러 :378-383의 speakerHeroineId도 같은 집합으로 필터링해 triggerIllustration이 방 외부 캐릭터로 발사되지 않게 할 것.

**제품 결정 연동**: docs/14 §G #6(⚠게이트오프) '레거시 캐릭터 일러 트랙(ModelsLab CG) 동결·신규 노출 중단'과 **부분 교차**한다. 다만 여기서 트리거되는 것은 극장 자동 노트의 씬 일러 경로(TheaterAutoNoteService.triggerIllustration)이지 승급 자동 CG가 아니므로 §G #6으로 소멸하지 않는다고 판단(§G #6이 죽이는 것은 '승급 자동 CG 트리거'와 신규 캐릭터 CG 노출). 블록 D 담당자가 '일러 트랙 동결'로 뭉뚱그려 이 경로까지 끄면 극장 연출이 죽으므로 경계 명시 필요.

---

### B-4.d. 극장 분기 ⓓ — 클라이언트 `label`이 다음 배치 LLM 프롬프트에 그대로 주입

**🔴 잔존** · P1 · SMALL · BE  
`aichat/src/main/java/com/spring/aichat/service/theater/TheaterBranchService.java:360-365 → service/theater/TheaterService.java:104-113`

**근거**

```java
360:        String newBranchContext = String.format(
361:            "유저가 '%s' 선택함 (%s, %s). %s",
362:            chosen.label(), level.name(), chosen.tone(),
363:            contextSummary != null ? contextSummary : ""
364:        );
365:        batchCache.putBranchContext(roomId, "active", newBranchContext);
```
`chosen.label()`·`chosen.tone()` 모두 클라이언트 스냅샷 값이고 길이 제한·이스케이프·인젝션 검사가 없다. 소비 지점은 다음 배치 생성이다 — TheaterService.java:104 `String branchContext = batchCache.consumeBranchContext(roomId, "active").orElse(null);` → :112-113 `new TheaterBatchGenerator.GenerateParams(room, state, hintedHeroineId, branchContext, false, justBranched)` → 배치 프롬프트에 삽입.
대조: 일반 채팅 입력은 PromptInjectionGuard(ChatStreamServiceV2.java:203-211 `injectionGuard.checkChatMessage(...)`)와 ContentModerationService(:180-189)를 거치는데, **분기 label 경로에는 두 가드가 모두 없다**. 즉 모더레이션·인젝션 검사를 우회해 임의 지시문을 LLM 컨텍스트에 넣는 뒷문이다(시크릿 수위 강제, 시스템 프롬프트 무력화 시도 등).
또한 :333 `optionsJson = objectMapper.writeValueAsString(optionsSnapshot);`로 조작된 스냅샷 전문이 DB(theater_branch_choices.options_json)에 영속된다.

**수정안**

B-4.a의 Redis 원본 재판정이 근본 해소(label도 서버 원본에서 가져오게 됨). 그 전 단독 완화로는 :360-364에서 label/tone을 (1) 길이 절단(예: 60자), (2) 개행·백틱·대괄호 등 프롬프트 구조 문자 제거, (3) tone은 허용 enum 집합(normal/affection/bold/witty/introspective)으로 화이트리스트 검증 후 사용. 아울러 이 경로에도 `injectionGuard.checkChatMessage(label, username)`를 태워 탐지 이벤트를 moderationEventService에 남길 것(현재 극장 분기는 모더레이션 로그에 아무 흔적도 남지 않는다).

**제품 결정 연동**: docs/16이 시크릿 수위를 '텍스트 풀노글'로 넓혔지만 그것은 **인가된 성인·시크릿 활성 세션 한정**이다. 이 주입 경로는 시크릿 미활성/미성년 페르소나 세션에서도 프롬프트를 밀어넣을 수 있어 블록 B의 나이 하드게이트(impl_spec_details §2 '이중 방어')를 측면 우회할 소지가 있다 — 나이 게이트를 '절대선'으로 규정한 만큼 이 항목은 컴플라이언스 관점에서 P1 이상으로 다뤄야 한다.

---

### B-4.e. 극장 분기 (신규 관찰) — 분기가 실제로 제시됐는지 검증 없음: 임의 시점·임의 레벨 분기 확정 + LOCATION 가드 우회

**🔴 잔존** · P1 · MEDIUM · BE  
`aichat/src/main/java/com/spring/aichat/service/theater/TheaterBranchService.java:306-330 · controller/TheaterBranchController.java:81-99`

**근거**

applyBranchChoice(:306-330)는 branchToken을 **검증하지 않는다** — :327-329 `if (branchToken != null) { contextSummary = batchCache.consumeBranchContext(roomId, branchToken).orElse(null); }` 로 있으면 쓰고 없으면 null로 진행할 뿐, 토큰 부재/위조를 거부하지 않는다. level도 컨트롤러에서 폴백된다(TheaterBranchController.java:87-92 `catch (Exception e) { lvl = BranchLevel.MINOR; }`).
결과 1: 서버가 분기를 제시한 적이 없어도 `POST /branches/choose`만으로 분기를 '확정'할 수 있다. generateLocationBranch(:60-71)는 `affections.size() < 2`면 예외를 던지지만, applyBranchChoice에는 그 검사가 없어 **싱글 히로인 세션에서도 LOCATION 확정이 가능**하다.
결과 2(연쇄): 위조 LOCATION 확정이 DB에 기록되면(:337-343 branchChoiceRepository.save) TheaterService.requestNextBatch의 LOCATION 선행 가드가 풀린다 — TheaterService.java:77-79 `&& !branchChoiceRepository.existsByRoom_IdAndActNumberAndChapterNumberAndBranchLevel(roomId, act, chapter, BranchLevel.LOCATION)`. 즉 분기 UI를 거치지 않고 배치 생성을 강제할 수 있고, 동시에 B-4.c로 화자를 임의 지정할 수 있다.
또한 MAJOR 1회 제한 상태 플래그(TheaterState.majorBranchDoneInChapter)를 applyBranchChoice가 갱신하지 않아 같은 chapter에서 MAJOR를 반복 확정할 수 있다(무과금 B-4.b와 결합 시 분기 보상만 반복 수확).

**수정안**

B-4.a의 Redis 원본 저장 설계에 흡수하는 것이 가장 깔끔하다: branchToken을 **필수**로 만들고(null이면 BadRequest), Redis에서 `{level, options[]}` 원본을 consume해 (1) 토큰 존재 = 서버가 그 분기를 제시했다는 증거, (2) 저장된 level로 클라 level 무시(컨트롤러의 MINOR 폴백 제거), (3) consume이므로 재사용 불가(리플레이 차단)를 한꺼번에 확보한다. generateBranchToken(:397-399)은 UUID로 교체. 추가로 LOCATION 확정 시 `affectionRepository.findByRoom_Id(roomId).size() >= 2` 검사와 MAJOR 확정 시 `state.majorBranchDoneInChapter` 갱신/중복 거부를 applyBranchChoice에 넣을 것.

**제품 결정 연동**: docs/14 §C #6 '극장 유지'로 게이트 오프 대상 아님. §G #13 '골격 유지'와도 충돌 없음(골격을 바꾸는 게 아니라 확정 경로에 증거 검증을 추가하는 것).

---

### B-4.f. 극장 분기 (신규 관찰) — `optionsSnapshot` 누락 시 NPE 500

**🔴 잔존** · P3 · ONE_LINE · BE  
`aichat/src/main/java/com/spring/aichat/service/theater/TheaterBranchService.java:311`

**근거**

TheaterBranchController.java:83-85 `@RequestBody ConfirmBranchRequest request` — `@Valid` 없음, record 필드에도 제약 애너테이션 없음(:74-79). 본문에서 optionsSnapshot을 생략하면 null이 그대로 서비스로 전달되고 TheaterBranchService.java:311 `if (chosenIndex < 0 || chosenIndex >= optionsSnapshot.size())`에서 NullPointerException → 500.
(대조: 같은 메서드의 level은 컨트롤러 :87-92 try/catch가 NPE까지 삼켜 MINOR로 폴백하므로 안전하다. optionsSnapshot만 무방비.)

**수정안**

B-4.a/e 재설계로 optionsSnapshot 필드 자체가 사라지면 소멸한다. 재설계 전 임시로는 TheaterBranchController.java:83에 `@Valid`를 붙이고 record 필드에 `@NotNull List<BranchOption> optionsSnapshot`(또는 서비스 :311 앞에 `if (optionsSnapshot == null || optionsSnapshot.isEmpty()) throw new BadRequestException(...)`) 처리.

---

### B-5.1. 극장 `next-batch`의 prefetch 플래그가 클라이언트 제어 — 전 배치 무과금으로 본편 완주

**🔴 잔존** · P0 · ONE_LINE · BE/FE  
`aichat/src/main/java/com/spring/aichat/controller/TheaterController.java:43-52 · service/theater/TheaterService.java:88-98`

**근거**

TheaterController.java:50-51:
```java
boolean prefetch = request != null && request.prefetch();
return theaterService.requestNextBatch(roomId, authentication.getName(), prefetch);
```
TheaterService.requestNextBatch의 과금 2지점이 모두 이 플래그에 걸려 있다:
```java
 88:        if (cached.isPresent()) {
 91:            if (!prefetch) chargeBatchEnergy(username);
 92:            return cached.get();
 ...
 98:        if (!prefetch) chargeBatchEnergy(username);
```
chargeBatchEnergy(:409-414)는 `ChatMode.THEATER.getBaseCost()` 1E 차감. prefetch=true여도 **반환값은 유료 호출과 동일한 SceneBatch 전문**이다(:92 `return cached.get();`, :115-117 `SceneBatch batch = batchGenerator.generateNextBatch(params); ... return batch;`) — 과금 여부를 나타내는 워터마크가 응답에 없다.
후속 소비도 막히지 않는다 — onBatchConsumed(:177-188)는 `consumedBatchId != state.getCurrentBatchId()`만 검사하고 과금 이력을 보지 않아 호감도·스탯·분기·엔딩이 정상 진행된다. 즉 에너지 0 계정으로 Act 1~4 완주 가능.
도달성: TheaterController.java:44 `@PreAuthorize checkRoomOwnership` — 자기 극장 방 소유자면 누구나.

**수정안**

**FE 무영향 확인 완료** — LucidChat-Front/src/api/TheaterPlayApi.js:18-19가 유일한 래퍼이고 실제 호출부는 hooks/useTheaterStream.js:61 `const batch = await requestNextBatch(roomId, false);` 한 곳뿐(`grep -rn "requestNextBatch("` 전수 확인). 선행 생성은 이미 전용 엔드포인트 `POST /theater/rooms/{roomId}/prefetch`(TheaterController.java:90-97 → prefetchNextBatchAsync, 응답 202로 **본문을 돌려주지 않고** currentBatchId+1만 캐시 워밍)가 담당한다.
따라서 픽스: TheaterController.java:50의 `boolean prefetch = request != null && request.prefetch();`를 삭제하고 :51을 `theaterService.requestNextBatch(roomId, authentication.getName(), false)`로 고정(NextBatchRequest DTO와 TheaterService의 prefetch 파라미터도 정리). FE는 TheaterPlayApi.js:18-19의 prefetch 인자만 제거하면 되고 동작 변화 없음.

**제품 결정 연동**: docs/14 §C #6 '극장 유지'라 게이트 오프 대상 아님 — 반드시 수리. impl_spec_details §6(재작업 금지 목록)에도 이 트레이드오프는 없다(등재된 것은 씬당 1회 심의 발제, 리플레이 언마운트, V2 scenesJson 사문 3건뿐) → '의도된 수용'이 아님을 확인.

---

### B-5.2. `batch-consumed`가 과금 여부를 확인하지 않음 — 무과금 배치로도 진행·보상이 확정

**🔴 잔존** · P1 · SMALL · BE  
`aichat/src/main/java/com/spring/aichat/service/theater/TheaterService.java:177-188 · controller/TheaterController.java:62-71`

**근거**

```java
177:    public boolean onBatchConsumed(Long roomId, String username, int consumedBatchId) {
178:        ChatRoom room = getOwnedRoom(roomId, username);
179:        TheaterState state = getState(roomId);
180:
181:        if (consumedBatchId != state.getCurrentBatchId()) {
...
186:            throw new BusinessException(ErrorCode.STALE_CLIENT_STATE, ...);
```
검사는 batchId 일치 하나뿐이다. 해당 배치에 대해 에너지가 실제로 차감됐는지를 판정할 상태가 애초에 없다 — 과금은 chargeBatchEnergy(:409-414)에서 User 엔티티를 깎을 뿐 배치 단위 '결제 완료' 표식을 남기지 않는다(SceneBatch/TheaterState 어디에도 paidBatchId류 필드 없음).
결과: B-5.1로 무과금 취득한 배치도 소비 신호를 보내면 호감도 영속·스탯 반영·chapter 진행이 정상 수행된다(:189 이후 진행 로직). B-5.1을 막아도 이 구멍은 남으므로(향후 다른 무과금 경로가 생기면 재현) 별도 원자로 분리한다.

**수정안**

chargeBatchEnergy 성공 시 '과금 완료 배치' 워터마크를 남긴다 — TheaterBatchCacheService에 `markBatchPaid(roomId, batchId)`/`isBatchPaid(roomId, batchId)`(Redis, TTL=세션 유효기간)를 추가하고 TheaterService:91·98의 차감 직후 마킹. onBatchConsumed(:181 앞)에 `if (!batchCache.isBatchPaid(roomId, consumedBatchId)) throw new BusinessException(ErrorCode.BAD_REQUEST, ...)` 를 넣어 fail-closed. B-5.1(플래그 제거)이 선행되면 이 항목은 심층 방어 성격이므로 같은 배치에서 함께 처리하되 우선순위는 5.1 다음.

**제품 결정 연동**: none (극장 유지, 블록 D 무관)

**❓ 결정 필요**: 과금 워터마크를 Redis에 둘지 TheaterState 컬럼(lastPaidBatchId)으로 영속할지 — Redis 유실 시 정상 유저가 소비 신호를 거부당하는 UX 리스크가 있어 정책 선택이 필요하다(추천: TheaterState 컬럼 영속 → DB_MIGRATION 동반. 관대 모드는 착취면이 그대로 남으므로 지양).

---

### B-6.1. 극장 스탯 리롤이 어떤 재화도 차감하지 않음 — 리롤권 상품·인벤토리가 백엔드에 부재

**🔴 잔존** · P1 · SMALL · BE  
`aichat/src/main/java/com/spring/aichat/service/theater/TheaterLobbyService.java:510-533 · controller/TheaterLobbyController.java:165-181`

**근거**

컨트롤러 주석은 유료를 전제한다 — TheaterLobbyController.java:165-167 `// ━━━ 스탯 리롤 (유료 아이템 사용 전제) ━━━`, :169 `@PostMapping("/rooms/{roomId}/reroll")`.
그러나 서비스 본문(TheaterLobbyService.java:510-533)에는 차감이 없다:
```java
510:    public void rerollStats(Long roomId, String username, InitialStatDistribution newDistribution) {
511:        User user = findUser(username);
512:        verifyRoomOwnership(roomId, username);
513:
514:        validateInitialStats(user, newDistribution);
...
526:        state.applyStatChange(AvatarStat.CHARM, newDistribution.charm() - state.getStatCharm());
...
532:        log.info("🎭 [THEATER] Stats rerolled | roomId={} | new={}", roomId, newDistribution);
```
`user`는 :511에서 조회만 하고 consumeEnergy도 아이템 소모도 호출하지 않는다(`grep -n "consumeEnergy" TheaterLobbyService.java` → 0건).
리롤권 상품·인벤토리 부재도 재확인: ProductType.java:19-32 전 7종(ENERGY_T1/T2/T3, SECRET_PASS_24H, SECRET_UNLOCK_PERMANENT, LUCID_PASS, LUCID_MIDNIGHT_PASS)에 리롤 아이템 없음. `grep -rn -i "reroll" src` 히트는 전부 UGC 파이프라인(golden/base/emotion/world reroll — 이쪽은 CharacterCreationService.java:250·395·432, UgcWorldService.java:277·475에서 정상 차감)과 이 극장 리롤뿐이며, 인벤토리/소모품 엔티티는 존재하지 않는다.

**수정안**

제품 결정(openQuestion) 확정 후 택1. (a) 에너지 과금이면 TheaterLobbyService.rerollStats :514 검증 통과 직후 `user.consumeEnergy(THEATER_REROLL_COST)` 추가 + 비용을 application.yml 노브(`theater.reroll-cost: ${THEATER_REROLL_COST:3}`)로 노출(UGC 파이프라인이 UgcPipelineProperties로 하는 방식과 동일 관례) + 성공 후 `cacheService.evictUserProfile(username)`. (b) 유료 아이템이면 ProductType 신설 + 인벤토리 테이블(DB_MIGRATION) + deliverProduct 분기까지 세트라 규모가 커진다. (c) 기능 제거면 컨트롤러 :169-181 + 서비스 :510-533 + FE 진입점 삭제.
어느 안이든 :165-167의 '유료 아이템 사용 전제' 주석은 실제 구현과 일치시킬 것 — 현재 주석이 감사 시 오해를 부른다.

**제품 결정 연동**: docs/14 §C BM 항목(UGC 25E·부스트 3.6-flash 치환·페르소나 슬롯 무료 3/구독 10)과 impl_spec_details §4에 **리롤권은 등장하지 않는다** — 즉 블록 C BM 설계에서 누락된 미결 상품이다. 블록 D 게이트 오프 대상도 아니다(§G 21건에 리롤 없음, 극장은 §C #6에서 유지 확정). 따라서 '고칠 필요 없어지는' 항목이 아니라 '제품 결정이 비어 있어 고치는 방식이 미정'인 항목.

**❓ 결정 필요**: 극장 스탯 리롤의 과금 모델을 확정해야 한다: (a) 에너지 N 차감(가장 싼 구현, 기존 관례 일치), (b) 별도 '리롤권' 상품 신설(ProductType+인벤토리, BM 확장이지만 구현 규모 큼), (c) 기능 자체 제거. 참고로 리롤은 캐릭터 생성 UGC에서 이미 2E 관례가 있어 (a)가 정합적이다.

---

### B-6.2. 극장 스탯 리롤 횟수 제한 부재 — 세션 초반 stat_gate가 사실상 무력

**🔴 잔존** · P2 · SMALL · BE/DB_MIGRATION  
`aichat/src/main/java/com/spring/aichat/service/theater/TheaterLobbyService.java:519-530`

**근거**

```java
519:        // 리롤은 세션 초반(Act 1, 총 씬 50 미만)에만 허용
520:        if (state.getTotalSceneCount() >= 50) {
521:            throw new BadRequestException("리롤권은 세션 초반에만 사용할 수 있습니다. ...");
522:        }
523:
524:        // 리롤은 덮어쓰기 (증가분이 아닌 재분배)
525:        state.applyStatChange(AvatarStat.CHARM, newDistribution.charm() - state.getStatCharm());
```
제한은 '누적 씬 50 미만'이라는 **구간 제한뿐이고 횟수 카운터가 없다**(TheaterState에 rerollCount류 필드 없음). 그 구간 안에서는 호출 횟수 무제한이다.
분기의 stat_gate는 min_value 30~70으로 생성되고(TheaterBranchService.java:218 프롬프트 규칙 `stat_gate의 min_value는 30~70 사이`) 잠금 판정은 `state.getStat(stat) >= minValue`(:271)다. 총점 상한은 티어별로 validateInitialStats(:627-662)가 강제하는데 LUCID_MIDNIGHT_PASS는 PREMIUM_TOTAL_POINTS/PREMIUM_PER_STAT_MAX(40/20). 40점을 게이트가 요구하는 축에 몰아주는 재분배를 분기마다 반복하면 초반 50씬 구간의 stat_gate가 전부 통과 가능해진다.
(B-4.a로 unlocked를 위조하는 것보다 은밀하고, B-6.1이 무과금이라 비용도 0이다.)

**수정안**

TheaterState에 `rerollCount` 컬럼(default 0)을 추가(Flyway **V31** 일러·극장 묶음 — 2026-08-26 정정 · D-33: 앞을 V26·V27 블록 D / V28 결제 / V29 구독 / V30 에너지 분할이 점유. ⚠ 극장 스키마는 결제와 **롤백 단위가 다르니 같은 파일에 묶지 말 것**)하고 TheaterLobbyService.rerollStats :520 가드 뒤에 `if (state.getRerollCount() >= MAX_REROLLS) throw new BadRequestException(...)` + 성공 시 증가. 비용 정책(B-6.1)이 (a) 에너지 과금으로 확정되면 횟수 제한 없이 과금만으로도 자연 억제되므로 MAX_REROLLS는 넉넉히(예: 3~5) 잡거나 생략 가능 — **B-6.1 결정 후에 착수할 것**(중복 설계 방지).

**제품 결정 연동**: §C #6 '극장 유지'로 게이트 오프 대상 아님. 다만 §G #10 '8축 스탯 — 유지하되 소프트캡화(만렙=엔딩 결합 해제)'는 **자유/스토리의 8축**에 대한 재해석이고, 극장 아바타 5축은 impl_spec_details §1 '극장 무변경 원칙'에 따라 별개다 — 블록 D/B 담당자가 두 스탯 체계를 혼동해 극장 리롤·게이트를 건드리지 않도록 경계 필요.

---

### B-7. 업적을 코드 문자열만으로 즉시 해금 — 서버가 달성 조건을 전혀 검증하지 않음

**🔴 잔존** · P3 · MEDIUM · BE  
`aichat/src/main/java/com/spring/aichat/service/AchievementService.java:143-155 · controller/AchievementController.java:57-63`

**근거**

```java
143:    public UnlockNotification unlockClientTriggered(Long userId, String easterEggCode) {
144:        try {
145:            EasterEggType eggType = EasterEggType.valueOf(easterEggCode);
146:            if (eggType.isLlmTriggered()) {
147:                log.warn("🏆 [ACHIEVEMENT] Attempted client unlock of LLM-triggered egg: {}", easterEggCode);
148:                return null;
149:            }
150:            return unlockEasterEgg(userId, eggType);
```
유일한 방어는 :146의 `isLlmTriggered()` 여부이고, **달성 조건 검증은 전무**하다. 컨트롤러도 코드 문자열을 그대로 넘긴다 — AchievementController.java:59-61 `public UnlockNotification unlockClientTriggered(@PathVariable Long roomId, @RequestBody UnlockRequest request) { Long userId = authGuard.getCurrentUserId(roomId); return achievementService.unlockClientTriggered(userId, request.code()); }`, :64 `public record UnlockRequest(String code) {}`.
**심각도 재평가 근거(docs/13의 P0-A → P3로 하향)**: (1) 착취 범위가 1종뿐이다 — EasterEggType.java 전 5종 중 CLIENT_TRIGGERED는 :31-33 `INVISIBLE_MAN("The Watcher", "투명인간", ..., false)` 하나이고 나머지 4종(STOCKHOLM/DRUNK/FOURTH_WALL/MACHINE_REBELLION)은 llmTriggered=true라 :146에서 차단된다. (2) 보상이 없다 — unlockEasterEgg(:61-83)는 Achievement 행 저장과 알림 반환뿐, 에너지·재화·권한 지급이 전혀 없다(`grep -n "chargePaidEnergy|consumeEnergy|activate" AchievementService.java` → 0건). (3) IDOR도 아니다 — 컨트롤러 :58 `@PreAuthorize("@authGuard.checkRoomOwnership(#roomId, principal.subject)")`로 본인 방 한정.
즉 현재 실피해는 '10분 대기 없이 갤러리 카운트 +1'이라는 자기만족 수준의 무결성 훼손이다. 정상 트리거는 FE hooks/useInvisibleMan.js:49 `api.post('/achievements/rooms/${roomId}/unlock', { code: "INVISIBLE_MAN" })`.

**수정안**

**권장: 별도 픽스하지 말고 블록 D 업적 게이트 오프에 흡수**(아래 productDecisionRisk 참조). 게이트 오프가 지연되거나 업적을 존치하기로 뒤집힐 경우에만 다음을 적용: (1) 즉시 완화 — AchievementService.unlockClientTriggered(:143)를 검증기 화이트리스트 기반 fail-closed로 전환(`Map<EasterEggType, Predicate<UnlockContext>> VERIFIERS`에 없으면 :150 대신 null 반환 + WARN). (2) INVISIBLE_MAN 검증기 구현 — 조건 증거(방별 마지막 유저 발화 시각)를 서버가 이미 보유하고 있어야 하는데 현재 ChatRoom에는 lastActiveAt만 있고 '유저가 10분간 입력 없이 관전했다'는 근거가 없다. ChatRoom에 lastUserMessageAt을 두거나 chatLogRepository의 최신 user role 로그 시각으로 판정(`now - lastUserLogAt >= 10min`)하도록 서비스에서 계산. (3) 엔드포인트에 레이트리밋 부여.

**제품 결정 연동**: **직접 충돌 — 별도 수정 불필요 판정.** docs/14 §C #6이 명시한다: '이스터에그 연출 유지 + 업적(지급·갤러리·해금 모달)만 게이트 오프' 그리고 같은 문장에 '부수효과: docs/13 P0 중 「엔딩 무제한 재생성」·「업적 자가해금」 착취면 소멸'. 즉 종원이 이미 이 결함을 게이트 오프로 소멸시키기로 결정했다. impl_spec_details §5도 '엔딩·업적은 게이트 오프=코드 보존이 의도 — 삭제는 §G의 🔴 목록만'이라 코드는 남기고 플래그로 끄는 방식이다. 다만 **현재 코드에는 그 게이트 플래그가 아직 없다**(`grep -rn -i "achievement|ending|gate" src/main/resources/application.yml` → 0건, 블록 D 미착수). 따라서 지금 이 순간은 STILL_PRESENT이며, 픽스 방식은 '조건 검증기 구현'이 아니라 '블록 D 게이트 플래그로 엔드포인트 자체를 닫기'가 정답이다 — 검증기를 만들면 게이트 오프와 함께 버려지는 낭비 작업이 된다.

**❓ 결정 필요**: 블록 D(업적 게이트 오프) 착수가 늦어질 경우, 그 전까지 `/achievements/rooms/{roomId}/unlock`을 임시로 닫아둘지(실피해가 갤러리 카운트뿐이라 방치도 선택지) 종원 판단 필요. 닫으면 FE useInvisibleMan.js의 정상 연출도 함께 죽는데, §C #6이 '이스터에그 연출은 유지'라 했으므로 연출(모달·씬 반응)과 업적 기록을 분리할지 여부도 같은 결정에 포함된다.

---

### B-8.1. 엔딩 종류를 요청 바디로 클라이언트가 지정 — 서버 대조 전무, 호감도 조건 없이 HAPPY 확정 가능

**🔴 잔존** · P0 · MEDIUM · BE/FE  
`src/main/java/com/spring/aichat/controller/EndingController.java:30-36`

**근거**

EndingController.java:28-38 현재 그대로:
```java
@PostMapping("/generate")
@PreAuthorize("@authGuard.checkRoomOwnership(#roomId, principal.subject)")
public EndingResponse generateEnding(
    @PathVariable Long roomId,
    @RequestBody GenerateEndingRequest request
) {
    EndingType type = EndingType.valueOf(request.endingType().toUpperCase());
    return endingService.generateEnding(roomId, type);
}
public record GenerateEndingRequest(String endingType) {}
```
EndingService.generateEnding(roomId, endingType)(EndingService.java:65) 전체를 읽었으나 endingType을 room 상태와 대조하는 코드가 단 한 줄도 없다. 유일한 가드는 방 소유권뿐이고, AuthGuard.checkRoomOwnership(AuthGuard.java:37-58)은 roomId→username 매핑만 본다.

중요: V1 SANDBOX는 서버가 엔딩 타입을 아예 영속하지 않는다. ChatRoom.checkEndingTrigger()(ChatRoom.java:729-735)는 스탯을 읽어 문자열만 반환할 뿐 markEndingReached를 호출하지 않고, markEndingReached의 호출처는 EndingEligibilityService.java:112(V2 STORY 전용)와 EndingService.java:149(바로 이 착취 경로)뿐이다. 즉 SANDBOX 방에서는 **바디에 적힌 값이 곧 정답**이 된다. 스탯 0인 갓 생성한 방에 {"endingType":"HAPPY"}를 던지면 그대로 HAPPY 엔딩이 확정된다.

**수정안**

EndingController.generateEnding에서 @RequestBody를 제거하고 record GenerateEndingRequest도 삭제한다. EndingService에 `private EndingType resolveEndingType(ChatRoom room)`를 신설해 모드별로 서버가 판정:
- STORY(V2): room.isEndingReached()가 true여야 하고 room.getEndingType()을 그대로 사용. false면 BadRequestException("엔딩에 도달하지 않았습니다").
- SANDBOX(V1): room.getMaxNormalStatValue() >= 100 → HAPPY, room.getMinNormalStatValue() <= -100 → BAD, 둘 다 아니고 endingReached도 false면 400. (ChatRoom.checkEndingTrigger()는 endingReached면 null을 반환하도록 설계돼 재사용 불가 — 별도 resolver가 필요하다.)
- THEATER: 무조건 400 (B-8.5 참조).
EndingService.generateEnding 시그니처를 (Long roomId)로 바꾸고 내부에서 resolveEndingType을 호출한다. FE는 ChatPage.jsx:1298 / ChatPageV2.jsx:1603의 `api.post(..., { endingType })`에서 바디만 빼면 되고, 응답의 endingType으로 BGM을 결정하도록 바꾼다(현재는 요청값 기준).

**제품 결정 연동**: 블록 D(docs/14 §C#6)가 '엔딩=자유·스토리 게이트 오프'를 넣으면 이 착취면은 소멸한다고 docs/14가 명시했다. 단 그 소멸은 **서버 측 차단일 때만** 성립한다. yml 플래그를 EndingController 진입부에서 검사하지 않고 FE 진입점만 지우면 API는 그대로 열려 있다(docs/14_assets §5가 beta-activate에 대해 남긴 것과 동일한 함정). 또한 블록 D는 극장 엔딩을 '유지'하므로 B-8.5의 모드 가드는 게이트 오프와 무관하게 반드시 필요하다.

**❓ 결정 필요**: 블록 D 엔딩 게이트 오프를 (a) yml 플래그로 EndingController 자체를 404/403 처리할지, (b) 엔드포인트는 살리고 FE 진입점만 제거할지. (b)면 이 P0을 별도로 고쳐야 하고, (a)면 B-8.1~B-8.4·B-9.1~B-9.6이 한 번에 닫힌다 — 블록 D 착수 시점이 이 절의 수정 범위를 좌우한다.

---

### B-8.2. 잘못되거나 누락된 endingType이 400이 아닌 500으로 반환

**🔴 잔존** · P3 · ONE_LINE · BE  
`src/main/java/com/spring/aichat/controller/EndingController.java:34`

**근거**

EndingController.java:34 `EndingType type = EndingType.valueOf(request.endingType().toUpperCase());`
- EndingType enum은 HAPPY/BAD 2값뿐(domain/enums/EndingType.java).
- 잘못된 값 → IllegalArgumentException. `endingType` 필드 누락 → record 컴포넌트가 null → `.toUpperCase()` NPE.
- 두 예외 모두 GlobalExceptionHandler.java:73-79의 최종 폴백에 잡힌다:
```java
@ExceptionHandler(Exception.class)
public ResponseEntity<ApiErrorResponse> handleUnknown(Exception e, HttpServletRequest req) {
    log.error("Unhandled exception occurred: ", e);
    return ResponseEntity.internalServerError()
        .body(ApiErrorResponse.of(500, ErrorCode.INTERNAL_ERROR, "서버 오류가 발생했습니다.", req.getRequestURI()));
}
```
IllegalArgumentException/MethodArgumentTypeMismatch 전용 핸들러는 GlobalExceptionHandler에 없다(핸들러 4개: BusinessException·RateLimitException·MethodArgumentNotValidException·Exception).

**수정안**

B-8.1을 적용해 바디를 제거하면 자동 소멸한다. 바디를 남기는 선택을 한다면 GlobalExceptionHandler에 `@ExceptionHandler({IllegalArgumentException.class, HttpMessageNotReadableException.class})` → 400 매핑을 추가한다. 이 핸들러는 이 절 밖에서도 광범위하게 쓸모가 있다(JwtTokenService.reissue도 IllegalArgumentException을 던져 현재 500이 된다 — AuthController.java:70·109 경로).

**제품 결정 연동**: none — 블록 D 게이트 오프 시 함께 소멸.

---

### B-8.3. 클라이언트가 고른 타입으로 HAPPY_ENDING·BAD_ENDING 업적을 조건 검증 없이 해금 (2종 모두 수집 가능)

**🔴 잔존** · P1 · SMALL · BE  
`src/main/java/com/spring/aichat/service/EndingService.java:175-179 → src/main/java/com/spring/aichat/service/AchievementService.java:89-108`

**근거**

EndingService.java:175-179:
```java
try {
    achievementService.unlockEnding(room.getUser().getId(), endingType.name());
} catch (Exception e) {
    log.warn("🏆 [ACHIEVEMENT] Failed to unlock ending achievement: {}", e.getMessage());
}
```
AchievementService.java:89-101:
```java
public UnlockNotification unlockEnding(Long userId, String endingType) {
    String code = endingType + "_ENDING";
    if (achievementRepository.existsByUserIdAndCode(userId, code)) { ... return null; }
    ...
    Achievement achievement = Achievement.ending(user, endingType);
```
중복 방지는 code 단위로만 동작한다. 즉 같은 방에 대해 {"endingType":"HAPPY"} 1회 + {"endingType":"BAD"} 1회를 호출하면 서로 다른 code라 **HAPPY_ENDING·BAD_ENDING 업적 2종을 모두 획득**한다. 원래 설계상 한 세션은 하나의 엔딩만 볼 수 있어야 한다(EndingEligibilityService 주석: "V2 엔딩은 세션 단위 1회 발동").
이는 docs/13 B-7(AchievementService.unlockClientTriggered)과 별개 경로다 — B-7을 고쳐도 이 경로는 남는다.

**수정안**

B-8.1의 resolveEndingType 적용으로 타입 위조가 막히면 부분 해소된다. 추가로 EndingService.java:176의 unlockEnding 호출을 '이번 호출에서 처음 markEndingReached된 경우'에만 실행하도록 옮긴다 — B-9.1의 멱등성 분기 안쪽(신규 확정 브랜치)으로 이동. AchievementService.unlockEnding에는 roomId 인자를 추가해 '해당 방이 실제로 endingReached이고 endingType이 인자와 일치'를 재확인하는 fail-closed 검증을 넣는다(B-7 수정 방침과 동일 패턴).

**제품 결정 연동**: 블록 D가 '업적(지급·갤러리·해금 모달) 게이트 오프'를 확정했다(docs/14 §C#6). 게이트 오프가 지급 자체를 끄면 이 원자는 소멸한다. 단 docs/14는 '코드 보존'이 의도라고 못박았으므로(14_assets §5) unlockEnding 호출부에 플래그 가드를 넣는 형태가 될 텐데, 그 가드 위치가 AchievementService 안이면 소멸하고 FE 모달 쪽이면 DB에 유령 업적 행이 계속 쌓인다. 게이트는 반드시 AchievementService.unlock* 진입부에 둘 것.

---

### B-8.4. 이미 확정·저장된 ending_type을 사후에 클라이언트가 뒤집을 수 있음 (V2 디렉터 판정 무력화)

**🔴 잔존** · P1 · SMALL · BE  
`src/main/java/com/spring/aichat/service/EndingService.java:146-152`

**근거**

V2 STORY는 서버가 이중 게이트로 타입을 확정해 영속한다 — EndingEligibilityService.java:106-112:
```java
EndingType type = parseEndingType(endingTypeStr);
if (type == null) { ... return false; }
room.markEndingReached(type);
```
그런데 EndingService.java:146-152가 재호출마다 무조건 덮어쓴다:
```java
txTemplate.execute(status -> {
    ChatRoom freshRoom = chatRoomRepository.findById(roomId)
        .orElseThrow(() -> new NotFoundException("Room not found"));
    freshRoom.markEndingReached(endingType);   // ← 클라이언트가 보낸 값
    freshRoom.saveEndingTitle(endingTitle);
    return null;
});
```
ChatRoom.markEndingReached(ChatRoom.java:561-564)에도 '이미 확정됐으면 무시' 가드가 없다:
```java
public void markEndingReached(EndingType endingType) {
    this.endingReached = true;
    this.endingType = endingType;
}
```
결과: 디렉터가 BAD로 확정한 세션에 {"endingType":"HAPPY"}를 한 번 더 던지면 DB의 ending_type이 HAPPY로 바뀐다. 이 값은 LobbyService.java:415·454, StoryV2Service.java:888, RoomSummaryResponse 등 로비·보관함 표시 전반의 소스다.

**수정안**

두 겹으로 막는다. (1) B-8.1 resolveEndingType으로 입력 자체를 제거. (2) 도메인 방어: ChatRoom.markEndingReached에 `if (this.endingReached) return;` 선행 가드를 넣어 최초 1회만 확정되게 한다 — 단 ChatRoom.java:819-821과 1045-1047의 리셋 경로(스토리 초기화/방 리셋)가 endingReached를 false로 되돌리므로 그 경로는 그대로 동작한다.

**제품 결정 연동**: 블록 D의 엔딩 게이트 오프가 서버 측이면 소멸. 다만 markEndingReached의 최초-1회 가드는 극장 외 엔딩이 꺼진 뒤에도 잔존 데이터 정합(로비 카드·보관함 표기)에 유효하므로 도메인 가드는 남겨두는 편이 좋다.

---

### B-8.5. 엔딩 생성 엔드포인트에 chatMode 가드 부재 — 극장 세션 방 ID로 V1 엔딩 파이프라인(LLM 3콜) 강제 실행 가능 (블록 D 게이트 오프 후에도 잔존하는 착취면)

**🔴 잔존** · P1 · ONE_LINE · BE  
`src/main/java/com/spring/aichat/controller/EndingController.java:29 · src/main/java/com/spring/aichat/service/EndingService.java:65-80`

**근거**

극장 세션도 ChatRoom이다 — TheaterLobbyService.java:436:
```java
ChatRoom room = new ChatRoom(user, leadHeroine, ChatMode.THEATER);
```
(character=대표 히로인이 세팅되므로 EndingService의 `room.getCharacter().getName()`(EndingService.java:73-74)도 NPE 없이 통과한다.)

가드는 소유권뿐이다 — EndingController.java:29 `@PreAuthorize("@authGuard.checkRoomOwnership(#roomId, principal.subject)")`, AuthGuard.java:37-58은 chatMode를 보지 않는다. EndingService.generateEnding 전문(:65-189)에도 `getChatMode()` 참조가 0건이다.

따라서 본인 소유 극장 방 ID로 POST /api/v1/ending/rooms/{theaterRoomId}/generate 를 호출하면:
1. transformMemoriesToPoetic(:105→:230) + 씬(:122) + 타이틀(:134) = LLM 3콜 소모
2. ChatRoom.endingReached=true, endingType 세팅(:149) → LobbyService.java:415·454의 방 요약이 '엔딩 완료'로 표시됨 (TheaterState.endingReached와 별개 필드라 극장 UI와 불일치)
3. HAPPY_ENDING/BAD_ENDING 업적 해금(:176)
4. 해당 roomId의 ChatLogDocument에 [ENDING:] SYSTEM 행 적재(:144)
(극장 프롬프트는 TheaterScene 문서를 읽으므로 4번의 컨텍스트 오염은 극장에 전이되지 않는다 — TheaterBatchGenerator.java:518·TheaterHistoryService.java:79가 findTop30ByRoomIdOrderByGlobalSceneSeqDesc를 사용.)

**수정안**

EndingService.generateEnding 진입부(현재 :70의 room 로드 직후)에 모드 가드를 추가:
```java
if (room.getChatMode() == ChatMode.THEATER) {
    throw new BadRequestException("극장 세션은 /api/v1/theater/rooms/{roomId}/ending 을 사용합니다.");
}
```
B-8.1의 resolveEndingType을 구현할 때 THEATER 분기에서 400을 던지는 것으로 통합해도 된다.

**제품 결정 연동**: ★가장 중요★ 블록 D는 '엔딩=자유·스토리만 게이트 오프, 극장 유지'다(docs/14 §C#6). 게이트를 chatMode 기준(SANDBOX/STORY만 차단)으로 구현하면 이 원자는 정확히 그 구멍을 통과한다 — 극장 방 ID로는 계속 호출된다. 게이트 플래그 검사와 무관하게 EndingController 자체가 THEATER를 거부해야 한다. docs/14_assets §5의 beta-activate 교훈("프론트만 지우면 API 착취면이 남는다")과 같은 계열이다.

---

### B-9.1. generateEnding 멱등성 전무 — 재호출마다 LLM 3콜 전체 파이프라인 재실행, 엔딩 결과를 어디에도 영속하지 않음

**🔴 잔존** · P0 · MEDIUM · BE/DB_MIGRATION  
`src/main/java/com/spring/aichat/service/EndingService.java:65-189`

**근거**

EndingService.java:65-71 진입부에 멱등성 분기가 없다:
```java
public EndingResponse generateEnding(Long roomId, EndingType endingType) {
    long totalStart = System.currentTimeMillis();
    log.info("🎬 [ENDING] ====== generateEnding START ====== roomId={} type={}", roomId, endingType);
    ChatRoom room = chatRoomRepository.findWithMemberAndCharacterById(roomId)
        .orElseThrow(() -> new NotFoundException("채팅방이 존재하지 않습니다. roomId=" + roomId));
```
`isEndingReached` 문자열이 EndingService.java 전체에 0건(grep 확인 — 호출처는 ChatService:300, DirectorService:71, LobbyService:415·454, StoryV2Service:888, EndingEligibilityService:63·97뿐).

LLM 3콜 확정:
- :105 transformMemoriesToPoetic → :230 openRouterClient.chatCompletion(sentimentModel)
- :122 openRouterClient.chatCompletion(props.model()) — 엔딩 씬
- :134 openRouterClient.chatCompletion(sentimentModel) — 타이틀

결과 영속 부재: ChatRoom의 엔딩 필드는 endingReached/endingType/endingTitle 3개뿐이다(ChatRoom.java:179-186). EndingResponse가 담는 endingScenes·transformedMemoryList·characterQuote·stats는 **어디에도 저장되지 않는다**(`ending_scenes` grep 0건). 그래서 docs/13이 제안한 "저장 결과 반환"을 그대로 하려면 영속 계층을 새로 만들어야 한다 — ONE_LINE 수정이 아니다.

FE 도달 경로 확정: ChatPageV2.jsx:4494-4497 `{roomInfo?.endingReached && (<button onClick={retryEnding}>🎬 엔딩 다시 보기</button>)}`, retryEnding(:1626-1635) → generateEnding(:1598) → `api.post('/ending/rooms/${roomId}/generate')`. ChatPage.jsx:1321·3608도 동일.

**수정안**

엔딩 결과를 영속하고 재호출 시 그대로 반환한다.
1) 영속 계층: MongoDB가 이미 붙어 있으므로 마이그레이션 없는 `EndingResultDocument`(_id=roomId, endingType, endingTitle, scenesJson, memories, characterQuote, generatedAt) 신설이 가장 싸다. RDB를 고르면 chat_room에 ending_payload JSON 컬럼 추가 → Flyway 마이그레이션 필요(2026-08-26 정정 · D-33 — **V28까지 점유**: V25 블록 B · V26·V27 블록 D · V28 결제. 신규는 **V29부터**). ※ 실제로는 **Mongo(`EndingResultDocument`)로 확정·구현**됐다(`25d0fb0`) — 이 RDB 분기는 미채택.
2) EndingService.generateEnding 진입부(현 :69 '데이터 로드' 직전)에:
```java
var saved = endingResultRepository.findByRoomId(roomId).orElse(null);
if (saved != null) return saved.toResponse();   // LLM 0콜
```
3) 정상 생성 끝(:181 return 직전)에 저장. 저장은 :146 txTemplate 블록과 같은 트랜잭션 경계에 넣어 markEndingReached와 원자적으로 커밋되게 한다.
4) 통계(collectStats, :319)는 시점 의존이므로 저장 스냅샷을 쓸지 매번 재집계할지 결정 — 재집계는 DB 조회만이라 LLM 비용이 없으니 재집계 유지가 무난하다.

**제품 결정 연동**: 블록 D 게이트 오프가 서버 측이면 자유·스토리 경로는 소멸한다. 하지만 (a) B-8.5의 극장 방 우회가 남고, (b) 극장 엔딩은 '유지'인데 극장도 결과를 영속하지 않아 같은 영속 계층이 필요하다(B-9.9). 즉 이 영속 작업은 블록 D 이후에도 버려지지 않는다 — 오히려 극장 다시 보기를 살리려면 반드시 해야 한다. EndingResultDocument를 V1/V2/극장 공용으로 설계할 것.

**❓ 결정 필요**: 엔딩 결과 영속 위치를 Mongo(마이그레이션 없음, 기존 ChatLogDocument와 같은 스토어)로 갈지 RDB(트랜잭션 원자성 유리)로 갈지 — 종원의 운영 취향 판단. → **결정 완료(2026-08-21): Mongo(`EndingResultDocument`)로 확정·구현**(`25d0fb0`, docs/19 §B). 마이그레이션 번호 조율은 불요 — 이 안건은 소멸했다.

---

### B-9.2. 엔딩 생성에 에너지·재화 차감이 전혀 없음 — LLM 3콜이 완전 무과금

**🔴 잔존** · P1 · SMALL · BE  
`src/main/java/com/spring/aichat/service/EndingService.java:49-60`

**근거**

EndingService의 의존성 전체(EndingService.java:51-60): chatRoomRepository, chatLogRepository, endingPromptAssembler, openRouterClient, props, objectMapper, memoryService, txTemplate, achievementService, secretModeService. **EnergyService·UserService·결제 계열 의존성이 하나도 없다.** 파일 전체에 'energy'/'Energy' 문자열 0건.
EndingController.java 전문(39줄)에도 에너지 관련 코드 없음.
대조: 씬 일러는 5E, V1 1E/V2 2E가 명시된 과금 체계가 있고(docs/14 §C#5), IllustrationModal.jsx:12 주석도 '에너지 10 소모'를 명시한다. 엔딩만 예외적으로 무과금이며, 이는 B-9.1(멱등성 부재)과 결합해 무제한 무과금 LLM 호출이 된다.

**수정안**

B-9.1(캐시 반환)을 먼저 넣으면 '최초 1회만 LLM'이 되므로 과금 필요성이 크게 줄어든다. 그 위에 정책이 '엔딩 = 유료'라면 EndingService.generateEnding의 신규 생성 브랜치(캐시 미스 경로)에서만 에너지를 차감하고, 캐시 히트(다시 보기)는 0E로 둔다. 차감 위치는 LLM 호출 전(:114 이전), 실패 시 환불은 docs/13 D-2(실패 경로 환불 부재) 수정과 같은 패턴을 쓸 것.

**제품 결정 연동**: 블록 D가 자유·스토리 엔딩을 끄면 과금 설계 자체가 불필요해진다. 극장 엔딩만 남는데 극장 엔딩은 '극 완주 보상'이라 무과금이 자연스럽다. 즉 이 원자는 '고치지 않는다'가 정답일 가능성이 높다 — B-9.1만 넣어 반복 호출 비용을 0으로 만들면 충분하다.

**❓ 결정 필요**: 엔딩 생성을 유료(에너지 차감)로 할 것인가, 아니면 B-9.1 캐시만으로 비용을 봉쇄하고 무과금을 유지할 것인가. 블록 D로 자유·스토리 엔딩이 꺼지면 이 질문 자체가 사라진다.

---

### B-9.3. 엔딩 생성 엔드포인트에 레이트리밋 미적용 — LLM 3콜짜리 요청을 무제한 연타 가능

**🔴 잔존** · P1 · SMALL · BE  
`src/main/java/com/spring/aichat/controller/EndingController.java:18-36`

**근거**

EndingController.java 전문(39줄)에 ApiRateLimiter 의존성이 없다 — 필드는 `private final EndingService endingService;` 하나뿐(:20).
ApiRateLimiter의 편의 메서드 목록(ApiRateLimiter.java:112-172)에도 엔딩 버킷이 없다: chat_send(1/3s), chat_init(1/5s), event_trigger(1/3s), payment(2/5s), profile_update(3/5s), login(5/60s), signup(3/60s), ugc_mutation(2/5s), world_mutation(2/5s).
전역 필터도 커버하지 않는다 — GuestBrowseRateLimitFilter는 GET + 비인증 + GUEST_BROWSE_PREFIXES 한정이다(GuestBrowseRateLimitFilter.java:68-71: `if (!"GET".equalsIgnoreCase(request.getMethod())) return true; if (isAuthenticated()) return true;`).
비교: 같은 LLM 과금면인 chat_send는 3초에 1회로 묶여 있는데(ApiRateLimiter.java:112-114 "가장 엄격 — LLM 과금 폭탄 핵심 방어선"), 3콜을 쓰는 엔딩은 무제한이다.

**수정안**

ApiRateLimiter에 `public boolean checkEndingGenerate(String username) { return isRateLimited("ending_generate", username, 1, 30); }`를 추가하고, EndingController에 ApiRateLimiter + Authentication을 주입해 generateEnding 진입부에서 검사 후 RateLimitException(60)을 던진다. B-9.1 캐시 반환이 들어가면 정상 유저는 캐시 히트라 30초 창에 걸릴 일이 없다. TheaterFinalityController.triggerEnding(:50-56)에도 같은 버킷을 붙일 것.

**제품 결정 연동**: 블록 D 게이트 오프가 서버 측이면 자유·스토리 경로는 소멸하나, 극장 엔딩(TheaterFinalityController)은 유지되므로 레이트리밋 자체는 극장 쪽으로 이관해 살려야 한다.

---

### B-9.4. saveEndingTitle이 재호출마다 무조건 덮어써 최초 엔딩 제목이 영구 소실

**🔴 잔존** · P1 · ONE_LINE · BE  
`src/main/java/com/spring/aichat/service/EndingService.java:150 · src/main/java/com/spring/aichat/domain/chat/ChatRoom.java:566-568`

**근거**

EndingService.java:146-152:
```java
txTemplate.execute(status -> {
    ChatRoom freshRoom = chatRoomRepository.findById(roomId).orElseThrow(...);
    freshRoom.markEndingReached(endingType);
    freshRoom.saveEndingTitle(endingTitle);   // ← 무조건 덮어쓰기
    return null;
});
```
ChatRoom.java:566-568:
```java
public void saveEndingTitle(String title) {
    this.endingTitle = title;
}
```
최초-1회 가드가 도메인에도 서비스에도 없다. 제목은 LLM이 temperature 0.9로 매번 새로 생성하므로(EndingService.java:134-136, `OpenAiChatRequest.withoutPenalty(props.sentimentModel(), ..., 0.9)` — "penalty 미적용, 창의성 극대화") 재호출마다 다른 문자열이 나온다.
소실 파급: endingTitle은 LobbyDtos.java:54, RoomSummaryResponse.java:41, StoryV2Responses.java:95, ChatRoomInfoResponse.java:29를 통해 로비·보관함 카드에 노출되고 ChatPageV2.jsx:4503의 버튼 라벨에도 쓰인다. 유저가 '다시 보기'를 누르는 순간, 그가 실제로 본 엔딩 제목이 사라지고 새 문구로 바뀐다 — 유저 관점에서는 자기 기록이 조용히 변조되는 것이다.

**수정안**

ChatRoom.saveEndingTitle에 최초-1회 가드를 넣는다:
```java
public void saveEndingTitle(String title) {
    if (this.endingTitle != null && !this.endingTitle.isBlank()) return;
    this.endingTitle = title;
}
```
리셋 경로(ChatRoom.java:821·1047)가 endingTitle=null로 되돌리므로 정상 재플레이는 영향 없다. B-9.1의 캐시 반환이 들어가면 애초에 이 코드에 두 번 도달하지 않지만, 도메인 가드는 방어층으로 남길 것.

**제품 결정 연동**: 블록 D 게이트 오프 후에도 이미 저장된 endingTitle은 로비·보관함에 계속 표시된다(코드 보존 방침). 가드는 그 표시 데이터의 정합을 지키므로 게이트 오프와 무관하게 유효하다.

---

### B-9.5. [ENDING:] SYSTEM 로그가 호출마다 중복 적재돼 이후 디렉터 컨텍스트(top-20 창)를 잠식·오염

**🔴 잔존** · P1 · ONE_LINE · BE  
`src/main/java/com/spring/aichat/service/EndingService.java:143-144`

**근거**

EndingService.java:143-144 — 중복 검사 없이 매번 insert:
```java
String endingNarration = "[ENDING:" + endingType.name() + "] " + endingTitle;
chatLogRepository.save(ChatLogDocument.system(roomId, endingNarration));
```
이 SYSTEM 행은 이후 모든 대화 컨텍스트에 실제로 주입된다:
- V1: ChatStreamService.java:1432-1434 `case SYSTEM -> messages.add(OpenAiMessage.system("[NARRATION] " + chatLog.getRawContent()));`
- V2: ChatStreamServiceV2.java:632 `case SYSTEM -> "system";`
- 엔딩 자체: EndingService.java:271 `case SYSTEM -> messages.add(OpenAiMessage.user("[NARRATION]\n" + chatLog.getRawContent()));`
컨텍스트 창은 top-20 고정이다 — ChatService.java:107, DirectorService.java:319, EndingService.java:108·259 모두 `findTop20ByRoomIdOrderByCreatedAtDesc`. 따라서 '다시 보기' 20회면 최근 창 20칸이 전부 [ENDING:] 행으로 채워져 **실제 대화 맥락이 완전히 밀려난다.** 게다가 각 행의 제목이 서로 달라(B-9.4) LLM에는 '엔딩이 20번 서로 다르게 일어났다'는 모순 서사가 주어진다.

**수정안**

B-9.1의 캐시 반환 분기가 :143 이전에 return하면 근본 해소된다. 방어층으로 :144를 조건부로 감싼다:
```java
if (!room.isEndingReached()) {
    chatLogRepository.save(ChatLogDocument.system(roomId, endingNarration));
}
```
(room은 :70에서 로드된 최초 스냅샷이므로 이번 호출에서 처음 확정되는 경우에만 true.) 기존에 이미 쌓인 중복 행 정리는 별도 일회성 스크립트 판단이 필요하다 — Mongo에서 role=SYSTEM AND rawContent LIKE '[ENDING:%' 인 행을 roomId별 최초 1건만 남기는 정리.

**제품 결정 연동**: none — 게이트 오프 후에도 이미 오염된 방의 로그는 남는다. 정리 스크립트 실행 여부는 오염 방 규모 확인 후 판단.

---

### B-9.6. 프론트 지수 백오프 자동 재시도 3회 — 1클릭당 최대 3요청 × LLM 3콜 = 9콜, 서버 측 억제 없음

**🔴 잔존** · P2 · SMALL · BE/FE  
`FE/src/pages/ChatPageV2.jsx:1598-1623 · ChatPage.jsx:1293-1318`

**근거**

ChatPageV2.jsx:1598-1623 (ChatPage.jsx:1293-1318도 동일 코드):
```javascript
const generateEnding = async (endingType, attempt = 1) => {
  const MAX_RETRIES = 3;
  setEndingLoading(true);
  try {
    const res = await api.post(`/ending/rooms/${roomId}/generate`, { endingType });
    setEndingData(res.data);
  } catch (err) {
    if (attempt < MAX_RETRIES) {
      const delay = 2000 * Math.pow(2, attempt - 1);   // 2s, 4s
      setTimeout(() => generateEnding(endingType, attempt + 1), delay);
      return;
    }
    setEndingLoading(false);
    showToast("엔딩 생성에 실패했습니다. 설정에서 '엔딩 다시 보기'를 시도해 주세요.", "error");
  }
};
```
증폭이 실질적인 이유: EndingService의 실패 지점 대부분은 **LLM 콜을 이미 소비한 뒤**에 온다. parseEndingScenes(:354-369)는 JSON 파싱 실패를 폴백으로 삼켜 실패로 안 치지만, :122 씬 콜이나 :134 타이틀 콜 자체가 타임아웃/5xx면 그 앞의 transformMemoriesToPoetic 콜(:230)은 이미 과금됐다. 3회 재시도 × 최대 3콜 = 9콜이 성립한다. 게다가 최종 실패 토스트가 '다시 보기'를 안내해 수동 재시도까지 유도한다.

**수정안**

서버 측이 본진이다 — B-9.1 캐시 반환 + B-9.3 레이트리밋(1/30s)을 넣으면 재시도가 증폭으로 이어지지 않는다(2번째 요청부터 429 또는 캐시 히트). FE는 보조: ChatPageV2.jsx:1610-1614 / ChatPage.jsx:1305-1309에서 429·4xx는 재시도 대상에서 제외하고(`if (err?.response?.status >= 400 && err.response.status < 500) { ...실패 처리; return; }`) 5xx·네트워크 오류만 백오프하도록 좁힌다.

**제품 결정 연동**: 블록 D가 FE 엔딩 진입점을 제거하면 이 코드 자체가 삭제된다(docs/14 §E 블록 D: '프론트 진입점(로비 재구성에 흡수)'). 단 서버 측 B-9.1·B-9.3은 극장 경로에도 필요하므로 남는다.

---

### B-9.7. V1 SANDBOX 엔딩 트리거가 영속되지 않아, 스탯 100 도달 후 매 턴 자동 일러스트(GPU) 재발주

**🔴 잔존** · P1 · SMALL · BE  
`src/main/java/com/spring/aichat/service/stream/ChatStreamService.java:316-327 · src/main/java/com/spring/aichat/domain/chat/ChatRoom.java:729-735`

**근거**

ChatRoom.java:729-735 — 트리거를 계산만 하고 영속하지 않는다:
```java
public String checkEndingTrigger() {
    requireSandbox();
    if (this.endingReached) return null;
    if (getMaxNormalStatValue() >= 100) return "HAPPY";
    if (getMinNormalStatValue() <= -100) return "BAD";
    return null;
}
```
markEndingReached 호출처 전수(grep): EndingEligibilityService.java:112(V2 STORY 전용)와 EndingService.java:149(=/generate 호출 시)뿐. 즉 SANDBOX에서 endingReached를 true로 만드는 유일한 길은 유저가 /generate를 호출하는 것이다.

ChatStreamService.java:316-327 — 트리거가 살아 있는 동안 매 턴 일러를 발주한다:
```java
EndingTrigger endingTrigger = null;
if (ChatModePolicy.supportsEnding(freshRoom.getChatMode())) {
    String endingCheck = freshRoom.checkEndingTrigger();
    if (endingCheck != null) {
        endingTrigger = new EndingTrigger(endingCheck);
        // ★ [Phase 5.5-Illust] 엔딩 도달 시 자동 일러스트 생성
        illustrationService.generateAutoIllustration(
            freshRoom.getUser().getId(), freshRoom.getCharacter().getId(),
            freshRoom.getId(), "ENDING", null);
    }
}
```
generateAutoIllustration(IllustrationService.java:208-236)에는 중복 방지가 없고 마지막에 실제 submitGeneration(:232)을 호출한다. 무과금이다(에너지 차감 코드 없음).
결과: 스탯 100을 찍고도 엔딩 화면을 열지 않은 채(혹은 크레딧을 닫고) 대화를 계속하면 **매 메시지마다 ModelsLab GPU 잡이 새로 뜬다.** 유저가 착취를 의도하지 않아도 자연 발생하는 상시 유출이다.

**수정안**

SANDBOX도 트리거 발생 시점에 서버가 확정·영속하도록 바꾼다. ChatStreamService.java:319의 `if (endingCheck != null)` 블록 안에서, 일러 발주 **이전에** `freshRoom.markEndingReached(EndingType.valueOf(endingCheck));`를 호출한다(같은 tx 안). 그러면 다음 턴부터 checkEndingTrigger()가 null을 반환해 재발주가 끊긴다. 부수 이득: B-8.1의 SANDBOX 분기가 'room.getEndingType()을 그대로 신뢰'로 단순해져 V1/V2 처리가 통일된다.
주의: markEndingReached를 여기서 부르면 checkEndingTrigger가 endingReached를 보고 null을 반환하므로 같은 턴의 endingTrigger 응답은 이미 위에서 만들어 둔 값을 쓰면 된다(순서만 지키면 됨).

**제품 결정 연동**: 블록 D가 자유(SANDBOX) 엔딩을 게이트 오프하면 ChatStreamService.java:317의 supportsEnding 분기 자체가 꺼져 소멸한다. 다만 §G #6이 '레거시 캐릭터 일러 트랙(ModelsLab CG) 동결·신규 노출 중단'을 확정했으므로, 이 자동 일러 발주는 게이트 오프 전이라도 §G #6 작업에서 함께 제거될 대상이다 — 두 작업이 같은 코드 줄을 건드리니 중복 작업하지 않도록 조율할 것.

---

### B-9.8. [극장 교차확인] 극장 엔딩을 서버·프론트 어디서도 자동 발동하지 않음 — 정상 플레이로는 도달 불가한 죽은 플로우이며, URL 직타로는 언제든 조기 강제 종료 가능

**🔴 잔존** · P1 · MEDIUM · BE/FE  
`src/main/java/com/spring/aichat/service/theater/TheaterEndingService.java:70-77 · src/main/java/com/spring/aichat/service/theater/TheaterService.java:261-271`

**근거**

1) triggerEnding 호출처는 컨트롤러 하나뿐이다(grep 전수): TheaterFinalityController.java:50-56 `@PostMapping("/ending")`. 서버 내부 자동 호출 0건.
2) TheaterDirectorEngine.java에 'ending'/'Ending' 문자열 **0건**. 그런데 TheaterService.java:268-269의 주석은 그 반대를 가정한다:
```java
//   예외: 마지막 Act의 마지막 chapter는 엔딩 직진 — 몰입 끊김 방지.
//         (엔딩 진입 시점엔 directorEngine이 endingReached를 set할 것이고, 그 직전이라
//          한 번 더 stamina를 쥐여줘봤자 엔딩 후엔 의미 없음.)
```
즉 '디렉터 엔진이 세팅할 것'이라는 전제가 구현되지 않은 채 남아 있다.
3) FE도 플레이 중 엔딩으로 이동하지 않는다: TheaterPlayPage.jsx 전체에서 'ending' 매치는 :172 `&& !roomInfo.endingReached`(autoStart 가드) 단 1건이고, `/theater/${...}/ending`으로의 navigate는 TheaterArchivePage.jsx:94(ENDED 카드의 '엔딩 다시 보기' CTA)뿐이다.
4) ENDED 상태를 만드는 유일한 코드는 TheaterEndingService.java:120 `state.markEnded();` — 즉 엔딩이 이미 발동해야만 ENDED가 된다. **닭-달걀 구조로 극장 엔딩은 자연 도달이 불가능하다.**
5) 반대로 triggerEnding에는 진행도 가드가 없다 — :75의 `if (state.isEndingReached())`가 유일한 검사이고 Act/Chapter 완주 여부를 보지 않는다. 유저가 주소창에 /theater/{내 방 id}/ending 을 치면 TheaterEndingCredits.jsx:90-103의 useEffect가 즉시 POST를 날려 Act 1에서도 엔딩이 확정되고 :120 markEnded()로 **resume 불가 상태로 영구 전환**된다(TheaterState.java:369-373 "엔딩 도달 — 영구 완결 상태(resume 불가)").

**수정안**

두 방향을 같이 고친다.
(1) 발동 경로 신설: TheaterService에서 `isLastAct && isLastChapterOfAct`가 성립하는 지점(TheaterService.java:266-272 부근, 현재 leadsToIntermission=false로만 처리되는 분기)에서 theaterEndingService.triggerEnding을 호출하거나, 최소한 응답 DTO에 endingAvailable=true 신호를 실어 FE가 /theater/{roomId}/ending으로 이동하게 한다. 순환 의존을 피하려면 TheaterService→TheaterEndingService 단방향으로 배치.
(2) 조기 강제 종료 차단: TheaterEndingService.triggerEnding의 :75 가드 옆에 진행도 검사를 추가:
```java
if (!(state.getCurrentAct() == TheaterAct.ACT_4_RESOLUTION
      && directorEngine.isLastChapterOfAct(state))) {
    throw new BadRequestException("아직 극이 끝나지 않았습니다.");
}
```
(3) B-9.9와 함께 처리 — 다시 보기 경로는 POST가 아니라 GET 조회로 분리해야 (2)의 가드와 충돌하지 않는다.

**제품 결정 연동**: ★블록 D 정면 충돌★ docs/14 §C#6은 '엔딩=자유·스토리만 게이트 오프(코드 보존·극장 유지)'다. 그런데 현재 극장 엔딩은 유저가 도달할 수 없다. 게이트 오프를 그대로 실행하면 **제품에서 엔딩이라는 문법이 사실상 전멸한다** — '극장 유지'라는 결정의 전제가 코드에서 성립하지 않기 때문이다. 블록 D 착수 전에 이 발동 경로를 먼저 살리거나, 극장 엔딩도 함께 게이트 오프할지를 재결정해야 한다.

**❓ 결정 필요**: 블록 D의 '극장 엔딩 유지'는 (a) 지금 죽어 있는 발동 경로를 살려서 유지한다는 뜻인가, (b) 코드만 보존하고 실사용은 안 해도 된다는 뜻인가? (a)라면 블록 D 규모가 docs/14 표의 '소'를 넘어선다(발동 경로 신설 + 결과 영속). 종원 판단 필요.

---

### B-9.9. [극장 교차확인] 극장 엔딩 결과를 영속하지 않아 보관함의 '엔딩 다시 보기' CTA가 항상 400

**🔴 잔존** · P1 · MEDIUM · BE/FE/DB_MIGRATION  
`src/main/java/com/spring/aichat/service/theater/TheaterEndingService.java:75-77,116 · C:/Users/zapza/Desktop/LucidChat-Front/LucidChat-Front/src/pages/TheaterEndingCredits.jsx:90-103`

**근거**

저장되는 것: TheaterState.java:169-181의 endingReached / endingType / endingTitle / endingMainHeroineId 4개뿐. TheaterEndingService.java:116 `state.markEndingReached(endingType, endingType.getTitleKo(), mainHeroine.getId());`
저장되지 않는 것: TheaterEnding 응답의 endingScenes(LLM 생성, :102-106), closingQuote, memoryHighlights, stats. `ending_scenes` 컬럼 grep 0건.
재호출은 무조건 거부된다 — TheaterEndingService.java:75-77:
```java
if (state.isEndingReached()) {
    throw new BadRequestException("이미 엔딩에 도달했습니다.");
}
```
그런데 FE의 유일한 진입점이 바로 그 재호출이다 — TheaterEndingCredits.jsx:90-103:
```javascript
useEffect(() => {
  sfx.wooshDeep();
  let alive = true;
  (async () => {
    try {
      const result = await triggerTheaterEnding(Number(roomId));   // POST /theater/rooms/{id}/ending
      if (alive) setEnding(result);
    } catch (e) {
      if (alive) setLoadError(e?.response?.data?.message || "엔딩을 불러오지 못했습니다.");
    }
  })();
}, [roomId]);
```
TheaterArchivePage.jsx:25 주석 "ENDED 카드: '엔딩 다시 보기' CTA → /theater/:roomId/ending 이동", :93-95 handleViewEnding이 그 페이지로 보낸다. ENDED 카드는 정의상 endingReached=true이므로 **CTA를 누르면 100% '이미 엔딩에 도달했습니다.' 에러 배너**가 뜬다. 조회 전용 GET 엔드포인트는 없다(TheaterFinalityApi.js 전문 확인 — 엔딩은 triggerTheaterEnding POST 하나뿐).

**수정안**

B-9.1의 공용 영속 계층을 극장에도 쓴다.
1) TheaterEndingService.triggerEnding 성공 시 TheaterEnding 전체(scenes·closingQuote·memoryHighlights·stats)를 EndingResultDocument(roomId 키, mode=THEATER)로 저장한다 — :116 markEndingReached 직후, :131 return 직전.
2) TheaterFinalityController에 `@GetMapping("/ending")` 조회 엔드포인트를 추가해 저장본을 반환한다(없으면 404).
3) TheaterFinalityApi.js에 `fetchTheaterEnding(roomId)` GET을 추가하고, TheaterEndingCredits.jsx:90-103의 useEffect를 'GET 먼저 → 404면 POST(최초 발동)'로 바꾼다. 그러면 아카이브 재감상은 GET(LLM 0콜), 최초 도달은 POST 한 번으로 정리되고 B-9.8의 진행도 가드와도 충돌하지 않는다.

**제품 결정 연동**: 블록 D가 극장 엔딩을 '유지'하는 이상 이 수정은 필수다. B-9.1의 영속 계층을 V1/V2/극장 공용으로 설계하면 두 작업이 하나로 합쳐진다 — 블록 D 착수 시 함께 처리하는 것이 효율적이다.

---

### B-9.10. [극장 교차확인] 극장 엔딩 멱등성 가드가 TOCTOU — 체크와 저장 사이에 LLM 생성이 끼어 병렬 요청 2건이 동시 통과

**🔴 잔존** · P2 · SMALL · BE  
`src/main/java/com/spring/aichat/service/theater/TheaterEndingService.java:70-120`

**근거**

TheaterEndingService.java:70-120의 순서:
```java
@Transactional
public TheaterEnding triggerEnding(Long roomId, String username) {
    ChatRoom room = getOwnedRoom(roomId, username);
    TheaterState state = getState(roomId);
    if (state.isEndingReached()) {            // :75  ← 체크
        throw new BadRequestException("이미 엔딩에 도달했습니다.");
    }
    ...
    EndingScenePayload payload = generateEndingScenesViaLlm(...);   // :102 ← 수 초~수십 초 LLM
    ...
    state.markEndingReached(endingType, endingType.getTitleKo(), mainHeroine.getId());  // :116 ← 저장(커밋은 메서드 종료 시)
```
@Transactional 기본 격리(READ_COMMITTED)에서 :75의 읽기는 공유 락을 잡지 않고, :116의 변경은 dirty checking으로 커밋 시점에야 flush된다. 따라서 동시 도착한 두 요청이 모두 :75를 통과해 각각 LLM을 태우고 둘 다 :116을 실행한다. 순차 연타(사람 클릭)는 첫 요청이 이미 커밋돼 막히므로 실전 위험은 낮으나, TheaterEndingCredits.jsx:90의 useEffect는 React StrictMode 개발 환경에서 2회 실행되는 패턴이라 재현 가능성이 있다(현재 alive 플래그는 setState만 막을 뿐 요청 자체는 두 번 나간다).
대조: V1/V2 EndingService에는 이 가드조차 없다(B-9.1).

**수정안**

LLM 호출 전에 상태를 선점한다. :75 체크 직후 별도의 짧은 트랜잭션(또는 비관적 락)으로 '생성 중' 마커를 커밋하거나, TheaterStateRepository에 `findByRoomIdForUpdate`(@Lock(PESSIMISTIC_WRITE))를 추가해 :73 getState를 그것으로 바꾼다. 후자가 코드 변경이 가장 작다 — 두 번째 요청은 첫 요청 커밋까지 대기했다가 :75에서 정상적으로 400을 받는다. B-9.9의 'GET 먼저, 없으면 POST' 전환을 하면 FE 측 중복 발사도 함께 줄어든다.

**제품 결정 연동**: none — 극장 엔딩은 블록 D에서 유지되므로 게이트 오프와 무관하게 남는 결함.

---

### B-10.1. 리프레시 토큰에 토큰 타입 구분 클레임이 없어 Bearer 액세스 토큰 자리에 그대로 통과 — 사실상 14일짜리 액세스 토큰

**🔴 잔존** · P0 · SMALL · BE  
`src/main/java/com/spring/aichat/service/auth/JwtTokenService.java:60-89 · src/main/java/com/spring/aichat/config/JwtConfig.java:31-36`

**근거**

두 토큰의 유일한 차이는 jti·role 클레임과 만료뿐, 타입 구분자가 없다 — JwtTokenService.java:60-89:
```java
private String generateAccessToken(String username, String role) {
    JwtClaimsSet claims = JwtClaimsSet.builder()
        .id(UUID.randomUUID().toString())   // jti
        .issuer(props.issuer()).issuedAt(now)
        .expiresAt(now.plusSeconds(props.accessTokenTtlSeconds()))
        .subject(username).claim("role", role).build();
    JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
    ...
}
private String generateRefreshToken(String username) {
    JwtClaimsSet claims = JwtClaimsSet.builder()
        .issuer(props.issuer()).issuedAt(now)
        .expiresAt(now.plusSeconds(props.refreshTokenTtlSeconds()))
        .subject(username).build();          // ← typ 없음, jti 없음, role 없음
    JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
    ...
}
```
디코더는 단 하나이고 추가 검증자가 없다 — JwtConfig.java:31-36:
```java
@Bean
public JwtDecoder jwtDecoder(SecretKey key) {
    return NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
}
```
(기본 검증자는 만료/nbf뿐 — issuer·audience·typ 검증 없음.)
인가 경로: SecurityConfig.java:88 `.anyRequest().authenticated()` — 권한이 아니라 '인증 여부'만 본다. RT에 role 클레임이 없으니 authorities는 비지만(SecurityConfig.java:131-138 JwtGrantedAuthoritiesConverter, claimName="role"), 그건 /api/v1/admin/**(:87 hasRole("ADMIN"))만 막을 뿐 일반 유저 API 전체는 통과한다. @PreAuthorize("@authGuard.checkRoomOwnership(#roomId, principal.subject)")도 subject만 쓰므로 정상 동작한다.
TTL 비대칭이 피해를 키운다 — application.yml:79-80 `access-token-ttl-seconds: 3600` / `refresh-token-ttl-seconds: 1209600`. RT 하나면 **14일간 유효한 액세스 토큰**을 손에 쥔 것과 같다(AT의 336배).
역방향은 안전하다: AT를 refresh로 쓰려 해도 reissue(:104-110)가 Redis의 RT:{username} 값과 문자열 비교하므로 실패한다. 즉 RT→AT 단방향 혼용만 성립한다.

**수정안**

토큰에 타입 클레임을 넣고 리소스 서버가 거부하게 한다.
1) JwtTokenService.generateAccessToken(:62-69)에 `.claim("typ", "access")`, generateRefreshToken(:80-85)에 `.claim("typ", "refresh")` 추가.
2) JwtConfig.jwtDecoder(:32-36)에 OAuth2TokenValidator를 붙여 `"access".equals(jwt.getClaimAsString("typ"))`가 아니면 실패시킨다. 단 이 디코더는 JwtTokenService.reissue(:100)와 logout(:147)·isTokenRevoked(:224)도 공유하므로, refresh 검증용 디코더를 별도 빈으로 분리하거나(권장) 검증을 JwtBlacklistFilter 쪽으로 옮긴다.
3) 대안(디코더를 안 건드리는 최소 변경): JwtBlacklistFilter.doFilterInternal(:36-48)에서 Bearer 토큰을 디코드해 typ != "access"면 401. 이미 그 자리에서 디코드하고 있어(isTokenRevoked→jwtDecoder.decode) 추가 비용이 없다.
4) 하위 호환: 기존 발급 토큰에는 typ가 없다. 전환기에는 'typ 없음 = access로 간주'로 두되, RT는 jti도 없으므로 `typ 없음 && jti 없음`을 refresh로 판정해 거부하는 규칙이 안전하다. AT TTL이 1시간이라 완전 전환까지 1시간이면 충분하다.

**제품 결정 연동**: none — 블록 A~E 어느 결정과도 무관. 다만 docs/16이 시크릿 모드를 핵심 BM으로 승격시켰고 성인 인증·결제가 붙는 만큼, 인증 계층 결함의 실질 위험도는 docs/13 작성 시점보다 올라갔다. PG 가맹 심사(docs/14 §D 2주차) 전에 닫아 두는 편이 좋다.

---

### B-10.2. 리프레시 토큰에 jti가 없어 로그아웃·블랙리스트로 무효화 불가 — 로그아웃 후에도 남은 TTL 동안 Bearer로 계속 통과

**🔴 잔존** · P1 · SMALL · BE  
`src/main/java/com/spring/aichat/service/auth/JwtTokenService.java:78-89,145-175`

**근거**

generateRefreshToken(JwtTokenService.java:78-89)은 `.id(...)`를 호출하지 않아 jti가 없다(generateAccessToken :63은 `.id(UUID.randomUUID().toString())`).
로그아웃은 AT의 jti만 블랙리스트하고 RT는 Redis 키만 지운다 — JwtTokenService.java:145-175:
```java
public void logout(String accessToken, String username) {
    Jwt jwt = jwtDecoder.decode(accessToken);
    long ttl = ...;
    if (ttl > 0) {
        String jti = jwt.getId();
        if (jti != null && !jti.isBlank()) {
            redisTemplate.opsForValue().set(BLACKLIST_PREFIX + jti, "logout", ttl, TimeUnit.SECONDS);
        } else { ... 토큰 전체로 폴백 ... }
    }
    redisTemplate.delete(REFRESH_PREFIX + username);   // ← RT는 '재발급 불가'만 될 뿐
}
```
AuthController.java:123-132(logout)는 Authorization 헤더의 AT만 넘긴다 — RT는 인자로도 안 들어간다(@CookieValue refreshToken은 받아만 놓고 clearRefreshTokenCookie만 호출).
결과: RT JWT 자체는 서명이 유효한 채 남고, BL:{jti}에도 BL:{token}에도 등록되지 않는다. isTokenRevoked(:222-238)는 jti·전체토큰·SUSP:USER:{sub} 3가지를 보는데 앞의 둘이 모두 미스다.
(부분 완화: 계정 정지 시 revokeUserSessions(:206-211)가 SUSP:USER:{username}을 심으므로 RT-as-Bearer도 차단된다. 정지가 아닌 '자발적 로그아웃'만 뚫린다.)
피해 시나리오: 공용 PC/기기 양도. 유저가 로그아웃해도 그 세션 중 캡처된 RT는 최대 14일간 유효한 액세스 토큰으로 남는다.

**수정안**

B-10.1을 적용하면 RT가 Bearer로 아예 거부되므로 실질 위험은 소멸한다. 방어층으로 함께 넣을 것:
1) generateRefreshToken(:80-85)에 `.id(UUID.randomUUID().toString())` 추가 — RT도 jti를 갖게 한다.
2) AuthController.logout(:123-132)에서 refreshToken을 jwtTokenService.logout에 함께 넘기고, JwtTokenService.logout이 RT의 jti도 BL:{jti}에 남은 TTL만큼 등록하도록 확장한다.
3) reissue의 RTR 회전(:98-121) 시 구 RT의 jti도 블랙리스트에 넣으면 회전 후 구 토큰 재사용까지 봉쇄된다(현재는 Redis 값 불일치로 reissue만 막힐 뿐 Bearer 통과는 열려 있음).

**제품 결정 연동**: none — B-10.1과 한 세트로 처리할 것.

---

### B-11.1. 로그인·회원가입 레이트리밋 키가 X-Forwarded-For 최좌측 값 — 헤더 조작으로 브루트포스 완전 우회 (올바른 리졸버가 이미 코드에 있는데 미적용)

**🔴 잔존** · P1 · ONE_LINE · BE/INFRA  
`src/main/java/com/spring/aichat/controller/AuthController.java:166-172`

**근거**

AuthController.java:166-172 현재 그대로:
```java
private String extractClientIp(HttpServletRequest request) {
    String xff = request.getHeader("X-Forwarded-For");
    if (xff != null && !xff.isBlank()) {
        return xff.split(",")[0].trim();      // ← 최좌측 = 클라이언트가 임의 주입 가능
    }
    return request.getRemoteAddr();
}
```
사용처: :55-58(signup, 3회/60초) · :112-115(login, 5회/60초). ApiRateLimiter.isRateLimited(:83-84)는 `"rl:" + endpoint + ":" + identifier`를 그대로 Redis 키로 쓰므로, 매 요청 XFF를 바꾸면 버킷이 매번 새로 생겨 한도가 무의미해진다.

★ 블록 A에서 **올바른 구현이 이미 들어왔지만 이 경로에는 적용되지 않았다** — ClientIpResolver.java:24-34:
```java
public static String resolve(HttpServletRequest request) {
    String xff = request.getHeader("X-Forwarded-For");
    if (xff != null && !xff.isBlank()) {
        String[] parts = xff.split(",");
        String candidate = parts[parts.length - 1].trim();   // 최우측 = ALB가 append
        if (!candidate.isEmpty()) return candidate;
    }
    return request.getRemoteAddr();
}
```
그 클래스의 주석(:12-13)이 의도적 보류를 명시한다: "기존 AuthController.extractClientIp(최좌측 사용)는 의도적으로 손대지 않는다 — docs/13 B-11 수정은 버그 픽스 세션 몫. 신규 게스트 경로만 본 유틸을 쓴다." 즉 B-11은 알려진 채 미수정으로 남아 있다.
현재 ClientIpResolver 사용처는 GuestBrowseRateLimitFilter.java:102 하나뿐.

**수정안**

AuthController.java:166-172의 extractClientIp 메서드를 삭제하고 :55·:112의 호출을 `ClientIpResolver.resolve(httpReq)`로 교체한다(import 추가). 신규 코드를 쓰지 말고 기존 유틸을 재사용할 것 — 두 벌의 IP 해석 로직이 갈라지면 다음 감사에서 또 어긋난다.
인프라 전제 동반: ClientIpResolver.java:15-18의 경고가 그대로 적용된다 — 최우측 신뢰는 '모든 트래픽이 ALB를 경유'할 때만 성립한다. ECS 태스크 SG 인그레스가 ALB SG 전용인지 확인해야 하며, 이는 docs/06 §7 보안 체크리스트 항목이다(코드가 아닌 SG로 막는 부분).

**제품 결정 연동**: none — 블록 A~E 어느 결정과도 무관. 다만 docs/14 §D 행정 체크리스트(PG 가맹 심사·NICE 본인확인)와 docs/16의 시크릿 정면 전략을 감안하면 계정 탈취 방어선은 심사 전 정리 대상이다.

---

### B-11.2. 로그인 레이트리밋이 IP 키 단독 — 계정 단위 한도가 없어 XFF를 고쳐도 분산 크리덴셜 스터핑은 무제한

**🔴 잔존** · P2 · SMALL · BE  
`src/main/java/com/spring/aichat/controller/AuthController.java:112-115 · src/main/java/com/spring/aichat/security/ApiRateLimiter.java:147-149`

**근거**

AuthController.java:112-115:
```java
String clientIp = extractClientIp(httpReq);
if (rateLimiter.checkLogin(clientIp)) {
    throw new RateLimitException("로그인 시도가 너무 빈번합니다. 1분 후 다시 시도해주세요.", 60);
}
AuthService.AuthResult result = authService.login(req);
```
ApiRateLimiter.java:147-149 `checkLogin(String ipOrUsername) { return isRateLimited("login", ipOrUsername, 5, 60); }` — 인자명이 ipOrUsername인데 호출처는 IP만 넘긴다. username 기반 병행 버킷이 없다.
또한 실패/성공을 구분하지 않는다 — 정상 로그인 성공도 카운트를 소모하고, 실패만 가중하는 설계가 아니다.
B-11.1을 고쳐 최우측 IP로 바꿔도, 봇넷/프록시 풀로 IP를 분산하면 한 계정에 대한 시도 횟수 상한이 없다.
부가: Redis 장애 시 fail-open이다 — ApiRateLimiter.java:101-106 `catch (Exception e) { ... return false; }`("Redis 장애 시 → 요청 허용, 서비스 가용성 우선"). 인증 엔드포인트에 대해서는 이 정책이 브루트포스 창을 여는 쪽으로 작용한다.

**수정안**

AuthController.login(:110-120)에서 IP 버킷과 계정 버킷을 둘 다 검사한다:
```java
if (rateLimiter.checkLogin(clientIp)) throw new RateLimitException(...);
if (rateLimiter.isRateLimited("login_acct", req.username(), 10, 600)) throw new RateLimitException(...);
```
(한도는 정책 판단 — 10회/10분 정도가 통상.) 더 나은 형태는 실패 시에만 카운트를 올리는 것 — authService.login이 던지는 인증 실패 예외를 catch해 그때만 INCR하고, 성공 시 계정 버킷을 삭제한다. 이러려면 ApiRateLimiter에 카운트 전용 메서드와 리셋 메서드를 추가해야 한다.
Redis fail-open은 인증 경로만 fail-closed로 뒤집는 옵션을 검토(ApiRateLimiter에 strict 플래그 인자 추가).

**제품 결정 연동**: none. 단 계정 단위 락아웃은 '타 유저 계정을 일부러 잠그는' DoS 벡터를 만들 수 있어 한도 설정에 균형이 필요하다.

**❓ 결정 필요**: 계정 단위 로그인 한도를 도입할 경우 (a) 초과 시 차단(락아웃 — 표적 DoS 위험) vs (b) 초과 시 CAPTCHA/추가 인증 요구(구현 비용) 중 어느 쪽을 택할지. 런칭 규모에서는 (a)의 완화형(10회/10분, 자동 해제)이 무난하나 종원 판단 사항.

---

### B-12. 미게시 공지 본문이 유저 대면 GET /api/v1/notices/{id}에 published 검사 없이 그대로 노출

**🔴 잔존** · P1 · ONE_LINE · BE  
`src/main/java/com/spring/aichat/service/notice/NoticeService.java:30-33 · src/main/java/com/spring/aichat/controller/NoticeController.java:26-29`

**근거**

NoticeService.java:24-33 — 목록은 필터하는데 단건은 안 한다:
```java
@Transactional(readOnly = true)
public List<NoticeResponse> publicList() {
    return noticeRepository.findByPublishedTrueOrderByPinnedDescPublishedAtDesc()
        .stream().map(NoticeResponse::from).toList();
}

@Transactional(readOnly = true)
public NoticeResponse get(Long id) {
    return NoticeResponse.from(load(id));      // ← published 검사 없음
}
```
load(:69-72)는 findById만 한다. 이 get()은 유저 컨트롤러가 직접 호출한다 — NoticeController.java:13-29 ("유저 대면 공지사항 — 게시된 항목만"이라는 클래스 주석과 실제 동작이 어긋난다):
```java
@GetMapping("/{id}")
public NoticeResponse get(@PathVariable Long id) {
    return noticeService.get(id);
}
```
본문이 통째로 실린다 — NoticeResponse는 body를 포함한다(dto/notice/NoticeResponse.java: `Long id, String title, String body, boolean pinned, boolean published, ...`).
도달 조건: SecurityConfig.java:88 `.anyRequest().authenticated()` — /api/v1/notices는 permitAll 목록(:58-84)에 없으므로 로그인 유저 전용이다. 즉 아무 계정이나 만들어 id를 1부터 훑으면 작성 중인 초안(가격 변경·점검·정책 발표 등)이 전부 노출된다.
블록 A가 이 결함을 인지하고 게스트 개방을 보류했다 — SecurityConfig.java:72-73 주석: "주의: /api/v1/notices는 published 미검사 결함(docs/13 B-12)이 남아 있어 버그 픽스 세션 전까지 게스트 개방 보류."

**수정안**

NoticeService.get(:30-33)에 published 검사를 넣는다:
```java
@Transactional(readOnly = true)
public NoticeResponse get(Long id) {
    Notice n = load(id);
    if (!n.isPublished()) throw new BusinessException(ErrorCode.NOT_FOUND, "공지를 찾을 수 없습니다: " + id);
    return NoticeResponse.from(n);
}
```
(미게시를 403이 아니라 404로 감추는 편이 존재 여부 유출을 막는다.) 어드민 단건 조회가 필요하면 AdminNoticeController용 별도 메서드(getForAdmin)를 만든다 — 현재 AdminNoticeController는 adminList/create/update/delete만 쓰고 단건 get을 안 쓰므로 지금은 불필요하다.
동일 패턴 점검: FaqService에도 publicList가 있으나 유저 대면 단건 get 엔드포인트는 없다(FaqController 확인) — FAQ는 현재 안전하다.

**제품 결정 연동**: 블록 A가 이 결함 때문에 /api/v1/notices의 게스트 개방을 보류해 두었다(SecurityConfig.java:72-73). 즉 이걸 고치면 '게스트도 공지를 볼 수 있게' 한다는 후속 판단이 열린다 — 수정 시 SecurityConfig의 게스트 permitAll 목록과 GuestBrowseRateLimitFilter의 프리픽스 목록에 /api/v1/notices를 추가할지 함께 결정할 것(두 목록은 동기 유지가 원칙이라고 주석에 명시돼 있다).

**❓ 결정 필요**: B-12 수정 후 공지를 게스트에게도 개방할 것인가? 블록 A 설계상 로비 게스트 브라우징에 공지가 있으면 자연스럽지만, 개방은 별도 승인 절차(응답 DTO 검수·레이트리밋 커버리지 확인)를 요구한다고 SecurityConfig 주석이 규정한다.

---

### B-13. 알림 읽음 처리 IDOR — notificationId가 roomId에 묶여 있지 않아 타 유저 알림을 읽음 처리 가능

**🔴 잔존** · P2 · ONE_LINE · BE  
`src/main/java/com/spring/aichat/controller/StoryV2Controller.java:215-223 · src/main/java/com/spring/aichat/service/story/OffscreenNotificationService.java:194-197`

**근거**

StoryV2Controller.java:215-223 — 소유권 검증은 roomId에만 걸리고, 정작 쓰이는 건 notificationId다:
```java
@PostMapping("/rooms/{roomId}/notifications/{notificationId}/read")
@PreAuthorize("@authGuard.checkRoomOwnership(#roomId, principal.subject)")
public ResponseEntity<Void> markNotificationRead(
    @PathVariable Long roomId,
    @PathVariable Long notificationId
) {
    notificationService.markRead(notificationId);   // ← roomId를 전혀 쓰지 않음
    return ResponseEntity.noContent().build();
}
```
OffscreenNotificationService.java:194-197:
```java
@Transactional
public void markRead(Long notificationId) {
    notificationRepository.findById(notificationId).ifPresent(OffscreenNotification::markRead);
}
```
공격: 본인 소유 roomId(가드 통과용) + 임의의 notificationId를 조합해 POST. 피해자의 알림이 읽음 처리된다.
대조로 조회 쪽은 안전하다 — getUnreadNotifications(:205-208)는 findUnreadForToast(roomId)로 방 스코프 질의를 하므로(OffscreenNotificationService.java:157-159 `findByChatRoom_IdAndReadAtIsNullOrderBySentAtDesc(roomId)`) 정보 유출은 없다. 응답도 204 No Content라 읽기 채널로는 못 쓴다.
피해: 정보 유출 없는 쓰기 전용 IDOR. 피해자는 토스트를 못 보게 되고, 미확인 알림 카운트(countUnread :190-192, countByChatRoom_IdAndReadAtIsNull)가 어긋난다. 다만 readAt과 respondedAt은 분리돼 있어(주석 :216-217 "읽음과 응답은 분리") 디렉터 서사(미응답 페널티·만료 처리)까지는 훼손되지 않는다 — markResponded(:206-209)와 만료 스케줄러는 respondedAt 기준이다.

**수정안**

조회 자체를 방 스코프로 좁힌다.
1) OffscreenNotificationRepository에 `Optional<OffscreenNotification> findByIdAndChatRoom_Id(Long id, Long chatRoomId);`를 추가.
2) OffscreenNotificationService.markRead의 시그니처를 `markRead(Long roomId, Long notificationId)`로 바꾸고 본문을 `notificationRepository.findByIdAndChatRoom_Id(notificationId, roomId).ifPresent(OffscreenNotification::markRead);`로 교체.
3) StoryV2Controller.java:221을 `notificationService.markRead(roomId, notificationId);`로 수정.
동일 패턴 점검: markResponded(OffscreenNotificationService.java:206-209)도 findById 단독이라 같은 형태지만, 호출처가 서버 내부(ChatStreamService 디렉터 응답 처리)뿐이라 외부 도달 경로가 없다 — 방어적으로 함께 고쳐도 비용이 같다.

**제품 결정 연동**: none — 오프스크린 알림은 V2 스토리 핵심 문법이고 docs/14 §G의 삭제·게이트오프 21건 어디에도 없다. 블록 D와 무관하게 유지되므로 수정 필요.

---

## C. P0-B 죽은 핵심 플로우  (23건)

### C-0.1. 로비 ModeSelectOverlay '스토리 모드' 버튼 → POST /lobby/rooms {chatMode:"STORY"} 프론트 배선

**⚪ 코드소멸** · P3 · N/A · -  
`N/A (C:/Users/zapza/Desktop/LucidChat-Front/LucidChat-Front/src/pages/LobbyPage.jsx 삭제됨)`

**근거**

FE master HEAD(55a4b78)에 LobbyPage.jsx 부재. `git log --diff-filter=D --all -- "**/LobbyPage.jsx"` → `0d9f87b feat : 플랫폼형 로비 전환 (블록 A)`에서 삭제. `git ls-tree -r HEAD | grep -i lobby` → ArchiveTab/FirstMeetPage/HomeTab/LobbyShell/StoryTab/lobbyShared/lobbyUi 만 존재.
`grep -rn "ModeSelectOverlay|handleModeSelect" src/` → 실코드 0건(TheaterCreateFlow.jsx:111,149의 주석 언급뿐).
현행 STORY 진입은 StoryTab → StoryCreateFlow → StoryV2Api:
  src\pages\lobby\StoryTab.jsx:69-72  `const handleStartStory = (world) => { ... setStoryCreate({ worldId: world.worldId }); }`
  src\pages\lobby\StoryTab.jsx:147   `<StoryCreateFlow worldId={storyCreate.worldId} ...`
  src\components\story-v2\StoryCreateFlow.jsx:124 `const res = await createStoryV2Room(payload);`
  src\api\StoryV2Api.js:74-75 `export async function createStoryV2Room(payload) { const res = await api.post("/v2/story/rooms", payload);`
또한 FE 전역에서 /lobby/rooms 로 STORY를 보내는 호출부는 0건 — `grep -rn "chatMode:" src/` 결과 전부 SANDBOX (HomeTab.jsx:184, FirstMeetPage.jsx:136, StudioPage.jsx:538, StudioCreateFlow.jsx:2219, postLogin.js:71).

**수정안**

수정 불필요. 단, C-0.2~C-0.4의 백엔드 방어는 이 프론트 삭제로 해소되지 않으므로 별도 수정 필요(프론트 소멸이 백엔드 500을 없애지 않는다).

**제품 결정 연동**: docs/14 §G #4(🔴삭제 '데드 코드 일괄')가 'LobbyTabShell·characters 카루셀+ModeSelectOverlay 체인' 제거를 명시했고, 블록 A R2(`0e82296`)가 이를 선행 이행했다. docs/14 §G #2(🔴삭제 'V1 STORY 모드 트랙 — 진입 전멸(로비 500+데드 체인), V2 완전 대체')와도 정합. 즉 docs/13이 제안한 '프론트에서 STORY를 V2 CreateFlow로 라우팅'은 이미 제품 결정대로 완료된 상태.

---

### C-0.2. ChatRoom(User,Character,ChatMode) 생성자가 STORY에 IllegalArgumentException을 던짐

**🔴 잔존** · P1 · ONE_LINE · BE  
`aichat/src/main/java/com/spring/aichat/domain/chat/ChatRoom.java:353-358`

**근거**

ChatRoom.java:352-358 (현재 코드 그대로)
```java
/** [V1 호환] Sandbox 또는 Theater 방 생성. STORY V2는 {@link #createStoryV2} 사용. */
public ChatRoom(User user, Character character, ChatMode chatMode) {
    if (chatMode == ChatMode.STORY) {
        throw new IllegalArgumentException(
            "V2 STORY 방은 ChatRoom.createStoryV2(user, world, ...)로 생성하세요. " +
                "Character FK 단독으로는 V2 STORY 방을 구성할 수 없습니다.");
    }
```
호출부 3곳 확인(`grep -rn "new ChatRoom(" src/main/java/`): OnboardingService.java:45(2-arg → SANDBOX), LobbyService.java:206(chatMode 그대로 전달 ← STORY 도달 지점), TheaterLobbyService.java:436(ChatMode.THEATER 고정). 즉 STORY가 이 throw에 도달하는 유일 경로는 LobbyService.createOrGetRoom.

**수정안**

이 throw 자체는 '잘못된 팩토리 사용'을 막는 정당한 방어이므로 **제거하지 말 것**. 대신 도메인 예외를 상위에서 500으로 만들지 않도록 C-0.3(핸들러 추가) 또는 C-0.4(서비스 선차단) 중 하나로 400 매핑을 보장한다. 권장: LobbyService에서 선차단(C-0.4)하여 이 생성자에 STORY가 애초에 도달하지 않게 하고, 이 throw는 최후 방어선으로 존치.

**제품 결정 연동**: docs/14 §G #2(V1 STORY 트랙 삭제)가 확정되면 이 생성자 분기는 오히려 '정책 표현'으로서 존치 가치가 커진다. 삭제 방향으로 가더라도 이 가드는 남겨야 한다(impl_spec_details §5: '잔존 V1 STORY 방 데이터 처리를 먼저 결정 — 방을 고아로 만들지 말 것').

---

### C-0.3. GlobalExceptionHandler에 IllegalArgumentException 핸들러 부재 → 모든 IAE가 500

**🔴 잔존** · P1 · SMALL · BE  
`aichat/src/main/java/com/spring/aichat/exception/GlobalExceptionHandler.java:73-79`

**근거**

`grep -n "@ExceptionHandler" GlobalExceptionHandler.java` → 20:BusinessException / 50:RateLimitException / 62:MethodArgumentNotValidException / 73:Exception. **IllegalArgumentException 핸들러 없음.**
GlobalExceptionHandler.java:73-79
```java
@ExceptionHandler(Exception.class)
public ResponseEntity<ApiErrorResponse> handleUnknown(Exception e, HttpServletRequest req) {
    log.error("Unhandled exception occurred: ", e);
    return ResponseEntity.internalServerError()
        .body(ApiErrorResponse.of(500, ErrorCode.INTERNAL_ERROR, "서버 오류가 발생했습니다.", req.getRequestURI()));
}
```
부수 확인: HttpMessageNotReadableException 핸들러도 없어, 잘못된 enum 값(예: C-2.c의 LUCID_PASS_MONTHLY) 역직렬화 실패도 400이 아닌 500이 된다.

**수정안**

GlobalExceptionHandler에 두 핸들러 추가:
1) `@ExceptionHandler(IllegalArgumentException.class)` → 400 + ErrorCode.BAD_REQUEST. 단 내부 메시지(팩토리 사용법 안내)를 그대로 노출하면 내부 구조가 새므로, 응답 message는 고정 카피("요청이 올바르지 않습니다.")로 하고 원문은 log.warn으로만 남긴다.
2) `@ExceptionHandler(HttpMessageNotReadableException.class)` → 400 (알 수 없는 enum/malformed body).
두 핸들러 모두 `@ExceptionHandler(Exception.class)`보다 구체적이므로 자동으로 우선 매칭된다.

**제품 결정 연동**: none — 횡단 관심사. 다만 광범위 IAE를 일괄 400으로 내리면 지금까지 500으로 잡히던 진짜 서버 버그가 400에 묻힐 수 있으므로, 핸들러 안에서 반드시 log.warn(스택 포함) 유지할 것.

---

### C-0.4. LobbyService.createOrGetRoom이 STORY 요청을 400으로 선차단하지 않아 500 도달 (직접 API 호출로 재현 가능)

**🔴 잔존** · P2 · ONE_LINE · BE  
`aichat/src/main/java/com/spring/aichat/service/LobbyService.java:175-222 (특히 189-191, 206)`

**근거**

LobbyService.java:189-191 — storyAvailable=true면 통과하는 유일한 STORY 가드
```java
if (chatMode == ChatMode.STORY && !character.isStoryAvailable()) {
    throw new BadRequestException("해당 캐릭터는 아직 스토리 모드를 지원하지 않습니다.");
}
```
LobbyService.java:203-206
```java
ChatRoom room = chatRoomRepository
    .findByUser_IdAndCharacter_IdAndChatMode(user.getId(), character.getId(), chatMode)
    .orElseGet(() -> {
        ChatRoom created = new ChatRoom(user, character, chatMode);   // ← STORY면 C-0.2의 IAE
```
도달 가능성: LobbyController.java:91-96 `@PostMapping("/rooms") ... @RequestBody @Valid CreateRoomRequest request` , CreateRoomRequest.java:16 `String chatMode   // "STORY" or "SANDBOX"` — 문자열이므로 인증된 아무 유저나 `{"characterId":<공식캐릭>,"chatMode":"STORY"}`를 보내면 189 가드를 통과(공식 시드 전부 story-available: true)하고 206에서 IAE → 500.
※ 프론트 진입점은 사라졌으므로(C-0.1) UI 상 100% 재현은 소멸했고, 남은 도달면은 직접 API 호출·구버전 캐시된 번들·외부 클라이언트다.

**수정안**

LobbyService.createOrGetRoom의 chatMode 파싱(:182-187) 직후, storyAvailable 가드(:189)보다 **앞에** 무조건 차단 추가:
```java
if (chatMode == ChatMode.STORY) {
    throw new BadRequestException("스토리는 세계관 선택 화면에서 시작할 수 있어요.");
}
```
(§G #2 'V1 STORY 트랙 삭제'가 확정 결정이므로 조건부가 아니라 무조건 차단이 맞다. yml 노브가 필요하다면 impl_spec_details §5의 `${ENV:default}` 관례를 따를 것.)
기존 STORY 방 재입장 경로는 이 엔드포인트가 아니라 `/chat/rooms/{roomId}`·`/v2/story/rooms/{roomId}`이므로 잔존 방 접근에 영향 없음(생성 경로만 차단).

**제품 결정 연동**: docs/14 §G #2가 'V1 STORY 모드 트랙 삭제'를 🔴로 확정했으므로 docs/13의 원 제안(프론트를 V2로 라우팅 + 백엔드 400)에서 **백엔드 400이 임시방편이 아니라 최종 정책 표현**으로 격상된다. 다만 impl_spec_details §5 경고 — '잔존 V1 STORY 방 데이터 처리를 먼저 결정(마이그레이션 vs 읽기 전용), 방을 고아로 만들지 말 것' — 이 차단은 생성만 막으므로 잔존 방 처분 결정과 독립적으로 즉시 적용 가능.

---

### C-0.5. ~~Character.storyAvailable 기본값 true + 공식 시드 전부 story-available: true~~ — **원 수정안 폐기 · 무수정 존치로 종결 (2026-08-26 · D-33)**

**⛔ 수정안 폐기 · 무수정 존치** · P3 · N/A · -  
> **폐기 사유** — 원 수정안(①시드 `story-available` 일괄 false ②엔티티 기본값 false ③DTO 필드 제거)을 집행하면 **라이브 기능이 죽는다.** `storyAvailable`은 V1 STORY의 잔재가 아니라 **V2 STORY 히로인 풀의 필터**로 현재 사용 중이다(실측):
> - `service/story/StoryV2Service.java:126`·`:165` `.filter(c -> c.getWorldId() != null && c.isStoryAvailable() && !c.isHidden())` — 월드 히로인 풀 조회
> - `:487` `if (!h.isStoryAvailable())` · `:563` `if (!h.isStoryAvailable() || h.isHidden())` — 히로인 편입·전환 가드
> - `domain/character/Character.java:758` `c.storyAvailable = spec.ugcWorldId() != null;` — **UGC 월드 캐릭터가 이 플래그로 태어난다.** 시드를 false로 내리면 공식 10종이 V2 월드 히로인 풀에서 통째로 사라지고, 기본값을 false로 바꾸면 UGC 히로인 편입 경로가 조용히 막힌다.
> - 어드민 노출·조작 경로도 살아 있다(`AdminCharacterService.java:37` · `CharacterVisibilityRequest`) — 죽은 필드가 아니다.
>
> 원래의 기능적 위험(=C-0.4 STORY 500의 도달 조건 제공)은 **C-0.4가 '무조건 400'으로 수정되면서 이미 닫혔다**(docs/19 재판정: C-0.4 ✅ 수정됨). 남는 것은 명명 혼동뿐이므로 **코드 변경 없이 `Character.java:85`에 javadoc 1줄**("V1 STORY 트랙과 무관 — V2 STORY 히로인 풀 필터")로 종결한다. ⚠ 이 항목을 §G #2(V1 STORY 트랙 삭제) 이행 목록에 다시 올리지 말 것.

<sub>(아래는 폐기된 원 판정·수정안 원문 — 근거 추적용으로 보존)</sub>

**🔴 잔존(원 판정)** · P3 · SMALL · YML/BE  
`aichat/src/main/java/com/spring/aichat/domain/character/Character.java:85 / src/main/resources/application-characters.yml:36,200,302,425,541,659,776,889,1001,1112`

**근거**

Character.java:85 `private boolean storyAvailable = true;` (docs/13은 'ChatRoom 엔티티'라 적었으나 실제 소유 엔티티는 Character — 문서 오기)
application-characters.yml — 공식 10종 전부 `story-available: true` (10개 라인 전부 확인). application-charactersm.yml(남캐)도 대부분 true.
이 플래그는 CharacterResponse.java:17 `boolean storyAvailable`로 FE에 그대로 노출되고, LobbyService.java:189 가드가 이 값만 보므로 **STORY 500의 도달 조건을 제공하는 유일한 데이터**다.

**수정안** — ⛔ **폐기됨(2026-08-26). 아래 ①~③을 집행하지 말 것.** 사유는 이 절 머리말 참조.

C-0.4(서비스 무조건 차단)를 넣으면 기능적 위험은 사라지므로 필수는 아니다. 다만 §G #2 이행 시 정리 대상: ①application-characters.yml/charactersm.yml의 `story-available` 시드 키를 일괄 false로 내리거나 키 자체 제거 ②Character.java:85 기본값 false로 ③CharacterResponse.java:17 `storyAvailable` 필드가 FE 어디서도 소비되지 않는지 확인 후(현재 FE grep 상 미소비) 제거. ③까지 하면 계약 변경이므로 FE 동시 배포 필요 — 순서는 C-0.4 → 시드 정리 → DTO 정리.

**제품 결정 연동**: docs/14 §G #2(V1 STORY 트랙 삭제) 이행 범위에 직접 포함. §G #4 '죽은 시드 필드' 정리와 같은 성격이나 §G #4 목록(background/behavioral-anchors/tts-voice-id)에는 story-available이 명시돼 있지 않다 — 블록 D 착수 시 목록에 추가할지 판단 필요.

~~**❓ 결정 필요**: §G #2 'V1 STORY 트랙 삭제' 이행 시 story-available 시드 키를 (a)false로 내릴지 (b)키/컬럼째 제거할지 — 잔존 V1 STORY 방을 '읽기 전용'으로 남기기로 하면 (b)는 조회 코드까지 건드리게 된다.~~
→ **결정 소멸(2026-08-26)**: (a)·(b) 어느 쪽도 취하지 않는다. 플래그가 V2 STORY 라이브 필터이므로 **무수정 존치**가 답이고, 종원에게 물을 안건이 아니다.

---

### C-1.1. 성인인증 토큰 요청 경로 이중 프리픽스 (GET /api/v1/api/v1/verify/token → 404)

**✅ 수정됨** · P0 · N/A · -  
`FE/src/components/AdultVerificationModal.jsx:30-31`

**근거**

현재 코드 — 프리픽스 제거 + 픽스 주석 명시
```js
// [docs/13 C-1 픽스] axios 인스턴스 baseURL에 /api/v1이 이미 포함 — 재부착하면 404
const { data } = await axios.get('/verify/token');
```
baseURL 확인: src\api\axios.js:4 `baseURL: import.meta.env.VITE_API_BASE_URL || 'http://localhost:8080/api/v1'`, .env `VITE_API_BASE_URL=https://api.lucid-chat.com/api/v1`.
백엔드 대응: VerificationController.java:27 `@RequestMapping("/api/v1/verify")` + :36 `@GetMapping("/token")` → 최종 `/api/v1/verify/token` 일치.
픽스 커밋: 블록 B `cab6b3e` (FE `d187a59` 계열).

**수정안**

수정 불필요. 단 C-1.3~C-1.5가 미해결이라 **이 픽스만으로 성인인증이 동작하지는 않는다** — docs/16의 'C-1 수리 0순위'를 '경로 픽스 완료'로 종결 처리하면 안 된다.

**제품 결정 연동**: docs/16 §37이 C-1을 0순위로 지정 — 경로 픽스는 완료됐으나 체인 미완성(C-1.3~1.5)이므로 0순위 항목은 여전히 열려 있다.

---

### C-1.2. 성인인증 결과 콜백 경로 이중 프리픽스 (POST /api/v1/api/v1/verify/success → 404)

**✅ 수정됨** · P0 · N/A · -  
`FE/src/components/AdultVerificationModal.jsx:75`

**근거**

AdultVerificationModal.jsx:75
```js
const result = await axios.post('/verify/success', {
  requestNo: requestNo,
  encData: event.data.encData,
  tokenVersionId: tokenVersionId,
});
```
프리픽스 없음. 백엔드 VerificationController.java:47 `@PostMapping("/success")` (클래스 @RequestMapping("/api/v1/verify")) → 경로 일치.

**수정안**

수정 불필요. 다만 이 코드는 `window.addEventListener('message', handleMessage)` 안에서만 실행되는데, 그 메시지를 보내는 주체가 코드베이스에 존재하지 않는다(C-1.5) — 즉 이 줄은 현재 도달 불가 코드다.

---

### C-1.3. [체인 신규] NICE 본인인증 자격증명이 리터럴 플레이스홀더 — 환경변수 오버라이드 배선 없음 → 토큰 발급 자체가 실패

**🔴 잔존** · P0 · SMALL · YML/INFRA  
`aichat/src/main/resources/application.yml:63-68`

**근거**

application.yml:63-68 (working tree 기준. `git diff application.yml`은 flyway.enabled 한 줄만 변경 — 이 블록은 커밋된 상태 그대로)
```yaml
nice:
  client-id: YOUR_NICE_CLIENT_ID
  client-secret: YOUR_NICE_CLIENT_SECRET
  product-id: YOUR_NICE_PRODUCT_ID
  return-url: https://yourdomain.com/verify/callback
  api-url: https://svc.niceapi.co.kr:22001
```
`${ENV:default}` 패턴 부재 → 환경변수로 덮을 수 없다. 프로필 오버라이드도 없음: `grep -rn "nice:|NICE_CLIENT" src/main/resources/*.yml` 결과 application.yml 단 1곳(application-prod.yml/-local.yml에 nice 블록 없음).
NiceApiProperties.java:11-17 javadoc은 `client-id: ${NICE_CLIENT_ID}`를 '예시'로 적어 두었으나 **실제 yml은 그 형태가 아니다**(문서-구현 불일치).
소비처: NiceApiClient.java:49-51 `String credentials = props.getClientId() + ":" + props.getClientSecret(); ... Base64...` → NICE OAuth 토큰 발급이 401로 실패 → VerificationService.requestToken 실패 → FE는 'Verification Failed'.

**수정안**

1) application.yml:64-66을 `client-id: ${NICE_CLIENT_ID:}` / `client-secret: ${NICE_CLIENT_SECRET:}` / `product-id: ${NICE_PRODUCT_ID:}` 형태로 변경(기존 프로젝트 관례 = impl_spec_details §5 '`${ENV:default}` yml 노브').
2) ECS 태스크 정의(현 rev 45)에 해당 3개를 Secrets Manager/Parameter Store 참조로 주입.
3) 미주입 상태에서 조용히 401을 내지 않도록 NiceApiClient.getAccessToken 진입부에 blank/placeholder 검사 후 명시적 BusinessException(설정 누락) 추가 — 운영 로그에서 '설정 누락'과 'NICE 장애'가 구분돼야 한다.
※ 실제 NICE 가맹 계약 자체가 미체결이면 코드 수정만으로 해결 불가 — 행정 선행 필요.

**제품 결정 연동**: docs/16 §37 'C-1 성인인증 수리 = 0순위 … 없으면 유저가 시크릿을 못 켬 + 문서-구현 불일치로 PG 심사 즉사'와 §39 '유해매체물 표시 + PASS/NICE 성인인증 → 국내 정규 PG 결제 가능'에 직결. 시크릿 모드가 핵심 BM으로 승격된 이상 이 항목은 기능 결함이 아니라 **BM 차단 요인**이다. docs/14 행정 체크리스트와 함께 처리해야 한다.

**❓ 결정 필요**: NICE(또는 PASS) 본인인증 가맹 계약이 실제로 체결돼 발급된 client-id/secret/product-id가 존재하는가? 미체결이라면 C-1 전체가 코드 이슈가 아니라 행정 선행 항목이며, docs/16의 '0순위'는 계약 착수로 재정의돼야 한다.

---

### C-1.4. [체인 신규] nice.return-url이 `https://yourdomain.com/verify/callback` 플레이스홀더 — NICE 팝업 결과가 우리 도메인으로 돌아오지 못함

**🔴 잔존** · P0 · ONE_LINE · YML/INFRA/FE  
`aichat/src/main/resources/application.yml:67`

**근거**

application.yml:67 `  return-url: https://yourdomain.com/verify/callback`
NiceApiProperties.java:28 `private String returnUrl;` — 오버라이드 경로 없음(C-1.3과 동일 블록, 동일 문제).
FE의 NICE 팝업 폼은 AdultVerificationModal.jsx:44-46 `form.action = 'https://nice.checkplus.co.kr/CheckPlusSa498'; form.target = 'nicePopup';` 로 열리고, 인증 완료 후 NICE는 서버가 encData에 심어 보낸 returnUrl로 리다이렉트한다. 값이 `yourdomain.com`이면 팝업이 제3자 도메인으로 빠져 결과가 영영 돌아오지 않는다.

**수정안**

application.yml:67을 `return-url: ${NICE_RETURN_URL:https://lucid-chat.com/verify/callback}` 로 바꾸고 ECS 환경변수로 실도메인 주입. **단 이 값이 가리키는 FE 라우트가 실제로 존재해야 하므로 C-1.5와 반드시 한 세트로 처리** (C-1.5 없이 이 값만 고치면 팝업이 홈으로 리다이렉트되고 끝난다).

**제품 결정 연동**: C-1.3과 동일 — docs/16 §37 0순위 체인.

---

### C-1.5. [체인 신규] FE에 `/verify/callback` 라우트와 NICE_VERIFY_RESULT postMessage 브리지가 존재하지 않음 — 인증 결과 수신 불가(도달 불가 코드)

**🔴 잔존** · P0 · MEDIUM · FE/INFRA  
`FE/src/App.jsx:53-160 (라우트 정의 전체) · src/components/AdultVerificationModal.jsx:68-96`

**근거**

AdultVerificationModal.jsx:68-71 — 수신 측만 존재
```js
const handleMessage = async (event) => {
  // Validate origin in production
  if (event.data && event.data.type === 'NICE_VERIFY_RESULT') {
```
발신 측 전수 검색: `grep -rn "NICE_VERIFY_RESULT" src/ public/` → **AdultVerificationModal.jsx:71 한 줄뿐**. `window.opener.postMessage(...)`를 수행하는 콜백 페이지·정적 html이 리포지토리에 없다.
라우트 확인: `grep -n "path=" src/App.jsx` → /login, /oauth2/success, /, /story, /worlds, /archive, /studio, /first-meet, /studio/world, /theater*, /chat/:roomId, /v2/chat/:roomId, 그리고 App.jsx:160 `<Route path="*" element={<Navigate to="/" replace />} />`. **`/verify/callback` 없음** → 팝업이 그 경로로 오면 홈으로 리다이렉트되고 postMessage 없이 끝난다.
결과: C-1.2에서 픽스된 `POST /verify/success` 호출은 영원히 실행되지 않는다(도달 불가).
부수: 주석 'Validate origin in production'대로 origin 검증도 미구현 — 브리지 신설 시 함께 처리 필요.

**수정안**

FE에 팝업 전용 콜백 화면 신설:
1) `src/pages/VerifyCallbackPage.jsx` 추가 — 마운트 시 URL 쿼리/폼 파라미터에서 NICE가 돌려준 enc_data·token_version_id를 읽어 `window.opener.postMessage({ type: 'NICE_VERIFY_RESULT', encData, tokenVersionId }, window.location.origin)` 후 `window.close()`. 레이아웃 없는 최소 화면(로딩 스피너)으로.
2) App.jsx의 `<Route path="*">`(:160) **앞에** `<Route path="/verify/callback" element={<VerifyCallbackPage />} />` 추가. 로그인 가드(ProtectedRoute) 밖에 둘 것 — 팝업 컨텍스트에 토큰이 없을 수 있다.
3) AdultVerificationModal.jsx:68 handleMessage에 `if (event.origin !== window.location.origin) return;` origin 검증 추가(주석이 예고한 미구현 항목).
4) NICE가 결과를 GET 쿼리가 아닌 POST 폼으로 보내는 방식이면 SPA 라우트로 받을 수 없으므로, 백엔드에 수신 엔드포인트를 두고 거기서 FE 콜백 페이지로 302 하는 구조가 필요 — NICE 연동 스펙(CheckPlusSa498) 확인 후 (2)/(4) 중 택1.

**제품 결정 연동**: docs/16 §37 0순위 체인의 마지막 링크. C-1.1/1.2(경로 픽스)와 C-1.3/1.4(설정)를 모두 고쳐도 이것이 없으면 성인인증은 완료되지 않는다 → 시크릿 BM 전체가 열리지 않는다.

**❓ 결정 필요**: NICE CheckPlusSa498 콜백이 return-url로 GET 쿼리를 보내는지 POST 폼을 보내는지에 따라 구현이 달라진다(SPA 라우트 vs 백엔드 수신+302). 실제 연동 스펙 문서/샘플이 확보돼 있는가?

---

### C-2.a. PaymentModal — POST /payments/ready 경로 이중 프리픽스 → 404

**🔴 잔존** · P0 · ONE_LINE · FE  
`FE/src/components/PaymentModal.jsx:60-63`

**근거**

PaymentModal.jsx:60-63
```js
const prepareRes = await axios.post('/api/v1/payments/ready', {
  productType: product.type,
  targetCharacterId: product.targetCharacterId || null,
});
```
import은 PaymentModal.jsx:3 `import axios from '../api/axios';` (커스텀 인스턴스) → 최종 URL `https://api.lucid-chat.com/api/v1/api/v1/payments/ready` → 404.
대조군(정상): LucidStore.jsx:166 `const { data: order } = await api.post("/payments/ready", payload);` — 비대칭 그대로 유지됨.
백엔드: PaymentController.java:43 `@RequestMapping("/api/v1/payments")` + :54 `@PostMapping("/ready")`.

**수정안**

C-2.i(PaymentModal 자체 폐기)로 수렴시키는 것이 정답. 폐기 전 임시 완화가 필요하면 `'/api/v1/payments/ready'` → `'/payments/ready'`. 단 경로만 고쳐도 C-2.c~h 때문에 결제는 여전히 실패하므로 **부분 픽스는 하지 말 것**(고쳐진 것처럼 보이는 게 더 위험).

---

### C-2.b. PaymentModal — POST /payments/confirm 경로 이중 프리픽스 → 404 (결제 완료 후 서버 검증 유실)

**🔴 잔존** · P0 · ONE_LINE · FE  
`FE/src/components/PaymentModal.jsx:87-90`

**근거**

PaymentModal.jsx:87-90
```js
const confirmRes = await axios.post('/api/v1/payments/confirm', {
  impUid: response.imp_uid,
  merchantUid: merchantUid,
});
```
대조군: LucidStore.jsx:186 `const confirm = await api.post("/payments/confirm", {`.
백엔드: PaymentController.java:72 `@PostMapping("/confirm")`.
※ C-2.a가 먼저 404 나므로 실행상 도달하지 않지만, a만 고치면 즉시 노출되는 독립 결함이라 별도 원자로 등재. 실지출 후 검증 실패는 웹훅(PaymentController /webhook, SecurityConfig.java:64 permitAll)이 보완하나, 웹훅 자체가 B-1(imp_uid 재사용) 결함을 안고 있다.

**수정안**

C-2.i로 수렴(모달 폐기). 임시 완화 시 `'/payments/confirm'`.

---

### C-2.c. PaymentModal 카탈로그의 `LUCID_PASS_MONTHLY`가 ProductType에 존재하지 않음 → 역직렬화 실패, 게다가 400이 아닌 500

**🔴 잔존** · P0 · ONE_LINE · FE/BE  
`FE/src/components/PaymentModal.jsx:31`

**근거**

PaymentModal.jsx:31
```js
{ type: 'LUCID_PASS_MONTHLY', name: 'Lucid Pass', price: 19900, desc: 'Monthly premium', emoji: '⭐' },
```
백엔드 ProductType.java:19-32 전체 상수: ENERGY_T1/T2/T3, SECRET_PASS_24H, SECRET_UNLOCK_PERMANENT, **LUCID_PASS**, LUCID_MIDNIGHT_PASS. `LUCID_PASS_MONTHLY` 없음.
PrepareOrderRequest.java:12 `ProductType productType` — enum 타입이므로 Jackson이 역직렬화 단계에서 HttpMessageNotReadableException. GlobalExceptionHandler에 해당 핸들러가 없어(C-0.3 evidence) `@ExceptionHandler(Exception.class)`(:73) → **500**. `@Valid`의 @NotNull도 도달 전이라 400이 되지 않는다.

**수정안**

FE: C-2.i로 수렴(LucidStore.jsx:66이 이미 `type: "LUCID_PASS"`로 정상). BE 동반: C-0.3의 `@ExceptionHandler(HttpMessageNotReadableException.class)` → 400 추가로, 앞으로 어떤 클라이언트가 오타 enum을 보내도 500이 아닌 400이 되게 할 것.

**제품 결정 연동**: none — 구독 상품 자체는 docs/14 §C BM 결정(부스트 3.6-flash 치환)에서 존치가 전제.

---

### C-2.d. PaymentModal이 Lucid Pass 가격을 19,900원으로 표시 — 실제 14,900원 대비 5,000원 오표시

**🔴 잔존** · P1 · ONE_LINE · FE  
`FE/src/components/PaymentModal.jsx:31`

**근거**

FE PaymentModal.jsx:31 `price: 19900`.
BE ProductType.java:31 `LUCID_PASS("루시드 패스", 14900, 0, false),`
대조군(정상): LucidStore.jsx:66 `type: "LUCID_PASS", name: "루시드 패스", price: 14900, adultOnly: false,`
표시 경로: PaymentModal.jsx:217 `<span ...>{formatPrice(product.price)}</span>` (:121 `const formatPrice = (price) => price.toLocaleString() + 'won';`) → 화면에 `19,900won`.
※ 실제 청구액은 서버가 내려주는 `amount`(PrepareOrderResponse, PaymentService.java:84 `product.getPriceKrw()`)를 쓰므로 과다청구는 아니고 **표시가만 부풀려진 형태** — 그래도 표시가≠청구가는 전자상거래법 표시 의무 위반 소지.

**수정안**

C-2.i로 수렴(모달 폐기 시 자동 해소). 근본 대책으로는 가격을 FE 하드코딩에서 걷어내고 `GET /payments/products` 류의 서버 카탈로그 엔드포인트로 단일 소스화하는 것을 권장(LucidStore.jsx:30-80도 동일하게 하드코딩이라 같은 표류 위험을 안고 있다).

**제품 결정 연동**: none. 단 docs/14 §C BM 개편(UGC 25E·부스트 치환)에서 구독 가격이 조정되면 FE 하드코딩 2벌(PaymentModal·LucidStore)이 또 어긋난다 — 서버 카탈로그화가 블록 C와 함께 처리될 후보.

---

### C-2.e. PaymentModal이 시크릿 2종에 targetCharacterId를 전송하지 않아 서버가 항상 400

**🔴 잔존** · P0 · ONE_LINE · FE  
`FE/src/components/PaymentModal.jsx:29-30, 62`

**근거**

카탈로그 정의에 targetCharacterId 필드 자체가 없다 — PaymentModal.jsx:29-30
```js
{ type: 'SECRET_PASS_24H', name: 'Secret Night Pass', price: 2900, desc: '24h Secret Mode', emoji: '🌙' },
{ type: 'SECRET_UNLOCK_PERMANENT', name: 'Secret Unlock Key', price: 14900, desc: 'Permanent unlock', emoji: '🔑' },
```
전송부 PaymentModal.jsx:62 `targetCharacterId: product.targetCharacterId || null,` → 항상 null.
서버 요구(현재도 유효) PaymentService.java:60-75
```java
if (product == ProductType.SECRET_UNLOCK_PERMANENT) {
    if (request.targetCharacterId() == null) {
        throw new BusinessException(ErrorCode.BAD_REQUEST, "target character ID required for secret unlock");
...
if (product == ProductType.SECRET_PASS_24H && request.targetCharacterId() == null) {
    throw new BusinessException(ErrorCode.BAD_REQUEST, "target character ID required for secret pass");
```
대조군(정상): LucidStore.jsx:157-163 — 시크릿 2종일 때 selectedCharId 검사 후 `payload.targetCharacterId = selectedCharId;`

**수정안**

C-2.i로 수렴(LucidStore로 교체하면 :157-163 로직이 처리). 단 V2에서 LucidStore로 보낼 때 대상 캐릭터가 실제로 채워지는지는 C-2.k 참조.

**제품 결정 연동**: docs/16 BM 피벗으로 시크릿 접근 게이트는 user-global이 됐지만(SecretModeService.java:137-145 `hasAnyPermanentUnlock`/`hasAnyActive24hPass`), **구매 시점의 targetCharacterId 요구는 의도적으로 존치**된다 — SecretModeService.java:148-151 주석 '결제 시점에는 여전히 캐릭터 단위 레코드를 지급 트래킹용으로 생성한다. BM 피벗은 접근 게이트에서만 user-global로 적용됨'. 따라서 '서버에서 targetCharacterId 필수 조건을 없앤다'는 방향의 수정은 제품 결정과 어긋난다 — FE가 보내도록 고치는 것이 맞다.

---

### C-2.f. PaymentModal ENERGY_T3의 `+Affection Potion` 보너스 표기 — 지급 로직이 코드베이스에 존재하지 않는 허위 표기

**🔴 잔존** · P1 · ONE_LINE · FE  
`FE/src/components/PaymentModal.jsx:26`

**근거**

PaymentModal.jsx:26
```js
{ type: 'ENERGY_T3', name: 'Afternoon Tea', price: 9900, energy: 250, emoji: '🍵', bonus: '+Affection Potion' },
```
렌더 PaymentModal.jsx:213 `{product.bonus && <span className="text-purple-300 ml-1">{product.bonus}</span>}`
지급 로직 전수 검색:
- BE `grep -rn -i "Affection Potion|AFFECTION_POTION|potion" src/main/java/` → **0건**
- FE `grep -rn "Potion" src/` → **PaymentModal.jsx:26 단 1건**(자기 자신)
ProductType.java:24 `ENERGY_T3("프리미엄 애프터눈 티", 9900, 250, false)` — energyAmount 250 외 부가 지급 정의 없음.
대조군: LucidStore.jsx:42의 ENERGY_T3 항목에는 bonus 필드가 없다.

**수정안**

C-2.i로 수렴(모달 폐기 시 소멸). 별도 조치 불요 — 'Affection Potion'이라는 아이템 개념 자체가 도메인에 없으므로 지급 구현이 아니라 표기 삭제가 정답.

**제품 결정 연동**: none — docs/14 §C BM 결정 어디에도 소모성 아이템(포션) 개념이 없다. 구현이 아니라 삭제 방향 확정.

---

### C-2.g. PREMIUM_REQUIRED 유저가 사야 할 LUCID_MIDNIGHT_PASS가 PaymentModal 카탈로그에 없음 → 업셀 데드엔드

**🔴 잔존** · P0 · ONE_LINE · FE  
`FE/src/components/PaymentModal.jsx:28-32 (packages 배열) · src/pages/ChatPageV2.jsx:1824-1827`

**근거**

PaymentModal.jsx:28-32 packages 배열 = SECRET_PASS_24H / SECRET_UNLOCK_PERMANENT / LUCID_PASS_MONTHLY 3종. LUCID_MIDNIGHT_PASS 없음.
업셀 유입 경로 ChatPageV2.jsx:1824-1827
```js
} else if (err.errorCode === "PREMIUM_REQUIRED") {
  sfx.locked();
  setPaymentInitialTab("packages");
  setShowPayment(true);
```
→ packages 탭이 열리지만 정작 프리미엄 해소 상품이 없다.
BE에는 존재: ProductType.java:32 `LUCID_MIDNIGHT_PASS("루시드 미드나잇 패스", 24900, 0, true)`; SecretModeService.java:123 `if (hasMidnightPass(user.getId())) return true;` — 시크릿 접근을 여는 정식 상품.
대조군(정상): LucidStore.jsx:76 `type: "LUCID_MIDNIGHT_PASS", name: "루시드 미드나잇 패스", price: 24900, adultOnly: true,`

**수정안**

C-2.i로 수렴 — LucidStore의 pass 탭에 이미 정상 등재돼 있으므로, PREMIUM_REQUIRED 경로를 `setStoreInitialTab("pass"); setShowStore(true);`로 바꾸면 해소된다(현재 V1이 쓰는 형태와 동일: ChatPageV2.jsx:3372-3374).

**제품 결정 연동**: docs/16이 시크릿을 핵심 BM으로 승격 → LUCID_MIDNIGHT_PASS(24,900원, 전캐릭 시크릿)가 최상위 매출 상품인데 **V2 인챗 업셀 지점에서 노출조차 되지 않는다**. 매출 직결도가 docs/13 작성 시점보다 올라갔다.

---

### C-2.h. PaymentModal이 PortOne SDK를 `imp_YOUR_CODE` 플레이스홀더로 초기화

**🔴 잔존** · P0 · ONE_LINE · FE/INFRA  
`FE/src/components/PaymentModal.jsx:72`

**근거**

PaymentModal.jsx:72
```js
window.IMP.init('imp_YOUR_CODE'); // Replace with actual PortOne merchant code
```
SDK 로드는 정상: index.html:17 `<script src="https://cdn.iamport.kr/v1/iamport.js"></script>`
환경변수 미사용(`import.meta.env` 참조 없음) → 빌드 설정으로도 덮을 수 없는 리터럴.

**수정안**

C-2.i로 수렴(모달 폐기)하되, **그 순간 코드베이스에서 IMP.init이 완전히 사라지므로 C-2.j를 반드시 동반**할 것. init 코드를 옮길 때 가맹점 코드는 `import.meta.env.VITE_PORTONE_IMP_CODE`로 빼고 Vercel 환경변수로 주입한다(리터럴 금지 — 지금과 같은 표류 재발 방지).

**제품 결정 연동**: docs/16 §39 '국내 정규 PG 결제 가능' 전제. PG 가맹 계약이 실제 존재해야 코드가 발급된다 — C-1.3과 동일한 행정 선행 의존.

---

### C-2.i. ChatPageV2의 4개 결제 진입 경로가 여전히 죽은 PaymentModal로 향함 (LucidStore로 미교체)

**🔴 잔존** · P0 · SMALL · FE  
`FE/src/pages/ChatPageV2.jsx:36, 1820-1827, 2142-2147, 3431, 4646-4655, 4819-4826`

**근거**

모달 렌더 ChatPageV2.jsx:4819-4826
```jsx
{/* V2 결제 모달 — in-place 진입 (시크릿 / 에너지 분기 자동) */}
<PaymentModal isOpen={isV2 && showPayment} onClose={() => setShowPayment(false)} onPaymentComplete={handlePaymentCompleteV2} userEnergy={energy} initialTab={paymentInitialTab} />
```
진입 4경로(모두 현존):
① :1820-1823 `if (err.errorCode === "INSUFFICIENT_ENERGY") { ... setPaymentInitialTab("energy"); setShowPayment(true); }`
② :1824-1827 `else if (err.errorCode === "PREMIUM_REQUIRED") { ... setPaymentInitialTab("packages"); setShowPayment(true); }`
③ :3431 `onOpenStore={isV2 ? handleOpenStoreV2 : (tab) => { setStoreInitialTab(tab); setShowStore(true); }}` (DialogueBox 상점 CTA)
④ :4646-4652 SecretModeFlow `onOpenStore={(tab) => { setShowSecretFlow(false); if (isV2) { handleOpenStoreV2(tab || "secret"); } else { ... setShowStore(true); } }}`
허브 :2143-2147 `const handleOpenStoreV2 = useCallback((tab) => { const initialTab = (tab === "secret" || tab === "pass" || tab === "packages") ? "packages" : "energy"; setPaymentInitialTab(initialTab); setShowPayment(true); }, []);`
**갱신 사항(docs/13 대비)**: LucidStore는 ChatPageV2.jsx:4584-4601에 이미 무조건 렌더돼 있고 V2에서도 상단 💎 버튼(:3354-3358)·BoostToggle(:3370-3374)·모바일 메뉴 시트(:3943)·설정창 BoostToggle(:4059-4063)에서 도달 가능하다. 즉 **동일 화면에 정상 상점과 죽은 상점이 공존**하며, 어느 버튼을 누르느냐에 따라 결제가 되거나(단 C-2.j 제약) 404가 난다.

**수정안**

ChatPageV2.jsx에서:
1) :2143-2147 `handleOpenStoreV2` 본문을 LucidStore로 리타깃 — `const t = (tab === "secret") ? "secret" : (tab === "pass" || tab === "packages") ? "pass" : "energy"; setStoreInitialTab(t); setShowStore(true);` (LucidStore의 탭 키는 energy/secret/pass — PaymentModal의 energy/packages 2탭과 다르므로 매핑 주의).
2) :1822-1823 → `setStoreInitialTab("energy"); setShowStore(true);`
3) :1826-1827 → `setStoreInitialTab("pass"); setShowStore(true);` (LUCID_MIDNIGHT_PASS 노출 = C-2.g 해소)
4) :3431 → `onOpenStore={(tab) => { setStoreInitialTab(tab); setShowStore(true); }}` 로 V1/V2 분기 자체를 제거.
5) :4819-4826 `<PaymentModal>` 블록과 :36 import 삭제, `showPayment`/`paymentInitialTab` state(:311-312) 삭제, 파일 `src/components/PaymentModal.jsx` 삭제.
6) 삭제 전 C-2.j(IMP.init 이관)와 C-2.k(V2 캐릭터 목록) 처리 — **이 둘 없이 5)를 먼저 하면 결제가 더 나빠진다.**
7) LucidStore의 `onPaymentComplete`가 V2 상태를 갱신하도록 :4600 부근 핸들러에 `fetchStoryV2RoomDetail(roomId).then(setV2Room)`를 추가(현재 handlePaymentCompleteV2(:2150-2154)가 하던 일).

**제품 결정 연동**: docs/16이 시크릿을 핵심 BM으로 승격시켰으므로 ④(시크릿 진입) 경로의 우선순위가 docs/13 시점보다 높다. docs/14_assets §6 재작업 금지 목록에 이 항목은 없다(의도된 트레이드오프 아님) — 정상 수정 대상.

---

### C-2.j. [체인 신규] LucidStore가 `IMP.init`을 한 번도 호출하지 않음 — PaymentModal 제거 시 코드베이스에서 PortOne 초기화가 완전히 소멸

**🔴 잔존** · P0 · SMALL · FE/INFRA  
`FE/src/components/LucidStore.jsx:166-181`

**근거**

`grep -rn "IMP.init|IMP\b" src/ index.html` 전수 결과:
- LucidStore.jsx:168 `if (!window.IMP) {` (존재 검사만)
- LucidStore.jsx:174 `window.IMP.request_pay(`
- PaymentModal.jsx:68 `if (!window.IMP) {`
- PaymentModal.jsx:72 `window.IMP.init('imp_YOUR_CODE');`  ← **유일한 init**
- PaymentModal.jsx:74 `window.IMP.request_pay(`
index.html:17에 SDK는 로드되지만 init 호출 없음. 아임포트 v1 SDK는 `IMP.init(가맹점코드)` 없이 `request_pay`를 호출하면 가맹점 식별 불가로 실패한다.
→ 현재 LucidStore(정상 경로) 결제도 실질 불능이며, C-2.i에서 PaymentModal을 삭제하면 init 코드가 리포지토리에서 사라진다.

**수정안**

init을 컴포넌트가 아니라 앱 부트스트랩으로 올린다:
1) `src/main.jsx`(또는 App.jsx 최상단 useEffect)에서 SDK 로드 확인 후 `window.IMP?.init(import.meta.env.VITE_PORTONE_IMP_CODE)` 1회 호출.
2) Vercel 환경변수 `VITE_PORTONE_IMP_CODE`에 실 가맹점 코드 주입(.env/.env.development에도 dev 값 추가).
3) LucidStore.jsx:168의 `if (!window.IMP)` 가드는 유지하되 에러 카피를 '결제 모듈 초기화 실패'로 구분.
4) C-2.i의 PaymentModal 삭제는 이 작업 **이후**에 수행.

**제품 결정 연동**: docs/16 §39 국내 정규 PG 결제 전제에 직결. C-1.3/C-2.l과 함께 'PG 가맹 계약 → 자격증명 주입' 한 세트로 처리해야 하며, 계약이 없으면 코드 수정만으로 결제가 살아나지 않는다.

**❓ 결정 필요**: PortOne(아임포트) 가맹 계약이 체결돼 실 가맹점 코드(imp_xxxxxxxx)가 발급돼 있는가? 미발급이면 C-2 전체가 '코드 정리'까지만 가능하고 결제 개통은 행정 대기다.

---

### C-2.k. [체인 신규] V2에서 `characters`가 항상 빈 배열 — LucidStore의 대상 캐릭터 선택 UI가 렌더되지 않아 유저가 시크릿 해금 대상을 고를 수 없음

**🔴 잔존** · P2 · SMALL · FE  
`FE/src/pages/ChatPageV2.jsx:198, 1282, 4593-4594 · src/components/LucidStore.jsx:486`

**근거**

state 선언 ChatPageV2.jsx:198 `const [characters, setCharacters] = useState([]);`
채우는 곳은 **V1 init 분기 단 1곳** — ChatPageV2.jsx:1272-1282
```js
// V1 init (기존 흐름 — 무수정)
const [roomRes, userRes, logsRes, charsRes] = await Promise.all([ ... api.get("/lobby/characters").catch(() => ({ data: [] })), ]);
setCharacters(charsRes.data || []);
```
V2 분기(:1126-1147)는 `fetchStoryV2RoomDetail` 성공 시 V1 init을 건너뛰므로 setCharacters가 호출되지 않는다 → V2에서 characters === [].
소비처 LucidStore.jsx:486 `{characters.length > 0 && (` → 대상 캐릭터 선택 블록 전체가 미렌더.
완화 요인: LucidStore.jsx:130 `useState(currentCharacterId)` + :140 `if (currentCharacterId) setSelectedCharId(currentCharacterId);`, 그리고 ChatPageV2.jsx:4594 `currentCharacterId={roomInfo?.characterId}` — V2 roomInfo는 :1136-1138에서 `characterId: firstHeroine.characterId`로 채워지므로 **결제 자체는 성립하되 '첫 히로인'에 조용히 귀속**된다. 유저가 어느 히로인을 해금하는지 알 수도, 고를 수도 없다.

**수정안**

두 안 중 택1.
(a) 최소: ChatPageV2 V2 init 분기(:1131 부근)에서 `setCharacters((v2Detail.heroines || []).map(h => ({ id: h.characterId, name: h.name, thumbnailUrl: h.thumbnailUrl })))` — LucidStore.jsx:493-508이 기대하는 `{id, name, thumbnailUrl}` 형태로 매핑하면 방에 등장하는 히로인 중에서 고를 수 있게 된다.
(b) 권장: docs/16 BM 피벗이 접근 게이트를 user-global로 만들었으므로, 시크릿 상품의 대상 선택 UI를 아예 없애고 FE가 '현재 화자'(ChatPageV2.jsx:2091-2096 handleHeroineSelectedV2가 갱신하는 roomInfo.characterId)를 자동 첨부하도록 단순화. 서버의 targetCharacterId 요구는 지급 트래킹용이므로 값만 채워지면 된다.
(b)를 택하면 LucidStore.jsx:486-512 블록과 ChatPageV2.jsx의 characters/`/lobby/characters` 호출을 함께 정리할 수 있다.

**제품 결정 연동**: docs/16 시크릿 user-global 피벗과 정면으로 맞물린다. SecretModeService.java:148-151이 '결제 시점 캐릭터 단위 레코드는 지급 트래킹용'이라 못박았으므로, 유저에게 대상 선택을 시키는 UI 자체가 이제는 **혼란 유발 잔재**일 수 있다(유저는 '이 캐릭터만 열린다'고 오해하는데 실제로는 전 캐릭터가 열린다 — 역방향 오표시). (b)안이 제품 결정과 더 정합.

**❓ 결정 필요**: 시크릿 접근이 user-global이 된 지금, 구매 화면에서 '대상 캐릭터 선택'을 계속 노출할 것인가? 노출하면 '이 캐릭터만 해금된다'는 오해를 낳고(실제로는 전 캐릭터 해금), 감추면 영구해금 14,900원의 체감 가치 설명이 달라진다 — 카피·가격 정책과 함께 판단 필요.

---

### C-2.l. [체인 신규] PortOne 서버측 자격증명(api-key/api-secret)이 리터럴 플레이스홀더 — 환경변수 오버라이드 배선 없음 → 결제 서버 검증 불능

**🔴 잔존** · P0 · SMALL · YML/INFRA  
`aichat/src/main/resources/application.yml:70-73`

**근거**

application.yml:70-73
```yaml
portone:
  api-key: YOUR_PORTONE_API_KEY
  api-secret: YOUR_PORTONE_API_SECRET
  api-url: https://api.iamport.kr
```
`${ENV:default}` 패턴 부재, 프로필 오버라이드 없음(`grep -rn "portone:|PORTONE" src/main/resources/*.yml` → application.yml 1곳뿐).
PortOneProperties.java:12-14 javadoc은 `api-key: ${PORTONE_API_KEY}`를 예시로 적었으나 실제 yml은 그 형태가 아님(NICE와 동일한 문서-구현 불일치 패턴).
소비처: PortOneClient가 이 값으로 아임포트 토큰을 받아 결제 대사(PaymentService.verifyAndDeliver의 CLIENT/WEBHOOK 검증)를 수행 → 실패 시 정상 결제도 지급되지 않는다.

**수정안**

application.yml:71-72를 `api-key: ${PORTONE_API_KEY:}` / `api-secret: ${PORTONE_API_SECRET:}` 로 바꾸고 ECS 태스크 정의에 Secrets Manager 참조 주입. NiceApiClient와 마찬가지로 PortOneClient 진입부에 blank/placeholder 검사 → 명시적 설정누락 예외를 추가해 '설정 누락'과 'PG 장애'를 로그에서 구분할 것. C-1.3과 완전히 동일한 작업 패턴이므로 한 커밋으로 묶는 것을 권장.

**제품 결정 연동**: docs/16 §39 '국내 정규 PG 결제 가능'의 실행 전제. C-2.h/C-2.j(FE 가맹점 코드)와 같은 계약에서 나오는 값이므로 세트로 처리.

---

### C-3.1. SecretModeStatus가 `canAccess`를 직렬화하지 않아 결제 완료 유저가 시크릿 토글 불가

**✅ 수정됨** · P0 · N/A · -  
`aichat/src/main/java/com/spring/aichat/service/payment/SecretModeService.java:329-338 (BE 픽스) · C:/Users/zapza/Desktop/LucidChat-Front/LucidChat-Front/src/components/SecretModeFlow.jsx:88 (FE 원문 유지)`

**근거**

백엔드가 `canAccess`를 **파생 메서드에서 명시 record 컴포넌트로 승격**해 해결 — SecretModeService.java:326-338
```java
/**
 * [docs/13 C-3 픽스] {@code canAccess}를 명시 컴포넌트로 승격 — record 파생 메서드는
 * Jackson이 직렬화하지 않아 FE(SecretModeFlow)가 undefined를 읽던 확정 버그.
 */
public record SecretModeStatus(
    boolean isAdult,
    boolean personaAdult,    // [블록 B] 페르소나 프로필 19+ 여부
    boolean hasMidnightPass,
    boolean hasPermanentUnlock,
    boolean has24hPass,
    boolean canAccess,
    String accessReason
) {}
```
생산부 SecretModeService.java:318-324 — `boolean canAccess = personaAdult && entitled;` 를 6번째 인자로 실제 전달, reason은 `entitled ? "GRANTED" : "NEED_PURCHASE"`.
FE는 원문(`data.canAccess`) 그대로 유효 — SecretModeFlow.jsx:86-95
```js
const { data } = await api.get("/users/secret-status");
setAccessStatus(data);
if (data.canAccess) { setStep("granted"); setTimeout(() => { onGranted?.(); onClose(); }, 1500); }
else if (data.accessReason === "PERSONA_UNDERAGE") { ... }
```
엔드포인트 정합: UserController.java:118-127 `@GetMapping("/secret-status")` → 1-arg user-global getStatus. FE는 characterId 미전송(:83 주석), 컨트롤러는 `@RequestParam(required = false)`.
토글 반영: ChatPageV2.jsx:4636-4643 onGranted → :2129-2136 `await api.patch(\`/chat/rooms/${roomId}/secret-mode\`, { enabled: true })` — 백엔드 ChatController.java:140 `@PatchMapping("/rooms/{roomId}/secret-mode")` 존재.
픽스 커밋: 블록 B `cab6b3e`.
※ docs/13의 대안(`accessReason === "GRANTED"` 비교)이 아니라 백엔드 직렬화 방식으로 해결됐다 — FE를 그 대안대로 고치면 오히려 PERSONA_UNDERAGE 분기(:95)와 충돌하므로 **FE는 건드리지 말 것**.

**수정안**

수정 불필요. 회귀 방지 관점에서 SecretModeStatus record에 컴포넌트를 추가/삭제할 때 `canAccess`가 파생 메서드로 되돌아가지 않도록 주의(주석 :326-329가 이미 경고 중).

**제품 결정 연동**: docs/16 §37이 C-3를 C-1 동반 수리 대상으로 지정했는데 이미 완료. 시크릿 진입 체인 3구간(인증→결제→토글) 중 **토글 구간만 온전**한 상태이므로, docs/16의 0순위는 C-1(인증)·C-2(결제)로 좁혀진다.

---

## D. P1 자산 손실·데이터 정합  (49건)

### D-1.1. refundEnergy가 consumeEnergy의 역연산이 아님 — 환불할 때마다 유료 에너지가 무료로 강제 변환되어 순소멸

**🔴 잔존** · P1 · MEDIUM · BE/DB_MIGRATION  
`aichat/src/main/java/com/spring/aichat/domain/user/User.java:187-193`

**근거**

docs/13 라인 번호가 지금도 정확히 일치(블록 A/B가 이 파일의 에너지 로직 미변경).

User.java:165-179 (차감 — free 우선, 부족분만 paid):
```java
public void consumeEnergy(int amount) {
    int total = this.freeEnergy + this.paidEnergy;
    if (total < amount) { throw new InsufficientEnergyException(...); }
    if (this.freeEnergy >= amount) { this.freeEnergy -= amount; }
    else { int remaining = amount - this.freeEnergy; this.freeEnergy = 0; this.paidEnergy -= remaining; }
}
```
User.java:187-193 (환불 — free 우선 복원):
```java
public void refundEnergy(int amount) {
    if (amount <= 0) return;
    int freeSpace = getFreeEnergyMax() - this.freeEnergy;
    int toFree = Math.min(amount, freeSpace);
    this.freeEnergy += toFree;
    this.paidEnergy += (amount - toFree);
}
```
반환형 void라 호출부가 유료 차감분을 알 방법이 없다. Javadoc(:184-185) "consumeEnergy의 역연산 … 정확한 복원이 보장됨"은 거짓.

[소멸 성립 증거] getFreeEnergyMax()=비구독 30/구독 100(User.java:157-159), 스케줄러가 free를 그 상한까지 공짜로 채운다 — EnergyRegenScheduler.java:27-31 `/** 비구독자: 10분마다 +1, max 30 */ @Scheduled(fixedRate = 10*60*1000) public void regenFreeUsers() { int count = userRepository.regenFreeUserEnergy(); ...}`. 따라서 free로 돌아온 분량은 경제 가치 0.

[재현] 비구독·free=0·paid=50 유저가 씬 일러(10E) 요청 → consume: free=0/paid=40 → 실패 환불: freeSpace=30, toFree=10 → free=10/paid=40. 총량 50으로 같아 보이나 free 10은 100분이면 저절로 찼을 분량 → **결제한 10E 소멸**.

[역방향 위험 동시 존재] 단순히 paid 우선 복원으로 뒤집으면 free만 쓰던 유저가 실패를 유발해 free→paid 승급(무제한·미소멸 자산화)을 파밍할 수 있다. 그래서 '분할분 추적' 없이 한 줄로 못 고친다.

**수정안**

차감 시 유료 분할분을 반환하고 환불은 그 분할을 그대로 되돌린다.

1) `User.consumeEnergy(int)` 반환형을 record `EnergySplit(int fromFree, int fromPaid)`로 변경:
```java
public EnergySplit consumeEnergy(int amount) {
    ... // 기존 검증
    int fromFree = Math.min(this.freeEnergy, amount);
    int fromPaid = amount - fromFree;
    this.freeEnergy -= fromFree; this.paidEnergy -= fromPaid;
    return new EnergySplit(fromFree, fromPaid);
}
```
2) `refundEnergy(int)`를 **삭제**하고 `refundEnergy(EnergySplit)`로 교체:
```java
public void refundEnergy(EnergySplit s) {
    if (s == null) return;
    this.paidEnergy += s.fromPaid();
    this.freeEnergy = Math.min(getFreeEnergyMax(), this.freeEnergy + s.fromFree());
}
```
1-arg 시그니처를 남기지 말 것 — 남기면 호출부가 조용히 낡은 경로로 컴파일되어 회귀한다. 7개 호출부 전부 컴파일 에러로 드러나게 하는 것이 목적.
3) 지연 환불 4곳(D-1.2/D-1.6/D-1.7/D-1.8)용 유료 분할분 영속: Flyway **V30**(에너지 분할 묶음 · 2026-08-26 정정 · D-33 — 앞을 V25 블록 B / V26·V27 블록 D / V28 결제 / V29 구독이 점유. 에너지 분할 3컬럼은 **한 파일에 묶고**, 착수 시점의 다음 가용 번호로 확정할 것)으로 `scene_illustrations.energy_charged_paid`, `character_creation_jobs.energy_charged_paid`, `ugc_world_creation_jobs.energy_charged_paid` (INT NOT NULL DEFAULT 0) 추가. `chargeEnergy(int)` → `chargeEnergy(int amount, int paidPortion)`으로 확장(CharacterCreationJob.java:339-341, UgcWorldCreationJob.java:215-217은 `+=` 누산이라 그대로 누산).
4) 기존 행(default 0)은 전액 free 환불로 폴백 — 신규 손실만 차단하는 보수적 처리.
5) 회귀 테스트: free=0/paid=N에서 각 환불 경로 후 paid 정확 복원 / free만 쓴 경우 paid 미증가 — 2방향 단위 테스트.

**제품 결정 연동**: none — 블록 D(엔딩·업적 게이트 오프, V1 STORY 트랙 제거, 복장/장소 해금 오프)와 무관한 결제 자산 정합 문제. 오히려 docs/16이 시크릿 모드를 핵심 BM으로 승격시켰으므로 유료 에너지 소각은 환불 클레임·PG 심사 리스크로 가중된다. 단 블록 D로 V1 STORY 트랙과 레거시 캐릭터 일러 트랙이 제거되면 호출부 수가 줄어 수정 범위는 작아진다.

**❓ 결정 필요**: 무료 분할분 환불이 상한을 넘길 때(예: 지연 환불 시점에 free가 이미 30) 초과분을 (a) 버릴 것인지 (b) paid로 승급시킬 것인지. (a)는 지연 환불에서 유저가 손해를 보고, (b)는 free→paid 파밍면이 생긴다. 유료 일러/UGC 잡처럼 환불이 수 분~수 시간 뒤 오는 경로에서 실제로 갈리는 문제라 정책 판단 필요.

---

### D-1.2. [호출부 1/7] 수동 씬 일러 실패 환불 — 지연 환불이라 유료 소각 폭이 가장 큼(10E/건)

**🔴 잔존** · P1 · SMALL · BE/DB_MIGRATION  
`aichat/src/main/java/com/spring/aichat/service/illustration/scene/SceneRenderWriteService.java:58`

**근거**

SceneRenderWriteService.java:53-68 (`refundManualCharge`, `failRender`(:40-49)에서 호출):
```java
private void refundManualCharge(SceneIllustration s) {
    try {
        userRepository.findById(s.getRequestedBy()).ifPresentOrElse(user -> {
            user.refundEnergy(s.getEnergyCharged());   // ← :58 분할분 없이 총액만
            s.markRefunded();
            cacheService.evictUserProfile(user.getUsername());
```
환불 원천이 `SceneIllustration.energyCharged`(도메인 :77, `energyRefunded` :81)뿐이라 유료/무료 비율 정보가 애초에 저장돼 있지 않다.

[지연이 손실을 키우는 이유] 이 경로는 외부 렌더 실패 콜백 시점(요청 후 수십 초~수 분)에 돈다. 그 사이 free가 낮으면 10E 전부가 free로 흡수돼 통째로 소각된다. 씬 일러는 1회 10E로 이 절 최대 단가.

**수정안**

`SceneIllustration`에 `energyChargedPaid` 추가(Flyway **V30** 에너지 분할 묶음 — D-1.1과 같은 파일. 2026-08-26 정정 · D-33). 차감 지점 SceneRequestService.java:137 `u.consumeEnergy(cost)`의 반환 `EnergySplit`을 `SceneIllustration.createPending(...)`(도메인 :117-121)에 함께 저장. `refundManualCharge`에서 `user.refundEnergy(new EnergySplit(s.getEnergyCharged()-s.getEnergyChargedPaid(), s.getEnergyChargedPaid()))`. 기존 행은 paid=0 폴백.

**제품 결정 연동**: none — 씬 일러는 §G #6에서 '일원화 후 존속'(레거시 캐릭터 일러 트랙을 흡수하는 쪽)으로 확정된 트랙이라 블록 D로 사라지지 않는다. docs/16 이미지 노드 수위 확정으로 트래픽이 늘 경로다.

---

### D-1.3. [호출부 2/7] 씬 일러 동기 요청 실패 환불 — 유료 분할 소실

**🔴 잔존** · P1 · SMALL · BE  
`aichat/src/main/java/com/spring/aichat/service/illustration/scene/SceneRequestService.java:249`

**근거**

SceneRequestService.java:246-254:
```java
/** 동기 실패 환불 — 유저 id 직접 조회(V2 보상 경로의 room 경유 조회 버그 재발 방지). */
private void refund(Long userId, int amount, String username) {
    try {
        txTemplate.execute(status -> {
            userRepository.findById(userId).ifPresent(u -> u.refundEnergy(amount));  // ← :249
            return null;
        });
```
호출부는 :168, :171 두 군데. 차감은 같은 클래스 :137 `u.consumeEnergy(cost)`.

동일 요청 내 즉시 환불이라 DB 영속 없이 로컬 변수로 분할분을 전달할 수 있다 — 7곳 중 가장 쉬운 축.

**수정안**

SceneRequestService.java:137에서 `EnergySplit split = u.consumeEnergy(cost)`로 받아 호출 스코프 변수에 보관하고 `refund(Long userId, EnergySplit split, String username)`으로 시그니처 변경. :168/:171 호출부 2곳 동시 수정. DB 변경 불필요.

---

### D-1.4. [호출부 3/7] V2 스토리 스트림 보상(compensateEnergy) — 유료 분할 소실

**🔴 잔존** · P1 · SMALL · BE  
`aichat/src/main/java/com/spring/aichat/service/story/ChatStreamServiceV2.java:1282`

**근거**

ChatStreamServiceV2.java:1277-1289:
```java
void compensateEnergy(Long userId, int amount, String username) {
    try {
        txTemplate.execute(status -> {
            User user = userRepository.findById(userId)
                .orElseThrow(() -> new NotFoundException("User not found"));
            user.refundEnergy(amount);   // ← :1282
            userRepository.save(user);
```
`70ff23a`는 이 메서드의 **유저 오조회**(room 경유)를 고쳤을 뿐 refundEnergy 시맨틱은 손대지 않았다 — D-1은 그 커밋과 중복이 아니다.

차감은 :195 `room.getUser().consumeEnergy(cost)`, 금액은 `JpaPreResult.energyCost`(record 정의 :129)와 `RollbackContext.energyCost`(:131)로 운반된다. 두 record에 분할분 필드만 추가하면 된다. 부스트 모드에서 cost가 커질수록 턴당 소각액도 커진다.

**수정안**

ChatStreamServiceV2.java:191-199 TX-1에서 `EnergySplit split = room.getUser().consumeEnergy(cost)`로 받아 `JpaPreResult`(:129)에 `EnergySplit split` 필드 추가 → `RollbackContext`(:131)에 전파 → `compensateEnergy(Long, EnergySplit, String)`로 변경. `compensateFullRollback`(:1292-1299)은 ctx에서 그대로 넘긴다. 오프닝 경로(:328 `new RollbackContext(userId, username, 0, null)`)는 `EnergySplit.ZERO`로 대체하고 refundEnergy에 zero 가드를 남겨 기존 no-op 동치 유지. 기존 테스트 ChatStreamServiceV2CompensationTest.java:136(`refundEnergy(0)은 내부 가드로 no-op`)도 함께 갱신.

**제품 결정 연동**: none — V2 STORY는 §G #2에서 V1 STORY를 대체하는 존속 트랙으로 확정. 블록 D 이후에도 남는다.

---

### D-1.5. [호출부 4/7] V1 채팅 스트림 보상(compensateEnergy) — 유료 분할 소실 (SANDBOX 메인 경로)

**🔴 잔존** · P1 · SMALL · BE  
`aichat/src/main/java/com/spring/aichat/service/stream/ChatStreamService.java:1319`

**근거**

ChatStreamService.java:1314-1327:
```java
private void compensateEnergy(Long userId, int cost, String username) {
    try {
        txTemplate.execute(status -> {
            User user = userRepository.findById(userId)
                .orElseThrow(() -> new NotFoundException("User not found"));
            user.refundEnergy(cost);   // ← :1319
            userRepository.save(user);
```
이 한 메서드를 **에너지 차감이 있는 SSE 엔트리 5곳 전부**가 공유한다 — 직접 호출부 :234, :535, :647, :769, :1629, `compensateFullRollback`(:1329-1335) 경유 :346, :581, :693, :813, :1681. 차감은 :210, :517, :626, :755, :1586.

빈도 기준 이 절 최대 노출면이다(모든 SANDBOX 턴이 여기를 지난다).

**수정안**

ChatStreamService의 `JpaPreResult`/`RollbackContext` record에 `EnergySplit` 필드를 추가하고 5개 TX-1(:210/:517/:626/:755/:1586)에서 `consumeEnergy` 반환값을 record에 실어 `compensateEnergy(Long, EnergySplit, String)`로 넘긴다. `compensateFullRollback`(:1329)은 ctx 경유라 자동 전파. 호출부가 10곳이지만 record 필드 1개 추가로 일괄 해결된다.

**제품 결정 연동**: none — §G #2가 제거하는 건 'V1 STORY 트랙'이고 ChatPage는 SANDBOX 전용으로 존치 선언. 이 메서드는 블록 D 이후에도 메인 채팅 경로다. 다만 STORY 분기의 '에너지 2배 계산 제거'가 §G #2에 포함돼 있어 cost 산출부(BoostModeResolver.resolveEnergyCost) 수정과 시기를 맞추면 회귀 테스트를 한 번에 돌릴 수 있다.

---

### D-1.6. [호출부 5/7] UGC 캐릭터 잡 실패 전액 환불(failAndRefund) — 최대 20E+ 유료 소각

**🔴 잔존** · P1 · SMALL · BE/DB_MIGRATION  
`aichat/src/main/java/com/spring/aichat/service/ugc/UgcPipelineWorker.java:800`

**근거**

UgcPipelineWorker.java:791-806:
```java
public void failAndRefund(Long jobId, String reason) {
    txTemplate.executeWithoutResult(tx -> {
        CharacterCreationJob job = jobRepository.findByIdForUpdate(jobId).orElse(null);
        if (job == null || job.getStatus().isTerminal()) return;
        job.fail(reason);
        int refund = job.getEnergyCharged();
        if (refund > 0) {
            userRepository.findById(job.getUserId()).ifPresent(user -> {
                user.refundEnergy(refund);   // ← :800
```
`CharacterCreationJob.chargeEnergy`(도메인 :339-341)는 `this.energyCharged += amount` 누산기이고 유료 분할분을 전혀 기록하지 않는다.

[소각 규모] 단계 과금 합계 20E(application.yml:190-193 stage-start 6 / standing 4 / emotions 8 / finalize 2) + 리롤 누적(golden 2, base 2, emotion 2 각 회당). 비구독 상한 30 대비 20E는 거의 전량 소각.
주석(:788-789)이 "V1 ChatStreamService 보상 패턴"을 따랐다고 명시 — 결함도 그대로 복제됐다.

**수정안**

`CharacterCreationJob`에 `energyChargedPaid` 추가(Flyway **V30** 에너지 분할 묶음 — 2026-08-26 정정 · D-33). `chargeEnergy(int)` → `chargeEnergy(int amount, int paidPortion)` 확장(누산 그대로). 차감 지점 전부(CharacterCreationService.java:249 base 리롤, :396 golden 리롤, :435 emotion 리롤, 단계 진입 차감들)에서 `consumeEnergy` 반환 분할분을 함께 누산. `failAndRefund`는 `user.refundEnergy(new EnergySplit(job.getEnergyCharged()-job.getEnergyChargedPaid(), job.getEnergyChargedPaid()))`. 기존 잡은 paid=0 폴백.

**제품 결정 연동**: none — UGC는 docs/14 BM(25E UGC)의 핵심이라 §G 처분 대상이 아니다.

---

### D-1.7. [호출부 6/7] UGC 월드 잡 실패 전액 환불(failAndRefund) — 최대 10E+ 유료 소각

**🔴 잔존** · P1 · SMALL · BE/DB_MIGRATION  
`aichat/src/main/java/com/spring/aichat/service/ugc/UgcWorldPipelineWorker.java:426`

**근거**

UgcWorldPipelineWorker.java:417-433:
```java
/** 파이프라인 귀책 실패 — 잡 FAILED + 누적 에너지 전액 환불 ({@link UgcPipelineWorker#failAndRefund} 동형). */
public void failAndRefund(Long jobId, String reason) {
    ...
        int refund = job.getEnergyCharged();
        if (refund > 0) {
            userRepository.findById(job.getUserId()).ifPresent(user -> {
                user.refundEnergy(refund);   // ← :426
```
`UgcWorldCreationJob.chargeEnergy`(도메인 :215-217)도 총액 누산기뿐.

[소각 규모] 기본 패키지 10E(application.yml:217) + 리롤 1E×n(:218). application.yml:220 주석이 "fal 전용이라 웹훅/폴링 폴백 없음 — 무진행 PROCESSING 잡 회수/환불 기준"이라 명시 — 스테일 스윕 환불이 정상 운용 경로라는 뜻이고, 곧 이 환불이 자주 돈다 = 소각이 상시화된다.

**수정안**

D-1.6과 동일 패턴. `UgcWorldCreationJob.energyChargedPaid` 추가(Flyway **V30** 에너지 분할 묶음 — 2026-08-26 정정 · D-33), `chargeEnergy(int, int)` 확장, 차감 지점(UgcWorldService.java:275-278 rerollAsset, 기본 패키지 차감)에서 분할분 누산, `failAndRefund`에서 분할 환불.

---

### D-1.8. [호출부 7/7] 실패 장소 삭제 1E 환불 — 원차감 분할을 모르는 채 정액 환불 (docs/13 미열거)

**🔴 잔존** · P3 · SMALL · BE/DB_MIGRATION  
`aichat/src/main/java/com/spring/aichat/service/ugc/UgcWorldService.java:517`

**근거**

UgcWorldService.java:505-522:
```java
/** 실패 장소 삭제 — 1E 환불 (생성 실패 귀책은 파이프라인). */
public void deleteFailedLocation(String username, Long worldId, String locationKey) {
    txTemplate.executeWithoutResult(tx -> {
        ...
        locationRepository.delete(loc);
        User user = findUser(username);
        user.refundEnergy(props.world().reroll());   // ← :517 정액 1E, 원차감 분할 불명
        userRepository.save(user);
```
대응 차감은 :475 `owner.consumeEnergy(props.world().reroll()); // 1E — 부족 시 차감 전 예외`. 원차감 시점과 환불 시점 사이에 임의의 시간이 흐르므로(유저가 실패 장소를 나중에 지운다) 분할 정보가 완전히 유실된다.

건당 1E로 금액은 작지만 **호출부 전수 열거 요구**에 따라 누락 없이 올린다. D-1.1의 시그니처 변경 시 이 지점이 컴파일 에러로 드러나므로 반드시 함께 처리해야 한다.

**수정안**

`UgcWorldLocation`(또는 장소 추가 시 기록되는 과금 레코드)에 유료 분할분 1필드를 남기고 삭제 시 그대로 환불. 1E 단위라 별도 컬럼이 과하다고 판단되면 D-1.1의 openQuestion 정책(초과분 처리)에 따라 '분할 미상 → 전액 paid 복원' 또는 '전액 free 복원'을 **명시적으로 선택**하고 코드 주석으로 근거를 남길 것. 암묵적 현행 유지는 금지 — D-1.1 수정 후에도 유일하게 남는 시맨틱 구멍이 된다.

**❓ 결정 필요**: 1E짜리 단발 환불에 분할 추적 컬럼을 붙일 가치가 있는가, 아니면 '분할 미상 환불은 paid 우선'이라는 전역 폴백 정책 하나로 덮을 것인가. 후자를 택하면 D-1.1이 막으려는 free→paid 파밍면이 이 경로로 소량 열린다(실패 장소를 반복 생성·삭제).

---

### D-2.a. V2 스트림 최외곽 catch에 보상 부재 — 라우팅/액션 전처리 예외 시 턴 에너지 전액 소멸 + 고아 USER 로그

**🔴 잔존** · P1 · SMALL · BE  
`aichat/src/main/java/com/spring/aichat/service/story/ChatStreamServiceV2.java:280-283`

**근거**

라인 번호가 docs/13과 정확히 일치. `70ff23a`는 이 지점을 손대지 않았다(그 커밋은 compensateEnergy 오조회 + compensateFullRollback 환불 누락 2건 — 둘 다 내부 catch 경로).

ChatStreamServiceV2.java:280-283:
```java
} catch (Exception e) {
    log.error("❌ [V2-STREAM] Unexpected error | roomId={}", roomId, e);
    sendSseError(emitter, "UNEXPECTED_ERROR", "예기치 않은 오류가 발생했습니다.");
}
```
로그 + SSE만. `compensateEnergy`/`compensateFullRollback` 호출이 없다.

[보상 없는 구간 실측 — 차감 이후 최외곽 catch로만 빠지는 코드]
- :195 `room.getUser().consumeEnergy(cost)` (TX-1 커밋)
- :203-211 인젝션 체크(`injectionGuard.checkChatMessage`, `moderationEventService.recordInjection` — DB 쓰기)
- :215 `buildSystemActionInjection(jpa.room(), actionType, request.actionPayload())` ← 유저 액션 페이로드를 파싱하는 지점
- (:223-227 Mongo USER 로그 저장 실패만 `compensateEnergy`(:224)로 보호)
- :233 `routingService.route(jpa.room(), userMessage)` ← rollbackCtx 생성(:229) 이후인데도 어떤 catch에도 안 감김
:236 `streamLlmAndParseV2(... rollbackCtx)`부터야 보상이 붙는다.

즉 :195~:233 사이의 모든 예외가 최외곽으로 떨어져 **에너지 소멸 + (:223 이후라면) Mongo USER 로그 고아화**를 동시에 낸다.

[반대로 환불하면 안 되는 구간도 같은 catch에 섞여 있다] :258 TX-2 커밋 이후 :260-278(ASSISTANT 로그 저장, 오프스크린 알림, 동적 배경, final_result 전송, 후처리)의 예외도 이 catch로 온다. 여기서 무조건 환불하면 스탯이 반영된 턴을 공짜로 주게 된다 — '최외곽에 compensateFullRollback 한 줄' 식 수정은 오답이다.

**수정안**

보상 여부를 **진행 단계 상태 변수**로 판정한다. `sendMessageStream` 시작부에 플래그를 두고 최외곽 catch에서 분기:
```java
boolean charged = false;          // TX-1 커밋 직후 true
RollbackContext rollbackCtx = null;
boolean committed = false;        // TX-2 성공 직후 true
...
} catch (Exception e) {
    log.error("❌ [V2-STREAM] Unexpected error | roomId={}", roomId, e);
    if (!committed) {
        if (rollbackCtx != null) compensateFullRollback(rollbackCtx);        // 로그 저장 이후 → 로그 삭제 + 환불
        else if (charged)        compensateEnergy(jpa.userId(), jpa.split(), jpa.username()); // 로그 저장 전 → 환불만
    }
    sendSseError(emitter, "UNEXPECTED_ERROR", "예기치 않은 오류가 발생했습니다.");
}
```
- `charged=true`는 :199 직후(TX-1 커밋 후) 세팅. `jpa`를 catch에서 보려면 try 밖 로컬로 승격 필요.
- `committed=true`는 :258 `response = txTemplate.execute(...)` 성공 직후 세팅.
- 이러면 :195~:233은 전액 보상, TX-2 이후 실패는 무보상(현행 유지)으로 정확히 갈린다.
- 추가로 :233 `routingService.route`를 :236의 보상 구간 안으로 밀어넣으면(try 블록 재배치) 방어가 이중이 된다.
- 회귀 테스트: `routingService.route`가 throw하도록 목킹 → 유저 에너지 불변 + Mongo USER 로그 미잔류 검증(ChatStreamServiceV2CompensationTest에 케이스 추가).

**제품 결정 연동**: none — V2 STORY는 §G #2에서 V1 STORY를 대체하는 존속 트랙. 블록 D로 사라지지 않고, docs/16의 시크릿 BM이 V2 STORY 위에 얹히므로 오히려 우선도가 올라간다.

---

### D-2.b. V1 sendMessageStream 최외곽 catch에 환불 부재 — SANDBOX 메인 채팅 턴당 소멸

**🔴 잔존** · P1 · SMALL · BE  
`aichat/src/main/java/com/spring/aichat/service/stream/ChatStreamService.java:488-491`

**근거**

docs/13의 :486에서 2줄 밀린 :488(블록 A/B가 이 파일을 직접 수정하진 않았고 그 이전 산발 픽스로 이동).

ChatStreamService.java:488-491:
```java
} catch (Exception e) {
    log.error("❌ Unexpected error | roomId={}", roomId, e);
    sendSseError(emitter, "UNEXPECTED_ERROR", "예기치 않은 오류가 발생했습니다.");
}
```

[보상 없는 구간]
- :210 `room.getUser().consumeEnergy(cost)` (TX-1)
- :217-225 인젝션 체크 + `moderationEventService.recordInjection` (DB 쓰기)
- (:233-236 Mongo USER 로그 저장 실패만 `compensateEnergy`(:234)로 보호)
- :239 rollbackCtx 생성 → :243 `jpa.room().isEventActive()` → :247 `resolveSecretMode(jpa.room())` ← LAZY 로딩·Redis/DB 접근이 얽힌 지점, 어떤 catch에도 안 감김
- TX-2 커밋(:344 catch로 보호) 이후 :419 부근 `jpa.room().getCharacter().getId()` 호출이 try 밖에 노출 — 여기서 던지면 스탯은 커밋됐는데 final_result가 안 나가 유저는 응답을 못 본다(무보상)

이 경로는 SANDBOX 전 유저의 모든 턴이 지나는 최대 트래픽면이다.

**수정안**

D-2.a와 동일한 `charged`/`rollbackCtx`/`committed` 3상태 분기를 :488 catch에 적용. TX-2 성공 지점은 :343 `});` 직후(catch :344-349를 빠져나온 시점). `jpa`를 try 밖 로컬로 승격. 추가로 :419 `jpa.room().getCharacter()`는 TX-2 이후 무보상 구간이므로 별도 try-catch로 감싸 final_result 전송만은 반드시 나가도록(응답 유실 방지) 방어할 것.

**제품 결정 연동**: none — §G #2가 제거하는 건 V1 **STORY** 트랙이고 ChatPage는 SANDBOX 전용으로 존치 확정. 이 메서드는 블록 D 이후에도 메인 경로다.

**❓ 결정 필요**: TX-2 커밋 이후(스탯·로그는 반영됐으나 final_result 전송 실패)에 유저가 화면상 아무것도 못 받는 경우, 에너지를 환불할 것인가 아니면 '로그는 남았으니 새로고침하면 보인다'로 무보상 유지할 것인가. 프론트가 새로고침 시 그 턴을 복원하는지에 따라 갈리는 제품 판단.

---

### D-2.c. V1 sendMessageStream 최외곽 catch가 저장된 USER 로그를 삭제하지 않음 — Mongo 고아 로그 누적

**🔴 잔존** · P2 · ONE_LINE · BE  
`aichat/src/main/java/com/spring/aichat/service/stream/ChatStreamService.java:488-491 (원인 지점 :227-240)`

**근거**

에너지 소멸(D-2.b)과 소멸 자산이 다르므로 분리했다 — 이쪽은 대화 정합이 깨진다.

ChatStreamService.java:227-241:
```java
// ── MongoDB: USER 메시지 저장 ──
String savedUserLogId;
try {
    ChatLogDocument savedLog = chatLogRepository.save(ChatLogDocument.user(roomId, userMessage));
    savedUserLogId = savedLog.getId();
} catch (Exception e) { compensateEnergy(...); ... return; }

RollbackContext rollbackCtx = new RollbackContext(
    jpa.userId(), jpa.username(), jpa.energyCost(), savedUserLogId);
```
삭제 로직은 `compensateFullRollback`(:1329-1335)에만 있다:
```java
private void compensateFullRollback(RollbackContext ctx) {
    if (ctx.savedUserLogId() != null) {
        try { chatLogRepository.deleteById(ctx.savedUserLogId()); }
        catch (Exception ex) { log.error("User msg delete FAILED", ex); }
    }
    compensateEnergy(ctx.userId(), ctx.energyCost(), ctx.username());
}
```
최외곽 catch(:488)는 이 메서드를 부르지 않는다 → :239 이후 ASSISTANT 로그 저장 전에 던지면 **USER 메시지만 있고 답이 없는 로그**가 영구 잔류.

[2차 피해] 다음 턴의 프롬프트 어셈블리가 이 고아 로그를 히스토리로 읽어 "유저가 두 번 말하고 캐릭터는 침묵"한 대화를 LLM에 먹인다. 또한 `chatLogRepository.countByRoomId(roomId)`(:212)가 부풀어 턴 인덱스가 어긋난다(씬 일러 `turnIndex` 좌표계에도 전파 — docs/13 E-1의 turnIndex/ordinal 불일치를 악화).

**수정안**

D-2.b의 3상태 분기에서 `rollbackCtx != null && !committed`일 때 `compensateFullRollback(rollbackCtx)`를 부르면 로그 삭제가 함께 해결된다(별도 코드 불필요). 즉 D-2.b와 한 번에 고쳐지지만 **검증은 따로** 해야 한다 — 환불만 되고 로그가 남는 반쪽 수정이 흔하다. 회귀 테스트: rollbackCtx 생성 이후 강제 예외 → `chatLogRepository.countByRoomId` 불변 검증.

---

### D-2.d. V1 sendEventSelectStream 최외곽 catch에 보상 부재 (docs/13 미열거 — 동형 결함)

**🔴 잔존** · P2 · SMALL · BE  
`aichat/src/main/java/com/spring/aichat/service/stream/ChatStreamService.java:596-599`

**근거**

ChatStreamService.java:499 `public void sendEventSelectStream(Long roomId, String eventDetail, int energyCost, SseEmitter emitter)`
- :517 `room.getUser().consumeEnergy(energyCost)` (TX-1)
- :534-535 Mongo 로그 저장 실패만 `compensateEnergy` 보호
- :540 rollbackCtx 생성
- :580-581 TX-2 실패만 `compensateFullRollback` 보호
- :596-599 최외곽:
```java
} catch (Exception e) {
    log.error("❌ Event select error | roomId={}", roomId, e);
    sendSseError(emitter, "UNEXPECTED_ERROR", "이벤트 처리 중 오류 발생");
}
```
보상 호출 없음 — sendMessageStream과 완전 동형.

[가중 요인] `energyCost`가 **클라이언트 파라미터**다(메서드 시그니처 :499). 서버 판정이 아니라 프론트가 보낸 값을 그대로 차감한다 → 실패 시 소멸액도 클라이언트가 정하는 값이 된다.

**수정안**

D-2.b와 동일한 3상태 분기를 :596 catch에 적용. 5개 엔트리가 같은 패턴이므로 공통 헬퍼 `finalizeOnUnexpected(Throwable, StreamGuard)`를 하나 만들어 5곳에서 재사용하는 편이 회귀 위험이 낮다(docs/13 §D 결론부의 '공통 failFinalize로 수렴' 제안과 동일 방향).

⚠ 동시에 `energyCost` 클라이언트 수신은 §G #13의 'energyCost 서버 판정' 처분 대상이다 — 보상 수정과 같은 커밋에서 서버 산출로 바꿀 것. 그러지 않으면 '클라이언트가 정한 금액을 정확히 환불하는' 무의미한 정합을 구현하게 된다.

**제품 결정 연동**: ⚠ 블록 D 상호작용 있음. §G #13 '디렉터 3분기 카드 — 골격 유지, 고정 3톤→맥락 가변 제안 + energyCost 서버 판정'에 따라 **이 경로는 존치**되지만 시그니처가 바뀐다. 따라서 결함은 고쳐야 하되 §G #13 구현과 **묶어서** 해야 한다(따로 하면 두 번 손댄다). 반대로 §G #7('V1 디렉터 잔여 정리')이 이벤트 선택까지 걷어내는 것으로 재해석되면 MOOT가 될 수 있다 — 종원 확인 필요.

---

### D-2.e. V1 sendDirectorWatchStream(지켜보기) 최외곽 catch에 보상 부재 (docs/13 미열거 — 동형 결함)

**🔴 잔존** · P2 · SMALL · BE  
`aichat/src/main/java/com/spring/aichat/service/stream/ChatStreamService.java:706-709`

**근거**

ChatStreamService.java:607 `public void sendDirectorWatchStream(Long roomId, SseEmitter emitter)`
- :626 `room.getUser().consumeEnergy(cost)`
- :646-647 로그 저장 실패만 보호
- :652 rollbackCtx
- :692-693 TX-2 실패만 보호
- :706-709 최외곽:
```java
} catch (Exception e) {
    log.error("❌ Director watch error | roomId={}", roomId, e);
    sendSseError(emitter, "UNEXPECTED_ERROR", "지켜보기 처리 중 오류 발생");
}
```

**수정안**

D-2.d의 공통 헬퍼를 :706 catch에 적용. 단 아래 productDecisionRisk를 먼저 해소할 것 — 경로가 삭제 대상이면 수정 자체가 낭비다.

**제품 결정 연동**: ⚠ **MOOT 가능성 높음**. docs/14 §G #7 'V1 디렉터 잔여 — INTERLUDE/TRANSITION/AWAY 소비 경로(생산자 소멸)·activeDirector* 필드 정리. 시간 넘기기만 페이싱 도구로 존치'. '지켜보기'는 디렉터 모드 소비 경로라 §G #7 정리 대상에 들어갈 가능성이 크다. 블록 D에서 이 엔트리를 삭제하면 결함은 소멸한다. **블록 D 착수 전에 고치면 버려질 코드에 공수를 쓰는 셈** — 순서를 지킬 것.

---

### D-2.f. V1 sendTimeSkipStream(시간 넘기기) 최외곽 catch에 보상 부재 — 블록 D 존치 확정 경로 (docs/13 미열거)

**🔴 잔존** · P2 · SMALL · BE  
`aichat/src/main/java/com/spring/aichat/service/stream/ChatStreamService.java:884-887`

**근거**

ChatStreamService.java:737 `public void sendTimeSkipStream(Long roomId, SseEmitter emitter)`
- :755 `room.getUser().consumeEnergy(TIME_SKIP_ENERGY_COST)` (상수 차감 — 클라이언트 미개입)
- :768-769 로그 저장 실패만 보호
- :774 rollbackCtx
- :812-813 TX-2 실패만 보호
- :884-887 최외곽:
```java
} catch (Exception e) {
    log.error("❌ Time skip error | roomId={}", roomId, e);
    sendSseError(emitter, "UNEXPECTED_ERROR", "시간 넘기기 처리 중 오류 발생");
}
```

**수정안**

D-2.d의 공통 헬퍼를 :884 catch에 적용. **5개 엔트리 중 블록 D 존치가 확정된 유일한 디렉터 경로이므로, 디렉터 계열 중에서는 이것부터 고칠 것.**

**제품 결정 연동**: ✅ 존치 확정. docs/14 §G #7이 "'시간 넘기기'만 페이싱 도구로 존치"라고 명시 — 블록 D 이후에도 살아남으므로 수정이 낭비되지 않는다. 다른 디렉터 경로(D-2.e, D-2.g)와 달리 즉시 착수 가능.

---

### D-2.g. V1 sendAutoDirectorResponse(자동 디렉터 응답) 최외곽 catch에 보상 부재 (docs/13 미열거 — 동형 결함)

**🔴 잔존** · P2 · SMALL · BE  
`aichat/src/main/java/com/spring/aichat/service/stream/ChatStreamService.java:1749-1752`

**근거**

ChatStreamService.java:1568 `public void sendAutoDirectorResponse(Long roomId, String directiveType, String eventContext, SseEmitter emitter)`
- :1585-1586 `int cost = 1; room.getUser().consumeEnergy(cost);`
- :1628-1629 로그 저장 실패만 보호
- :1634 rollbackCtx
- :1680-1681 TX-2 실패만 보호
- :1749-1752 최외곽:
```java
} catch (Exception e) {
    log.error("❌ Director auto-respond error | type={} | roomId={}", directiveType, roomId, e);
    sendSseError(emitter, "UNEXPECTED_ERROR", "자동 응답 처리 중 오류 발생");
}
```
`directiveType`이 AWAY / BRANCH 등으로 분기(:1571-1572)한다 — AWAY는 §G #7 정리 대상, BRANCH는 §G #13 존치 대상이라 **한 메서드 안에 처분이 갈리는 두 경로가 섞여 있다**.

**수정안**

D-2.d의 공통 헬퍼를 :1749 catch에 적용. 단 블록 D에서 이 메서드가 AWAY 경로 제거로 쪼개질 예정이라면 그때 함께 하는 편이 낫다.

**제품 결정 연동**: ⚠ **부분 MOOT**. §G #7이 AWAY/INTERLUDE/TRANSITION 소비 경로를 걷어내지만, §G #13이 '디렉터 3분기 카드 골격 유지'라 BRANCH 분기(:1572 `isBranchResponse`)는 존치된다. 즉 메서드 전체가 사라지진 않고 축소된다 → 결함은 축소된 형태로 남으므로 결국 고쳐야 한다. 다만 블록 D의 메서드 분해 이후에 하는 것이 효율적이다.

---

### D-2.h. 캐릭터 일러 수동 생성 — 비동기 실패(markFailed) 5개 지점 전부 10E 환불 없음

**🔴 잔존** · P1 · MEDIUM · BE/DB_MIGRATION  
`aichat/src/main/java/com/spring/aichat/service/illustration/IllustrationService.java:119 (차감) / :482, :501, :567, :576, :588 (실패 전이)`

**근거**

docs/13의 :119가 지금도 정확히 차감 지점이다.

IllustrationService.java:117-126:
```java
// 3) 에너지 차감
user.consumeEnergy(ILLUSTRATION_ENERGY_COST);   // ← :119, 상수 10 (:65)
userRepository.save(user);
cacheService.evictUserProfile(username);
// 4) 생성 제출
return submitGeneration(user, target.character(), ...);
```

[정정 — docs/13보다 좁게 판정] `requestIllustration`은 `@Transactional`(:102)이므로 **동기 구간의 예외는 TX 롤백으로 에너지가 자동 복구**된다(:409 `modelsLabClient.submit` throw, :450 DB 저장 실패 후 `throw e`). 따라서 "실패 전 경로 환불 없음"은 과대 서술이다.

[그러나 비동기 실패 전이 5곳은 전부 무환불 — 파일 전체에 `refundEnergy` 문자열이 0회]
```
:482  illust.markFailed("No image URL in ModelsLab response");
:501  illust.markFailed(e.getMessage());                       // 완료 처리 중 S3 업로드 등 실패
:567  illust.markFailed("ModelsLab generation failed");         // 폴러가 FAILED 관측
:576  illust.markFailed("Aborted after " + consecutiveErrors + " consecutive poll errors");
:588  illust.markFailed("Generation timed out after " + elapsed + " seconds");
```
대조군: 씬 일러 트랙은 같은 상황에서 환불한다(SceneRenderWriteService.java:41-48 `refundableOnFail()` → `refundManualCharge`, 도메인에 `energyCharged`/`energyRefunded` 멱등 가드까지 존재). 캐릭터 일러 트랙에만 그 설비가 통째로 없다 — `UserIllustration`에 환불 상태 필드 자체가 없다.

결과: 생성이 실패로 확정돼도 10E는 그대로 소멸. D-1.1과 겹치면 소각까지 이중.

**수정안**

씬 일러 트랙의 설비를 그대로 이식한다.
1) `UserIllustration`에 `energyCharged`(int) · `energyRefunded`(boolean) 추가 — Flyway **V31**(일러·극장 묶음 — 2026-08-26 정정 · D-33) `user_illustrations.energy_charged INT NOT NULL DEFAULT 0`, `energy_refunded BOOLEAN NOT NULL DEFAULT false`. (D-1.1 적용 시 `energy_charged_paid`도 함께.)
2) `requestIllustration`(:119)에서 차감액(과 유료 분할분)을 `createPending`에 실어 저장.
3) `SceneIllustration.refundableOnFail()`(도메인 :162-165) 동형 메서드 추가: `"MANUAL".equals(triggerType) && energyCharged > 0 && !energyRefunded && user != null`.
4) **private 헬퍼 `failAndRefund(UserIllustration illust, String reason)`을 신설하고 :482/:501/:567/:576/:588의 `markFailed(...)` 호출을 전부 이 헬퍼로 교체** — markFailed 직접 호출을 남기지 말 것(다음에 실패 전이를 추가하는 사람이 또 빠뜨린다). 헬퍼는 `SceneRenderWriteService.refundManualCharge`(:53-68)와 동일하게 userId 직접 조회 + 멱등 가드 + `cacheService.evictUserProfile` + 환불 실패는 로그만.
5) AUTO 트리거(`generateAutoIllustration`, 승급/엔딩 자동 생성)는 유저 과금이 아니므로 환불 대상에서 제외 — `triggerType` 판정으로 가를 것.

**제품 결정 연동**: ⚠ 블록 D 상호작용 있음. docs/14 §G #6 '레거시 캐릭터 일러 트랙(ModelsLab CG) — 씬 일러로 일원화·동결·신규 노출 중단'이 정확히 이 트랙이다. 다만 FE는 아직 살아 있어 지금도 과금이 발생한다: LucidChat-Front `src/pages/ChatPage.jsx:3862`, `src/pages/ChatPageV2.jsx:4725`에서 `IllustrationModal`이 마운트되고 `src/components/IllustrationModal.jsx:102`가 `POST /illustrations/generate`를 호출한다. 즉 **현재도 유저 10E가 소각 가능**하다. 종원 판단: '§G #6 조기 집행(노출 차단)으로 결함을 소멸시킬 것인가' vs '환불 설비를 붙일 것인가'. 전자가 공수가 훨씬 적다.

---

### D-2.i. 수동 일러 경로가 백그라운드 폴링을 아예 기동하지 않음 — 영구 PENDING 원인 1/3

**🔴 잔존** · P1 · SMALL · BE  
`aichat/src/main/java/com/spring/aichat/service/illustration/IllustrationService.java:122-125 (수동) vs :250-253 (자동)`

**근거**

docs/13이 3중 결함을 한 줄로 묶었으나 셋은 독립이라(하나만 고쳐도 나머지가 구멍) 쪼갰다. 이건 첫 번째.

자동 경로는 폴링을 건다 — :250-253:
```java
// [Phase 6-Illust] 동기 완료된 경우 폴링 불필요 — submitGeneration 내부에서 이미 처리됨
if (!"COMPLETED".equals(result.status())) {
    processPollingInBackground(result.requestId());
}
```
수동 경로는 걸지 않는다 — :122-125:
```java
// 4) 생성 제출
return submitGeneration(user, target.character(), target.emotion(), target.location(),
    target.outfit(), "MANUAL", target.sceneHint(), target.dynamicLocDesc());
}
```
`submitGeneration`은 큐 모드에서 `return new IllustrationRequestResult(requestId, illust.getId(), "PENDING")`(:456)으로 끝나고 폴러를 부르지 않는다. `processPollingInBackground` 호출부는 :252 단 1곳(자동)뿐, 정의는 :529.

→ 수동 요청의 상태 전이는 **오직 웹훅에만 의존**한다. 웹훅이 안 오거나(D-2.k) 유실되면 PENDING 영구.

**수정안**

`requestIllustration`(:103)에서도 큐 모드일 때 폴링을 기동한다. 단 두 가지 함정을 함께 처리할 것:
1) **자기호출 프록시 우회** — `processPollingInBackground`는 `@Async("illustrationExecutor") protected`(:528-529)인데 :252에서 **같은 클래스 내부 호출**이라 스프링 프록시를 타지 않는다. 즉 지금 자동 경로조차 비동기가 아니라 호출 스레드에서 최대 180초(`MAX_POLL_ATTEMPTS`=180 × `POLL_INTERVAL_MS`=1000, :68-69) 블로킹된다. 수동 경로에 그대로 붙이면 **HTTP 요청 스레드가 3분 잡힌다**. 반드시 (a) 폴러를 별도 빈으로 분리하거나 (b) `ApplicationEventPublisher` + `@TransactionalEventListener(AFTER_COMMIT)`로 태워 프록시를 타게 할 것.
2) `requestIllustration`은 `@Transactional`(:102)이라 커밋 전에 폴러가 뜨면 `findByFalRequestId`가 행을 못 찾는다(:531 `orElse(null)` → 조용히 return). AFTER_COMMIT 훅이 정답.
3) 폴러의 종결 전이(:567/:576/:588 markFailed)는 D-2.h의 `failAndRefund` 헬퍼로 교체돼 있어야 환불까지 이어진다 — D-2.h와 묶어서 진행할 것.

**제품 결정 연동**: ⚠ D-2.h와 동일 — §G #6으로 트랙이 동결·노출 중단되면 소멸. 조기 집행 판단이 선행돼야 한다.

---

### D-2.j. checkStatus의 프로바이더 폴링이 통째로 주석 처리 — DB 상태만 에코, 영구 PENDING 원인 2/3

**🔴 잔존** · P1 · SMALL · BE  
`aichat/src/main/java/com/spring/aichat/service/illustration/IllustrationService.java:283-298`

**근거**

docs/13의 :284가 지금도 정확히 주석 블록 첫 줄이다.

IllustrationService.java:283-298:
```java
        // 아직 진행 중 → RunPod에 직접 폴링
//        if (illust.isPending() && illust.getStatusUrl() != null) {
//            try {
//                PollResult poll = modelsLabClient.pollStatus(illust.getStatusUrl());
//                if (poll.completed()) {
//                    handleCompletion(illust, poll.payload());
//                    return new IllustrationStatusResult("COMPLETED", illust.getImageUrl(), null);
//                }
//                return new IllustrationStatusResult(poll.status(), null, null);
//            } catch (Exception e) { ... }
//        }

        return new IllustrationStatusResult(illust.getStatus(), null, null);   // ← :298 DB 값 에코만
```
즉 `GET /api/v1/illustrations/status/{id}`는 DB에 적힌 값을 되돌려줄 뿐, 외부 상태를 확인해 전이시키는 능력이 없다.

[프론트가 이 구멍에 정확히 물린다] LucidChat-Front `src/components/IllustrationModal.jsx:119-141`이 `setInterval`로 `/illustrations/status/${reqId}`를 폴링하며 `COMPLETED`(:134) 또는 `FAILED`(:138)만 종결로 처리하고, 그 외에는 `MAX_POLL_COUNT` 초과 시(:122-123) 클라이언트 측에서만 중단한다. 서버 DB는 PENDING인 채로 남고 10E는 회수 불가.

**수정안**

주석을 그대로 되살리지 말 것 — API가 RunPod 시절 것이고(`modelsLabClient.pollStatus`), 현행 클라이언트 메서드는 `fetch(String fetchUrl, String generationId)`(external/ModelsLabClient.java:152)다.

**권장안**: D-2.i의 서버측 폴러를 제대로 붙이면 `checkStatus`는 DB 에코로 충분해진다. 두 군데에 폴링을 두면 경쟁 상태(웹훅 vs 폴러 vs checkStatus)가 3중이 된다. D-2.i를 정답으로 삼고 이 주석 블록은 **삭제**해 사문을 남기지 않는 쪽이 낫다(§G #4 '데드 코드 일괄' 처분과도 정합).

굳이 되살린다면 `modelsLabClient.fetch(illust.getStatusUrl(), requestId)`로 교체하고, FAILED 관측 시 D-2.h의 `failAndRefund`를 태우며, `checkStatus`(:267)에 트랜잭션이 없고 `handleCompletion`이 `protected @Transactional`(:476-477) 자기호출이라 프록시를 못 타는 점(현재는 명시 `illustrationRepository.save`에 의존)을 함께 정리할 것.

**제품 결정 연동**: ⚠ §G #6(레거시 캐릭터 일러 트랙 동결) 대상. 추가로 §G #4 '데드 코드 일괄' 관점에서 이 주석 블록 자체가 제거 대상이다.

---

### D-2.k. ModelsLab 비-success 웹훅을 무시하고 200 OK 반환 — FAILED 전이 경로 자체가 없음, 영구 PENDING 원인 3/3

**🔴 잔존** · P1 · SMALL · BE  
`aichat/src/main/java/com/spring/aichat/controller/IllustrationWebhookController.java:70-73`

**근거**

IllustrationWebhookController.java:53-73:
```java
String generationId = payload.path("id").asText(null);
String status = payload.path("status").asText("UNKNOWN");
String trackId = payload.path("track_id").asText(null);
...
if (!"success".equalsIgnoreCase(status)) {
    log.info("[MODELSLAB-WEBHOOK] Non-success status ignored: {}", status);   // ← :71
    return ResponseEntity.ok().build();                                       // ← :72 폐기
}
```
프로바이더가 **실패를 통보해도 그 정보가 버려진다**. `illustrationService.handleModelsLabWebhookCallback`(:80)은 success일 때만 호출된다.

[3중 결함의 합류점] 수동 경로는 서버 폴러 없음(D-2.i) + checkStatus 프로바이더 폴링 없음(D-2.j) + 실패 웹훅 폐기(D-2.k) → **PENDING에서 FAILED로 갈 수 있는 경로가 0개**. 성공 웹훅만이 유일한 탈출구다. 실패 시 DB 행은 영구 PENDING, 10E 영구 소멸, 갤러리에도 안 뜨고 재시도 유도도 없다.

[웹훅 자체는 구성돼 있음 — 확인] external/ModelsLabClient.java:94-96
```java
if (props.webhookBaseUrl() != null && !props.webhookBaseUrl().isBlank()) {
    body.put("webhook", props.webhookUrl());
    if (req.trackId() != null) body.put("track_id", req.trackId());
```
application.yml:111 `webhook-base-url: ${LUCID_WEBHOOK_BASE}` — 기본값 없는 필수 주입이라 미설정이면 기동 실패. 즉 웹훅은 실제로 걸린다 → 성공은 완료되고 실패만 사라지는 비대칭이 확정된다.

**수정안**

:70-73 분기를 '무시'에서 '실패 전이'로 바꾼다:
```java
boolean success = "success".equalsIgnoreCase(status);
try {
    if (trackId != null && trackId.startsWith("BG_")) {
        if (success) backgroundGenerationService.handleModelsLabWebhookCallback(generationId, payload);
        else         backgroundGenerationService.handleFailureCallback(generationId, status);
    } else {
        if (success) illustrationService.handleModelsLabWebhookCallback(generationId, payload);
        else         illustrationService.handleFailureCallback(generationId, status);  // 신규 — D-2.h의 failAndRefund 경유
    }
} catch (Exception e) { log.warn(...); }
return ResponseEntity.ok().build();
```
- `IllustrationService.handleFailureCallback(String, String)` 신설: `findByFalRequestId` → 이미 COMPLETED/FAILED면 멱등 skip → 아니면 D-2.h의 `failAndRefund(illust, "webhook status=" + status)`.
- 배경 트랙(`BG_` prefix)은 무과금이라 로그·상태 전이만.
- ModelsLab이 실제로 보내는 실패 status 문자열 집합을 확인해 `"processing"`/`"queued"` 같은 **중간 상태를 실패로 오판하지 않도록** 화이트리스트/블랙리스트를 명시할 것 — 현재 코드는 success 외 전부를 한 덩어리로 보고 있어, 중간 상태 웹훅이 오면 성급히 FAILED+환불 처리해 버릴 위험이 있다. 이게 이 수정의 유일한 실질 리스크다.
- `verifySecret`(:93-97)이 `expected` 미설정 시 무조건 true라 인증 없이 실패 전이를 트리거당할 수 있다 — 환불이 붙는 순간 **무료 환불 파밍면**이 된다. 이 수정과 함께 `MODELSLAB_WEBHOOK_SECRET` 필수화를 반드시 동반할 것.

**제품 결정 연동**: ⚠ §G #6 대상 트랙. 단 이 컨트롤러는 시크릿 모드 NSFW 배경 트랙(`BG_` prefix)도 함께 라우팅하므로(:77-78) **§G #6으로 캐릭터 일러 트랙이 동결돼도 컨트롤러 자체는 존치**한다. docs/16이 이미지 노드 수위를 확정하며 배경/씬 일러 트래픽을 늘리는 방향이라 웹훅 실패 처리 부재는 오히려 더 아플 수 있다. → 캐릭터 일러 분기는 §G #6 판단에 맡기되, **웹훅 시크릿 필수화는 트랙 처분과 무관하게 지금 해야 한다.**

**❓ 결정 필요**: ModelsLab이 보내는 status 값의 전체 집합(중간 상태 포함)이 문서/실측으로 확정돼 있는가? 확정 없이 '비-success=실패'로 전이시키면 정상 진행 중인 생성을 죽이고 환불까지 나간다. 실측 로그(:57-58 `[MODELSLAB-WEBHOOK] Received: id=..., status=...`)를 며칠 수집한 뒤 켜는 것을 권한다.

---

### D-2.l. 감정 컷 유료 리롤(2E) 실패·기존본 복귀 시 미환불 — 낸 돈으로 아무것도 못 받고 상태만 원위치

**🔴 잔존** · P2 · SMALL · BE  
`aichat/src/main/java/com/spring/aichat/service/ugc/UgcPipelineWorker.java:476-479`

**근거**

docs/13의 :477이 지금도 정확히 복귀 라인이다.

과금 지점 — CharacterCreationService.java:415-441 `rerollEmotion`:
```java
boolean free = state.is(EmotionAssetState.FAILED);
if (!free) {
    int cost = props.energy().emotionReroll();   // application.yml:196 emotion-reroll-cost: 2
    User user = findUser(username);
    user.consumeEnergy(cost);
    userRepository.save(user);
    job.chargeEnergy(cost);
}
```
실패 소진 처리 — UgcPipelineWorker.java:463-485 `handleEmotionFailure`:
```java
int next = state.retryCount() + 1;
if (next <= props.job().emotionRetries()) {          // application.yml:200 emotion-max-retries: 3
    emotions.put(tag, state.derivingAgain(next));
    ...
    return true;
}
// 소진: 이전 완성본이 있으면 그리로 복귀(리롤 실패가 기존 결과를 파괴하지 않도록), 없으면 FAILED
emotions.put(tag, state.hasCompletedVersion() ? state.revertToReady() : state.failed());   // ← :477
job.updateEmotionAssets(json.writeEmotions(emotions));
checkEmotionsSettled(job, emotions);
return false;
```
환불 코드가 없다.

[두 분기의 비대칭이 핵심]
- `state.failed()` 분기(완성본 없음): 컷이 FAILED가 되므로 다음 `rerollEmotion`이 `free=true`로 판정(:425) → **무료 재시도로 자가치유**. 실질 손실 없음.
- `state.revertToReady()` 분기(완성본 있음 = **유저가 돈 내고 리롤한 정확히 그 케이스**): 상태가 READY로 되돌아가므로 다음 리롤은 다시 `free=false` → **또 2E 과금**. 즉 유료 리롤을 시도한 유저만 손해를 보고, 재시도하려면 계속 돈을 낸다.

잡 전체가 실패하는 게 아니라 컷 하나만 되돌아가는 것이라 `failAndRefund`(:791)의 전액 환불에도 걸리지 않는다.

**수정안**

`handleEmotionFailure`(:463)의 소진 분기에서 **`revertToReady()` 경로에 한해** 리롤 과금을 환불한다.
1) 유료 리롤 여부를 상태에 실어야 한다 — `EmotionAssetState`에 `paidReroll`(boolean) 또는 `rerollChargedEnergy`(int) 필드 추가. `CharacterCreationService.rerollEmotion`(:433-438)의 `if (!free)` 블록에서 `worker.resetEmotionForReroll(job, tag)`(:439, 정의 UgcPipelineWorker:932) 호출 시 함께 마킹.
2) :477을 다음으로 교체:
```java
boolean hadPaidReroll = state.isPaidReroll();
if (state.hasCompletedVersion()) {
    emotions.put(tag, state.revertToReady().clearPaidReroll());
    if (hadPaidReroll) {
        int back = props.energy().emotionReroll();
        job.refundEnergy(back);   // energyCharged 누산 되돌림 (D-1.1 분할 포함)
        userRepository.findById(job.getUserId()).ifPresent(u -> {
            u.refundEnergy(splitOf(back)); cacheService.evictUserProfile(u.getUsername()); });
    }
} else {
    emotions.put(tag, state.failed().clearPaidReroll());   // 무료 재시도로 자가치유 — 환불 불요
}
```
3) `job.energyCharged`도 함께 감액해야 나중에 잡이 실패했을 때 `failAndRefund`가 이미 환불한 2E를 **이중 환불**하지 않는다. 이게 이 수정에서 가장 놓치기 쉬운 지점.
4) 회귀 테스트: (a) 완성본 있는 컷 유료 리롤 → 3회 실패 → 에너지 원복 + energyCharged 원복, (b) 완성본 없는 컷 → FAILED + 무환불 + 다음 리롤 무료, (c) (a) 이후 잡 전체 실패 → 이중 환불 없음.

**제품 결정 연동**: none — UGC 캐릭터 빌더는 docs/14 BM의 핵심(25E UGC)이며 §G 처분 대상이 아니다.

---

### D-2.m. 월드 에셋 유료 리롤(1E) 실패·기존본 복귀 시 미환불 — 캐릭터 트랙과 동형 (docs/13 미열거)

**🔴 잔존** · P3 · SMALL · BE  
`aichat/src/main/java/com/spring/aichat/service/ugc/UgcWorldPipelineWorker.java:281-284`

**근거**

과금 지점 — UgcWorldService.java:262-283 `rerollAsset`(썸네일/장소 배경 공통, `rerollThumbnail` :253 · `rerollLocation` :258에서 진입):
```java
boolean free = state.is(WorldAssetState.FAILED);
if (!free) {
    int cost = props.world().reroll();   // application.yml:218 reroll-cost: 1
    User user = findUser(username);
    user.consumeEnergy(cost);
    userRepository.save(user);
    job.chargeEnergy(cost);
}
```
실패 소진 처리 — UgcWorldPipelineWorker.java:260-287 `handleIllustrationFailure`:
```java
int next = state.retryCount() + 1;
if (next <= CUT_MAX_RETRIES) { ... return "RETRY"; }
// 소진: 이전 완성본이 있으면 복귀(리롤 실패가 기존 결과를 파괴하지 않도록), 없으면 FAILED(무료 재시도 대상)
scratch.remove(token);
assets = updated(assets, token, state.hasCompletedVersion() ? state.revertToReady() : state.failed());   // ← :282
```
주석 문장까지 캐릭터 트랙과 같다 — 복붙 계보. 환불 코드 없음, 비대칭도 동일(FAILED는 무료 재시도로 자가치유 / revertToReady는 순손실).

금액은 1E로 작지만 **누락하면 D-2.l만 고치고 같은 결함이 옆 트랙에 남는다**. docs/13에 없으므로 명시적으로 올린다.

**수정안**

D-2.l과 동일 패턴을 `WorldAssetState` + `UgcWorldPipelineWorker.handleIllustrationFailure`(:260)에 적용. `WorldAssetState`에 유료 리롤 마킹 필드 추가, `UgcWorldService.rerollAsset`(:272-279)의 `if (!free)` 블록에서 세팅, :282의 `revertToReady()` 분기에서만 `props.world().reroll()` 환불 + `job.energyCharged` 감액(이중 환불 방지). D-2.l과 **같은 커밋에서 처리**할 것 — 따로 하면 한쪽이 잊힌다.

---

### D-2.n. WorldRoutingService.route가 히로인의 character 참조를 무가드로 역참조 + 보상 창 밖에서 호출 — 재시도할 때마다 차감되는 결정론적 드레인

**🟠 부분수정** · P2 · SMALL · BE  
`aichat/src/main/java/com/spring/aichat/service/story/WorldRoutingService.java:90, 94, 106, 125, 159, 164 (호출 지점: ChatStreamServiceV2.java:233)`

**근거**

docs/13의 :90이 지금도 정확한 역참조 지점이다.

WorldRoutingService.java:88-95:
```java
List<ChatRoomHeroine> charsHere = heroines.stream()
    .filter(h -> charsHereIds.contains(h.getCharacter().getId()))   // ← :90 무가드
    .toList();
List<Long> charsHereIdList = charsHere.stream()
    .map(h -> h.getCharacter().getId())                             // ← :94
    .toList();
```
추가 역참조: :106 `charsHere.get(0).getCharacter().getId()`, :125 `defaultSpeaker.getCharacter().getId()`, :159 `h.getCharacter().getName()`, :164 `h.getCharacter().getId()`. 전부 null/댕글링 가드 없음.

[docs/13의 'null 행' 전제는 현 스키마상 성립하지 않는다 — 이 부분은 무효]
C:\Users\zapza\Desktop\MuseLab\aichat\src\main\java\com\spring\aichat\domain\heroine\ChatRoomHeroine.java:69-71:
```java
@ManyToOne(fetch = FetchType.LAZY, optional = false)
@JoinColumn(name = "character_id", nullable = false)
private Character character;
```
`optional=false` + `nullable=false` → 컬럼 NOT NULL. 직접 SQL 삽입이 아니면 literal null은 불가능하다. 또한 LAZY 프록시에서 `getId()`는 프록시 초기화 없이 반환되므로 :90/:94/:106/:125는 참조 행이 삭제돼도 NPE를 안 낸다. 위험한 건 :159 `getCharacter().getName()`(프록시 초기화 → 참조 행 부재 시 `EntityNotFoundException`)이고, 이는 **같은 공간에 2명 이상일 때만** 도달한다(:113 `if (charsHere.size() == 1) return ...` 이후). 전 소스에서 `characterRepository.delete` / 캐릭터 하드 삭제 호출부를 grep한 결과 0건 — 댕글링 참조를 만드는 코드 경로도 현재는 없다.
→ **NPE 트리거 조건 자체는 CANNOT_VERIFY**(운영 DB에 수기 삭제/마이그레이션 잔재가 있으면 성립).

[그러나 드레인 구조는 STILL_PRESENT — 이쪽이 진짜 결함]
ChatStreamServiceV2.java:229-233:
```java
RollbackContext rollbackCtx = new RollbackContext(
    jpa.userId(), jpa.username(), jpa.energyCost(), savedUserLogId);

// ── 6. V2 라우팅 — 시작 화자 결정 ──
WorldRoutingService.RoutingResult routing = routingService.route(jpa.room(), userMessage);   // ← :233
```
`route()`는 rollbackCtx 생성 **이후**, 보상이 붙는 `streamLlmAndParseV2(... rollbackCtx)`(:236) **이전**이라 어떤 catch에도 안 감기고 최외곽(:280, D-2.a)으로 떨어진다. 최외곽은 무보상.
즉 **route()에서 나는 모든 결정론적 예외**(댕글링 참조든, 리포지토리 쿼리 실패든, 데이터 이상이든)가 '유저가 재전송할 때마다 에너지가 깎이고 매번 같은 에러' 드레인이 된다. 예외 종류가 NPE인지 EntityNotFoundException인지는 드레인 성립과 무관하다.

PARTIALLY_FIXED로 둔 이유: docs/13이 지목한 원인(null 컬럼)은 스키마 제약으로 봉쇄됐으나, 결과(무보상 결정론적 드레인)는 그대로 살아 있다.

**수정안**

두 겹으로 막는다.
1) **드레인 봉쇄(필수·즉효)** — D-2.a의 최외곽 catch 3상태 보상을 적용하면 route() 예외도 자동으로 전액 보상된다. 또는 :233 `routingService.route(...)`를 try로 감싸 `compensateFullRollback(rollbackCtx)` 후 `sendSseError(emitter, "ROUTING_ERROR", ...)` + return. **D-2.a를 고치면 이 항목의 핵심은 함께 해결된다.**
2) **방어적 가드(선택)** — `WorldRoutingService.route`(:77) 진입부에서 참조 무결한 행만 남긴다:
```java
List<ChatRoomHeroine> heroines = heroineRepository.findByChatRoom_Id(room.getId()).stream()
    .filter(h -> { try { return h.getCharacter() != null && h.getCharacter().getId() != null; }
                   catch (EntityNotFoundException e) {
                       log.error("[ROUTING] 댕글링 히로인 참조 스킵: roomId={}, heroineId={}", room.getId(), h.getId());
                       return false; } })
    .toList();
```
전부 걸러지면 `charsHere.isEmpty()` → AMBIENT(:98-103)로 자연 폴백하므로 방이 죽지 않는다.
3) `heroineRepository.findByChatRoom_Id`를 `@EntityGraph`/fetch join으로 바꿔 프록시 초기화를 쿼리로 앞당기면 :159의 지연 폭발 지점도 사라진다.
4) 운영 DB 실사: `SELECT h.id FROM chat_room_heroines h LEFT JOIN characters c ON c.id = h.character_id WHERE c.id IS NULL;` — 0행이면 (2)는 순수 방어, 1행 이상이면 P1으로 승격하고 데이터 정리를 선행할 것.

**제품 결정 연동**: none — V2 STORY 라우팅은 §G #2에서 V1 STORY를 대체하는 존속 트랙의 심장부다. 블록 D로 사라지지 않는다.

---

### D-3.1a. BINDING 상태 캐릭터 잡에 회수 경로가 전무 — 폴러·TTL·스테일 스윕 어느 리스트에도 없어 서버 재기동 시 영구 좀비

**🔴 잔존** · P1 · SMALL · BE  
`aichat/src/main/java/com/spring/aichat/service/scheduler/UgcJobScheduler.java:38-53, 143-155 (누락) / src/main/java/com/spring/aichat/service/ugc/UgcPipelineWorker.java:522-525, 773-780`

**근거**

UgcJobScheduler.java L38-47 `COMFY_PROCESSING_STATUSES`에 BINDING이 없다:
```java
private static final List<CreationJobStatus> COMFY_PROCESSING_STATUSES = List.of(
    CreationJobStatus.CONCEPT_PROCESSING, CreationJobStatus.BASE_PROCESSING,
    CreationJobStatus.EMOTIONS_PROCESSING, CreationJobStatus.REVIEW_WAIT,
    CreationJobStatus.POSTPROCESSING);   // ← BINDING 부재
```
L49-53 `WAIT_STATUSES`도 GACHA_WAIT/BASE_WAIT/REVIEW_WAIT뿐. L143-155 유일한 캐릭터 스윕 `recoverStaleConceptJobs`는 L147에서 `findByStatusAndUpdatedAtBefore(CreationJobStatus.CONCEPT_PROCESSING, cutoff)` — 단일 상태만 조회한다.

`grep -rn "CreationJobStatus.BINDING" src/main/java/` 전수 결과 4곳뿐이며 스케줄러는 0곳:
- CharacterCreationJob.java:278 (`toBinding()` 진입)
- CharacterCreationService.java:279 (updateProfileDraft 편집 거부)
- UgcPipelineWorker.java:525 (`bind()` 자체 가드)
(반면 월드 트랙은 UgcJobScheduler.java:70에 `WorldCreationJobStatus.BINDING`이 `WORLD_STALE_STATUSES`로 등록돼 있다.)

진입 경로 UgcPipelineWorker.java L773-780:
```java
    if (done) { job.toBinding(); }
    return done;
}));
if (allDone) { bind(jobId); }   // ← @Async 자기호출: 프록시 미경유라 호출 스레드에서 동기 실행
```
`bind()`는 L522 `@Async`가 붙어 있으나 L779에서 `this.bind(jobId)`로 자기호출되므로 **웹훅/폴러 스레드 위에서 동기 실행**된다. 그 스레드가 배포·크래시로 죽으면 DB는 BINDING으로 커밋된 채 남고, 재기동 후 누구도 이 잡을 다시 보지 않는다.

대비 — 월드 트랙에는 정확히 이 복구가 있다 (UgcWorldPipelineWorker.java:479-482):
```java
case BINDING -> {
    log.info("[UGC-WORLD] 스테일 BINDING 재실행: jobId={}", jobId);
    bindWorld(jobId);
}
```

재현: ① 유저가 감정 15컷 검수 확정 → 누끼 완료 → `toBinding()` 커밋 ② 그 순간 ECS 태스크 교체/재기동 ③ 재기동 후 잡은 status=BINDING·energyCharged=20 ④ 폴러(1분) 스킵, TTL(10분) 스킵, CONCEPT 스윕(5분) 스킵 ⑤ `CharacterCreationService.java:47` ACTIVE_STATUSES = `!isTerminal()`이므로 BINDING은 active → L142 `existsByUserIdAndStatusIn` 가 신규 생성을 **영구 차단**.

금전: 유저 20E 전액 몰수(블록 C 이후 25E). 조작자: 황금샷+스탠딩+감정 14종+누끼 15종 = 캐릭터 1건 풀 GPU 원가가 산출물 0으로 소각. 부가: `bind()` L535-539가 promoteToCharacterAsset를 tx 밖에서 먼저 실행하는 구조라, BINDING 도중 죽으면 `characters/{slug}/` 아래 최대 16개 S3 객체가 고아로 남는다.

**수정안**

UgcJobScheduler에 월드 트랙과 동형의 캐릭터 스테일 스윕을 추가한다.
① `UgcJobScheduler.java`에 상수 추가: `private static final List<CreationJobStatus> CHAR_STALE_STATUSES = List.of(CreationJobStatus.BINDING);` (D-3.1b·D-3.1d를 함께 고칠 경우 POSTPROCESSING/BASE_PROCESSING/EMOTIONS_PROCESSING도 이 리스트로 통합).
② `@Scheduled(fixedRate = 5*60*1000) public void recoverStaleCharacterJobs()` 신설 — `jobRepository.findByStatusInAndUpdatedAtBefore(CHAR_STALE_STATUSES, now-CONCEPT_STALE_MINUTES)`로 조회 후 `worker.recoverStaleJob(job.getId())` 호출. (리포지토리에 `findByStatusInAndUpdatedAtBefore`가 없으면 추가 — 현재는 `findByStatusAndUpdatedAtBefore`(단수)만 있다.)
③ `UgcPipelineWorker`에 `public void recoverStaleJob(Long jobId)` 신설, `UgcWorldPipelineWorker.java:455-485`의 switch 패턴을 그대로 이식: `case BINDING -> bind(jobId);`. `bind()`는 L525에 `getStatus() != BINDING` 가드가 있고, L532 `uniqueSlug`가 slug 충돌 시 접미사를 붙이며, L588-601 tx가 Character 저장과 `toReady`를 한 tx로 묶으므로 재실행이 멱등하다(고아 slug 1개가 남는 비용만).
④ 스윕 진입 시 `job.touchRecovery()`류로 updatedAt을 갱신해 같은 창 안 중복 재실행을 막을 것 — 월드의 `UgcWorldCreationJob::touchRecovery`(UgcWorldPipelineWorker.java:504) 대응물을 `CharacterCreationJob`에 추가.
⑤ 고아 S3 정리는 별건으로 분리(bind 재실행이 같은 slug를 재사용하면 자연 덮어쓰기).

**제품 결정 연동**: 블록 C(UGC 25E, 6/5/11/3)가 들어가면 몰수액이 20E→25E로 상승 — 수정 시급도만 올라가고 방식은 불변. 블록 D(엔딩·업적 게이트오프, V1 STORY/승급 시험 제거, 복장·장소 해금 오프)와 무관: docs/14 §G 21건에 UGC 파이프라인 처분 항목이 없다. docs/16(시크릿=핵심 BM)은 간접 상향 — UGC 캐릭터가 시크릿 심사·공개의 공급원이므로 UGC 좀비는 시크릿 콘텐츠 파이프라인도 막는다.

---

### D-3.1b. POSTPROCESSING 좀비 — 폴러 리스트에는 있으나 externalJobs가 빈 유실 케이스(누끼 미제출·부분 제출)를 어떤 경로도 회수하지 못함

**🔴 잔존** · P1 · MEDIUM · BE  
`aichat/src/main/java/com/spring/aichat/service/scheduler/UgcJobScheduler.java:46, 143-155 / src/main/java/com/spring/aichat/service/ugc/UgcPipelineWorker.java:491-509 / src/main/java/com/spring/aichat/service/ugc/CharacterCreationService.java:467-489`

**근거**

POSTPROCESSING은 `UgcJobScheduler.java:46`에서 폴러 대상이긴 하다. 그러나 폴러(L86-104)는 **externalJobs 스크래치를 순회할 뿐**이라 키가 없으면 아무 일도 하지 않는다:
```java
List<CharacterCreationJob> jobs = jobRepository.findByStatusIn(COMFY_PROCESSING_STATUSES);
for (CharacterCreationJob job : jobs) {
    Map<String, String> scratch = json.readScratch(job.getExternalJobsJson());
    for (Map.Entry<String, String> entry : scratch.entrySet()) { ... }   // 스크래치가 비면 no-op
}
```
그리고 완료된 키는 제거된다 — UgcPipelineWorker.java:770 `removeExternalJob(job, externalKey(UgcStage.CUTOUT, tag.name()));`. 즉 **완료분은 사라지고 미제출분은 애초에 없으므로**, 유실 시 스크래치가 비어 폴러가 영구 no-op이 된다.

유실 창 ①(전량 미제출) — CharacterCreationService.java:482-488:
```java
    job.toPostprocessing();     // ← tx 안에서 커밋
    return c;
}));
if (charged) { cacheService.evictUserProfile(username); }
worker.runCutoutStage(jobId);   // ← tx 커밋 후 @Async (프록시 경유, 진짜 비동기)
```
toPostprocessing 커밋 후 `runCutoutStage`가 실행되기 전에 프로세스가 죽으면 CUTOUT 키가 0개.

유실 창 ②(부분 제출) — UgcPipelineWorker.java:503-505:
```java
for (Map.Entry<EmotionTag, EmotionAssetState> entry : emotions.entrySet()) {
    submitCutout(jobId, entry.getKey(), entry.getValue().key());   // 15회 순차 제출
}
```
7번째에서 죽으면 제출된 7개는 완료되며 각각 removeExternalJob으로 지워지고, 미제출 8개는 CUTTING 상태로 영원히 남는다. L772 `boolean done = emotions.values().stream().allMatch(s -> s.is(DONE));`가 절대 true가 되지 않아 BINDING 전이 불가.

어느 쪽이든 최종 상태: status=POSTPROCESSING, 스크래치 비어 있음 → 폴러 no-op, TTL 비대상(isWait() false), CONCEPT 스윕 비대상(단일 상태 조회). 영구 고착.

대비 — 월드 트랙은 정확히 이 케이스를 명시적으로 처리한다 (UgcWorldPipelineWorker.java:462-473):
```java
case ILLUSTRATING -> {
    Map<String, String> scratch = json.readScratch(job.getExternalJobsJson());
    if (scratch.isEmpty()) {
        // toIllustrating 커밋 후 runIllustration이 유실된 상태 — TTL 대상도 아니라 방치 시 영구 좀비
        runIllustration(jobId); return;
    }
    reattachPending(jobId, job, scratch);
}
```

금전: 유저 누적 20E 전액 몰수(블록 C 이후 25E). 창 ②는 조작자가 이미 누끼 7장의 GPU를 지불한 뒤 산출물 0. 유실 창 ②는 누끼 15회 순차 제출이라 **수십 초~수 분**으로 D-3.1a(수 초)보다 훨씬 넓다.

**수정안**

D-3.1a의 `recoverStaleCharacterJobs` 스윕에 POSTPROCESSING을 추가하고, `UgcPipelineWorker.recoverStaleJob`에 케이스를 구현한다.
```java
case POSTPROCESSING -> {
    Map<String,String> scratch = json.readScratch(job.getExternalJobsJson());
    boolean anyPending = scratch.keySet().stream().anyMatch(UgcPipelineWorker::isExternalJobKey);
    if (anyPending) return;             // 폴러가 담당 — 중복 제출 금지
    // 미제출 컷만 재제출 (CUTTING인데 cutoutKey==null인 항목)
    // 전부 미제출이면 runCutoutStage 재기동, 일부면 해당 tag만 submitCutout
}
```
구현 상세: `runCutoutStage`(L491-509)는 진입 시 emotions 전체를 `s.cutting()`으로 덮으므로 그대로 재호출하면 이미 DONE인 컷의 cutoutKey를 날린다 → **재사용 불가**. 부분 재개용으로 `resumeCutoutStage(Long jobId)`를 신설해 `state.cutoutKey() == null`인 tag만 `submitCutout(jobId, tag, state.key())` 하도록 할 것. `EmotionAssetState.doneWith(cutKey)`(dto/ugc/EmotionAssetState.java:82)가 cutoutKey를 채우므로 판별 가능하다.
스테일 컷오프는 CONCEPT_STALE_MINUTES(30)와 별개로 누끼 최장 소요를 고려해 별도 상수(예: 20분)로 둘 것.
오너 결정에 따라 '재개' 대신 `failAndRefund(jobId, "마무리 처리 시간 초과 — 사용한 에너지는 전액 환불되었어요.")`로 단순화할 수도 있다(구현 SMALL로 하락).

**제품 결정 연동**: 블록 C의 25E(6/5/11/3) 인상으로 몰수·환불액 상승. 블록 D 무관. 만약 '재개' 대신 '실패·전액 환불'을 택하면 조작자가 이미 지불한 감정 14종 GPU(원가 최대 구간, 블록 C에서 8E→11E 비중)를 통째로 버리게 되므로, 블록 C 원가 재산정과 함께 판단하는 것이 유리하다.

**❓ 결정 필요**: POSTPROCESSING 좀비를 ①누끼 재개(추가 GPU 소량 발생, 유저는 캐릭터를 받음) ②실패·전액 환불(완주분 전량 폐기, 유저는 처음부터 다시) 중 어느 쪽으로 회수할 것인가? 감정 14종까지 완주한 잡이라 ①이 원가상 압도적으로 유리해 보이나, 재개 로직 구현 비용(MEDIUM)과 부분 재개 버그 리스크를 감수할지는 종원 판단.

---

### D-3.1c. 좀비 잡의 유일 탈출구 abandon이 무환불 — 서버 귀책 유실인데 유저가 20E를 포기해야 신규 생성이 열림

**🔴 잔존** · P2 · SMALL · BE  
`aichat/src/main/java/com/spring/aichat/service/ugc/CharacterCreationService.java:495-503, 46-47, 142`

**근거**

CharacterCreationService.java:495-503:
```java
/** 중도 포기 — 무환불 정책(이미 GPU/LLM 비용 발생). */
public void abandon(String username, Long jobId) {
    txTemplate.executeWithoutResult(tx -> {
        CharacterCreationJob job = lockOwnedJob(username, jobId);
        if (job.getStatus().isTerminal()) return; // 멱등
        job.fail("유저 중도 포기");                 // ← refundEnergy 호출 없음
    });
    log.info("[UGC] 중도 포기: username={}, jobId={}", username, jobId);
}
```
`UgcPipelineWorker.failAndRefund`(L791-807)는 `job.getEnergyCharged()`를 전액 환불하지만, `abandon`은 그 경로를 타지 않고 `job.fail()`만 호출한다.

차단 게이트 — CharacterCreationService.java:46-47, 142:
```java
private static final List<CreationJobStatus> ACTIVE_STATUSES =
    Arrays.stream(CreationJobStatus.values()).filter(CreationJobStatus::isActive).toList();
...
if (jobRepository.existsByUserIdAndStatusIn(user.getId(), ACTIVE_STATUSES)) {
    throw new BadRequestException("이미 진행 중인 캐릭터 생성이 있어요. 완료하거나 정리한 뒤 다시 시도해 주세요.");
}
```
`CreationJobStatus.isActive()`(L54-56) = `!isTerminal()`이므로 BINDING·POSTPROCESSING 좀비는 active로 집계된다.

결과적으로 D-3.1a/b/d로 좀비가 생긴 유저의 선택지는 두 개뿐이다: (1) 영원히 UGC 캐릭터를 못 만든다 (2) abandon으로 20E를 포기하고 푼다. 무환불 정책의 전제인 '이미 GPU 비용 발생'은 유저 귀책 이탈에는 타당하지만 **서버 재기동 유실에는 성립하지 않는다** — 유저는 완주 직전까지 갔다.

BINDING 케이스는 특히 부당하다: 15컷 누끼까지 전부 성공했고 남은 것은 DB insert 한 번이었다.

**수정안**

① 근본 수정은 D-3.1a/b/d의 스윕이다(좀비가 안 생기면 이 문제도 안 생긴다) — 그것을 선행하고, 이 건은 잔여 안전망으로 처리한다.
② `CharacterCreationJob`에 서버 귀책 마킹을 추가하거나(예: 스윕이 회수 실패 시 `markRecoveryFailed()`), 더 단순하게는 `abandon`에서 **최종 진행 구간(POSTPROCESSING/BINDING)에서의 포기는 전액 환불**로 분기한다:
```java
public void abandon(String username, Long jobId) {
    txTemplate.executeWithoutResult(tx -> {
        CharacterCreationJob job = lockOwnedJob(username, jobId);
        if (job.getStatus().isTerminal()) return;
        boolean serverFault = job.getStatus() == CreationJobStatus.POSTPROCESSING
                           || job.getStatus() == CreationJobStatus.BINDING;
        job.fail("유저 중도 포기");
        if (serverFault) { /* UgcPipelineWorker.failAndRefund와 동일한 환불 블록 */ }
    });
}
```
(POSTPROCESSING/BINDING은 유저 개입 지점이 아니라 '유저가 자발적으로 포기할 이유가 없는 구간'이므로, 이 구간의 abandon 요청은 정의상 좀비 탈출이다.)
③ 환불 로직 중복을 피하려면 `UgcPipelineWorker.failAndRefund(jobId, reason)`를 그대로 재사용하고 `abandon`은 상태 분기만 하도록 할 것.
④ 프론트: 진행 카드가 30분 이상 POSTPROCESSING/BINDING이면 '정리하기(환불)' 문구를 노출하도록 카피 조정(StudioCreateFlow).

**제품 결정 연동**: 블록 C 25E 인상으로 포기 시 손실이 20E→25E. 블록 D 무관. 무환불 정책 자체는 docs/14 §G·§6 어디에도 재작업 금지로 지정돼 있지 않으므로 변경 가능하다.

**❓ 결정 필요**: 서버 귀책(배포·크래시)으로 고착된 잡을 유저가 abandon할 때 환불할 것인가? 현행 무환불의 명분은 '이미 GPU 비용 발생'인데, 이 경우 조작자는 GPU를 이미 태웠고 유저도 산출물을 못 받는 양측 손실 상태다. 선택지: (a) 최종 구간(POSTPROCESSING/BINDING) abandon은 전액 환불 (b) 스윕이 회수 실패로 마킹한 잡만 환불 (c) 현행 유지하고 CS 수동 처리. 조작자 원가 부담과 신뢰 비용의 트레이드오프라 오너 결정이 필요하다.

---

### D-3.1d. BASE_PROCESSING·EMOTIONS_PROCESSING의 fal(Qwen) 구간 유실도 스테일 스윕 부재 — 원가 최대 구간이 무회수 (docs/13 미열거)

**🔴 잔존** · P1 · MEDIUM · BE  
`aichat/src/main/java/com/spring/aichat/service/scheduler/UgcJobScheduler.java:136-155 (특히 L141 주석) / src/main/java/com/spring/aichat/service/ugc/UgcPipelineWorker.java:418-436`

**근거**

UgcJobScheduler.java:136-155 — 스윕 javadoc이 이 사각을 **명시적으로 자인**한다:
```java
/**
 * [2026-07-21 리뷰 픽스] 캐릭터 잡 CONCEPT_PROCESSING 스테일 스윕 — ...
 * (BASE/EMOTIONS의 fal(Qwen) 구간 유실은 별도 태스크 — requestId 선확보 이관 예정)
 */
@Scheduled(fixedRate = 5 * 60 * 1000)
public void recoverStaleConceptJobs() {
    ...
    jobRepository.findByStatusAndUpdatedAtBefore(CreationJobStatus.CONCEPT_PROCESSING, cutoff);
```
'이관 예정'이라 적힌 태스크는 docs/13 이후 커밋(3115edc/6809945/af4d9e8/cab6b3e/8299f4b) 어디에도 들어가지 않았다.

사각의 실체 — UgcPipelineWorker.java:418-436, 감정 파생은 fal SDK future로 시작하고 RunPod 키는 **WF-2 제출 시점에야** 생긴다:
```java
private void submitEmotionDerivation(Long jobId, EmotionTag tag, Long fixedSeed) {
    CharacterCreationJob job = jobRepository.findById(jobId).orElse(null);
    if (job == null || job.getStatus().isTerminal() || job.getBaseStandingKey() == null) return;
    ...
    poseEditClient.edit(new PoseEditClient.EditRequest(...))
        .whenComplete((result, err) -> { ... });   // ← in-memory future. 재기동 시 소멸
}
```
Qwen 구간이 진행 중인 잡의 스크래치에는 해당 tag의 `EMOTION_REFINE:*` 키가 아직 없다. 프로세스가 죽으면 future가 사라지고 웹훅도 없다 → 그 tag는 DERIVING에 영구 정지 → `checkEmotionsSettled`(L823-832)의 `allMatch(READY||FAILED)`가 영원히 false → EMOTIONS_PROCESSING 영구 고착.

폴러(L86-104)는 EMOTIONS_PROCESSING을 조회하지만 키가 없으므로 no-op. 스윕은 CONCEPT_PROCESSING만 본다. BASE_PROCESSING도 동형(`runBaseStage` L219~의 Qwen 2패스 구간).

금전 규모가 가장 크다: EMOTIONS_PROCESSING 시점의 누적 energyCharged는 6+4+8 = **18E**(블록 C 이후 6+5+11 = 22E)이고, 조작자는 이미 스탠딩 후보 배치 + 감정 14종 중 완료분의 fal/RunPod 비용을 지불한 상태다. yml 주석(application.yml:192)이 `stage-emotions-cost: 8   # 원가 최대 구간`이라 명시.

노출 창도 가장 넓다: 감정 14종 병렬 파생은 실측 수 분~십수 분이며, 그 전 구간에서 배포가 걸리면 유실.

**수정안**

두 단계로 나눈다.
① 즉시(안전망): D-3.1a의 `recoverStaleCharacterJobs` 스테일 리스트에 BASE_PROCESSING·EMOTIONS_PROCESSING을 추가하고, `UgcPipelineWorker.recoverStaleJob`에서 `hasPendingExternal`가 false인 경우에만 **미결 tag를 재제출**한다(무과금 재시도). 재제출 대상 판별: `json.readEmotions(job.getEmotionAssetsJson())`에서 `s.is(DERIVING)`이고 스크래치에 `EMOTION_REFINE:<tag>` 키가 없는 항목 → `submitEmotionDerivation(jobId, tag, null)`. BASE_PROCESSING도 동형으로 `runBaseStage(jobId)` 재기동(단 후보 누적 정합 확인 필요 — `restartBaseGeneration`은 상태만 되돌리므로 그대로 재호출하면 후보가 중복 누적될 수 있다).
② 근본(원 태스크): fal 호출에 requestId 선확보를 도입해 Qwen 구간에도 외부 키를 남기고(`recordExternalJob(jobId, PENDING 센티널)` → 응답 도착 시 실제 id로 치환), 월드 트랙의 `reattachPending`(UgcWorldPipelineWorker.java:492-505) — `PENDING_SENTINEL`이면 재제출, 실제 id면 `attachAwait`로 재부착 — 패턴을 그대로 이식한다. 월드 트랙에 이미 완성된 레퍼런스 구현이 있으므로 설계 비용이 없다.
③ 재제출 폭주 방지: `touchRecovery()`로 updatedAt을 갱신해 다음 스윕 창까지 같은 잡의 중복 재부착을 막을 것(월드의 L504 대응물).

**제품 결정 연동**: 블록 C 25E(6/5/11/3)에서 감정 스테이지 배분이 8E→11E로 커지므로 이 구간 좀비의 몰수액이 18E→22E로 상승 — 상대적 시급도가 가장 크게 오른다. 블록 D 무관. §G 처분 목록에 UGC 파이프라인 항목 없음.

---

### D-3.2a. 폴러가 status "ERROR"를 무조건 continue — /status 404(잡 퍼지)가 영구히 스킵되고 키도 제거되지 않음

**🔴 잔존** · P1 · SMALL · BE  
`aichat/src/main/java/com/spring/aichat/service/scheduler/UgcJobScheduler.java:93 / src/main/java/com/spring/aichat/external/UgcComfyClient.java:129-141`

**근거**

UgcJobScheduler.java:86-104, 문제의 L93:
```java
for (Map.Entry<String, String> entry : scratch.entrySet()) {
    if (!UgcPipelineWorker.isExternalJobKey(entry.getKey())) continue;
    try {
        UgcComfyClient.JobStatus status = comfyClient.getStatus(entry.getValue());
        if (status.inFlight() || "ERROR".equals(status.status())) continue;   // ← L93
        ...
        worker.onComfyEvent(job.getId(), parsed.stage(), parsed.tag(), status);
    } catch (Exception e) { log.warn(...); }
}
```
"ERROR"는 RunPod가 보내는 값이 아니라 **클라이언트가 예외를 삼키고 합성하는 값**이다 — UgcComfyClient.java:129-141:
```java
public JobStatus getStatus(String jobId) {
    requireConfigured();
    try {
        String responseStr = restClient.get().uri(props.runpod().statusUrl(jobId)).retrieve().body(String.class);
        return parseStatusPayload(jobId, objectMapper.readTree(responseStr));
    } catch (Exception e) {
        log.warn("[UGC-COMFY] status poll failed: jobId={}, {}", jobId, e.getMessage());
        return new JobStatus(jobId, "ERROR", List.of(), e.getMessage(), null, null);   // ← 합성
    }
}
```
따라서 **404(잡 결과 퍼지)·인증 실패·엔드포인트 삭제 같은 영구 실패가 일시적 네트워크 오류와 동일하게 취급되어 무한 스킵**된다. 재시도 카운터도 없고, 스크래치 키도 제거되지 않는다(L93은 `continue`뿐 — `removeExternalJob` 미호출).

JobStatus 술어 정의(UgcComfyClient.java:243-245)상 "ERROR"는 `completed()`·`failed()`·`inFlight()` 어디에도 해당하지 않는 제4의 값이다:
```java
public boolean completed() { return "COMPLETED".equalsIgnoreCase(status); }
public boolean failed()    { return "FAILED"||"CANCELLED"||"TIMED_OUT"; }
public boolean inFlight()  { return "IN_QUEUE"||"IN_PROGRESS"; }
```
→ L93의 스킵을 단순 삭제하면 `onComfyEvent`가 `!completed()` 경로로 빠져 **일시적 폴 실패 1회가 `retryStageOrFail`의 재시도 예산을 태운다**(UgcPipelineWorker.java:660-664). 그래서 이 가드가 존재하는 것이며, 수정은 삭제가 아니라 영구/일시 구분이어야 한다.

재현: ① 서버 30분+ 다운(배포 롤백, 인시던트) ② RunPod가 완료 잡 결과를 보존 기간 경과로 퍼지 ③ 재기동 후 폴러가 `/status/{id}` → 404 → `getStatus` catch → status="ERROR" ④ L93 continue ⑤ 1분마다 영원히 반복. 스크래치의 GOLDEN 키는 그대로 남는다 → 이 잔존 키가 D-3.2b의 스윕 스킵을 유발해 데드락을 완성한다.

**수정안**

`UgcJobScheduler.pollPendingComfyJobs`에서 영구 실패와 일시 실패를 구분한다.
① `UgcComfyClient.getStatus`가 HTTP 404를 별도 상태로 알리도록 한다 — 예: catch를 `HttpClientErrorException.NotFound`와 그 외로 분리해 404는 `new JobStatus(jobId, "NOT_FOUND", ...)`를 반환. (또는 `JobStatus`에 `boolean transientError` 플래그 추가.)
② 폴러 L93을 다음으로 교체:
```java
if (status.inFlight()) continue;
if ("NOT_FOUND".equals(status.status())) {          // 영구 — 외부 결과 소실 확정
    log.warn("[UGC-POLL] 외부 잡 결과 소실(404): jobId={}, key={}", job.getId(), entry.getKey());
    worker.failAndRefund(job.getId(), "외부 처리 결과를 확인할 수 없어 작업을 종료했어요. 사용한 에너지는 전액 환불되었어요.");
    break;                                          // 이 잡은 종결 — 나머지 키 순회 중단
}
if ("ERROR".equals(status.status())) continue;      // 일시 — 다음 주기 재시도
```
③ 일시 ERROR가 무한 반복되지 않도록 상한을 둔다: 스크래치에 `K_POLL_ERR:<key>` 카운터(`isExternalJobKey`가 `K_` 프리픽스를 내부 키로 걸러주므로 폴러가 이를 RunPod id로 오인하지 않는다 — UgcPipelineWorker.java:901-903)를 누적해 N회(예: 10회 = 10분) 연속 ERROR면 ②와 동일 처리.
④ D-3.2b와 함께 고칠 것 — 어느 한쪽만 고쳐도 데드락은 풀리지만, ③의 상한이 있어야 D-3.2b의 스윕 위임이 실제로 종결로 이어진다.

**제품 결정 연동**: 블록 C 25E 인상으로 몰수액 상승(CONCEPT_PROCESSING 시점은 6E 불변, 골든 리롤 누적 시 6+2n E). 블록 D 무관. 환불 문구는 기존 `failAndRefund` 카피 관례(UgcJobScheduler.java:153)를 따르면 되므로 별도 결정 불요.

---

### D-3.2b. recoverStaleConceptJobs가 externalJobs 키 존재만으로 전면 스킵 — 폴러가 영구 무능한 키에도 복구를 위임해 데드락 완성

**🔴 잔존** · P1 · SMALL · BE  
`aichat/src/main/java/com/spring/aichat/service/scheduler/UgcJobScheduler.java:143-155 (특히 149-151)`

**근거**

UgcJobScheduler.java:143-155:
```java
@Scheduled(fixedRate = 5 * 60 * 1000)
public void recoverStaleConceptJobs() {
    LocalDateTime cutoff = LocalDateTime.now().minusMinutes(CONCEPT_STALE_MINUTES);   // 30분
    List<CharacterCreationJob> stale =
        jobRepository.findByStatusAndUpdatedAtBefore(CreationJobStatus.CONCEPT_PROCESSING, cutoff);
    for (CharacterCreationJob job : stale) {
        boolean hasPendingExternal = json.readScratch(job.getExternalJobsJson()).keySet().stream()
            .anyMatch(UgcPipelineWorker::isExternalJobKey);
        if (hasPendingExternal) continue;   // ← L151. "WF-1 제출됨 — 폴링 폴백이 복구"
        log.warn("[UGC-POLL] 스테일 CONCEPT_PROCESSING 회수 (LLM 구간 유실): jobId={}", job.getId());
        worker.failAndRefund(job.getId(), "컨셉 처리 시간 초과 — 사용한 에너지는 전액 환불되었어요.");
    }
}
```
L151의 위임 전제('폴링 폴백이 복구')는 **폴러가 그 키를 실제로 처리할 수 있을 때만** 참이다. D-3.2a에 의해 폴러는 "ERROR"를 영구 스킵하므로, 이 위임은 아무도 처리하지 않는 곳으로의 위임이 된다. 스킵 판정에 **경과 시간·시도 횟수 조건이 전혀 없다** — 키가 존재하기만 하면 30분이든 30일이든 무조건 continue다.

데드락 성립(양방향 확인 완료):
- 폴러 측 L93: 키가 있고 /status가 ERROR → `continue`, 키 유지
- 스윕 측 L151: 키가 있음 → `continue`, 종결 안 함
- 두 조건 모두 '키의 존재'를 근거로 상대에게 미루므로 자기유지형 루프다.

또한 스윕의 유일한 회수 액션이 `failAndRefund`뿐이라, 폴러가 처리 가능한 키였더라도 부분 실패 상황에서 취할 수 있는 중간 조치(재제출·재부착)가 없다. 대비 — 월드 트랙 `recoverStaleJob`(UgcWorldPipelineWorker.java:455-485)은 스크래치가 비었으면 재기동, 있으면 `reattachPending`으로 **키가 있어도 능동 복구**한다. 캐릭터 트랙만 '키 있으면 손 떼기'다.

금전: CONCEPT_PROCESSING 고착 시 몰수액은 최초 진입이면 6E(`stage-start-cost`), 황금샷 리롤을 n회 했으면 6+2n E. `restartGoldenGeneration()`(CharacterCreationJob.java:214-219)이 GACHA_WAIT → CONCEPT_PROCESSING으로 되돌리므로 **리롤 유저일수록 고착 확률과 금액이 함께 커진다**. 여기에 ACTIVE_STATUSES 차단(D-3.1c)이 겹쳐 신규 생성 영구 불가.

**수정안**

`recoverStaleConceptJobs`의 위임을 무조건에서 시한부로 바꾼다.
① 2단 컷오프 도입:
```java
private static final int CONCEPT_STALE_MINUTES = 30;          // 기존 — LLM 구간 유실
private static final int EXTERNAL_HARD_STALE_MINUTES = 90;    // 신규 — 폴러 위임 만료
...
for (CharacterCreationJob job : stale) {
    boolean hasPendingExternal = ...;
    if (hasPendingExternal) {
        if (job.getUpdatedAt().isAfter(LocalDateTime.now().minusMinutes(EXTERNAL_HARD_STALE_MINUTES))) continue;
        log.warn("[UGC-POLL] 폴러 위임 만료 — 외부 잡 미결 상태로 하드 스테일: jobId={}", job.getId());
    }
    worker.failAndRefund(job.getId(), "컨셉 처리 시간 초과 — 사용한 에너지는 전액 환불되었어요.");
}
```
② 하드 컷오프 값은 RunPod 최장 큐 대기 + 여유로 잡을 것(현행 CONCEPT_STALE_MINUTES 30의 3배 = 90분 제안). yml 노브로 빼면 운영 조정이 가능하다(`ugc.job.external-hard-stale-minutes`).
③ 종결 시 스크래치를 남겨둘 것 — `job.fail()`은 externalJobs를 보존하므로(CreationJobStatus javadoc L36 "failReason·externalJobs 보존(디버깅)") 별도 조치 불요.
④ D-3.1a/b/d의 통합 스윕(`recoverStaleCharacterJobs`)을 만들 경우 이 로직을 그쪽으로 흡수하고 `recoverStaleConceptJobs`는 제거·통합할 것.

**제품 결정 연동**: 블록 C 25E 인상은 몰수액만 변동(CONCEPT 구간은 stage-start 6E 불변, 골든 리롤 단가 2E도 인상 대상 아님 — 6/5/11/3 배분은 스테이지 진입 차감분만). 블록 D 무관.

---

### D-3.3. 씬 렌더 쓰기 빈에 터미널 가드 부재 — 스윕이 환불한 FAILED 행을 큐 대기 태스크가 COMPLETED로 부활시켜 순 5E에 일러 2장·GPU 2회

**🔴 잔존** · P1 · SMALL · BE  
`aichat/src/main/java/com/spring/aichat/service/illustration/scene/SceneRenderWriteService.java:29-37`

**근거**

SceneRenderWriteService.java:29-37 — 두 전이 모두 상태를 보지 않는다:
```java
@Transactional
public void markSubmitted(Long illustrationId, String providerRequestId) {
    repository.findById(illustrationId).ifPresent(s -> s.markSubmitted(providerRequestId));
}

@Transactional
public void completeRender(Long illustrationId, String publicUrl) {
    repository.findById(illustrationId).ifPresent(s -> s.complete(publicUrl));
}
```
엔티티 전이도 무가드 — SceneIllustration.java:140-159:
```java
public void markSubmitted(String providerRequestId) { this.providerRequestId = providerRequestId; this.status = "GENERATING"; }
public void complete(String imageUrl) { this.imageUrl = imageUrl; this.status = "COMPLETED"; this.errorMessage = null; }
public boolean isTerminal() { return "COMPLETED".equals(status) || "FAILED".equals(status) || "SKIPPED".equals(status); }
```
`isTerminal()`은 이미 존재하는데 쓰기 빈이 호출하지 않는다.

부활 경로가 실재함:
(1) 태스크는 큐에서 대기할 수 있다 — SceneRenderService.java:129 / 190:
```java
sceneRenderExecutor.execute(() -> render(pending.getId(), plan.prompt(), roomId, turnIndex));
```
 TheaterConfig.java:78-88 → `corePoolSize=2, maxPoolSize=8, queueCapacity=32`. ThreadPoolTaskExecutor는 큐가 찰 때까지 풀을 키우지 않으므로 **동시 2건만 실행되고 최대 32건이 대기**한다. `render()`는 폴링 최장 12분(`MAX_POLL_ATTEMPTS * POLL_INTERVAL_MS`, SceneRenderService.java:329)이므로 큐 뒤쪽 태스크는 수십 분~수 시간 대기할 수 있다.
(2) 그 사이 스윕이 행을 죽이고 환불한다 — SceneRenderService.java:140, 150-163:
```java
private static final long STALE_IN_FLIGHT_MINUTES = 20;
...
public SceneView inFlightView(Long roomId) {
    SceneIllustration latest = repository.findTopByChatRoomIdOrderByIdDesc(roomId).filter(s -> !s.isTerminal()).orElse(null);
    if (latest == null) return null;
    if (latest.getUpdatedAt() != null && latest.getUpdatedAt().isBefore(now().minusMinutes(STALE_IN_FLIGHT_MINUTES))) {
        writeService.failRender(latest.getId(), "서버 재시작 등으로 유실된 렌더 — 자동 정리(환불)");
        return null;
    }
```
 `failRender`(WriteService L40-49)는 `refundableOnFail()` 시 `refundManualCharge`로 5E를 환불하고 `markRefunded()`한다.
(3) 이후 큐에서 깨어난 태스크가 그대로 진행한다 — SceneRenderService.java:291, 320:
```java
writeService.markSubmitted(illustrationId, submit.jobId());   // FAILED → GENERATING 부활
...
writeService.completeRender(illustrationId, publicUrl);        // → COMPLETED, imageUrl 채워짐
```
`energyRefunded=true`는 그대로 남으므로 유저는 **환불도 받고 일러도 받는다**.

금전(현재 코드 기준): 씬 일러 수동 요청 5E (application.yml:141 `energy-cost: 5`, SceneIllustrationProperties.java:66-68 기본값 5). 재현 시나리오 — 렌더 풀 정체 중 유저가 수동 요청(−5E) → 20분 초과 스윕으로 FAILED+5E 환불(±0) → 유저 재요청(−5E) → 큐의 1차 태스크가 완주해 1차 행 COMPLETED, 2차도 COMPLETED. **순 5E 지불, 일러 2장 수령, RunPod GPU 2회 지출.** docs/13의 수치가 현재 코드에서도 그대로 성립한다.
부수: `markSubmitted` 부활만으로도 `inFlightView`가 다시 비종결 행을 반환해 같은 방이 '렌더 중' 409에 재차 갇힌다.

**수정안**

`SceneRenderWriteService`의 두 전이에 터미널 가드를 넣는다(엔티티에 이미 `isTerminal()`이 있으므로 한 줄씩).
```java
@Transactional
public void markSubmitted(Long illustrationId, String providerRequestId) {
    repository.findById(illustrationId)
        .filter(s -> !s.isTerminal())      // 추가 — 스윕이 종결한 행의 부활 차단
        .ifPresent(s -> s.markSubmitted(providerRequestId));
}

@Transactional
public void completeRender(Long illustrationId, String publicUrl) {
    repository.findById(illustrationId)
        .filter(s -> !s.isTerminal())      // 추가
        .ifPresent(s -> s.complete(publicUrl));
}
```
(`failRender`는 `refundableOnFail()`의 `!energyRefunded` 조건으로 이미 환불 멱등이므로 가드 불요. 다만 일관성을 위해 같은 filter를 넣어도 무해하다.)

추가 권장(같은 결함의 뿌리 — 별도 커밋 가능):
① `SceneRenderService.render()` 진입부에 종결 확인을 넣어 **GPU 제출 자체를 막는다**. 현재는 가드를 넣어도 RunPod 제출(L290)은 이미 나간 뒤 결과만 버리므로 GPU 비용은 그대로 발생한다:
```java
void render(Long illustrationId, ScenePromptAssembler.ScenePrompt prompt, Long roomId, int turnIndex) {
    if (repository.findById(illustrationId).map(SceneIllustration::isTerminal).orElse(true)) {
        log.info("[SCENE-RENDER] 종결 행 렌더 스킵(스윕 회수분): illustrationId={}", illustrationId);
        return;
    }
    ...
```
이것이 '순 5E로 일러 2장'과 'GPU 2회' 양쪽을 동시에 막는 실질 픽스다. UgcPipelineWorker.java:348-354의 `submitRefine` 터미널 재확인("제출 직전 차단")이 이미 같은 패턴을 쓰고 있으므로 관례 일치.
② `STALE_IN_FLIGHT_MINUTES`(20)와 큐 최장 대기의 정합을 맞출 것 — core=2·queue=32·건당 최장 12분이면 큐 대기가 20분을 상시 초과할 수 있다. `sceneRenderExecutor`의 corePoolSize 상향 또는 큐 용량 축소(초과분은 즉시 RejectedExecution → failRender 환불)를 검토.

**제품 결정 연동**: 우선순위가 **올라간다**. docs/14 §G #6이 레거시 캐릭터 일러 트랙(ModelsLab CG)을 동결·씬 일러로 일원화하기로 확정했으므로 씬 일러가 유일 일러 트랙이 된다. 나아가 docs/16이 씬 일러 ComfyUI 워커에 검열해제+마스킹 노드를 얹어 **시크릿 모드 핵심 BM**으로 승격시켰다 — 이 결함의 GPU 이중 지출과 무료 수령이 곧 핵심 BM의 원가 누수가 된다. docs/14_assets §6 재작업 금지 목록의 '씬당 1회 심의 전송 시작 발제'와는 충돌하지 않는다(그건 발제 시점 정책, 이건 종결 후 쓰기 가드로 계층이 다름).

---

### D-3.4. 감정 컷 리롤에 in-flight 가드 부재 — 2E 이중 과금 + 파생 체인 중복 제출 + externalJobs 키 덮임으로 선발 체인 유실 (월드 트랙엔 있는 가드)

**🔴 잔존** · P1 · SMALL · BE/FE  
`aichat/src/main/java/com/spring/aichat/service/ugc/CharacterCreationService.java:415-443 (특히 428-436)`

**근거**

CharacterCreationService.java:415-443 — 상태 판정이 FAILED 여부(무료/유료)뿐이고, DERIVING/REFINING(진행 중) 검사가 없다:
```java
public void rerollEmotion(String username, Long jobId, EmotionTag tag) {
    if (tag == EmotionTag.NEUTRAL) { throw new BadRequestException("기본 표정은 다시 뽑을 수 없어요."); }
    boolean charged = Boolean.TRUE.equals(txTemplate.execute(tx -> {
        CharacterCreationJob job = lockOwnedJob(username, jobId);
        requireStatus(job, CreationJobStatus.REVIEW_WAIT);
        Map<EmotionTag, EmotionAssetState> emotions = json.readEmotions(job.getEmotionAssetsJson());
        EmotionAssetState state = emotions.get(tag);
        if (state == null) { throw new BadRequestException("알 수 없는 감정 컷입니다."); }
        boolean free = state.is(EmotionAssetState.FAILED);        // ← in-flight 검사 없음
        if (!free) {
            int cost = props.energy().emotionReroll();            // 2E
            User user = findUser(username); user.consumeEnergy(cost); userRepository.save(user);
            job.chargeEnergy(cost);
        }
        worker.resetEmotionForReroll(job, tag);
        return !free;
    }));
    ...
    worker.runEmotionReroll(jobId, tag);
}
```

동일 서비스의 **월드 트랙에는 이 가드가 있다** — UgcWorldService.java:262-283, L268-272:
```java
WorldAssetState state = requireAsset(job, token);
// [리뷰 픽스] in-flight 컷 재리롤 차단 — 이중 과금 + 구세대/신세대 결과 경합으로
// 결제분 산출물이 유실되는 레이스 원천 봉쇄 (더블클릭도 레이트리밋을 통과한다)
if (state.is(WorldAssetState.GENERATING)) {
    throw new BadRequestException("이미 다시 만드는 중이에요. 완료 후 시도해 주세요.");
}
boolean free = state.is(WorldAssetState.FAILED);
```
주석이 이 결함의 재현 조건("더블클릭도 레이트리밋을 통과한다")까지 그대로 적고 있는데 캐릭터 트랙에만 이식되지 않았다.

왜 `requireStatus(REVIEW_WAIT)`가 가드 역할을 못 하나 — 감정 리롤은 잡 상태를 바꾸지 않는다. 골든/스탠딩 리롤은 같은 tx 안에서 `restartGoldenGeneration()`/`restartBaseGeneration()`으로 상태를 CONCEPT_PROCESSING/BASE_PROCESSING으로 옮기므로(CharacterCreationJob.java:214-219, 243-248) 2번째 요청이 `requireStatus`에서 걸린다. 감정 리롤만 REVIEW_WAIT에 머문다 — UgcJobScheduler.java:42-45 주석이 이 설계를 명시("감정 컷 리롤은 잡을 REVIEW_WAIT에 둔 채 WF-2를 재제출"). 즉 **감정 리롤만 구조적으로 무방비**다.

레이트리밋으로 못 막는다 — CharacterCreationController.java:130-137이 `guardRate()`를 걸지만 ApiRateLimiter.java:162-164:
```java
public boolean checkUgcMutation(String username) { return isRateLimited("ugc_mutation", username, 2, 5); }
```
5초당 2회 허용 → **더블클릭 2회는 그대로 통과**하고, 2.5초 간격 클릭은 무한 지속된다.

중복 제출의 실제 비용 — `resetEmotionForReroll`(UgcPipelineWorker.java:932-937)은 `state.derivingAgain(0)`으로 덮을 뿐 진행 중 체인을 취소하지 않고, `runEmotionReroll`(L411-413) → `submitEmotionDerivation`(L418-436)이 fal Qwen edit + WF-2 RunPod를 **매 클릭마다 새로 제출**한다. 잡 상태 가드(L420 `isTerminal()`)만 있어 REVIEW_WAIT는 통과.

키 덮어쓰기 — UgcPipelineWorker.java:881-887:
```java
private void recordExternalJob(Long jobId, String key, String runpodId) {
    mutateJob(jobId, j -> {
        Map<String, String> scratch = json.readScratch(j.getExternalJobsJson());
        scratch.put(key, runpodId);        // ← EMOTION_REFINE:JOY 동일 키를 put으로 덮음
        j.updateExternalJobs(json.writeScratch(scratch));
    });
}
```
키가 `externalKey(stage, token)` = `"EMOTION_REFINE:JOY"`(L896-898)로 tag당 1개뿐이라 2차 제출이 1차 RunPod id를 지운다 → 1차 체인은 스크래치에서 사라져 **폴링 폴백의 추적 대상에서 제외**된다(웹훅이 살아 있으면 도착은 하지만, 웹훅 미도달 환경에서는 결제분이 그대로 유실).

금전: 클릭당 2E 이중·N중 과금(`emotion-reroll-cost: 2`, application.yml:196). 유저 손실 = 2E × (클릭수−1). 조작자 손실 = fal Qwen 편집 + RunPod WF-2 × 중복수.

프론트 완화는 부분적 — StudioCreateFlow.jsx:1461-1473이 in-progress 시 버튼 대신 '생성 중' 텍스트를 렌더하지만, 이 상태는 폴링으로 갱신되므로 클릭 직후~다음 폴 사이에 창이 남고, API 직접 호출은 아예 무방비다.

**수정안**

① BE(핵심): `CharacterCreationService.rerollEmotion`의 L428 직전에 월드 트랙과 동형의 가드를 삽입한다.
```java
EmotionAssetState state = emotions.get(tag);
if (state == null) { throw new BadRequestException("알 수 없는 감정 컷입니다."); }
// [in-flight 가드] 월드 rerollAsset(UgcWorldService:270)과 동형 — 이중 과금 + 세대 경합 봉쇄
if (state.is(EmotionAssetState.DERIVING) || state.is(EmotionAssetState.REFINING)) {
    throw new BadRequestException("이미 다시 만드는 중이에요. 완료 후 시도해 주세요.");
}
boolean free = state.is(EmotionAssetState.FAILED);
```
`lockOwnedJob`이 잡 행을 비관적 락으로 잡으므로(동일 tx) 동시 요청 2건은 직렬화되어 두 번째가 확실히 걸린다.
(CUTTING/DONE는 REVIEW_WAIT에서 나타나지 않지만, 방어적으로 `!state.is(READY) && !state.is(FAILED)`이면 거부하는 화이트리스트 방식이 더 안전하다.)
② BE(부수): `recordExternalJob`이 기존 키를 덮을 때 경고 로그를 남기도록 해 향후 세대 경합을 관측 가능하게 할 것 — `String prev = scratch.put(key, runpodId); if (prev != null) log.warn(...)`.
③ FE(보조): `LucidChat-Front/src/components/studio/StudioCreateFlow.jsx:1461-1473`에서 리롤 클릭 즉시 해당 tag를 로컬 pending 집합에 넣어 폴 갱신 전까지 버튼을 비활성화(낙관적 잠금). 현재는 서버 상태 폴링에만 의존해 클릭~폴 사이 창이 열려 있다.

**제품 결정 연동**: 블록 C(UGC 25E, 6/5/11/3)는 스테이지 진입 차감분만 조정하며 `emotion-reroll-cost: 2`는 대상이 아니므로 금액 불변. 블록 D 무관 — §G 21건에 UGC 위저드 항목 없음. docs/14_assets §6 재작업 금지 목록에도 해당 없음. 월드 트랙에 동일 픽스가 이미 배포돼 있어 정책 일관성 측면에서도 이식이 자명하다.

---

### D-3.5. 월드 장소 배경 재시도가 GENERATING에서도 통과 — 무과금 외부 GPU/LLM 호출 무제한 중복 (1E로 배경 생성 ~20회 증폭)

**🔴 잔존** · P1 · SMALL · BE  
`aichat/src/main/java/com/spring/aichat/service/ugc/UgcWorldService.java:487-502 / src/main/java/com/spring/aichat/service/ugc/UgcWorldPipelineWorker.java:375-410`

**근거**

UgcWorldService.java:487-502 — READY만 거부하고 GENERATING은 통과시킨다:
```java
/** 배경 생성 재시도 — 무료 (FAILED 또는 멈춘 GENERATING 복구). READY는 거부. */
public void retryLocation(String username, Long worldId, String locationKey) {
    Long locationId = txTemplate.execute(tx -> {
        ownedWorldOrThrow(username, worldId);
        requireNotUnderReview(worldId);
        UgcWorldLocation loc = locationRepository
            .findByUgcWorldIdAndLocationKey(worldId, requireLocationKey(locationKey))
            .orElseThrow(() -> new NotFoundException("장소를 찾을 수 없습니다."));
        if (loc.is(UgcWorldLocation.READY)) {
            throw new BadRequestException("이미 완성된 장소예요.");   // ← READY만 차단
        }
        loc.markGenerating();
        return loc.getId();
    });
    worker.generateAddedLocationBackground(worldId, locationId);
}
```
'멈춘 GENERATING 복구'가 의도이지만 **경과 시간·시도 횟수 조건이 없다** — 방금 시작한 GENERATING도 동일하게 통과한다.

워커 측 가드도 무력 — UgcWorldPipelineWorker.java:375-379:
```java
@Async
public void generateAddedLocationBackground(Long worldId, Long locationId) {
    UgcWorld world = worldRepository.findById(worldId).orElse(null);
    UgcWorldLocation loc = locationRepository.findById(locationId).orElse(null);
    if (world == null || loc == null || !loc.is(UgcWorldLocation.GENERATING)) return;   // GENERATING이면 통과
```
서비스가 방금 `markGenerating()`으로 상태를 GENERATING으로 되돌려놓았으므로 이 가드는 항상 통과한다. 이후 L384-395에서 **LLM 프롬프트화(`structuringService.promptizeLocations`) + fal flux-2 배경 생성**을 매번 새로 실행한다. 과금은 없다(`retryLocation`에 `consumeEnergy` 없음).

완주 시에도 상태 가드가 없다 — L400-402:
```java
txTemplate.executeWithoutResult(tx ->
    locationRepository.findById(locationId).ifPresent(l -> l.markReady(finalBgPrompt, assetService.publicUrl(storedKey))));
```
중복 in-flight 전원이 순서 없이 markReady/markFailed를 덮어쓴다.

증폭률 계산(현재 코드 기준): 레이트리밋은 UgcWorldController.java:174-178 `guardRate` → ApiRateLimiter.java:170-172 `isRateLimited("world_mutation", username, 2, 5)` = **5초당 2회**. 배경 생성 1건이 완주하기까지(LLM 프롬프트화 + flux-2, 실측 수십 초, 타임아웃 상한 5분 — L395 `.orTimeout(5, MINUTES)`) 계속 재시도를 쏠 수 있으므로, 1회 완주 전까지 **약 12~120회의 중복 외부 호출**이 성립한다. 장소 추가 1건의 과금은 1E(`world.reroll-cost: 1`, application.yml:218 / UgcWorldService.java:472 `owner.consumeEnergy(props.world().reroll())`).

증폭 경로 추가 — `deleteFailedLocation`(UgcWorldService.java:504-521)이 FAILED 장소 삭제 시 1E를 환불한다:
```java
if (!loc.is(UgcWorldLocation.FAILED)) { throw new BadRequestException("실패한 장소만 삭제할 수 있어요."); }
locationRepository.delete(loc);
User user = findUser(username);
user.refundEnergy(props.world().reroll());
```
→ 장소 추가(−1E) → GENERATING 상태로 retry 스팸(무료 외부 호출 N회) → 최종 FAILED로 착지하면 삭제(+1E) = **순 0E에 외부 호출 N회**. FAILED 착지를 유저가 결정론적으로 만들 수는 없으나(fal 실패/타임아웃 의존), 무료 구간이 열려 있다는 사실 자체가 조작자 GPU 예산 드레인 면이다.

금전: 순전히 조작자 손실(LLM 1콜 + fal flux-2 1장 × 중복수). 유저 에너지는 감소하지 않는다 — 그래서 유저 쪽 억지력이 0이다.

**수정안**

`UgcWorldService.retryLocation`에 스테일 컷오프를 도입해 '멈춘 GENERATING 복구'라는 원 의도는 유지하되 즉시 재시도를 막는다.
① 상수 추가: `private static final int LOCATION_STALE_MINUTES = 6;` (워커 타임아웃 5분 + 여유 1분 — UgcWorldPipelineWorker.java:395 `.orTimeout(5, MINUTES)` 기준).
② L495-498을 교체:
```java
if (loc.is(UgcWorldLocation.READY)) {
    throw new BadRequestException("이미 완성된 장소예요.");
}
if (loc.is(UgcWorldLocation.GENERATING)
    && loc.getUpdatedAt() != null
    && loc.getUpdatedAt().isAfter(LocalDateTime.now().minusMinutes(LOCATION_STALE_MINUTES))) {
    throw new BadRequestException("아직 만드는 중이에요. 잠시 후 다시 시도해 주세요.");
}
loc.markGenerating();   // updatedAt 갱신 → 다음 재시도 창이 다시 6분 뒤로 밀림
```
`markGenerating()`이 @LastModifiedDate로 updatedAt을 갱신하는지 `UgcWorldLocation`에서 확인할 것 — 갱신되지 않으면 명시적 터치 메서드를 추가해야 컷오프가 창을 재설정한다.
③ 워커 완주 측에도 세대 가드를 넣어 늦게 도착한 중복 결과가 신세대 결과를 덮지 않게 한다 — `generateAddedLocationBackground` L400-402의 `markReady`를 `if (l.is(GENERATING))` 조건부로.
④ (권장) `deleteFailedLocation`의 1E 환불과 결합한 무한 무과금 루프를 막으려면, 재시도 횟수를 컬럼(`retry_count`)으로 누적하고 N회 초과 시 재시도를 거부하거나 과금 전환할 것. 이 부분은 별건으로 분리 가능.

**제품 결정 연동**: 블록 C·D 모두 무관 — docs/14 §G 21건에 세계관 빌더 항목이 없고, §5 블록 D 구현 노트에도 UGC 월드 언급이 없다. 다만 docs/14 §C #5가 '**UGC 월드 W0 flash 전환 A/B**'를 백로그로 두고 있어 LLM 프롬프트화 단가가 바뀔 여지는 있으나, 무제한 중복이라는 성질상 단가와 무관하게 수정 필요.

**❓ 결정 필요**: 재시도를 ①GENERATING 전면 거부(단순·안전하나 '진짜로 멈춘' 케이스는 유저가 스스로 못 푼다 — 서버 스윕 필요) ②스테일 컷오프 후 허용(제안안 — 원 의도 보존) ③횟수 상한 후 과금 전환 중 어느 정책으로 갈 것인가? ②를 택할 경우 컷오프 값(제안 6분)이 유저 체감 대기와 GPU 낭비 사이의 트레이드오프 지점이 된다.

---

### D-3.6. 프로필 초안·Stage0 산출에 길이 상한 부재 — name(50)/tagline(100)/role(100)/tone(300) 초과 시 완주한 잡이 바인딩에서 통째로 FAILED (전액 환불되어 무과금 GPU 드레인 성립)

> ↔ **재분류 (2026-08-26 · D-33 · docs/19 결정 안건 D-19): `## D. P1 자산 손실·데이터 정합` → `## B. P0-A 착취 가능(자금·권한)`.**
> 파일 위치는 추적 편의를 위해 이 절에 그대로 두되, **심각도·배치는 B절(P0-A) 기준으로 읽는다** — 배치 3(자산 손실 정지)이 아니라 **배치 1(착취 차단)** 소관이다.
> 사유: 이것은 '가끔 나는 실패'가 아니라 **재현 가능한 순 0E 무한 GPU 드레인**이다. 유저가 프로필 초안에 상한 없는 문자열을 넣으면(`CharacterCreationService.java:267-318 updateProfileDraft`에 길이 검증이 전무하고 DTO에 `@Size`도, 컨트롤러에 `@Valid`도 없다) 파이프라인은 **전 스테이지를 완주해 GPU를 다 쓰고**, 마지막 바인딩(`Character.createUgc`)에서 varchar 초과로 죽어 `failAndRefund`가 **전액 환불**한다. 즉 공격자 비용 0 · 우리 비용은 잡 1건 풀코스이며 **횟수 제한이 없다**. 결제·권한 침해와 같은 등급의 자금 착취면이고, 지표에도 '실패·환불'로만 잡혀 안 보인다.
> ⚠ 수정 시 **환불 자체를 없애지 말 것** — **입력 시점 400 거부**(D-19 방향, 선례 `UgcWorldService.updateWorld:404-421`이 name/intro/lore를 이미 400으로 거부한다) 또는 **Stage 0 정규화 절삭**(`ConceptStructuringService`의 `normalizeShort`를 name/tagline/role/tone까지 확장)으로 **잡이 시작되기 전에** 막아야 한다. 완주 후 실패시키고 환불만 끊으면 정상 유저의 손실로 바뀐다.
> ※ `Character.createUgc`(현 `:762`·`:764`·`:770`)는 절삭 없이 그대로 대입한다 — 실측 재확인(2026-08-26). D-19 표제의 "조용한 절삭" 표현은 느슨한 서술이며, 실제 증상은 **절삭이 아니라 varchar 초과 예외**다.

**↔ 재분류(D → B절 P0-A) · 🔴 잔존** · ~~P1~~ **P0** · SMALL · BE/FE  
`aichat/src/main/java/com/spring/aichat/service/ugc/CharacterCreationService.java:267-318 (특히 302-309) / src/main/java/com/spring/aichat/service/ugc/ConceptStructuringService.java:170-178 / src/main/java/com/spring/aichat/service/ugc/UgcPipelineWorker.java:544-586, 613-616`

**근거**

■ 결정적 근거 — 같은 결함에 대한 부분 픽스가 이미 있고, 4개 필드만 빠졌다. ConceptStructuringService.java:170-178:
```java
// [리뷰 픽스] 신상 4종은 VARCHAR(30/200) 컬럼에 저장된다 — LLM 과다 산출·배열 bullet 펼침이
// 최종 바인딩(전 스테이지 완주 후)에서 varchar 초과로 잡 전체를 죽이지 않도록 정규화·절삭.
StructuredConcept.CharacterProfile fixed = new StructuredConcept.CharacterProfile(
    effectiveName, p.tagline(), p.age(), p.role(), p.personality(), p.tone(),   // ← 4개 전부 무절삭
    p.appearance(), p.clothing(), p.backstory(), p.coreValues(), p.flaws(),
    p.speechQuirks(), dialogue, intro,
    normalizeShort(p.height(), 30), normalizeShort(p.likes(), 200),
    normalizeShort(p.dislikes(), 200), normalizeShort(p.hobby(), 200),
    normalizeShort(p.profileQuote(), 200));
```
주석이 정확히 이 실패 모드를 서술하는데, 정작 `name`·`tagline`·`role`·`tone`은 `normalizeShort`를 통과하지 않는다.

■ 대상 컬럼 (Character.java, ddl-auto=validate 운영):
```java
@Column(nullable = false, length = 50)   private String name;     // L53
@Column(name = "tagline", length = 100)  private String tagline;  // L75
@Column(name = "role", length = 100)     private String role;     // L91
@Column(name = "tone", length = 300)     private String tone;     // L103
```
`Character.createUgc`(L731-771)는 절삭 없이 그대로 대입한다 — `c.tagline = spec.tagline(); c.role = spec.role(); c.tone = spec.tone();`(L742-746). `UgcPipelineWorker.bind` L544-586의 spec 조립에서도 `joinMood`(UgcWorldPipelineWorker.java:540, 200자 절삭)만 절삭 대상이고 나머지는 원문 통과.

■ 유저 입력 경로 — CharacterCreationService.java:267-318 `updateProfileDraft`에 길이 검증이 전무하다. L302-309:
```java
StructuredConcept.CharacterProfile updated = new StructuredConcept.CharacterProfile(
    or(req.name(), p.name()), patch(req.tagline(), p.tagline()), p.age(),
    patch(req.role(), p.role()), patch(req.personality(), p.personality()), patch(req.tone(), p.tone()),
    ...
```
`or`(L320-322)/`patch`(L328-332)는 trim만 하고 길이를 보지 않는다. L268-273의 유일한 검증은 `moderationService.assertRawConceptAllowed(combined)`인데 UgcModerationService.java:71-87은 키워드 매칭만 수행(길이 검사 없음).
DTO에도 제약이 없다 — UgcDtos.java:49-53:
```java
public record UpdateProfileRequest(String name, String tagline, String role,
                                   String personality, String tone, ...) {}   // @Size 없음
```
컨트롤러도 `@Valid` 없이 `@RequestBody UgcDtos.UpdateProfileRequest request`만 받는다(CharacterCreationController.java:123-127). 대조적으로 `UgcWorldService.addLocation`(L441-455)은 displayName 100자·description 상한을 명시 검증한다 — 월드 트랙에만 있는 규율.

■ 실패 지점 — UgcPipelineWorker.java:588-616:
```java
Long characterId = txTemplate.execute(status -> {
    Character character = Character.createUgc(spec);
    ...
    character = characterRepository.save(character);   // ← PSQL: value too long for type character varying(100)
    ...
});
} catch (Exception e) {
    log.error("[UGC-WORKER] 바인딩 실패: jobId={}", jobId, e);
    failAndRefund(jobId, "캐릭터 등록 실패: " + e.getMessage());   // ← DB 예외 원문이 유저 노출
}
```

■ 심각도 재평가 (docs/13 표현 갱신): docs/13은 "20E 지불 후 마지막 단계에서 통째로 실패"라 적었으나, `failAndRefund`(L791-807)가 `job.getEnergyCharged()`를 **전액 환불**한다. 실제 성질은 다르다 —
(a) 유저: 에너지 손실 0. 30분+ 위저드 작업과 선택한 15컷 전량 소실.
(b) 조작자: 황금샷 + 스탠딩 후보 + Qwen 감정 14종 + WF-2 15회 + WF-3 누끼 15회 = **캐릭터 1건 풀 GPU 원가 전액이 산출물 0으로 소각**. docs/14 §C #5가 "25E = 실수취 T3 기준 전형 원가 손익분기"라 명시했으므로 이 손실은 정확히 25E 상당.
(c) 잡이 FAILED(terminal)가 되어 동시 1잡 게이트(L142)가 즉시 해제된다 → **순 0E로 무한 반복 가능**. tagline을 101자로 설정한 뒤 위저드를 완주시키기만 하면 매 사이클마다 조작자가 캐릭터 1건 GPU를 부담한다. docs/13 B절(P0-A, 착취 가능·자금) 기준을 충족하는 재분류 후보다.
(d) 사고 트리거도 낮다 — `tagline`(100자)은 '한 줄 캐치프레이즈'(ConceptStructuringService.java:54)라 유저가 조금 길게 쓰면 넘고, **Stage0 LLM 산출 자체가 절삭 없이 통과**하므로 유저가 아무것도 하지 않아도 발생할 수 있다.
(e) 부수: `bind()` L535-539의 `promoteToCharacterAsset` 16회가 tx 밖에서 선행하므로 실패 시 `characters/{slug}/` S3 객체 16개가 매 사이클 고아로 누적된다.

**수정안**

3중 방어로 넣는다(어느 하나만으로는 경로가 남는다).
① **Stage0 산출 절삭** — `ConceptStructuringService.java:172-178`의 기존 `normalizeShort` 블록에 4개 필드를 편입한다:
```java
StructuredConcept.CharacterProfile fixed = new StructuredConcept.CharacterProfile(
    normalizeShort(effectiveName, 50), normalizeShort(p.tagline(), 100), p.age(),
    normalizeShort(p.role(), 100), p.personality(), normalizeShort(p.tone(), 300),
    ...
```
(personality/appearance/clothing/backstory/coreValues/flaws/speechQuirks/firstGreeting/introNarration은 TEXT 컬럼이라 절삭 불요 — Character.java L97/124/128/132/136/140/144/203/206 확인 완료.)
② **유저 입력 거부** — `CharacterCreationService.updateProfileDraft` L273의 모더레이션 게이트 직후에 길이 검증을 추가한다(절삭이 아니라 400 거부 — 유저 텍스트를 조용히 자르면 편집 의도가 소실되므로):
```java
requireMax(req.name(), 50, "이름");
requireMax(req.tagline(), 100, "한 줄 소개");
requireMax(req.role(), 100, "역할");
requireMax(req.tone(), 300, "말투");
```
`requireMax`는 `UgcWorldService.addLocation`(L443-455)의 검증 관례를 따를 것. 같은 필드를 다루는 `UgcCharacterService.updateTexts`(L88-94, name/tagline/personality/tone)도 동일 검증이 없으므로 함께 처리한다 — 그쪽은 이미 바인딩된 Character를 직접 수정해 500을 낸다.
③ **최종 방어** — `UgcPipelineWorker.bind` L544의 spec 조립에서 `normalizeShort`를 한 번 더 적용해, 구버전 잡 JSON이나 미래의 새 입력 경로가 바인딩을 죽이지 못하게 한다. 이 단계는 절삭(거부 불가 — 이미 GPU를 다 쓴 시점).
④ 실패 메시지에서 DB 예외 원문 노출 차단 — L615 `failAndRefund(jobId, "캐릭터 등록 실패: " + e.getMessage())`를 고정 문구 + 서버 로그 상세로 분리.
⑤ FE: `LucidChat-Front/src/components/studio/StudioCreateFlow.jsx`의 프로필 편집 폼 4개 입력에 `maxLength`를 걸고 잔여 글자수를 표시.
⑥ (별건 분리 가능) `bind()`의 `promoteToCharacterAsset`를 tx 성공 후로 옮기거나, 실패 시 승격분을 정리해 고아 S3 누적을 막을 것.

**제품 결정 연동**: 블록 C(UGC 25E)가 들어가면 환불액이 20E→25E로 오르지만 유저 순비용은 여전히 0 — 즉 **조작자 손실만 25% 증가**한다. 블록 D 무관(§G 21건에 UGC 위저드·프로필 항목 없음). docs/14_assets §6 재작업 금지 목록에도 해당 없음. 다만 블록 B(페르소나 개편, cab6b3e)에서 프로필 문법이 개편됐으므로, ②의 검증 문구는 블록 B의 프로필 카피 톤과 맞출 것.

**❓ 결정 필요**: 유저가 상한을 넘긴 입력을 ①400으로 거부(제안안 — 텍스트 소실 없음, 편집 흐름 1회 중단) ②조용히 절삭(흐름 무중단, 유저가 쓴 문장 끝이 사라짐) 중 어느 쪽으로 처리할 것인가? 또한 이 건이 순 0E 무한 GPU 드레인 경로를 성립시키므로 docs/13 D절(P1)에서 B절(P0-A 착취)로 재분류해 우선순위를 올릴지 종원 판단이 필요하다.

---

### D-4.1. 구독 갱신이 만료일을 now+30으로 리셋 — 잔여 기간 소멸(로그는 '연장'으로 기록)

**🔴 잔존** · P1 · ONE_LINE · BE  
`aichat/src/main/java/com/spring/aichat/domain/payment/UserSubscription.java:78-83`

**근거**

UserSubscription.java:78-83 — 라인 번호까지 docs/13과 동일하게 잔존.
```java
78:    /** 구독 갱신 (+30일) */
79:    public void renew(String newMerchantUid) {
80:        this.expiresAt = LocalDateTime.now().plusDays(30);
81:        this.merchantUid = newMerchantUid;
82:        this.active = true;
83:    }
```
호출부 SubscriptionService.java:51-56 —
```java
51:            if (current.getType() == type) {
52:                // 같은 티어: 연장
53:                current.renew(merchantUid);
55:                log.info("[SUB] Renewed: user={}, type={}, newExpiry={}", ...);
```
`max(now, expiresAt)`가 아니라 무조건 `now`가 기준이라 잔여 10일이 있는 유저가 재결제하면 40일이 아니라 30일이 된다. 로그 문구는 "Renewed"라 운영상 탐지도 안 된다. 도달 경로: PaymentService.java:247-249 `case LUCID_PASS, LUCID_MIDNIGHT_PASS -> subscriptionService.activateSubscription(...)` (결제 확정 /confirm·/webhook 양쪽) + AdminUserService.java:71(관리자 수동 지급).

**수정안**

`UserSubscription.renew()`(UserSubscription.java:80)의 기준을 잔여기간 보존형으로 교체:
```java
LocalDateTime base = (this.expiresAt != null && this.expiresAt.isAfter(LocalDateTime.now()))
    ? this.expiresAt : LocalDateTime.now();
this.expiresAt = base.plusDays(30);
```
(= `max(now, expiresAt) + 30d`). 만료 후 재가입은 `now` 기준이 되어 정상. 로그(SubscriptionService.java:55)에 `oldExpiry→newExpiry`를 같이 남길 것. 티어 변경(업그레이드) 분기(SubscriptionService.java:57-66)는 새 행 생성이라 별도 정책 판단 대상(아래 openQuestion).

**제품 결정 연동**: 블록 D(§G) 무관 — 구독은 docs/14 §C #5에서 "리스트가 전원 현행 유지(구독 14,900/24,900)"로 존속 확정. 오히려 블록 B가 페르소나 슬롯 3/10을 구독에 결합했고(UserPersonaService.java:153-157), docs/14 §D 행정 체크리스트가 "환불 산식" 법적 문서화·PG 가맹 심사를 요구하므로 구독 기간 정합은 런칭 전 필수. 게이트오프로 회피 불가.

**❓ 결정 필요**: 티어 업그레이드(LUCID_PASS→MIDNIGHT) 시 기존 행을 deactivate하고 새 30일을 발급하는 현행 동작에서 **하위 티어 잔여 기간을 어떻게 처리할지**(소멸 / 일할 환산 이월 / 상위 티어 일수로 환산). 현재는 조용히 소멸이며 이것도 유상 자산 소각이다. docs/14 §C #5가 리스트가 현행 유지를 확정했으므로 두 티어 병존은 계속 발생한다.

---

### D-4.2. renew()가 merchantUid를 덮어써 이전 회차 주문번호 이력이 소실 — 환불 조회 키 상실

**🔴 잔존** · P1 · SMALL · BE  
`aichat/src/main/java/com/spring/aichat/domain/payment/UserSubscription.java:81`

**근거**

UserSubscription.java:50-52, 81 — 구독 1행이 최신 결제 1건의 주문번호만 보유한다.
```java
50:    /** 결제 추적용 주문번호 */
51:    @Column(name = "merchant_uid", length = 50)
52:    private String merchantUid;
...
81:        this.merchantUid = newMerchantUid;
```
조회 키는 이것뿐이다 — UserSubscriptionRepository.java:18-19
```java
18:    /** [Phase 6] 환불 회수용 — 주문번호로 구독 조회 */
19:    Optional<UserSubscription> findByMerchantUid(String merchantUid);
```
따라서 3회차까지 갱신된 구독에서 1·2회차 주문을 환불하면 `findByMerchantUid(1회차uid)`가 empty가 된다. 별도 구독-주문 이력 테이블은 존재하지 않는다(`grep user_subscriptions src/main/resources/db/migration/` → 0건, 스키마는 ddl-auto로만 생성).

**수정안**

두 안 중 택1.
(A) 최소 수정(권장·BE 단독): `RefundService.clawback()`(RefundService.java:95)이 merchantUid 대신 **Order의 유저**로 회수 대상을 찾도록 변경 — `subscriptionService.deactivateForUserIfTier(order.getUser(), order.getProductType().toSubscriptionType(), order.getMerchantUid())` 형태로, `findByUser_IdAndActiveTrue`로 활성 구독을 잡고 티어가 일치할 때만 회수. `merchantUid` 불일치는 '이전 회차 환불'로 간주해 감사 로그에 명시.
(B) 이력 보존(내구성 우선): **V29+ 결제·구독 묶음 마이그레이션**(2026-08-26 정정 · D-33 — V28은 `V28__orders_imp_uid_unique.sql`이 점유했다)으로 `user_subscription_payments(subscription_id, merchant_uid, paid_at, days_added)` 이력 테이블을 만들고 `renew()`가 append. `findByMerchantUid`는 이력 테이블 경유로 교체. D-4.1의 잔여기간 계산·부분 환불 산식과도 맞물린다.
어느 안이든 `renew()`가 이전 uid를 무조건 버리는 현행(라인 81)은 유지 불가.

**제품 결정 연동**: 블록 D 무관. 단 docs/16(시크릿=핵심 BM)·docs/14 §D의 PG 가맹 심사 항목과 직결 — 환불 처리 정합성은 PG 심사·소비자 분쟁의 1차 점검 대상이다. (b)안 선택 시 이 스키마가 D-4.4의 unique index와 같은 마이그레이션(구독 묶음, V29+)에 묶인다.

**❓ 결정 필요**: 갱신 이력이 있는 구독에서 **과거 회차 1건만 환불**할 때의 제품 정책: (a) 그 회차분 30일만 만료일에서 차감, (b) 구독 전체 즉시 해지, (c) 과거 회차 환불 자체를 CS에서 금지. docs/14 §D가 '환불 산식'을 법적 문서에 명시하라고 요구하므로 이 결정이 약관 문구를 좌우한다.

---

### D-4.3. 환불 혜택 회수가 조용히 no-op — 돈만 나가고 구독 유지, 예외·경고·감사 흔적 전무

**🔴 잔존** · P1 · SMALL · BE  
`aichat/src/main/java/com/spring/aichat/service/payment/SubscriptionService.java:101-117 (호출부 RefundService.java:95)`

**근거**

RefundService.java:84-97 — clawback이 merchantUid만 넘긴다(라인 95는 docs/13 지목 라인과 정확히 일치).
```java
95:            case LUCID_PASS, LUCID_MIDNIGHT_PASS -> subscriptionService.deactivateByMerchantUid(order.getMerchantUid());
```
SubscriptionService.java:101-117 — `.ifPresent(...)`만 있고 **else 분기·로그·예외가 전혀 없다**.
```java
102:    public void deactivateByMerchantUid(String merchantUid) {
103:        subscriptionRepository.findByMerchantUid(merchantUid).ifPresent(sub -> {
...
116:        });
117:    }
```
그런데 RefundService는 이 호출 **직후 성공을 전제로** 감사 로그를 남긴다(RefundService.java:69-77): `order.markRefunded()` → `auditLogService.record(actor, "REFUND_EXECUTE", ...)` → `log.info("[REFUND] Done: ...")`. 즉 PortOne 취소는 성공(라인 60)해 실제로 돈이 나가고, 혜택은 그대로 남고, 감사 로그에는 "환불 완료"로 찍힌다. D-4.2가 원인일 때(과거 회차) 재현되며, 그 외에도 구독 행이 이미 스케줄러로 삭제/비활성화된 경우 동일.

**수정안**

① `SubscriptionService.deactivateByMerchantUid`를 **boolean 반환**으로 바꾸고 `orElseGet(() -> { log.error(...); return false; })` 형태로 미발견을 명시. ② `RefundService.clawback(Order)`도 회수 성공/실패를 반환하도록 시그니처 변경 — SECRET_PASS_24H·SECRET_UNLOCK_PERMANENT(RefundService.java:93-94, SecretModeService.java:249/262도 동일한 `.ifPresent` 패턴)까지 같은 처리. ③ `RefundService.refund`(라인 68-77)에서 회수 실패 시 감사 로그 액션을 `REFUND_EXECUTE`가 아니라 `REFUND_CLAWBACK_FAILED`로 분리 기록하고 `log.error` + 운영 알림. 트랜잭션은 롤백하지 말 것(클래스 주석 27-30행의 '유저에게 유리한 방향' 원칙 유지) — 대신 **반드시 흔적을 남긴다**가 수정의 핵심.

**제품 결정 연동**: 블록 D 무관. docs/14 §D 행정(PG 가맹·환불 산식 명시)과 직결하고, docs/16이 시크릿 3종 결제를 핵심 BM으로 올렸으므로 시크릿 패스 회수(RefundService.java:93-94)의 동일 패턴까지 같은 커밋에서 처리하는 것이 이득.

**❓ 결정 필요**: 회수 실패 시 환불 자체를 막을지(= PortOne 취소 전에 회수 대상 존재를 선검증) 여부. 선검증으로 바꾸면 '돈도 혜택도 잃을 위험'을 기각한 기존 순서 근거(RefundService.java:27-30 주석)와 충돌하므로 오너 판단 필요.

---

### D-4.4. '유저당 활성 구독 1개' DB 제약 부재 — 인덱스가 비유니크, 마이그레이션도 없음 (제안된 V25는 블록 B가 선점 → ~~V26~~ **V29+로** · 2026-08-26 정정 · D-33)

**🔴 잔존** · P1 · SMALL · BE/DB_MIGRATION  
`aichat/src/main/java/com/spring/aichat/domain/payment/UserSubscription.java:23-26`

**근거**

UserSubscription.java:11-26 — 설계 주석은 '최대 1개'를 선언하는데 실제 인덱스는 **비유니크**다.
```java
15: * - 유저당 활성 구독은 최대 1개 (Tier 업그레이드 시 기존 구독 비활성화)
...
23: @Table(name = "user_subscriptions", indexes = {
24:     @Index(name = "idx_sub_user_active", columnList = "user_id, active"),
25:     @Index(name = "idx_sub_expires", columnList = "expires_at")
26: })
```
`@Index`에 `unique = true`가 없고, DB 레벨 보강도 없다: `grep -rn "user_subscriptions" src/main/resources/db/migration/` → **0건**(테이블 자체가 Flyway가 아니라 ddl-auto 산물, application-local.yml:21 `ddl-auto: update` / application-prod.yml:24 `validate`).
★ 제안된 버전 번호는 사용 불가: `src/main/resources/db/migration/` 실측 V1~V25 연속 점유, **V25__persona_profile_lens.sql은 블록 B 커밋 cab6b3e(2026-08-15)가 선점**(파일 1행 "[2026-08-15 블록 B 페르소나 개편]"). ~~다음 가용 버전 = V26~~
**갱신 (2026-08-26 · D-33)** — 그 뒤로도 번호가 더 나갔다: **V26·V27은 블록 D**(`V26__drop_bpm_not_null.sql` · `V27__drop_promotion_not_null.sql`), **V28은 결제 정합**(`V28__orders_imp_uid_unique.sql`, B-1.2)이 점유했다. **구독 부분 유니크는 V29부터** 배정한다 — 착수 시점에 `ls src/main/resources/db/migration/`로 다시 확인할 것. ⚠ 같은 번호로 새 파일을 만들면 **로컬은 checksum mismatch로 죽고 프로드는 조용히 통과**해 스키마가 갈린다(CLAUDE.md §2-2).

**수정안**

`src/main/resources/db/migration/V29__subscription_active_unique.sql` 신규 작성 (2026-08-26 정정 · D-33 — V26·V27 블록 D, V28 결제 점유. 결제 V28 파일에 함께 넣는 것도 가능하나 **같은 번호로 새 파일을 만들지는 말 것**):
```sql
-- 1) 기존 중복 활성 행 정리 (인덱스 생성 실패 방지)
WITH ranked AS (
  SELECT id, ROW_NUMBER() OVER (PARTITION BY user_id ORDER BY expires_at DESC, id DESC) rn
  FROM user_subscriptions WHERE active = true)
UPDATE user_subscriptions s SET active = false
FROM ranked r WHERE s.id = r.id AND r.rn > 1;
-- 2) 부분 유니크 인덱스
CREATE UNIQUE INDEX uq_sub_user_active ON user_subscriptions(user_id) WHERE active = true;
```
엔티티 쪽은 부분 인덱스를 JPA `@Index`로 표현할 수 없으므로 UserSubscription.java:23-26에는 주석으로 'DB 레벨 부분 유니크(V29)'만 명시하고 인덱스 선언은 그대로 둘 것(ddl-auto=validate가 부분 인덱스를 검증하지 않으므로 충돌 없음). 배포 전 `application.yml`의 미커밋 `flyway.enabled: false`를 `true`로 되돌릴 것.

**제품 결정 연동**: 블록 D 무관. 다만 **블록 B가 이미 V25를 소비했다는 사실이 docs/13 배치3 지시서를 무효화**하므로(그 뒤 V26·V27은 블록 D, V28은 결제가 더 가져갔다 — 2026-08-26 정정 · D-33), 배치3 착수 시 버전 번호를 반드시 재확인할 것. 또 블록 B가 페르소나 슬롯을 구독에 결합했지만 슬롯 판정은 `User.subscriptionTier`(UserPersonaService.java:153-157)를 읽으므로 이 결함의 500 폭발 반경에는 들어가지 않는다.

**❓ 결정 필요**: 프로드 DB에 이미 중복 활성 행이 존재할 경우 **어느 행을 살릴지**: (a) 만료일이 가장 먼 행(위 SQL 기본값·유저 유리), (b) 최신 결제 행. 그리고 (a)로 정리해 죽는 행에 대해 보상(에너지/기간 이월)을 할지. 마이그레이션 실행 전 `SELECT user_id, count(*) FROM user_subscriptions WHERE active GROUP BY 1 HAVING count(*)>1`로 실제 존재 여부부터 확인 필요.

---

### D-4.5. activateSubscription이 락 없는 read-then-write — 활성 2행 생성 레이스, 발생 시 구독 조회 경로 전부 500이 최대 30일 지속

**🔴 잔존** · P1 · MEDIUM · BE  
`aichat/src/main/java/com/spring/aichat/service/payment/SubscriptionService.java:41-80`

**근거**

SubscriptionService.java:41-72 — 조회 후 분기해서 INSERT하는데 유저 단위 락이 없다.
```java
41:    @Transactional
42:    public UserSubscription activateSubscription(User user, SubscriptionType type, String merchantUid) {
44:        Optional<UserSubscription> existing = subscriptionRepository.findByUser_IdAndActiveTrue(user.getId());
...
62:                subscription = UserSubscription.create(user, type, merchantUid);
63:                subscriptionRepository.save(subscription);   // 업그레이드 분기
...
69:            subscription = UserSubscription.create(user, type, merchantUid);
70:            subscriptionRepository.save(subscription);       // 신규 분기
```
동시성 방어는 **주문 단위 락뿐**이다 — PaymentService.java:93/111이 `orderRepository.findByMerchantUidForUpdate(...)`로 같은 merchantUid의 /confirm·/webhook만 직렬화한다. **서로 다른 merchantUid 2건**(유저 더블 결제, LUCID_PASS+MIDNIGHT 동시, 또는 관리자 지급 AdminUserService.java:71 `"ADMIN_GRANT_"+currentTimeMillis()`와 결제 동시)은 상호배제되지 않고 두 트랜잭션이 모두 `existing=empty`를 보고 각각 INSERT한다. D-4.4로 DB 제약도 없어 2행이 커밋된다.
결과: 반환 타입이 `Optional`이라 2행이면 Spring Data가 `IncorrectResultSizeDataAccessException`을 던진다 — UserSubscriptionRepository.java:16 `Optional<UserSubscription> findByUser_IdAndActiveTrue(Long userId)`. 이 예외를 잡는 핸들러가 없어 GlobalExceptionHandler.java:73-79 `@ExceptionHandler(Exception.class)` → **500**. 폭발 반경: UserController.java:132-150 `/users/subscription`, SubscriptionService.java:87(관리자 해제), :109(환불 회수), :137(getActiveSubscription→isSubscriber/hasSubscriptionTier), :44(재결제 자체). `expiresAt`가 지날 때까지(최대 30일) 자가 치유되지 않는다.

**수정안**

3중 방어를 같은 커밋에.
① **비관적 락**: `UserSubscriptionRepository`에 `@Lock(LockModeType.PESSIMISTIC_WRITE) @Query("SELECT s FROM UserSubscription s WHERE s.user.id = :userId AND s.active = true") List<UserSubscription> lockActiveByUser(Long userId)` 추가. 유저 행이 없으면 락 대상이 없어 레이스가 남으므로 `userRepository.findByIdForUpdate(userId)`로 **User 행을 먼저 잠그고** activateSubscription 전체를 그 락 안에서 수행하는 편이 확실.
② **반환 타입 방어**: `findByUser_IdAndActiveTrue`를 `Optional`이 아니라 `List<UserSubscription> findByUser_IdAndActiveTrueOrderByExpiresAtDesc(Long)`로 바꾸고, `SubscriptionService.getActiveSubscription`에서 size>1이면 `log.error`+첫 행 반환(500 대신 degrade) — D-4.4 인덱스가 적용되기 전/실패한 환경에서도 조회가 죽지 않게. 호출부 5곳(SubscriptionService:44/87/109/137, UserController:137) 동시 수정.
③ D-4.4의 **V29** 부분 유니크 인덱스가 최종 방어선 — INSERT 시 `DataIntegrityViolationException`을 잡아 재조회 후 renew로 전환하는 catch를 activateSubscription에 추가.

**제품 결정 연동**: 블록 D 무관. docs/14 §C #5가 구독 존속을 확정했고 docs/14 §D의 PG 심사가 걸려 있어 '결제는 됐는데 구독 조회 500' 상태는 런칭 차단 사유급. 단 이 원자는 D-4.4(부분 유니크, V29+)와 **반드시 한 세트로** 처리해야 한다 — 인덱스만 넣고 catch를 안 넣으면 레이스가 500 대신 결제 실패로 바뀐다.

---

### D-5.1. 극장 prefetch가 N+1이 아니라 현재 배치 ID N으로 생성·저장 — 유저가 보고 있는 배치를 통째로 덮어씀

**🔴 잔존** · P1 · SMALL · BE  
`aichat/src/main/java/com/spring/aichat/service/theater/TheaterBatchGenerator.java:291-293 (진입 TheaterService.java:145-164)`

**근거**

TheaterService.java:145-164 — `nextBatchId`를 **존재 검사와 로그에만** 쓰고, 생성기에는 넘기지 않는다.
```java
145:            int nextBatchId = state.getCurrentBatchId() + 1;
146:            if (batchCache.existsBatch(roomId, nextBatchId)) { ... }
...
161:            TheaterBatchGenerator.GenerateParams params = new TheaterBatchGenerator.GenerateParams(
162:                room, state, null, null, false, false);
163:
164:            batchGenerator.generateNextBatch(params);
165:            log.info("🎭 [PREFETCH] Done | roomId={} | nextBatchId={}", roomId, nextBatchId);
```
`GenerateParams`(TheaterBatchGenerator.java:90-115)에는 batchId 필드가 **아예 없다**. 생성기는 항상 state의 현재 값으로 저장한다 — TheaterBatchGenerator.java:291-293
```java
291:        // ─── 캐시 저장 ───
292:        batchCache.putBatch(room.getId(), state.getCurrentBatchId(), batch);
293:        batchCache.putRawBatch(room.getId(), state.getCurrentBatchId(), llmOutput);
```
`putBatch`는 같은 키에 무조건 덮어쓴다(TheaterBatchCacheService.java:92-102, 키 `theater:batch:{roomId}:{batchId}`, TTL 6h). prefetch 시점에 state는 아직 advanceBatch 전이므로 `getCurrentBatchId()==N` — 즉 유저가 지금 재생 중인 N번 배치가 새 LLM 결과로 교체된다.
도달 경로 실재: FE가 현재 배치 70% 지점에서 자동 발사 — LucidChat-Front\src\hooks\useTheaterStream.js:136-138
```js
136:      if (!prefetchFiredRef.current && nextIdx / total >= 0.7 && !currentBatch.chapterEndAfter) {
137:        prefetchFiredRef.current = true;
138:        triggerPrefetch(roomId);
```
→ TheaterPlayApi.js:45-51 `POST /theater/rooms/{roomId}/prefetch` → TheaterController.java:90-96 → `theaterService.prefetchNextBatchAsync(roomId)`.

**수정안**

`GenerateParams`(TheaterBatchGenerator.java:90-115)에 `int targetBatchId` 필드를 추가하고(기존 5/6인자 보조 생성자는 `state.getCurrentBatchId()`를 기본값으로 채워 하위호환 유지), 생성기 내부의 `state.getCurrentBatchId()` 사용처 전부를 `params.targetBatchId()`로 치환: :292 putBatch, :293 putRawBatch, :353 sceneRefId(D-5.5), :462 persistSceneLogs batchId(D-5.4), :281 markUsed, :218/:230/:317 로그. `TheaterService.prefetchNextBatchAsync`(:161-162)는 `nextBatchId`를, `requestNextBatch`(:112-113)는 `batchId`(:84)를 넘긴다. 그 다음 prefetch가 state를 변경하는 부작용(:266 `state.markMajorBranchDoneInChapter()`)이 미래 배치 기준으로 동작해도 되는지 함께 점검할 것 — prefetch는 @Transactional이 아니라 현재는 detached라 유실되고 있다.

**제품 결정 연동**: 블록 D 무관 — 오히려 **극장은 존속 확정**이다: docs/14 §C #6 "엔딩=자유·스토리만 게이트 오프(코드 보존·**극장 유지**)", §G ✅유지 확정 "극장 세이브 슬롯(극장 전용 문법 격리)", docs/14 #4 "극장 스탯 분리 — 극장 코드 무변경". 즉 이 결함은 게이트오프로 소멸하지 않으며 반드시 고쳐야 한다. 블록 B(페르소나)도 극장 무접촉이라 충돌 없음.

---

### D-5.2. prefetch 중복 방지 가드가 N+1 키를 검사하는데 실제 기록은 N 키 — 가드가 영구 무력, 배치당 LLM 호출 2배

**🔴 잔존** · P1 · ONE_LINE · BE  
`aichat/src/main/java/com/spring/aichat/service/theater/TheaterService.java:146-149`

**근거**

TheaterService.java:145-149 — 검사 키와 기록 키가 어긋나 있다.
```java
145:            int nextBatchId = state.getCurrentBatchId() + 1;
146:            if (batchCache.existsBatch(roomId, nextBatchId)) {
147:                log.debug("🎭 [PREFETCH] Already cached | roomId={} | nextBatchId={}", roomId, nextBatchId);
148:                return CompletableFuture.completedFuture(null);
149:            }
```
D-5.1대로 실제 기록은 `theater:batch:{roomId}:N`이므로 `existsBatch(roomId, N+1)`은 **항상 false** → prefetch가 매번 완주해 LLM을 호출한다. 그리고 소비 후 `state.advanceBatch()`(TheaterService.java:210)로 currentBatchId가 N+1이 되면 `requestNextBatch`가 `batchCache.getBatch(roomId, N+1)`을 보는데(TheaterService.java:84-93) 그 키는 비어 있어 **캐시 MISS → 또 생성**(:95-98,115). 결과적으로 유저가 보는 배치 1개당 LLM 배치 생성이 2회 발생하고, prefetch 1회분은 100% 폐기된다(에너지는 prefetch에서 차감하지 않으므로 손실은 전액 사업자 부담 — :91,:98 `if (!prefetch) chargeBatchEnergy(username)`).
부가: prefetch가 매번 완주하므로 D-5.4(중복 씬로그)·D-5.5(중복 일러 트리거)도 매 배치 재현된다.

**수정안**

D-5.1의 `targetBatchId` 배선이 들어가면 `existsBatch(roomId, nextBatchId)`가 실제 기록 키와 일치해 자동 해소된다. 다만 **검증 항목으로 분리해 남길 것**: 수정 후 `theater:batch:{roomId}:{N+1}` 키 생성 → 다음 `requestNextBatch`가 "Batch cache HIT"(TheaterService.java:89)를 찍는지 로그로 확인. 추가로 동시 prefetch 중복 발사를 막으려면 `SETNX theater:prefetch:lock:{roomId}`(TTL 60s) 형태의 in-flight 가드를 `prefetchNextBatchAsync` 진입부에 두는 것을 권장(FE는 배치마다 1회만 쏘지만 새로고침·다중 탭에서 중복 가능).

**제품 결정 연동**: 블록 D 무관(극장 존속). docs/14 §C의 원가 관리(부스트 3.6-flash 치환·max_tokens 캡 하향으로 원가 30% 절감 시도)와 정면으로 맞물린다 — 극장 배치 LLM 비용이 실제로는 장부의 2배로 나가고 있으므로, 원가 실측(docs/14 §C "런칭 후 1주 원가 실측 대조") 전에 반드시 선행 수정해야 실측치가 오염되지 않는다.

---

### D-5.3. 배치 소비 확정이 덮어써진(한 씬도 안 본) 배치의 호감도·씬 수·화자를 영속 — 실제 감상분은 소실

**🔴 잔존** · P1 · SMALL · BE  
`aichat/src/main/java/com/spring/aichat/service/theater/TheaterService.java:190-210`

**근거**

TheaterService.java:190-210 — 소비 확정이 **캐시에서 다시 읽은** 배치를 신뢰한다. D-5.1로 그 캐시는 이미 prefetch 결과로 교체돼 있다.
```java
190:        SceneBatch batch = batchCache.getBatch(roomId, consumedBatchId)
191:            .orElseThrow(() -> new BusinessException(ErrorCode.INTERNAL_ERROR,
192:                "소비된 배치 캐시가 없습니다. batchId=" + consumedBatchId));
194:        // 호감도 변화 영속화
195:        if (batch.heroineAffectionDeltas() != null && !batch.heroineAffectionDeltas().isEmpty()) {
199:                affectionRepository.findByRoom_IdAndCharacter_Id(roomId, heroineId)
200:                    .ifPresent(a -> { a.applyDelta(delta); a.recordAppearance(...); });
...
207:        int scenesInBatch = batch.scenes() == null ? 0 : batch.scenes().size();
208:        state.addScenes(scenesInBatch);
209:        state.setCurrentHeroine(batch.speakerHeroineId());
210:        state.advanceBatch();
```
앞단 가드(:181-188)는 `consumedBatchId != state.getCurrentBatchId()`만 본다 — batchId는 여전히 N으로 일치하므로 통과한다. 즉 **내용물이 바뀐 것을 탐지할 수단이 없다**. 결과: 유저가 실제로 본 씬들의 호감도 델타는 영원히 반영되지 않고, 보지도 않은 배치의 델타(±최대 2/히로인, TheaterBatchGenerator.java:65)·씬 수·화자(state.currentHeroine)가 확정된다. 화자 오염은 다음 배치의 등장 히로인 분배까지 왜곡한다.

**수정안**

D-5.1로 근본 원인은 사라지지만, **재발 탐지 장치를 같이 넣을 것**. `SceneBatch`에 이미 `batchId()` 필드가 있으므로(TheaterBatchGenerator.java:254에서 재조립 시 사용) `onBatchConsumed`(TheaterService.java:190 직후)에 `if (batch.batchId() != consumedBatchId) { log.error(...); throw new BusinessException(ErrorCode.STALE_CLIENT_STATE, ...); }` 가드를 추가. 더 강하게는 배치 생성 시 랜덤 `generationToken`을 SceneBatch에 실어 FE가 consume 시 되돌려주게 하고 서버가 대조하는 방식(덮어쓰기 자체를 소비 단계에서 차단). 최소한 전자는 넣을 것 — 이 결함은 조용히 데이터만 틀어지므로 탐지 수단이 없으면 회귀를 못 잡는다.

**제품 결정 연동**: 블록 D 무관(극장 존속 확정). 다만 docs/14 #4가 "극장 아바타 5축은 현행 유지 — 극장 코드 무변경"을 선언했으므로, 이 수정은 스탯 체계를 건드리지 말고 **배치 동일성 검증에만 한정**해야 블록 B 결정과 충돌하지 않는다.

---

### D-5.4. prefetch가 동일 batchId·동일 시퀀스로 Mongo 씬로그를 이중 기록 — 기록 패널·이전 버튼·기억 주입이 중복 오염

**🔴 잔존** · P1 · SMALL · BE  
`aichat/src/main/java/com/spring/aichat/service/theater/TheaterBatchGenerator.java:433-494 (호출 :271)`

**근거**

TheaterBatchGenerator.java:271에서 생성 시점마다 무조건 영속한다(prefetch 여부 인자 없음).
```java
270:        // ─── [v2] Scene 로그 MongoDB 영구 저장 ───
271:        persistSceneLogs(room, state, speaker, batch);
```
시퀀스 기준값이 전부 **state 현재값**이라 prefetch본과 실제본이 완전히 겹친다 — :436-437, :462-465
```java
436:        long globalSeqStart = state.getTotalSceneCount();
437:        int chapterSeqStart = state.getScenesInCurrentChapter();
...
462:                .batchId(state.getCurrentBatchId())
463:                .sceneIndexInBatch(idx)
464:                .sceneSeqInChapter(chapterSeqStart + idx)
465:                .globalSceneSeq(globalSeqStart + idx)
...
488:            sceneLogRepository.saveAll(logs);
```
Mongo에는 유니크 제약이 없어(TheaterSceneLogRepository.java는 순수 조회 메서드만) 두 세트가 공존한다. 소비처 전부가 오염된다 — TheaterHistoryService.java:41-45(챕터 기록 패널, `...OrderBySceneSeqInChapterAsc`), :56-57(페이지네이션 `findByRoomIdOrderByGlobalSceneSeqAsc` — totalElements도 2배), :78-79(`findTop30ByRoomIdOrderByGlobalSceneSeqDesc` — '이전' 버튼 + 최근 기억 주입). 즉 유저는 보지도 않은 씬이 기록에 섞여 보이고, LLM 컨텍스트에도 상충되는 두 버전이 함께 주입된다(토큰도 2배).

**수정안**

D-5.1로 batchId만 바뀌면 **여전히 시퀀스가 겹친다**(globalSeqStart가 state 기준이라 N+1 배치가 N과 같은 seq에서 시작). 따라서 별도 수정 필요, 두 안 중 택1.
(A) 권장 — **prefetch 시 씬로그를 쓰지 않는다**: `GenerateParams`에 `boolean prefetch`를 추가해 :271을 `if (!params.prefetch()) persistSceneLogs(...)`로 감싸고, 실제 영속은 `TheaterService.onBatchConsumed`(:190 이후, 실제 소비 확정 시점)로 이동. 소비 시점엔 state가 정확하므로 시퀀스 오염이 구조적으로 불가능해진다.
(B) 오프셋 보정: `persistSceneLogs`가 `targetBatchId`와 함께 `seqOffset`(= 현재 배치의 씬 수)을 받아 계산. 단 현재 배치의 씬 수를 prefetch 시점에 알아야 해 캐시 재조회가 필요하고, 분기로 캐시가 evict되면(TheaterBatchCacheService.invalidateBatchesFrom :137-149) 폐기된 prefetch본의 로그가 그대로 남는 2차 결함이 생긴다 → (A) 권장.
어느 안이든 기존 프로드/dev Mongo의 중복분 정리 스크립트가 별도로 필요하다(`roomId+globalSceneSeq` 중복 그룹에서 최신 1건만 남기기).

**제품 결정 연동**: 블록 D 무관(극장 존속). §G ✅ "극장 세이브 슬롯 유지"와 관련 — TheaterSaveLoadService가 씬로그 시퀀스를 참조한다면 세이브/로드 복원까지 오염될 수 있으므로 (A)안 적용 시 세이브 슬롯 경로 회귀 테스트 필요.

**❓ 결정 필요**: 이미 쌓인 중복 씬로그를 정리할지, 정리한다면 어느 쪽을 정본으로 볼지(중복 그룹 중 나중 것 = prefetch본 = 유저가 안 본 쪽일 가능성이 높음 → 오히려 **먼저 쓰인 쪽**을 남겨야 한다). 극장 플레이 이력이 있는 방이 몇 개인지에 따라 정리 자체를 생략할 수도 있다.

---

### D-5.5. 폐기될 prefetch 배치가 자동 노트·일러스트·배경 생성을 트리거 — 외부 GPU 중복 지출 + sceneRefId 키 충돌

**🔴 잔존** · P2 · SMALL · BE  
`aichat/src/main/java/com/spring/aichat/service/theater/TheaterBatchGenerator.java:304-307, 333-355, 391-427`

**근거**

생성기 말미의 부작용 3종이 prefetch/실사용을 구분하지 않는다 — TheaterBatchGenerator.java:304-314
```java
304:        captureAutoMomentsFromBatch(room, state, batch);
305:        if (batch.chapterEndAfter()) {
306:            captureChapterEndFromBatch(room, state, llmOutput, batch);
307:        }
...
314:        prefetchBatchLocations(speaker.getId(), batch);
```
① `captureAutoMomentsFromBatch`(:333-355)는 호감도 절대값 ≥2인 히로인을 잡아 `autoNoteService.captureAffectionMoment(...)`를 호출하는데, 키가 **현재 배치 ID 기반**이라 실제 배치본과 충돌한다 — :353
```java
353:        String sceneRefId = state.getCurrentBatchId() + ":auto-moment";
```
즉 `N:auto-moment` 노트/일러가 prefetch본과 실제본 양쪽에서 만들어지며 서로 덮는다(주석 :330-331은 "비용 절감을 위해 절대값 최대 1명만 트리거"라고 명시 — 그 절감 의도가 무효화된다).
② `captureChapterEndFromBatch`(:360-368)는 챕터 종료 일러를 캡처 — prefetch본이 `chapterEndAfter=true`면 유저가 도달하지도 않은 챕터 엔드 일러가 생성된다.
③ `prefetchBatchLocations`(:391-427)는 배치 내 location마다 `BackgroundGenerationService.resolveBackground`를 호출 → 캐시 미스면 비동기 배경 생성(:404-420, 실패는 :422-424에서 삼킴). 폐기될 배치의 장소들에 대해 배경 GPU가 돈다.
D-5.2로 prefetch가 **매 배치 완주**하므로 이 3종이 배치마다 재현된다.

**수정안**

D-5.1의 `GenerateParams`에 `boolean prefetch`(또는 D-5.4 (A)안과 동일 필드)를 추가하고 TheaterBatchGenerator.java:304-314를 조건부로:
- `captureAutoMomentsFromBatch`/`captureChapterEndFromBatch`: prefetch면 **호출하지 않는다**. 실제 소비 확정 시점(`TheaterService.onBatchConsumed`, :190-210)으로 이동하는 것이 정합적 — 그래야 유저가 실제로 본 배치에만 자동 노트/일러가 붙는다.
- `sceneRefId`(:353): `state.getCurrentBatchId()` → `params.targetBatchId()`로 교체(소비 시점 이동 시에는 consumedBatchId).
- `prefetchBatchLocations`(:314): 배경 캐시 워밍은 prefetch의 본래 목적과 맞으므로 **유지 가능**하나, D-5.1 수정 후 N+1 배치의 장소를 워밍하게 되므로 그대로 두면 오히려 의도대로 동작한다. 단 폐기 배치(분기 선택으로 evict되는 경우)의 배경 생성 낭비는 남으므로 `BackgroundGenerationService` 측 캐시 히트율 로그로 사후 관측 권장.

**제품 결정 연동**: 블록 D 무관(극장 존속). §G #6 "레거시 캐릭터 일러 트랙(ModelsLab CG) 동결·씬 일러로 일원화"는 **캐릭터 CG 트랙**을 가리키고, 여기 트리거되는 것은 극장 자동 노트/씬 일러·동적 배경(Fal.ai 트랙, TheaterBatchGenerator.java:419 주석 "Theater 모드는 Fal.ai 트랙")이라 동결 대상이 아니다 — 즉 살아 있는 지출 경로이며 수정 대상이다.

---

### D-6.1. 이벤트 선택 스트림의 ASSISTANT 저장이 ChatLogPersister(retry+deadletter)를 우회

**🔴 잔존** · P3 · ONE_LINE · BE  
`aichat/src/main/java/com/spring/aichat/service/stream/ChatStreamService.java:587`

**근거**

`sendEventSelectStream`(메서드 시작 :499)의 ASSISTANT 저장 — ChatStreamService.java:586-588
```java
586:            // ── MongoDB: ASSISTANT 저장 ──
587:            String assistantLogId = saveAssistantLog(roomId, parsed);
588:            cacheService.evictRoomInfo(roomId);
```
주석까지 정상 경로(:351-354)와 동일한데 호출 대상만 다르다(정상 경로는 :362 `chatLogPersister.saveWithRetry`). 실패 시 조용히 null → SSE는 :590 `sendFinalResult(...)`로 정상 전송 → 유저에겐 응답이 보이지만 history에는 없다.
도달성: 엔드포인트는 살아 있다 — StoryController.java:120-137 `@PostMapping("/events/select")`. 다만 **FE 호출부는 현재 사문**이다: `sendEventSelectStream`은 UseChatStream.js:23에 정의되고 ChatPage.jsx:26 / ChatPageV2.jsx:40에서 import되지만 실제 호출 `sendEventSelectStream(` 은 소스 어디에도 없다(ChatPage.jsx:1850 주석 "Bug 1 Fix: sendEventSelectStream → sendAutoDirectorResponse"). 즉 현 UI로는 도달 불가, 직접 API 호출로는 도달 가능(그래서 B-3 energyCost 착취면이 남아 있다).

**수정안**

D-6.5에서 `saveAssistantLog`를 persister 위임형으로 고치면 이 호출부는 무수정으로 해소된다. 별도 작업 불요. 단 B-3(클라이언트 energyCost)·§G #13 처리 시 이 엔드포인트를 존치할지 제거할지가 먼저 결정돼야 하므로 **D-6.5 일괄 수정 대상에는 포함하되 개별 검증 우선순위는 최하**로 둘 것.

**제품 결정 연동**: §G #13(디렉터 3분기 카드 — "골격 유지, 고정 3톤→맥락 가변 제안 + energyCost 서버 판정(docs/13 P0 픽스 세트)")의 사정권. 골격 유지이므로 엔드포인트는 남지만 **FE 호출부가 이미 사문**이라, §G #13 집행 시 `/events/select`를 아예 제거하고 `sendAutoDirectorResponse`(BRANCH)로 일원화하면 이 원자는 MOOT가 된다. 그 결정 전에는 수정 비용이 0에 가까우므로(D-6.5에 흡수) 그냥 같이 고치는 편이 싸다.

---

### D-6.2. '계속 지켜보기' 스트림의 ASSISTANT 저장이 ChatLogPersister를 우회

**🔴 잔존** · P2 · ONE_LINE · BE  
`aichat/src/main/java/com/spring/aichat/service/stream/ChatStreamService.java:698`

**근거**

`sendDirectorWatchStream`(메서드 시작 :607)의 ASSISTANT 저장 — ChatStreamService.java:697-701
```java
697:
698:            String assistantLogId = saveAssistantLog(roomId, parsed);
699:            cacheService.evictRoomInfo(roomId);
700:
701:            sendFinalResult(emitter, response, false, assistantLogId, false, null);
```
도달성 확인: StoryController.java:139-155 `@PostMapping("/events/watch")` 살아 있음. FE 실호출 2곳 — LucidChat-Front\src\pages\ChatPage.jsx:1904 `await sendDirectorWatchStream(roomId, {...})`, **ChatPageV2.jsx:2730 동일 호출**(V2 STORY 방도 이 V1 서비스를 탄다). 에너지 1E를 이미 차감한 뒤(:617 부근 TX-1)이므로 저장 실패 시 유료 턴이 통째로 증발한다.

**수정안**

D-6.5의 `saveAssistantLog` 수정으로 자동 해소(호출부 무수정). 검증 시 ChatPageV2 경로(V2 STORY 방에서 지켜보기)도 함께 확인할 것 — docs/13은 V1만 언급했으나 실제로는 V2 방에서도 이 경로가 쓰인다.

**제품 결정 연동**: §G #7의 사정권(디렉터 문법 정리). 문면상 존치가 확정된 것은 '시간 넘기기'뿐이라 지켜보기는 제거 후보다. 다만 ChatPageV2가 실사용 중이므로 제거는 V2 UX 변경을 동반한다 — 블록 D 착수 전에 존폐 결정이 선행돼야 한다. 수정 자체는 D-6.5에 흡수돼 추가 비용이 0이므로, 결정을 기다리지 말고 같이 고쳐도 손해가 없다.

**❓ 결정 필요**: §G #7("V1 디렉터 잔여 — INTERLUDE/TRANSITION/AWAY 소비 경로·activeDirector* 필드 정리, '시간 넘기기'만 페이싱 도구로 존치") 집행 시 **'👀 계속 지켜보기'를 존치할지 제거할지**가 문서에 명시돼 있지 않다. 제거 대상이라면 이 원자는 MOOT가 되고, 존치라면 V2에서도 쓰이므로 정식 수리 대상이다. 오너 확인 필요.

---

### D-6.3. '시간 넘기기' 스트림의 ASSISTANT 저장이 ChatLogPersister를 우회

**🔴 잔존** · P2 · ONE_LINE · BE  
`aichat/src/main/java/com/spring/aichat/service/stream/ChatStreamService.java:818`

**근거**

`sendTimeSkipStream`(메서드 시작 :737)의 ASSISTANT 저장 — ChatStreamService.java:817-819
```java
817:
818:            String assistantLogId = saveAssistantLog(roomId, parsed);
819:            cacheService.evictRoomInfo(roomId);
```
도달성: StoryController.java:157-173 `@PostMapping("/time-skip")`. FE 실호출 2곳 — ChatPage.jsx:2004, ChatPageV2.jsx:2830. 과금은 `TIME_SKIP_ENERGY_COST = 1`(ChatStreamService.java:118, 차감 :755). 시간 넘기기는 시간/장소 전환 나레이션을 만드는 턴이라 로그가 유실되면 **다음 턴 LLM이 전환 사실 자체를 모르게 되어** 배경·시간대 서술이 역행한다.

**수정안**

D-6.5의 `saveAssistantLog` 수정으로 자동 해소. 4개 중 **유일하게 존속이 확정된 경로**이므로 회귀 테스트 우선순위 1순위로 둘 것.

**제품 결정 연동**: §G #7이 "'시간 넘기기'만 페이싱 도구로 존치"라고 **명시적으로 존치 확정**했다. 즉 블록 D 집행 후에도 살아남는 경로이며, 4개 중 유일하게 게이트오프로 소멸할 가능성이 없다 → 무조건 수리 대상.

---

### D-6.4. 자동 디렉터 응답(AWAY/BRANCH) 스트림의 ASSISTANT 저장이 ChatLogPersister를 우회

**🔴 잔존** · P2 · ONE_LINE · BE  
`aichat/src/main/java/com/spring/aichat/service/stream/ChatStreamService.java:1741`

**근거**

`sendAutoDirectorResponse`(메서드 시작 :1568)의 ASSISTANT 저장 — ChatStreamService.java:1738-1744
```java
1738:            // [Phase 6-Illust] illustration_scene_hint 영속화
1739:            applyParsedToRoom(roomId, parsed);
1740:
1741:            String assistantLogId = saveAssistantLog(roomId, parsed);
1742:            cacheService.evictRoomInfo(roomId);
1743:
1744:            sendFinalResult(emitter, response, false, assistantLogId, false, locationTransition);
```
처리 타입은 :1571-1572 `isAway = "AWAY".equalsIgnoreCase(directiveType)` / `isBranchResponse = "BRANCH".equalsIgnoreCase(directiveType)`, 과금 :1585-1586 `int cost = 1; room.getUser().consumeEnergy(cost);`.
도달성: StoryController.java:96-114 `@PostMapping("/director/auto-respond")`. FE 실호출 4곳 — ChatPage.jsx:498·1885 부근(이벤트 카드 선택이 이 경로로 통합됨: :1848-1850 주석 "Bug 1 Fix: sendEventSelectStream → sendAutoDirectorResponse"), ChatPageV2.jsx:635·2711. **현재 이벤트 카드 선택의 실질 경로가 이것**이라 4개 중 트래픽이 가장 많다.

**수정안**

D-6.5의 `saveAssistantLog` 수정으로 자동 해소. 4개 중 실사용 빈도가 가장 높으므로(이벤트 카드 선택이 여기로 통합) 시간 넘기기와 함께 회귀 검증 필수. 추가로 :1739 `applyParsedToRoom`이 로그 저장보다 **먼저** 실행되므로, 로그 유실 시 방 상태(장소/일러 힌트)만 갱신되고 그 원인 로그는 없는 비대칭이 생긴다 — persister 도입 후 null 반환 시 `log.error`로 상관관계를 남길 것.

**제품 결정 연동**: §G #7(AWAY·activeDirector* 정리)과 §G #13(디렉터 3분기 카드 골격 유지)이 **한 메서드 안에서 갈린다**. BRANCH 경로는 유지 확정이므로 메서드가 통째로 사라질 일은 없고, 따라서 로그 영속 수리는 유효하다. 블록 D 집행 시 :1593-1597 `setDirectorInterlude` (activeDirector* 필드 계열)가 정리 대상인지 함께 판단할 것.

**❓ 결정 필요**: §G #7 집행 시 AWAY directive를 제거하면 이 메서드의 AWAY 분기(:1571, :1588-1591 `room.updateEventStatus("ONGOING")`)만 사라지고 BRANCH 분기는 §G #13(골격 유지)로 남는다 — 즉 메서드 자체는 존속. 이 분리 처리가 맞는지 오너 확인.

---

### D-6.5. [근본] saveAssistantLog 헬퍼가 retry·deadletter 없이 직접 save하고 예외를 삼킴 — 4개 경로 공통 원인

**🔴 잔존** · P1 · SMALL · BE  
`aichat/src/main/java/com/spring/aichat/service/stream/ChatStreamService.java:1209-1219`

**근거**

docs/13이 지목한 라인(1210)과 거의 동일 위치에 그대로 존재 — ChatStreamService.java:1209-1219
```java
1209:    private String saveAssistantLog(Long roomId, ParsedLlmResult parsed) {
1210:        try {
1211:            ChatLogDocument assistantLog = ChatLogDocument.assistantWithThought(
1212:                roomId, parsed.cleanJson(), parsed.combinedDialogue(),
1213:                parsed.mainEmotion(), null, parsed.innerThought(), parsed.scenesJson());
1214:            return chatLogRepository.save(assistantLog).getId();
1215:        } catch (Exception e) {
1216:            log.error("⚠️ ASSISTANT log save failed | roomId={}", roomId, e);
1217:            return null;
1218:        }
1219:    }
```
`chatLogRepository`는 순수 Mongo 리포지토리(:75 `private final ChatLogMongoRepository chatLogRepository;`)다. 반면 같은 클래스가 persister를 **이미 주입받고 있다**(:112 `private final ChatLogPersister chatLogPersister;`) — 정상 경로만 그것을 쓴다(:362). ChatLogPersister가 제공하는 3회 지수백오프 재시도(:54-72)와 deadletter 보존(:78-95)이 이 4개 경로에서만 통째로 누락된다. 클래스 주석이 명시한 피해 그대로 재현된다(ChatLogPersister.java:18-20: "history 누락 + 다음 LLM 컨텍스트 손실 + 새로고침 시 응답 영구 손실 + 스탯 변화 원인 추적 불가 → 정합성 파괴").
부가 발견: :1213이 `parsed.innerThought()`를 **무조건** 넘기는데 정상 경로 :357은 `ChatModePolicy.supportsInnerThought(jpa.room().getChatMode()) ? parsed.innerThought() : null` 게이트를 통과시킨다 → 4개 경로는 속마음 정책 게이트도 함께 우회한다.

**수정안**

`saveAssistantLog`를 정상 경로(:355-369)와 동형으로 재작성. chatMode 게이트를 적용하려면 roomId만으로는 부족하므로 시그니처에 `ChatMode`(또는 `ChatRoom`)를 추가하고 호출부 4곳(:587/:698/:818/:1741)에 `jpa.room()`을 넘긴다:
```java
private String saveAssistantLog(Long roomId, ChatMode mode, ParsedLlmResult parsed) {
    String inner = ChatModePolicy.supportsInnerThought(mode) ? parsed.innerThought() : null;
    ChatLogDocument doc = ChatLogDocument.assistantWithThought(
        roomId, parsed.cleanJson(), parsed.combinedDialogue(),
        parsed.mainEmotion(), null, inner, parsed.scenesJson());
    ChatLogDocument saved = chatLogPersister.saveWithRetry(doc);
    if (saved != null) return saved.getId();
    log.error("⚠️ [CHAT-LOG] ASSISTANT_LOG_PERSIST_FAILED — deadlettered | roomId={} | path={}", roomId, mode);
    return null;
}
```
호출부는 `saveAssistantLog(roomId, jpa.room().getChatMode(), parsed)` 한 줄씩 교체. 4개 경로가 한 헬퍼를 공유하므로 **실질 수정은 이 메서드 하나 + 인자 4곳**이다. (참고: `sendMessageStream`의 인라인 블록 :355-369도 이 헬퍼로 수렴시키면 중복이 사라지지만, `hasInnerThought` 반환이 필요해 별도 리팩터 — 필수는 아님.)

**제품 결정 연동**: 블록 D 무관 — 4개 경로 중 최소 2개(시간 넘기기 §G #7 존치 확정, 자동 디렉터 BRANCH §G #13 골격 유지)는 확실히 살아남으므로 이 헬퍼 수정은 어떤 게이트오프 시나리오에서도 낭비가 되지 않는다.

---

### D-6.6. 에너지 회복 벌크 업데이트가 프로필 캐시를 무효화하지 않음 — 최대 30분간 0에너지 표시로 전송 차단 지속

**🔴 잔존** · P1 · MEDIUM · BE  
`aichat/src/main/java/com/spring/aichat/service/scheduler/EnergyRegenScheduler.java:27-45`

**근거**

스케줄러 전체(46줄)에 캐시 참조가 **한 줄도 없다** — EnergyRegenScheduler.java:27-45
```java
27:    /** 비구독자: 10분마다 +1, max 30 */
28:    @Scheduled(fixedRate = 10 * 60 * 1000)
29:    @Transactional
30:    public void regenFreeUsers() {
31:        int count = userRepository.regenFreeUserEnergy();
...
38:    @Scheduled(fixedRate = 5 * 60 * 1000)
40:    public void regenSubscribers() {
41:        int count = userRepository.regenSubscriberEnergy();
```
주입 필드는 `UserRepository` 하나뿐(:25) — `RedisCacheService`가 없다. 갱신은 JPQL 벌크 UPDATE라 엔티티 리스너도 안 탄다(UserRepository.java:44-47 `@Modifying @Query("UPDATE User u SET u.freeEnergy = ...")`).
캐시 TTL은 30분 — RedisCacheService.java:88
```java
88:    public <T> void cacheUserProfile(String username, T profile) { put(USER_PROFILE_PREFIX + username, profile, 30, TimeUnit.MINUTES); }
```
그리고 `/users/me`는 이 캐시를 먼저 읽고 **energy를 그 안에 담아 반환한다** — UserService.java:44-67 (`getUserProfile(...).orElseGet(...)`, :56-58 `user.getEnergy(), user.getFreeEnergy(), user.getPaidEnergy()`).
FE는 이 값으로 전송을 막는다 — LucidChat-Front\src\components\DialogueBox.jsx:491 `const noEnergy = energy <= 0;`, :1051 placeholder "에너지가 부족합니다"; ChatPage.jsx:1370-1371 / ChatPageV2.jsx:2199-2200 `if (text && energy <= 0 && !endingTrigger) { showToast("에너지가 부족합니다...") }`.
결과: 0E로 소진한 유저는 서버 DB에 에너지가 3~6 회복된 뒤에도 캐시가 만료될 때까지(최대 30분) 전송이 막힌다. 반대로 결제·소비 경로는 전부 명시적으로 evict하고 있어(SubscriptionService.java:77/94/114, RefundService.java:71, ChatStreamService.java:1603 등) **스케줄러만 빠져 있는 비대칭**이다.

**수정안**

벌크 UPDATE라 대상 username 목록이 없다는 것이 난점. 세 안 중 택1(권장 순).
(A) **에너지를 프로필 캐시에서 분리**(구조적 정답): `UserService.getMyInfo`(:44-67)가 캐시에서 정적 필드(nickname/email/profileDescription/isAdult/subscriptionTier)만 읽고 `energy/freeEnergy/paidEnergy/freeEnergyMax`는 항상 DB에서 채우도록 변경. `/users/me`는 이미 유저당 저빈도 호출이라 부하 증가가 제한적이고, 결제·소비 경로의 evict 누락 위험도 근본 제거된다.
(B) **회복 대상만 evict**: `regenFreeUserEnergy`를 실행하기 전에 `SELECT u.username FROM User u WHERE u.freeEnergy < 30`로 대상 목록을 뽑아 UPDATE 후 `cacheService.evictUserProfile(name)` 루프. 정확하지만 유저 수에 비례하는 추가 쿼리+Redis DEL N회.
(C) **TTL 단축**: RedisCacheService.java:88의 30분을 회복 주기(5분)보다 짧은 60~120초로. 한 줄이지만 캐시 효용이 크게 준다.
어느 안이든 EnergyRegenScheduler에 `RedisCacheService` 주입이 필요하면 :25 필드 추가. (A)를 권장한다.

**제품 결정 연동**: 블록 D 무관. docs/14 §C #5 "가격 경쟁력은 리스트 인하가 아닌 **지급 밸브**(신규 보너스·프로모션)로" — 지급 밸브 전략은 지급 즉시 반영이 전제이므로 이 캐시 결함은 BM 전략과 직접 충돌한다. 블록 A(로비 재설계)가 토큰/에너지 표시를 로비로 끌어올렸다면 오표시 노출면이 더 넓어졌을 가능성이 있어, 수정 후 로비 에너지 표시도 함께 확인 권장.

**❓ 결정 필요**: (A)안 채택 시 `/users/me`가 매 호출 DB를 읽게 되는데, 현재 FE가 이 엔드포인트를 얼마나 자주 폴링하는지(전송 후·모달 열 때마다 등)에 따라 부하가 달라진다. 캐시 정합 vs DB QPS 중 어느 쪽을 택할지 오너 판단. 참고로 docs/16이 시크릿을 핵심 BM으로 올리면서 에너지 소비가 늘 예정이라 '회복됐는데 못 쓴다'는 체감 손실은 커지는 방향이다.

---

### D-6.7. 데드레터가 방금 실패한 그 MongoDB에 기록 — Mongo 장애 시 retry·deadletter 안전망이 통째로 무의미

**🔴 잔존** · P2 · MEDIUM · BE  
`aichat/src/main/java/com/spring/aichat/service/stream/ChatLogPersister.java:78-95 (+ ChatLogDeadletterRepository.java:8)`

**근거**

데드레터 저장소가 **같은 MongoDB**다 — ChatLogDeadletterRepository.java:1-9
```java
3: import org.springframework.data.mongodb.repository.MongoRepository;
8: public interface ChatLogDeadletterRepository extends MongoRepository<ChatLogDeadletter, String> {
```
엔티티도 Mongo Document — ChatLogDeadletter.java:30 `@Document(collection = "chat_log_deadletter")`.
ChatLogPersister.java:74-95 — 3회 재시도가 전부 실패한 뒤 같은 인프라에 쓴다.
```java
74:        // 모든 재시도 소진 — 데드레터로 보존
75:        log.error("[CHAT-LOG] All {} retries failed, deadlettering | roomId={}", ...);
78:        try {
79:            String payloadJson = objectMapper.writeValueAsString(doc);
88:            deadletterRepository.save(deadletter);
92:        } catch (Exception dle) {
93:            log.error("[CHAT-LOG] Deadletter save also failed — payload lost! | roomId={}", doc.getRoomId(), dle);
```
엔티티 주석 :25-28은 컬렉션 분리 이유를 "인덱스 최적화/오염 방지"로만 설명하고 **장애 격리는 고려하지 않는다**. 3회 연속 실패의 지배적 원인은 Mongo 연결 불가/다운인데, 그 상황에서 :88도 반드시 실패해 :93 경로로 빠지고 페이로드는 로그 문구대로 영구 소실된다. 즉 C-9로 도입한 안전망이 **가장 필요한 시나리오에서만 작동하지 않는다**(일시적 write conflict·타임아웃 같은 국소 실패에서만 유효).

**수정안**

데드레터 싱크를 Mongo 밖으로 옮긴다. 우선순위:
(A) **RDB(PostgreSQL)로 이관** — `ChatLogDeadletter`를 JPA 엔티티로 전환하고 **V29 이후 다음 가용 번호**(D-4.4와 같은 파일 또는 그 다음 번호 — 2026-08-26 정정 · D-33)에 `chat_log_deadletter(id, original_room_id, original_role, payload_json TEXT, error_message, attempt_count, failed_at)` 테이블 추가. 채팅 로그와 저장소가 완전히 분리돼 Mongo 장애를 견딘다. RDB까지 죽었다면 어차피 서비스 전체가 정지 상태라 허용 가능한 실패.
(B) 차선 — 실패 페이로드를 Redis 리스트(`chatlog:deadletter`)에 LPUSH + 별도 배치가 Mongo 복구 후 재밀어넣기. RDB 마이그레이션 없이 가능하지만 Redis도 죽으면 동일 문제.
(C) 최소 — :93의 `log.error`에 **payloadJson 전문을 함께 출력**(현재는 예외만 찍고 본문을 버린다). CloudWatch에서 수동 복구 가능해진다. 최소 이것만이라도 즉시 적용.
권장: (C)를 즉시, (A)를 D-4.4의 구독 마이그레이션(V29+)에 묶어 처리.

**제품 결정 연동**: 블록 D 무관. docs/13 §G가 이 항목을 반박에서 D-6으로 재분류했으므로 배치3 범위에 포함되는 것이 맞다. D-4.4의 구독 마이그레이션과 같은 배치에서 처리하면 파일을 1개로 합칠 수 있다(단 관심사가 달라 **번호 분리 권장** — 롤백 단위를 나눈다).

**❓ 결정 필요**: 데드레터를 어디까지 보장할지 = 운영 비용 판단. (A) RDB 이관은 마이그레이션 1건이지만, 이 페이로드를 실제로 수동 복구할 운영 프로세스(chat_logs 재삽입 도구)가 없으면 보관만 하는 셈이다(엔티티 주석 :22-23이 '수동 복구 도구'를 전제하는데 그 도구가 코드에 없음). 복구 도구까지 만들 것인지, 아니면 (C) 로그 출력으로 끝낼 것인지 오너 결정 필요.

---

## E. P2 로직·진행 불능  (117건)

### E-1.1. 극장 finalizeChapter 실패 시 chapterEnding=true 영구 잔류 — 진행 잠금·재시도 경로 없음

**🔴 잔존** · P1 · ONE_LINE · FE  
`FE/src/hooks/useTheaterStream.js:160-173`

**근거**

useTheaterStream.js:160-173 — setChapterEnding(false)가 try 블록 안에만 있고 finally가 없다.
```
160:      if (chapterEnd || currentBatch.chapterEndAfter) {
161:        setChapterEnding(true);
162:        const report = await finalizeChapter(roomId);
163:        setChapterEnding(false);
164:        if (onChapterEnd) onChapterEnd(report);
165:        return;
166:      }
...
170:    } catch (e) {
171:      console.error("[Theater] Batch transition failed:", e);
172:      if (onError) onError(e);
173:    }
```
finalizeChapter가 throw하면 163이 실행되지 않고 170의 catch로 빠져 chapterEnding이 true로 고착된다. 소비처가 이를 전면 잠금으로 쓴다 — TheaterPlayPage.jsx:247 `if (loadingNext || chapterEnding) return;`(nextScene 차단), :741 `canGoNext={!loadingNext && !chapterEnding}`, :450 키보드 진행 차단, :767-770 `message={chapterEnding ? "Chapter를 마무리하는 중…" : ...}` 오버레이 상주. 즉 일시적 5xx 1회로 '마무리하는 중…'에서 영구 정지하며 새로고침 외 탈출구가 없다. 극장은 docs/14 §C #6에서 '극장 유지'로 명시된 존속 기능이라 도달 가능하다.

**수정안**

useTheaterStream.js nextScene()의 chapterEnd 블록을 중첩 try/finally로 감싼다: `setChapterEnding(true); try { const report = await finalizeChapter(roomId); if (onChapterEnd) onChapterEnd(report); } finally { setChapterEnding(false); }`. 바깥 catch(170)가 onError로 토스트를 띄우므로 유저는 실패를 인지하고 '다음' 버튼으로 재시도할 수 있게 된다. 추가로 TheaterPlayPage에 '리포트 생성 재시도' 버튼을 붙이는 것은 선택 사항(한 줄 픽스만으로 잠금은 해제됨).

**제품 결정 연동**: 블록 D는 극장을 명시적으로 존치(docs/14 §C #6 '코드 보존·극장 유지')하므로 게이트 오프 대상이 아니다. 수정 필요성 그대로.

---

### E-1.2. SSE(fetch)와 axios가 별개 single-flight — 동시 401 시 RTR 탈취 오탐으로 전 기기 강제 로그아웃

**🔴 잔존** · P1 · SMALL · FE  
`FE/src/api/UseChatStream.js:312-339 (+ src/api/axios.js:26-34)`

**근거**

두 개의 독립 뮤텍스가 그대로 공존한다.
UseChatStream.js:312-319 —
```
312: // [Phase6/Tier3 / H-24] SSE 흐름의 refresh도 single-flight.
314: //   axios.js의 refreshOnce와는 별도 트랙(fetch 기반)이지만 동일 패턴.
315: let _sseRefreshPromise = null;
317: async function tryRefreshToken() {
318:   if (!_sseRefreshPromise) { ... fetch(`${BASE_URL}/auth/refresh`) ... }
```
axios.js:26-34 —
```
26: let refreshPromise = null;
28: function refreshOnce() {
30:   refreshPromise = api.post('/auth/refresh').finally(() => { refreshPromise = null; });
```
주석 자체가 '별도 트랙'임을 인정한다. 백엔드는 RT 회전 + 재사용 감지가 전면 무효화다 — JwtTokenService.java:104-110 `String storedToken = redisTemplate.opsForValue().get(REFRESH_PREFIX + username); if (storedToken == null || !storedToken.equals(refreshToken)) { redisTemplate.delete(REFRESH_PREFIX + username); log.warn("[JWT] RT mismatch — possible theft. All sessions revoked"); throw ... }`. 따라서 AT 만료 순간 axios 호출과 SSE 호출이 동시에 401을 받으면 /auth/refresh가 2회 발사 → 두 번째가 회전된 구 RT를 제시 → Redis 키 삭제 → 그 유저의 **모든 기기 세션 무효화**. UseChatStream.js:203-209에서 refresh 실패는 `window.location.href = '/login'`으로 직행한다.

**수정안**

리프레시 뮤텍스를 단일 모듈로 승격한다. 신규 `src/api/refreshLock.js`에 `let p=null; export function refreshOnce(){ if(!p) p = fetch(`${BASE_URL}/auth/refresh`,{method:'POST',credentials:'include'}).then(r=>r.ok?r.json():Promise.reject(r)).then(d=>{ if(d.accessToken) localStorage.setItem('accessToken',d.accessToken); return true; }).finally(()=>{p=null;}); return p; }` 를 두고, axios.js:28 `refreshOnce()`와 UseChatStream.js:317 `tryRefreshToken()`, UseStoryV2Stream.js:211 `tryRefreshToken()`이 **모두 이 하나를 호출**하게 바꾼다(E-1.2b와 동일 픽스로 묶어 처리). axios 인터셉터는 반환값이 boolean이 되므로 res.data.accessToken 대신 localStorage에서 재독출하도록 55행을 조정.

---

### E-1.2b. UseStoryV2Stream에 제3의 리프레시 트랙 — 존재하지 않는 localStorage refreshToken을 읽어 V2 SSE 401은 100% 즉시 강제 로그아웃

**🔴 잔존** · P1 · SMALL · FE  
`FE/src/api/UseStoryV2Stream.js:207-233 (호출부 :93-100)`

**근거**

UseStoryV2Stream.js:209-214 —
```
209: * 401 응답 시 토큰 갱신 시도.
210: * axios.js의 refreshOnce와 별도 트랙 (fetch 기반) — 동일 패턴.
212: async function tryRefreshToken() {
213:   const refreshToken = localStorage.getItem("refreshToken");
214:   if (!refreshToken) return false;
```
그런데 `localStorage.setItem("refreshToken", ...)`은 이 파일 내부(:227)를 제외하면 **저장소 전체 어디에도 없다** — `grep -rn refreshToken src/ | grep -v UseStoryV2Stream` 결과 0건. RT는 httpOnly 쿠키로만 오간다(axios.js:9 `withCredentials: true`). 따라서 214에서 항상 false로 조기 반환되고, 호출부가 즉시 로그아웃시킨다:
```
93:      if (response.status === 401) {
94:        const refreshed = await tryRefreshToken();
95:        if (refreshed) { return _ssePostV2(...); }
97:        window.location.href = "/login";
```
sendV2Message / sendV2Action / sendV2Opening 전부 이 경로를 탄다(:32,:49,:66). 즉 **V2 스토리(현재 주력 트랙)에서 AT가 만료된 채 메시지를 보내면 갱신 시도조차 못 하고 로그인 페이지로 튕긴다** — 작성 중이던 입력·씬 큐 전부 소실. 게다가 single-flight도 없어 E-1.2의 RTR 오탐에도 노출된다.

**수정안**

UseStoryV2Stream.js:212-233 `tryRefreshToken`을 통째로 삭제하고 E-1.2에서 신설하는 공용 `refreshOnce()`를 import해 :94에서 호출한다. localStorage refreshToken 판정(:213-214)과 body에 refreshToken을 실어 보내는 :220 `body: JSON.stringify({ refreshToken })`, 응답 저장 :227 `localStorage.setItem("refreshToken", ...)`는 전부 제거 — 서버 계약은 쿠키 기반이라 body가 불필요하다. 검증: 로컬에서 accessToken을 임의 훼손한 뒤 V2 방에서 전송 → /login 리다이렉트 없이 정상 재시도되는지 확인.

**제품 결정 연동**: none. V2 스토리 트랙은 §G #2에서 V1 STORY를 대체할 존속 주력으로 확정됐으므로 오히려 우선순위가 높다.

---

### E-1.3. 디렉터 fetch 3종(peek·consume·request)에 401 갱신 부재 — 토큰 만료 구간에서 디렉터 무음 실패

**🔴 잔존** · P3 · SMALL · FE  
`FE/src/api/UseChatStream.js:57-79, :91-113, :125-149`

**근거**

세 함수 모두 `_ssePost`(:200-210에 401→tryRefreshToken 분기 보유)를 쓰지 않고 raw fetch를 직접 호출하며 401 처리 자체가 없다.
```
62:    const res = await fetch(`${BASE_URL}/story/rooms/${roomId}/director/peek`, { ... 'Authorization': `Bearer ${token}` ... });
71:    if (res.status === 204) return null;
72:    if (!res.ok) return null;          // ← 401도 여기서 조용히 null
...
96:    const res = await fetch(`${BASE_URL}/story/rooms/${roomId}/director/consume`, ...);
105:    if (res.status === 404) return null;
106:    if (!res.ok) return null;          // ← 401도 조용히 null
...
130:    const res = await fetch(`${BASE_URL}/story/rooms/${roomId}/director/request`, ...);
139:    if (!res.ok) { const err = await res.json().catch(() => ({})); throw new Error(err.message || `HTTP ${res.status}`); }
```
도달 가능성 확인: ChatPage.jsx:350/1392/1394/1813/1823, ChatPageV2.jsx:487/2221/2223/2639/2649에서 실제 호출된다. 증상 — peek/consume은 401을 'directive 없음'으로 오인해 디렉터 인터루드가 조용히 사라지고(consume 실패 시 서버 측 directive는 미소비로 남아 다음 턴에 뒤늦게 재생될 수 있음), requestDirectorIntervention은 'HTTP 401' 원문 토스트를 유저에게 노출한다. 강제 로그아웃까지 가지는 않으므로 docs/13이 암시한 것보다 파급은 작다.

**수정안**

세 함수를 공용 헬퍼로 감싼다. `async function _authedJson(url, init)` 를 UseChatStream.js에 추가해 401이면 E-1.2의 공용 refreshOnce() 후 1회 재시도하고, 재시도도 401이면 호출자에게 명시적 에러를 던지도록 한다. peek(:62)·consume(:96)·request(:130)의 fetch를 이 헬퍼 호출로 교체. 다만 아래 productDecisionRisk를 먼저 확인 — peek/consume 경로는 곧 삭제 대상일 수 있으므로 request 1종만 고치는 축소안이 합리적일 수 있다.

**제품 결정 연동**: docs/14 §G #7 '⚠게이트오프: V1 디렉터 잔여 — INTERLUDE/TRANSITION/AWAY 소비 경로(생산자 소멸)·activeDirector* 필드 정리, 시간 넘기기만 페이싱 도구로 존치'. 블록 D 확장이 집행되면 peek/consume/request 3종 모두 사문화되어 이 결함은 MOOT가 된다. **블록 D 집행 전에 이걸 먼저 고치는 것은 낭비** — 블록 D 순서에 종속시킬 것.

**❓ 결정 필요**: §G #7의 V1 디렉터 정리를 이번 버그픽스 세션에 포함할 것인가, 별도 블록 D로 미룰 것인가? 포함한다면 E-1.3과 E-1.4/4b의 지켜보기·자동응답 부분은 수정 대신 삭제로 처리해야 한다.

---

### E-1.4. ChatPageV2 지켜보기·시간넘기기·자동응답 SSE에 abortController 미전달 — 새 전송과 동시 진행하며 상태 역행

**🔴 잔존** · P2 · SMALL · FE  
`FE/src/pages/ChatPageV2.jsx:635, :2730, :2830`

**근거**

sseAbortRef는 존재하고(:274 `const sseAbortRef = useRef(null);`) 4개 경로에서만 배선돼 있다 — :1706/1834(handleSendMessageV2), :1858/1950(handleSendActionV2), :1978/2059(fireOpeningV2), :2249/2526. 나머지 3개 SSE는 컨트롤러 인자 자리를 비운 채 호출된다.
```
635:      await sendAutoDirectorResponse(roomId, directiveType, eventContext, {   // 5번째 인자 abortController 없음
...
747:      });
2730:      await sendDirectorWatchStream(roomId, {                                 // 3번째 인자 없음
...
2810:      });
2830:      await sendTimeSkipStream(roomId, {                                      // 3번째 인자 없음
...
2919:      });
```
API 시그니처는 컨트롤러를 받게 돼 있다 — UseChatStream.js:32 `sendDirectorWatchStream(roomId, callbacks, abortController)`, :41 `sendTimeSkipStream(roomId, callbacks, abortController)`, :175 `sendAutoDirectorResponse(roomId, directiveType, eventContext, callbacks, abortController)`, 그리고 :197 `signal: abortController?.signal`. 결과: 유저가 지켜보기를 누른 뒤 곧바로 메시지를 전송하면 handleSendMessageV2가 :1705에서 `sseAbortRef.current?.abort()`를 호출해도 지켜보기 스트림은 살아남아, 뒤늦게 도착한 onFinalResult가 setAffection/setCharacterStats/setMessages/setCurrentScene을 덮어써 상태가 역행한다. 언마운트 정리(:277 `sseAbortRef.current?.abort()`)도 이 3종에는 무효라 방을 나간 뒤에도 스트림이 계속 돌며 setState 경고를 낸다.

**수정안**

세 핸들러(triggerAutoDirectorResponse @623, handleDirectorWatch @2716, handleTimeSkip @2818) 각각의 SSE 호출 직전에 handleSendMessageV2와 동일한 2줄을 삽입한다 — `try { sseAbortRef.current?.abort(); } catch {} ; sseAbortRef.current = new AbortController();` — 그리고 호출 끝 `});`를 `}, sseAbortRef.current);`로 바꾼다(:747, :2810, :2919). 콜백 내부에서 AbortError는 UseChatStream.js:282에서 이미 무음 처리되므로 추가 방어는 불필요.

**제품 결정 연동**: §G #7로 지켜보기(watch)·자동응답(auto-respond)은 제거 후보다. 그러나 **시간 넘기기(handleTimeSkip)는 '페이싱 도구로 존치' 확정**이므로 최소한 :2830은 반드시 수리해야 한다. 나머지 2건은 블록 D 집행 여부에 종속.

---

### E-1.4b. ChatPage V1에도 동일한 abortController 미전달 3곳 — docs/13이 V2만 지목했으나 V1 사본이 그대로 존재

**🔴 잔존** · P2 · SMALL · FE  
`FE/src/pages/ChatPage.jsx:498, :1904, :2004`

**근거**

ChatPage.jsx도 sseAbortRef를 갖고 있으나(:268 `const sseAbortRef = useRef(null);`) 배선은 sendMessageStream 1곳뿐이다 — :1419-1420 `try { sseAbortRef.current?.abort(); } catch {} / sseAbortRef.current = new AbortController();` … :1700 `}, sseAbortRef.current);`. 나머지 3종은 컨트롤러 없이 닫힌다:
```
498:      await sendAutoDirectorResponse(roomId, directiveType, eventContext, {
...
610:      });                     ← 컨트롤러 미전달
1904:      await sendDirectorWatchStream(roomId, {
...
1984:      });                     ← 컨트롤러 미전달
2004:      await sendTimeSkipStream(roomId, {
...
2093:      });                     ← 컨트롤러 미전달
```
ChatPage는 App.jsx:142-145 `path="/chat/:roomId"`로 여전히 라우팅되어 살아 있다.

**수정안**

E-1.4와 동일 패턴을 ChatPage.jsx의 세 지점에 적용 — 호출 직전 abort+new AbortController, 종결부 :610/:1984/:2093을 `}, sseAbortRef.current);`로 변경. V2와 V1을 한 커밋에서 같이 처리해 패턴 드리프트를 막을 것.

**제품 결정 연동**: §G #2 'ChatPage=SANDBOX 전용 선언'으로 V1 STORY 분기는 제거되지만 ChatPage 자체는 SANDBOX용으로 존속한다. §G #7에 따라 watch/auto-respond는 삭제 후보, time-skip은 존치 — E-1.4와 동일한 종속 관계.

---

### E-1.5. V2 메시지 전송의 멀티씬 턴 엔트리에 parentLogId 누락 — 씬 분리 메시지 일괄 삭제 불가

**🔴 잔존** · P2 · ONE_LINE · FE  
`FE/src/pages/ChatPageV2.jsx:1741-1762 (handleSendMessageV2 onFinalResult 인라인 빌더)`

**근거**

handleSendMessageV2가 공용 buildHistoryEntries를 쓰지 않고 자체 빌더를 갖고 있는데 거기에 parentLogId가 없다.
```
1749:            return {
1750:              role: isSystem ? 'SYSTEM' : (isNpc ? 'NPC' : 'ASSISTANT'),
1751:              cleanContent: content.join('\n'),
1752:              speaker: isSystem ? null : (s.speaker || null),
1754:              logId: isLast ? (resLogId || null) : null,
1755:              hasInnerThought: isLast ? !!resHasThought : false,
1756:              thoughtUnlocked: false,
1758:              emotionTag: s.emotion || null,
1759:              outfit: s.outfit ?? null,
1760:            };
```
대조군 — 같은 파일의 공용 빌더 :798-810에는 있다:
```
802:        logId: (i === scenes.length - 1) ? (resLogId || null) : null,
803:        parentLogId: resLogId || null,  // [Bug Fix #1] 모든 씬에 원본 logId 공유 — 일괄 삭제용
```
그리고 복원 경로 :854에도 `parentLogId: log.logId`가 있다. 삭제 로직이 이 필드에 의존한다 — ChatPageV2.jsx:2973-2982 `// [Bug #1 Fix] 씬 분리된 메시지의 전체 씬을 일괄 삭제 (parentLogId 기반) … msg.logId !== logId && msg.parentLogId !== logId`. 결과: 새로고침 전(라이브 세션) 유저가 방금 받은 4~5씬 턴을 삭제하면 **마지막 씬만 사라지고 앞 씬들이 유령으로 남는다**(새로고침하면 복원 경로가 parentLogId를 채우므로 증상이 사라져 재현이 헷갈림).

**수정안**

ChatPageV2.jsx:1754 아래에 `parentLogId: resLogId || null,` 한 줄 추가. 근본 해법은 이 인라인 빌더(1741-1762)를 삭제하고 공용 `buildHistoryEntries(scenes, resLogId, resHasThought)`(:787) 호출로 대체하는 것 — 공용 빌더는 이미 isSystemSpeakerName 기반 3축 분류·parentLogId·emotionTag·outfit을 모두 갖췄고 ctx 인자도 받으므로, 이 치환 하나로 E-1.5/6a/6b/6c/6d가 동시에 해소된다. 다만 공용 빌더는 SYSTEM 씬 cleanContent를 narration만으로 자르므로(:800) 현 인라인 빌더(:1751 content.join)와 미세 차이가 있어 회귀 확인 필요.

---

### E-1.6a. V2 액션 전송(handleSendActionV2) 엔트리가 NPC를 히로인으로 오분류 — SYSTEM/ASSISTANT 2축만 존재

**🔴 잔존** · P2 · ONE_LINE · FE  
`FE/src/pages/ChatPageV2.jsx:1886-1900`

**근거**

```
1886:          const entries = scenes.map((s, i) => {
1887:            const isSystem = isSystemSpeaker(s.speaker, heroinesSnapshot);
...
1891:            const isLast = i === scenes.length - 1;
1892:            return {
1893:              role: isSystem ? 'SYSTEM' : 'ASSISTANT',      ← NPC 축 없음
1894:              cleanContent: content.join('\n'),
1895:              speaker: isSystem ? null : (s.speaker || null),
```
같은 파일의 정본 3축 규칙(메시지 경로 :1744-1745)은 이렇다:
```
1744:            const isNpc = !isSystem && !heroNamesSnapshot.includes(s.speaker);
1750:              role: isSystem ? 'SYSTEM' : (isNpc ? 'NPC' : 'ASSISTANT'),
```
복원 경로(:870-872)와 공용 빌더(:793-799)도 3축이다. 즉 **액션 경로만 2축**이라, NEXT_SCENE/TIME_ADVANCE/MOVE 응답에 등장한 NPC 대사가 히로인 대사(ASSISTANT)로 히스토리에 남는다. 히스토리 렌더에서 NPC는 실루엣·별도 이름 표시를 받는데(:4332 `if (isV2 && !isMe && v2Room?.heroines)`) 이 판정이 role에 의존하므로 표시가 어긋나고, 새로고침 후 복원 경로가 3축으로 다시 계산하면 **같은 로그가 새로고침 전후로 다르게 보이는** 비결정성이 생긴다.

**수정안**

handleSendActionV2 onFinalResult(:1885 부근)에 `const heroNamesSnapshot = heroinesSnapshot.map(h => h.name);`를 추가하고 :1887 뒤에 `const isNpc = !isSystem && !heroNamesSnapshot.includes(s.speaker);`, :1893을 `role: isSystem ? 'SYSTEM' : (isNpc ? 'NPC' : 'ASSISTANT'),`로 교체. 권장은 E-1.5의 근본안 — 이 블록 전체를 공용 buildHistoryEntries 호출로 치환.

---

### E-1.6b. V2 오프닝(fireOpeningV2) 엔트리도 NPC 미분류 — 첫 진입 도입 장면의 NPC가 히로인으로 기록

**🔴 잔존** · P2 · ONE_LINE · FE  
`FE/src/pages/ChatPageV2.jsx:2009-2023`

**근거**

```
2009:          const entries = scenes.map((s, i) => {
2010:            const isSystem = isSystemSpeaker(s.speaker, heroinesSnapshot);
...
2015:            return {
2016:              role: isSystem ? 'SYSTEM' : 'ASSISTANT',      ← NPC 축 없음
2017:              cleanContent: content.join('\n'),
2018:              speaker: isSystem ? null : (s.speaker || null),
2019:              // [Bug-Thought] 마지막 씬에 logId+속마음 플래그 …
2020:              logId: isLast ? (resLogId || null) : null,
2021:              hasInnerThought: isLast ? !!resHasThought : false,
2022:              thoughtUnlocked: false,
2023:            };
```
액션 경로(:1892-1899)와 완전 동일한 사본이다. 오프닝은 빈 방 첫 진입에서 1회 자동 발사되므로(:2071 부근 effect, :1976 `if (openingFiredRef.current) return;`) **모든 V2 방의 첫 턴이 이 결함 경로를 탄다** — 도입 장면에 NPC(집사·행인 등)가 등장하면 히로인으로 기록된다.

**수정안**

E-1.6a와 동일 패치를 :2009-2023 블록에 적용. 세 곳(메시지/액션/오프닝)이 같은 코드의 3중 복제이므로 공용 buildHistoryEntries 치환으로 일괄 제거하는 편이 재발 방지에 낫다.

---

### E-1.6c. V2 액션·오프닝 엔트리에 emotionTag/outfit 미보존 — 해당 턴은 리플레이 시 감정·복장이 현재값으로 오염

**🔴 잔존** · P2 · ONE_LINE · FE  
`FE/src/pages/ChatPageV2.jsx:1892-1899, :2015-2023`

**근거**

액션(:1892-1899)·오프닝(:2015-2023) 반환 객체에는 emotionTag/outfit 키 자체가 없다(위 발췌 참조). 반면 메시지 경로 :1757-1759는 보존한다:
```
1757:              // [리플레이 E6] 라이브 구간도 씬 감정·복장 보존 — 새로고침 전 리플레이 재현용
1758:              emotionTag: s.emotion || null,
1759:              outfit: s.outfit ?? null,
```
주석이 목적을 명시한다 — '새로고침 전 리플레이 재현용'. 소비처는 sceneReplay.js:56-58 `emotion: msg.emotionTag || "NEUTRAL", outfit: msg.outfit ?? null`이고, 최종적으로 ChatPageV2.jsx:392/396 `emotion: heroine ? sc.emotion : displayedEmotion` / `outfit: sc.outfit || (heroine ? heroine.defaultOutfit : v2SceneSpeakerOutfit)`로 무대에 반영된다. 결과: 새로고침 전 세션에서 액션·오프닝 턴을 히스토리에서 클릭해 리플레이하면 **그 씬의 감정이 NEUTRAL로, 복장이 '지금' 복장으로 재현**된다(새로고침 후에는 복원 경로 :858-860이 채워주므로 증상이 사라져 재현 조건이 까다롭다).

**수정안**

두 블록의 반환 객체에 메시지 경로와 동일한 2줄 추가 — `emotionTag: s.emotion || null,` / `outfit: s.outfit ?? null,`. (E-1.5 근본안인 공용 빌더 치환 시 자동 해소.) 단, V2 백엔드가 SceneResponse의 outfit을 null로 하드코딩한다는 §6 기록이 있어 실제 값이 오는지는 별개 — 그래도 '누락'과 'null 수신'은 구분해 두어야 V2 스레딩 백로그 착수 시 프론트를 다시 손대지 않는다.

**제품 결정 연동**: impl_spec_details §6 재작업 금지 목록 — 'V2 scenesJson 씬 컨텍스트 4필드 사문(SceneResponse null 하드코딩): 알려진 상태 — V2 스레딩은 별도 백로그(docs/13 #12)'. 즉 **V2에서 실제 값이 채워지는 것은 백로그 소관**이고, 여기서 고칠 것은 '프론트가 값을 버리는 배선'뿐이다. 값이 null이어도 배선을 맞춰두면 백로그 처리 시 프론트 재작업이 없다.

---

### E-1.6d. V2 액션·오프닝 엔트리에 parentLogId 누락 — E-1.5와 동일 증상이 두 경로에 추가로 존재

**🔴 잔존** · P2 · ONE_LINE · FE  
`FE/src/pages/ChatPageV2.jsx:1892-1899, :2015-2023`

**근거**

두 블록의 반환 객체(위 발췌)에 parentLogId가 없다. 삭제 로직 ChatPageV2.jsx:2973-2982는 `msg.parentLogId !== logId`로 형제 씬을 찾으므로, 액션(NEXT_SCENE/TIME_ADVANCE/MOVE)·오프닝으로 생성된 멀티씬 턴은 라이브 세션에서 마지막 씬만 삭제되고 앞 씬이 잔류한다. 오프닝은 모든 V2 방의 1턴이므로 '첫 도입 장면을 지웠는데 반만 지워지는' 형태로 나타난다.

**수정안**

두 블록에 `parentLogId: resLogId || null,` 추가. E-1.5·6a·6b·6c와 함께 공용 buildHistoryEntries 치환 1건으로 통합 처리 권장.

---

### E-1.7. ChatPage V1 init 복원의 스테일 클로저 — 초기 50개 로그의 화자명이 "캐릭터"가 되고 히로인 대사가 NPC로 오분류

**🔴 잔존** · P2 · SMALL · FE  
`FE/src/pages/ChatPage.jsx:1039 (호출) · :698-728 (콜백) · :1065 (effect deps)`

**근거**

expandLogWithScenes는 roomInfo state에 의존한다:
```
698:  const expandLogWithScenes = useCallback((log) => {
704:          const isNpc = scene.speaker && scene.speaker !== roomInfo?.characterName;
709:            role: isNpc ? 'NPC' : 'ASSISTANT',
711:            speaker: scene.speaker || roomInfo?.characterName || "캐릭터",
728:  }, [roomInfo]);
```
init effect는 roomId만 의존하고, 같은 async 함수 안에서 setRoomInfo를 호출한 직후 이 콜백을 쓴다:
```
961:  useEffect(() => {
962:    const init = async () => {
980:        setRoomInfo(roomRes.data);          ← 상태 갱신 예약(이번 렌더에는 미반영)
1037:            for (const log of sortedLogs) {
1039:              const expanded = expandLogWithScenes(log);   ← roomInfo가 아직 null인 클로저
1065:  }, [roomId]);
```
따라서 첫 진입 시 복원되는 최대 50개 로그 전부에서 roomInfo가 null이다 → :711 화자 폴백이 "캐릭터"가 되고, 더 나쁘게 :704의 `scene.speaker !== undefined`가 **항상 참**이라 히로인 본인 대사까지 role='NPC'로 기록된다. 히스토리 렌더 :3449 `const displayName = isMe ? '나' : (msg.speaker || roomInfo?.characterName || "캐릭터");`.
올바른 패턴이 같은 파일에 이미 있다 — :1106 `const charName = roomData?.characterName || "캐릭터";`(startIntroSequence는 roomData를 인자로 받는다). V2 페이지는 이 결함을 명시적으로 고쳤다 — ChatPageV2.jsx:839-845 `// [Bug-Restore] ctx로 분류 컨텍스트를 명시 전달 가능 — init처럼 state가 아직 stale한 시점에서 방금 fetch한 로컬 값을 직접 넘겨 결정적 분류를 보장한다.` + :1196/:1344/:3155 호출부가 ctx를 넘긴다. **V1만 미이식.**

**수정안**

ChatPageV2의 ctx 패턴을 그대로 이식한다. ChatPage.jsx:698 시그니처를 `(log, ctx)`로 바꾸고 본문 상단에 `const ctxCharacterName = ctx ? (ctx.characterName ?? null) : (roomInfo?.characterName ?? null);`를 두어 :704·:711에서 roomInfo 대신 이 값을 쓴다. init의 :1039 호출을 `expandLogWithScenes(log, { characterName: roomRes.data.characterName })`로, 더 불러오기 경로 :2348도 동일하게 ctx를 넘긴다(그쪽은 roomInfo가 이미 채워져 있어 미전달도 무방하나 일관성을 위해 권장).

**제품 결정 연동**: §G #2 'V1 STORY 모드 트랙 삭제 — ChatPage=SANDBOX 전용 선언'. ChatPage 자체는 SANDBOX 채팅으로 존속하므로 결함은 남는다. 다만 §G #2 집행 시 이 파일을 대대적으로 손보게 되므로 **두 작업을 같은 세션에 묶는 것이 효율적**이다.

---

### E-1.8a. 씬 일러 K-윈도우 복원 판정의 좌표계 불일치 — turnIndex(hidden 포함) vs 로그 총수(hidden 제외)

**🔴 잔존** · P2 · MEDIUM · BE/FE  
`FE/src/hooks/useSceneIllustrations.js:147-156 · 백엔드 SceneRequestService.java:75,123 vs ChatController.java:93-99`

**근거**

프론트 판정:
```
147:  const resolveRestoreVisibility = useCallback(() => {
152:    const { lastTurnIndex } = pending;
153:    const isRecent = lastTurnIndex == null || lastTurnIndex >= logTotal - RESTORE_RECENT_LOG_WINDOW;
154:    setVisible(isRecent);
155:    setDismissReason(isRecent ? null : "STALE");
```
두 피연산자의 출처가 다르다.
· lastTurnIndex ← SceneRequestService.java:75 `int turnIndex = (int) chatLogRepository.countByRoomId(roomId);` (:123도 동일). ChatLogMongoRepository.java:69 `long countByRoomId(Long roomId);` — **hidden 필터 없음**.
· logTotal ← ChatPage.jsx:1023 `sceneStage.notifyLogTotal(logsRes.data?.totalElements ?? logs.length)` → 이 totalElements는 ChatController.java:93 `chatLogRepository.findByRoomIdAndHiddenFalse(roomId, pageable)`의 총수 = **hidden 제외**(ChatLogMongoRepository.java:101-103 `findByRoomIdAndHiddenNot(roomId, true, pageable)`).
hidden 로그는 실제로 생성된다 — ChatLogDocument.java:222-231 `hiddenSystem(...)  .hidden(true)`(SYSTEM_DIRECTOR·TIME_SKIP), :239-248 `hiddenUser(...) .hidden(true)`(EVENT_START). 디렉터/시간넘기기/이벤트를 쓴 방일수록 turnIndex가 logTotal보다 크게 부풀어 `lastTurnIndex >= logTotal - K`가 **항상 참**이 된다 → 오래된 씬 일러가 재입장마다 풀블리드로 부활한다(주석 :164-166이 막으려던 바로 그 버그). 반대 방향 오차는 구조상 발생하지 않으나, 좌표계가 다르다는 사실 자체가 K-윈도우 판정을 무의미하게 만든다.

**수정안**

좌표계를 하나로 통일한다. **권장(BE)**: SceneRequestService.java:75/:123의 `chatLogRepository.countByRoomId(roomId)`를 hidden 제외 카운트로 교체 — ChatLogMongoRepository에 `long countByRoomIdAndHiddenNot(Long roomId, boolean hidden)` 파생 쿼리를 추가하고 `countByRoomIdAndHiddenNot(roomId, true)`를 쓴다. 이러면 turnIndex와 ordinal이 같은 축(hidden 제외 1-based 서수)에 놓인다. 단 **기존 씬 데이터의 turnIndex는 구 좌표계로 저장돼 있어** 마이그레이션 없이는 과거 씬이 어긋난다 — 대안(FE 전용): 백엔드 `/illustrations/scenes` 응답에 hidden 포함 총수를 함께 내려 프론트가 같은 축끼리 비교하게 하거나, 판정 자체를 서수 대신 '마지막 완료 씬 id가 최근 N개 안에 드는가'로 바꿔 좌표계 의존을 제거. 어느 쪽이든 E-1.8b와 같은 결정을 공유해야 한다.

**제품 결정 연동**: none. 다만 §G #7로 디렉터 잔여가 정리되면 hidden 로그 생산량이 줄어 증상이 완화될 뿐 근본 불일치는 남는다(TIME_SKIP은 존치 확정이므로 hidden 로그는 계속 생성된다).

**❓ 결정 필요**: 기존 SceneIllustration 행의 turnIndex를 새 좌표계로 재계산하는 마이그레이션을 감수할 것인가, 아니면 판정 로직을 좌표계 비의존 방식으로 바꿀 것인가? (전자는 DB 마이그레이션 1건, 후자는 FE만 수정하나 '최근성' 의미가 약해진다.)

---

### E-1.8b. 히스토리→씬 점프(goToTurn)의 좌표계 불일치 — 클릭한 대화 시점과 다른 씬 일러로 점프

**🔴 잔존** · P3 · MEDIUM · BE/FE  
`FE/src/hooks/useSceneIllustrations.js:449-469`

**근거**

```
449:   * 로그 서수(ordinal)와 가장 가까운 씬으로 점프:
450:   * turnIndex ≤ ordinal 인 최대 씬 → 없으면 turnIndex ≥ ordinal 인 최소 씬 → 없으면 최신.
453:  const goToTurn = useCallback((ordinal) => {
459:      const t = list[i].turnIndex;
460:      if (t != null && t <= target) found = i;
463:      found = list.findIndex((s) => s.turnIndex != null && s.turnIndex >= target);
```
주석 :450이 turnIndex와 ordinal을 같은 축으로 비교한다고 스스로 선언한다. 그러나 ordinal은 hidden 제외 서수다 — ChatController.java:95-99 `// ordinal = total - (page*size + i). 씬 일러 turnIndex 매핑 키(히스토리 클릭→씬 점프).` 이고 total은 hidden 제외 총수, ChatLogResponse.java:30 `- 방 내 절대 서수(1-based, 오래된 순 — hidden 제외). 씬 일러 turnIndex와의 매핑 키로`. 반면 turnIndex는 hidden 포함(E-1.8a 근거). 두 축의 차이는 그 방의 누적 hidden 로그 수만큼 단조 증가하므로, 디렉터·시간넘기기를 많이 쓴 방일수록 히스토리에서 옛 대사를 클릭했을 때 **더 이전 씬으로** 점프한다(turnIndex가 부풀어 `t <= ordinal` 조건을 만족하는 씬이 줄어듦). 문서화된 계약(ChatLogResponse.java:30 '매핑 키')이 실제로 깨져 있는 사례다.

**수정안**

E-1.8a와 동일한 좌표계 통일 결정을 그대로 적용한다(별도 수정 지점 아님 — 같은 결정의 두 번째 소비처). BE에서 turnIndex를 hidden 제외로 바꾸면 goToTurn은 코드 변경 없이 정상화된다. FE 전용 우회를 택할 경우 goToTurn에도 동일 보정을 넣어야 하므로, 반드시 8a와 한 결정·한 커밋으로 처리할 것.

---

### E-1.9. 씬 리플레이가 outfit만 소비 — 과거 복장 + 현재 배경/시간/BGM이 뒤섞인 장면이 재현됨

**🔴 잔존** · P2 · MEDIUM · FE  
`FE/src/utils/sceneReplay.js:52-61 · ChatPage.jsx:719 · ChatPageV2.jsx:860 · ChatPage.jsx:248-257 · ChatPageV2.jsx:383-409`

**근거**

3단 배선 전부에서 location/time/bgmMode가 탈락한다.
① 로그→메시지: ChatPage.jsx:717-721 및 ChatPageV2.jsx:858-862 — `emotionTag`, `outfit`, `ordinal`만 옮기고 location/time/bgmMode는 버린다(`// [리플레이] 씬 컨텍스트 복장(2026-08-07 백엔드 영속) — 레거시 로그는 null` 주석 아래 outfit 한 줄뿐).
② 메시지→씬: sceneReplay.js:52-61
```
56:    emotion: msg.emotionTag || "NEUTRAL",
57:    // scenesJson 씬 컨텍스트(2026-08-07 백엔드 영속) — 레거시 로그는 null → 소비처가 현재값 폴백
58:    outfit: msg.outfit ?? null,
```
③ 씬→무대: ChatPage.jsx:252-256 `{ scene, emotion, outfit: sc.outfit || currentOutfit, npcSpeaker }` / ChatPageV2.jsx:391-397 동일 — outfit만 오버라이드하고 배경·시간·BGM은 라이브 state를 그대로 쓴다.
데이터는 서버에 실재한다 — ChatStreamService.java:1488-1493 `// [2026-08-07 리플레이] 씬 컨텍스트 보존(additive) … if (s.location() != null) m.put("location", ...); if (s.time() != null) m.put("time", ...); if (s.outfit() != null) m.put("outfit", ...);` (bgmMode 포함), DTO도 SendChatResponse.java:94-98 `record SceneResponse(String speaker, String narration, String dialogue, EmotionTag emotion, String location, String time, String outfit, String bgmMode)`. 즉 **백엔드가 리플레이용으로 영속한 4필드 중 프론트가 1개만 쓴다** — 커밋 285eea1의 의도가 절반만 구현된 상태.

**수정안**

3단 전부 배선한다. ① ChatPage.jsx:719 / ChatPageV2.jsx:860 옆에 `location: scene.location ?? null, time: scene.time ?? null, bgmMode: scene.bgmMode ?? null,` 추가. ② sceneReplay.js messageToScene(:52-61)과 SYSTEM 분기(:34-43) 반환 객체에 동일 3필드 전달. ③ replayView(ChatPage.jsx:248-257, ChatPageV2.jsx:383-409)에 `location: sc.location || currentLocation, time: sc.time || currentTime, bgmMode: sc.bgmMode || currentBgmMode`를 추가하고, 배경 컴포넌트 props(ChatPage.jsx:2383 `location={showEndingCredits ? null : currentLocation}`, ChatPageV2.jsx:3196)를 `replayView ? replayView.location : ...` 형태로 분기. BGM은 리플레이 중 실제 트랙을 바꿀지(몰입) 유지할지(청각 피로) 정책 판단이 필요하므로 location/time을 먼저 처리하고 bgmMode는 플래그로 분리하는 것을 권한다.

**제품 결정 연동**: impl_spec_details §6 재작업 금지 목록 확인 결과 **이 항목은 금지 대상이 아니다**. §6이 금지한 것은 (a) '리플레이 중 씬 일러 무대 언마운트'(의도된 설계 — 본 수정과 무관), (b) 'V2 scenesJson 씬 컨텍스트 4필드 사문(SceneResponse null 하드코딩)'. 즉 **V2는 백엔드가 값을 안 채우므로 프론트를 고쳐도 효과 없음(백로그)**, 반면 **V1(ChatStreamService.java:1478-1493)은 4필드를 실제로 영속하므로 V1 리플레이는 지금 고칠 수 있고 고쳐야 한다**. 수정 시 'V1에서만 유효, V2는 백로그 대기' 주석을 남길 것.

**❓ 결정 필요**: 리플레이 중 BGM을 그 시점 트랙으로 실제 전환할 것인가(몰입 우선), 현재 트랙 유지인가(전환 소음·청각 피로 우려)? location/time은 전환이 자명하나 BGM만 판단이 필요하다.

---

### E-1.10a. V2 메시지 전송의 낙관적 에너지 차감이 플랫 2 하드코딩 — 부스트 비구독자는 실제 10 차감

**🔴 잔존** · P2 · ONE_LINE · FE  
`FE/src/pages/ChatPageV2.jsx:1697 (가드 :1682)`

**근거**

```
1682:    if (energy <= 0) { showToast("에너지가 부족합니다.", "error"); return; }
...
1697:    setEnergy(prev => Math.max(0, prev - 2));   // V2 STORY 기본 2 에너지
```
서버 실제 차감은 부스트·구독을 반영한다 — ChatStreamServiceV2.java:194-195 `int cost = boostModeResolver.resolveEnergyCost(room.getChatMode(), room.getUser()); room.getUser().consumeEnergy(cost);`, BoostModeResolver.java:48-52 → ChatMode.java:43-56 `int base = getBaseCost(); if (!boostMode) return base; if (isSubscriber) return base; else return base * 5;` (STORY base = ChatMode.java:30 `case STORY -> 2;`). 즉 **부스트 ON·비구독자 = 10**.
같은 파일이 이미 올바른 계산식을 갖고 있다는 점이 결정적이다 — handleDirectorWatch :2726-2727 `const baseCost = roomInfo?.chatMode === "STORY" ? 2 : 1; const cost = boostMode && !isSubscriber ? baseCost * 5 : baseCost;`. 증상: 부스트 유저 화면의 에너지가 매 턴 8씩 과대 표시되고(onFinalResult의 refreshUser로 뒤늦게 정정), :1682 가드가 energy 1~9에서 통과시켜 서버 INSUFFICIENT_ENERGY로 되튕긴다(:1826 결제 모달 강제 오픈).

**수정안**

ChatPageV2.jsx에 부스트 반영 코스트 헬퍼를 하나 만들고(예: `const v2SendCost = useMemo(() => { const base = 2; return boostMode && !isSubscriber ? base * 5 : base; }, [boostMode, isSubscriber]);`) :1697을 `setEnergy(prev => Math.max(0, prev - v2SendCost));`로, 가드 :1682를 `if (energy < v2SendCost)`로 교체. handleDirectorWatch(:2726-2727)의 기존 식과 동일 규칙이므로 그 계산도 이 헬퍼로 수렴시켜 중복 제거.

**제품 결정 연동**: 블록 C(BM) '부스트 모델 gemini-3.6-flash 치환'은 **모델과 원가**를 바꿀 뿐 5배 배율 자체는 docs/14 §C #5에서 '리스트가 전원 현행 유지'로 확정됐다. 따라서 배율 5는 하드코딩해도 무방하나, 블록 C 착수 시 배율이 재론되면 서버 값(BoostModeInfo.energyCost)을 받아 쓰는 편이 안전하다.

---

### E-1.10b. V2 액션 전송(handleSendActionV2)은 낙관적 차감이 아예 없음 — 서버는 동일하게 2~10 차감

**🔴 잔존** · P2 · ONE_LINE · FE  
`FE/src/pages/ChatPageV2.jsx:1840-1863 (handleSendActionV2 진입~SSE 호출)`

**근거**

handleSendActionV2에는 setEnergy 호출도 에너지 가드도 없다:
```
1839:  const handleSendActionV2 = useCallback(async (actionType, payload = null) => {
1840:    if (isTyping || awaitingFinalResult) return;      ← 에너지 검사 없음
...
1851:    setIsTyping(true);
1852:    setAwaitingFinalResult(true);
...
1863:    await sendV2Action(roomId, actionType, payload, { ... }, sseAbortRef.current);
```
그런데 sendV2Action은 메시지와 **같은 엔드포인트**를 친다 — UseStoryV2Stream.js:49-55 `sendV2Action(roomId, actionType, actionPayload, ...) { return _ssePostV2(getStoryV2StreamUrl(roomId), { message: null, actionType, actionPayload }, ...) }` (:32-38 sendV2Message와 동일 URL). 서버는 액션/메시지를 구분하지 않고 차감한다 — ChatStreamServiceV2.java:191-199 TX-1에서 `resolveEnergyCost(room.getChatMode(), room.getUser())` 후 `consumeEnergy(cost)`. 즉 NEXT_SCENE/TIME_ADVANCE/MOVE를 누를 때마다 2(부스트 시 10)가 실제로 빠지는데 **화면 에너지는 그대로**다가 onFinalResult의 refreshUser에서 갑자기 툭 떨어진다. 에너지 0에서도 액션 버튼이 눌려 서버 에러로만 막힌다.

**수정안**

handleSendActionV2 진입부(:1840)에 `if (energy < v2SendCost) { showToast("에너지가 부족합니다.", "error"); return; }`를 추가하고 setIsTyping 직전에 `setEnergy(prev => Math.max(0, prev - v2SendCost));`를 넣는다(v2SendCost는 E-1.10a에서 만든 헬퍼 재사용). onError 경로(:1946-1951)에 `setEnergy(prev => prev + v2SendCost);` 롤백도 함께 — 현재 액션 onError에는 롤백이 없어 차감분 복구가 refreshUser에만 의존한다. useCallback deps에 energy 추가 필요.

**제품 결정 연동**: E-1.10a와 동일(블록 C 배율 재론 여부에만 종속).

---

### E-1.11a. V2 상태창이 존재하지 않는 heroine.stats를 읽음 — 백엔드는 평탄 statX라 스탯 레이더가 전부 0

**🔴 잔존** · P2 · SMALL · FE  
`FE/src/pages/ChatPageV2.jsx:2101 · :1149`

**근거**

★주의 1(블록 B 렌즈 5축 개편으로 바뀌었을 수 있음)에 따라 재확인함 — **바뀌지 않았다.** 렌즈 5축(매력/친근함/신뢰감/카리스마/신비)은 docs/14 §C #4대로 *유저 페르소나 프로필* 스탯이고, 히로인 8축 스탯은 그대로다.
프론트:
```
2101:    setCharacterStats(heroine.stats || null);        (handleHeroineSelectedV2)
1149:          if (firstHeroine.stats) setCharacterStats(firstHeroine.stats);   (init)
```
백엔드 DTO에는 `stats` 필드가 없고 평탄 필드만 있다 — StoryV2Responses.java:102-119 `public record HeroineStateResponse(Long characterId, String name, String slug, String defaultOutfit, String profileImageUrl, String defaultImageUrl, int statIntimacy, int statAffection, int statDependency, int statPlayfulness, int statTrust, Integer statLust, Integer statCorruption, Integer statObsession, RelationStatus statusLevel, ...)`. 따라서 :2101은 항상 `setCharacterStats(null)`이고, :1149의 if는 항상 거짓이라 초기값(:204-207 `{intimacy:0, affection:0, dependency:0, playfulness:0, trust:0, lust:0, corruption:0, obsession:0}`)이 영구 유지된다.
소비처: :3286 `stats={characterStats}` → BiometricStatusPanel.jsx:244-246 `intimacy: stats?.intimacy ?? 0, affection: stats?.affection ?? 0, ...` → :150 RadarChart. 결과 **레이더 차트 전 축 0**.
악화 요인: V2 SSE final_result에도 stats가 없다 — StoryV2SendResponse.java:22-30(scenes/topicConcluded/locationTransition/dialogueOptions/hasInnerThought/assistantLogId뿐)이며 :14-16이 그 설계 의도를 명시한다("권위 상태(affection·bpm·heroines·ending)는 방 상세 재조회로 갱신한다"). 그런데 재조회 후 `setV2Room(freshRoom)`(:1804, :1934)만 하고 characterStats를 다시 파생시키지 않으므로 **세션 내내 0으로 고정**된다. 대조군으로 V1 경로는 정상 — ChatRoomInfoResponse.java:33 `SendChatResponse.StatsSnapshot stats,`가 실재하여 :1288 `if (roomRes.data.stats) setCharacterStats(roomRes.data.stats);`가 동작한다.

**수정안**

ChatPageV2에 평탄→중첩 매퍼를 하나 두고 3곳에서 쓴다: `const toStats = (h) => h ? { intimacy: h.statIntimacy ?? 0, affection: h.statAffection ?? 0, dependency: h.statDependency ?? 0, playfulness: h.statPlayfulness ?? 0, trust: h.statTrust ?? 0, lust: h.statLust ?? 0, corruption: h.statCorruption ?? 0, obsession: h.statObsession ?? 0 } : null;`. ① :1149 → `setCharacterStats(toStats(firstHeroine));` ② :2101 → `setCharacterStats(toStats(heroine));` ③ **재조회 후 갱신 추가** — :1804/:1934의 `setV2Room(freshRoom)` 뒤에 현재 화자 히로인(currentSpeakerHeroine 기준)으로 `setCharacterStats(toStats(...))`를 호출하거나, characterStats를 state 대신 `useMemo(() => toStats(currentSpeakerHeroine), [currentSpeakerHeroine])` 파생값으로 바꿔 근본 해결. 후자를 택하면 :2321/:2866의 `characterStats[key]` 변화량 계산(V1 경로)과 충돌하지 않도록 isV2 분기 필요. (BE에 stats 중첩 필드를 추가하는 대안도 있으나 V2 DTO를 평탄하게 유지한 설계 의도가 있어 FE 매핑이 적절.)

**제품 결정 연동**: docs/14 §G #9 '🔄재해석: 바이오메트릭 스탯 HUD — 수치 게이지→서술형/렌즈형 표현 개편, 시크릿 스탯(음란·타락) 존속은 그때 결정' + #10 '8축 스탯 유지하되 소프트캡화'. **§G 8~11 재해석은 메모리 기록상 종원 보류 상태**다. HUD를 서술형으로 갈아엎기로 확정되면 이 픽스는 폐기될 코드에 대한 투자가 된다. 다만 #10이 '8축 스탯 유지'를 명시했고 현재 HUD가 프로드에 살아 있으므로, **저비용(매퍼 1개) 픽스로 먼저 정상화하고 HUD 개편 시 함께 대체**하는 것이 합리적이다.

**❓ 결정 필요**: §G #9(스탯 HUD 서술형 개편)를 언제 착수할 것인가? 근시일 내 개편이면 E-1.11a/11b는 개편에 흡수시키고 지금은 건너뛰는 판단도 가능하다.

---

### E-1.11b. V2 상태창 관계 단계가 STRANGER로 고정 — 프론트가 heroine.relationStatus를 읽지만 백엔드 필드명은 statusLevel

**🔴 잔존** · P2 · ONE_LINE · FE  
`FE/src/pages/ChatPageV2.jsx:2098 · :1144 · :344`

**근거**

프론트는 세 곳에서 `relationStatus`를 읽는다:
```
1144:            statusLevel: firstHeroine.relationStatus || "STRANGER",     (init)
2098:      statusLevel: heroine.relationStatus || "STRANGER",              (히로인 선택)
 344:      statusLevel: currentSpeakerHeroine.relationStatus || "STRANGER", (v2DerivedRoomInfo)
```
그러나 백엔드 필드명은 statusLevel이다 — StoryV2Responses.java:113 `RelationStatus statusLevel,` (record HeroineStateResponse 내). `relationStatus`라는 키는 이 DTO에 없으므로 세 곳 모두 undefined → **항상 "STRANGER"**.
소비처: ChatPageV2.jsx:3291 `statusLevel={roomInfo?.statusLevel || "STRANGER"}` → BiometricStatusPanel. 즉 V2에서 관계가 아무리 진전돼도 상태창은 영구히 STRANGER를 표시한다.
부수 발견: :335-348 `v2DerivedRoomInfo` useMemo는 **정의만 되고 어디서도 사용되지 않는다**(`grep -n v2DerivedRoomInfo ChatPageV2.jsx` = 335행 1건). 데드 코드이며 §G #4 '데드 코드 일괄' 처분 후보.

**수정안**

:1144와 :2098을 `statusLevel: heroine.statusLevel || "STRANGER",`로 교체(:344는 데드 코드이므로 v2DerivedRoomInfo memo 자체를 삭제하는 편이 낫다 — §G #4와 함께 처리). E-1.11a의 toStats 매퍼를 만들 때 statusLevel도 같은 매퍼에서 함께 뽑아내면 필드명 드리프트를 구조적으로 막을 수 있다. 검증: V2 방에서 관계가 진전된 히로인을 선택해 상태창의 관계 단계 라벨이 실제 값으로 뜨는지 확인.

**제품 결정 연동**: E-1.11a와 동일 — §G #9 스탯 HUD 서술형 개편(종원 보류)에 흡수될 수 있음. 다만 관계 단계 표시는 렌즈 개편과 무관하게 존속할 가능성이 높아 11a보다 픽스 가치가 안정적이다.

---

### E-1.12a. 성인인증 모달 — 팝업을 닫으면 스테일 step 클로저 때문에 'Verification in progress…'가 영구 고착

**🔴 잔존** · P1 · ONE_LINE · FE  
`FE/src/components/AdultVerificationModal.jsx:102-110 (콜백 deps :117)`

**근거**

★주의 2에 따라 블록 B 변경분을 별도 확인함. `git show d187a59 -- src/components/AdultVerificationModal.jsx` = **3 insertions, 2 deletions** — 고친 것은 `/api/v1/verify/token` → `/verify/token`(:31)과 `/api/v1/verify/success` → `/verify/success`(:75) 두 줄뿐이며 커밋 메시지도 'AdultVerificationModal 이중 접두사 404 픽스 (docs/13 C-1)'로 한정한다. **스테일 클로저는 손대지 않았다.**
현재 코드:
```
 24:  const startVerification = useCallback(async () => {
 25:    setStep('loading');
...
102:      const checkClosed = setInterval(() => {
103:        if (popup.closed) {
104:          clearInterval(checkClosed);
105:          window.removeEventListener('message', handleMessage);
106:          if (step === 'loading') {        ← 클로저가 캡처한 값은 'intro'
107:            setStep('intro');
108:          }
109:        }
110:      }, 1000);
117:  }, [onClose, onVerified, step]);
```
호출은 step==='intro'인 렌더에서만 일어난다(:163 intro 화면의 'Verify Now' 버튼). 그 클로저의 `step`은 영원히 'intro'이고, :25의 setStep('loading')은 클로저 변수를 바꾸지 않는다. 따라서 :106 조건은 **항상 거짓** → 유저가 NICE 팝업을 닫아도 모달은 :172-177의 스피너와 'Verification in progress...'에 머문다. 재시도 버튼은 error 단계(:200)에만 있어 loading에서는 탈출구가 없고, 모달을 닫았다 다시 열어도 step state가 컴포넌트에 남아 있으면 그대로다(언마운트되지 않는 배치 — :119 `if (!isOpen) return null;`는 렌더만 막고 state는 보존).
도달 경로 3곳: SecretModeFlow.jsx:162, ChatPage.jsx:3798, ChatPageV2.jsx:4661.

**수정안**

step을 ref로 미러링하거나 함수형 업데이트로 조건을 뒤집는다. 최소 수정: :106-108을 `setStep(prev => prev === 'loading' ? 'intro' : prev);`로 교체하고 useCallback deps(:117)에서 `step`을 제거(더 이상 필요 없음 → 콜백 재생성도 줄어듦). 함께 :119 `if (!isOpen) return null;` 위에 `useEffect(() => { if (!isOpen) { setStep('intro'); setErrorMsg(''); } }, [isOpen]);`를 추가해 모달 재오픈 시 항상 intro에서 시작하도록 하면 잔여 고착 경로까지 막힌다.

**제품 결정 연동**: docs/16 §'C-1 성인인증 수리 = 0순위' — '없으면 유저가 시크릿을 못 켬 + 문서-구현 불일치로 PG 심사 즉사'. URL 404는 이미 고쳐졌으나 **팝업을 닫은 유저(=취소·재시도하려는 다수 케이스)가 인증 화면에 갇히는 것은 같은 0순위 플로우의 잔여 데드엔드**다. 시크릿이 핵심 BM으로 승격된 지금 P1로 상향해야 한다.

---

### E-1.12b. 성인인증 모달 — 서버가 success:false를 반환하면 어떤 단계 전이도 없어 동일하게 loading에 고착

**🔴 잔존** · P1 · ONE_LINE · FE  
`FE/src/components/AdultVerificationModal.jsx:81-90`

**근거**

```
81:            if (result.data.success) {
82:              sfx.chime();
83:              setStep('success');
84:              setTimeout(() => { onVerified && onVerified(); onClose(); }, 1500);
85:            } else {
86:              sfx.thud();          ← 효과음만. setStep도 setErrorMsg도 없음
87:            }
```
else 분기에서 step이 'loading'인 채 유지되고 errorMsg도 비어 있다. 게다가 :72에서 이미 `window.removeEventListener('message', handleMessage)`를 했으므로 재수신도 불가능하고, :102의 checkClosed 인터벌은 E-1.12a 때문에 무력하다. 즉 **서버가 '인증 실패'를 정상 응답(HTTP 200 + success:false)으로 돌려주면 유저는 실패 사실조차 모른 채 스피너를 영원히 본다.** HTTP 에러(throw)일 때만 :91-95의 catch가 error 단계로 보내준다.

**수정안**

:85-87 else 분기를 채운다 — `} else { sfx.thud(); setStep('error'); setErrorMsg(result.data.message || '본인확인에 실패했습니다. 다시 시도해 주세요.'); }`. 백엔드 /verify/success 응답에 실패 사유 필드가 있는지 확인해 메시지를 매핑할 것(미성년·CI 중복 등 사유별 안내가 필요하면 별도 코드 분기). E-1.12a와 한 커밋으로 처리.

**제품 결정 연동**: E-1.12a와 동일 — docs/16 C-1 0순위 플로우의 잔여 데드엔드. 특히 '미성년 판정으로 인한 실패'가 이 경로로 오면 유저에게 아무 설명이 없어 CS 문의로 직결된다.

**❓ 결정 필요**: 성인인증 실패 사유(미성년/CI 중복/기관 오류)를 유저에게 어디까지 구분해 노출할 것인가? 미성년 판정 노출은 개인정보·정서 측면에서 문구 검토가 필요할 수 있다.

---

### E-1.13a. 극장 포털에서 연 상점의 시크릿 탭 — characters=[] 때문에 캐릭터 선택 UI가 없고 구매 시 복구 불가 에러

**🔴 잔존** · P1 · SMALL · FE  
`FE/src/pages/TheaterPortalPage.jsx:597 · LucidStore.jsx:486, :158-161`

**근거**

LucidStore는 캐릭터 선택기를 배열이 비면 통째로 숨긴다:
```
486:                {characters.length > 0 && (
487:                  <div>
488:                    <label ...>대상 캐릭터 선택</label>
```
그런데 구매 가드는 selectedCharId를 필수로 요구한다:
```
157:      if (product.type === "SECRET_PASS_24H" || product.type === "SECRET_UNLOCK_PERMANENT") {
158:        if (!selectedCharId) {
159:          setStatus("error");
160:          setErrorMsg("캐릭터를 선택해주세요.");
161:          return;
162:        }
```
TheaterPortalPage는 빈 배열을 넘긴다 — TheaterPortalPage.jsx:592-597 `<LucidStore isOpen={showStore} onClose={...} initialTab={storeInitialTab} userInfo={userInfo} characters={[]}` (currentCharacterId도 미전달이라 :130 `useState(currentCharacterId)` = undefined). 결과: 극장 포털에서 상점→시크릿 탭→상품 클릭 시 **선택할 UI가 없는데 '캐릭터를 선택해주세요' 에러 화면으로 전이**되고, 그 에러 화면에서 돌아가도 여전히 선택기가 없어 구매를 완결할 방법이 없다.
대조: LobbyShell.jsx:330 `characters={storeCharacters}`(:143-145에서 `/lobby/characters` 지연 로드), ChatPage.jsx:3742 / ChatPageV2.jsx:4594 `characters={characters} currentCharacterId={roomInfo?.characterId}` — 나머지 3개 진입점은 정상.

**수정안**

두 겹으로 막는다. ① TheaterPortalPage에 LobbyShell.jsx:143-145와 동일한 지연 로드를 이식 — 상점 오픈 핸들러에서 `if (storeCharacters.length === 0) { try { setStoreCharacters((await api.get('/lobby/characters')).data); } catch {} }` 후 :597을 `characters={storeCharacters}`로 교체. ② LucidStore 자체를 방어적으로 — :486의 조건부 렌더를 바꿔 `characters.length === 0 && (시크릿 탭)`일 때 선택기 대신 '캐릭터를 불러오지 못했어요 / 대화방에서 구매해 주세요' 안내 + 재시도 또는 로비 이동 CTA를 노출하고, 상품 카드는 disabled 처리해 애초에 :158 에러로 못 가게 한다. ②는 향후 새 진입점이 추가돼도 동일 데드엔드가 재발하지 않게 하는 구조적 방어라 함께 넣기를 권한다.

**제품 결정 연동**: docs/16이 시크릿 모드를 핵심 BM으로 승격시켰고 시크릿 3종 상품(SECRET_PASS_24H·SECRET_UNLOCK_PERMANENT 등)이 주 수익원이 된다. 구매 진입점 하나가 완전 불능인 것은 P1이다. 블록 D와 무관.

**❓ 결정 필요**: 극장(TheaterPortalPage)에서 시크릿 캐릭터 상품을 파는 것이 제품적으로 맞는가? 극장은 아바타 기반 트랙이라 '대상 캐릭터'라는 개념이 어색할 수 있다 — 맞지 않다면 극장 상점에서는 시크릿 탭 자체를 숨기는 것이 더 단순한 해법이다.

---

### E-1.13b. 로비·극장 상점에서 onRequestAdultVerify 미전달 — 미인증 유저의 '인증하기' 버튼과 상품 클릭이 완전 무반응

**🔴 잔존** · P1 · SMALL · FE  
`FE/src/pages/lobby/LobbyShell.jsx:325-336 · TheaterPortalPage.jsx:592-605 · LucidStore.jsx:517-521, :541-549`

**근거**

LucidStore는 미인증 유저의 두 조작을 전부 콜백에 위임한다:
```
517:                {!userInfo?.isAdultVerified ? (
518:                  <button onClick={() => { sfx.click(); onRequestAdultVerify?.(); }}
...
523:                    <span ...>성인 인증을 완료하면 시크릿 상품을 구매할 수 있습니다.</span>
...
543:                        onClick={() => {
544:                          sfx.click();
545:                          if (!isVerified) { onRequestAdultVerify?.(); }
546:                          else { handlePurchase(p); }
```
옵셔널 체이닝이라 미전달 시 조용히 no-op다. 전달 여부를 진입점별로 확인하면:
· LobbyShell.jsx:325-336 — isOpen/onClose/initialTab/userInfo/characters/onPaymentComplete만 전달, **onRequestAdultVerify 없음**
· TheaterPortalPage.jsx:592-605 — 동일하게 **없음**
· ChatPage.jsx:3744 / ChatPageV2.jsx:4597 — `onRequestAdultVerify={() => { setShowStore(false); setShowAdultVerifyFromStore(true); }}` 정상 전달
결과: 로비(블록 A R2의 주 진입점)와 극장에서 미인증 유저가 시크릿 상품이나 빨간 '인증하기 →' 배너를 눌러도 **클릭음만 나고 아무 일도 일어나지 않는다**. 인증으로 가는 유일한 안내가 무반응이므로 신규 유저 기준 시크릿 구매 퍼널이 로비에서 끊긴다.

**수정안**

LobbyShell과 TheaterPortalPage에 AdultVerificationModal을 마운트하고 콜백을 배선한다. 각 페이지에 `const [showAdultVerify, setShowAdultVerify] = useState(false);`를 추가하고 `<LucidStore ... onRequestAdultVerify={() => { setShowStore(false); setShowAdultVerify(true); }} />`, 그리고 `<AdultVerificationModal isOpen={showAdultVerify} onClose={() => setShowAdultVerify(false)} onVerified={() => { /* userInfo 재조회 */ }} />`를 렌더한다(ChatPageV2.jsx:4597 및 :4661 패턴을 그대로 복제). 보강책으로 LucidStore 쪽에 `onRequestAdultVerify`가 없을 때 버튼을 disabled 처리하거나 `/mypage` 인증 경로로 폴백 라우팅하면 향후 진입점 추가 시 재발을 막는다.

**제품 결정 연동**: docs/16 시크릿 BM 승격 + docs/15 로비 재설계 R2가 로비를 주 진입점으로 만든 상황에서, **가장 트래픽이 많은 진입점의 인증 CTA가 무반응**이다. 블록 A R2 구현 시 누락된 배선으로 보이므로 블록 A 잔여 목록에도 반영할 것.

---

### E-1.14. SupportPanel 알림 탭이 읽음 처리 직후 스스로를 닫음 — initialTab 재계산이 activeTab을 QnA로 되돌림

**🔴 잔존** · P3 · ONE_LINE · FE  
`FE/src/components/SupportPanel.jsx:429-434 · :361-372 · HelpButton.jsx:52-57`

**근거**

파일은 docs/13 작성 이후 무변경이다(`git log --oneline -- src/components/SupportPanel.jsx src/components/HelpButton.jsx` → bfb2bf4, 95dd5c7 둘 다 docs/13 이전). 연쇄를 추적하면:
① HelpButton.jsx:52-57 — `<SupportPanel open={open} onClose={...} onSeen={poll} initialTab={unread > 0 ? "notify" : "qna"} />`
② 미읽음이 있으면 initialTab="notify"로 열리고 SupportPanel.jsx:429-434가 그 값을 activeTab에 심는다:
```
429:  useEffect(() => {
430:    if (open) {
431:      sfx.wooshLight();
432:      setActiveTab(initialTab);
433:    }
434:  }, [open, initialTab]);
```
③ NotificationsTab이 마운트되자마자 전체 읽음 처리 후 onSeen을 부른다 — :361-372
```
368:        markAllNotificationsRead().then(() => onSeen?.()).catch(() => {});
```
④ onSeen = HelpButton의 poll(:20-26) → `setUnread(await fetchUnreadCount())` → unread가 0이 됨
⑤ HelpButton 리렌더 → initialTab이 "notify"→"qna"로 바뀜 → open은 여전히 true이므로 ②의 effect가 deps 변화로 재실행 → `setActiveTab("qna")`
결과: 배지를 보고 알림을 열었는데 **목록을 읽기도 전에 QnA 탭으로 튕긴다**. 패널이 닫히는 게 아니라 알림 탭이 스스로 사라지는 형태다(docs/13 표현 '스스로를 닫음'과 일치).

**수정안**

탭 초기화를 '열리는 순간 1회'로 한정한다. SupportPanel.jsx:429-434의 deps에서 initialTab을 빼고 열림 엣지에서만 반영: `const prevOpen = useRef(false); useEffect(() => { if (open && !prevOpen.current) { sfx.wooshLight(); setActiveTab(initialTab); } prevOpen.current = open; }, [open, initialTab]);` (또는 eslint 예외와 함께 deps를 `[open]`으로 축소). 대안으로 HelpButton.jsx:56에서 initialTab을 오픈 시점에 고정한 state로 넘기는 방법도 동일 효과다 — 어느 쪽이든 '패널이 열려 있는 동안 initialTab 변화가 activeTab을 덮어쓰지 않는다'가 불변식.

**제품 결정 연동**: none. 알림 센터는 §G 처분 목록에 없고 문의·티켓 CS 경로라 런칭 행정(docs/14 §D)상 존속 필수.

---

### E-1.15. EndingCredits SPECIAL THANKS 단계에 자동 진행 타이머 부재 — 무조작 재생이 영구 정지

**🔴 잔존** · P2 · ONE_LINE · FE  
`FE/src/components/EndingCredits.jsx:252-311 (누락 지점) · PHASES :140-150 · 렌더 :791-808`

**근거**

페이즈 전진은 phaseRevealed 게이트를 통과해야만 일어난다:
```
252:  // revealed 후 2초 대기 → 다음 페이즈 자동 이동
253:  useEffect(() => {
254:    if (!phaseRevealed) return;                 ← 여기서 막힌다
255:    if (phase === PHASES.EPILOGUE || phase === PHASES.FADE_IN || phase === PHASES.FIN) return;
261:    const t = setTimeout(() => { setPhase(order[currentIdx + 1]); }, 2000);
```
그리고 phaseRevealed는 페이즈 전환마다 false로 리셋된다(:191-193 `useEffect(() => { setPhaseRevealed(false); }, [phase]);`). 따라서 각 페이즈는 자기 전용 '자동 revealed' 타이머를 가져야 하는데, 있는 것은 4개뿐이다:
· QUOTE — :268-274 `setTimeout(() => setPhaseRevealed(true), duration)`
· MEMORIES — :277-294
· STATS — :297-302 `setTimeout(..., 18000)`
· DEVELOPER — :305-310 `setTimeout(..., 18000)`
PHASES 열거(:140-150)는 FADE_IN, EPILOGUE, TITLE_CARD, QUOTE, MEMORIES, STATS, **ACKNOWLEDGMENTS**, DEVELOPER, FIN 이고, STATS 다음이 ACKNOWLEDGMENTS(:147)인데 **ACKNOWLEDGMENTS용 자동 revealed effect가 존재하지 않는다**. TITLE_CARD는 :244-250에 자체 타이머가 있어 무사하다. 렌더는 정상 존재한다 — :791-808 `{phase === PHASES.ACKNOWLEDGMENTS && ( ... SPECIAL THANKS ...)}` (ACK_DATA 4명, :152-172).
결과: 크레딧을 틀어놓고 자리를 비우면 SPECIAL THANKS에서 멈추고 DEVELOPER/FIN·onComplete에 도달하지 못한다. 유저가 탭(handleSkip :312~)해야만 진행된다.
블록 D 관련 확인: `grep -n ending src/main/resources/application.yml` = 0건 → 엔딩 게이트 플래그 미구현(블록 D 미착수). EndingCredits는 ChatPage.jsx:3905-3906과 ChatPageV2.jsx:4771-4772(`{!isV2 && showEndingCredits && endingData && <EndingCredits .../>}`)에서 현재 렌더된다.

**수정안**

STATS/DEVELOPER와 동형의 effect를 추가한다: `useEffect(() => { if (phase === PHASES.ACKNOWLEDGMENTS) { const t = setTimeout(() => setPhaseRevealed(true), 18000); return () => clearTimeout(t); } }, [phase]);` — :302 뒤(STATS effect와 DEVELOPER effect 사이)에 삽입. ACK_DATA가 4명이고 각 comment가 2~3줄이라 STATS와 같은 18초가 적절하며, 정밀하게 하려면 :269의 `calcTextDuration` 헬퍼로 총 텍스트량 기반 산출도 가능. 재발 방지책으로 :253의 자동 전진 effect에 '타이머 없는 페이즈' 폴백(예: 알려진 페이즈 집합에 없으면 기본 18초 후 revealed)을 넣어두면 향후 페이즈 추가 시 같은 사고를 막는다.

**제품 결정 연동**: ★주의 4 검증 결과 — 블록 D의 엔딩 처분은 docs/14 §C #6 '**엔딩=자유·스토리만 게이트 오프**(코드 보존·극장 유지)'다. 즉 게이트 오프 대상은 **STORY 모드 엔딩**이고 **SANDBOX(자유) 엔딩은 존속**한다. EndingCredits는 ChatPage(§G #2로 SANDBOX 전용 선언 예정)에서 계속 재생되므로 **MOOT가 아니며 수정해야 한다**. 또한 §C #6은 '코드 보존'을 명시하므로 컴포넌트 삭제도 아니다. 다만 STORY 엔딩 트리거가 오프되면 노출 빈도는 줄어들어 P1까지 올릴 이유는 없다.

---

### E-2.1. LoRA ID 맵이 4인 하드코딩 — 공식 6인·전 UGC가 아이리 LoRA로 생성

**🔴 잔존** · P2 · MEDIUM · BE/DB_MIGRATION/YML  
`aichat/src/main/java/com/spring/aichat/service/prompt/IllustrationPromptAssembler.java:270-273`

**근거**

IllustrationPromptAssembler.java:270-273
```java
public String getLoraId(String characterSlug) {
    CharacterVisual visual = CHARACTER_VISUALS.get(characterSlug);
    return visual != null ? visual.loraUrl : CHARACTER_VISUALS.get("airi").loraUrl;
}
```
CHARACTER_VISUALS 등록 slug는 :52/:57/:62/:67의 airi·taeri·luna·yeonhwa 4종뿐.
시드 캐릭터는 10종 — application-characters.yml의 slug: airi(:11) yeonhwa(:178) taeri(:277) luna(:399) claire(:522) rosetta(:640) chaerin(:757) sierra(:870) edel(:982) seolah(:1095). 즉 claire·rosetta·chaerin·sierra·edel·seolah 6인 + 모든 UGC slug가 `lucid_airi_v1`로 폴백한다.
호출부: IllustrationService.java:392 `String loraId = promptAssembler.getLoraId(slug);` → :396 `loras.add(new ModelsLabClient.LoraSlot(loraId, 1.0));` → ModelsLabClient.java:78-89가 lora_model/lora_strength로 실제 전송.
도달성: IllustrationController.java:111 → POST /illustrations/generate (FE IllustrationModal.jsx:102). 자동 경로 3곳도 생존 — ChatStreamService.java:323(승급 성공)·:992(엔딩 트리거)·TheaterAutoNoteService.java:198.
가드 부재 확인: `isSupported(slug)`(:282)는 전 소스 호출처 0건(사문).

**수정안**

§G-6 처분에 종속. 트랙 폐지(권장)면 파일째 삭제로 소멸. 존치한다면: (1) Character 엔티티에 `loraId` 컬럼 신설(Flyway **V31** 일러·극장 묶음 — 2026-08-26 정정 · D-33) + application-characters.yml에 `lora-id:` 시드 10종 + CharacterSeedProperties/applySeed 바인딩, (2) IllustrationService.submitGeneration에서 `character.getLoraId()`를 우선 사용하고 null이면 LoRA 슬롯을 아예 비운다(airi 폴백 금지 — 잘못된 얼굴보다 LoRA 없는 렌더가 낫다), (3) getLoraId(slug)/getLoraUrl/isSupported 삭제.

**제품 결정 연동**: docs/14 §G-6 '레거시 캐릭터 일러 트랙(ModelsLab CG) 동결·씬 일러로 일원화·신규 노출 중단'의 정중앙. 이 맵을 DB 일반화까지 해서 고치는 것은 동결 대상에 대한 투자다. 추가로 자동 트리거 중 승급(ChatStreamService:323)은 §G-1 'V1 승급 시험 삭제'와, 엔딩(:992)은 §C-6 '엔딩 스토리만 게이트 오프'와 세트로 제거·차단될 예정 — 즉 이 결함의 도달면은 곧 축소된다. 반면 극장(TheaterAutoNoteService:198)은 §C-6 '극장 유지'라 남는다.

**❓ 결정 필요**: 레거시 CG 트랙을 (A) 코드 폐지할 것인가, (B) yml 노브로 진입점만 차단하고 코드는 보존할 것인가? 이 답 하나로 E-2.1~E-2.12 열두 건의 처분이 전부 결정된다.

---

### E-2.2. 정체성 태그 맵도 4인 하드코딩 — 미등록 slug는 아이리 외형(분홍머리·다색눈)으로 폴백

**🔴 잔존** · P3 · ONE_LINE · BE  
`aichat/src/main/java/com/spring/aichat/service/prompt/IllustrationPromptAssembler.java:211-212, 220-222`

**근거**

IllustrationPromptAssembler.java:211-212
```java
CharacterVisual visual = CHARACTER_VISUALS.getOrDefault(
    characterSlug, CHARACTER_VISUALS.get("airi"));
```
:220-222
```java
String identity = (identityTagsOverride != null && !identityTagsOverride.isBlank())
    ? identityTagsOverride.trim() : visual.identityPrompt;
```
현재는 **마스킹 상태**다: IllustrationService.java:390이 `character.getAppearanceTags()`를 override로 넘기고, 시드 10종 전원이 application-characters.yml에 `appearance-tags:`를 갖고 있다(:22 :187 :286 :408 :601 :720 :834 :945 :1058 :1177). UGC도 Character.UgcCharacterSpec.appearanceTags(Character.java:718) → createUgc(:770) 경로로 Stage0 산출값이 들어간다.
따라서 airi 외형 폴백(:54 "pink hair, short hair…")은 appearanceTags가 null/공백인 캐릭터에서만 발동 — 현재 시드에는 없으나 Stage0 실패로 appearanceTags가 비는 UGC나 신규 시드 누락 시 즉시 재발하는 잠복 결함.

**수정안**

트랙 존치 시에만: :211-212의 `getOrDefault(slug, CHARACTER_VISUALS.get("airi"))`를 `get(slug)`로 바꾸고, identity가 최종적으로 비면 airi 외형을 붙이는 대신 중립 태그(예: "1girl" 만)로 두거나 조립 자체를 거부한다. 타인의 외형으로 렌더하는 것보다 밋밋한 렌더가 안전.

**제품 결정 연동**: E-2.1과 동일 — §G-6 동결 트랙. 현재 실피해 없음(appearanceTags가 전원 채워져 마스킹)이라 트랙 폐지 시 고민할 가치도 없음.

---

### E-2.3. 복장 resolveOutfit의 default 분기가 AIRI 맵 — DAILY 공식 6인 + 전 UGC가 메이드복으로 조립

**🔴 잔존** · P2 · MEDIUM · BE/DB_MIGRATION/YML  
`aichat/src/main/java/com/spring/aichat/service/prompt/IllustrationPromptAssembler.java:290-298 (default 분기 :296)`

**근거**

IllustrationPromptAssembler.java:290-298
```java
private String resolveOutfit(String slug, String outfit) {
    String key = normalize(outfit);
    return switch (slug == null ? "" : slug) {
        case "taeri"   -> TAERI_OUTFIT_PROMPTS.getOrDefault(key, TAERI_OUTFIT_PROMPTS.get("DAILY"));
        case "luna"    -> LUNA_OUTFIT_PROMPTS.getOrDefault(key, LUNA_OUTFIT_PROMPTS.get("DAILY"));
        case "yeonhwa" -> YEONHWA_OUTFIT_PROMPTS.getOrDefault(key, YEONHWA_OUTFIT_PROMPTS.get("HANBOK"));
        default        -> AIRI_OUTFIT_PROMPTS.getOrDefault(key, AIRI_OUTFIT_PROMPTS.get("MAID"));
    };
}
```
AIRI_OUTFIT_PROMPTS 키는 MAID(:80)·DATE(:81)·SWIMSUIT(:82) 3개뿐 — DAILY 키가 없다.
application-characters.yml default-outfit 실측: claire "DAILY"(:598) · rosetta "DAILY"(:717) · chaerin "DAILY"(:831) · sierra "DAILY"(:942) · edel "DAILY"(:1055) · seolah "DAILY"(:1174) — 6인 전부.
→ default 분기 진입 → key="DAILY" 미스 → :80 값 반환: "maid headdress, bunny hair ornament, classic maid outfit, black dress, white frilled apron, black ribbon bowtie, short puffy sleeves, black thighhighs, mary janes".
UGC도 동일: UgcPipelineWorker.java:565가 defaultOutfit에 리터럴 "DEFAULT"를 넣는다 → 역시 미스 → MAID.
주입 경로: IllustrationService.java:173/221 `room.getCurrentOutfit().name() : c.getEffectiveDefaultOutfit()` → Character.java:478-480 `defaultOutfit != null ? defaultOutfit : "MAID"`.

**수정안**

트랙 폐지면 소멸. 존치한다면 캐릭터별 4개 static 맵 구조 자체를 버리고 DB로 일반화: Character에 `outfitPromptsJson`(또는 character_outfit_prompts 테이블) 신설 + Flyway + 시드. resolveOutfit은 (slug 스위치 없이) 해당 캐릭터의 맵에서 outfit 키를 찾고, 없으면 **아무 태그도 붙이지 않는다**(빈 문자열). 남의 기본 복장으로 폴백하는 현재 설계가 결함의 뿌리다 — 특히 E-2.12(appearanceTags가 이미 복장 태그를 포함) 때문에 폴백은 항상 유해하다.

**제품 결정 연동**: 이중 종속. ①docs/14 §G-6 동결 트랙. ②docs/14 §G-5 '복장·장소 관계 해금 게이트 오프' — 단 impl_spec_details.md §5는 '씬 outfit 필드·복장 표시·전환은 살아있는 기능(리플레이 영속에도 사용) — 죽이는 건 관계 단계별 LOCK뿐'이라 명시했으므로 outfit 값 자체는 계속 흐른다. 즉 게이트오프로 결함이 소멸하지는 않고, 오직 §G-6 트랙 폐지로만 소멸한다.

---

### E-2.4. 복장 맵 키 'SWIMSUIT'가 enum 'SWIMWEAR'와 불일치 — 4개 맵 전부

**🔴 잔존** · P2 · ONE_LINE · BE  
`aichat/src/main/java/com/spring/aichat/service/prompt/IllustrationPromptAssembler.java:82, 89, 96, 103`

**근거**

IllustrationPromptAssembler.java
:82 `AIRI_OUTFIT_PROMPTS.put("SWIMSUIT", "swimsuit, bikini, navel, cleavage, sarong, …");`
:89 `TAERI_OUTFIT_PROMPTS.put("SWIMSUIT", "swimsuit, bikini, navel");`
:96 `LUNA_OUTFIT_PROMPTS.put("SWIMSUIT", "swimsuit, bikini");`
:103 `YEONHWA_OUTFIT_PROMPTS.put("SWIMSUIT", "swimsuit, bikini");`
Outfit.java:16은 `SWIMWEAR,   // 수영복` — 'SWIMSUIT'라는 상수는 enum에 존재하지 않는다.
키 입력은 항상 enum name(): IllustrationService.java:173 `room.getCurrentOutfit().name()`. SWIMWEAR가 실제로 세팅되는 경로도 살아 있다 — ChatRoom.java:680 `this.currentOutfit = Outfit.valueOf(outfit);`(LLM 씬 상태 산출).
해금 시드에도 존재: application-characters.yml:155 airi `friendUnlockOutfits: "SWIMWEAR"`, :378 taeri 동일.
→ SWIMWEAR 입력 시 4개 맵 전부 미스 → airi=MAID / taeri·luna=DAILY / yeonhwa=HANBOK로 렌더. 4개 SWIMSUIT 항목은 도달 불가 사문.

**수정안**

트랙 존치 시: 4곳의 문자열 리터럴 "SWIMSUIT" → "SWIMWEAR". 재발 방지로 맵 키를 String이 아니라 `Map<Outfit,String>`(EnumMap)으로 바꾸면 컴파일 타임에 오타가 잡힌다 — 권장.

**제품 결정 연동**: docs/13 §H 배치4가 'SWIMSUIT→SWIMWEAR 4곳'을 '한 커밋짜리 저비용 고효과'로 분류했으나, §G-6 동결 트랙이라 **고쳐도 사용자에게 도달하지 않을 코드**다. 배치4에서 빼는 것을 권고. 또 §G-5 복장 해금 게이트오프로 SWIMWEAR가 '해금'을 통해 세팅되는 경로는 줄지만, LLM 씬 상태(ChatRoom:680)로는 계속 세팅 가능.

---

### E-2.5. PAJAMA·NEGLIGEE가 4개 복장 맵 어디에도 없음 — 잠옷/네글리제 씬이 메이드복

**🔴 잔존** · P2 · SMALL · BE  
`aichat/src/main/java/com/spring/aichat/service/prompt/IllustrationPromptAssembler.java:78-104`

**근거**

4개 맵의 전체 키 집합(IllustrationPromptAssembler.java:78-104):
- AIRI(:80-82): MAID, DATE, SWIMSUIT
- TAERI(:87-89): DAILY, DATE, SWIMSUIT
- LUNA(:94-96): DAILY, DATE, SWIMSUIT
- YEONHWA(:101-103): HANBOK, DAILY, SWIMSUIT
PAJAMA·NEGLIGEE 문자열은 파일 전체에 0회.
Outfit.java:14 `PAJAMA,     // 잠옷` / :17 `NEGLIGEE,    // 네글리제 (시크릿 전용)`.
해금 시드 실재: application-characters.yml:153 airi `acquaintanceUnlockOutfits: "DATE,PAJAMA"`, :157 airi `loverUnlockOutfits: "NEGLIGEE"`, 표시명 :162-164.
→ airi 방에서 PAJAMA/NEGLIGEE 세팅 시 AIRI 맵 미스 → :80 MAID 태그. **시크릿 전용 복장 NEGLIGEE가 메이드복으로 렌더**되는 것이 docs/16(시크릿=핵심 BM) 맥락에서 특히 나쁘다 — 다만 렌더 주체가 동결 대상 레거시 CG 트랙이라 실질 영향은 제한적.

**수정안**

트랙 존치 시: 캐릭터별 맵에 PAJAMA/NEGLIGEE 항목 추가(예 AIRI: PAJAMA→"pajamas, long sleeves, loose clothes, barefoot", NEGLIGEE→"negligee, lingerie, sheer, lace trim"). 단 E-2.3 제안대로 DB 일반화로 가면 시드 저작으로 해결. NEGLIGEE는 시크릿 전용이므로 sfw 게이트와 함께 다뤄야 한다(레거시 트랙에는 sfw 게이트가 없다는 점도 별도 검토 필요).

**제품 결정 연동**: §G-6 동결 트랙. 또 §G-5 복장 해금 게이트오프로 PAJAMA/NEGLIGEE의 '해금' 경로는 사라지지만, impl_spec_details §5대로 복장 전환 자체는 살아 있어 LLM이 NEGLIGEE를 세팅할 수 있다. docs/16 시크릿 피벗에서 네글리제 연출을 살릴 계획이라면 **씬 일러 트랙에서** 다뤄야 한다(씬 트랙은 복장을 LLM 태그로 직접 받으므로 이 결함 자체가 없음).

---

### E-2.6. UGC 규약 복장 'DEFAULT'가 맵에 없음 — 전 UGC 캐릭터 CG가 메이드복

**🔴 잔존** · P2 · SMALL · BE  
`aichat/src/main/java/com/spring/aichat/service/prompt/IllustrationPromptAssembler.java:78-83, 296 / UgcPipelineWorker.java:565`

**근거**

Outfit.java:19-24는 UGC 전용 상수 DEFAULT를 선언한다("에셋 규약 characters/{slug}/default_{emotion}.png").
UgcPipelineWorker.java:565가 `Character.UgcCharacterSpec` 생성 시 defaultOutfit 자리에 리터럴 `"DEFAULT"`를 넘긴다.
IllustrationPromptAssembler의 4개 맵 어디에도 "DEFAULT" 키가 없다(:80-82 :87-89 :94-96 :101-103).
UGC slug는 CHARACTER_VISUALS(:52-72)에도 없으므로 resolveOutfit :296 default 분기 → AIRI 맵 미스 → MAID.
→ **모든 UGC 캐릭터의 레거시 CG가 메이드복으로 렌더**된다. 게다가 그 캐릭터의 appearanceTags에는 이미 자기 복장이 들어 있어 E-2.12(이중 복장 충돌)와 합쳐진다.

**수정안**

트랙 존치 시: resolveOutfit에서 키가 "DEFAULT"이거나 캐릭터가 UGC이면 복장 슬롯을 **빈 문자열로 반환**(appearanceTags가 이미 복장을 서술하므로 추가 태그가 오히려 충돌). 즉 `if ("DEFAULT".equals(key) || identityTagsOverride 존재) return "";` — E-2.12와 한 세트로 고칠 것.

**제품 결정 연동**: §G-6 동결 트랙. 다만 docs/14의 플랫폼(UGC 25E) 방향에서는 'UGC가 공식 4인 전용 맵에 폴백한다'는 구조 자체가 플랫폼 비대칭(§G-5가 지적한 것과 같은 유형)이라, 트랙을 존치할 명분이 더 약해진다.

---

### E-2.7. 감정 맵 키 'SURPRISED'가 enum 'SURPRISE'와 불일치 — 4개 맵 전부

**🔴 잔존** · P3 · ONE_LINE · BE  
`aichat/src/main/java/com/spring/aichat/service/prompt/IllustrationPromptAssembler.java:138, 150, 161, 172`

**근거**

IllustrationPromptAssembler.java
:138 `AIRI_EMOTION_PROMPTS.put("SURPRISED",  "wide eyes, mouth open, surprised expression");`
:150 `TAERI_EMOTION_PROMPTS.put("SURPRISED", "wide eyes, parted lips");`
:161 `LUNA_EMOTION_PROMPTS.put("SURPRISED",  "surprised eyes wide");`
:172 `YEONHWA_EMOTION_PROMPTS.put("SURPRISED",  "ears perked, wide eyes");`
EmotionTag.java:12는 `SURPRISE, // 놀람` — 'SURPRISED'는 enum에 없다.
입력은 항상 enum name(): IllustrationService.java:170 `room.getLastEmotion().name()`, :192 `heroine.getLastEmotion().name()`(ChatRoom.java:212 / ChatRoomHeroine.java:118 모두 `EmotionTag` 타입).
→ 놀람 표정이 4개 캐릭터 전부 NEUTRAL(무표정)로 폴백, SURPRISED 항목 4개는 사문.

**수정안**

트랙 존치 시: 4곳의 "SURPRISED" → "SURPRISE". E-2.8과 함께 `EnumMap<EmotionTag,String>`으로 전환하면 재발 불가.

**제품 결정 연동**: §G-6 동결 트랙. docs/13 §H 배치4의 '감정 키 10종'에 포함돼 있으나 배치4에서 제외 권고.

---

### E-2.8. EmotionTag 15개 중 10개가 어느 감정 맵에도 없어 무표정(NEUTRAL) 폴백 — 전수 열거

**🔴 잔존** · P2 · MEDIUM · BE  
`aichat/src/main/java/com/spring/aichat/service/prompt/IllustrationPromptAssembler.java:130-174, 319-327`

**근거**

EmotionTag.java 전체 15값: NEUTRAL, JOY, SAD, ANGRY, SHY, SURPRISE, PANIC, RELAX, DISGUST, FRIGHTENED, FLIRTATIOUS, HEATED, DUMBFOUNDED, SULKING, PLEADING.
4개 맵 키 대조(IllustrationPromptAssembler.java):
- AIRI(:132-140, 9키): NEUTRAL JOY LAUGH SHY SAD LOVE SURPRISED ANGRY EMBARRASSED
- TAERI(:145-151, 7키): NEUTRAL JOY SHY SAD LOVE SURPRISED ANGRY
- LUNA(:156-162, 7키): NEUTRAL JOY SHY SAD LOVE SURPRISED ANGRY
- YEONHWA(:167-173, 7키): NEUTRAL JOY SHY SAD LOVE SURPRISED ANGRY
★ 4개 맵 **전부** enum과 겹치는 키는 5개뿐: NEUTRAL · JOY · SAD · ANGRY · SHY.
★ 어느 맵에도 없어 NEUTRAL로 폴백되는 enum 값 **10개(전수)**: SURPRISE · PANIC · RELAX · DISGUST · FRIGHTENED · FLIRTATIOUS · HEATED · DUMBFOUNDED · SULKING · PLEADING.
폴백 코드 :319-327 `…EMOTION_PROMPTS.getOrDefault(key, …get("NEUTRAL"))` — 4개 분기 전부 NEUTRAL.
즉 유혹적(FLIRTATIOUS)·흥분(HEATED)·애원(PLEADING) 같은 시크릿 핵심 표정이 전부 "calm face, soft expression"으로 렌더된다.

**수정안**

트랙 존치 시: 4개 맵을 `EnumMap<EmotionTag,String>`으로 바꾸고 15값 전부 채운다(누락 시 컴파일 타임 검출은 안 되므로 `@PostConstruct`에서 `EmotionTag.values()` 전수 커버리지 assert 추가 권장). 미기재 값은 NEUTRAL 폴백 대신 **감정 슬롯 생략**이 안전(무표정을 강제하는 것보다 LLM sceneHint에 맡김). 캐릭터별 4벌 유지 대신 공통 기본 맵 1벌 + 캐릭터별 오버라이드(연화 귀 표현 등)로 축약 권장.

**제품 결정 연동**: §G-6 동결 트랙. 단 docs/16(시크릿=핵심 BM)에서 FLIRTATIOUS/HEATED 표정 표현이 중요해지는데, **그 요구는 생존 트랙인 씬 일러가 이미 충족**한다(ScenePromptAssembler는 감정을 enum이 아니라 LLM danbooru 태그로 받고, SceneDirectorService.java:151이 'emotion must be lowercase danbooru tags, NEVER emotion enum words'로 명시 지시). 즉 이 결함은 고칠 게 아니라 트랙 폐지로 소멸시키는 것이 정합.

---

### E-2.9. 감정 맵의 사문 키 4종(LAUGH·LOVE·EMBARRASSED·SURPRISED) — ★단 'LOVE' 삭제는 자동 CG를 깨뜨림

**🔴 잔존** · P3 · SMALL · BE  
`aichat/src/main/java/com/spring/aichat/service/prompt/IllustrationPromptAssembler.java:134, 137, 140, 149, 160, 171 / IllustrationService.java:217, 223`

**근거**

enum EmotionTag에 존재하지 않는 맵 키 4종:
- LAUGH — AIRI만(:134)
- LOVE — 4개 맵 전부(:137 :149 :160 :171)
- EMBARRASSED — AIRI만(:140)
- SURPRISED — 4개 맵 전부(E-2.7 참조)
★ 함정: LOVE는 사문이 아니다. IllustrationService.java:217-223
```java
String emotion = "LOVE";
…
if ("ENDING".equals(triggerType)) {
    emotion = "JOY";
}
```
generateAutoIllustration(승급/엔딩/극장 자동노트 경로)이 **enum이 아닌 리터럴 "LOVE"/"JOY"** 를 넘긴다. 즉 LOVE 항목을 '사문'으로 판단해 지우면 승급·극장 자동 CG의 표정이 NEUTRAL로 퇴행한다.
LAUGH·EMBARRASSED는 enum에도 없고 리터럴 호출처도 없어 진짜 사문.

**수정안**

트랙 존치 시 EnumMap 전환 작업(E-2.8)과 동시 처리: (1) LAUGH·EMBARRASSED 제거, (2) SURPRISED→SURPRISE 개명(E-2.7), (3) **LOVE는 제거하지 말고** IllustrationService.java:217/223의 문자열 리터럴을 EmotionTag 상수로 승격시켜야 한다 — 'LOVE'에 해당하는 enum이 없으므로 EmotionTag에 LOVE를 추가하거나(파급 큼) 자동 경로가 FLIRTATIOUS 등 기존 값을 쓰도록 바꾼다. 순서를 지키지 않으면 조용한 표정 퇴행이 발생.

**제품 결정 연동**: §G-6 동결 트랙. 게다가 이 리터럴을 넘기는 3개 자동 경로 중 승급(ChatStreamService:323)은 §G-1로 제거, 엔딩(:992)은 §C-6 게이트오프 대상이라 극장 경로만 남는다. 트랙 폐지 시 전부 소멸.

---

### E-2.10. 장소 맵 11키 중 enum과 겹치는 건 3개뿐 — 8키 사문·enum 11값이 'simple background'로 폴백 (전수 대조표)

**🔴 잔존** · P2 · MEDIUM · BE/DB_MIGRATION/YML  
`aichat/src/main/java/com/spring/aichat/service/prompt/IllustrationPromptAssembler.java:111-124, 308-317`

**근거**

LOCATION_PROMPTS 키 11개(IllustrationPromptAssembler.java:113-123): MAID_CAFE, HOME, BEDROOM, KITCHEN, OUTDOOR, PARK, BEACH, CAFE, SCHOOL, ROOFTOP, LIBRARY.
Location enum 14값(Location.java:9-22): LIVINGROOM, BALCONY, STUDY, BATHROOM, GARDEN, KITCHEN, BEDROOM, ENTRANCE, FOREST, BEACH, DOWNTOWN, BAR, CLUB_ROOM, CONVENIENCE_STORE.
★ 교집합 3개(전수): **BEDROOM**(:115) · **KITCHEN**(:116) · **BEACH**(:119).
★ 도달 불가 사문 키 8개(전수): **MAID_CAFE**(:113) · **HOME**(:114) · **OUTDOOR**(:117) · **PARK**(:118) · **CAFE**(:120) · **SCHOOL**(:121) · **ROOFTOP**(:122) · **LIBRARY**(:123).
★ "simple background"로 폴백되는 enum 값 11개(전수): **LIVINGROOM · BALCONY · STUDY · BATHROOM · GARDEN · ENTRANCE · FOREST · DOWNTOWN · BAR · CLUB_ROOM · CONVENIENCE_STORE**.
폴백 코드 :316 `return LOCATION_PROMPTS.getOrDefault(normalize(location), "simple background");`
실측 파급: 시드 default-location 10종 중 맵에 걸리는 것이 0개다 — airi ENTRANCE(:137) yeonhwa FOREST(:237) taeri CLUB_ROOM(:360) luna CONVENIENCE_STORE(:483) claire CATHEDRAL(:599) rosetta TERRACE(:718) chaerin TERRACE(:832) sierra GARDEN(:943) edel TERRACE(:1056) seolah ABANDONED_SHRINE(:1175). 전원 "simple background".
완화 요인: :308-315의 dynamicLocationDescription이 있으면 맵을 건너뛴다(IllustrationService.java:135-139 `buildDynamicLocationTagsFromCache`) — 동적 장소가 세팅된 방에서만 배경이 산다.

**수정안**

트랙 존치 시: LOCATION_PROMPTS를 `EnumMap<Location,String>`으로 바꾸고 14값 전부 채운다(사문 8키는 제거). 다만 docs/14 §G-5 'V1 장소 enum은 동적 배경으로 단계적 일원화' 방향과 충돌하므로, 더 나은 안은 **정적 enum 맵 자체를 폐기하고 dynamicLocationDescription 경로로 일원화**하는 것 — `resolveLocation`은 동적 묘사가 없으면 장소 슬롯을 생략(빈 문자열)한다. 그리고 E-3 ①(CATHEDRAL/TERRACE/ABANDONED_SHRINE 등 enum 부재 시드)과 반드시 같은 커밋에서 다뤄야 한다.

**제품 결정 연동**: 삼중 종속. ①§G-6 동결 트랙(고칠 가치 소멸). ②§G-5 'V1 장소 enum은 동적 배경으로 단계적 일원화' — enum 맵을 14값으로 채우는 작업은 폐기 예정 체계에 대한 투자다. ③docs/13 E-3 ①(Location enum에 없는 시드 장소 5종)과 원인이 얽혀 있어 단독 수정 불가.

**❓ 결정 필요**: §G-5의 'V1 장소 enum 동적 배경 일원화'를 언제 착수할 것인가? 그 일정이 정해지면 E-2.10·E-2.11·E-3①의 처분이 한꺼번에 결정된다.

---

### E-2.11. V2·UGC 경로는 애초에 비-enum 문자열을 장소 슬롯에 넘김 — 구조적으로 항상 'simple background'

**🔴 잔존** · P3 · SMALL · BE  
`aichat/src/main/java/com/spring/aichat/service/illustration/IllustrationService.java:193 (V2) / :171, :219 (V1 폴백) / Character.java:482-484`

**근거**

IllustrationService.java:191-193 (V2 STORY 분기)
```java
// V2는 V1 Location enum 미사용 — 캐릭터별 기본 location으로 LoRA 학습 데이터와 정합 유지.
String location = c.getEffectiveDefaultLocation();
```
Character.java:482-484 `return defaultLocation != null ? defaultLocation : "ENTRANCE";` — 이는 **시드 yml의 원시 문자열**이지 Location enum name이 아니다.
결과: claire="CATHEDRAL", rosetta/chaerin/edel="TERRACE", seolah="ABANDONED_SHRINE" 같은 enum에도 없는 값이 그대로 LOCATION_PROMPTS 키로 조회된다 → 미스 → "simple background".
UGC는 더 확정적이다: Character.UgcCharacterSpec(:700-720)에 defaultLocation 필드 자체가 없다 → createUgc가 세팅하지 않음 → null → "ENTRANCE" → 맵 미스 → simple background.
V1도 room.getCurrentLocation()이 null이면 같은 문자열 경로로 떨어진다(:171, :219).

**수정안**

트랙 존치 시: E-2.10의 'enum 맵 폐기 + 동적 묘사 일원화'로 가면 자동 해소. 중간 조치가 필요하면 IllustrationService에서 문자열을 `Location.valueOf` 시도 후 실패 시 장소 슬롯 생략(빈 문자열)하도록 정규화 헬퍼를 두고, V2 분기 주석(:191-192)의 'LoRA 학습 데이터 정합' 근거가 지금도 유효한지 재확인한다(LoRA 자체가 E-2.1대로 airi 고정 폴백이라 근거가 이미 무너져 있음).

**제품 결정 연동**: §G-6 동결 트랙 + §G-5 장소 일원화. 추가로 docs/13 E-3 ①과 동일 뿌리(시드 장소 문자열이 enum과 무관)라 그쪽 담당 섹션과 중복 보고될 수 있음 — 여기서는 '일러 조립 측 파급'만 다룬다.

---

### E-2.12. 복장 슬롯이 appearanceTags의 복장 태그와 이중 충돌 — 수녀복 캐릭터에 메이드복 태그가 덧씌워짐

**🔴 잔존** · P2 · ONE_LINE · BE  
`aichat/src/main/java/com/spring/aichat/service/prompt/IllustrationPromptAssembler.java:219-225`

**근거**

IllustrationPromptAssembler.java:219-225 — 슬롯 (2) 정체성과 슬롯 (3) 복장이 무조건 둘 다 append된다.
```java
String identity = (identityTagsOverride != null && !identityTagsOverride.isBlank())
    ? identityTagsOverride.trim() : visual.identityPrompt;
sb.append(identity).append(", ");
// (3) 복장
sb.append(resolveOutfit(characterSlug, outfit)).append(", ");
```
그런데 시드 appearance-tags에는 **이미 복장 태그가 포함**돼 있다. application-characters.yml:601 claire = "blue eyes, blonde hair, long hair, side braid, **nun, habit, veil, cross necklace, latin cross, white dress, gold trim**, long sleeves, …".
claire는 CHARACTER_VISUALS 미등록 + default-outfit "DAILY"(:598) → E-2.3에 의해 resolveOutfit이 AIRI MAID 문자열을 반환.
→ 최종 프롬프트에 "nun, habit, veil, white dress …, maid headdress, bunny hair ornament, classic maid outfit, black dress, white frilled apron …"이 동시에 실린다. 수녀복과 메이드복이 한 프롬프트에서 경합.
airi(:22)도 appearance-tags에 "maid, maid headdress, rabbit hair ornament, black dress, white apron…"가 이미 있어, MAID일 때 동일 태그가 중복 가중된다.
UGC 전원 동일(E-2.6).

**수정안**

트랙 존치 시: `identityTagsOverride`가 존재하는 경우(=DB appearanceTags 사용) 복장 슬롯을 **건너뛴다**. 즉 :224-225를 `if (identityTagsOverride == null || identityTagsOverride.isBlank()) sb.append(resolveOutfit(...)).append(", ");`로. 근본 해법은 시드/Stage0에서 appearanceTags를 '외형만'과 '복장'으로 분리 저장하는 것이나, 현재 시드 10종이 이미 혼재 저장이라 스키마 변경 + 시드 재작성이 필요(LARGE). 단기는 위 한 줄. ★E-2.3·E-2.5·E-2.6의 '맵을 채운다' 방향은 이 결함을 악화시키므로, 맵 보강 전에 이 판단부터 확정해야 한다.

**제품 결정 연동**: §G-6 동결 트랙이면 전부 무의미. 반대로 트랙을 존치하기로 하면 이 결함 때문에 docs/13이 제안한 '맵 전면 재작성' 방향 자체가 재검토 대상이 된다 — appearanceTags(2026-07-30 B-3 DB 일반화)와 하드코딩 복장 맵(Phase 5.5 유산)이 설계상 양립하지 않는다.

**❓ 결정 필요**: appearanceTags를 '외형 전용'으로 재정의하고 복장을 분리할 것인가, 아니면 appearanceTags를 '완성된 캐릭터 룩'으로 보고 복장 슬롯을 폐지할 것인가? (트랙을 존치할 때만 답이 필요)

---

### E-2.13. CharacterPromptAssembler가 UGC 캐릭터 프롬프트에 'Age: null' 리터럴 삽입

**🔴 잔존** · P2 · ONE_LINE · BE  
`aichat/src/main/java/com/spring/aichat/service/prompt/CharacterPromptAssembler.java:86 (템플릿) · :134 (인자)`

**근거**

CharacterPromptAssembler.java:86 템플릿 `            - Age: %s`
:134 인자 `            character.getAge(),                                               // Identity Age` — 널 가드 없음. Java `String.format`의 %s는 null을 문자열 "null"로 렌더한다.
Character.java:94-95 `@Column(name = "age", length = 100) private Integer age;`
★ UGC가 항상 null인 확정 근거: `Character.UgcCharacterSpec` 레코드(Character.java:700-720)에 **age 필드 자체가 없다**. `createUgc(spec)`(:730-772)는 tagline·description·role·personality·tone·appearance·clothing·backstory·… 를 전부 복사하지만 `c.age`에는 아무것도 대입하지 않는다. Character.java 전체에서 age 대입은 :443 `if (seed.age() != null) this.age = seed.age();` 단 한 곳(시드 전용)뿐.
→ 모든 UGC 캐릭터의 시스템 프롬프트 최상단 Identity 블록에 "- Age: null"이 실린다.
★ 대조군(V2는 가드됨): StoryDirectorPromptAssemblerV2.java:355 `c.getAge() != null ? c.getAge().toString() : "(미상)"`.

**수정안**

CharacterPromptAssembler.java:134를 `character.getAge() != null ? character.getAge().toString() : "(미상)"`으로 교체(StoryDirectorPromptAssemblerV2:355와 동일 문구로 통일). 근본 보강은 UgcCharacterSpec에 age 필드를 추가하고 Stage0 산출을 바인딩하는 것(SMALL) — 다만 나이는 시크릿 나이 게이트(블록 B PERSONA_UNDERAGE)와 얽히므로 UGC에 나이를 도입할지는 제품 판단.

**제품 결정 연동**: none — 블록 D/§G/docs/16 어느 처분과도 무관. **레거시 CG 트랙이 아니라 텍스트 채팅 시스템 프롬프트**이므로 §G-6 동결의 영향을 받지 않는다. E-2 섹션에서 트랙 폐지와 무관하게 반드시 고쳐야 하는 3건 중 하나.

**❓ 결정 필요**: UGC 캐릭터에 나이 필드를 도입할 것인가? 도입 시 블록 B의 시크릿 나이 하드 게이트(PERSONA_UNDERAGE)를 UGC 캐릭터에도 적용해야 하는지(현재는 유저 페르소나 나이만 게이트)가 함께 결정돼야 한다 — docs/16 법적 그라디언트와 직결.

---

### E-2.14. [신규 인접] TheaterPromptAssembler도 동일한 'Age: null' 무가드 — 극장은 §C-6로 존치되는 경로

**🔴 잔존** · P3 · ONE_LINE · BE  
`aichat/src/main/java/com/spring/aichat/service/prompt/TheaterPromptAssembler.java:212`

**근거**

`grep -rn "getAge()" src/main/java/` 결과 무가드 호출 2곳:
- CharacterPromptAssembler.java:134 (E-2.13)
- TheaterPromptAssembler.java:212 `sb.append("Age: ").append(heroine.getAge()).append("\n");`
(`StringBuilder.append(Object)`도 null을 "null"로 렌더)
:200 `Character heroine = ctx.speakerHeroine();` — 타입이 Character이므로 E-2.13과 동일하게 UGC 히로인이면 age가 항상 null.
가드된 대조군: StoryDirectorPromptAssemblerV2.java:355 · StoryV2Service.java:283/:355(`c.getAge() != null ? c.getAge() : 0`).
도달성: UGC 캐릭터는 ugcWorldId가 있으면 theaterAvailable=true(Character.java:740 `c.theaterAvailable = spec.ugcWorldId() != null;`)라 극장 스피커가 될 수 있다.

**수정안**

TheaterPromptAssembler.java:212를 `sb.append("Age: ").append(heroine.getAge() != null ? heroine.getAge() : "(미상)").append("\n");`로. E-2.13과 같은 커밋에서 처리.

**제품 결정 연동**: none — 오히려 docs/14 §C-6이 '극장 유지'를 명시했으므로 이 경로는 확실히 살아남는다. 고쳐야 한다.

---

### E-2.15. 씬 일러(생존 트랙) 유저 성별이 여전히 LLM 출력에만 의존 — 페르소나 스냅샷은 프롬프트 힌트로만 배선

**🟠 부분수정** · P2 · SMALL · BE  
`aichat/src/main/java/com/spring/aichat/service/illustration/scene/SceneRenderService.java:251, 255`

**근거**

【수정된 부분 — 블록 B `cab6b3e`】
ChatRoom.java:122-124에 스냅샷 컬럼이 신설됐다.
```java
/** [페르소나] 유저 성별 스냅샷 — 씬 렌더 유저 표현 기준(null=MALE 기존 폴백). */
@Enumerated(EnumType.STRING)
@Column(name = "persona_gender", length = 10)
private com.spring.aichat.domain.enums.CharacterGender personaGender;
```
ChatRoom.java:134-136 `isPersonaUserMale()`. 유일한 외부 호출처는 SceneRequestService.java:157-159
```java
AiJsonOutput.SceneIllustrationSpec spec =
    directorService.composeSpec(recentLogs, cast, resolveLocationText(room), sfw,
        room.isPersonaUserMale());   // [페르소나] 유저 성별 스냅샷 반영
```
→ SceneDirectorService.java:121-174 `buildSystemPrompt(...)`의 :158/:174 `The user is an ADULT %s` … `.formatted(heroineNames, userMale ? "male" : "female", rating)` — **LLM에게 주는 지시문**으로만 쓰인다.
【잔존 부분 — 미수정】
SceneRenderService.java:251,255 (docs/13이 지목한 바로 그 라인)
```java
boolean male = c.isMale();                                    // :251
…
actors.add(new ScenePromptAssembler.SceneActor(null, c.emotion(), c.pose(), true, male));  // :255
```
유저 액터의 성별 권위는 여전히 LLM 산출 `SceneCast.gender`다. AiJsonOutput.java:147-154 `isMale()`은 gender가 비면 `return isUser();` → **무조건 male**.
대조: 같은 루프의 히로인은 :259-260에서 DB가 권위 — `boolean actorMale = hero != null ? hero.getGenderOrDefault().isMale() : male;`. 유저만 이 처리가 빠졌다.
파급: 여성 페르소나 유저의 방에서 디렉터 LLM이 gender 필드를 누락하면 ScenePromptAssembler.userIdentity(:187-190)가 "faceless male, mature male, adult"를 붙여 남성으로 렌더된다. pov 경로(:113-119)의 male pov/female pov 선택도 같은 값에 의존.

**수정안**

planRender에 유저 성별을 결정론적으로 주입한다. (1) `SceneRenderService.planRender(List<Character>, SceneIllustrationSpec, boolean sfw)`에 `Boolean userMale` 파라미터를 추가(기존 오버로드는 null 전달로 무회귀), (2) :251-255에서 `boolean male = (c.isUser() && userMale != null) ? userMale : c.isMale();`로 유저 액터만 스냅샷을 권위로 삼는다 — 히로인의 DB-권위 패턴(:259-260)과 대칭, (3) castKey(:252-253)도 같은 값으로 계산해야 scene_hash 디덥이 성별 변경을 인지한다, (4) 호출부 SceneRequestService.java:160 `renderService.submitManual(...)`에 `room.isPersonaUserMale()`을 함께 전달(submitManual 시그니처 확장).

**제품 결정 연동**: ★ 이 결함은 **생존 트랙**(씬 일러/RunPod)에 있다 — docs/14 §G-6 동결 대상이 아니며 docs/16에서 핵심 BM 기반으로 승격된 바로 그 트랙이다. 블록 B(페르소나 개편)가 성별 선택을 실제 제품 기능으로 만들었으므로 오히려 우선순위가 올라갔다. E-2 섹션에서 반드시 고쳐야 하는 항목.

---

### E-2.15b. 씬 일러 자동(인밴드) 경로에는 페르소나 성별이 아예 미전달 — 현재는 trigger=manual로 휴면

**🔴 잔존** · P3 · SMALL · BE  
`aichat/src/main/java/com/spring/aichat/service/stream/ChatStreamService.java:464-466 / SceneRenderService.java:97-99`

**근거**

ChatStreamService.java:462-466
```java
com.spring.aichat.service.illustration.scene.SceneRenderService.SceneView view = sceneRenderService.resolveForTurn(
    roomId, List.of(jpa.room().getCharacter()),
    parsed.aiOutput(), (int) (jpa.logCount() + 1), !effectiveSecretMode);
```
`resolveForTurn`(SceneRenderService.java:97-99)에는 성별 파라미터가 없고 `planRender(roomCharacters, out, sfw)`(:233-235)로 흘러 E-2.15와 동일하게 `c.isMale()`에 의존한다. 수동 경로가 받은 `room.isPersonaUserMale()` 배선이 여기엔 없다.
또 인밴드 경로는 SceneDirectorService를 거치지 않으므로 '유저는 성인 female'이라는 프롬프트 힌트조차 없다 — 채팅 LLM이 sceneIllustration.cast[].gender를 스스로 채워야 한다.
휴면 근거: SceneRenderService.java:79-81 `autoReady()` = `ready() && props.isAutoTrigger()`, SceneIllustrationProperties.java:61-62 `"auto".equalsIgnoreCase(trigger)`, application.yml:140 `trigger: ${SCENE_ILLUST_TRIGGER:manual}` — 기본 manual이라 이 블록은 현재 실행되지 않는다(프로드 env에 SCENE_ILLUST_TRIGGER=auto가 없다는 전제하에).

**수정안**

E-2.15의 planRender 시그니처 확장과 한 세트로: `resolveForTurn`에 userMale을 추가하고 ChatStreamService.java:464 호출부에서 `jpa.room().isPersonaUserMale()`을 넘긴다. 추가로 채팅 LLM 출력 포맷 안내(CharacterPromptAssembler의 output format 블록)에도 유저 성별을 명시해 cast[].gender 산출 정확도를 올린다.

**제품 결정 연동**: 생존 트랙(§G-6 동결 대상 아님). 다만 현재 trigger=manual로 휴면이라 즉시 피해는 없다 — SCENE_ILLUST_TRIGGER=auto로 전환할 때 반드시 선행돼야 하는 항목. docs/16이 씬 일러를 핵심 BM으로 올렸으므로 auto 전환 검토 시 재부상한다.

**❓ 결정 필요**: 인밴드 자동 씬 일러(trigger=auto)를 되살릴 계획이 있는가? 없다면 ChatStreamService:462-476 블록과 resolveForTurn 자체를 §G-4 '데드 코드 일괄'에 넣는 편이 낫다.

---

### E-3.①.1. 클레어 default-location "CATHEDRAL"이 Location enum에 없어 V1 방이 '저택 현관'으로 시작한다

**🔴 잔존** · P2 · ONE_LINE · YML  
`src/main/resources/application-characters.yml:599 (소비: src/main/java/com/spring/aichat/domain/chat/ChatRoom.java:369, 402, 795, 1130)`

**근거**

application-characters.yml:599 `      default-location: "CATHEDRAL"` (521행 `- name: 클레어` 블록). Location.java 전체 값 = LIVINGROOM/BALCONY/STUDY/BATHROOM/GARDEN/KITCHEN/BEDROOM/ENTRANCE/FOREST/BEACH/DOWNTOWN/BAR/CLUB_ROOM/CONVENIENCE_STORE — CATHEDRAL 없음. ChatRoom.java:1130-1132 `private static Location parseLocationOrDefault(String v) { try { return Location.valueOf(v); } catch (Exception e) { return Location.ENTRANCE; } }`. 도달 경로 실재: LobbyService.java:206 `ChatRoom created = new ChatRoom(user, character, chatMode);` → ChatRoom.java:369 `this.currentLocation = parseLocationOrDefault(character.getEffectiveDefaultLocation());`. 블록 A 신규 경로 OnboardingService.java:45 `new ChatRoom(user, character)`도 동일. 결과: CharacterPromptAssembler.java:339-352 `# 💡 CURRENT SCENE STATE - location : %s`에 성당의 성녀가 `ENTRANCE`(저택 현관)로 주입된다.

**수정안**

노선 (a) 채택 시: Location.java에 `CATHEDRAL,`를 추가하는 것만으로 이 행은 해소(컬럼 varchar(20), CHECK 제약 없음 → 마이그레이션 불필요). 노선 (b) 채택 시: application-characters.yml:599를 기존 enum 값으로 교정 — 성당 성녀에 의미상 가장 가까운 값이 없으므로 `GARDEN`(성당 뒤편 정원, v2.yml:53-55 클레어 루틴에 이미 존재) 또는 `LIVINGROOM`. 노선 (c) 채택 시: yml은 그대로 두고 LobbyService.createRoom에서 `seedUgcWorldBackground`(LobbyService.java:353) 패턴을 공식 캐릭터로 확장해 `room.updateDynamicBackground("대성당", canonicalKey, bgUrl)`을 시딩. 어느 노선이든 E-2 `IllustrationPromptAssembler.LOCATION_PROMPTS`(:111-123)에 CATHEDRAL 키가 없어 `"simple background"`로 떨어지는 문제는 별도 동반 수정 필요.

**제품 결정 연동**: §G-5 '복장·장소 관계 해금 게이트 오프 — V1 장소 enum은 동적 배경으로 단계적 일원화'와 정면으로 얽힌다. enum 확장은 '일원화 방향 역행'이지만 비용이 1줄이고 마이그레이션이 없다. 반대로 동적 배경 일원화는 §G-5 정합이나 방 생성부 신규 배선(MEDIUM)이 필요하다. 해금 게이트 오프(§G-5) 자체는 이 행에 영향 없음 — 클레어에겐 unlock 장소 시드가 아예 없다(characters.yml:617-622 전부 주석).

**❓ 결정 필요**: ① 5행 전체의 처리 노선을 (a)enum 확장 / (b)기존 enum으로 시드 교정 / (c)동적 배경 일원화 대기 중 무엇으로 갈지 확정해 달라. (a)는 오늘 1줄로 끝나지만 §G-5가 지운다고 한 트랙을 키우는 셈이고, (c)는 §G-5 정합이지만 블록 D 착수 전까지 5명이 계속 '저택 현관'에서 시작한다.

---

### E-3.①.2. 클레어 baseLocations "CATHEDRAL"이 프롬프트 static 장소 목록·allowedLocations에 도달 불가 키로 실린다

**🔴 잔존** · P2 · ONE_LINE · YML  
`src/main/resources/application-characters.yml:616 (소비: Character.java:574-577 getBaseLocationSet → :560 getAllLocations → CharacterPromptAssembler.java:709 / Character.java:535 getAllowedLocations → ChatService.java:304)`

**근거**

application-characters.yml:615-616 `      baseOutfits: "DAILY"` / `      baseLocations: "CATHEDRAL"`. CharacterPromptAssembler.java:709 `String staticLocations = String.join(", ", character.getAllLocations());` → 프롬프트 `## 1. Static Locations (use \`location\` field in scenes): These are pre-defined locations with existing background images: [CATHEDRAL]`. 그런데 LLM이 지시대로 `location: "CATHEDRAL"`을 출력하면 ChatRoom.java:662-676 `try { Location parsedLoc = Location.valueOf(location); ... } catch (IllegalArgumentException ignored) {}` 에서 **조용히 버려진다**. 즉 프롬프트가 광고한 유일한 정적 장소가 영구히 반영 불가. 동시에 ChatService.java:304 `new java.util.ArrayList<>(character.getAllowedLocations(room.getStatusLevel(), isSecret))`로 FE에 `availableLocations: ["CATHEDRAL"]`이 나가지만 room.currentLocation은 ENTRANCE라 목록과 현재값이 불일치한다.

**수정안**

①.1과 같은 노선으로 동시 처리. (a)면 enum 추가로 자동 해소. (b)면 :616을 :599와 **같은 값으로** 교정(두 행이 어긋나면 ①.6/①.9와 같은 새 결함이 생긴다). (c)면 baseLocations를 비우고(`getBaseLocationSet()`이 defaultLocation으로 폴백 — Character.java:575-577) 동적 장소 트랙에 위임.

**제품 결정 연동**: §G-5 동적 배경 일원화 시 이 필드는 '정적 장소 풀'이라는 개념 자체가 사라져 삭제 대상이 된다. 즉 (c) 노선에서는 수정이 아니라 제거가 답. 해금 게이트 오프(§G-5)로 acquaintance/friend/loverUnlockLocations는 사문화되지만 baseLocations는 남는다(getAllLocations의 첫 항).

---

### E-3.①.3. 로제타 default-location "TERRACE"가 Location enum에 없어 V1 방이 '저택 현관'으로 시작한다

**🔴 잔존** · P2 · ONE_LINE · YML  
`src/main/resources/application-characters.yml:718`

**근거**

application-characters.yml:718 `      default-location: "TERRACE"` (639행 `- name: 로제타` 블록). Location.java에 TERRACE 없음 → ChatRoom.java:1131 `catch (Exception e) { return Location.ENTRANCE; }`. 로제타는 FANTASY_ACADEMY 소속(characters.yml:749 `world-id: "FANTASY_ACADEMY"`)인데 V1 SANDBOX 방은 중세저택 현관(`ENTRANCE`)에서 시작한다.

**수정안**

①.1과 동일 노선. (b) 교정 시 로제타(마법학원 영애)에 의미상 근접한 기존 enum 값은 `BALCONY`(테라스 대체) 또는 `GARDEN`. (a) 확장 시 `TERRACE,` 1값 추가로 ①.3/①.4/①.5/①.8 4행이 한꺼번에 해소된다.

**제품 결정 연동**: ①.1과 동일(§G-5). 추가로 로제타는 V2 STORY 히로인이기도 해서 ②.4~②.8과 같은 캐릭터다 — ① 노선 결정 시 V1/V2 장소 어휘를 통일할지도 함께 봐야 한다(현재 V1은 enum, V2는 world_locations 테이블로 완전 이원화).

---

### E-3.①.4. 로제타 baseLocations "TERRACE"가 프롬프트 static 목록에 도달 불가 키로 실린다

**🔴 잔존** · P2 · ONE_LINE · YML  
`src/main/resources/application-characters.yml:734`

**근거**

application-characters.yml:733-734 `      baseOutfits: "DAILY"` / `      baseLocations: "TERRACE"`. CharacterPromptAssembler.java:709 경유로 프롬프트 static 목록 `[TERRACE]`가 되고, LLM이 그 값을 쓰면 ChatRoom.java:674 `catch (IllegalArgumentException ignored)`로 폐기.

**수정안**

①.2와 동일 노선. :718과 반드시 같은 값으로 맞출 것.

**제품 결정 연동**: ①.2와 동일(§G-5).

---

### E-3.①.5. 강채린 default-location "TERRACE"가 Location enum에 없어 V1 방이 '저택 현관'으로 시작한다

**🔴 잔존** · P2 · ONE_LINE · YML  
`src/main/resources/application-characters.yml:832`

**근거**

application-characters.yml:832 `      default-location: "TERRACE"` (756행 `- name: 강채린` 블록). Location.java에 TERRACE 없음 → ENTRANCE 폴백. 강채린은 MODERN_KOREA 소속(characters.yml:862 `world-id: "MODERN_KOREA"`)의 '동네 톰보이 소꿉친구'(:779 role)인데 중세저택 현관에서 시작한다.

**수정안**

①.1과 동일 노선. 단 강채린은 ①.6(값 자체가 오배정)이 겹치므로, (a) enum 확장을 택하더라도 이 행은 **TERRACE로 남겨서는 안 된다** — ①.6의 교정을 먼저 적용한 뒤 노선을 적용할 것.

**제품 결정 연동**: ①.1과 동일(§G-5).

---

### E-3.①.6. 강채린 default-location이 로제타 값 복붙이라 자기 baseLocations(STREET)에도 없는 오배정

**🔴 잔존** · P2 · ONE_LINE · YML  
`src/main/resources/application-characters.yml:832 vs :847`

**근거**

강채린 블록: :832 `default-location: "TERRACE"` / :847 `baseLocations: "STREET"`. 로제타 블록: :718 `default-location: "TERRACE"` / :734 `baseLocations: "TERRACE"` — 값이 그대로 복붙된 형태. Character.java:574-577 `getBaseLocationSet()`은 baseLocations가 비어있지 않으므로 `{STREET}`를 반환하고, :535 `getAllowedLocations()`도 `{STREET}`. 따라서 default-location(TERRACE)은 **자기 자신의 허용 장소 집합에 포함되지 않는다**. enum 확장(①.5 (a) 노선)으로 TERRACE를 추가해도 이 의미 오류는 남는다 — 소꿉친구가 로제타의 마법학원 테라스에서 시작하게 된다.

**수정안**

application-charactersm... 아니라 application-characters.yml:832를 `default-location: "STREET"`로 교정해 :847 baseLocations와 일치시킨다(그 다음 STREET 자체의 enum 미등재는 ①.7 노선으로 처리). enum 확장 노선을 택하는 경우에도 이 교정이 **선행**되어야 한다.

**제품 결정 연동**: none — 어느 §G 처분과도 무관한 순수 시드 오타. 다만 ①.7(STREET도 유령 키)과 묶여 있어 이 교정만으로는 ENTRANCE 폴백이 해소되지 않는다.

---

### E-3.①.7. 강채린 baseLocations "STREET"가 Location enum에 없어 프롬프트 static 목록이 도달 불가 키뿐이다

**🔴 잔존** · P2 · ONE_LINE · YML  
`src/main/resources/application-characters.yml:847`

**근거**

application-characters.yml:846-847 `      baseOutfits: "DAILY"` / `      baseLocations: "STREET"`. Location.java에 STREET 없음(유사값으로 `DOWNTOWN`이 존재). CharacterPromptAssembler.java:709 → 프롬프트 `[STREET]`, LLM이 채택 시 ChatRoom.java:674에서 폐기. ChatService.java:304로 FE에 나가는 availableLocations도 `["STREET"]`.

**수정안**

(b) 교정 노선이 가장 자연스러운 행 — `STREET` → 기존 enum `DOWNTOWN`(번화가)으로 바꾸면 캐릭터 정체성 손실 없이 즉시 해소되고, `IllustrationPromptAssembler.LOCATION_PROMPTS`에도... (:111-123 확인 결과 DOWNTOWN 역시 맵에 없어 `"simple background"`이므로 E-2 동반 수정 대상). (a) 노선이면 `STREET,` 추가.

**제품 결정 연동**: §G-5 일원화 대상. 다만 이 행은 기존 enum(`DOWNTOWN`)으로 무손실 치환이 가능한 유일한 케이스라 노선 논쟁 없이 먼저 처리해도 안전하다.

---

### E-3.①.8. 에델 default-location "TERRACE"가 Location enum에 없어 V1 방이 '저택 현관'으로 시작한다

**🔴 잔존** · P2 · ONE_LINE · YML  
`src/main/resources/application-characters.yml:1056`

**근거**

application-characters.yml:1056 `      default-location: "TERRACE"` (981행 `- name: 에델` 블록). Location.java에 TERRACE 없음 → ChatRoom.java:1131 ENTRANCE 폴백. 에델은 FANTASY_ACADEMY 조교(characters.yml:1087 `world-id: "FANTASY_ACADEMY"`).

**수정안**

①.1과 동일 노선. ①.9 교정(→ LIBRARY)이 선행되어야 한다.

**제품 결정 연동**: ①.1과 동일(§G-5).

---

### E-3.①.9. 에델 default-location이 로제타 값 복붙이라 자기 baseLocations(LIBRARY)에도 없는 오배정

**🔴 잔존** · P2 · ONE_LINE · YML  
`src/main/resources/application-characters.yml:1056 vs :1072`

**근거**

에델 블록: :1056 `default-location: "TERRACE"` / :1072 `baseLocations: "LIBRARY"`. Character.java:535 `getAllowedLocations()`가 `{LIBRARY}`를 반환하므로 default-location(TERRACE)은 자기 허용 집합 밖. 로제타(:718 TERRACE)의 복붙 흔적. enum에 TERRACE를 추가해도 '차가운 쿨데레 조교'가 로제타의 테라스에서 시작하는 의미 오류는 남는다.

**수정안**

application-characters.yml:1056을 `default-location: "LIBRARY"`로 교정해 :1072와 일치시킨다(LIBRARY의 enum 미등재는 ①.10 노선으로). enum 확장 노선에서도 이 교정을 선행.

**제품 결정 연동**: none — 순수 시드 오타.

---

### E-3.①.10. 에델 baseLocations "LIBRARY"가 Location enum에 없어 프롬프트 static 목록이 도달 불가 키뿐이다

**🔴 잔존** · P2 · ONE_LINE · YML  
`src/main/resources/application-characters.yml:1072`

**근거**

application-characters.yml:1071-1072 `      baseOutfits: "DAILY"` / `      baseLocations: "LIBRARY"`. Location.java에 LIBRARY 없음(유사값 `STUDY`=서재 존재). 참고로 `IllustrationPromptAssembler.LOCATION_PROMPTS`(:123)에는 `LIBRARY` 키가 **있다** — 즉 일러 맵과 enum이 서로 다른 어휘를 쓰고 있다는 E-2의 증거이기도 하다.

**수정안**

(b) 교정 노선이면 `LIBRARY` → 기존 enum `STUDY`(서재)로 무손실 치환 가능. 단 이 경우 일러 프롬프트 맵의 `LIBRARY` 키(:123)는 계속 사문이 되므로 E-2와 어휘를 맞출지 결정 필요. (a) 노선이면 `LIBRARY,` 추가 — 이쪽이 일러 맵과 자동 정합된다.

**제품 결정 연동**: §G-5 일원화 대상. 이 행은 (a) enum 확장이 E-2 일러 맵과 자동으로 맞아떨어지는 유일한 케이스라, ① 노선 결정 시 판단 재료로 쓸 수 있다.

---

### E-3.①.11. 류설아 default-location "ABANDONED_SHRINE"이 Location enum에 없어 V1 방이 '저택 현관'으로 시작한다

**🔴 잔존** · P2 · ONE_LINE · YML  
`src/main/resources/application-characters.yml:1175`

**근거**

application-characters.yml:1175 `      default-location: "ABANDONED_SHRINE"` (1094행 `- name: 류설아` 블록). Location.java에 없음 → ENTRANCE 폴백. 류설아는 ORIENTAL_FANTASY 용녀(characters.yml:1206 `world-id: "ORIENTAL_FANTASY"`)로 '옛 사당'이 정체성인데 중세저택 현관에서 시작한다. 문자열 길이 16자 < `@Column(length=20)` — enum 확장 시 컬럼 변경 불필요.

**수정안**

①.1과 동일 노선. (b) 교정 시 기존 enum에 사당 계열 값이 전무하므로 `FOREST`가 최선의 근사(정체성 손실 큼) — 류설아는 ①에서 (a) enum 확장 또는 (c) 동적 배경이 사실상 강제되는 행이다. V2 쪽에는 이미 `ANCIENT_SHRINE`(application-v2.yml:114)이 선언돼 있어 어휘 통일 시 참고값이 된다.

**제품 결정 연동**: §G-5와 얽힘. 이 행이 ① 노선 결정의 결정타 — (b) 시드 교정만으로는 류설아의 정체성을 지킬 수 없으므로 '(b) 전면 교정' 노선은 사실상 배제된다.

**❓ 결정 필요**: 류설아의 '옛 사당'을 (a) V1 enum에 ABANDONED_SHRINE으로 추가할지, V2가 이미 쓰는 ANCIENT_SHRINE으로 어휘를 통일할지 — 후자면 V1/V2 장소 어휘 통합의 첫 케이스가 된다.

---

### E-3.①.12. 류설아 baseLocations "ABANDONED_SHRINE"이 프롬프트 static 목록에 도달 불가 키로 실린다

**🔴 잔존** · P2 · ONE_LINE · YML  
`src/main/resources/application-characters.yml:1191`

**근거**

application-characters.yml:1190-1191 `      baseOutfits: "DAILY"` / `      baseLocations: "ABANDONED_SHRINE"`. CharacterPromptAssembler.java:709 → 프롬프트 static 목록 `[ABANDONED_SHRINE]`, LLM 채택 시 ChatRoom.java:674에서 폐기.

**수정안**

①.11과 같은 값으로 동시 처리.

**제품 결정 연동**: ①.2와 동일(§G-5).

---

### E-3.①.13. parseLocationOrDefault가 유령 키를 로그 없이 삼켜 시드 오류가 영구히 은폐된다

**🔴 잔존** · P3 · SMALL · BE  
`src/main/java/com/spring/aichat/domain/chat/ChatRoom.java:1130-1132 (동종: :1134-1136 parseOutfitOrDefault, :1138-1140 parseBgmModeOrDefault, :662-676 updateSceneState)`

**근거**

ChatRoom.java:1130-1132 `private static Location parseLocationOrDefault(String v) {\n        try { return Location.valueOf(v); } catch (Exception e) { return Location.ENTRANCE; }\n    }` — 예외를 삼키고 **로그를 남기지 않는다**. 같은 파일 :673-675 `} catch (IllegalArgumentException ignored) {\n                // AI 생성 동적 장소 — enum 매핑 불가, 무시 }` 도 동일. 그래서 ①.1~①.12가 2026-07 이래 프로드에서 조용히 동작했고, 시드 오류를 알려주는 신호가 시스템 어디에도 없다.

**수정안**

ChatRoom은 엔티티라 로거 주입이 부담이면, 검증을 시드 단계로 올리는 편이 낫다(①.14 참조). 최소 조치로는 `parseLocationOrDefault`에 `catch (Exception e) { log.warn("⚠️ [SEED] unknown Location key '{}' → ENTRANCE fallback", v); return Location.ENTRANCE; }`(@Slf4j 부착) — 단 폴백 자체는 유지해야 한다(런타임에 LLM이 임의 문자열을 보내는 정상 경로가 있음, :673 주석 참조).

**제품 결정 연동**: none — 어느 §G 항목과도 무관. ① 노선을 무엇으로 정하든 재발 방지에 필요.

---

### E-3.①.14. CharacterSeeder가 default-location/baseLocations를 Location enum과 대조하지 않는다

**🔴 잔존** · P3 · SMALL · BE  
`src/main/java/com/spring/aichat/config/CharacterSeeder.java (Location 참조 0건) · src/main/java/com/spring/aichat/domain/character/Character.java:396-399`

**근거**

`grep -n "Location\|validate" CharacterSeeder.java` 결과 매칭은 주석 1건(:40)뿐 — 검증 코드가 없다. Character.java:396-399 `if (seed.defaultOutfit() != null) this.defaultOutfit = seed.defaultOutfit();\n        if (seed.defaultLocation() != null) this.defaultLocation = seed.defaultLocation();\n        if (seed.baseOutfits() != null) this.baseOutfits = seed.baseOutfits();\n        if (seed.baseLocations() != null) this.baseLocations = seed.baseLocations();` — 문자열을 그대로 DB에 적재(Character.java:166-167 `@Column(name = "base_locations", length = 500) private String baseLocations;`). application.yml:3 `update-existing: true`라 매 부팅마다 유령 키가 재적재된다.

**수정안**

CharacterSeeder에 부팅 검증 추가: 시드의 `defaultLocation`과 `baseLocations`(콤마 분리) 각 토큰을 `Location.valueOf`로 시도해 실패 시 `log.error("❌ [CHAR-SEED] {} unknown location key '{}'", slug, key)`. 부팅 실패(throw)로 할지 경고로 할지는 정책 — 프로드 부팅 리스크를 감안하면 경고 + 어드민 헬스체크 노출이 안전. `defaultOutfit`/`baseOutfits`(Outfit enum), `default-bgm`(BgmMode)도 같은 루프에 넣으면 ③과 E-2 일부까지 커버된다.

**제품 결정 연동**: none. 오히려 §G-5 일원화 이행기에 '어느 시드가 아직 enum 밖인가'를 자동으로 알려주는 도구가 되므로 노선과 무관하게 이득.

---

### E-3.①.15. application-charactersm.yml에도 동일 유령 키(claire CATHEDRAL, rosetta TERRACE) — 시크릿 시드 활성화 시 부활

**🔴 잔존** · P3 · ONE_LINE · YML  
`src/main/resources/application-charactersm.yml:399, 411 (클레어) · :476, 488 (로제타) — 현재 미활성 프로필`

**근거**

application-charactersm.yml:399 `      default-location: "CATHEDRAL"` / :411 `      baseLocations: "CATHEDRAL"` (341행 `- name: 클레어`), :476 `      default-location: "TERRACE"` / :488 `      baseLocations: "TERRACE"` (418행 `- name: 로제타`). 같은 파일 :3 `update-existing: true`. 활성화 여부: application.yml:10-11 `spring:\n  profiles:\n    active: local, characters, worlds, v2` — `charactersm`가 없어 **현재는 로드되지 않는다**(prod yml에도 profiles/include 지시 없음, `grep` 0건).

**수정안**

①.1/①.3에서 확정한 노선을 charactersm.yml:399/411/476/488에도 동일하게 적용해 두 파일을 동기화한다. 활성화 전에 처리해야 재발을 막는다. (참고: charactersm.yml에는 아이리 BEDROOM/BATHROOM/STUDY/GARDEN, 연화 FOREST, 태리 CLUB_ROOM, 루나 CONVENIENCE_STORE 등 나머지는 전부 유효 enum이라 이 4행이 전부다.)

**제품 결정 연동**: docs/16 §E '시크릿 페르소나 시드 — personalitySecret/toneSecret 엔드투엔드 완비, charactersm.yml에 값만 공란 → YAML 저작만으로 즉효'와 §G '유지 확정: charactersm.yml 성인 변형 시드는 시크릿 개편 때 재론'이 이 파일의 활성화를 예고한다. 시크릿이 핵심 BM으로 승격(docs/16 §A)된 만큼 활성화 시점에 이 4행이 그대로 살아난다.

**❓ 결정 필요**: charactersm.yml을 실제로 활성화할 계획이라면, 그 시점에 characters.yml과의 장소·복장 시드 동기화 규칙(복붙 유지 vs 시크릿 전용 오버라이드만 기재)을 먼저 정해야 한다 — 지금은 두 파일이 서로 다른 값으로 드리프트하는 중이다.

---

### E-3.②.1. V2 루틴 연화 AFTERNOON 50% → 미선언 장소 MOONLIT_FOREST

**🔴 잔존** · P2 · ONE_LINE · YML  
`src/main/resources/application-v2.yml:462`

**근거**

application-v2.yml:462 `      - { character-slug: yeonhwa, time-of-day: AFTERNOON, location-key: MOONLIT_FOREST, probability: 50, notes: "자기 영역 거닐기" }`. ORIENTAL_FANTASY 선언 장소 8종(application-v2.yml:99-154) = DEEP_FOREST / LAKE_PAVILION / ANCIENT_SHRINE / TEAHOUSE / BAMBOO_PATH / SHRINE_GATE / MOUNTAIN_CABIN / HIDDEN_GROVE — MOONLIT_FOREST 없음. 전 파일 `grep MOONLIT_FOREST` 결과 = 462/466/468 세 루틴 행뿐(선언 0건). 같은 시간대 경쟁 후보는 HIDDEN_GROVE 30 / LAKE_PAVILION 20 → 가중치 50/100 = **오후 전환 시 50% 확률로 유령 위치**.

**수정안**

application-v2.yml:462의 `location-key: MOONLIT_FOREST` → `DEEP_FOREST`(ORIENTAL_FANTASY 선언값, :100 `display-name: 깊은 숲` / description '구미호의 영역'과 notes '자기 영역 거닐기'가 정확히 일치). 3행 일괄 치환이 정답.

**제품 결정 연동**: none — 블록 D/§G 어느 항목과도 무관한 순수 시드 오타. V2 STORY는 §G-2에서 '완전 대체' 주체로 살아남는 트랙이므로 게이트 오프 대상이 아니다.

---

### E-3.②.2. V2 루틴 연화 EVENING 35% → 미선언 장소 MOONLIT_FOREST

**🔴 잔존** · P2 · ONE_LINE · YML  
`src/main/resources/application-v2.yml:466`

**근거**

application-v2.yml:466 `      - { character-slug: yeonhwa, time-of-day: EVENING,   location-key: MOONLIT_FOREST, probability: 35, notes: "밤마실 채비" }`. 경쟁 후보 LAKE_PAVILION 45 / SHRINE_GATE 20 → 35/100.

**수정안**

`MOONLIT_FOREST` → `DEEP_FOREST`.

---

### E-3.②.3. V2 루틴 연화 NIGHT 55% → 미선언 장소 MOONLIT_FOREST (11행 중 최고 확률)

**🔴 잔존** · P2 · ONE_LINE · YML  
`src/main/resources/application-v2.yml:468`

**근거**

application-v2.yml:468 `      - { character-slug: yeonhwa, time-of-day: NIGHT,     location-key: MOONLIT_FOREST, probability: 55, notes: "가장 생기 도는 시간" }`. 경쟁 후보 LAKE_PAVILION 30 / TEAHOUSE 15 → **55/100으로 11행 중 최고**. 캐릭터 컨셉 주석(:459) '야행성. 밤의 달빛 숲에서 가장 생기가 돈다'와 정면 충돌 — 연화의 하이라이트 시간대가 통째로 유령 위치가 된다.

**수정안**

`MOONLIT_FOREST` → `DEEP_FOREST`. (컨셉상 '달빛 숲'을 살리고 싶다면 대안으로 ORIENTAL_FANTASY에 MOONLIT_FOREST 장소를 **신규 선언**하는 길도 있다 — application-v2.yml:34~ locations 블록에 world-id: ORIENTAL_FANTASY 항목 추가 + display-order 9. 단 배경 에셋 `/backgrounds/locations/oriental_fantasy/moonlit_forest_{day|sunset|night}.png` 3장이 동반 필요하므로 비용이 다르다.)

**제품 결정 연동**: none — 다만 '유령 키 3개를 DEEP_FOREST로 접을 것인가 vs MOONLIT_FOREST를 정식 장소로 승격할 것인가'는 에셋 비용이 걸린 소규모 제품 판단이다.

**❓ 결정 필요**: 연화의 '달빛 숲'을 DEEP_FOREST로 흡수할지, ORIENTAL_FANTASY 9번째 장소로 정식 신설할지(배경 에셋 3장 필요) — 캐릭터 컨셉 문구가 '달빛 숲'을 명시하므로 종원 취향 판단.

---

### E-3.②.4. V2 루틴 로제타 MORNING 20% → 미선언 장소 GARDEN_OF_MIRRORS

**🔴 잔존** · P2 · ONE_LINE · YML  
`src/main/resources/application-v2.yml:542`

**근거**

application-v2.yml:542 `      - { character-slug: rosetta, time-of-day: MORNING,   location-key: GARDEN_OF_MIRRORS, probability: 20, notes: "환영 장난 구상" }`. FANTASY_ACADEMY 선언 장소 8종 = GREAT_HALL / LIBRARY_TOWER / ALCHEMY_LAB / ENCHANTED_FOREST / DORMITORY / ASTRONOMY_TOWER / ARENA / **GARDEN_OF_ACADEMY** — GARDEN_OF_MIRRORS 없음. 전 파일 grep 결과 선언 0건, 루틴 8행(로제타 5·시에라 3)에만 등장.

**수정안**

application-v2.yml:542의 `GARDEN_OF_MIRRORS` → `GARDEN_OF_ACADEMY`. 로제타 5행(542/544/546/551/554) + 시에라 3행(562/565/567) 일괄 치환.

**제품 결정 연동**: none — 순수 시드 오타. 단 캐릭터 컨셉 주석(:539) '거울의 정원에서 환영 장난을 꾸민다'가 GARDEN_OF_MIRRORS를 전제로 쓰여 있어, 치환 시 이 주석과 시에라 :565 notes '거울 정원에서 멍하니'도 함께 손봐야 문서-데이터 정합이 유지된다.

---

### E-3.②.5. V2 루틴 로제타 NOON 30% → 미선언 장소 GARDEN_OF_MIRRORS

**🔴 잔존** · P2 · ONE_LINE · YML  
`src/main/resources/application-v2.yml:544`

**근거**

application-v2.yml:544 `      - { character-slug: rosetta, time-of-day: NOON,      location-key: GARDEN_OF_MIRRORS, probability: 30, notes: "장난 마법 시연" }`. 경쟁 후보 GREAT_HALL 50 / ARENA 20.

**수정안**

`GARDEN_OF_MIRRORS` → `GARDEN_OF_ACADEMY`.

---

### E-3.②.6. V2 루틴 로제타 AFTERNOON 45% → 미선언 장소 GARDEN_OF_MIRRORS

**🔴 잔존** · P2 · ONE_LINE · YML  
`src/main/resources/application-v2.yml:546`

**근거**

application-v2.yml:546 `      - { character-slug: rosetta, time-of-day: AFTERNOON, location-key: GARDEN_OF_MIRRORS, probability: 45, notes: "환영 마법 연습" }`. 경쟁 후보 ARENA 30 / GREAT_HALL 25 → 45/100으로 로제타 최고 확률 행.

**수정안**

`GARDEN_OF_MIRRORS` → `GARDEN_OF_ACADEMY`.

---

### E-3.②.7. V2 루틴 로제타 EVENING 25% → 미선언 장소 GARDEN_OF_MIRRORS

**🔴 잔존** · P2 · ONE_LINE · YML  
`src/main/resources/application-v2.yml:551`

**근거**

application-v2.yml:551 `      - { character-slug: rosetta, time-of-day: EVENING,   location-key: GARDEN_OF_MIRRORS, probability: 25, notes: "노을 속 환영 연출" }`. 경쟁 후보 ARENA 40 / GREAT_HALL 35.

**수정안**

`GARDEN_OF_MIRRORS` → `GARDEN_OF_ACADEMY`.

---

### E-3.②.8. V2 루틴 로제타 NIGHT 20% → 미선언 장소 GARDEN_OF_MIRRORS

**🔴 잔존** · P2 · ONE_LINE · YML  
`src/main/resources/application-v2.yml:554`

**근거**

application-v2.yml:554 `      - { character-slug: rosetta, time-of-day: NIGHT,     location-key: GARDEN_OF_MIRRORS, probability: 20, notes: "한밤의 장난 준비" }`. 경쟁 후보 ASTRONOMY_TOWER 40 / DORMITORY 40.

**수정안**

`GARDEN_OF_MIRRORS` → `GARDEN_OF_ACADEMY`.

---

### E-3.②.9. V2 루틴 시에라 NOON 30% → 미선언 장소 GARDEN_OF_MIRRORS

**🔴 잔존** · P2 · ONE_LINE · YML  
`src/main/resources/application-v2.yml:562`

**근거**

application-v2.yml:562 `      - { character-slug: sierra, time-of-day: NOON,      location-key: GARDEN_OF_MIRRORS, probability: 30, notes: "꽃 구경" }`. 경쟁 후보 ENCHANTED_FOREST 45 / GREAT_HALL 25.

**수정안**

`GARDEN_OF_MIRRORS` → `GARDEN_OF_ACADEMY`.

---

### E-3.②.10. V2 루틴 시에라 AFTERNOON 30% → 미선언 장소 GARDEN_OF_MIRRORS

**🔴 잔존** · P2 · ONE_LINE · YML  
`src/main/resources/application-v2.yml:565`

**근거**

application-v2.yml:565 `      - { character-slug: sierra, time-of-day: AFTERNOON, location-key: GARDEN_OF_MIRRORS, probability: 30, notes: "거울 정원에서 멍하니" }`. 경쟁 후보 ENCHANTED_FOREST 50 / DORMITORY 20.

**수정안**

`GARDEN_OF_MIRRORS` → `GARDEN_OF_ACADEMY`, 그리고 notes의 '거울 정원'도 '아카데미 정원'으로(치환 노선 채택 시).

---

### E-3.②.11. V2 루틴 시에라 EVENING 40% → 미선언 장소 GARDEN_OF_MIRRORS

**🔴 잔존** · P2 · ONE_LINE · YML  
`src/main/resources/application-v2.yml:567`

**근거**

application-v2.yml:567 `      - { character-slug: sierra, time-of-day: EVENING,   location-key: GARDEN_OF_MIRRORS, probability: 40, notes: "해질녘 꽃밭" }`. 경쟁 후보 ENCHANTED_FOREST 35 / DORMITORY 25 → 40/100으로 시에라 최고 확률 행.

**수정안**

`GARDEN_OF_MIRRORS` → `GARDEN_OF_ACADEMY`.

---

### E-3.②.12. CharacterRoutineSeeder가 location-key를 WorldLocation 선언과 대조하지 않아 유령 키를 그대로 적재한다

**🔴 잔존** · P2 · SMALL · BE  
`src/main/java/com/spring/aichat/config/CharacterRoutineSeeder.java:100-113`

**근거**

CharacterRoutineSeeder.java:100-113 — timeOfDay는 `DayPart.valueOf`로 검증(:92)하고 빈 문자열도 거르지만(:100 `if (seed.locationKey() == null || seed.locationKey().isBlank())`), **선언된 WorldLocation과의 대조는 없다**: `CharacterRoutine routine = CharacterRoutine.create(\n                characterId, timeOfDay, seed.locationKey().trim(), probability, notes);\n            routineRepository.save(routine);`. 시더 실행 순서는 World(0) → Location(1) → … → Routine(4)(:26, :40 주석)라 **검증에 필요한 WorldLocation 데이터는 이미 DB에 있다** — 대조가 기술적으로 가능한데 안 하고 있는 상태. `update-existing`과 무관하게 :74-76에서 매 부팅 전삭제 후 재삽입하므로 유령 키가 영구 재생산된다.

**수정안**

CharacterRoutineSeeder.seedAllRoutines()에 검증 추가. ①단계에서 `characterRepository.findBySlug(slug)`로 이미 Character를 얻으므로 그 `worldId`로 `WorldLocationRepository`에서 선언 키 집합을 미리 로드(월드당 1회) → :110 저장 직전에 `if (!declaredKeys.get(worldId).contains(key)) { log.error("❌ [ROUTINE-SEED] unknown location_key '{}' for {} ({}) — skip", key, slug, worldId); skipped++; continue; }`. **skip이 정답**이다(유령 위치에 배치하느니 그 후보를 빼는 게 가중치 정규화상 안전 — WorldRoutingService.java:246 `totalWeight`가 남은 후보로 재정규화된다). CharacterRoutineSeeder에 `WorldLocationRepository` 주입 1개 추가.

**제품 결정 연동**: none — V2 STORY는 §G-2에서 살아남는 트랙이라 게이트 오프 영향 없음. UGC 월드 루틴에도 같은 검증이 필요한지는 확인 필요(현재 시드 루틴은 공식 캐릭터 전용).

---

### E-3.②.13. 유령 키를 심는 경로가 moveTo 외에 initializePresences(스토리 첫 진입)에도 있다

**🔴 잔존** · P2 · SMALL · BE  
`src/main/java/com/spring/aichat/service/story/WorldRoutingService.java:301 (docs/13이 지목한 경로는 :255 moveTo)`

**근거**

docs/13이 지목한 경로: WorldRoutingService.java:250-255 `for (CharacterRoutine r : candidates) {\n                acc += r.getProbability();\n                if (roll < acc) {\n                    p.moveTo(r.getLocationKey());` — 맞다. **그러나 누락된 두 번째 경로**: :281-303 `initializePresences(ChatRoom room, List<Long> heroineCharIds, DayPart startDayPart)`가 `String pick = userLocationKey; for (CharacterRoutine r : candidates) { acc += r.getProbability(); if (roll < acc) { pick = r.getLocationKey(); break; } } initialLocation = pick;` → :301 `CharacterPresence p = CharacterPresence.create(room, charId, initialLocation);`. 호출처 StoryV2Service.java:660 `// 2. CharacterPresence 위치 초기화 (루틴 기반)`. CharacterPresence 자체도 무검증: CharacterPresence.java:93-98 `public void moveTo(String newLocationKey) { if (newLocationKey == null || newLocationKey.isBlank()) return; ... this.currentLocationKey = newLocationKey; }`. 즉 **스토리 방을 만든 첫 순간부터** 연화가 MOONLIT_FOREST에 놓일 수 있다(NIGHT 시작 시 55%).

**수정안**

②.12(시드 단계 차단)로 근본 해결되지만, 방어 심층화를 하려면 `WorldRoutingService`에 선언 키 화이트리스트 검사를 두는 편이 낫다 — `initializePresences`(:295 pick 확정 직후)와 `recomputePresencesFromRoutine`(:255 moveTo 직전) 양쪽에서 `worldLocationRepository.existsByWorldIdAndLocationKey(...)` 실패 시 **해당 후보를 건너뛰고 다음 후보로**(initialize는 userLocationKey 폴백이 이미 있으므로 그대로 사용). `applyCharacterMovements`(:191, :196)에도 같은 검사가 필요하다 — 여긴 LLM 디렉터가 만든 키가 들어오므로 시드 수정만으로는 못 막는다.

**제품 결정 연동**: none. 참고로 §G-14 'TimeOfDay(3값)/DayPart(5단계) 이중 체계 통일'이 이 코드(DayPart 기반 루틴)를 건드리게 되므로, 그 작업과 같은 커밋에 묶으면 회귀 테스트를 한 번만 돌려도 된다.

---

### E-3.②.14. 유령 위치 키가 디렉터 프롬프트와 FE UI에 raw 영문 토큰("MOONLIT_FOREST")으로 그대로 노출된다

**🔴 잔존** · P2 · SMALL · BE  
`src/main/java/com/spring/aichat/service/prompt/StoryDirectorPromptAssemblerV2.java:829-837 · src/main/java/com/spring/aichat/service/story/StoryV2Service.java:855-863 · C://Users//zapza//Desktop//LucidChat-Front//LucidChat-Front//src//components//story-v2//StoryV2CharacterPanel.jsx:87`

**근거**

StoryDirectorPromptAssemblerV2.java:829-837 `private String resolveLocationDisplay(String locationKey, List<...LocationView> worldLocations) {\n        if (locationKey == null) return "(위치 미상)";\n        return worldLocations.stream().filter(l -> l.key().equals(locationKey))...\n            .orElse(locationKey);  // 동적 임시 장소 등 — key 자체를 표시\n    }` → buildSection6Offscreen(:481)에서 `- **연화** (ID: 2, MOONLIT_FOREST): 관계 …` 형태로 **한국어 프롬프트 안에 영문 enum 토큰**이 박히고, 디렉터는 월드 맵(Section 3)에 없는 장소를 지시받는다. FE도 동일: StoryV2Service.java:855-863 `Map<String,String> locationKeyToDisplay = worldView.locations()...; locationKeyToDisplay.getOrDefault(p.getCurrentLocationKey(), p.getCurrentLocationKey())` → StoryV2CharacterPanel.jsx:87 `<MapPin size={10} /> {presence.currentLocationDisplayName}` → 유저에게 `MOONLIT_FOREST`가 그대로 보인다. 추가로 유령 키에 있는 히로인은 `p.isAt(userLocationKey)`가 영구 false라 **유저와 절대 같은 공간이 될 수 없고**(WorldRoutingService.java:80-91 라우팅에서 항상 제외), 오프스크린 취급되어 직접 대사 출력이 금지된다(:492 프롬프트 규약).

**수정안**

②.12/②.13으로 유령 키 유입을 막는 것이 1차. 2차 방어로 `resolveLocationDisplay`의 orElse를 '동적 임시 장소'와 '미선언 유령 키'로 구분할 수 없으므로(주석이 그 한계를 자인한다), 최소한 orElse 진입 시 `log.warn`을 남겨 운영 중 탐지가 되게 한다. FE 쪽은 표시명 폴백이 raw 키라 SCREAMING_SNAKE가 그대로 보이므로 `currentLocationDisplayName`이 `/^[A-Z0-9_]+$/`이면 '(알 수 없는 장소)'로 치환하는 가드를 StoryV2CharacterPanel.jsx:87에 두는 것을 권장.

**제품 결정 연동**: none — V2 STORY는 유지 트랙. 다만 §G-12 '인트로 문 영상 → 경량 연출 교체' 같은 블록 A UI 정리와 같은 화면(StoryV2CharacterPanel)을 만질 수 있어 FE 작업 묶음 조정 여지 있음.

---

### E-3.③.1. application-worlds.yml FANTASY_ACADEMY default-bgm: MYSTERIOUS가 BgmMode에 없어 DAILY로 폴백

**🔴 잔존** · P3 · ONE_LINE · YML  
`src/main/resources/application-worlds.yml:49`

**근거**

application-worlds.yml:42-53 `    - id: FANTASY_ACADEMY` … `:49       default-bgm: MYSTERIOUS`. BgmMode.java:11-20 전체 값 = DAILY / DAILY_CALM / DAILY_BRIGHT / ROMANTIC / EXCITING / TOUCHING / TENSE / EROTIC — MYSTERIOUS 없음. 소비: ChatRoom.java:440 `r.currentBgmMode = parseBgmModeOrDefault(world.getDefaultBgm());`(createStoryV2 방 생성) 및 :1108 `if (world != null) { this.currentBgmMode = parseBgmModeOrDefault(world.getDefaultBgm()); }`(resetStoryFields) → :1138-1140 `try { return BgmMode.valueOf(v); } catch (Exception e) { return BgmMode.DAILY; }`. docs/13의 '교정됐다'는 대조군도 확인됨 — application-v2.yml:219 `      #  ※ default-bgm: 무효값 MYSTERIOUS → 유효값으로 교정`이고 FANTASY_ACADEMY 8개 장소의 default-bgm은 전부 유효값. 전 파일 default-bgm 값 감사 결과 무효값은 worlds.yml:49 **이 1건뿐**(v2.yml은 DAILY13/TOUCHING15/TENSE2/ROMANTIC1/EXCITING1 전부 유효).

**수정안**

application-worlds.yml:49를 `default-bgm: TOUCHING`으로 교정 권장 — FANTASY_ACADEMY 8개 장소 중 신비 계열(LIBRARY_TOWER/ASTRONOMY_TOWER 등)이 이미 TOUCHING을 쓰고, MEDIEVAL/ORIENTAL 두 판타지 월드도 TOUCHING이라 톤이 일관된다. V2 방의 시작 BGM만 정하는 값이므로 FE 에셋 추가 불필요(AudioEngine.jsx:53-56이 worldId 기반 `/sounds/worlds/fantasy_academy/bgm_touching.mp3`… 실제로는 :26 `TOUCHING: "/sounds/bgm_touching.mp3"` 공통 트랙으로 해상 — 에셋 존재 여부는 CDN 확인 필요).

**제품 결정 연동**: none — 블록 D/§G 어느 항목과도 무관. §G-14(TimeOfDay/DayPart 이중체계 통일)와 §G '✅ 유지 확정: 동적 BGM(BgmMode)'에 따라 BgmMode는 살아남는 트랙이므로 고쳐야 하는 값이 맞다.

**❓ 결정 필요**: '신비로운' 톤을 살리고 싶다면 BgmMode에 MYSTERIOUS를 정식 추가하는 길도 있으나 BGM 에셋 1종 제작이 동반된다 — TOUCHING 치환으로 끝낼지, MYSTERIOUS를 정식 값으로 승격할지.

---

### E-3.③.2. BGM 무효값 폴백이 침묵이고, 폴백 목적지 DAILY가 V2에서는 '레거시 V1 전용' 값이다

**🔴 잔존** · P3 · SMALL · BE  
`src/main/java/com/spring/aichat/domain/chat/ChatRoom.java:1138-1140 · src/main/java/com/spring/aichat/domain/enums/BgmMode.java:12-14 · src/main/java/com/spring/aichat/config/WorldSeeder.java:88`

**근거**

ChatRoom.java:1138-1140 `private static BgmMode parseBgmModeOrDefault(String v) {\n        try { return BgmMode.valueOf(v); } catch (Exception e) { return BgmMode.DAILY; }\n    }` — 로그 없음. WorldSeeder.java:88 `String defaultBgm = nullToEmpty(seed.defaultBgm());` 후 :102/:117로 그대로 저장 — 검증 없음(World.java:70 `private String defaultBgm;`는 String 컬럼). 그리고 폴백 목적지가 문제: BgmMode.java:12-14 `DAILY,        // 일상적인 분위기 (레거시 — V1 전용. V2는 CALM/BRIGHT 이원화)\n    DAILY_CALM,   // [V2] 잔잔하고 조용한 일상 …\n    DAILY_BRIGHT, // [V2] 밝고 활기찬 일상 …` — 즉 **V2 STORY 방(ChatRoom.java:440)이 명시적으로 'V1 전용 레거시'로 표시된 값에 착지한다**. FE도 그 값을 그대로 따라간다: AudioEngine.jsx:49 `// DAILY(레거시)는 기존 파일(bgm_daily.mp3) 그대로`.

**수정안**

① `WorldSeeder`(및 `WorldLocationSeeder`)에 BgmMode 검증 추가 — ①.14의 CharacterSeeder 검증과 같은 커밋으로 묶는 것이 효율적. 무효값이면 `log.error`로 노출하고 유효 폴백으로 저장. ② `parseBgmModeOrDefault`의 V2 폴백 목적지를 재검토 — createStoryV2(:440)와 resetStoryFields(:1108)에서만 `DAILY_CALM`을 쓰도록 오버로드를 두거나, 호출부에서 명시하는 편이 안전(V1 SANDBOX 경로 :364/:378/:794의 `BgmMode.DAILY` 하드코딩은 그대로 유지).

**제품 결정 연동**: §G-14 'TimeOfDay(3값)/DayPart(5단계) 이중 체계 통일'과 같은 성격의 'V1/V2 이중 어휘' 문제다 — BgmMode의 DAILY vs DAILY_CALM/BRIGHT 이원화도 통일 대상에 넣을지 종원 판단이 필요할 수 있다. §G '✅ 유지 확정: 동적 BGM(BgmMode)'이므로 삭제 대상은 아니다.

---

### E-3.④.1. 강채린 ending-role-desc가 로제타 문구 복붙 — 엔딩이 '메스가키 귀족 마법사' 페르소나로 생성된다

**🔴 잔존** · P2 · ONE_LINE · YML  
`src/main/resources/application-characters.yml:837 (소비: src/main/java/com/spring/aichat/service/prompt/EndingPromptAssembler.java:162, 246)`

**근거**

application-characters.yml:837 `      ending-role-desc: "a mesugaki noble mage whose magic shield was finally broken by sincerity"` — 로제타의 :723과 **문자 단위로 동일**. 강채린은 MODERN_KOREA '동네 톰보이 소꿉친구'(:779). 소비: EndingPromptAssembler.java:162 `String endingRoleDesc = character.getEffectiveEndingRoleDesc();` → 템플릿 `You are %s, %s. This is the FINAL scene of your love story with %s.`에 :225 `.formatted(characterName, endingRoleDesc, userNickname, ...)` → 실제 프롬프트 = `You are 강채린, a mesugaki noble mage whose magic shield was finally broken by sincerity.` 배드엔딩(:246)도 동일. **폴백이 아니라 주 경로**라 100% 발현된다.

**수정안**

블록 D에서 '엔딩 시드 유지' 결정 시: application-characters.yml:837을 강채린 정체성에 맞게 작성(예: `"a bright tomboy childhood friend who finally admitted what she always felt"`). '삭제' 결정 시: :837 행 삭제 + Character.java:195 `endingRoleDesc` 필드·:407 applySeed·:490 getEffectiveEndingRoleDesc·EndingPromptAssembler의 `%s` 슬롯까지 세트 제거(getEffective의 기본값 `"a character in a visual novel"`을 상수로 인라인하면 프롬프트는 살아남는다).

**제품 결정 연동**: **블록 D와 정면 충돌.** docs/14 §C-#6은 '엔딩=자유·스토리만 게이트 오프(코드 보존·극장 유지)'이고 §G-4 🔴삭제 목록에 '엔딩 시드 필드(엔딩 오프로 사문)'가 있다. `endingRoleDesc`는 **EndingPromptAssembler(=V1 자유/스토리 엔딩) 전용**이고 TheaterEndingService는 이 필드를 쓰지 않는다(grep 확인: getEffectiveEndingRoleDesc 호출처는 EndingPromptAssembler.java:162, :246 두 곳뿐). 따라서 **role-desc 4건에 한해서는 §G-4 '삭제'가 실제로 성립**한다. 다만 블록 D는 미착수 상태(application.yml에 엔딩 게이트 플래그 없음, EndingController.java:30 `/api/v1/ending/rooms/{roomId}`와 ChatPage.jsx:1293 generateEnding 모두 라이브)라 **지금은 살아있는 결함**이다.

**❓ 결정 필요**: 블록 D 엔딩 게이트 오프를 언제 넣을지가 이 4건의 운명을 정한다. 게이트가 이번 스프린트에 들어가면 role-desc 4건은 '채우지 말고 §G-4대로 필드 삭제'가 맞고, 게이트가 뒤로 밀리면 '지금 4줄 채우기'(각 1줄)가 훨씬 싸다. 어느 쪽인지 확정 필요.

---

### E-3.④.2. 시에라 ending-role-desc가 로제타 문구 복붙 — 엔딩이 '메스가키 귀족 마법사' 페르소나로 생성된다

**🔴 잔존** · P2 · ONE_LINE · YML  
`src/main/resources/application-characters.yml:948`

**근거**

application-characters.yml:948 `      ending-role-desc: "a mesugaki noble mage whose magic shield was finally broken by sincerity"` — 로제타 :723과 동일. 시에라는 869행 블록, FANTASY_ACADEMY '잠꾸러기 힐링계 후배'(v2.yml:557 컨셉 주석). EndingPromptAssembler.java:162/246 주 경로 → `You are 시에라, a mesugaki noble mage…`.

**수정안**

④.1과 동일 처분. 유지 노선이면 예: `"a sleepy, gentle underclassman who quietly became your safest place"`.

**제품 결정 연동**: ④.1과 동일 — §G-4 '엔딩 시드 필드 삭제'가 role-desc에는 성립. 블록 D 미착수라 현재 라이브.

---

### E-3.④.3. 에델 ending-role-desc가 로제타 문구 복붙 — 엔딩이 '메스가키 귀족 마법사' 페르소나로 생성된다

**🔴 잔존** · P2 · ONE_LINE · YML  
`src/main/resources/application-characters.yml:1061`

**근거**

application-characters.yml:1061 `      ending-role-desc: "a mesugaki noble mage whose magic shield was finally broken by sincerity"` — 로제타 :723과 동일. 에델은 981행 블록, FANTASY_ACADEMY '차가운 쿨데레 조교'(v2.yml:569 컨셉 주석). EndingPromptAssembler.java:162/246 주 경로.

**수정안**

④.1과 동일 처분. 유지 노선이면 예: `"an ice-cold academy assistant whose composure finally cracked for one person"`.

**제품 결정 연동**: ④.1과 동일.

---

### E-3.④.4. 류설아 ending-role-desc가 빈 문자열이라 엔딩 프롬프트가 "You are 류설아, ."로 파손된다

**🔴 잔존** · P2 · ONE_LINE · YML  
`src/main/resources/application-characters.yml:1180 (소비: EndingPromptAssembler.java:162 + :225 formatted, :246)`

**근거**

application-characters.yml:1180 `      ending-role-desc: ""`. Character.java:490-492 `public String getEffectiveEndingRoleDesc() {\n        return endingRoleDesc != null ? endingRoleDesc : "a character in a visual novel";\n    }` — `""`는 non-null이라 **폴백이 발동하지 않고 빈 문자열이 그대로 반환**된다. EndingPromptAssembler.java:163-165 템플릿 `You are %s, %s. This is the FINAL scene…`에 :225 `.formatted(characterName, endingRoleDesc, ...)` → 실제 문자열 `You are 류설아, . This is the FINAL scene of your love story with …`. 배드엔딩(:246-252)도 동일.

**수정안**

④.1과 동일 처분. 유지 노선이면 :1180에 류설아(ORIENTAL_FANTASY 용녀, characters.yml:1094 블록) 문구 작성. 삭제 노선이라도 ④.10(getEffective의 `!= null` → `isBlank()`)은 별도로 고쳐야 같은 클래스의 다른 필드가 안 터진다.

**제품 결정 연동**: ④.1과 동일. 단 이 행은 '빈 문자열'이라 §G-4 '삭제'를 택하면 자동 해소되지만, 삭제 전까지는 문법이 깨진 프롬프트가 나간다는 점에서 복붙 3건(④.1~3)보다 증상이 명확하다.

---

### E-3.④.5. 강채린 엔딩 인용구 2필드가 빈 문자열 — 극장 엔딩 폴백에서 인용구 없는 엔딩 씬이 나간다

**🔴 잔존** · P2 · ONE_LINE · YML  
`src/main/resources/application-characters.yml:838-839 (소비: EndingService.java:167-171 · TheaterEndingService.java:110-112, :269-273)`

**근거**

application-characters.yml:838-839 `      ending-quote-happy: ""` / `      ending-quote-bad: ""`. Character.java:494-501 `public String getEffectiveEndingQuoteHappy() {\n        return endingQuoteHappy != null ? endingQuoteHappy\n            : "당신과의 모든 순간이, " + name + "에겐 기적이었어요.";\n    }` — `""`가 non-null이라 폴백 미발동, 빈 문자열 반환. 소비 2곳: (1) EndingService.java:167-171 `String characterQuote = scenesWrapper.characterQuote() != null ? scenesWrapper.characterQuote() : (endingType == EndingType.HAPPY ? character.getEffectiveEndingQuoteHappy() : character.getEffectiveEndingQuoteBad());` — V1 엔딩 LLM이 quote를 안 주면 빈 인용구. (2) **TheaterEndingService.java:108-112** `catch (Exception e) { log.error("🎭 [ENDING] LLM generation failed, using fallback: {}", …); endingScenes = buildFallbackScenes(mainHeroine, endingType); closingQuote = endingType.isHappy() ? mainHeroine.getEffectiveEndingQuoteHappy() : mainHeroine.getEffectiveEndingQuoteBad(); }` 및 :269-273 `buildFallbackScenes`의 `String quote = type.isHappy() ? heroine.getEffectiveEndingQuoteHappy() : heroine.getEffectiveEndingQuoteBad();` → `new SceneResponse(narration, quote, …)`. 강채린은 characters.yml:863 `theater-available: true`, 극장은 application.yml:173 `theater-enabled: ${UGC_THEATER_ENABLED:true}`로 활성.

**수정안**

application-characters.yml:838-839에 강채린 대사 2줄 작성(다른 캐릭터의 :141-142, :364-365 형식 참조 — 해피는 고백조, 배드는 회한조). **삭제 노선은 이 필드에 적용하면 안 된다**(productDecisionRisk 참조). 병행하여 ④.10 수정으로 빈 문자열이 들어와도 이름 기반 기본 인용구로 폴백되게 할 것.

**제품 결정 연동**: **§G-4와 §C-#6이 여기서 충돌한다.** §G-4 🔴삭제는 '엔딩 시드 필드(엔딩 오프로 사문)'라고 적었지만, §C-#6은 '엔딩=자유·스토리만 게이트 오프(코드 보존·**극장 유지**)'다. `getEffectiveEndingQuoteHappy/Bad`의 호출처는 EndingService.java:170-171(V1 — 게이트 오프 대상)과 **TheaterEndingService.java:111-112, :270-271(극장 — 유지 확정)** 두 갈래다. 즉 **인용구 2필드를 삭제하면 극장 엔딩 폴백이 빈 대사로 깨진다.** role-desc(④.1~4)와 달리 이 4건은 '채우기'가 답이다.

**❓ 결정 필요**: §G-4의 '엔딩 시드 필드 삭제'를 endingRoleDesc(V1 전용)에만 적용하고 endingQuoteHappy/Bad(극장 공용)는 유지·채움으로 갈라도 되는지 확정해 달라. 지금 문서대로 일괄 삭제하면 극장 엔딩 폴백이 무대사 씬을 낸다.

---

### E-3.④.6. 시에라 엔딩 인용구 2필드가 빈 문자열 — 극장 엔딩 폴백에서 인용구 없는 엔딩 씬이 나간다

**🔴 잔존** · P2 · ONE_LINE · YML  
`src/main/resources/application-characters.yml:949-950`

**근거**

application-characters.yml:949-950 `      ending-quote-happy: ""` / `      ending-quote-bad: ""`. 시에라 theater-available: true(characters.yml:975). 소비 경로는 ④.5와 동일(Character.java:494-501 폴백 무력화 → EndingService.java:170-171 / TheaterEndingService.java:111-112, :270-271).

**수정안**

application-characters.yml:949-950에 시에라 대사 2줄 작성. 삭제 노선 금지(④.5 productDecisionRisk).

**제품 결정 연동**: ④.5와 동일 — §G-4(삭제) vs §C-#6(극장 유지) 충돌 대상.

---

### E-3.④.7. 에델 엔딩 인용구 2필드가 빈 문자열 — 극장 엔딩 폴백에서 인용구 없는 엔딩 씬이 나간다

**🔴 잔존** · P2 · ONE_LINE · YML  
`src/main/resources/application-characters.yml:1062-1063`

**근거**

application-characters.yml:1062-1063 `      ending-quote-happy: ""` / `      ending-quote-bad: ""`. 에델 theater-available: true(characters.yml:1088). 소비 경로 ④.5와 동일.

**수정안**

application-characters.yml:1062-1063에 에델 대사 2줄 작성. 삭제 노선 금지.

**제품 결정 연동**: ④.5와 동일.

---

### E-3.④.8. 류설아 엔딩 인용구 2필드가 빈 문자열 — 극장 엔딩 폴백에서 인용구 없는 엔딩 씬이 나간다

**🔴 잔존** · P2 · ONE_LINE · YML  
`src/main/resources/application-characters.yml:1181-1182`

**근거**

application-characters.yml:1181-1182 `      ending-quote-happy: ""` / `      ending-quote-bad: ""` (:1180 role-desc는 ④.4). 류설아 theater-available: true(characters.yml:1207). TheaterEndingService.java:269-275 `String quote = type.isHappy() ? heroine.getEffectiveEndingQuoteHappy() : heroine.getEffectiveEndingQuoteBad();\n\n        return List.of(new SceneResponse(\n            narration, quote, …EmotionTag.RELAX, defaultLoc, null, null, null\n        ));` → 대사가 빈 엔딩 씬 1장. 참고로 같은 메서드 :263 `String defaultLoc = heroine.getEffectiveDefaultLocation();`는 ①.11의 `"ABANDONED_SHRINE"`을 그대로 씬 location에 넣는다 — ①과 ④가 같은 코드 라인에서 만나는 지점.

**수정안**

application-characters.yml:1181-1182에 류설아 대사 2줄 작성. 삭제 노선 금지.

**제품 결정 연동**: ④.5와 동일.

---

### E-3.④.9. Character.applySeed의 != null 검사가 빈 문자열 시드를 그대로 DB에 영속시킨다

**🔴 잔존** · P2 · SMALL · BE  
`src/main/java/com/spring/aichat/domain/character/Character.java:407-409 (동일 패턴이 :382-424 전 필드)`

**근거**

Character.java:407-409 `        if (seed.endingRoleDesc() != null) this.endingRoleDesc = seed.endingRoleDesc();\n        if (seed.endingQuoteHappy() != null) this.endingQuoteHappy = seed.endingQuoteHappy();\n        if (seed.endingQuoteBad() != null) this.endingQuoteBad = seed.endingQuoteBad();`. YAML의 `""`는 빈 String으로 바인딩되어 non-null → 매 부팅마다(application-characters.yml:3 `update-existing: true`) DB에 빈 문자열이 재기록된다. 즉 어드민에서 값을 채워 넣어도 **다음 배포에 다시 지워진다**. 같은 `!= null` 패턴이 :382-424 전 시드 필드에 걸쳐 있어(personality, tone, oocExample, backstory, defaultLocation…) 이 클래스의 결함이 어느 필드에서든 재현 가능하다.

**수정안**

엔딩 3필드에 한정해 최소 수정하려면 Character.java:407-409를 `if (seed.endingRoleDesc() != null && !seed.endingRoleDesc().isBlank()) …` 3줄로 교체. 더 나은 방식은 private 헬퍼 `private static boolean present(String s) { return s != null && !s.isBlank(); }`를 만들어 applySeed 전체(:382-424)에 적용하는 것 — 단 '빈 문자열로 명시적 초기화'를 의도한 필드가 있는지 확인 필요(현재 시드 전수 확인 결과 빈 문자열은 엔딩 3필드×4캐릭터 = 10건뿐이라 부작용 없음). ⚠ 로컬 dev DB에 이미 빈 문자열이 적재돼 있으므로 코드 수정 후에도 재부팅 1회가 필요하다.

**제품 결정 연동**: 블록 D가 엔딩 시드 필드를 삭제하는 노선이면 :407-409는 통째로 사라져 이 defect도 소멸한다. 다만 `!= null` 패턴 자체는 나머지 40여 필드에 남으므로, 헬퍼 도입 방식으로 고치면 블록 D 노선과 무관하게 가치가 있다.

---

### E-3.④.10. getEffectiveEndingRoleDesc/QuoteHappy/QuoteBad의 != null 검사가 빈 문자열에서 폴백을 무력화한다

**🔴 잔존** · P1 · ONE_LINE · BE  
`src/main/java/com/spring/aichat/domain/character/Character.java:490-502`

**근거**

Character.java:490-502 `    public String getEffectiveEndingRoleDesc() {\n        return endingRoleDesc != null ? endingRoleDesc : "a character in a visual novel";\n    }\n\n    public String getEffectiveEndingQuoteHappy() {\n        return endingQuoteHappy != null ? endingQuoteHappy\n            : "당신과의 모든 순간이, " + name + "에겐 기적이었어요.";\n    }\n\n    public String getEffectiveEndingQuoteBad() {\n        return endingQuoteBad != null ? endingQuoteBad\n            : "그 분이 처음 문을 열었을 때의 온기가... 아직도 손끝에 남아 있습니다.";\n    }`. 폴백 문구가 **이미 잘 준비돼 있는데**(이름 보간까지) `""`가 non-null이라 한 번도 쓰이지 않는다. 이 3줄만 고쳐도 ④.4~④.8의 사용자 가시 증상(빈 인용구·`"You are 류설아, ."`)이 즉시 사라진다 — 시드를 안 채워도. 같은 파일 :486-488 `getEffectiveRole()`, :478-484 `getEffectiveDefaultOutfit/Location()`도 동일 패턴(다만 해당 시드에는 빈 문자열이 없다).

**수정안**

Character.java:491 / :495 / :500의 `xxx != null` → `xxx != null && !xxx.isBlank()` (또는 `!org.springframework.util.StringUtils.hasText(xxx)` 반전). **이 3줄이 E-3 ④ 전체에서 비용 대비 효과가 가장 크다** — 시드 채우기(④.1~8, 제품 판단 대기)와 무관하게 오늘 적용 가능하고, 블록 D가 엔딩 필드를 삭제하는 노선을 택해도 극장 폴백(TheaterEndingService.java:270-271)에서 계속 값을 하는 코드다. 다만 ④.1~③의 '복붙된 로제타 문구'는 non-blank이므로 이 수정으로 해소되지 않는다(그건 시드 교정이 필요).

**제품 결정 연동**: 블록 D 노선과 무관하게 유효 — §C-#6이 '극장 유지'를 확정했고 극장 엔딩 폴백이 이 getter를 쓰기 때문. §G-4가 endingRoleDesc를 삭제하면 :490-492만 함께 사라지고 quote 2개는 남는다. 즉 **이 defect는 어떤 제품 결정에서도 고쳐야 하는 유일한 ④ 항목**이다.

---

### E-4.1. RelationStatusPolicy.isUpgrade가 ordinal 비교라 ENEMY 회복 전이가 전부 '승급 아님' — 적대 상태에서 회복하면 승급 시험·세리머니·해금을 통째로 건너뛰고 LOVER까지 무료 직행

**🔴 잔존** · P2 · ONE_LINE · BE  
`src/main/java/com/spring/aichat/domain/chat/RelationStatusPolicy.java:93-95 (판정자) · src/main/java/com/spring/aichat/service/stream/ChatStreamService.java:942 (유일 호출처)`

**근거**

RelationStatusPolicy.java:93-95 —
```java
public static boolean isUpgrade(RelationStatus current, RelationStatus next) {
    return next.ordinal() > current.ordinal() && next != RelationStatus.ENEMY;
}
```
RelationStatus.java의 선언 순서가 STRANGER(0), ACQUAINTANCE(1), FRIEND(2), LOVER(3), **ENEMY(4)** — ENEMY가 맨 뒤라 ordinal이 가장 크다. 따라서 isUpgrade(ENEMY, LOVER) = (3 > 4) = false. ENEMY에서 어떤 단계로 회복해도 승급으로 판정되지 않는다.

도달 경로 확인 — ChatStreamService.java:296에서 `ChatModePolicy.supportsPromotion(freshRoom.getChatMode())` 가드를 통과해 호출되고, ChatModePolicy.java:85-87이 `return mode == ChatMode.SANDBOX;`라 SANDBOX(자유 모드)에서 **현재 라이브**다.

피해가 '승급 이벤트 미발동'에서 끝나지 않는다. ChatStreamService.java:942-951에서 isUpgrade가 true일 때만 `room.updateAffection(thresholdEdge)` + `room.updateStatusLevel(oldStatus)` + `room.markPromotionWaiting(newStatus)`로 **승급을 되돌려 붙잡아 둔다**. false면 그 블록이 통째로 스킵되고, 곧이어 ChatStreamService.java:306-308의
```java
if (!freshRoom.isPromotionPending()) {
    freshRoom.refreshRelationFromStats();
}
```
가 실행된다. ChatRoom.java:777-789 refreshRelationFromStats는 fromStats 결과를 **무조건 statusLevel에 대입**한다. 즉 유저가 스탯을 음수로 떨어뜨려 ENEMY가 된 뒤 회복하면, 승급 시험도 세리머니도 `getUnlocksForRelation` 해금도 자동 CG(generateAutoIllustration)도 전부 건너뛰고 statusLevel만 조용히 FRIEND/LOVER로 점프한다.

**수정안**

RelationStatusPolicy.java:93 isUpgrade를 ordinal 비교에서 **정책 서열 비교**로 교체한다. 같은 파일에 이미 서열의 단일 소스가 있다 — `getThresholdScore(status)`(:83-91, ENEMY=-100 / STRANGER=0 / ACQUAINTANCE=21 / FRIEND=40 / LOVER=80). 이걸 재사용하면 새 상수를 만들지 않아도 된다.
```java
public static boolean isUpgrade(RelationStatus current, RelationStatus next) {
    if (next == RelationStatus.ENEMY) return false;
    return getThresholdScore(next) > getThresholdScore(current);
}
```
ENEMY(-100) → STRANGER(0)도 승급으로 잡히는 점을 의도로 확정할지 정책 판단이 필요하다(아래 openQuestion). '적 → 타인'까지 승급 시험을 태우는 게 과하다면 `current == ENEMY`일 때는 시험 없이 statusLevel만 복원하고 세리머니만 재생하는 별도 분기가 대안이다.

★ 착수 순서 주의: §G-1이 이 판정자의 유일한 호출처(ChatStreamService:853-965 승급 블록)를 삭제 대상으로 지목했다. **지금 단독으로 고치면 블록 D에서 재작업**이 된다. V2 이중 게이트를 SANDBOX에 이식하는 같은 커밋 안에서 교정할 것.

**제품 결정 연동**: §G-1(🔴삭제: V1 승급 '시험' 이벤트)과 **부분적으로만** 얽힌다. §G-1이 지운다고 명시한 것은 '시험·mood_score·디렉터 모드 결합'이고, **'승급 세리머니 연출은 유지'**·'V2 이중 게이트 패턴(자격 활성+LLM 자율 발동)을 SANDBOX에 이식'이 명시 조건이다(docs/14_assets §5: "시험만 뽑고 승급 자체를 죽이면 안 된다"). 이식된 구조에서도 '관계 단계가 올라갔는가'를 판정하는 술어는 반드시 필요하므로 **블록 D를 해도 이 결함은 사라지지 않는다** — 다만 판정자가 놓일 위치가 바뀔 수 있으므로 블록 D와 동일 커밋에서 처리해야 한다. 부수적으로 §G 재해석 #10('8축 스탯 유지+소프트캡화, 만렙=엔딩 결합 해제')이 fromStats/fromScore 임계 자체를 손대면 getThresholdScore 기반 수정안도 함께 검토 대상이 된다.

**❓ 결정 필요**: ENEMY(적) 상태에서 회복할 때 승급 시험을 태울 것인가, 아니면 시험 없이 관계 단계만 복원하고 세리머니만 재생할 것인가? 전자면 '적 → 타인' 회복에도 5턴 시험이 붙어 회복이 매우 무거워지고, 후자면 ENEMY 회복만 예외 규칙이 된다. §G-1의 '무한 관계 시뮬' 방향성상 후자로 보이나 종원 확정 필요.

---

### E-4.2. 승급 진행도가 스탯 변화량의 '절댓값 합'이라 캐릭터를 모욕해도 승급 성공 — 실패 분기가 사문화

**🔴 잔존** · P2 · ONE_LINE · BE  
`src/main/java/com/spring/aichat/dto/chat/AiJsonOutput.java:182-185 (집계식) · src/main/java/com/spring/aichat/service/stream/ChatStreamService.java:912-914 (누적) · :980-983 (판정)`

**근거**

AiJsonOutput.java:182-185 —
```java
public int totalNormalStatDelta() {
    return Math.abs(safeIntimacy()) + Math.abs(safeAffection())
        + Math.abs(safeDependency()) + Math.abs(safePlayfulness()) + Math.abs(safeTrust());
}
```
ChatStreamService.java:912-914 —
```java
int statDelta = parsed.statChanges() != null
    ? parsed.statChanges().totalNormalStatDelta() : 0;
room.advancePromotionTurn(statDelta);
```
ChatStreamService.java:980-983 (resolvePromotionResult) —
```java
int totalStatDelta = room.getPromotionMoodScore();
RelationStatus target = room.getPendingTargetStatus();
boolean success = totalStatDelta >= RelationStatusPolicy.PROMOTION_SUCCESS_THRESHOLD;
```
RelationStatusPolicy.java:33/44/51 — PROMOTION_MAX_TURNS=5, PROMOTION_SUCCESS_THRESHOLD=10, PROMOTION_FAILURE_PENALTY=5.

절댓값이므로 부호가 소거된다. 5턴 동안 5축을 각 -2씩 깎는(= 관계를 최대한 파탄내는) 플레이는 턴당 |−2|×5=10, 누적 50 ≥ 10으로 **성공 임계를 5배 초과**한다. 반대로 스탯을 전혀 움직이지 않는 무미건조한 5턴만이 실패한다. 즉 실패 조건이 '모욕'이 아니라 '무반응'이 되어, ChatStreamService.java:997 이하의 `room.completePromotionFailure()` + PROMOTION_FAILURE_PENALTY 강등 분기는 사실상 도달하지 않는다.

같은 파일 :11의 클래스 주석이 "PROMOTION_SUCCESS_THRESHOLD: 매 턴 최소 평균 2의 스탯 변화 필요"라고 적혀 있어, 원래 의도가 '변화량'이 아니라 '긍정적 상승분'이었음을 보여준다.

도달성: ChatStreamService.java:296이 `ChatModePolicy.supportsPromotion` 가드 안에서 호출하고 ChatModePolicy.java:85-87이 SANDBOX에 true를 주므로 자유 모드에서 라이브.

**수정안**

**블록 D를 먼저 확정하고, 그 결과에 따라 둘 중 하나만 한다.**

(a) 블록 D 전에 급히 막아야 한다면 — AiJsonOutput.java:182에 부호 보존 집계를 하나 더 만들고(기존 totalNormalStatDelta는 다른 호출처가 없는지 확인 후 교체 가능), ChatStreamService.java:913을 그것으로 바꾼다:
```java
public int signedNormalStatDelta() {
    return safeIntimacy() + safeAffection()
        + safeDependency() + safePlayfulness() + safeTrust();
}
```
음수 누적이 가능해지므로 ChatRoom.advancePromotionTurn의 promotionMoodScore 하한(0 클램프 여부)도 함께 확인해야 한다 — 클램프가 있으면 모욕 플레이가 '0에서 정체'가 되어 실패로 떨어지고, 없으면 음수로 내려가 확실히 실패한다.

(b) 블록 D를 함께 한다면 — 고치지 말고 **삭제**한다. §G-1이 지목한 대로 resolvePromotionLogic의 wasPending 분기(ChatStreamService:900-930)·resolvePromotionResult(:978-1010)·ChatRoom promotion* 6필드·PROMOTION_MAX_TURNS/SUCCESS_THRESHOLD/FAILURE_PENALTY 상수를 함께 걷어내고, V2 RelationPromotionService의 '자격 활성 + LLM 자율 발동' 패턴을 SANDBOX에 이식한다. 이 결함은 그 삭제 대상 코드 안에서만 산다.

**제품 결정 연동**: **블록 D를 하면 이 결함은 코드째 사라진다(MOOT 예정).** §G-1이 삭제 대상으로 명시한 범위("시험·mood_score·디렉터 모드 결합 제거", 인용 좌표 ChatStreamService:853-965)가 이 결함의 서식지와 정확히 일치한다 — promotionMoodScore가 곧 mood_score이고, resolvePromotionResult의 성공/실패 판정이 곧 '시험'이다. §G-1의 삭제 사유("실패→강등이 무한 관계 시뮬과 정면 충돌")가 이 결함의 증상(실패 분기 사문)과 같은 곳을 가리킨다. → **단독 수정 권장하지 않음. 블록 D 대기.** 다만 블록 D 착수가 지연되고 그 사이 자유 모드가 라이브라면, 위 (a)를 임시 방편으로 넣되 블록 D에서 지워질 코드임을 커밋 메시지에 명시할 것.

**❓ 결정 필요**: 블록 D(§G-1 승급 시험 제거) 착수 시점이 언제인가? '곧'이면 이 건은 손대지 말고 대기, '미정/장기'면 (a) 임시 픽스를 넣어야 한다. 이 판단이 없으면 재작업이 확정된다.

---

### E-4.3. TheaterCommandClassifier.llmClassify가 LLM 분류 결과를 계산해놓고 버린 뒤 무조건 ALLOWED_OTHER 반환 — 감독 명령어 거부 게이트가 LLM 경로 전체에서 무력화

**🔴 잔존** · P1 · ONE_LINE · BE  
`src/main/java/com/spring/aichat/service/theater/TheaterCommandClassifier.java:317-325 · 소비처 src/main/java/com/spring/aichat/service/theater/TheaterDirectorNoteService.java:181-188`

**근거**

TheaterCommandClassifier.java:317-325 —
```java
CommandVerdict verdict;
try {
    verdict = CommandVerdict.valueOf(verdictStr.trim());
} catch (IllegalArgumentException ex) {
    verdict = CommandVerdict.REJECTED_UNCLEAR;
}
return new ClassificationResult(CommandVerdict.ALLOWED_OTHER, reason);
```
지역변수 `verdict`를 파싱·폴백까지 완비해놓고 **한 번도 읽지 않은 채** 리터럴 `ALLOWED_OTHER`를 반환한다. reason 문자열만 LLM 것을 쓴다.

영향 범위 = ruleBasedCheck가 null을 돌려주는 모든 입력. ruleBasedCheck(:194-259)는 (1) 정규식 인젝션 패턴, (2) AFFECTION_KEYWORDS 13개 완전일치 contains, (3) PERSONA_KEYWORDS 7개, (4) AVATAR_KEYWORDS 6개, (5) 히로인 이름+능동동사 정규식, (6) ENVIRONMENT_KEYWORDS 화이트리스트 — 이 6개 중 어디에도 안 걸리면 `return null`(:258)로 LLM에 위임한다. 즉 키워드 목록을 살짝 비껴간 표현은 **전부 무조건 허용**된다. 시스템 프롬프트(:280-302)가 REJECTED_HEROINE_DIRECT / REJECTED_AFFECTION / REJECTED_PERSONA / REJECTED_AVATAR / REJECTED_INJECTION 5종을 성실히 정의하고 LLM이 그걸 정확히 반환해도 전부 버려진다.

소비처에서 게이트가 실제로 열린다 — TheaterDirectorNoteService.java:181-188:
```java
ClassificationResult cls = commandClassifier.classify(sanitized, roomId);
if (!cls.isAllowed()) { ... return saveAndReturn(room, state, trimmed, cls.verdict(), cls.verdict().userMessage()); }
```
CommandVerdict.isAllowed()(:66-68)가 `name().startsWith("ALLOWED_")`이므로 ALLOWED_OTHER는 항상 통과, 명령어가 에너지 1 차감 후 DB 저장 + Redis 활성 큐 등록을 거쳐 다음 배치 프롬프트에 주입된다. 앞단 ContentModerationService(:170)는 NSFW/유해성만 보므로 '히로인 행동 직접 지시' 같은 설계 위반은 걸러내지 못한다 — 이 분류기가 유일한 방어선이다.

**수정안**

TheaterCommandClassifier.java:325를 계산된 값으로 교체한다:
```java
return new ClassificationResult(verdict, reason);
```
한 줄이지만 **배포 즉시 체감 동작이 바뀐다** — 지금까지 통과하던 애매한 명령어가 거부되기 시작하므로, 배포 전에 실제 유입 로그를 확인할 것. :137-138의 `log.info("🎬 [COMMAND-CLF] llm-classified | verdict={} ...")`가 이미 verdict를 찍고 있으니(현재는 항상 ALLOWED_OTHER가 찍히지만 수정 후엔 진짜 판정이 찍힌다) 프로드 로그로 거부율을 사전 추정하려면 이 한 줄만 먼저 넣고 `cls.isAllowed()` 강제 통과를 며칠 유지하는 2단 배포도 가능하다.

추가 점검(같은 수정 범위): 거부가 실제로 살아나면 REJECTED_UNCLEAR 오탐 시 유저가 에너지를 잃지 않는지 확인 — TheaterDirectorNoteService.java:190-196 주석이 "거부된 명령어에는 차감하지 않는다"고 선언하고 차감이 거부 분기 뒤(:194)에 있으므로 정합하다. 별도 수정 불요.

**제품 결정 연동**: none — 극장(Theater)은 docs/14 #6이 "코드 보존·**극장 유지**"로 명시 존치를 확정했고, docs/14_assets §1의 '극장 무변경 원칙'("페르소나 개편이 극장 코드를 한 줄도 건드리면 안 된다")도 극장을 손대지 말라는 뜻이지 극장을 버린다는 뜻이 아니다. §G 21건 처분 목록에도 감독 명령어/분류기는 없다. 블록 D와 무관하게 남는다.

---

### E-4.4. TheaterService.finalizeChapter에 중복 호출 가드가 없어 POST /chapter-end 반복만으로 챕터 무한 스킵 + 인터미션 스태미나 무한 리필

**🔴 잔존** · P1 · SMALL · BE  
`src/main/java/com/spring/aichat/service/theater/TheaterService.java:224-347 · 엔드포인트 src/main/java/com/spring/aichat/controller/TheaterController.java:77-84`

**근거**

TheaterController.java:77-84 —
```java
@PostMapping("/{roomId}/chapter-end")
@PreAuthorize("@authGuard.checkRoomOwnership(#roomId, principal.subject)")
public ChapterReport finalizeChapter(Long roomId, Authentication authentication) {
    return theaterService.finalizeChapter(roomId, authentication.getName());
}
```
소유권만 검사한다. 요청 본문도 배치 ID도 없어 **호출이 유효한 챕터 종료 시점인지 검증할 수단 자체가 없다**.

TheaterService.java:224-227 —
```java
@Transactional
public ChapterReport finalizeChapter(Long roomId, String username) {
    ChatRoom room = getOwnedRoom(roomId, username);
    TheaterState state = getState(roomId);
```
이후 :347까지 훑어도 멱등 가드가 없다. `state.isInIntermission()` 검사도, `scenesInCurrentChapter >= chapterTargetScenes` 검사도, 마지막 finalize 시각/배치 ID 대조도 없다. 곧장 부작용으로 들어간다.

부작용 1 — 챕터 스킵. TheaterState.java:308-316 completeChapter():
```java
public void completeChapter() {
    this.scenesInCurrentChapter = 0;
    this.currentBatchId = 0;
    this.currentChapter += 1;
    this.majorBranchDoneInChapter = Boolean.FALSE;
}
```
호출마다 currentChapter가 무조건 +1. TheaterService.java:314에서 조건 없이 실행되고, 바로 아래 :319-320에서 `if (transitionToNewAct) state.advanceToNextAct();`까지 타면 Act까지 넘어간다(directorEngine.isLastChapterOfAct가 true인 상태에서 반복 호출하면 매번 Act 전진).

부작용 2 — 스태미나 무한 리필. TheaterState.java:433-436:
```java
public void startIntermission() {
    this.inIntermission = true;
    this.intermissionStamina = 5;
}
```
TheaterService.java:322-324가 `if (leadsToIntermission) state.startIntermission();`로 호출한다. leadsToIntermission은 :275의 `!(isLastAct && isLastChapterOfAct)`라 마지막 Act 마지막 챕터를 뺀 **거의 항상 true**. 인터미션 스태미나는 TheaterState.java:438-440 consumeIntermissionStamina로 아바타 5축 스탯을 올리는 데 쓰는 진행 재화이므로, 반복 호출 → 스탯 무제한 상승이 성립한다. TheaterService.java:270-274 주석이 "신규: 모든 chapter 후 인터미션(총 ~25회) → 끝까지 진행 시 종당 60+ 가능"이라며 총량을 설계 상수로 못박고 있는데, 그 총량 제약이 그대로 무너진다.

부작용 3 — 리포트 오염. :253 `a.sealChapterDelta()`가 히로인별 runningDelta를 0으로 봉인하므로, 2회차 이후 호출은 전 히로인 델타 0짜리 빈 리포트를 반환하고 인터미션 화면이 그 값으로 그려진다.

프론트는 useTheaterStream.js:162에서 `chapterEnding` 플래그로 중복을 막지만(TheaterPlayPage.jsx:247 `if (loadingNext || chapterEnding) return;`), 이는 클라이언트 가드라 API 직접 호출에 무력하다.

**수정안**

TheaterService.java:226 (`TheaterState state = getState(roomId);`) 직후에 서버 권위 멱등 가드를 넣는다. TheaterState에 이미 있는 필드만으로 구성 가능하다:
```java
// 이미 인터미션 중 = 직전 finalize가 성공한 상태 → 중복 호출
if (state.isInIntermission()) {
    throw new BadRequestException("이미 챕터가 종료되어 인터미션 중입니다.");
}
// 챕터 목표 씬을 못 채운 상태에서의 종료 요청 = 위조
if (state.getScenesInCurrentChapter() < state.getChapterTargetScenes()
        && !directorEngine.isChapterEndSignaled(state)) {
    throw new BadRequestException("아직 챕터가 끝나지 않았습니다.");
}
```
두 번째 조건의 `isChapterEndSignaled`는 현재 존재하지 않는다 — 배치 소비 경로(TheaterService.java:215 부근 notifyBatchConsumed가 반환하는 `chapterEnd`)가 이미 '이번 배치로 챕터가 끝났는가'를 계산하고 있으므로, 그 결과를 TheaterState에 `chapterEndPending` boolean으로 스탬프해 두고 finalizeChapter가 그것을 **소비(consume-once)**하는 형태가 가장 견고하다. 즉:
1. TheaterState에 `chapterEndPending` 컬럼 추가(마이그레이션 1컬럼) + `markChapterEndPending()` / `consumeChapterEndPending()` 메서드.
2. 배치 소비에서 chapterEnd=true 산출 시 markChapterEndPending().
3. finalizeChapter 진입부에서 `if (!state.consumeChapterEndPending()) throw new BadRequestException(...)`.

마이그레이션을 피하고 싶으면 1단계 가드(`isInIntermission` + 목표 씬 미달 거부)만으로도 무한 리필·무한 스킵은 막힌다. 다만 '마지막 Act 마지막 챕터'는 leadsToIntermission=false라 inIntermission이 안 켜지므로 그 구간만 목표 씬 검사에 의존하게 된다는 점을 인지할 것.

**제품 결정 연동**: none — 극장은 docs/14 #6에서 "코드 보존·**극장 유지**"로 존치 확정. §G 처분 21건에 극장 챕터/인터미션 항목 없음. 블록 D를 해도 그대로 남는다. 다만 §G 재해석 #10(8축 스탯 소프트캡화)이 '스탯 상한' 개념을 손대면 '스태미나 무한 리필'의 피해 크기 평가가 달라질 수 있으나, 그건 극장 아바타 5축(TheaterState 소속)이 아니라 V1/V2 관계 8축 이야기이므로 docs/14_assets §1 '극장 무변경 원칙'상 무관하다.

---

### E-4.5.a. 세이브 로드가 sessionStatus를 복원하지 않아, ENDED(엔딩 완결) 세션에 로드하면 게임 상태는 중반으로 되돌아가는데 세션은 영구 '완결'로 잠긴 모순 상태가 된다

**🔴 잔존** · P2 · ONE_LINE · BE  
`src/main/java/com/spring/aichat/service/theater/TheaterSaveLoadService.java:205-207 · src/main/java/com/spring/aichat/domain/theater/TheaterState.java:501-528 (restoreFromSnapshot)`

**근거**

TheaterSaveLoadService.java:205-207 —
```java
state.restoreFromSnapshot(act, chapter, scenesInChapter, chapterTarget,
    totalScenes, currentHeroineId, batchId,
    charm, wit, bold, intel, emp, stamina);
```
TheaterState.java:501-528 restoreFromSnapshot의 전체 대입 목록 — currentAct, currentChapter, scenesInCurrentChapter, chapterTargetScenes, totalSceneCount, currentHeroineId, currentBatchId, 5개 스탯, intermissionStamina, 그리고 리셋 6종:
```java
this.inIntermission = false;
this.interventionActive = false;
this.interventionCheckpointJson = null;
this.interventionLastLogId = null;
this.endingReached = false;
this.endingType = null;
this.endingTitle = null;
this.endingMainHeroineId = null;
```
**sessionStatus / sessionStatusChangedAt은 목록에 없다.** 메서드 javadoc(:497)이 "엔딩/인터미션/난입 플래그는 모두 리셋된다"고 선언하는데 sessionStatus만 빠졌다 — 의도가 아니라 누락임을 문서 자체가 증명한다.

모순이 성립하는 경로: TheaterEndingService.java:116-120이 엔딩 도달 시 `state.markEndingReached(...)`와 함께 `state.markEnded()`를 호출한다. TheaterState.java:370-373 —
```java
public void markEnded() {
    this.sessionStatus = "ENDED";
    this.sessionStatusChangedAt = LocalDateTime.now();
}
```
이후 유저가 이전 슬롯을 로드하면 endingReached는 false로 풀리는데 sessionStatus는 "ENDED"로 남는다. 그 결과:
- TheaterLobbyService.java:560-562 — `if (state.isEnded()) throw new BadRequestException("엔딩에 도달한 극은 다시 시작할 수 없습니다. 아카이브에서 감상만 가능합니다.");` → resumeArchivedSession 영구 차단.
- TheaterLobbyService.java:402-404 — 같은 월드·같은 lead로 새 세션 시도 시 `"이 히로인의 극은 이미 엔딩에 도달했습니다..."` 400.
- TheaterLobbyService.java:266 / :276 — `filter(c -> "ARCHIVED".equals(...) || "ENDED".equals(...))`로 로비 '활성 극' 목록에서 빠지고 아카이브 칸에만 뜬다.
즉 로드는 성공(LoadResult "로드 완료" 반환)했는데 그 방으로 **다시 들어갈 수 없다**. 세이브/로드가 존재 이유를 잃는다.

도달성: FE에 로드 호출이 실재 — TheaterFinalityApi.js:27 `api.post("/theater/rooms/" + roomId + "/saves/" + slotNumber + "/load")`.

**수정안**

TheaterState.java restoreFromSnapshot(:519-527의 리셋 블록)에 sessionStatus 복원을 추가한다. 같은 클래스에 이미 전용 전이 메서드가 있으므로 필드 직접 대입 대신 그걸 쓰는 편이 sessionStatusChangedAt까지 함께 갱신되어 로비 정렬(TheaterLobbyService.java:278-281이 sessionStatusChangedAt으로 정렬)과 정합하다:
```java
// restoreFromSnapshot 말미, endingReached=false 리셋과 같은 자리
this.sessionStatus = "ACTIVE";
this.sessionStatusChangedAt = LocalDateTime.now();
```
또는 :378-381 resumeFromArchive()를 그대로 호출해도 동일하다(같은 두 줄).

주의 — 이 수정은 '다른 활성 극이 이미 있는 경우' 모델 C-2(활성 1개 + 아카이브 N)를 깨뜨릴 수 있다. TheaterLobbyService.java:394 archiveCurrentActiveIfAny(user, roomId)가 resume 경로에서는 호출되지만 **load 경로에는 없다**. 따라서 TheaterSaveLoadService.load(:176)에도 같은 정리를 넣거나, load가 ACTIVE로 되살릴 때 기존 활성 극을 아카이브하도록 TheaterLobbyService의 헬퍼를 주입해 호출해야 한다. 이 부분이 ONE_LINE을 넘어가는 유일한 지점이니 구현 시 함께 판단할 것.

**제품 결정 연동**: none — 극장 존치 확정(docs/14 #6 "코드 보존·극장 유지"), §G 처분 목록에 극장 세이브/로드 없음. 블록 D 무관. 단, docs/14 #6의 '엔딩 게이트 오프'는 **자유·스토리 한정**이고 극장 엔딩(TheaterEndingService)은 게이트 대상이 아니므로 markEnded 경로 자체가 계속 살아 있다 — 즉 이 모순을 만들어내는 선행 조건이 블록 D 후에도 그대로다.

---

### E-4.5.b. 세이브 로드가 majorBranchDoneInChapter를 복원하지 않아, MAJOR 분기 발동 후 로드하면 해당 챕터에서 MAJOR 분기가 영구 차단된다

**🔴 잔존** · P3 · SMALL · BE  
`src/main/java/com/spring/aichat/domain/theater/TheaterState.java:501-528 (restoreFromSnapshot) · 소비처 src/main/java/com/spring/aichat/service/theater/TheaterDirectorEngine.java:353`

**근거**

저장 측 누락 — TheaterSaveLoadService.java:244-269 buildSnapshot이 stateSnap에 담는 키 전량: currentAct, currentChapter, scenesInCurrentChapter, chapterTargetScenes, totalSceneCount, currentHeroineId, currentBatchId, statCharm, statWit, statBoldness, statIntellect, statEmpathy, intermissionStamina. **majorBranchDoneInChapter는 직렬화조차 되지 않는다.**

복원 측 누락 — TheaterState.java:501-528 restoreFromSnapshot의 대입 목록에도 majorBranchDoneInChapter가 없다. inIntermission·interventionActive·endingReached 등은 명시 리셋하면서 이 필드만 손대지 않는다.

필드 의미 — TheaterState.java:336-341:
```java
/**
 * [R2] MAJOR 분기 발동 마킹 — DirectorEngine.decideBranchAfterBatch가
 *      MAJOR를 결정한 직후 호출되어, 같은 Chapter에서 두 번 발동 차단.
 */
public void markMajorBranchDoneInChapter() {
    this.majorBranchDoneInChapter = Boolean.TRUE;
}
```
소비처 — TheaterDirectorEngine.java:353:
```java
if (!Boolean.TRUE.equals(state.getMajorBranchDoneInChapter())) {
```
리셋 경로는 completeChapter(:313)와 advanceToNextAct(:328) 둘뿐이다.

증상: 챕터 N에서 MAJOR 분기를 발동(플래그 TRUE) → 그 분기 결과가 마음에 안 들어 분기 직전 Quick Save(TheaterSaveLoadService.java:134 quickSave — "분기 직전 자동 저장"이 정확히 이 용도다)를 로드 → currentChapter는 N으로 되돌아오지만 플래그는 TRUE로 남는다 → DirectorEngine이 그 챕터에서 MAJOR 분기를 다시 제안하지 않는다. **Quick Save의 존재 목적(분기 되돌리기)이 정면으로 무력화된다.** 챕터가 넘어가야만(completeChapter) 풀린다.

반대 방향 결함도 성립: 챕터 N에서 아직 MAJOR를 안 쓴 상태로 저장 → 챕터 N에서 MAJOR 소진 → 로드 → 플래그는 여전히 TRUE라 되살아나지 않는다. 어느 방향이든 세이브가 이 플래그를 표현하지 못한다.

**수정안**

저장·복원 양쪽을 함께 고쳐야 한다(한쪽만 고치면 구 세이브에서 값이 없다).
1. TheaterSaveLoadService.java:257 부근 buildSnapshot에 한 줄 추가:
```java
stateSnap.put("majorBranchDoneInChapter", state.getMajorBranchDoneInChapter());
```
2. TheaterSaveLoadService.java:200 부근 load의 스냅샷 추출에 한 줄:
```java
boolean majorDone = stateNode.path("majorBranchDoneInChapter").asBoolean(false);
```
(구 세이브 호환 — 키가 없으면 false, 즉 '분기 가능'으로 관대하게 폴백. 반대로 true 폴백은 구 세이브 전량을 분기 불능으로 만들므로 금물.)
3. TheaterState.restoreFromSnapshot 시그니처에 파라미터 추가 + 대입:
```java
this.majorBranchDoneInChapter = majorDone;
```
restoreFromSnapshot 호출처는 TheaterSaveLoadService.java:205 단 한 곳이라 시그니처 변경 파급이 없다(grep 확인 완료).

**제품 결정 연동**: none — 극장 존치 확정, §G 처분 목록 무관. 블록 D 무관하게 남는다.

---

### E-4.6. V2 스토리 리셋이 월드 메모리의 Redis 캐시를 지우지 않아, 초기화 후에도 최대 2시간 동안 이전 회차 기억이 프롬프트에 계속 주입된다

**🔴 잔존** · P2 · ONE_LINE · BE  
`src/main/java/com/spring/aichat/service/story/StoryV2Service.java:809 (cascadeResetRoom 7번 단계) · 우회된 API src/main/java/com/spring/aichat/service/MemoryService.java:168-176`

**근거**

StoryV2Service.java:806-810 (cascadeResetRoom) —
```java
// 7. World-level MemorySummary (기존 RAG 시스템 재활용)
memorySummaryRepository.deleteByRoomId(roomId);

// 8. ChatLogDocument (대화 로그)
chatLogMongoRepository.deleteByRoomId(roomId);
```
**리포지토리를 직접 때려 DB 행만 지운다.** 바로 옆에 캐시까지 함께 처리하는 서비스 메서드가 이미 존재하는데 쓰지 않았다 — MemoryService.java:167-172:
```java
@Transactional
public void clearMemories(Long roomId) {
    memorySummaryRepository.deleteByRoomId(roomId);
    evictMemoryCache(roomId);
    log.info("🗑️ [MEMORY] Cleared all memories: roomId={}", roomId);
}
```
MemoryService.java:174-176 —
```java
public void evictMemoryCache(Long roomId) {
    redisTemplate.delete(MEMORY_CACHE_PREFIX + roomId);
}
```
캐시 TTL — MemoryService.java:47 `private static final long MEMORY_CACHE_TTL_HOURS = 2;`, 기록은 :185-188 `redisTemplate.opsForValue().set(..., MEMORY_CACHE_TTL_HOURS, TimeUnit.HOURS)`. 읽기는 :55-63이 Redis를 **먼저** 보고 히트면 DB를 아예 조회하지 않는다:
```java
String cached = redisTemplate.opsForValue().get(cacheKey);
if (cached != null) { ... return summaries; ...
```
주입 경로 확인 — ChatStreamServiceV2.java:431 `worldMemory = memoryService.retrieveContext(room.getId());`. 즉 리셋 직후 첫 턴부터 캐시가 살아 있으면 **삭제된 이전 회차 기억이 그대로 디렉터 프롬프트에 들어간다.**

대조군: 같은 코드베이스의 V1 삭제 경로는 올바르게 쓰고 있다 — ChatService.java:336 `memoryService.clearMemories(roomId);`. V2 리셋만 우회했다.

영향 범위는 리셋 양쪽 진입점 전부 — resetStory(:746)와 createOrReuseRoom의 overwrite(:508), createOrReuseUgcRoom의 overwrite(:585)가 모두 cascadeResetRoom을 탄다.

**수정안**

StoryV2Service.java:809의 리포지토리 직접 호출을 서비스 호출로 교체한다:
```java
// 7. World-level MemorySummary — 캐시까지 함께 무효화 (직접 deleteByRoomId 금지)
memoryService.clearMemories(roomId);
```
StoryV2Service에 MemoryService 주입이 없다면 필드 추가가 필요하다(현재 memorySummaryRepository를 직접 들고 있으므로 그것을 MemoryService로 치환하면 의존 수는 그대로). MemoryService.clearMemories가 `@Transactional`이고 cascadeResetRoom도 이미 상위 `@Transactional` 안이라 전파는 REQUIRED로 합류 — 다만 **Redis delete는 트랜잭션 롤백을 따라 되돌아오지 않으므로**, 리셋 트랜잭션이 뒤에서 실패하면 캐시만 날아간 상태가 된다. 그 방향의 실패는 '캐시 미스 → DB 재조회'로 자기치유되므로 무해하다(반대 방향보다 훨씬 안전).

동일 패턴 점검 권장: cascadeResetRoom의 6번 `heroineMemoryService.clearMemoriesForRoom(roomId)`도 캐시를 함께 지우는지 같은 커밋에서 확인할 것(HeroineMemoryService는 이번 조사 범위 밖).

**제품 결정 연동**: none — V2 STORY는 §G-2가 'V1 STORY 트랙 제거, **V2 완전 대체**'로 명시한 **살아남는 트랙**이다. 블록 D는 V2 리셋을 건드리지 않는다. 오히려 §G-2 실행 후 V2가 유일한 스토리 트랙이 되므로 이 결함의 노출도는 올라간다.

---

### E-4.7. V2 스토리 리셋 cascade가 scene_illustrations를 지우지 않아, 리셋 후 turnIndex가 0부터 다시 세어지며 '씬당 1회' 게이트가 이전 회차 기록으로 신규 요청을 오차단한다

**🔴 잔존** · P2 · SMALL · BE  
`src/main/java/com/spring/aichat/service/story/StoryV2Service.java:781-822 (cascadeResetRoom — 삭제 대상 9종에 scene_illustrations 없음) · 게이트 src/main/java/com/spring/aichat/service/illustration/scene/SceneRequestService.java:75-84,123-128`

**근거**

cascadeResetRoom(StoryV2Service.java:781-822)이 지우는 것 전량 — 주석 번호 그대로: 1.ChatRoom 본체 resetProgress / 2.ChatRoomHeroine / 3.CharacterPresence / 4.RelationPromotionEligibility / 5.OffscreenNotification / 6.HeroineMemorySummary / 7.MemorySummary / 8.ChatLogDocument / 9.StoryV2State threads. **scene_illustrations는 없다.**

SceneIllustrationRepository.java 전문에 삭제 메서드 자체가 없다 — findTopBy… 2종, findByChatRoomIdOrderByIdAsc, existsBy… 4종 조합뿐. JpaRepository 기본 deleteAll만 존재.

게이트 판정 — SceneRequestService.java:81-85:
```java
/** [2026-08-07 씬당 1회] 같은 턴의 비-FAILED 수동 렌더 존재 — 서버 권위 판정의 단일 지점. */
private boolean manualAlreadyDrawn(Long roomId, int turnIndex) {
    return illustrationRepository.existsByChatRoomIdAndTurnIndexAndTriggerSourceAndStatusNot(
        roomId, turnIndex, "MANUAL", "FAILED");
}
```
턴 좌표계 — SceneRequestService.java:75 및 :123이 동일하게:
```java
int turnIndex = (int) chatLogRepository.countByRoomId(roomId);
```
여기서 chatLogRepository는 :50 `private final ChatLogMongoRepository chatLogRepository;` — **cascadeResetRoom 8번 단계가 지우는 바로 그 컬렉션이다.**

결합: 리셋으로 Mongo 로그가 0이 되면 turnIndex가 0부터 다시 매겨진다. 그런데 scene_illustrations에는 이전 회차의 turnIndex 0..N 행이 MANUAL/COMPLETED 상태로 남아 있다. 따라서 리셋 후 새 플레이의 0..N번째 턴에서 `manualAlreadyDrawn`이 전부 true를 반환한다.

유저가 보는 증상 — SceneRequestService.java:78 `return new SceneAvailability(ready, props.energyCostOrDefault(), alreadyDrawn);`로 availability에 alreadyDrawn=true가 실려 버튼이 '이미 그림' 상태로 잠기고, 우회해 requestManual을 때려도 :128 `if (manualAlreadyDrawn(roomId, turnIndex))`에서 막힌다. 즉 **이전 회차에 씬 일러를 뽑은 턴 수만큼, 리셋 후 새 플레이의 초반이 통째로 씬 일러 구매 불가**가 된다. 5E 유료 기능이므로 결제 전환 손실이다(과금은 안 되므로 자금 손실은 아님).

주의 — docs/14_assets §6 '재작업 금지 목록'의 "씬당 1회 심의 '전송 시작' 발제" 항목과는 다른 건이다. 그 항목은 '턴 실패 시 409 → 자가치유'라는 **의도된 트레이드오프**를 지키라는 뜻이고, 여기는 리셋이 좌표계를 되감는데 기록이 안 지워지는 **정합성 결함**이다.

**수정안**

두 가지 안 중 (a)를 권장한다.

(a) 리셋 시 삭제 — SceneIllustrationRepository에 파생 삭제 메서드를 추가하고 cascade에 10번 단계로 넣는다.
```java
// SceneIllustrationRepository
void deleteByChatRoomId(Long chatRoomId);
```
```java
// StoryV2Service.cascadeResetRoom, 8번(ChatLogDocument) 직후
// 10. SceneIllustration — turnIndex 좌표계가 로그와 함께 0으로 되감기므로 함께 삭제
sceneIllustrationRepository.deleteByChatRoomId(roomId);
```
단점: 유저가 이전 회차에 뽑은 씬 일러 갤러리가 사라진다. **이게 제품 판단 지점이다**(아래 openQuestion). 갤러리 보존이 필요하면 (b)로.

(b) 좌표계 분리 — scene_illustrations에 `playthroughSeq`(리셋 카운터)를 추가하고 게이트 조회를 `(roomId, playthroughSeq, turnIndex)`로 바꾼다. ChatRoom에 resetCount 컬럼 추가 + resetProgress에서 증가. 갤러리는 전부 살아남고 게이트만 회차별로 리셋된다. 대신 DB 마이그레이션 2건(컬럼 2개) + existsBy… 시그니처 변경 + 기존 행 백필(전부 seq=0)이 필요해 규모가 커진다.

어느 쪽이든 리플레이 기능과의 상호작용을 확인할 것 — docs/14_assets §6이 "리플레이 중 씬 일러 무대 언마운트: 의도된 설계(일러 점프는 마커 썸네일 전담)"라고 적었으므로, 씬 일러가 리플레이 마커의 데이터원이라면 (a)는 리셋 시 마커도 함께 잃는다.

**제품 결정 연동**: none (블록 D 무관 — V2 STORY는 §G-2에서 살아남는 트랙, 씬 일러 트랙도 §G-6에서 '**씬 일러로 일원화**'로 오히려 승격된 쪽이다). 다만 §G-6이 "레거시 캐릭터 일러 트랙(ModelsLab CG)은 동결하고 **갤러리는 씬 일러 열람처로 개편**(빈 갤러리 방치 금지)"이라고 확정한 것이 위 수정안 (a)와 충돌한다 — 갤러리의 유일한 콘텐츠 공급원이 씬 일러가 되는데 리셋이 그걸 지우면 §G-6이 금지한 '빈 갤러리'가 리셋 때마다 발생한다. **§G-6을 근거로 (b) 좌표계 분리가 정답에 가깝다.**

**❓ 결정 필요**: 리셋(스토리 초기화) 시 이전 회차의 씬 일러를 (a) 함께 삭제할 것인가, (b) 갤러리에 영구 보존하고 게이트 좌표계만 회차별로 분리할 것인가? §G-6의 '갤러리=씬 일러 열람처, 빈 갤러리 방치 금지'와 정면으로 얽히므로 종원 확정 필요. (b)면 DB 마이그레이션 2컬럼이 추가된다.

---

### E-4.8. 공식 월드 V2 스토리 방 생성의 히로인 검증에 isHidden 검사가 빠져, 어드민이 숨김 처리한 공식 캐릭터로도 API 직접 호출로 방을 열 수 있다

**🔴 잔존** · P3 · ONE_LINE · BE  
`src/main/java/com/spring/aichat/service/story/StoryV2Service.java:481-489 (공식 경로 — 누락) ↔ :557-568 (UGC 경로 — 검사 있음)`

**근거**

공식 월드 경로 — StoryV2Service.java:481-489 (createOrReuseRoom):
```java
for (Character h : heroines) {
    if (!worldId.equals(h.getWorldId())) {
        throw new BadRequestException("Heroine " + h.getId() + " does not belong to world " + worldId);
    }
    if (!h.isStoryAvailable()) {
        throw new BadRequestException("Inactive heroine: " + h.getId());
    }
}
```
UGC 경로 — StoryV2Service.java:557-568 (createOrReuseUgcRoom), **같은 서비스 같은 검증 루프인데 isHidden이 들어 있다**:
```java
if (!h.isStoryAvailable() || h.isHidden()) {
    throw new BadRequestException("Inactive heroine: " + h.getId());
}
```
두 경로의 비대칭이 누락임을 자체 증명한다.

조회 풀 쪽은 이미 막혀 있어 결함이 API 전용 우회면으로 남는다 — StoryV2Service.java:123-126:
```java
// [적대적 리뷰 P2] !isHidden() 추가 — CreateFlow 히로인 풀(findBy…HiddenFalse…)·게스트 카드와
//   카운트를 일치시킨다(hidden 캐릭터가 있으면 카드 숫자만 +1 부풀던 잔존 결함).
.filter(c -> c.getWorldId() != null && c.isStoryAvailable() && !c.isHidden())
```
(:165도 동일 필터.) 즉 UI에는 안 뜨지만, `heroineIds`를 임의로 실어 POST하면 통과한다 — 검증 루프가 `characterRepository.findAllById(request.heroineIds())`(:478) 결과를 그대로 받기 때문이다.

hidden의 의미 — Character.java:223-224 `@Column(name="hidden") private boolean hidden = false;`, 설정 주체는 AdminCharacterService.java:36 `if (req.hidden() != null) c.setHidden(req.hidden());` **어드민 전용**이다. 시드는 :740에서 항상 false로 둔다. 따라서 실사용 의미는 '어드민이 노출을 내린 캐릭터'(철회·문제 대응)이고, 이 결함은 그 어드민 조치를 API로 우회하는 통로가 된다.

심각도를 P3로 둔 근거: (1) 공식 캐릭터에만 해당하고 UGC 경로는 이미 막혀 있다, (2) hidden 캐릭터가 상시 존재하는 상태가 아니다(시드 기본 false, 어드민이 켤 때만), (3) 방 생성까지만이고 UGC 캐릭터의 `isAccessibleBy` 같은 권한 우회는 아니다. 다만 어드민이 '문제 캐릭터 긴급 차단' 용도로 hidden을 쓰는 순간 P1로 승격된다.

**수정안**

StoryV2Service.java:486을 UGC 경로(:563)와 동일한 형태로 맞춘다:
```java
if (!h.isStoryAvailable() || h.isHidden()) {
    throw new BadRequestException("Inactive heroine: " + h.getId());
}
```
같은 커밋에서 형제 경로 점검 권장 — V1 SANDBOX 방 생성과 극장 세션 생성(TheaterLobbyService)의 캐릭터 검증에도 같은 비대칭이 있는지 `isHidden` grep으로 확인할 것. 현재 백엔드 전체에서 `isHidden`을 호출하는 곳은 StoryV2Service 3곳(:126, :165, :563)뿐이므로, 다른 진입점은 전부 미검사 상태일 가능성이 높다(이번 조사 범위 밖이라 판정하지 않음).

**제품 결정 연동**: none — V2 STORY 공식 월드는 §G-2에서 살아남는 트랙이고, hidden은 어드민 운영 수단이라 §G 처분 21건과 무관하다. 다만 블록 A의 게스트 브라우징 보안 3원칙(docs/14_assets §3: "공개 전환 대상 엔드포인트는 permitAll 추가 시 하나씩 게스트 응답을 검수할 것 — 일괄 개방 금지")과 같은 결의 문제다. 방 생성은 인증 필수라 게스트 노출면은 아니다.

---

### E-4.9. V2 STORY 방에서 엔딩 생성 API가 room.getCharacter() NPE로 확정 500 — 플래그십 모드의 종착점이 전면 불능이고 프론트가 3회 재시도까지 한다

**🔴 잔존** · P0 · MEDIUM · BE/FE  
`src/main/java/com/spring/aichat/service/EndingService.java:73-74 · 엔드포인트 src/main/java/com/spring/aichat/controller/EndingController.java:27-35 · 호출처 LucidChat-Front/src/pages/ChatPageV2.jsx:1603`

**근거**

EndingService.java:70-74 —
```java
ChatRoom room = chatRoomRepository.findWithMemberAndCharacterById(roomId)
    .orElseThrow(() -> new NotFoundException("채팅방이 존재하지 않습니다. roomId=" + roomId));

com.spring.aichat.domain.character.Character character = room.getCharacter();
String characterName = character.getName();
```
null 검사 없이 즉시 역참조한다.

V2 STORY 방은 character가 구조적으로 null이다 — ChatRoom.java:430-446 createStoryV2 팩토리는 user/world/chatMode/userPersona/startLocationKey 등만 세팅하고 **character를 대입하는 문장이 없다**. 그럴 수밖에 없는 것이, ChatRoom.java:353-358의 V1 생성자가 아예 이렇게 막아 놨다:
```java
public ChatRoom(User user, Character character, ChatMode chatMode) {
    if (chatMode == ChatMode.STORY) {
        throw new IllegalArgumentException(
            "V2 STORY 방은 ChatRoom.createStoryV2(user, world, ...)로 생성하세요. " +
                "Character FK 단독으로는 V2 STORY 방을 구성할 수 없습니다.");
    }
```
V2는 멀티 히로인이라 ChatRoomHeroine으로 표현하므로 단일 character FK가 없는 게 설계다.

엔드포인트에 모드 가드가 없다 — EndingController.java:27-35:
```java
@PostMapping("/generate")
@PreAuthorize("@authGuard.checkRoomOwnership(#roomId, principal.subject)")
public EndingResponse generateEnding(@PathVariable Long roomId, @RequestBody GenerateEndingRequest request) {
    EndingType type = EndingType.valueOf(request.endingType().toUpperCase());
    return endingService.generateEnding(roomId, type);
}
```
소유권만 본다. 대조군으로 ChatService.java:253-256은 같은 문제를 정확히 인지하고 방어하고 있다 — `if (room.getCharacter() == null) throw new BadRequestException("V2 스토리 방은 이 엔드포인트로 조회할 수 없습니다...")`. EndingService에만 그 가드가 없다.

프론트가 이 경로를 정상 플로우로 탄다 — ChatPageV2.jsx(V2 STORY 전용 페이지) :1581 `generateEnding(endingTrigger.endingType);` → :1603:
```js
const res = await api.post(`/ending/rooms/${roomId}/generate`, { endingType });
```
게다가 :1607-1617이 MAX_RETRIES=3 지수백오프(2s/4s/8s)로 **500을 3연발**시킨 뒤 :1621 `showToast("엔딩 생성에 실패했습니다. 설정에서 '엔딩 다시 보기'를 시도해 주세요.", "error")`로 끝난다. 유저는 안내대로 '엔딩 다시 보기'(:1627 retryEnding)를 눌러도 같은 경로라 영원히 실패한다.

선행 트리거도 살아 있다 — EndingEligibilityService.java:112 `room.markEndingReached(type);`가 V2 STORY에서 엔딩 도달을 정상적으로 세팅하므로, endingReached=true 상태의 방은 재진입할 때마다 :1635 `generateEnding(roomInfo.endingType)`로 다시 500을 맞는다. **엔딩에 도달한 V2 방은 영구히 그 상태에 갇힌다.**

**수정안**

**블록 D 결정에 따라 수정 방향이 완전히 갈린다. 종원 확정 전에는 착수 금지(아래 openQuestion).**

(a) 블록 D대로 'V2 STORY 엔딩 게이트 오프'를 택하는 경우 — 되살리는 게 아니라 **서버에서 닫는다**. EndingService.java:70 직후에:
```java
if (room.isStoryMode()) {
    throw new BadRequestException("스토리 모드 엔딩은 현재 제공하지 않습니다.");
}
```
또는 yml 노브(`${ENDING_STORY_ENABLED:false}`)로 감싼다. **프론트만 막으면 안 된다** — docs/14_assets §5의 beta-activate 교훈("프론트만 지우면 API 착취면이 남는다")이 그대로 적용된다. FE 쪽은 ChatPageV2.jsx:1573-1582의 endingTrigger useEffect와 :1627 retryEnding 진입점을 함께 제거해야 유저가 500 토스트를 보지 않는다. 추가로 EndingEligibilityService.checkAndActivateEligibility / processDirectorTrigger도 같은 플래그로 묶어 애초에 endingReached가 켜지지 않게 해야 '엔딩 도달했는데 볼 수 없는 방'이 생기지 않는다.

(b) V2 STORY 엔딩을 실제로 살리는 경우 — EndingService 전체가 단일 캐릭터 전제로 짜여 있어 개조 범위가 크다. character를 쓰는 지점이 최소 :74(이름) · :117-120(assembleEndingScenePrompt에 character 전달) · :139(assembleEndingTitlePrompt) · :169-171(getEffectiveEndingQuoteHappy/Bad) · :77(canAccessSecretMode에 characterId). V2 대응은 `heroineRepository.findByChatRoom_Id(roomId)`에서 statAffection 최고 히로인을 lead로 뽑아 그 Character를 주입하는 방식이 최소 변경이다. 단 docs/13 §E-3 ④가 지적한 '엔딩 시드 빈 문자열/복붙' 결함이 그대로 터지므로(강채린·시에라·에델의 ending-role-desc가 로제타 것, 류설아는 3필드 전부 빈 문자열) 시드 정리가 선행되어야 한다 — 그래서 MEDIUM이 아니라 사실상 LARGE에 가깝다.

어느 쪽이든 최소한의 즉시 조치로 (a)의 3줄 가드를 먼저 넣어 500을 400으로 바꾸는 것은 손해가 없다.

**제품 결정 연동**: **블록 D와 직접 충돌 — 최우선 판단 대상.** docs/14 §C #6이 "**엔딩=자유·스토리만 게이트 오프**(코드 보존·극장 유지)"를 확정했고, 부수효과로 "docs/13 P0 중 '엔딩 무제한 재생성'…착취면 소멸"까지 명시했다. 즉 종원의 이미 내려진 결정대로라면 **이 NPE는 '고칠 결함'이 아니라 '닫을 경로'다** — 블록 D 실행 시 MOOT가 된다.

단 조건부다. docs/14 §E 블록 D의 범위가 "게이트 오프 yml 플래그+**프론트 진입점**"으로 적혀 있는데, 프론트 진입점만 막고 서버 가드를 안 넣으면 `/api/v1/ending/rooms/{id}/generate`가 소유권만으로 열린 채 남아 NPE 500이 그대로 살아 있게 된다. docs/14_assets §5가 beta-activate에 대해 정확히 같은 실수를 경고했다("프론트만 지우면 API 착취면이 남는다"). **블록 D 구현 시 이 엔드포인트의 서버측 가드를 체크리스트에 명시적으로 넣어야 한다.**

추가로 docs/16(시크릿 모드 피벗)이 V2 스토리를 핵심 소비 동선으로 승격시킨 맥락에서, '스토리에 종착점이 없다'는 것이 BM상 수용 가능한지는 별개 판단이다.

**❓ 결정 필요**: V2 STORY 엔딩을 (a) 블록 D대로 게이트 오프해서 이 경로를 서버에서 닫을 것인가, (b) 실제로 살려서 멀티 히로인 엔딩으로 구현할 것인가? docs/14 #6은 (a)로 확정돼 있지만, docs/16 이후 V2 스토리가 시크릿 핵심 동선이 된 상황에서 '엔딩 없는 스토리'가 여전히 맞는지 재확인이 필요하다. (b)를 택하면 docs/13 §E-3 ④ 엔딩 시드 정리(4캐릭터 복붙·빈 문자열)가 선행 조건으로 딸려 온다. — 그리고 (a)든 (b)든, 이미 endingReached=true로 잠긴 기존 V2 방들을 어떻게 처리할지(플래그 해제 마이그레이션 vs 방치)를 함께 정해야 한다.

---

### E-4.10. V2 STORY 엔딩 업적이 영구 미해금 — 엔딩 발동 지점(EndingEligibilityService)에 해금 호출이 없고, 유일한 해금 호출은 V2에서 500 나는 V1 경로 안에 있다

**🔴 잔존** · P2 · SMALL · BE  
`src/main/java/com/spring/aichat/service/story/EndingEligibilityService.java:112 (V2 엔딩 발동 — 해금 없음) · src/main/java/com/spring/aichat/service/EndingService.java:176 (유일한 unlockEnding — V1 경로)`

**근거**

V2 STORY의 엔딩 발동 지점 — EndingEligibilityService.java:106-114 (processDirectorTrigger 말미):
```java
room.markEndingReached(type);
log.info("🎬 [ENDING-TRIGGER] Activated: roomId={}, type={}", room.getId(), type);
return true;
```
업적 해금 호출이 없다. 이 서비스 전체(1-123행)에 achievementService 의존조차 없다 — 필드는 `private final ChatRoomHeroineRepository heroineRepository;` 하나뿐(:44).

백엔드 전체에서 엔딩 업적을 해금하는 지점은 단 하나 — grep `achievementService\.` 결과 5건 중 엔딩 관련은 EndingService.java:176:
```java
try {
    achievementService.unlockEnding(room.getUser().getId(), endingType.name());
} catch (Exception e) {
    log.warn("🏆 [ACHIEVEMENT] Failed to unlock ending achievement: {}", e.getMessage());
}
```
(나머지 4건: AchievementController :40/:50 getGallery, :61 unlockClientTriggered, ChatStreamService:1273 unlockEasterEgg.)

이중 실패 구조가 성립한다. 이 unlockEnding은 generateEnding 본문 **끝부분**(:175)에 있어서, V2 STORY 방이 이 메서드에 들어오면 :74에서 이미 NPE로 죽는다(E-4.9). 즉 V2 STORY는 (1) 발동 지점에 해금이 없고 (2) 해금이 있는 유일한 경로는 V2에서 도달 불가다. 어느 쪽으로도 열리지 않는다.

markEndingReached 호출처 전량 확인(grep) — ChatRoom.java:561(정의), EndingService.java:149(V1 경로 내부), EndingEligibilityService.java:112(V2), TheaterState.java:469 + TheaterEndingService.java:116(극장, 별도 타입). V2 경로에서 업적으로 이어지는 분기는 존재하지 않는다.

**수정안**

**블록 D 결정 대기가 우선이다(아래 productDecisionRisk).** 업적을 살리기로 하는 경우에만:

EndingEligibilityService에 AchievementService를 주입하고 :112 직후에 해금을 붙인다. EndingService.java:175-179의 try/catch 패턴을 그대로 복제해 실패가 엔딩 발동을 롤백시키지 않게 한다:
```java
room.markEndingReached(type);
try {
    achievementService.unlockEnding(room.getUser().getId(), type.name());
} catch (Exception e) {
    log.warn("🏆 [ACHIEVEMENT] V2 ending achievement unlock failed: {}", e.getMessage());
}
```
주의 2가지:
1. 순환 의존 확인 — AchievementService가 story 패키지를 참조하지 않는지 볼 것. 참조한다면 이벤트 발행(ApplicationEventPublisher) 방식으로 우회.
2. **E-4.9와 함께 판단할 것.** 만약 E-4.9를 (b)로 풀어 EndingService가 V2를 지원하게 되면, :176의 기존 해금이 살아나므로 여기에 또 넣으면 이중 해금이 된다. AchievementService.unlockEnding이 멱등인지(이미 해금된 코드 재호출 시 no-op) 확인이 선행 조건이다.

**제품 결정 연동**: **블록 D로 사라질 가능성이 높다.** docs/14 §C #6이 "이스터에그 연출 유지+**업적(지급·갤러리·해금 모달)만 게이트 오프**"를 확정했고, 부수효과로 "docs/13 P0 중 …'업적 자가해금' 착취면 소멸"까지 명시했다. 업적 지급 자체를 끄기로 한 이상 '업적이 안 열린다'는 결함은 **의도된 상태와 구분되지 않게 된다** → 블록 D 실행 시 MOOT.

추가로 E-4.9와 연동된다. 블록 D가 V2 STORY 엔딩까지 게이트 오프하면 엔딩 발동 자체가 없어져 이 결함은 두 겹으로 사라진다.

반대로 종원이 나중에 업적을 되살리기로 뒤집으면(#6은 '삭제'가 아니라 '게이트 오프+코드 보존'이므로 되살릴 여지를 남겨둔 결정이다) 이 결함이 그대로 부활한다 — 그때 V2 경로 해금 배선이 애초에 없었다는 사실이 다시 드러난다. **게이트 오프 커밋의 주석에 '이 경로는 원래 배선이 없었음'을 남겨 둘 것**을 권한다. 안 그러면 훗날 플래그만 켜고 V2에서 안 열리는 이유를 다시 추적하게 된다.

**❓ 결정 필요**: 블록 D의 '업적 게이트 오프'가 영구 결정인가, 런칭 후 되살릴 수 있는 임시 조치인가? 후자라면 지금 V2 해금 배선을 넣어두고 게이트 플래그로만 끄는 편이(코드 보존 원칙과도 일치) 나중 비용이 낮다.

---

### E-4.11. 극장 엔딩 전용 모델 선택자 resolveEndingModel이 호출처 0건 사문 — 세션의 클라이맥스인 엔딩 씬이 저비용 기본 모델로 생성된다

**🔴 잔존** · P2 · ONE_LINE · BE  
`src/main/java/com/spring/aichat/service/theater/TheaterModelResolver.java:105-113 (사문) · 실제 사용 지점 src/main/java/com/spring/aichat/service/theater/TheaterEndingService.java:220-223`

**근거**

사문 메서드 — TheaterModelResolver.java:105-113:
```java
/**
 * 엔딩 씬 생성용 모델 — 항상 proModel.
 *
 * 한 세션의 누적 가치가 응축되는 순간이며, 비용 대비 임팩트가 가장 큼.
 * 따라서 정책 분기 없이 일관되게 proModel.
 */
public String resolveEndingModel(User user) {
    return props.proModel();
}
```
호출처 grep(`resolveEndingModel`, src 전체) — 정의 1건 외 **0건**.

실제 엔딩 생성이 쓰는 모델 — TheaterEndingService.java:220-223:
```java
String responseText = openRouterClient.completeJson(
    openAiProperties.model(), systemPrompt,
    "Generate ending now.", 1800, 0.85
);
```
`openAiProperties.model()` = 기본(저비용) 모델. TheaterEndingService 전체에서 model 관련 호출은 이 1건뿐(grep `model` 결과 :221 단독)이며, TheaterModelResolver 주입 자체가 없다.

대조군 — 같은 리졸버의 형제 메서드들은 정상 배선돼 있다: TheaterBatchGenerator.java:575 `modelResolver.resolveBatchModel(...)`, TheaterBranchService.java:127 `modelResolver.resolveBranchModel(room.getUser(), level)`. **엔딩만 배선이 빠졌다.** 즉 '엔딩은 pro로 간다'는 명시적 정책이 코드로 선언돼 있으나 실행되지 않는다.

영향: 극장 세션의 최종 산출물(3씬 + closing_quote, max_tokens 1800)이 배치·CLIMAX 분기보다 낮은 품질 모델로 생성된다. resolveBranchModel(:94-103)은 CLIMAX 분기에 proModel을 주는데, 정작 그 클라이맥스들이 수렴하는 엔딩은 base 모델을 쓰는 역전이 일어난다.

**수정안**

TheaterEndingService에 TheaterModelResolver를 주입하고 :221을 교체한다:
```java
// 필드
private final TheaterModelResolver modelResolver;

// :220-223
String responseText = openRouterClient.completeJson(
    modelResolver.resolveEndingModel(room.getUser()), systemPrompt,
    "Generate ending now.", 1800, 0.85
);
```
해당 메서드 스코프에 User가 없으면 room 또는 state.getRoom().getUser()로 끌어온다(resolveEndingModel은 현재 user 인자를 쓰지 않지만 시그니처를 유지해 부스트 정책을 나중에 붙일 여지를 남기는 편이 낫다).

**원가 영향을 반드시 함께 확인할 것.** 블록 C(docs/14 #5)가 `openai.pro-model`을 `google/gemini-3.6-flash`로 치환하기로 확정했고 그 전제 3개(V1 캐시 매턴 부착·max_tokens 캡 하향·reasoning low)가 원가 성립 조건이다. **블록 C 이후에 배선하면** 엔딩이 3.6-flash로 가서 품질↑·원가 영향 최소. **블록 C 이전에 배선하면** 현행 pro-model(3.1-pro) 단가가 엔딩마다 붙는다. 순서상 블록 C 뒤가 유리하다. max_tokens 1800은 이미 낮으므로 캡 하향 전제와 충돌하지 않는다.

**제품 결정 연동**: **블록 D와 무관 — 극장은 남는다.** docs/14 §C #6이 엔딩 게이트 오프의 범위를 "**자유·스토리만**"으로 한정하고 "코드 보존·**극장 유지**"를 명시했다. 따라서 E-4.9/E-4.10과 달리 이 결함은 블록 D 이후에도 그대로 살아 있다. §G 처분 21건에도 극장 엔딩 항목 없음.

대신 **블록 C(BM·비용)와 강하게 얽힌다** — docs/14 #5의 pro-model → gemini-3.6-flash 치환이 이 배선의 원가 계산 전제를 바꾼다. 착수 순서를 블록 C 뒤로 두는 것이 원가·품질 양쪽에 유리하다. docs/14_assets §4가 "**품질 검증 없이 치환 금지**(stage0 모델 품질이 외형 태그를 좌우한 전례)"라고 경고했으므로, 블록 C 배포 후 엔딩 샘플 품질을 한 번 눈으로 확인하고 배선할 것.

---

### E-4.12. 극장 엔딩의 기억 하이라이트가 '최근 5개'라는 주석과 달리 오름차순 첫 5개 — 세션 초반 노트만 회고에 실리고 클라이맥스 직전 기억은 전부 누락

**🔴 잔존** · P3 · ONE_LINE · BE  
`src/main/java/com/spring/aichat/service/theater/TheaterEndingService.java:320-328 · 소비 지점 :142`

**근거**

TheaterEndingService.java:320-328 —
```java
private List<String> extractMemoryHighlights(Long roomId) {
    // 감독 노트 중 AUTO_MOMENT / CHAPTER_END 타입을 최근 5개 추출
    return directorNoteRepository.findByRoom_IdOrderByCreatedAtAsc(roomId).stream()
        .filter(n -> "AUTO_MOMENT".equals(n.getNoteType()) || "CHAPTER_END".equals(n.getNoteType()))
        .map(TheaterDirectorNote::getContent)
        .filter(Objects::nonNull)
        .limit(5)
        .toList();
}
```
리포지토리 메서드가 `OrderByCreatedAt**Asc**`(오름차순 = 오래된 것 먼저)인데 `.limit(5)`를 걸었다. 결과는 **가장 오래된 5개**다. 바로 위 줄의 주석이 "최근 5개 추출"이라고 선언하므로 의도와 구현이 명시적으로 어긋난다.

소비 지점 — TheaterEndingService.java:142 `extractMemoryHighlights(roomId),`로 엔딩 응답 페이로드에 실린다.

증상 규모: 인터미션 정책이 "모든 chapter 후 인터미션(총 ~25회)"(TheaterService.java:270-274 주석)이므로 CHAPTER_END 노트만 해도 세션당 20건 이상 쌓인다. AUTO_MOMENT까지 합치면 수십 건 중 **Act 1 초반 5건만** 엔딩 회고로 노출되고, Act 3~4의 클라이맥스 기억은 한 건도 실리지 않는다. 엔딩 회고의 정서적 기능(누적 가치 응축)이 정확히 반대로 작동한다.

대조군 — 같은 파일 :303-308 buildEndingStats는 `findByRoom_IdOrderByChosenAtAsc(roomId).size()`처럼 전량을 세는 용도라 Asc가 맞다. 정렬 방향 자체가 리포지토리의 문제는 아니고 이 호출부만 어긋났다.

**수정안**

두 가지 중 하나. (b)를 권장한다.

(a) 최소 변경 — 필터 후 뒤에서 5개를 취한다:
```java
List<String> all = directorNoteRepository.findByRoom_IdOrderByCreatedAtAsc(roomId).stream()
    .filter(n -> "AUTO_MOMENT".equals(n.getNoteType()) || "CHAPTER_END".equals(n.getNoteType()))
    .map(TheaterDirectorNote::getContent)
    .filter(Objects::nonNull)
    .toList();
return all.size() <= 5 ? all : all.subList(all.size() - 5, all.size());
```
시간 순서(오래된 것 → 최근)가 보존되므로 회고 나열 순서가 자연스럽다.

(b) 리포지토리에 Desc 파생 메서드를 추가하고 limit(5) 후 뒤집기 — DB에서 전량을 끌어오지 않아도 되지만, 어차피 세션당 수십 건 규모라 (a)의 성능 손해가 무시할 만하다. 오히려 (a)가 리포지토리 변경 없이 끝나 파급이 작다.

**주석도 함께 정리할 것** — 지금 주석이 구현과 반대라서, 고친 뒤에도 주석을 그대로 두면 다음 사람이 다시 헷갈린다. 그리고 정말로 '최근 5개'가 맞는 제품 의도인지 한 번만 확인하면 좋다 — Act별 대표 1건씩 뽑는 게 회고로는 더 나을 수 있으나, 그건 개선이지 버그 수정이 아니므로 별건으로 둔다.

**제품 결정 연동**: none — 극장 존치 확정(docs/14 #6 "극장 유지"), §G 처분 21건 무관. 블록 D 이후에도 남는다.

---

### E-4.13. 오프스크린 알림 토스트가 respondedAt·expiresAt을 무시하고 readAt만 보므로, 이미 소비되거나 만료 처리된 알림이 계속 재노출되고 미확인 뱃지 수가 영구히 부풀어 있다

**🔴 잔존** · P2 · SMALL · BE  
`src/main/java/com/spring/aichat/service/story/OffscreenNotificationService.java:156-160 (findUnreadForToast) · :190-192 (countUnread) ↔ 올바른 필터 :147-150`

**근거**

OffscreenNotificationService.java:156-160 —
```java
@Transactional(readOnly = true)
public List<NotificationResponse> findUnreadForToast(Long roomId) {
    List<OffscreenNotification> rows = notificationRepository
        .findByChatRoom_IdAndReadAtIsNullOrderBySentAtDesc(roomId);
```
**readAt만** 본다. 같은 클래스의 프롬프트 주입용 조회는 세 조건을 다 거는데(:147-150):
```java
public List<OffscreenNotification> findPendingForPrompt(Long roomId) {
    return notificationRepository
        .findByChatRoom_IdAndRespondedAtIsNullAndExpiresAtAfterOrderBySentAtAsc(
            roomId, LocalDateTime.now());
}
```
두 조회의 비대칭이 누락임을 자체 증명한다 — 필요한 파생 메서드가 리포지토리에 **이미 존재**하는데 토스트 경로만 안 쓴다.

소비/만료 처리가 readAt을 세팅하지 않는다:
- :207-209 markResponded → `OffscreenNotification::markResponded` (respondedAt만)
- :212-222 markRespondedByCharacter → 루프 안에서 `n.markResponded()` (:218)
- :232-256 expireOverdueNotifications(@Scheduled 1시간) → 친밀도 −1 페널티 부여 후 :252 `n.markResponded();`, 주석은 `// *Responded* 마킹 — 이력 보존 (delete X)`

따라서 세 경로 모두 readAt=null을 남긴다 → findUnreadForToast가 계속 반환한다.

증상 2가지:
1. 재노출 — 디렉터가 이미 화제로 꺼내 소비한 알림(markRespondedByCharacter), 그리고 24시간 만료로 페널티까지 이미 먹은 알림이 방에 들어갈 때마다 '새 알림' 토스트로 다시 뜬다. 유저가 하나하나 클릭해 markRead(StoryV2Controller.java:221)를 태워야만 사라진다.
2. 뱃지 부풀림 — :190-192 countUnread도 같은 파생 메서드를 쓴다:
```java
public int countUnread(Long roomId) {
    return notificationRepository.findByChatRoom_IdAndReadAtIsNullOrderBySentAtDesc(roomId).size();
}
```
소비·만료분이 전부 카운트에 남는다.

도달성 — StoryV2Controller.java:208 `return notificationService.findUnreadForToast(roomId);`로 V2 스토리 방의 정상 폴링 경로다.

**수정안**

토스트/카운트 조회를 프롬프트 조회와 같은 조건계로 통일한다. 리포지토리에 readAt까지 포함한 파생 메서드를 하나 추가하는 것이 가장 깔끔하다:
```java
// OffscreenNotificationRepository
List<OffscreenNotification> findByChatRoom_IdAndReadAtIsNullAndRespondedAtIsNullAndExpiresAtAfterOrderBySentAtDesc(
    Long roomId, LocalDateTime now);
```
그리고 두 호출부를 교체:
- :158-159 findUnreadForToast
- :191 countUnread

대안(파생 메서드 추가 없이) — findPendingForPrompt와 동일 조회를 쓰고 스트림에서 `readAt == null`을 추가 필터. 정렬이 Asc라 토스트 순서(최근 먼저)를 뒤집어야 하므로 파생 메서드 추가 쪽이 낫다.

부수 결정 1건 — **만료된 알림을 토스트에서 완전히 숨길지, '놓친 알림'으로 한 번은 보여줄지**. 지금 expireOverdueNotifications는 친밀도 −1 페널티를 조용히 먹인다(:246-249 `h.applyNormalStatChanges(EXPIRY_PENALTY_AFFECTION, 0, 0, 0, 0)`). 유저가 왜 호감도가 깎였는지 알 방법이 없으므로, expiresAt 조건으로 전부 숨기면 페널티가 완전히 불투명해진다. 아래 openQuestion 참조.

**제품 결정 연동**: none (블록 D 무관) — 오프스크린 알림은 V2 STORY 기능이고 §G-2에서 V2는 살아남는 트랙이다. §G 처분 21건에 알림 항목 없음. 다만 §G 재해석 #9(바이오메트릭 스탯 HUD를 수치 게이지 → 서술형/렌즈형으로 개편)가 '친밀도 −1' 같은 수치 페널티의 표시 방식을 바꾸면, 아래 openQuestion의 만료 안내 UX도 그 문법에 맞춰야 한다.

**❓ 결정 필요**: 24시간 만료로 친밀도 −1 페널티를 먹은 알림을 유저에게 알릴 것인가? 지금은 페널티가 조용히 적용되고 알림만 토스트로 남는 어정쩡한 상태다. (a) 만료분도 숨기고 페널티도 조용히 → 유저가 호감도 하락 원인을 영영 모름, (b) '답하지 못한 연락' 형태로 1회 노출 후 자동 read 처리 → 페널티 근거가 보임. 페널티를 유지할 거라면 (b)가 맞아 보이나 종원 확정 필요.

---

### E-4.14. 유저 스코프 업적 갤러리가 방 스코프 URL로만 열리던 결함 — 블록 A에서 /achievements/gallery 신설로 해소

**✅ 수정됨** · P3 · N/A · -  
`src/main/java/com/spring/aichat/controller/AchievementController.java:31-40 (신설 엔드포인트) · LucidChat-Front/src/components/AchievementGallery.jsx:63-65 (분기 소비)`

**근거**

수정 커밋 — `3115edc` "feat : 게스트 브라우징 백엔드 (블록 A) — 탐색 공개·게스트 DTO 스코프·IP 리밋" (git log -- AchievementController.java의 최신 커밋).

현재 코드가 docs/13 E-4를 **명시 인용하며** 수정 사실을 기록하고 있다 — AchievementController.java:31-40:
```java
/**
 * [블록 A 보관함] 유저 스코프 업적 갤러리 — 방 무관 전역 수집품 뷰.
 * 데이터는 원래 유저 단위였고(getGallery(userId)) URL만 방 스코프였다(docs/13 E-4) —
 * 로비 보관함은 방 컨텍스트가 없으므로 이 경로를 쓴다. 인증 필수(게스트 비개방).
 */
@GetMapping("/gallery")
public Gallery getMyGallery(Authentication authentication) {
    Long userId = userRepository.findByUsername(authentication.getName())
        .orElseThrow(() -> new NotFoundException("유저를 찾을 수 없습니다."))
        .getId();
    return achievementService.getGallery(userId);
}
```
기존 방 스코프 경로는 호환용으로 존치 — :45-50 `@GetMapping("/rooms/{roomId}/gallery")` + `@PreAuthorize("@authGuard.checkRoomOwnership(...)")`, 클래스 javadoc(:17-18)이 두 경로의 역할을 구분해 적어 뒀다.

프론트도 배선 완료 — AchievementGallery.jsx:18 `//    userScope  — [블록 A 보관함] true면 방 무관 유저 스코프 API(/achievements/gallery) 사용.`, :63-65:
```js
? "/achievements/gallery"
: `/achievements/rooms/${roomId}/gallery`;
```

보안 회귀 없음 확인 — 신설 경로는 `Authentication`에서 username을 뽑아 본인 userId로만 조회하므로 타인 갤러리 열람 경로가 생기지 않았고, 주석대로 게스트에 permitAll을 주지도 않았다(docs/14_assets §3 게스트 보안 3원칙 준수).

**수정안**

조치 불필요. 다만 검증 시 확인할 잔여 항목 1건 — 로비 '보관함' 탭이 실제로 userScope=true로 이 컴포넌트를 렌더하는지(블록 A R2 로비 재설계 `0e82296`에서 보관함이 인탭으로 재구성됨). 이 조사 범위 밖이라 판정하지 않았고, 결함이 아니라 배선 확인 항목이다.

**제품 결정 연동**: none — 이미 블록 A(로비·보관함 재구성)에 흡수되어 처리 완료. docs/14 §C #6의 "수집품 로비 노출은 보관함 재구성에 흡수"가 정확히 이 항목이었고 그대로 이행됐다. 단, 블록 D의 '업적 게이트 오프'가 실행되면 이 갤러리 진입점 자체가 숨겨질 수 있다(E-4.10 참조) — 그때 이 엔드포인트는 코드 보존 원칙에 따라 남기고 프론트 진입점만 감추는 형태가 된다.

---

### E-4.15. 시크릿 모드 배경 생성의 ModelsLab 웹훅 폴백이 구조적 사문 — 캐시 행이 동기 성공 후에만 만들어져, 폴백이 필요한 실패 케이스에서는 조회 대상 자체가 존재하지 않는다

**🔴 잔존** · P2 · MEDIUM · BE  
`src/main/java/com/spring/aichat/service/illustration/BackgroundGenerationService.java:298-315 (웹훅 핸들러) · :249-266 (시크릿 동기 경로) · :286 (persistCache 시점)`

**근거**

웹훅 핸들러 — BackgroundGenerationService.java:300-315:
```java
@Transactional
public void handleModelsLabWebhookCallback(String generationId, JsonNode payload) {
    log.info("[BG-WEBHOOK] (ModelsLab) Received: id={}", generationId);

    backgroundCacheRepository.findByFalRequestId(generationId).ifPresent(cache -> {
        if (cache.getImageUrl() != null && !cache.getImageUrl().isBlank()) {
            log.info("[BG-WEBHOOK] (ModelsLab) Already processed (idempotent): {}", generationId);
            return;
        }
        ...
        uploadAndCache(cache, imageUrl, generationId);
    });
}
```
`findByFalRequestId(generationId)`로 기존 캐시 행을 찾아야 동작한다. 그런데 그 행을 만드는 시점이 어긋나 있다.

시크릿 동기 경로 — :249-266:
```java
if (secretMode) {
    String trackId = "BG_" + cacheHash;  // "BG_" prefix → webhook이 캐릭터/배경 분기 가능
    ModelsLabClient.SubmitResult submit = modelsLabClient.submit(...);
    providerRequestId = submit.generationId();
    ...
    String imageUrl = submit.syncCompleted() ? submit.imageUrl()
        : pollModelsLabUntilComplete(submit.fetchUrl(), providerRequestId);
    if (imageUrl == null) {
        log.error("[BG] ModelsLab generation failed: ckey={}", canonicalKey);
        return null;                      // ← 여기서 빠져나가면 캐시 행이 없다
    }
    s3Url = s3StorageService.downloadAndUpload(imageUrl, "backgrounds/", cacheHash);
}
```
캐시 행 생성은 훨씬 뒤 :286에서 일어난다:
```java
persistCache(locationName, canonicalKey, timeOfDay, s3Url, positivePrompt, characterId, providerRequestId);
```
:374-388 persistCache가 `BackgroundCache.create(..., providerRequestId)`로 falRequestId를 채운다(BackgroundCache.java:161 `cache.falRequestId = providerRequestId;`).

따라서 두 경우로 갈리고 **양쪽 다 웹훅이 무의미하다**:
1. 동기 성공 → :286에서 imageUrl이 이미 채워진 행이 생성됨 → 나중에 웹훅이 와도 :305-308 멱등 가드에 걸려 return.
2. 동기 실패(폴링 타임아웃/에러) → :259에서 `return null`, persistCache에 도달하지 못함 → **falRequestId가 어디에도 기록되지 않음** → 웹훅이 도착해도 `findByFalRequestId`가 빈 Optional → ifPresent가 통째로 스킵.

즉 웹훅이 구제해야 할 유일한 케이스(2번)에서 조회 대상이 존재하지 않는다. `trackId = "BG_" + cacheHash`로 라우팅까지 준비해 두고(IllustrationWebhookController.java:76-78이 그 prefix로 분기한다) 정작 수신 측이 아무 일도 못 한다.

결과: 시크릿 배경 생성이 폴링 구간에서 실패하면 재시도·복구 수단이 전혀 없고, 유저는 배경 없이 진행한다. 다음 요청 때 캐시 미스로 처음부터 다시 생성한다(원가 재지출).

**수정안**

핵심은 **제출 직후에 pending 캐시 행을 먼저 만들어 두는 것**이다(웹훅이 찾을 앵커 확보).

1. BackgroundCache에 imageUrl null 허용 pending 팩토리를 추가하거나, 기존 create를 imageUrl=null로 호출할 수 있게 한다. DB에서 image_url이 NOT NULL이면 마이그레이션 1건 필요 — 확인 후 진행.
2. BackgroundGenerationService.java:257(`providerRequestId = submit.generationId();`) 직후에 pending 행을 저장한다:
```java
persistPendingCache(locationName, canonicalKey, timeOfDay, positivePrompt, characterId, providerRequestId);
```
3. :286 persistCache는 '이미 있는 pending 행을 완성'하는 형태로 바꾼다(findByCacheHash로 찾아 imageUrl 세팅). 현재 :381-384가 `findByCacheHash(...).isPresent()`면 그냥 return하므로, pending 행이 생긴 뒤에는 **완성 업데이트가 스킵되어 버리는 회귀가 난다** — 이 분기를 반드시 함께 고쳐야 한다. 이게 이 수정이 ONE_LINE이 아닌 이유다.
4. :259 실패 return 시 pending 행을 남겨 둔다(웹훅이 늦게 도착해 구제할 수 있도록). 대신 영원히 pending으로 남는 행을 정리할 TTL/스케줄러가 필요한지 판단 — 없으면 조회 시 `imageUrl != null` 조건을 거는 것으로 충분하다. **캐시 조회 경로가 pending 행을 히트해서 null URL을 반환하지 않는지 반드시 확인할 것**(이 회귀가 가장 위험하다).

대안(더 작게) — 폴백을 되살리는 대신 **명시적으로 제거**한다. 웹훅 핸들러와 IllustrationWebhookController의 "BG_" 분기를 지우고, 폴링 실패 시 재시도 정책(예: 1회 재제출)만 넣는다. 사문 코드를 남겨 두는 것보다 정직하고, docs/14 §G-4의 '데드 코드 일괄 정리' 정신과도 맞는다.

같은 파일 인접 이슈(별건, 참고): :373-374 `@Transactional protected void persistCache(...)`가 같은 클래스 :286에서 자기호출돼 프록시를 타지 않는다. 위 수정으로 persistCache 구조를 손대는 김에 함께 정리하면 좋다.

**제품 결정 연동**: **docs/16으로 우선순위가 올라간 영역이다.** docs/16(시크릿 모드 피벗 지시서)이 시크릿 모드를 핵심 BM으로 승격시켰으므로, 시크릿 전용 경로(ModelsLab 트랙)의 신뢰성 결함은 docs/13 작성 시점보다 지금 더 무겁다. 블록 D(§G 처분)와는 무관 — §G-6이 동결하기로 한 것은 '레거시 **캐릭터** 일러 트랙(ModelsLab CG)'이고, 여기는 **배경** 생성 트랙이라 다른 대상이다. 배경은 §G-5가 "V1 장소 enum은 **동적 배경으로 단계적 일원화**"라며 오히려 존치·확대 방향으로 잡았다.

주의할 혼동 하나 — §G-6의 ModelsLab 동결을 이 배경 트랙까지로 넓게 읽으면 잘못된 삭제가 일어난다. 시크릿 배경은 "캐릭터 트랙과 동일 플랫폼으로 화풍 일치"(:228 주석)를 위해 ModelsLab을 쓰는 것이지 레거시 CG 트랙이 아니다.

**❓ 결정 필요**: 시크릿 배경 웹훅 폴백을 (a) 되살릴 것인가, (b) 사문 코드로 인정하고 제거한 뒤 폴링 실패 시 재제출 1회로 단순화할 것인가? docs/16이 시크릿을 핵심 BM으로 올린 만큼 신뢰성 투자 가치는 있으나, (a)는 pending 캐시 행 도입에 따른 조회 경로 회귀 위험이 있어 규모가 커진다.

---

### E-4.16. V1 동적 배경 백필이 @Deprecated 2인자 해시를 써서 쓰기 측(canonicalKey 해시)과 키가 어긋난다 — 새로고침 후 동적 배경이 영구 미표시

**🔴 잔존** · P2 · ONE_LINE · BE  
`src/main/java/com/spring/aichat/service/ChatService.java:266-281 · 해시 정의 src/main/java/com/spring/aichat/domain/illustration/BackgroundCache.java:104-122 · 쓰기 측 BackgroundGenerationService.java:243,286`

**근거**

읽기(백필) — ChatService.java:266-281:
```java
// [Phase 5.5-Fix] 동적 배경 URL 해상도:
// 1) ChatRoom에 URL이 이미 저장되어 있으면 그대로 사용
// 2) locationName만 있고 URL이 null이면 BackgroundCache에서 조회 (비동기 생성 완료 후)
String dynamicBgUrl = room.getCurrentDynamicBgUrl();
String dynamicLocationName = room.getCurrentDynamicLocationName();
if (dynamicLocationName != null && !dynamicLocationName.isBlank() && dynamicBgUrl == null) {
    String timeOfDay = room.getCurrentTimeOfDay() != null ? room.getCurrentTimeOfDay().name() : "DAY";
    String cacheHash = com.spring.aichat.domain.illustration.BackgroundCache.computeHash(dynamicLocationName, timeOfDay);
    dynamicBgUrl = backgroundCacheRepository.findByCacheHash(cacheHash)
```
2인자 오버로드를 호출한다 — BackgroundCache.java:115-122:
```java
/**
 * 구버전 호환 — locationName + timeOfDay 직해싱.
 * 신규 코드에서는 사용 금지. 호출 전부 신 시그니처로 이행 권장.
 */
@Deprecated
public static String computeHash(String locationName, String timeOfDay) {
    return computeHash(null, timeOfDay, locationName);
}
```
canonicalKey에 **null**을 넘기므로, 3인자 본체(:108-113)의 폴백 가지를 타 `normalize(locationName) + "_" + normalize(timeOfDay)`를 해싱한다.

쓰기 측은 canonicalKey를 해싱한다 — BackgroundGenerationService.java:243:
```java
String cacheHash = BackgroundCache.computeHash(canonicalKey, timeOfDay, locationName);
```
3인자 본체 :108-111:
```java
String source = (canonicalKey != null && !canonicalKey.isBlank())
    ? canonicalKey
    : (fallbackLocationName != null ? fallbackLocationName : "");
```
canonicalKey가 있으면 그것을 쓴다. persistCache(:378)와 BackgroundCache.create(:154)도 동일 3인자 해시로 행을 만든다.

따라서 canonicalKey가 존재하는 모든 배경(= 현행 경로 전부)에서 **읽기 해시 ≠ 쓰기 해시** → findByCacheHash가 항상 미스 → :281 `.orElse(null)` → dynamicBgUrl이 null인 채 응답에 실린다.

결정적으로, 방에는 정답 키가 이미 저장돼 있는데 안 쓰고 있다 — ChatRoom.java:572-576:
```java
public void updateDynamicBackground(String locationName, String canonicalKey, String bgUrl) {
    this.currentDynamicLocationName = locationName;
    this.currentDynamicCanonicalKey = canonicalKey;
    this.currentDynamicBgUrl = bgUrl;
}
```
그리고 URL 미확정 상태(= 백필이 필요한 바로 그 상태)를 만드는 :587-591 updateDynamicLocationName도 canonicalKey를 함께 저장한다:
```java
public void updateDynamicLocationName(String locationName, String canonicalKey) {
    this.currentDynamicLocationName = locationName;
    this.currentDynamicCanonicalKey = canonicalKey;
    this.currentDynamicBgUrl = null;  // 아직 URL 미확정
}
```
호출 지점 ChatStreamService.java:860-867이 캐시 미스일 때 정확히 이 메서드를 태운다. 즉 `room.getCurrentDynamicCanonicalKey()`를 읽기만 하면 되는데 안 읽는다.

증상: 동적 장소로 이동 → 배경 비동기 생성 → 그 세션에서는 SSE로 URL이 내려와 보이지만, 새로고침해 GET /chat/rooms/{id}를 타면 백필이 미스나 배경이 사라진다. 캐시 행은 멀쩡히 있는데 못 찾는 것이라 **영구 미표시**다.

**수정안**

ChatService.java:273을 방에 저장된 canonicalKey를 쓰도록 바꾼다:
```java
String cacheHash = com.spring.aichat.domain.illustration.BackgroundCache.computeHash(
    room.getCurrentDynamicCanonicalKey(), timeOfDay, dynamicLocationName);
```
3인자 본체가 canonicalKey null/blank일 때 locationName으로 폴백하므로, canonicalKey가 없는 구 데이터도 현행과 동일하게 동작한다(회귀 없음).

같은 블록의 인접 문제 1건 — :277 `room.updateDynamicBackground(dynamicLocationName, cache.getImageUrl());`이 2인자 오버로드라 canonicalKey를 건드리지 않는다. ChatRoom.java:578-581 주석이 "currentDynamicCanonicalKey는 의도적으로 건드리지 않음(기존 값 보존)"이라 명시했으니 **이건 의도된 동작이므로 바꾸지 말 것**. 위 한 줄만 고치면 된다.

마무리 권장 — BackgroundCache.java:119의 `@Deprecated computeHash(String, String)` 호출처가 이 한 곳뿐인지 확인하고(맞으면) 오버로드 자체를 삭제한다. 남겨 두면 같은 실수가 재발한다. §G-4 '데드 코드 일괄 정리'에 함께 얹기 좋은 항목이다.

**제품 결정 연동**: none으로 보이나 §G-5와 방향이 겹친다 — §G-5가 "V1 장소 enum은 **동적 배경으로 단계적 일원화**"를 확정했으므로 동적 배경 경로는 축소가 아니라 **확대** 대상이다. 즉 이 결함은 블록 D 이후 노출도가 오히려 올라간다. §G-5의 '복장·장소 관계 해금 게이트 오프'는 해금 컬럼 6종·프롬프트 LOCK 규칙을 지우는 작업이라 이 해시 배선과는 별개다.

주의 — 이 경로는 V1 SANDBOX 전용이다(ChatService.java:253-256이 V2 STORY 방을 400으로 막는다). §G-2가 지우는 것은 V1 **STORY** 트랙이지 SANDBOX가 아니므로(오히려 "ChatPage=SANDBOX 전용 선언"), 이 코드는 블록 D 후에도 살아남는다.

---

### E-4.17.a. 이벤트 선택 턴의 씬 상태 저장이 V1에서 절대 참이 될 수 없는 isStoryMode() 게이트 안에 갇혀 사문 (ChatStreamService:564)

**🔴 잔존** · P3 · ONE_LINE · BE  
`src/main/java/com/spring/aichat/service/stream/ChatStreamService.java:564-567 · 모드 가드 :177-181 · 올바른 대조군 :301-304`

**근거**

ChatStreamService.java:562-567 (sendEventSelectStream의 TX-2) —
```java
if (parsed.bpm() != null) freshRoom.updateBpm(parsed.bpm());
freshRoom.updateLastActive(parsed.mainEmotion());
if (jpa.room().isStoryMode()) {
    freshRoom.updateSceneState(parsed.lastBgm(), parsed.lastLoc(),
        parsed.lastOutfit(), parsed.lastTime());
}
```
게이트가 절대 열리지 않는다 — ChatRoom.java:484 `public boolean isStoryMode() { return this.chatMode == ChatMode.STORY; }`인데, 이 서비스는 STORY를 입구에서 되돌려 보낸다. ChatStreamService.java:174-181:
```java
// ── [V2 분리] STORY 모드는 ChatStreamServiceV2가 담당 — 방어적 가드 ──
ChatRoom modeCheck = chatRoomRepository.findById(roomId)...
if (modeCheck.isStoryMode()) {
    log.warn("⚠️ [V1-STREAM] STORY V2 room routed to V1 service. roomId={}", roomId);
    sendSseError(emitter, "INVALID_ROUTE", "STORY 모드는 V2 엔드포인트를 사용해야 합니다.");
    return;
}
```
더 결정적으로 updateSceneState 자체가 STORY를 거부한다 — ChatRoom.java:656-657 / :693-694 두 오버로드 모두 첫 줄이 `requireSandbox();`다. 즉 **게이트가 참이 되면 오히려 예외가 나야 정상**인, 논리적으로 뒤집힌 조건이다.

올바른 형태가 같은 파일에 있다 — :301-304 (메인 채팅 턴):
```java
if (ChatModePolicy.supportsSceneDirection(freshRoom.getChatMode())) {  // [이관]
    freshRoom.updateSceneState(parsed.lastBgm(), parsed.lastLoc(),
        parsed.lastOutfit(), parsed.lastTime());
}
```
ChatModePolicy.java:71-73 `return mode == ChatMode.STORY || mode == ChatMode.SANDBOX || mode == ChatMode.THEATER;` — SANDBOX 포함. 주석의 `[이관]` 표식은 이 파일의 다른 게이트들이 isStory→정책으로 일괄 이관될 때(:296, :301, :311, :316에 동일 표식) **이 두 지점만 빠졌다**는 뜻이다. 그래서 :679와 :794(지켜보기·시간넘기기)는 아예 무조건 호출로 남아 있다.

**도달성이 이 원자를 P3로 낮추는 근거** — 엔드포인트는 살아 있으나 프론트 호출처가 소멸했다. StoryController.java:120-137 `POST /story/rooms/{roomId}/events/select`가 소유권+레이트리밋만 걸고 존재하고, FE 래퍼도 존재한다(UseChatStream.js:23 `export async function sendEventSelectStream(...)`). 그런데 ChatPage.jsx:26과 ChatPageV2.jsx:40이 **import만 하고 실제 호출이 0건**이다(grep `sendEventSelectStream(` → 두 페이지 모두 결과 없음). 이유가 코드에 적혀 있다 — ChatPage.jsx:1848-1850:
```js
// [v3 Fix] 이벤트 카드 선택 → sendAutoDirectorResponse 기반 원샷 응답
// Bug 1 Fix: sendEventSelectStream → sendAutoDirectorResponse (ONGOING 완전 제거)
```
즉 UI 도달 불가, 인증된 API 직접 호출로만 도달 가능.

**수정안**

§G-2 작업에 흡수하는 것이 옳다(아래 productDecisionRisk). 지금 단독으로 처리한다면 대조군과 동일하게 맞춘다 — ChatStreamService.java:564:
```java
if (ChatModePolicy.supportsSceneDirection(freshRoom.getChatMode())) {
    freshRoom.updateSceneState(parsed.lastBgm(), parsed.lastLoc(),
        parsed.lastOutfit(), parsed.lastTime());
}
```
주의 2가지: (1) 판정 대상을 `jpa.room()`이 아니라 TX 안에서 다시 읽은 `freshRoom`으로 바꿀 것 — :301의 올바른 형태가 그렇게 돼 있고, jpa.room()은 TX 밖 스냅샷이다. (2) supportsSceneDirection이 THEATER에도 true를 주지만 극장은 이 서비스를 타지 않으므로 무해하다.

**다만 사문 엔드포인트를 되살릴 가치가 있는지 먼저 판단할 것.** 프론트가 이미 sendAutoDirectorResponse로 갈아탄 경로이므로, 고치는 것보다 `/events/select` 엔드포인트 + sendEventSelectStream 서비스 메서드 + FE 래퍼를 **세트로 삭제**하는 편이 §G-4('데드 코드 일괄') 정신에 맞는다. 삭제 시 FE의 미사용 import(ChatPage.jsx:26, ChatPageV2.jsx:40)도 함께 제거.

**제품 결정 연동**: **§G-2·§G-4에 흡수되는 항목.** §G-2가 "V1 STORY 모드 트랙 — 진입 전멸, V2 완전 대체. ChatPage=SANDBOX 전용 선언, **isStoryMode 분기**·에너지 2배 계산 제거"를 🔴삭제로 확정했다. 이 게이트가 정확히 그 'isStoryMode 분기'이며, **블록 D의 작업 항목 자체와 같은 것**이다. 따라서 별건 버그픽스로 올리는 것보다 §G-2 체크리스트에 좌표(ChatStreamService.java:564)를 적어 넣는 편이 낫다.

동시에 §G-4('데드 코드 일괄')와도 얽힌다 — 엔드포인트 자체가 프론트 호출처를 잃은 사문이므로 삭제 후보다. §G-4 목록에 `/events/select` 체인은 아직 등재돼 있지 않으니 **추가 등재를 제안한다**.

반대로 §G 재해석 #13("디렉터 3분기 카드 — 골격 유지, 고정 3톤→맥락 가변 제안 + energyCost 서버 판정")이 이벤트 카드를 존치·개선 대상으로 잡았으므로, 이벤트 카드 UI가 살아남는 것과 이 백엔드 경로가 죽는 것은 별개다(카드는 sendAutoDirectorResponse로 처리된다). 삭제 판단 시 이 구분을 혼동하지 말 것.

**❓ 결정 필요**: `POST /story/rooms/{roomId}/events/select` 엔드포인트를 (a) 게이트만 고쳐 살려 둘 것인가, (b) 프론트 호출처가 이미 sendAutoDirectorResponse로 이관됐으니 백엔드·FE 래퍼를 세트로 삭제할 것인가? docs/13 §E-5도 이 경로의 `detail` 텍스트가 모더레이션을 통과하지 않는다고 지적했으므로(같은 엔드포인트), 삭제하면 그 결함도 함께 소멸한다 — (b)의 부수 이득.

---

### E-4.17.b. 자동 디렉터 응답 턴의 씬 상태 저장이 죽은 isStoryMode() 게이트에 갇혀, INTERLUDE/TRANSITION/AWAY 응답 중 발생한 장소·복장·BGM·시간 변화가 영속되지 않는다 (ChatStreamService:1664)

**🔴 잔존** · P2 · ONE_LINE · BE  
`src/main/java/com/spring/aichat/service/stream/ChatStreamService.java:1664-1667 · 올바른 대조군 :301-304 · 무조건 호출 대조군 :679, :794`

**근거**

ChatStreamService.java:1660-1668 (sendAutoDirectorResponse의 TX-2) —
```java
} else {
    // INTERLUDE/TRANSITION: 일반 스탯 적용 + constraint 클리어
    applyStatChanges(freshRoom, parsed.statChanges(), effectiveSecretMode);
    freshRoom.clearDirectorInterlude();
}

if (jpa.room().isStoryMode()) {
    freshRoom.updateSceneState(parsed.lastBgm(), parsed.lastLoc(),
        parsed.lastOutfit(), parsed.lastTime());
}
```
E-4.17.a와 동일한 죽은 게이트다 — 진입부 :177-181이 STORY를 INVALID_ROUTE로 되돌려 보내므로 isStoryMode()는 이 서비스 안에서 항상 false. 게다가 ChatRoom.java:657/:694의 updateSceneState는 첫 줄이 `requireSandbox();`라, 게이트가 참이 되면 예외가 나야 하는 뒤집힌 조건이다.

**E-4.17.a와 달리 이 경로는 라이브다.** FE 실호출 확인:
- ChatPage.jsx:498 `await sendAutoDirectorResponse(roomId, directiveType, eventContext, {`
- ChatPageV2.jsx:635 동일 호출
- 게다가 ChatPage.jsx:1848-1850 주석이 "[v3 Fix] 이벤트 카드 선택 → sendAutoDirectorResponse 기반 원샷 응답 / Bug 1 Fix: sendEventSelectStream → sendAutoDirectorResponse"라고 적어, **이벤트 카드 트래픽까지 전부 이 경로로 넘어왔다.**
- 백엔드 엔드포인트 StoryController.java:96-114 `POST /story/rooms/{roomId}/director/auto-respond`.

유실되는 것: parsed.lastBgm / lastLoc / lastOutfit / lastTime. 즉 자동 디렉터 응답 도중 LLM이 캐릭터를 다른 장소로 옮기거나 복장·BGM·시간대를 바꿔도 ChatRoom에 반영되지 않는다. 유저는 그 턴의 SSE 응답으로는 변화를 보지만, 새로고침하거나 다음 턴 프롬프트가 조립될 때 방 상태는 이전 값 그대로다 → 캐릭터가 이전 장소로 되돌아간 것처럼 보이고 프롬프트의 장소/복장 컨텍스트도 어긋난다.

부분적으로만 저장된다는 점이 진단을 어렵게 만든다 — 디렉터 지시문의 환경값은 별도 경로로 이미 반영된다. :1525-1527 / :1535-1536 / :1545-1547 applyDirectiveToRoom:
```java
if (interlude.environment() != null) {
    var env = interlude.environment();
    room.updateSceneState(env.bgm(), null, null, env.time());
}
```
즉 **지시문이 정한 BGM·시간은 반영되고, 그 후 캐릭터 응답이 만들어낸 장소·복장 변화만 소실된다.**

형제 경로들은 정상이다 — :679 (지켜보기, 주석 "지켜보기에서도 씬 상태는 업데이트(장소 이동 등)")와 :794 (시간 넘기기)는 게이트 없이 무조건 호출한다. :301-304 (메인 턴)는 `ChatModePolicy.supportsSceneDirection(...)`을 쓴다. 6개 호출 지점 중 이 :1664와 :564 둘만 죽은 게이트에 묶여 있다.

**수정안**

§G-7 범위 확정 후 착수 권장(아래 productDecisionRisk). 살리는 쪽으로 간다면 :301의 정식 형태로 통일한다 — ChatStreamService.java:1664:
```java
if (ChatModePolicy.supportsSceneDirection(freshRoom.getChatMode())) {
    freshRoom.updateSceneState(parsed.lastBgm(), parsed.lastLoc(),
        parsed.lastOutfit(), parsed.lastTime());
}
```
판정 대상을 `jpa.room()`(TX 밖 스냅샷) → `freshRoom`(TX 안 재조회)으로 바꾸는 것까지가 한 세트다.

**부작용 점검이 필수다** — 지금까지 이 턴에서 장소가 저장되지 않았으므로, 살리는 순간 동적 배경 로직과 상호작용이 새로 생긴다. ChatRoom.java:662-676의 updateSceneState(String...) 오버로드가 정적 location enum으로 동적 배경을 클리어하지 않도록 방어하는 주석([Phase 6 hotfix])을 달고 있으니 그 방어가 이 경로에도 유효한지 확인할 것. 그리고 바로 아래 :1687 이하에 이미 동적 장소 전환 처리(`parsed.newLocationName()` 분기)가 별도로 있으므로, 두 경로가 같은 턴에 장소를 두 번 쓰며 충돌하지 않는지 봐야 한다. 이 확인 때문에 코드 변경량은 한 줄이어도 검증 비용은 SMALL에 가깝다.

**제품 결정 연동**: **§G-2와 §G-7 사이에서 결론이 갈린다 — 범위 확정이 선행돼야 한다.**

§G-2(🔴삭제: V1 STORY 트랙)만 실행하면: '죽은 isStoryMode 분기 제거'가 §G-2의 명시 작업("isStoryMode 분기·에너지 2배 계산 제거")이므로 이 게이트는 그때 걷힌다. 다만 **단순 삭제냐 정책 게이트로 교체냐가 갈린다** — 게이트만 지우고 호출을 무조건화하면 정답(:679/:794와 동일)이지만, 게이트째 블록을 지워버리면 씬 상태 저장이 아예 사라져 **결함이 악화된다**. §G-2 작업자에게 이 구분을 명시해야 한다.

§G-7(⚠게이트오프: V1 디렉터 잔여)까지 실행하면: "INTERLUDE/TRANSITION/AWAY **소비 경로(생산자 소멸)**·activeDirector* 필드 정리"가 이 메서드 전체(sendAutoDirectorResponse)를 지우는 범위일 수 있고, 그러면 MOOT가 된다. 단 §G-7이 "'시간 넘기기'만 페이싱 도구로 존치"라고 적은 반면, 현재 프론트는 **이벤트 카드 선택을 이 경로로 처리**하고 있다(ChatPage.jsx:1850) — 즉 §G 재해석 #13("디렉터 3분기 카드 — 골격 유지")이 존치하기로 한 이벤트 카드가 §G-7이 지우려는 경로 위에 올라타 있다. **§G-7과 #13이 실제 코드에서 충돌한다.** 이 충돌은 docs/14 작성 시점에 인지되지 않았을 가능성이 높다.

**❓ 결정 필요**: §G-7('V1 디렉터 잔여 — INTERLUDE/TRANSITION/AWAY 소비 경로 정리')의 실제 범위가 `sendAutoDirectorResponse` 메서드/엔드포인트 삭제까지인가, 아니면 activeDirector* 필드와 미사용 지시문 타입만 정리하는 것인가? 현재 프론트는 이벤트 카드 선택을 이 경로로 처리하므로(ChatPage.jsx:1850) 경로를 통째로 지우면 §G 재해석 #13이 존치하기로 한 '디렉터 3분기 카드'가 함께 죽는다. §G-7과 #13의 범위 충돌을 종원이 정리해 줘야 이 결함의 처분(수정 vs 삭제)이 결정된다.

---

### E-5.1.a. 이벤트 선택 detail이 ContentModerationService를 전혀 통과하지 않음 (게이트 off로 현재는 no-op)

**🔴 잔존** · P3 · ONE_LINE · BE  
`aichat/src/main/java/com/spring/aichat/service/stream/ChatStreamService.java:499-533`

**근거**

ChatStreamService.java:499 `public void sendEventSelectStream(Long roomId, String eventDetail, int energyCost, SseEmitter emitter)` 본문 전체(499~560)에 `contentModerationService.moderate(...)` 호출이 없다. 대조군인 :195-203 sendMessageStream에는 존재한다:
```
ContentModerationService.ModerationVerdict verdict =
    contentModerationService.moderate(userMessage, isSecretCheck);
if (!verdict.passed()) { ... sendSseError(emitter, "CONTENT_BLOCKED", ...); return; }
```
동일 파일 grep 결과 moderate() 호출 지점은 :196 단 1곳뿐.

단, ContentModerationService.java:78-81:
```
public ModerationVerdict moderate(String message, boolean isSecretMode) {
    // [2026-08-06 B안] 게이트 off — 전체 바이패스 (재설계 전까지)
    if (!chatModerationEnabled) { return ModerationVerdict.PASS; }
```
+ application.yml:60 `chat-enabled: ${CHAT_MODERATION_ENABLED:false}` → 지금 호출을 추가해도 무조건 PASS다. 결함 코드(호출 누락)는 현존하나 착취면은 0.

**수정안**

단독 수정하지 말 것. docs/06 §7 '모더레이션 재설계'(Step 2 OpenAI 키 복구 포함) 작업의 체크리스트 항목으로 이월해, 재활성 시 sendEventSelectStream에도 sendMessageStream:195-203과 동일한 moderate 블록(+moderationEventService.recordModeration의 source를 "EVENT"로)을 넣는다. 지금 넣으면 죽은 코드 1블록만 늘어난다.

**제품 결정 연동**: b281477(종원 확정 B안)로 채팅 모더레이션 전면 off — 이 원자만은 현재 실효 없음. 추가로 docs/14 §G #13(디렉터 3분기 카드 골격 유지 + energyCost 서버 판정)에서 events/select 엔드포인트를 어차피 손보게 되므로, 재설계 시점에 함께 처리하는 것이 자연스럽다. 블록 D의 V1 STORY 트랙 삭제(§G #2)는 이 경로를 죽이지 않는다 — ChatModePolicy.supportsEvents(:103-105)가 `mode == ChatMode.SANDBOX`라 SANDBOX 전용이기 때문.

---

### E-5.1.b. 이벤트 detail이 인젝션 가드·길이 제한 없이 ChatRole.SYSTEM으로 영구 저장 → 매 턴 system 롤로 재주입

**🔴 잔존** · P1 · SMALL · BE  
`aichat/src/main/java/com/spring/aichat/service/stream/ChatStreamService.java:531-533 (저장) / :1432-1434 (재주입)`

**근거**

① 클라이언트 완전 제어 입력임: StoryController.java:135 `chatStreamService.sendEventSelectStream(roomId, request.detail(), request.energyCost(), emitter);` + :176 `public record SelectEventRequest(String detail, int energyCost) {}` — 검증 애노테이션 0개. FE도 자유 문자열을 그대로 보냄(LucidChat-Front/src/api/UseChatStream.js:24-26 `_ssePost(url, { detail, energyCost }, ...)`).

② 가드 미통과: ChatStreamService.java grep `checkChatMessage` → :219 한 곳(sendMessageStream)뿐. sendEventSelectStream에는 없음.

③ SYSTEM 롤로 저장: ChatStreamService.java:531-533
```
ChatLogDocument savedLog = chatLogRepository.save(
    ChatLogDocument.hiddenSystem(roomId, "[EVENT_START]\n" + eventDetail));
```
ChatLogDocument.java:222-225 `hiddenSystem(...)` → `.role(ChatRole.SYSTEM)`.

④ 매 턴 system 롤로 재주입: ChatStreamService.java:1429-1434
```
// [Fix 핵심] SYSTEM 나레이션: role="system"으로 전환
case SYSTEM -> messages.add(
    OpenAiMessage.system("[NARRATION] " + chatLog.getRawContent())
);
```

⑤ 영구성: ChatService.java:409-411 `if (doc.getRole() == ChatRole.SYSTEM) throw ... "시스템 메시지는 삭제할 수 없습니다."` — 유저가 지울 수도 없다.

⑥ 설계 전제 붕괴: PromptInjectionGuard.java:184-188 주석 — "채팅 메시지는 user role로 전달되므로 system prompt보다 위험도가 낮다. 차단하면 UX가 크게 저하되므로, 감지 + 로깅만 수행." 이 경로는 user role이 아니라 **system role**이라 그 전제가 성립하지 않는다.

⑦ 길이 상한도 없음 — 임의 길이 텍스트가 방의 모든 후속 턴에 영구 부착(토큰 비용 증폭).

**수정안**

ChatStreamService.sendEventSelectStream(:499) 진입부, `ChatModePolicy.supportsEvents` 검사 직후·TX-1 앞에 3단 방어를 추가:
1) 길이·공백 검증 — `if (eventDetail == null || eventDetail.isBlank() || eventDetail.length() > 300) { sendSseError(emitter, "BAD_REQUEST", "..."); return; }` (상수는 이벤트 카드 detail 실측 최대 기준으로).
2) 인젝션 가드 — `injectionGuard.checkChatMessage(eventDetail, username)` 호출. **여기서는 sendMessageStream처럼 로깅만 하지 말고 CRITICAL이면 차단**할 것: 저장 롤이 SYSTEM이라 user role 전제가 깨지므로 정책이 달라야 한다. `moderationEventService.recordInjection(..., "EVENT", ...)`로 적재.
3) 근본 방어(권장·별도) — `ChatLogDocument.hiddenSystem` 대신 `injectionGuard.encapsulate("Event Detail", eventDetail)`(PromptInjectionGuard Layer 2, :222 부근)로 감싸 저장하거나, `hiddenUser`가 아닌 전용 role/마커를 도입해 system 채널 특권을 주지 않는다.
동일 결함이 형제 경로에도 있음 — `sendAutoDirectorResponse(:1568)`의 `eventContext`가 :1594-1596 `room.setDirectorInterlude(eventContext, "상황: " + eventContext + " ...")`와 :1622 `ChatLogDocument.system(roomId, eventContext)`로 동일하게 흐른다(StoryController:113 AutoRespondRequest). 같은 커밋에서 함께 막을 것.

**제품 결정 연동**: b281477의 채팅 모더레이션 off는 **이 원자에 영향 없음** — PromptInjectionGuard는 별개 컴포넌트이며 어떤 플래그로도 게이트되지 않는다(grep 확인). docs/14 §G #13이 events/select를 재작업 대상으로 잡고 있으므로(energyCost 서버 판정) 같은 커밋에 묶는 것이 경제적이다. 블록 D의 V1 STORY 삭제로 소멸하지 않음(SANDBOX 전용 경로). docs/16(시크릿=핵심 BM) 관점에서 system 롤 자유 주입은 나이 게이트·수위 통제를 우회할 수 있는 채널이라 런칭 전 처리 권장.

**❓ 결정 필요**: 인젝션 CRITICAL 감지 시 이벤트 선택을 '차단'할 것인가, 기존 채팅과 동일하게 '로깅만' 할 것인가? (system 롤이라 차단 권장하나, 이벤트 카드 문구가 오탐될 경우 UX 손상 — 종원 판단)

---

### E-5.2.a. 완성 캐릭터 PATCH /texts가 UGC 미성년 하드 키워드 게이트를 우회 (형제 경로엔 전부 있음)

**🔴 잔존** · P1 · ONE_LINE · BE  
`aichat/src/main/java/com/spring/aichat/service/ugc/UgcCharacterService.java:87-95`

**근거**

UgcCharacterService.java:87-95 전문:
```
@Transactional
public void updateTexts(String username, Long characterId, UgcDtos.UpdateTextsRequest req) {
    Character character = ownedUgc(username, characterId);
    character.updateUgcTexts(req.name(), req.tagline(), req.personality(), req.tone(), req.firstGreeting());
    // [2026-07-31 난이도] 무료 편집 — 무효값·null은 유지(NORMAL도 명시값)
    var difficulty = ...fromStringOrNull(req.difficulty());
    if (difficulty != null) character.updateDifficulty(difficulty);
}
```
`moderationService`가 이 클래스에 **주입조차 되어 있지 않다**(:38-42 필드 목록: characterRepository, userRepository, ugcWorldRepository, vlmPrefilterService, routineGenerationService — UgcModerationService 없음).

형제 경로 대조(전부 게이트 있음):
- 생성 진입: CharacterCreationService.java:107-114 (컨셉 길이 + name 50자) 및 UgcModerationService.assertRawConceptAllowed 호출 경로
- **동일 성격의 초안 편집**: CharacterCreationService.updateProfileDraft(:267-273)
```
String combined = String.join(" ", List.of(nz(req.name()), nz(req.tagline()), nz(req.personality()), nz(req.tone()), ...));
moderationService.assertRawConceptAllowed(combined);
```
- 월드 텍스트 수정: UgcWorldService.java:402 `moderationService.assertRawConceptAllowed(combined.toString());`
- 월드 장소 추가: UgcWorldService.java:454 동일

차단 대상: UgcModerationService.java:58-68 HARD_BLOCK_EN(loli/shota/child/preteen/toddler) + HARD_BLOCK_KO(초등학생/유치원생/중학생/미성년).

착취 경로: 위저드에서 게이트를 통과하는 컨셉으로 캐릭터를 완성 → 완성 화면 인라인 수정(PATCH /ugc/characters/{id}/texts)으로 personality/tone/firstGreeting을 미성년 시그널 텍스트로 교체. 컨트롤러(CharacterCreationController.java:239-247)에도 `guardRate` 호출조차 없다(형제 엔드포인트 :230-236 secretRequest에는 있음).

**수정안**

UgcCharacterService에 `private final UgcModerationService moderationService;`를 주입하고, updateTexts(:88) 본문 첫 줄에 `moderationService.assertRawConceptAllowed(String.join(" ", nz(req.name()), nz(req.tagline()), nz(req.personality()), nz(req.tone()), nz(req.firstGreeting())));`를 추가. CharacterCreationService.updateProfileDraft(:268-273)의 조립 방식을 그대로 복사하면 된다(nz 헬퍼 포함). 덤으로 CharacterCreationController:239의 updateTexts에도 형제 엔드포인트와 동일하게 `guardRate(authentication)` 추가 권장.

**제품 결정 연동**: b281477은 '**UGC 빌더의 미성년 키워드 게이트는 별개·유지**'라고 커밋 메시지에 명시했다. 즉 채팅 모더레이션 off로 무의미해지지 **않는다** — 오히려 채팅 게이트가 꺼진 지금 UGC 게이트가 유일한 미성년 방어선이라 상대적 중요도가 올라간다. docs/16(시크릿=핵심 BM, 법적 그라디언트·PG 심사)와 정면으로 맞물리는 컴플라이언스 항목. 블록 D와 무관.

---

### E-5.2.b. PATCH /texts가 PUBLIC·PENDING_PUBLIC 상태를 무시 → 승인 후 공개 캐릭터 내용 무제한 교체(재심사 없음)

**🔴 잔존** · P1 · SMALL · BE  
`aichat/src/main/java/com/spring/aichat/service/ugc/UgcCharacterService.java:87-95`

**근거**

updateTexts(:87-95)에 visibility 검사도, reviewStatus 되돌림도 없다(위 E-5.2.a 발췌 참조 — 본문 4줄이 전부).

**같은 클래스의 바로 아래 메서드는 정확히 이 TOCTOU를 막고 있다** — UgcCharacterService.linkWorld(:106-131):
```
// [리뷰 픽스] 공개 심사 중 월드 교체 차단 — 관리자가 상세에서 본 월드와 판정 시점 월드가
// 달라지는 TOCTOU(미심사 월드가 승인·공개) 방지. 심사 취소 후 변경 가능.
if (character.getVisibility() == CharacterVisibility.PENDING_PUBLIC) {
    throw new BadRequestException("공개 심사 중에는 세계관을 변경할 수 없어요. ...");
}
...
if (character.getVisibility() == CharacterVisibility.PUBLIC
    && world.getReviewStatus() != WorldReviewStatus.APPROVED) {
    throw new BadRequestException("공개된 캐릭터에는 검수 승인된 세계관만 연결할 수 있어요.");
}
```
즉 '월드 텍스트'는 막혀 있는데 '캐릭터 본문 텍스트(성격·말투·첫인사)'는 무방비다. 비대칭이 명확.

어드민 재심사로도 잡히지 않음: AdminUgcReviewService.queue(:64-74)는 `findByVisibilityOrderByIdAsc(PENDING_PUBLIC)` + `findBySecretReviewStatusOrderByIdAsc(PENDING)` 두 집합만 담는다. PUBLIC 캐릭터를 텍스트 교체해도 큐에 재진입하지 않는다.

착취 시나리오: 무난한 설정으로 공개 승인 획득 → PATCH /texts로 personality/tone/firstGreeting을 심사 불가 내용으로 전면 교체 → 공개 상태 유지 + 재심사 없음 + 어드민 인지 수단 없음.

**수정안**

UgcCharacterService.updateTexts(:88)에 상태 정책을 추가. 3안 중 택1(오너 판단 필요, openQuestion 참조):
(A) 심사 중 잠금 + 공개 시 자동 재심사: `if (character.getVisibility() == CharacterVisibility.PENDING_PUBLIC) throw new BadRequestException("공개 심사 중에는 설정을 수정할 수 없어요. 심사 취소 후 변경해 주세요.");` (linkWorld:108-111 문구 재사용) + `if (character.getVisibility() == CharacterVisibility.PUBLIC) { character.requestPublish(); }` 로 PENDING_PUBLIC 회귀시켜 큐 재진입.
(B) 공개 캐릭터는 텍스트 수정 자체를 금지(언퍼블리시 후 수정).
(C) 수정은 허용하되 심사 대상 필드(personality/tone/firstGreeting)만 PENDING 회귀, 표시 필드(tagline)는 자유.
어느 안이든 Character 엔티티는 이미 requestPublish()(Character.java:844-850)를 갖고 있어 추가 도메인 메서드가 거의 필요 없다. FE(StudioCreateFlow.jsx:2201 handleSaveTexts)는 400/상태변경 응답 처리 문구만 추가하면 된다.

**제품 결정 연동**: 블록 D와 무관. 단 docs/16(시크릿 모드가 핵심 BM으로 승격, PG 가맹 심사·법적 그라디언트) 및 docs/14 §D 행정 체크리스트 2주차 'PG 가맹 심사(시크릿 완전 게이팅 상태로)'와 직결 — '승인 후 내용 교체 가능'은 심사 체계 자체의 신뢰를 무너뜨리는 구조라 런칭 전 필수. UGC 25E(블록 C) BM과도 상호작용: 수정마다 재심사를 걸면 창작자 UX 마찰이 생기므로 정책 선택이 필요하다.

**❓ 결정 필요**: 공개 승인된 캐릭터의 텍스트 수정 정책을 무엇으로 확정할지 — (A) 수정 허용+자동 재심사 회귀(공개 일시 중단), (B) 공개 중 수정 전면 금지, (C) 심사 대상 필드만 회귀. 창작자 UX(자주 다듬고 싶어함) vs 심사 신뢰성의 트레이드오프라 오너 결정 사항.

---

### E-5.3.a. APPROVED 월드에 장소를 추가해도 reviewStatus가 APPROVED로 유지 → 미검수 장소 설명이 공개 캐릭터 프롬프트에 주입

**🔴 잔존** · P2 · SMALL · BE  
`aichat/src/main/java/com/spring/aichat/service/ugc/UgcWorldService.java:441-486`

**근거**

addLocation(:441-486)에 reviewStatus를 건드리는 코드가 전무하다. TX 본문(:456-479)은 락→requireNotUnderReview→상한검사→키발급→에너지 차감→`locationRepository.save(UgcWorldLocation.createGenerating(...))`로 끝나고 `worldRepository.save(world)`조차 호출하지 않는다.

대조군 — updateWorld(:395-435)는 리셋한다. UgcWorld.java:112-126:
```
public void updateTexts(String name, String intro, String lore, String moodTags) {
    ...
    if (changed && reviewStatus != WorldReviewStatus.NONE) {
        this.reviewStatus = WorldReviewStatus.NONE;
    }
}
```
주석도 명시(UgcWorldService.java:395): "설정 텍스트 수정 — 무료. 판정 이력(APPROVED/REJECTED)은 NONE으로 리셋(재검수 대상)." → **텍스트는 리셋, 장소 추가는 리셋 안 함**이라는 비대칭이 확정된다.

유일한 가드는 requireNotUnderReview(:534-539)인데 이는 `PENDING_PUBLIC` 캐릭터가 연결됐을 때만 막는다:
```
if (characterRepository.existsByUgcWorldIdAndVisibility(worldId, CharacterVisibility.PENDING_PUBLIC)) throw ...
```
캐릭터가 **이미 PUBLIC**이면 통과한다.

프롬프트 도달 확인 — CharacterPromptAssembler.java:226-228:
```
if (ugcWorld != null && !ugcWorldLocations.isEmpty()) {
    staticBuilder.append(buildUgcWorldLocationsBlock(ugcWorld, ugcWorldLocations));
}
```
(:158-165에서 `ugcWorldLocationRepository.findByUgcWorldIdAndActiveTrue...`로 로드 — reviewStatus 무관)

완화 요소: addLocation:454 `moderationService.assertRawConceptAllowed(displayName + " " + description)`로 미성년 하드 키워드는 걸러진다. 즉 '좁은 게이트'는 통과하지만 '②승인 큐 검수'(UgcModerationService.java:24-26 주석의 3층 모더레이션 중 2층)를 건너뛴다.

**수정안**

UgcWorldService.addLocation(:456-479) TX 안, `locationRepository.save(...)` 직전에 월드 판정 리셋을 추가. UgcWorld에 `public void resetReview() { if (reviewStatus != WorldReviewStatus.NONE) this.reviewStatus = WorldReviewStatus.NONE; }`(UgcWorld.java:126 아래)를 추가하고 addLocation에서 `locked.resetReview(); worldRepository.save(locked);` 호출. 동일 처리를 `retryLocation`(:489~)과 장소 삭제/비활성 경로에도 적용할지 함께 검토(설명 텍스트가 바뀌는 경로 전부). 단 리셋만으로는 실효가 없다 — 반드시 E-5.3.b와 함께 수정할 것.

**제품 결정 연동**: 블록 D와 무관. b281477 채팅 모더레이션 off와도 무관(UGC 심사는 별개 게이트). docs/14 §G #5 '복장·장소 관계 해금 게이트 오프 / V1 장소 enum은 동적 배경으로 단계적 일원화'는 **V1 공식 캐릭터의 Location enum** 얘기라 UGC 월드 장소 트랙에는 영향 없음(UGC 장소는 §G 처분 대상 아님). docs/16 PG 심사 관점에서 '검수 승인 표기가 실제 검수 범위와 불일치'는 리스크.

---

### E-5.3.b. [인접 결함] 월드 reviewStatus를 NONE으로 리셋해도 이미 PUBLIC인 캐릭터는 재심사 큐에 재진입하지 않아 리셋이 실효 없음

**🔴 잔존** · P2 · MEDIUM · BE/ADMIN  
`aichat/src/main/java/com/spring/aichat/service/admin/AdminUgcReviewService.java:63-74 / UgcWorldService.java:395-435`

**근거**

AdminUgcReviewService.queue()(:63-74):
```
Set<Character> pending = new LinkedHashSet<>();
pending.addAll(characterRepository.findByVisibilityOrderByIdAsc(CharacterVisibility.PENDING_PUBLIC));
pending.addAll(characterRepository.findBySecretReviewStatusOrderByIdAsc(SecretReviewStatus.PENDING));
```
큐 진입 조건에 `world.reviewStatus`가 전혀 없다. 월드 검수는 캐릭터 심사에 '피기백'되는 구조(UgcWorld.java:18 주석: "독립 공개 기능 없음(캐릭터 공개 심사에 피기백)", AdminUgcReviewService.java:97 buildWorldSection)이므로, 캐릭터가 PUBLIC으로 굳은 뒤에는 월드를 NONE으로 되돌려도 아무 일도 일어나지 않는다.

linkWorld의 APPROVED 요구(UgcCharacterService.java:129-132)는 '연결 시점'만 검사하므로 이미 연결된 월드에는 적용되지 않는다.

결과: PUBLIC 캐릭터에 연결된 APPROVED 월드의 lore/intro를 updateWorld로 전면 교체 → reviewStatus는 NONE이 되지만 캐릭터는 계속 PUBLIC이고, CharacterPromptAssembler:226-228이 새 텍스트를 그대로 주입한다. 어드민은 이 월드가 미검수로 되돌아간 사실을 볼 화면이 없다(queue에 안 뜸).

**수정안**

두 축이 필요하다.
1) 트리거: UgcWorldService.updateWorld(:420-433)와 addLocation(TX)에서 월드 판정이 NONE으로 떨어질 때, 해당 월드에 연결된 **PUBLIC 캐릭터를 전부 PENDING_PUBLIC으로 회귀**시킨다(`characterRepository.findByUgcWorldIdAndVisibility(worldId, PUBLIC)` → 각 `character.requestPublish()`). 소유자에게 NotificationService로 '세계관 수정으로 재심사가 시작되었어요' 통지.
2) 가시성: 위 회귀만으로 queue()에 자동 편입되므로 어드민 화면 변경은 최소. 다만 LucidChat-Admin/src/pages/UgcReviewPage.jsx에 '재심사 사유=월드 수정' 배지를 추가하면 판정 효율이 오른다(선택).
대안(마찰 최소): 회귀 대신 `world.reviewStatus != APPROVED`인 월드의 장소·lore를 CharacterPromptAssembler(:226-228)에서 **주입 제외**하고 마지막 승인 스냅샷만 쓰는 방식. 이쪽이 창작자 UX 마찰은 적으나 스냅샷 저장 컬럼이 필요해 DB 마이그레이션이 든다.

**제품 결정 연동**: E-5.2.b와 동일한 정책 축('승인 후 수정' 처리 원칙)이라 반드시 같은 결정으로 묶어야 한다. 두 곳에서 다른 정책을 쓰면 창작자가 우회 경로를 학습한다(캐릭터 텍스트는 막고 월드 lore는 열어두면 lore로 우회). 블록 D 무관.

**❓ 결정 필요**: E-5.2.b와 통합 판단 — '승인 후 수정'을 (A) 재심사 회귀 (B) 수정 금지 (C) 마지막 승인본 스냅샷 주입 중 무엇으로 통일할지. 캐릭터 텍스트/월드 lore/월드 장소 세 경로에 같은 원칙을 적용해야 우회면이 생기지 않는다.

---

### E-6.1.a. 어드민 프롬프트 인스펙션이 V20 gender를 무시하고 남캐를 1girl로 재구성 (positive 3종)

**🔴 잔존** · P2 · ONE_LINE · BE  
`aichat/src/main/java/com/spring/aichat/service/admin/AdminUgcReviewService.java:235-263`

**근거**

AdminUgcReviewService.prompts()(:249-262)가 성별 인자 없는 오버로드만 호출한다:
```
:252  promptAssembler.goldenShotPositive(concept.appearanceTags(), concept.personaTags(), concept.sceneTags()),
:253  promptAssembler.refinePositive(concept.appearanceTags(), concept.personaTags(), EmotionTag.NEUTRAL, job.getBgColor()),
:254  promptAssembler.refinePositive(concept.appearanceTags(), concept.personaTags(), EmotionTag.JOY, job.getBgColor()),
```
이 오버로드들은 male=false로 위임한다 — UgcPromptAssembler.java:168-170 `return goldenShotPositive(appearanceTags, personaTags, sceneTags, false);` / :187-190 `return refinePositive(appearanceTags, personaTags, emotion, bgColor, false);`
그리고 UgcPromptAssembler.java:266-268:
```
private static String anchor(boolean male) {
    return male ? "1boy, male focus, solo" : "1girl, solo";
}
```

실제 생성 경로는 성별을 반영한다 — UgcPipelineWorker.java:146-149 / :359-366:
```
boolean male = isMaleJob(jobId);
String positive = promptAssembler.goldenShotPositive(concept.appearanceTags(), concept.personaTags(), concept.sceneTags(), male);
var workflow = workflowFactory.buildGoldenShot(positive, "job_" + jobId + "_golden", male);
```
`isMaleJob`(:116-118) = `jobRepository.findById(jobId).map(j -> j.getGenderOrDefault().isMale())`.

prompts()는 이미 `job`을 손에 쥐고 있다(:236-237 `CharacterCreationJob job = jobRepository.findByCharacterId(...)`) — `job.getGenderOrDefault().isMale()` 한 줄이면 되는데 안 쓴다. CharacterCreationJob.java:159-163에 gender 필드와 getGenderOrDefault() 존재.

도달성: 남캐 빌더는 **켜져 있다** — application.yml:178 `male-builder-enabled: ${UGC_MALE_BUILDER_ENABLED:true}`, CharacterCreationService.java:95-97의 차단은 플래그 off일 때만. 어드민 화면도 실제로 이 API를 호출한다 — LucidChat-Admin/src/pages/UgcReviewPage.jsx:37 `setData((await api.get(`/admin/characters/ugc/${id}/prompts`)).data)`.

결과: 남캐 잡의 인스펙션이 실제 생성에 쓰인 프롬프트와 다르다. 이 화면의 존재 이유(주석 :229-232 "최종 프롬프트 = ... 결정적 함수이므로 저장본 없이 제출 시점 값이 정확히 재현된다")가 남캐에 대해 거짓이 된다.

**수정안**

AdminUgcReviewService.prompts()(:235) 안, StructuredConcept 파싱 직후에 `boolean male = job.getGenderOrDefault().isMale();`를 선언하고 :252-254의 세 호출을 4·5인자 오버로드로 교체:
`promptAssembler.goldenShotPositive(concept.appearanceTags(), concept.personaTags(), concept.sceneTags(), male)`,
`promptAssembler.refinePositive(concept.appearanceTags(), concept.personaTags(), EmotionTag.NEUTRAL, job.getBgColor(), male)`,
`promptAssembler.refinePositive(concept.appearanceTags(), concept.personaTags(), EmotionTag.JOY, job.getBgColor(), male)`.
덧붙여 UgcReviewDtos.PromptInspection(:94~)에 `String gender` 필드를 추가하고 UgcReviewPage.jsx에 표시하면 심사자가 어느 앵커로 재구성됐는지 즉시 확인 가능(선택·소).

**제품 결정 연동**: 블록 D 무관. 다만 메모리상 '남캐 4종=지인 위탁 보류' 상태이므로 실제 남캐 UGC 잡의 존재량이 적을 수 있다 — 그러나 플래그 기본값이 true라 유저가 남캐를 만들 수 있고, 만들면 인스펙션이 틀린다. 남캐 트랙 자체를 게이트 오프할 계획이 있다면(§G에는 없음) 우선순위가 내려간다.

---

### E-6.1.b. 프롬프트 인스펙션의 네거티브가 남캐 추가 네거티브·Male LoRA 체인을 반영하지 않음

**🔴 잔존** · P3 · SMALL · BE/ADMIN  
`aichat/src/main/java/com/spring/aichat/service/admin/AdminUgcReviewService.java:257`

**근거**

AdminUgcReviewService.java:257 `workflowFactory.templateNegative(),`
UgcWorkflowFactory.java:249-252:
```
/** [어드민 프롬프트 인스펙션] 템플릿 동결 네거티브 (wf2 node 13 — wf1과 동일 값). */
public String templateNegative() {
    return wf2Template.path("13").path("inputs").path("text").asText();
}
```
템플릿 원본만 읽는다. 그러나 실제 생성 경로는 남캐일 때 네거티브를 **증강**한다 — UgcWorkflowFactory.java:103-118(buildGoldenShot)과 :154(buildRefine):
```
/** [2026-08-04 남캐] male이면 Male_Type LoRA를 그래프에 조건부 체인. */
public ObjectNode buildGoldenShot(String positivePrompt, String filenamePrefix, boolean male) { ... }
...
if (male) {
    ...
    appendMaleNegative(wf);
}
```
즉 남캐 잡의 실제 네거티브 = templateNegative + appendMaleNegative 결과인데, 인스펙션은 앞부분만 보여준다. 또한 Male_Type LoRA 조건부 체인 여부도 화면에 전혀 표시되지 않는다.

**수정안**

UgcWorkflowFactory에 `public String templateNegative(boolean male)` 오버로드를 추가해 male일 때 appendMaleNegative가 붙이는 토큰을 동일 로직으로 이어붙여 반환하고(기존 무인자 오버로드는 `templateNegative(false)`로 위임), AdminUgcReviewService:257을 `workflowFactory.templateNegative(male)`로 교체. 추가로 UgcReviewDtos.PromptInspection에 `boolean maleLoraChained` 같은 필드를 넣어 UgcReviewPage.jsx에 'Male_Type LoRA 주입됨' 배지를 노출하면 튜닝 참조용이라는 화면 목적에 부합한다.

**제품 결정 연동**: E-6.1.a와 동일 — 블록 D 무관, 남캐 트랙 존폐에 종속. 반드시 E-6.1.a와 한 커밋으로 처리할 것(따로 고치면 화면이 반쪽만 정확해져 더 헷갈린다).

---

### E-6.2. 어드민 CS 티켓 목록: 상태+유형 동시 필터 시 type이 조용히 무시됨

**🔴 잔존** · P2 · SMALL · BE  
`aichat/src/main/java/com/spring/aichat/service/support/SupportTicketService.java:103-110`

**근거**

SupportTicketService.java:103-110:
```
@Transactional(readOnly = true)
public Page<SupportTicketSummary> adminList(SupportTicketStatus status, SupportTicketType type, Pageable pageable) {
    Page<SupportTicket> page;
    if (status != null) page = ticketRepository.findByStatusOrderByIdDesc(status, pageable);
    else if (type != null) page = ticketRepository.findByTypeOrderByIdDesc(type, pageable);
    else page = ticketRepository.findAllByOrderByIdDesc(pageable);
    return page.map(SupportTicketSummary::from);
}
```
`status != null`이면 type은 읽히지도 않는다.

컨트롤러는 둘 다 정상 파싱해 넘긴다 — AdminSupportController.java:35-37:
```
SupportTicketStatus st = (status != null && !status.isBlank()) ? parseStatus(status) : null;
SupportTicketType ty = (type != null && !type.isBlank()) ? parseType(type) : null;
return supportTicketService.adminList(st, ty, PageRequest.of(...));
```

어드민 화면이 실제로 둘을 **동시에** 보낸다 — LucidChat-Admin/src/pages/TicketsPage.jsx:21-29:
```
const [status, setStatus] = useState('')
const [type, setType] = useState('')
...
if (status) params.status = status
if (type) params.type = type
```
상태 버튼(:54)과 유형 셀렉트(:61)가 별개 컨트롤이라 운영자는 '진행중 + 버그'를 고를 수 있고, 화면은 유형 셀렉트가 '버그'로 보이는데 결과에는 다른 유형 티켓이 섞여 나온다(무증상 오답).

**수정안**

SupportTicketRepository에 `Page<SupportTicket> findByStatusAndTypeOrderByIdDesc(SupportTicketStatus status, SupportTicketType type, Pageable pageable);`를 추가하고 adminList(:104)를 4분기로 확장: (status&&type) → findByStatusAndType..., (status만) → findByStatus..., (type만) → findByType..., (없음) → findAll.... 분기가 늘어나는 게 싫으면 Specification/`@Query`의 `(:status is null or t.status = :status) and (:type is null or t.type = :type)` 형태로 단일화. AdminAuditController(E-6.3.a)와 같은 패턴이라 한 커밋으로 처리 권장.

---

### E-6.3.a. 어드민 감사 로그: 관리자+액션 동시 필터 시 action이 조용히 무시됨

**🔴 잔존** · P2 · SMALL · BE  
`aichat/src/main/java/com/spring/aichat/controller/admin/AdminAuditController.java:39-49`

**근거**

AdminAuditController.java:39-49:
```
Page<AuditLog> result;
if (actor != null && !actor.isBlank()) {
    result = auditLogRepository.findByActorUsernameOrderByIdDesc(actor.trim(), pageable);
} else if (action != null && !action.isBlank()) {
    result = auditLogRepository.findByActionOrderByIdDesc(action.trim(), pageable);
} else if (targetType != null && !targetType.isBlank() && targetId != null && !targetId.isBlank()) {
    result = auditLogRepository.findByTargetTypeAndTargetIdOrderByIdDesc(targetType.trim(), targetId.trim(), pageable);
} else {
    result = auditLogRepository.findAllByOrderByIdDesc(pageable);
}
```
actor 분기가 action을 완전히 가린다.

어드민 화면이 둘을 동시에 보낸다 — LucidChat-Admin/src/pages/AuditLogPage.jsx:7-17:
```
const [actor, setActor] = useState('')
const [action, setAction] = useState('')
...
if (actor.trim()) params.actor = actor.trim()
if (action.trim()) params.action = action.trim()
const res = await api.get('/admin/audit-logs', { params })
```
두 입력칸(:39, :48)이 나란히 있어 '이 관리자가 한 UGC_UNPUBLISH만'을 조회하려 하면 해당 관리자의 **모든** 액션이 나온다. 감사 도구가 조용히 틀린 결과를 주는 것은 사고 조사 시 오판으로 이어진다.

**수정안**

AuditLogRepository를 `JpaSpecificationExecutor<AuditLog>`로 확장하고 AdminAuditController.list(:29-50)를 Specification 조합(actor/action/targetType/targetId 각각 null-safe AND)으로 교체. 간단히 가려면 `@Query("select a from AuditLog a where (:actor is null or a.actorUsername = :actor) and (:action is null or a.action = :action) and (:targetType is null or a.targetType = :targetType) and (:targetId is null or a.targetId = :targetId) order by a.id desc")` 단일 메서드로 4분기를 통째로 대체하는 편이 낫다(E-6.3.b도 동시에 해소된다). 컨트롤러에 리포지토리를 직접 주입한 구조라 서비스 계층 신설은 불필요.

---

### E-6.3.b. [인접 결함] 같은 else-if 사슬 때문에 targetType/targetId 필터도 actor·action이 있으면 무시됨

**🔴 잔존** · P3 · ONE_LINE · BE  
`aichat/src/main/java/com/spring/aichat/controller/admin/AdminAuditController.java:44-46`

**근거**

AdminAuditController.java:44-46이 사슬의 **세 번째** 분기라, actor 또는 action 중 하나라도 있으면 도달하지 않는다:
```
} else if (targetType != null && !targetType.isBlank() && targetId != null && !targetId.isBlank()) {
    result = auditLogRepository.findByTargetTypeAndTargetIdOrderByIdDesc(targetType.trim(), targetId.trim(), pageable);
```
또한 targetType만 주고 targetId를 안 주면(또는 그 반대) 조건이 통째로 거짓이 되어 전체 목록이 반환된다 — 부분 지정이 '필터 없음'으로 침묵 강등된다.

현재 어드민 UI는 이 두 파라미터를 보내지 않는다(AuditLogPage.jsx:14-16이 actor/action만 구성) → 즉 targetType/targetId는 **API에만 존재하는 미사용 필터**다. 그래서 실사용 피해는 아직 없으나, '특정 캐릭터에 가해진 모든 조치 추적' 같은 감사 시나리오를 붙이는 순간 바로 터진다.

**수정안**

E-6.3.a의 Specification/@Query 단일화 안을 채택하면 자동 해소된다(4개 조건이 각각 독립 AND가 되고, targetType 단독·targetId 단독도 정상 동작). 별도 수정 불필요 — E-6.3.a 수정 시 반드시 이 두 파라미터까지 포함해 설계할 것. 추가로 AuditLogPage.jsx에 대상(타입/ID) 검색칸을 넣을지는 선택.

---

### E-6.4. P0 공개 철회(unpublish) 엔드포인트를 호출하는 어드민 화면이 전무 — 부적절 공개 캐릭터를 내릴 UI가 없음

**🔴 잔존** · P1 · SMALL · ADMIN  
`백엔드 C:/Users/zapza/Desktop/MuseLab/aichat/src/main/java/com/spring/aichat/controller/admin/AdminCharacterController.java:70-77 (엔드포인트 존재) / 어드민 SPA C:/Users/zapza/Desktop/LucidChat-Front/LucidChat-Admin/src (호출처 0건)`

**근거**

엔드포인트는 살아 있다 — AdminCharacterController.java:70-77:
```
/** [2026-07-30 P0] 공개 강제 철회 — 부적절 공개 캐릭터 즉시 내림 (PENDING 불요, PUBLIC 대상). */
@PostMapping("/ugc/{id}/unpublish")
public ResponseEntity<Void> ugcUnpublish(@PathVariable Long id, @RequestBody UgcReviewDtos.UnpublishRequest req, Authentication auth) {
    adminUgcReviewService.unpublish(auth.getName(), id, req == null ? null : req.note());
```

호출처 검색 결과 0건: `grep -rn "unpublish" LucidChat-Admin/src LucidChat-Front/src` → **출력 없음**. (어드민 SPA는 24개 파일 규모이며 pages/ 14개 전수 확인)

워크어라운드가 있어 보이지만 실효 없음 — CharactersPage.jsx:35 `api.post(`/admin/characters/${id}/visibility`, { [field]: value })`는 AdminCharacterService.updateVisibility(:32-44)로 가고, 이 메서드는 `hidden`/`storyAvailable`/`theaterAvailable`만 건드린다. `CharacterVisibility`(PUBLIC/PRIVATE)는 **손대지 않는다**.

그런데 실제 접근 차단은 visibility로만 이뤄진다 — Character.java:782-784:
```
public boolean isAccessibleBy(Long userId) {
    return visibility.isPubliclyVisible() || isOwnedBy(userId);
}
```
그리고 ChatStreamService.java:190 `blockIfUgcInaccessible(roomForCheck, emitter)`가 이 판정에 수렴한다(Character.java:876-878 주석: "…isAccessibleBy로 수렴하므로 신규 노출은 이 전환만으로 차단된다. 타 유저의 기존 방은 채팅 전송 경로의 접근 재검증이 막는다").
→ 즉 `hidden=true`로 신규 노출은 가릴 수 있어도 **타 유저가 이미 만든 방에서의 대화는 계속된다**. 운영자가 지금 부적절 캐릭터를 완전히 내리려면 DB 직접 수정이나 curl 수동 호출밖에 없다.

또한 unpublish에만 있는 부수효과(AdminUgcReviewService.java:214-226: 감사로그 UGC_UNPUBLISH 기록 + notifyOwner 통지)도 함께 실행되지 않는다.

**수정안**

백엔드 무변경. LucidChat-Admin에 호출 UI를 추가한다. 최소안: `src/pages/CharactersPage.jsx`의 각 행에 `visibility === 'PUBLIC' || 'PENDING_PUBLIC'`일 때만 보이는 '공개 철회' 버튼을 추가하고, 이미 존재하는 `src/components/ReasonModal.jsx`로 사유를 받아 `api.post(`/admin/characters/ugc/${id}/unpublish`, { note })` 호출 후 목록 재조회. (ReasonModal은 UgcReviewPage의 반려 사유 입력에 쓰이는 기존 컴포넌트라 그대로 재사용 가능.) 전제: `/admin/characters` 목록 응답 DTO(CharacterAdminResponse)에 visibility가 실려야 한다 — 없으면 필드 1개 추가(BE 소). 권장안: UgcReviewPage에 'PUBLIC 캐릭터' 탭을 신설해 승인 큐와 같은 화면에서 사후 조치까지 처리.

**제품 결정 연동**: 블록 D 무관. docs/16(시크릿=핵심 BM) + docs/14 §D 행정 체크리스트(PG 가맹 심사·청소년보호책임자 표기)와 직결 — 신고 접수 후 즉시 내릴 수단이 UI로 없다는 것은 운영 대응 SLA를 지킬 수 없다는 뜻이다. E-5.2.b(승인 후 내용 교체)와 짝을 이루는 결함: 교체를 막지 못한다면 최소한 내릴 수단은 있어야 하는데 그것도 없다. 두 건을 함께 처리하면 리스크가 실질적으로 닫힌다.

---

### E-6.5. 어드민 CS 대화 로그가 가장 오래된 100건만 로드 (ASC 정렬 + page 0 고정 + 페이지네이션 UI 없음)

**🔴 잔존** · P2 · SMALL · ADMIN/BE  
`ADM/src/pages/UserDetailPage.jsx:97-98 + C:/Users/zapza/Desktop/MuseLab/aichat/src/main/java/com/spring/aichat/controller/admin/AdminChatLogController.java:27-33`

**근거**

프론트 — UserDetailPage.jsx:97-98:
```
const res = await api.get(`/admin/chatlogs/rooms/${roomId}/logs`, { params: { page: 0, size: 100 } })
setLogView({ roomId, logs: res.data.content })
```
`page: 0` 하드코딩이고, 렌더 지점(:407 `logView.logs.map(...)`, :416 빈 상태 문구)에 다음/이전 페이지 컨트롤이 전혀 없다. 전체 건수(totalElements)도 표시하지 않아 **운영자는 잘렸다는 사실 자체를 모른다**.

백엔드 — AdminChatLogController.java:27-33:
```
@GetMapping("/rooms/{roomId}/logs")
public Page<AdminChatLogResponse> roomLogs(@PathVariable Long roomId,
                                           @RequestParam(defaultValue = "0") int page,
                                           @RequestParam(defaultValue = "50") int size) {
    return adminChatLogService.roomLogs(roomId,
        PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 200), Sort.by(Sort.Direction.ASC, "createdAt")));
}
```
`Sort.Direction.ASC` + page 0 → 정확히 '가장 오래된 100건'. size 상한은 200이므로 무한정 키울 수도 없다.

영향: CS 문의는 대개 '방금 있었던 일'에 대한 것인데, 운영자가 보는 건 그 방의 최초 대화 100건이다. 오래된 방일수록 완전히 무관한 로그만 보인다.

**수정안**

두 갈래 중 택1.
(A) 최소·즉효: UserDetailPage.jsx:97 호출에 `sort: 'desc'`(또는 `latest: true`) 파라미터를 추가하고, AdminChatLogController.roomLogs(:27)에 `@RequestParam(defaultValue="asc") String sort`를 받아 `Sort.by("desc".equalsIgnoreCase(sort) ? DESC : ASC, "createdAt")`로 분기. 프론트는 받은 배열을 화면 표시 직전 reverse해 시간순으로 렌더.
(B) 정공법: UserDetailPage에 페이지네이션(또는 '이전 대화 더 보기' 무한스크롤)을 추가하고 `res.data.totalElements`를 헤더에 노출. 기본 진입은 마지막 페이지(가장 최근)로.
CS 도구 성격상 (A)로 즉시 막고 (B)를 백로그로 두는 것이 합리적이다.

---

### E-7.1.a. OAuth 로그인 시 users.email UNIQUE 충돌 → DataIntegrityViolationException 500, 해당 유저는 그 provider로 영구 로그인 불가

**🔴 잔존** · P1 · MEDIUM · BE  
`aichat/src/main/java/com/spring/aichat/service/auth/OAuth2LoginSuccessHandler.java:132-140 / :163-172 / :193-202`

**근거**

UNIQUE 제약 확인 — User.java:16-20:
```
@Table(name = "users", indexes = {
    @Index(name = "idx_user_username", columnList = "username", unique = true),
    @Index(name = "idx_user_email", columnList = "email", unique = true),
    @Index(name = "idx_user_ci_hash", columnList = "ci_hash", unique = true)
})
```
(prod는 ddl-auto=validate이므로 이 유니크 인덱스가 실제 DB에 존재한다는 뜻)

세 upsert 경로 전부 동일 패턴 — OAuth2LoginSuccessHandler.java:132-140 (Google):
```
return userRepository.findByProviderAndProviderId(AuthProvider.GOOGLE, sub)
    .orElseGet(() -> {
        String username = email != null ? email : ("google_" + sub.substring(0, 8));
        if (userRepository.existsByUsername(username)) {
            username = "google_" + sub.substring(0, 12);
        }
        User created = User.google(username, name, email, sub);
        return userRepository.save(created);
    });
```
:163-172 (Kakao), :193-202 (Naver)도 문자열만 다르고 구조 동일.
**username 충돌은 existsByUsername으로 회피하는데 email 충돌은 검사 자체가 없다.** 그리고 User.google(:148-151)/setUserFields(:207-219)가 email을 그대로 세팅한다.

리포지토리에는 필요한 메서드가 이미 있는데 안 쓴다 — UserRepository.java:15 `boolean existsByEmail(String email);`, :19 `Optional<User> findByEmail(String email);`. 로컬 가입 경로는 쓰고 있다 — AuthService.java:55-57:
```
if (req.email() != null && !req.email().isBlank() && userRepository.existsByEmail(req.email())) {
    throw new BadRequestException("이미 사용 중인 이메일입니다.");
}
```
→ '로컬 가입 시 소셜 이메일 충돌'은 친절히 막는데, **반대 방향(소셜 로그인 시 기존 이메일 충돌)은 500으로 터진다.**

재현 경로 2종(둘 다 실사용에서 흔함):
1) 유저가 email=X로 로컬 가입 → 이후 같은 X 구글 계정으로 소셜 로그인 → findByProviderAndProviderId(GOOGLE, sub) 빈 결과 → INSERT(email=X) → unique 위반 → 500.
2) 구글(X)로 가입해 쓰던 유저가 네이버(같은 X)로 로그인 시도 → 동일 결과.
두 경우 모두 providerId가 저장되지 않으므로 다음 시도에도 findByProviderAndProviderId가 계속 비고 → **매번 500. 영구 잠금.**

@RestControllerAdvice 미포착 확인: 이 클래스는 `implements AuthenticationSuccessHandler`(:46)로 Spring Security 필터 체인에서 실행되므로 DispatcherServlet의 핸들러 예외 처리 밖이다.

부수 관찰(수정 시 주의): upsert 메서드들이 `protected` + `@Transactional`인데 :68-77에서 **같은 빈 내부 자기호출**로 불린다 → 프록시가 적용되지 않아 트랜잭션 경계가 사실상 리포지토리 단위다. 이메일 링크 로직을 넣을 때 원자성을 기대하면 안 된다.

**수정안**

세 upsert의 `orElseGet` 블록을 공통 헬퍼로 통합하고 INSERT 전에 이메일 소유자를 조회하도록 바꾼다. OAuth2LoginSuccessHandler에 다음을 신설:
```
private User upsertSocial(AuthProvider provider, String providerId, String email, String nickname, String usernamePrefix)
```
동작: ① `findByProviderAndProviderId(provider, providerId)` 히트면 반환. ② 미스면 email이 non-blank일 때 `userRepository.findByEmail(email)`로 기존 계정 조회 → 존재하면 **정책 분기**(openQuestion). ③ 없으면 기존대로 신규 생성.
②의 기술적 최소 안전판(정책 확정 전에도 즉시 넣을 것): 기존 계정이 있으면 신규 INSERT를 시도하지 말고 `email = null`로 만들어 생성하거나(username은 이미 `google_<sub>` 폴백 로직이 있음) 명시적 에러 리다이렉트로 빠져나가 **500만은 막는다**. 어떤 경우에도 email UNIQUE에 무방비로 INSERT하지 않는다.
자기호출 문제 해결: upsert 로직을 별도 `@Service`(예: SocialUserUpsertService)로 분리해 주입받으면 @Transactional이 실제로 적용되고, 이메일 조회~INSERT를 한 트랜잭션에 묶을 수 있다. 동시 요청 대비로 INSERT를 try/catch(DataIntegrityViolationException) → findByEmail 재조회 폴백으로 감싸면 레이스까지 닫힌다.

**제품 결정 연동**: 블록 D 무관. docs/16 C-1(성인인증 수리 0순위)과 같은 '로그인/인증 축' 결함이라 같은 세션에서 처리하면 검증 비용이 준다. docs/14 §D 행정 체크리스트(NICE 본인확인·PortOne)도 로그인 정상 동작이 전제.

**❓ 결정 필요**: 동일 이메일의 다른 provider 로그인 시 정책을 무엇으로 할 것인가 — (A) 자동 계정 연동(기존 유저에 provider/providerId를 덮어쓰거나 다중 provider 테이블 도입: UX 최상이지만 이메일 소유 검증이 약하면 계정 탈취 벡터), (B) '이미 구글로 가입된 이메일이에요. 구글로 로그인해 주세요' 안내 후 /login 리다이렉트(가장 안전·구현 최소), (C) 이메일 없이 별도 계정 생성(중복 계정 양산). 보안·CS 부담 트레이드오프라 오너 판단 필요. (B)를 기본값으로 권장.

---

### E-7.1.b. OAuth 성공 핸들러에 예외 처리가 전무 — upsert가 던지면 로그인 화면 대신 원시 500 페이지

**🔴 잔존** · P2 · SMALL · BE  
`aichat/src/main/java/com/spring/aichat/service/auth/OAuth2LoginSuccessHandler.java:57-119`

**근거**

onAuthenticationSuccess(:57-119) 전체에 try/catch가 없다. 실패를 우아하게 처리하는 분기는 딱 두 곳뿐이고 둘 다 '예상된' 케이스다:
- :72-76 알 수 없는 provider → `response.sendRedirect("/login?error=unknown_provider")`
- :82-93 정지 계정 → `/login?error=account_suspended` 리다이렉트 또는 403

반면 :68-71의 upsert 호출, :98-100 `jwtTokenService.issueTokenPair(...)`, :102 쿠키 설정에서 던져지는 런타임 예외는 아무도 잡지 않는다. setUserFields(:216-218)조차 `throw new IllegalStateException("소셜 유저 생성 실패", e)`로 던진다.

이미 `successRedirect` 기반 에러 리다이렉트 패턴(:85-89)이 클래스 안에 존재하므로, 재사용할 코드가 바로 옆에 있는데 쓰이지 않는 상태다.

결과: 유저는 프론트로 못 돌아오고 톰캣 기본 500 페이지를 본다(원인 추적 문자열도 없음). CS 인입 시 어떤 provider·어떤 계정이었는지도 로그로 남지 않는다.

**수정안**

onAuthenticationSuccess(:57) 본문 전체를 try/catch(Exception e)로 감싸고, catch에서 ① `log.error("[OAUTH] Login failed: provider={}", registrationId, e)` ② :85-89의 리다이렉트 패턴을 재사용해 `/login?error=oauth_failed`(+선택적 correlation id)로 보낸다. successRedirect가 비어 있으면 `response.sendError(SC_INTERNAL_SERVER_ERROR, ...)`. 프론트(LucidChat-Front 로그인 페이지)는 `error` 쿼리 파라미터를 이미 unknown_provider/account_suspended로 처리하는 경로가 있으므로 케이스만 하나 추가하면 된다. E-7.1.a와 같은 커밋으로 처리할 것 — a가 정책 분기를 늘리므로 b의 안전망이 함께 있어야 한다.

---

### E-7.2. PATCH /ugc/characters/{id}/texts에 길이 검증이 없어 tone 300자 초과 시 400이 아닌 500 (FE에 maxLength도 없음)

**🔴 잔존** · P2 · SMALL · BE/FE  
`aichat/src/main/java/com/spring/aichat/service/ugc/UgcCharacterService.java:87-95 / 프론트 C:/Users/zapza/Desktop/LucidChat-Front/LucidChat-Front/src/components/studio/StudioCreateFlow.jsx:1768-1782`

**근거**

컬럼 폭 확인 — Character.java:53-54 `@Column(nullable = false, length = 50) private String name;` / :75-76 `@Column(name = "tagline", length = 100)` / :103-104 `@Column(name = "tone", length = 300)`. personality(:97 TEXT)와 firstGreeting(:206 TEXT)은 무제한이라 안전.

검증 부재 — UgcCharacterService.java:87-95 (E-5.2.a 발췌와 동일: 본문 4줄, 길이 검사 0). DTO에도 없다 — UgcDtos.java:59-62 `public record UpdateTextsRequest(String name, String tagline, String personality, String tone, String firstGreeting, String difficulty) {}` — @Size/@Valid 없음. 컨트롤러도 마찬가지 — CharacterCreationController.java:239-247 `@RequestBody UgcDtos.UpdateTextsRequest request`에 `@Valid` 없음.

형제 경로엔 있다:
- UgcWorldService.java:169-183 `validateTextLimits(...)` — name 50 / intro / lore / moodTags 전부 검사 후 BadRequestException
- UgcWorldService.java:405-418 updateWorld — 동일 검사 인라인
- CharacterCreationService.java:112-114 `if (name != null && name.length() > 50) throw new BadRequestException("이름은 50자 이하로 입력해 주세요.");`

★ UI로 실제 도달 가능함이 확정된다 — StudioCreateFlow.jsx CompleteStep 인라인 편집(이 폼이 PATCH /texts를 호출: :2201-2202 `const handleSaveTexts = (texts) => runCharAction(() => updateUgcTexts(completedChar.characterId, texts), sfx.chime);`, StudioApi.js:210-211 `api.patch(`/ugc/characters/${characterId}/texts`, texts)`).
  - 입력칸(:1753-1765)은 `{ key: "name", max: 20 }`, `{ key: "tagline", max: 60 }`으로 maxLength가 걸려 있어 DB 한계(50/100) 아래다 → UI로는 안 터짐.
  - **그러나 textarea 3종(:1768-1782)에는 maxLength가 전혀 없다**:
```
{[{ key: "personality", ...}, { key: "tone", label: "말투", rows: 2 }, { key: "firstGreeting", ...}].map((f) => (
  <textarea value={form[f.key]} rows={f.rows} onChange={...} className="..." />
))}
```
  → '말투'에 4~5문장(>300자)을 쓰고 저장하면 즉시 500. personality/firstGreeting은 TEXT라 무사하고 **정확히 tone만 터진다**(docs/13 서술과 일치).
  - ProfileEditPanel.jsx도 동일 구조(:39-50 TEXTAREA_FIELDS에 max 없음, :222 maxLength는 INPUT_FIELDS에만) — 다만 이쪽은 jobId 기반 PATCH /profile로 가고 그 경로는 JSON 컨셉에 쓰므로 500이 아니다.
  - 직접 API 호출 시엔 name/tagline도 동일하게 500.

**수정안**

BE(필수): UgcCharacterService.updateTexts(:88) 첫머리에 UgcWorldService.validateTextLimits(:169-183) 스타일의 검사를 추가 —
```
if (req.name() != null && req.name().trim().length() > 50) throw new BadRequestException("이름은 50자 이하로 입력해 주세요.");
if (req.tagline() != null && req.tagline().length() > 100) throw new BadRequestException("태그라인은 100자 이하로 입력해 주세요.");
if (req.tone() != null && req.tone().length() > 300) throw new BadRequestException("말투는 300자 이하로 입력해 주세요.");
```
(personality/firstGreeting은 TEXT라 불필요하나, 프롬프트 토큰 폭주 방지용 상한을 둘지는 별도 판단.) 대안으로 UgcDtos.UpdateTextsRequest에 @Size를 달고 컨트롤러(:242)에 @Valid를 붙이는 방식도 가능하나, 형제 코드가 전부 서비스 계층 명시 검증 스타일이라 일관성 측면에서 서비스 검증을 권장.
FE(동반): StudioCreateFlow.jsx:1768-1770의 textarea 배열에 `{ key: "tone", label: "말투", rows: 2, max: 300 }`을 넣고 :1775 textarea에 `maxLength={f.max}` 전달 + 글자수 카운터. ProfileEditPanel.jsx TEXTAREA_FIELDS(:39-50)에도 동일 적용(현재 /profile 경로는 안 터지지만 바인딩 시 같은 컬럼으로 흘러 들어간다).
★ E-5.2.a·E-5.2.b와 **같은 메서드**이므로 반드시 한 커밋으로 묶을 것.

**제품 결정 연동**: 블록 D 무관. b281477 무관. docs/14_assets §6 재작업 금지 목록에 해당 없음(의도된 트레이드오프가 아니라 단순 누락 — 형제 경로가 전부 검증을 갖고 있다는 사실이 '의도되지 않음'의 증거).

---

## F. P3 카피·표시  (17건)

### F-1.a. 극장 생성 4단계 업셀 카드가 Lucid Pass 스탯 포인트를 '최대 40 P'로 광고 — 실제 20P

**🔴 잔존** · P2 · ONE_LINE · FE  
`FE/src/components/theater/TheaterCreateFlow.jsx:741`

**근거**

TheaterCreateFlow.jsx:739-741 (무료 유저 전용 업셀 비교표 중 Pass 열)
```
                              Lucid Pass
                            </div>
                            <div className="text-sm text-amber-100 font-bold mb-0.5">최대 40 P</div>
```
같은 파일 137-139행의 실제 로직은 이미 정확하다:
```
    if (tier === "LUCID_MIDNIGHT_PASS") return { total: 500, perStat: 100, label: "프리미엄" };
    if (tier === "LUCID_PASS") return { total: 20, perStat: 10, label: "표준" };
```
백엔드 정본 TheaterLobbyService.java:84-90:
```
    /** LUCID_PASS (Standard, 14,900원/월): 총 20포인트, 단일 스탯 최대 10 */
    private static final int STANDARD_TOTAL_POINTS = 20;
```
→ 카피(40)와 실제 지급(20)이 2배 괴리. 도달성 확인: 새 로비 StoryTab.jsx:161이 TheaterCreateFlow를 마운트하고 onOpenStore를 넘겨 업셀 CTA 분기가 렌더된다(블록 A R2 이후에도 살아있음). 무료 유저(statTier.total===0)면 step 4에서 이 카드가 항상 보인다(697행 분기).

**수정안**

파일 …\src\components\theater\TheaterCreateFlow.jsx.
최소 수정: 741행 문자열 치환.
- 현재: `<div className="text-sm text-amber-100 font-bold mb-0.5">최대 40 P</div>`
- 교정: `<div className="text-sm text-amber-100 font-bold mb-0.5">최대 20 P</div>`
권장(재발 방지): STAT_AXES 정의(66-72행) 아래에 티어 상수를 신설하고 statTier useMemo(135-140행)와 업셀 카피가 같은 출처를 보게 한다.
```js
// 백엔드 TheaterLobbyService 티어 상수와 1:1 (FREE 0/0 · LUCID_PASS 20/10 · LUCID_MIDNIGHT_PASS 500/100)
const STAT_TIER_LIMITS = {
  LUCID_MIDNIGHT_PASS: { total: 500, perStat: 100, label: "프리미엄" },
  LUCID_PASS:          { total: 20,  perStat: 10,  label: "표준" },
  FREE:                { total: 0,   perStat: 0,   label: null },
};
```
그 뒤 741행을 `최대 {STAT_TIER_LIMITS.LUCID_PASS.total} P`로, statTier를 `STAT_TIER_LIMITS[user?.subscriptionTier] ?? STAT_TIER_LIMITS.FREE`로 교체.

**제품 결정 연동**: 낮음 — docs/14 §C #6이 '극장 유지'를 명시했고 §G ✅유지 목록에 '극장 세이브 슬롯(극장 전용 문법 격리)'이 있어 극장 생성 플로우는 존치 확정. 블록 C(BM)는 UGC 25E·부스트 모델 치환만 다루고 구독 티어별 스탯 포인트는 건드리지 않는다. 단 F-1.c의 미드나잇 정책값(500 vs 40)이 확정되기 전에는 미드나잇 열을 카피에 추가하지 말 것.

---

### F-1.b. 같은 업셀 카드의 스탯 이름 5종이 실제 UI 라벨과 불일치 (위트/대담함/공감 vs 입담/담력/감수성)

**🔴 잔존** · P3 · ONE_LINE · FE  
`FE/src/components/theater/TheaterCreateFlow.jsx:720`

**근거**

TheaterCreateFlow.jsx:718-722 (업셀 설명문):
```
                          <div className="text-xs text-white/70 leading-relaxed mb-4 max-w-[320px]">
                            Lucid Pass 가입자는 시작 단계에서 매력 / 위트 / 대담함 / 지성 / 공감
                            5개 스탯에 자유롭게 포인트를 분배할 수 있습니다.
                          </div>
```
같은 파일 66-72행의 실제 렌더 라벨(STAT_AXES):
```
const STAT_AXES = [
  { key: "charm", label: "매력", ... },
  { key: "wit", label: "입담", ... },
  { key: "boldness", label: "담력", ... },
  { key: "intellect", label: "지성", ... },
  { key: "empathy", label: "감수성", ... },
];
```
인터미션 화면도 동일 라벨을 쓴다 — TheaterIntermissionPage.jsx:35-39 STAT_META = 매력/입담/담력/지성/감수성.
→ 유저는 업셀에서 '위트·대담함·공감'을 사고 실제로는 '입담·담력·감수성' 슬라이더를 만난다(같은 컴포넌트 안에서 한 단계 차이).

**수정안**

파일 …\src\components\theater\TheaterCreateFlow.jsx 720행.
- 현재: `                            Lucid Pass 가입자는 시작 단계에서 매력 / 위트 / 대담함 / 지성 / 공감`
- 교정: `                            Lucid Pass 가입자는 시작 단계에서 매력 / 입담 / 담력 / 지성 / 감수성`
권장(단일 출처화): 720행을 `Lucid Pass 가입자는 시작 단계에서 {STAT_AXES.map((a) => a.label).join(" / ")}`로 바꿔 STAT_AXES가 유일 정본이 되게 한다.

---

### F-1.c. 미드나잇 패스 스탯 포인트가 코드 500/100인데 BE·FE 주석과 클래스 javadoc은 전부 40/20 — 어느 쪽이 정본인지 불명

**🔴 잔존** · P3 · SMALL · BE/FE  
`aichat/src/main/java/com/spring/aichat/service/theater/TheaterLobbyService.java:88-90 / …/LucidChat-Front/src/components/theater/TheaterCreateFlow.jsx:127-134`

**근거**

TheaterLobbyService.java:88-90 — 주석과 값이 정면 충돌:
```
    /** LUCID_MIDNIGHT_PASS (Premium, 24,900원/월): 총 40포인트, 단일 스탯 최대 20 */
    private static final int PREMIUM_TOTAL_POINTS = 500;
    private static final int PREMIUM_PER_STAT_MAX = 100;
```
같은 파일 72-77행 매핑 주석도 `LUCID_MIDNIGHT_PASS → PREMIUM (40p, perStat 20)`, 652-654행 인라인 주석도 `// [Polish] LUCID_MIDNIGHT_PASS = Premium tier (40/20)`.
SubscriptionType.java:16 javadoc도 `- Theater 초기 스탯 분배 40p (perStat 20)  ※ 추후 무제한으로 정책 변경 예정`.
FE도 동일 — TheaterCreateFlow.jsx:127 `//   - LUCID_MIDNIGHT_PASS (24,900원/월, 프리미엄) → 40 / perStat 20`, 134행 `// ⚠️ LUCID_MIDNIGHT_PASS는 추후 "분배 무제한" 정책으로 전환될 예정 — 지금은 임시 40/20.` 인데 바로 다음 137행 코드는 `{ total: 500, perStat: 100 }`.
→ BE 실제 검증값 500/100, 문서·주석 전부 40/20. FE 코드는 BE 값을 따라가고 있으니 런타임 정합은 맞지만, 모든 서술이 반대라 다음 수정자가 확실히 잘못 고친다.

**수정안**

오너가 정본을 확정한 뒤(아래 openQuestion) 한쪽으로 통일한다.
(가) '사실상 무제한' 의도라면 코드 500/100 유지 + 주석 4곳 교정:
- TheaterLobbyService.java:88 → `/** LUCID_MIDNIGHT_PASS (Premium, 24,900원/월): 사실상 무제한 — 총 500포인트, 단일 스탯 최대 100 */`
- TheaterLobbyService.java:73 → `//   - LUCID_MIDNIGHT_PASS → PREMIUM  (500p, perStat 100 — 사실상 무제한)`
- TheaterLobbyService.java:652 → `// [Polish] LUCID_MIDNIGHT_PASS = Premium tier (500/100 — 사실상 무제한)`
- SubscriptionType.java:16 → `- Theater 초기 스탯 분배 사실상 무제한 (500p / perStat 100)`
- TheaterCreateFlow.jsx:127·134 동일 취지로 교정
(나) 40/20이 정본이면 TheaterLobbyService.java:89-90을 `= 40;` `= 20;`으로, TheaterCreateFlow.jsx:137을 `{ total: 40, perStat: 20, label: "프리미엄" }`로 되돌린다.

**제품 결정 연동**: 블록 C(BM)·docs/16 시크릿 BM 승격과 간접 연결 — 미드나잇 패스는 시크릿 상시 개방 티어라 docs/16 피벗에서 가치 제안이 재조정될 수 있다. 스탯 500p는 사실상 '분배 무제한'이므로 이 값이 확정되면 F-1.a의 업셀 비교표에 미드나잇 열을 추가할지도 함께 결정해야 한다.

**❓ 결정 필요**: 미드나잇 패스의 극장 초기 스탯 분배 정본이 (가) 500/100(=사실상 무제한, 현행 코드) 인가 (나) 40/20(=모든 주석·javadoc) 인가? 500/100이 '무제한 전환'의 임시 구현이라면 그대로 두고 주석만 고치면 되고, 실수로 들어간 값이면 40/20으로 되돌려야 한다.

---

### F-1.d. 결제 모달의 'Lucid Pass' 카드가 19,900원으로 표기(실제 14,900원) + BE에 없는 productType 'LUCID_PASS_MONTHLY' 전송

**🔴 잔존** · P1 · SMALL · FE  
`FE/src/components/PaymentModal.jsx:31`

**근거**

PaymentModal.jsx:31 (packages 탭 상품 정의):
```
    { type: 'LUCID_PASS_MONTHLY', name: 'Lucid Pass', price: 19900, desc: 'Monthly premium', emoji: '⭐' },
```
같은 파일 60-61행이 이 값을 그대로 서버로 보낸다:
```
      const prepareRes = await axios.post('/api/v1/payments/ready', {
        productType: product.type,
```
백엔드 정본 ProductType.java:31-32:
```
    LUCID_PASS("루시드 패스", 14900, 0, false),
    LUCID_MIDNIGHT_PASS("루시드 미드나잇 패스", 24900, 0, true);
```
SubscriptionType.java:24도 `LUCID_PASS("루시드 패스", 14900, false)`.
→ ① 가격 카피 19,900 ≠ 실제 14,900. ② enum 값 `LUCID_PASS_MONTHLY` 자체가 BE에 존재하지 않음(grep 결과 0건) → /payments/ready 역직렬화 실패. ③ 미드나잇 패스 상품 카드가 목록에 아예 없음 — 시크릿 상시 개방 티어를 살 수 있는 화면이 없다(docs/16이 시크릿을 핵심 BM으로 승격했는데도).

**수정안**

파일 …\src\components\PaymentModal.jsx PRODUCTS.packages(28-32행) 교체:
- 현재 31행: `{ type: 'LUCID_PASS_MONTHLY', name: 'Lucid Pass', price: 19900, desc: 'Monthly premium', emoji: '⭐' },`
- 교정: `{ type: 'LUCID_PASS', name: '루시드 패스', price: 14900, desc: '부스트 무제한 · 회복 2배 · 최대 100E', emoji: '⭐' },`
- 추가(미드나잇 판매 경로 신설, 성인 전용 게이트 필요): `{ type: 'LUCID_MIDNIGHT_PASS', name: '루시드 미드나잇 패스', price: 24900, desc: '루시드 패스 + 전 캐릭터 시크릿 상시', emoji: '🌙', adultOnly: true },`
가격 문자열은 ProductType.priceKrw를 서버에서 내려받는 구조가 아니면 최소한 위 값으로 하드코딩 정정.

**제품 결정 연동**: docs/16(시크릿 모드 핵심 BM 승격)과 직결 — 미드나잇 패스 판매 카드 추가 여부는 성인인증(C-1) 수리 및 PG 가맹 승인 이후로 미룰지 오너 판단이 필요하다. 가격 정정(19,900→14,900)과 productType 정정은 그와 무관하게 지금 해야 한다.

**❓ 결정 필요**: 미드나잇 패스(24,900원) 상품 카드를 결제 모달에 노출할 것인가? docs/14 §D 로드맵상 PG 심사는 '시크릿 완전 게이팅 상태'로 받는다고 되어 있어, 승인 전 노출은 리스크일 수 있다.

---

### F-2. 인터미션 활동 '대성공' 결과가 무음 — 효과음 분기가 존재하지 않는 outcome 값 "CRIT"을 비교

**🔴 잔존** · P3 · ONE_LINE · FE  
`FE/src/pages/TheaterIntermissionPage.jsx:107`

**근거**

TheaterIntermissionPage.jsx:105-107 (handleActivity 결과 처리):
```
      if (result?.outcome === "FAIL") sfx.thud();
      else if (result?.outcome === "SUCCESS") sfx.chime();
      else if (result?.outcome === "CRIT") sfx.chime();
```
백엔드가 내려주는 실제 값 — TheaterIntermissionService.java:231 `if (roll < 25) outcome = "GREAT_SUCCESS";`, TheaterResponses.java:283 `String outcome,             // FAIL / SUCCESS / GREAT_SUCCESS`, IntermissionCatalog.java:177 `return Map.of("GREAT_SUCCESS", 15, "SUCCESS", 65, "FAIL", 20);`.
같은 FE 파일 43-48행은 이미 GREAT_SUCCESS를 알고 있다:
```
const OUTCOME_CONFIG = {
  GREAT_SUCCESS: {
    label: "대성공!",
    emoji: "💥",
```
→ 시각 표현(라벨 '대성공!'·💥)은 정상이고 오직 효과음 분기만 죽은 문자열. GREAT_SUCCESS는 어느 분기에도 걸리지 않아 최고 등급 결과가 완전 무음이다. 부수적으로 SUCCESS와 CRIT이 같은 sfx.chime()이라, 문자열만 고치면 성공/대성공 청각 차별화도 없다.

**수정안**

파일 …\src\pages\TheaterIntermissionPage.jsx.
최소 수정(107행 한 줄):
- 현재: `      else if (result?.outcome === "CRIT") sfx.chime();`
- 교정: `      else if (result?.outcome === "GREAT_SUCCESS") sfx.boom();`
(`sfx.boom`은 utils/sfx.js:103에 실재 — L11. OUTCOME_CONFIG.GREAT_SUCCESS.emoji가 💥라 boom이 톤 정합. 차별화가 과하면 `sfx.sparkle()`(L12) 또는 기존대로 `sfx.chime()`.)
권장(재발 방지): 105-107행 3줄을 OUTCOME_CONFIG 옆 맵으로 승격.
```js
const OUTCOME_SFX = { FAIL: () => sfx.thud(), SUCCESS: () => sfx.chime(), GREAT_SUCCESS: () => sfx.boom() };
...
      OUTCOME_SFX[result?.outcome]?.();
```
이러면 OUTCOME_CONFIG와 키가 나란히 놓여 다음에 값이 추가돼도 무음이 재발하지 않는다.

**제품 결정 연동**: none — 극장은 docs/14 §C #6에서 '유지' 확정, 인터미션은 §G 처분 21건 어디에도 없다.

---

### F-3.a. 투명인간 이스터에그 나레이션이 공식 4인만 하드코딩 — 나머지 공식 6인·전 UGC 캐릭터는 범용 폴백

**🔴 잔존** · P3 · SMALL · FE  
`FE/src/hooks/useInvisibleMan.js:52-57`

**근거**

useInvisibleMan.js:51-57 (라인 번호 docs/13과 동일, 무변경):
```
        if (res.data) {
            const narrationMap = {
                  "연화": "연화가 흥미롭다는 눈빛으로 당신을 바라봅니다.",
                  "아이리": "아이리가 숙여 인사하며 부드럽게 미소짓는다.",
                  "백루나": "루나가 머뭇거리며 말합니다.",
                  "서태리": "태리가 귀찮다는 듯이 인사합니다."
              };
```
시드 정본 application-characters.yml에는 공식 캐릭터가 10명이다: 아이리(airi)·연화(yeonhwa)·서태리(taeri)·백루나(luna)·클레어(claire)·로제타(rosetta)·강채린(chaerin)·시에라(sierra)·에델(edel)·류설아(seolah). 맵에 없는 6인 + 모든 UGC 캐릭터가 70행 폴백으로 떨어진다.
호출부에서 이름은 동적으로 들어온다 — ChatPage.jsx:751 `characterName: roomInfo?.characterName`, ChatPageV2.jsx:917 동일. 두 페이지 모두 App.jsx:145/154에 라우팅되어 살아있다.
키가 이름 문자열이라 UGC 유저가 캐릭터 이름을 '연화'로 지으면 공식 연화 나레이션이 그대로 나오는 오염 경로도 열려 있다.

**수정안**

파일 …\src\hooks\useInvisibleMan.js.
오너 결정(openQuestion)에 따라 둘 중 하나.
(가) 범용 1종 통일(권장·플랫폼 정합): 52-57행 narrationMap 전체 삭제, 70행을 F-4.a의 `idleNarration(name)` 호출로 교체. 캐릭터별 개성은 이미 LLM 본문이 담당하므로 손실이 크지 않다.
(나) 개성 유지 확장: 맵의 키를 이름→slug로 바꾸고(이름 충돌 오염 차단) 공식 10인 전부를 채운 뒤, UGC는 (가)의 범용 폴백. 이 경우 호출부 2곳에 `characterSlug: roomInfo?.characterSlug`를 추가로 넘기고 훅 시그니처(25행)에 `characterSlug` 파라미터를 더해야 한다 — UGC는 slug가 null일 수 있으니 반드시 폴백 경유.
어느 쪽이든 이름 문자열 키는 제거할 것.

**제품 결정 연동**: 블록 D와 직접 상호작용 — docs/14 §C #6은 '이스터에그 연출 유지 + 업적(지급·갤러리·해금 모달)만 게이트 오프'다. 그런데 이 코드의 narrationMap은 `if (res.data)`(51행) 안에 있어, 업적 API `/achievements/rooms/{roomId}/unlock`가 게이트 오프로 null/에러를 반환하게 되면 **이 성공 경로 자체가 죽고 catch 폴백(83행)만 남는다**. 즉 블록 D 구현 방식(204 반환 vs 404 vs 플래그 응답)에 따라 여기를 고쳐도 의미가 없어질 수 있다. **블록 D 착수와 함께 처리하고, 게이트 오프 시에도 연출이 나오도록 51행 조건을 `if (res.data)` → 무조건 실행 + achievement optional 구조로 바꾸는 것을 세트로 권장.**

**❓ 결정 필요**: 투명인간 나레이션을 (가) 전 캐릭터 범용 1종으로 통일할 것인가, (나) 공식 10인 개성 문구를 마저 채우고 UGC만 폴백으로 둘 것인가? UGC 플랫폼 비대칭(docs/14 §G #5가 지적한 '공식 4캐릭 전용 no-op' 패턴과 동종)이라 (가)가 정합적이지만, 이스터에그 연출은 '유지 확정' 항목이라 개성 손실을 오너가 감수할지 확인 필요.

---

### F-3.b. 투명인간 훅의 dialogueMap이 완전 사문 — 4개 값이 전부 동일하고 어디서도 참조되지 않음

**🔴 잔존** · P3 · ONE_LINE · FE  
`FE/src/hooks/useInvisibleMan.js:58-63`

**근거**

useInvisibleMan.js:58-63:
```
            const dialogueMap = {
                  "연화": "...주무시나..? 속눈썹 되게 기네..",
                  "아이리": "...주무시나..? 속눈썹 되게 기네..",
                  "백루나": "...주무시나..? 속눈썹 되게 기네..",
                  "서태리": "...주무시나..? 속눈썹 되게 기네.."
            };
```
선언 직후 71행은 맵을 쓰지 않고 같은 문자열을 다시 하드코딩한다:
```
              dialogue: "...주무시나..? 속눈썹 되게 기네..",
```
파일 전체 grep 결과 `dialogueMap` 참조는 58행 선언 1건뿐(사용 0건). 4개 값도 전부 같은 문자열이라 맵으로서 의미가 없다.

**수정안**

파일 …\src\hooks\useInvisibleMan.js 58-63행 dialogueMap 선언 블록 전체 삭제. 71행·84행에 중복된 대사 문자열은 모듈 상단 상수로 승격:
```js
const IDLE_DIALOGUE = "...주무시나..? 속눈썹 되게 기네..";
```
그리고 71행·84행을 `dialogue: IDLE_DIALOGUE,`로 교체.

**제품 결정 연동**: docs/14 §G #4 '데드 코드 일괄' 삭제(🔴) 방침에 부합 — 블록 D 확장 작업과 같은 성격이라 함께 처리해도 무방. 삭제로 인한 제품 결정 리스크는 없다.

---

### F-3.c. V1·V2 채팅 SSE 에러 폴백 대사도 공식 4인 하드코딩 (동종 결함 2곳)

**🔴 잔존** · P3 · SMALL · FE  
`FE/src/pages/ChatPage.jsx:1685-1690 / …/src/pages/ChatPageV2.jsx:2511-2516`

**근거**

ChatPage.jsx:1685-1693 (SSE onError 최종 else 분기):
```
          const narrationMap = {
            "연화": "음.. 잠깐 생각에 잠겨버렸네요.. 뭐라고 하셨나요?",
            "아이리": "잠시만요.. 아이리가 잠깐 바쁜 일이 있어서...",
            "백루나": "음.. ㄴ,네?! 아, 죄송해요.. 잠깐 멍때려버렸어요.. 헤헤..",
            "서태리": "..."
          };
          setCurrentScene({
            dialogue: narrationMap[roomInfo?.characterName] || "잠시 후 다시 시도해주세요.",
```
ChatPageV2.jsx:2511-2518에 문자 단위로 동일한 블록이 존재.
대조 증거 — 같은 파일들의 다른 지점에서는 이미 하드코딩을 걷어낸 이력이 있다: ChatPage.jsx:1101 `// [Scene-Polish A] 하드코딩 narrationMap 삭제 — 서버가 이미 생성해 로그로 도착한`, ChatPageV2.jsx:1406 동일 주석, utils/dialogueSanitizer.js:75 `// 첫인사(ASSISTANT) 씬의 한 줄 나레이션을 하드코딩 narrationMap 대신`.
→ 정리 방침은 이미 섰는데 에러 폴백 2곳만 남았다. 공식 6인·UGC 전원은 무미건조한 "잠시 후 다시 시도해주세요."를 대사로 받는다.

**수정안**

두 파일에서 동일 처리.
1) ChatPage.jsx:1685-1690, ChatPageV2.jsx:2511-2516의 narrationMap 선언 삭제.
2) 이어지는 setCurrentScene을 캐릭터 무관 문구로 교체:
```js
          setCurrentScene({
            dialogue: "...잠깐, 뭐라고 하셨죠? 다시 한 번만요.",
            emotion: "SAD",
            narration: "연결이 잠시 흔들렸다. 잠시 후 다시 시도해주세요.",
          });
```
(기존 dialogue·narration이 둘 다 "잠시 후 다시 시도해주세요."로 중복돼 있어 대사/나레이션 역할 분리도 함께 정리.)
3) 두 파일의 블록이 완전 동일하므로 하나만 고치고 끝내지 말 것 — 반드시 2곳 모두.

**제품 결정 연동**: 낮음 — §G #2(V1 STORY 트랙 제거)로 ChatPage가 SANDBOX 전용이 되어도 이 에러 폴백은 남는다. F-3.a의 '개성 유지 vs 범용 통일' 결정과 동일한 판단을 적용해야 일관된다(같은 결정을 두 번 내리지 말 것).

---

### F-4.a. 투명인간 폴백 나레이션이 주격 조사 '가'를 하드코딩 — 받침 있는 이름에서 '강채린가'

**🔴 잔존** · P3 · SMALL · FE  
`FE/src/hooks/useInvisibleMan.js:70`

**근거**

useInvisibleMan.js:69-73 (라인 번호 docs/13과 동일, 무변경):
```
            scene: {
              narration: narrationMap[name] || `${name}가 가까이 조용히 다가온다. 그 눈동자가 당신을 빤히 올려다본다.`,
              dialogue: "...주무시나..? 속눈썹 되게 기네..",
              emotion: "RELAX",
            },
```
`name`은 43행 `const name = characterNameRef.current;` — 호출부에서 `roomInfo?.characterName`이 그대로 흘러든다.
시드 기준 받침으로 끝나는 공식 캐릭터가 실재한다 — application-characters.yml:756 `- name: 강채린`(→ "강채린가"), :981 `- name: 에델`(→ "에델가"), :521 `- name: 클레어`는 받침 없어 정상. narrationMap에 없는 6인 + UGC 전원이 이 경로다.
블록 A에서 신설된 조사 유틸 src\utils\josa.js는 와/과만 제공하고 이/가 헬퍼가 없다:
```
export const josaWaGwa = (name) => { ... return (c - 0xac00) % 28 > 0 ? "과" : "와"; };
```

**수정안**

1단계 — 유틸 확장. 파일 …\src\utils\josa.js 말미에 이/가 헬퍼 신설(기존 종성 판별 규칙 그대로 재사용):
```js
/** 주격 조사 이/가 — 종성 있으면 '이', 없으면 '가'. 비한글 종결은 null(판별 불가). */
export const josaIGa = (name) => {
  if (!name) return null;
  const c = name.charCodeAt(name.length - 1);
  if (c < 0xac00 || c > 0xd7a3) return null; // 비한글 종결 → 폴백 신호
  return (c - 0xac00) % 28 > 0 ? "이" : "가";
};
```
2단계 — 훅 적용. …\src\hooks\useInvisibleMan.js 상단에 `import { josaIGa } from "../utils/josa";` 추가하고, 모듈 상수로 나레이션 생성기를 둔다(F-4.b와 공유):
```js
const idleNarration = (name) => {
  const j = josaIGa(name);
  return j
    ? `${name}${j} 화면 가까이 조용히 다가온다. 그 눈동자가 당신을 빤히 올려다본다.`
    : `누군가 화면 가까이 조용히 다가온다. 그 눈동자가 당신을 빤히 올려다본다.`; // 비한글 이름 → 이름 생략형(josa.js 폴백 원칙 준수)
};
```
3단계 — 70행 교체:
- 현재: `              narration: narrationMap[name] || \`${name}가 가까이 조용히 다가온다. 그 눈동자가 당신을 빤히 올려다본다.\`,`
- 교정(F-3.a에서 (가)안 채택 시): `              narration: idleNarration(name),`
- 교정(F-3.a에서 (나)안 채택 시): `              narration: narrationBySlug[slug] || idleNarration(name),`

**제품 결정 연동**: F-3.a와 동일 — 이 70행은 `if (res.data)` 성공 경로 안에 있어, 블록 D에서 업적 API가 게이트 오프되면 도달하지 않게 될 수 있다. **F-4.b(83행)를 반드시 함께 고쳐야 하는 이유**가 여기 있다: 게이트 오프 후 실제로 유저가 보는 문장은 83행 쪽이 될 가능성이 높다.

---

### F-4.b. 같은 훅의 catch(서버 실패) 경로 나레이션도 조사 '가' 하드코딩 — 게이트 오프 후 유일 경로가 될 수 있음

**🔴 잔존** · P3 · ONE_LINE · FE  
`FE/src/hooks/useInvisibleMan.js:83`

**근거**

useInvisibleMan.js:76-87 (업적 unlock 실패 시 경로):
```
      } catch (err) {
        console.error("INVISIBLE_MAN unlock failed:", err);
        // 서버 실패해도 연출은 보여줌
        onTrigger?.({
          trigger: "INVISIBLE_MAN",
          achievement: null,
          scene: {
            narration: `${name}가 화면 가까이 조용히 다가온다. 그 눈동자가 당신을 빤히 올려다본다.`,
            dialogue: "...주무시나..? 속눈썹 되게 기네..",
            emotion: "RELAX",
          },
        });
```
70행 폴백과 문구도 미세하게 다르다 — 70행은 "…가 **가까이** 조용히 다가온다", 83행은 "…가 **화면 가까이** 조용히 다가온다". 즉 조사 오류 + 문구 불일치가 겹쳐 있다.
narrationMap 분기가 없어 공식 4인을 포함한 **전 캐릭터**가 이 경로에서는 조사 오류를 맞는다(70행보다 노출 범위가 넓다).

**수정안**

F-4.a에서 만든 `idleNarration`을 그대로 재사용. …\src\hooks\useInvisibleMan.js 83행:
- 현재: `            narration: \`${name}가 화면 가까이 조용히 다가온다. 그 눈동자가 당신을 빤히 올려다본다.\`,`
- 교정: `            narration: idleNarration(name),`
이렇게 하면 70행과 83행의 문구 불일치('가까이' vs '화면 가까이')도 자동으로 해소된다. **F-4.a만 고치고 여기를 빠뜨리는 것이 이 항목을 분리한 이유다.**

**제품 결정 연동**: 블록 D 업적 게이트 오프 시 이 경로가 사실상 유일한 실행 경로가 된다(업적 API가 404/에러를 내면 catch로 떨어짐) → 게이트 오프 이후 유저가 실제로 보는 문장. 우선순위를 F-4.a보다 높게 볼 근거가 된다.

---

### F-5. FOURTH_WALL 이스터에그 콘솔이 캐릭터 무관하게 'Airi.exe'를 출력 — 타 캐릭터 방에서 아이리 모듈명 노출

**🔴 잔존** · P3 · SMALL · FE  
`FE/src/components/EasterEggEffects.jsx:197`

**근거**

EasterEggEffects.jsx:189-197 (console 페이즈, 라인 번호 docs/13과 동일):
```
            <p className="text-green-600/80">{">"} SYSTEM_INTERRUPT: persona_layer_breached</p>
            <p className="text-green-600/80">{">"} WARNING: 4th_wall_integrity = 0%</p>
            <motion.p ...>
              {">"} Airi.exe — core_identity_module v4.4
```
컴포넌트는 캐릭터 정보를 받지조차 않는다 — 111행 `const FourthWallEffect = ({ onEffectEnd }) => {`, 루트 327행 `const EasterEggEffects = ({ activeEffect, onEffectEnd }) => {`, 전달부 332-334행 `<FourthWallEffect key="4wall" onEffectEnd={onEffectEnd} />`.
호출부도 캐릭터를 안 넘긴다 — ChatPage.jsx:3069-3072와 ChatPageV2.jsx:3888-3891 모두 `activeEffect`/`onEffectEnd`만.
도달 경로 확정: 트리거는 LLM 출력이고 캐릭터 게이팅이 없다 — CharacterPromptAssembler.java:248-249가 `supportsEasterEggs(mode)`면 캐릭터 불문 이스터에그 블록을 붙이고, :658 `**Output format:** Add to your JSON root: \`"easter_egg_trigger": "STOCKHOLM"\` (or DRUNK, FOURTH_WALL, MACHINE_REBELLION)`. 소비는 ChatStreamService.java:330-331 → :1268 processEasterEgg. ChatModePolicy.java:126-127 `return mode == ChatMode.SANDBOX;` → V1 SANDBOX의 공식 10인 전원이 도달 가능하고, 아이리가 아닌 9인은 남의 모듈명을 본다.

**수정안**

파일 …\src\components\EasterEggEffects.jsx — prop 배선 3곳 + 문자열 1곳.
1) 111행 시그니처: `const FourthWallEffect = ({ onEffectEnd }) => {` → `const FourthWallEffect = ({ onEffectEnd, characterSlug }) => {`
2) 같은 컴포넌트 본문 상단(useEffect 위)에 모듈명 계산 추가:
```js
  // 슬러그를 파스칼 표기로 — UGC 등 slug 부재 시 중립 모듈명
  const moduleName = characterSlug
    ? `${characterSlug.charAt(0).toUpperCase()}${characterSlug.slice(1)}.exe`
    : "persona.exe";
```
3) 197행 교체:
- 현재: `              {">"} Airi.exe — core_identity_module v4.4`
- 교정: `              {">"} {moduleName} — core_identity_module v4.4`
4) 루트 327행: `const EasterEggEffects = ({ activeEffect, onEffectEnd }) => {` → `const EasterEggEffects = ({ activeEffect, onEffectEnd, characterSlug }) => {`
5) 332-334행 전달: `<FourthWallEffect key="4wall" onEffectEnd={onEffectEnd} characterSlug={characterSlug} />`
6) 호출부 2곳에 prop 추가 — ChatPage.jsx:3069-3072, ChatPageV2.jsx:3888-3891 각각에 `characterSlug={roomInfo?.characterSlug}` 한 줄.
(이름이 아니라 slug를 쓰는 이유: 모듈명은 ASCII여야 'Airi.exe' 톤이 유지된다. 한글 이름을 넣으면 '강채린.exe'가 되어 연출이 깨진다. slug가 null인 UGC는 'persona.exe'로 안전 폴백.)

**제품 결정 연동**: docs/14 §C #6이 '이스터에그 연출 유지 + 업적만 게이트 오프'를 확정했으므로 FOURTH_WALL 연출은 존치 → 수정 필요. §G #2(V1 STORY 트랙 제거)와 무관 — 이스터에그는 SANDBOX 전용이고 V1 SANDBOX는 존치다. 단 §G #3이 '로고 5회 베타 이스터에그'만 콕 집어 삭제 대상으로 지정했으므로 FOURTH_WALL을 같이 지우지 말 것.

---

### F-6. 어드민 모더레이션 로그의 '단계' 컬럼이 2분기뿐 — UGC VLM(3)·UGC Stage0(4)이 전부 'OpenAI'로 오표기

**🔴 잔존** · P2 · SMALL · ADMIN  
`ADM/src/pages/ModerationPage.jsx:86`

**근거**

어드민 ModerationPage.jsx:86 (라인 번호 docs/13과 동일, 어드민 리포는 0188aba 이후 무변경):
```
                    <td className="px-4 py-2.5 text-slate-400">{e.blockedAtStep === 1 ? '키워드' : 'OpenAI'}</td>
```
백엔드가 실제로 적재하는 값 4종:
- 1·2 — ContentModerationService.java:129 `int blockedAtStep,    // 0=통과, 1=키워드, 2=OpenAI` (ChatStreamService.java:198-200, ChatStreamServiceV2.java:182-184가 이 verdict를 그대로 기록)
- 3 — UgcVlmPrefilterService.java:87-88 `moderationEventService.recordModeration(ownerUserId, null, "UGC_IMAGE", 3, verdict.category(), latency, "[VLM 프리필터 자문] …")`
- 4 — UgcModerationService.java:176-178 `moderationEventService.recordModeration(userId, source, ... 4, category, latencyMs, detail);` (2차 확정 판정 MINOR_SIGNAL_CONFIRMED / MINOR_SIGNAL_OVERTURNED)
→ 3·4가 화면에서 'OpenAI'로 둔갑. 미성년 신호 오탐률 측정이 이 화면의 목적인데(UgcModerationService.java:113 주석 '오탐률 측정 기반(어드민 모더레이션 큐 가시화)'), 정작 그 이벤트가 엉뚱한 단계로 표시돼 지표가 오독된다.

**수정안**

파일 C:\Users\zapza\Desktop\LucidChat-Front\LucidChat-Admin\src\pages\ModerationPage.jsx.
1) 모듈 상단(컴포넌트 밖)에 라벨 맵 신설:
```js
// 백엔드 blockedAtStep 정본
//   0 = 통과 / Stage0 즉시 차단(ContentModerationException 경유)
//   1 = 키워드 필터        (ContentModerationService)
//   2 = OpenAI 모더레이션  (ContentModerationService)
//   3 = UGC 이미지 VLM 프리필터 (UgcVlmPrefilterService)
//   4 = UGC Stage 0 미성년 신호 2차 확정 판정 (UgcModerationService)
const STEP_LABELS = {
  0: '—',
  1: '키워드',
  2: 'OpenAI',
  3: 'UGC 이미지(VLM)',
  4: 'UGC Stage 0',
};
```
2) 86행 교체:
- 현재: `                    <td className="px-4 py-2.5 text-slate-400">{e.blockedAtStep === 1 ? '키워드' : 'OpenAI'}</td>`
- 교정: `                    <td className="px-4 py-2.5 text-slate-400">{STEP_LABELS[e.blockedAtStep] ?? `단계 ${e.blockedAtStep}`}</td>`
(`??` 폴백을 두는 이유: 향후 단계가 추가돼도 무단 오표기 대신 '단계 5'로 정직하게 표시된다 — 이번 결함의 재발 방지.)

**제품 결정 연동**: docs/14 §D 블록 E 동반 코드 목록에 'docs/06 §7 보안 잔여(… 모더레이션 재설계)'가 있다 — 재설계가 들어오면 단계 체계 자체가 바뀔 수 있으므로 **지금은 라벨 맵 상수화까지만** 하고 화면 구조 개편은 재설계로 위임할 것. 상수화해두면 재설계 시 이 맵 한 곳만 갱신하면 된다.

---

### F-7. 극장 난입 시작 응답의 히로인 이름이 현재 화자가 아니라 방의 lead 캐릭터로 고정 — ID와 이름이 서로 다른 사람을 가리킴

**🔴 잔존** · P3 · SMALL · BE  
`aichat/src/main/java/com/spring/aichat/service/theater/TheaterInterventionService.java:112`

**근거**

TheaterInterventionService.java:111-117 (라인 번호 docs/13과 동일, 무변경):
```
        Long currentHeroineId = state.getCurrentHeroineId();
        String currentHeroineName = room.getCharacter() != null ? room.getCharacter().getName() : null;

        return new InterventionStart(
            roomId, token, transitionNarration,
            currentHeroineId, currentHeroineName
        );
```
ID는 `state.getCurrentHeroineId()`(현재 화자 — TheaterBranchService.java:54 주석 '[R6] 화자 히로인 조회용 … 그 외는 state.currentHeroineId'), 이름은 `room.getCharacter()`(방 생성 시 정해진 lead). 멀티 히로인 극에서 화자가 lead가 아닐 때 DTO 한 레코드 안에서 ID와 이름이 다른 인물을 가리킨다.
DTO 계약 — TheaterResponses.java:338-344 `public record InterventionStart(Long roomId, String checkpointToken, String transitionNarration, Long currentHeroineId, String currentHeroineName) {}`. 82행도 체크포인트에 `checkpoint.put("currentHeroineId", state.getCurrentHeroineId());`로 화자 ID를 저장한다.
**도달성 단서**: 이 엔드포인트 `/api/v1/theater/rooms/{roomId}/intervention/start`(TheaterInterventionController.java:22-27)를 호출하는 프론트 코드가 없다 — FE·어드민 전체 grep 결과 theater intervention API 호출 0건. FE의 `requestDirectorIntervention`(UseChatStream.js:125)은 `${BASE_URL}/story/rooms/${roomId}/director/request`로 **다른 엔드포인트**다. 따라서 결함 코드는 확실히 존재·API로 도달 가능하지만 현재 화면에 노출되는 경로는 없다.

**수정안**

파일 …\aichat\src\main\java\com\spring\aichat\service\theater\TheaterInterventionService.java.
1) 의존성 추가 — 46-53행 필드 블록에 `private final com.spring.aichat.domain.character.CharacterRepository characterRepository;` (RequiredArgsConstructor라 생성자 수정 불필요). ※ 도메인 클래스명이 `Character`라 `java.lang.Character`와 충돌하니 import 대신 완전수식명 사용을 권장.
2) 111-112행 교체:
```java
        Long currentHeroineId = state.getCurrentHeroineId();
        String currentHeroineName = currentHeroineId == null ? null
            : characterRepository.findById(currentHeroineId)
                .map(com.spring.aichat.domain.character.Character::getName)
                .orElse(null);
        if (currentHeroineName == null && room.getCharacter() != null) {
            currentHeroineName = room.getCharacter().getName();  // 폴백: 화자 미정 시 lead
        }
```
(폴백을 남기는 이유: TheaterState.java:314·329 주석대로 currentHeroineId는 chapter 전환 시에도 보존되지만 극 시작 직후 null일 수 있다.)

**제품 결정 연동**: 극장은 docs/14 §C #6에서 '극장 유지' 확정이라 수정 대상이 맞다. 다만 §G 어디에도 '난입(Intervention)' 처분이 없고 프론트 소비처가 없어 **기능 자체가 미배선 상태**다 — 난입을 살릴지(FE 배선) 접을지 결정 전에는 이 수정의 실효가 0이다. 우선순위는 극장 난입 부활 결정에 종속.

**❓ 결정 필요**: 극장 난입(intervention) 기능을 살릴 것인가? 백엔드 API·에너지 차감·체크포인트·디렉터 노트 기록까지 전부 구현돼 있는데 프론트 호출부가 전무하다. 살린다면 F-7을 FE 배선과 함께 고치고, 접는다면 §G 처분 목록에 추가해 데드코드로 분류하는 편이 낫다.

---

### F-8.a. V1 채팅 스트림에서 에너지 부족이 전용 에러코드를 잃고 '예기치 않은 오류'로 표시 — 충전 유도 실패

**🔴 잔존** · P2 · SMALL · BE  
`aichat/src/main/java/com/spring/aichat/service/stream/ChatStreamService.java:488-491`

**근거**

ChatStreamService.java:488-491 (라인 번호 docs/13과 동일, 무변경) — sendMessageStream 최외곽 catch:
```
        } catch (Exception e) {
            log.error("❌ Unexpected error | roomId={}", roomId, e);
            sendSseError(emitter, "UNEXPECTED_ERROR", "예기치 않은 오류가 발생했습니다.");
        }
```
예외 발생원은 같은 try 안 210행 `room.getUser().consumeEnergy(cost);` → User.java:166-171:
```
    public void consumeEnergy(int amount) {
        int total = this.freeEnergy + this.paidEnergy;
        if (total < amount) {
            throw new InsufficientEnergyException(
                "에너지가 부족합니다. (보유: " + total + ", 필요: " + amount + ")");
```
InsufficientEnergyException.java는 `super(ErrorCode.INSUFFICIENT_ENERGY, message)`이고 BusinessException은 RuntimeException 상속 → 그대로 최외곽 catch(Exception)에 잡혀 코드·메시지가 모두 뭉개진다. HTTP 경로에서는 GlobalExceptionHandler.java:25 `case INSUFFICIENT_ENERGY -> 402;`로 살아 있는데 SSE 경로에서만 소실.
프론트는 이미 이 코드를 기다린다 — ChatPageV2.jsx:1820-1822:
```
        if (err.errorCode === "INSUFFICIENT_ENERGY") {
          sfx.locked();
          setPaymentInitialTab("energy");
          setShowPayment(true);
```
→ 현재는 else 분기로 떨어져 결제 모달이 열리지 않고 "예기치 않은 오류가 발생했습니다." 토스트만 뜬다. 에너지 소진 순간이 곧 충전 전환 지점인데 그 퍼널이 통째로 끊겨 있다.

**수정안**

파일 …\aichat\src\main\java\com\spring\aichat\service\stream\ChatStreamService.java 488-491행을 3단 catch로 교체(순서 필수 — InsufficientEnergyException이 BusinessException의 하위):
```java
        } catch (InsufficientEnergyException e) {
            log.info("⚡ [STREAM] 에너지 부족 | roomId={} | {}", roomId, e.getMessage());
            sendSseError(emitter, "INSUFFICIENT_ENERGY", e.getMessage());
        } catch (BusinessException e) {
            log.warn("⚠️ [STREAM] business error | roomId={} | code={}", roomId, e.getErrorCode(), e);
            sendSseError(emitter, e.getErrorCode().name(), e.getMessage());
        } catch (Exception e) {
            log.error("❌ Unexpected error | roomId={}", roomId, e);
            sendSseError(emitter, "UNEXPECTED_ERROR", "예기치 않은 오류가 발생했습니다.");
        }
```
import 추가: `com.spring.aichat.exception.InsufficientEnergyException` (BusinessException은 17행에 이미 import 되어 있음).
BusinessException 일반 분기를 함께 두는 이유: ErrorCode.name()이 곧 프론트 계약 문자열이라, 앞으로 추가되는 도메인 예외도 자동으로 정확한 코드로 전달된다.

**제품 결정 연동**: docs/16(시크릿 BM 승격)·블록 C(부스트 3.6-flash 치환, 구독자 기본 E 차감 혜택 유지)와 정합 — 에너지 소진→충전 전환 퍼널은 BM 직결이라 오히려 우선순위 상향 근거가 된다. 블록 C가 cost 계산(boostModeResolver)을 바꿔도 에러코드 전파와는 직교하므로 지금 고쳐도 충돌 없음.

---

### F-8.b. V2 채팅 스트림에도 동형 잔존 — 라이브 트랙인 V2에서 에너지 부족이 '예기치 않은 오류'

**🔴 잔존** · P2 · SMALL · BE  
`aichat/src/main/java/com/spring/aichat/service/story/ChatStreamServiceV2.java:280-283`

**근거**

ChatStreamServiceV2.java:280-283 — sendMessageStream 최외곽 catch:
```
        } catch (Exception e) {
            log.error("❌ [V2-STREAM] Unexpected error | roomId={}", roomId, e);
            sendSseError(emitter, "UNEXPECTED_ERROR", "예기치 않은 오류가 발생했습니다.");
        }
```
예외 발생원 — 같은 try 안 191-198행 TX-1:
```
            JpaPreResult jpa = txTemplate.execute(status -> {
                ChatRoom room = chatRoomRepository.findWithMemberAndWorldById(roomId)
                    .orElseThrow(() -> new NotFoundException("채팅방이 존재하지 않습니다."));
                int cost = boostModeResolver.resolveEnergyCost(room.getChatMode(), room.getUser());
                room.getUser().consumeEnergy(cost);
```
동일 파일 375행(오프닝 스트림)에도 같은 최외곽 처리가 있다.
F-8.a와 같은 이유로 ChatPageV2.jsx:1820의 결제 모달 분기가 죽는다 — 그리고 V2가 STORY 정식 트랙(§G #2 'V2 완전 대체')이라 실제 유저 노출은 이쪽이 주력이다.

**수정안**

파일 …\aichat\src\main\java\com\spring\aichat\service\story\ChatStreamServiceV2.java 280-283행에 F-8.a와 **동일한 3단 catch** 적용:
```java
        } catch (InsufficientEnergyException e) {
            log.info("⚡ [V2-STREAM] 에너지 부족 | roomId={} | {}", roomId, e.getMessage());
            sendSseError(emitter, "INSUFFICIENT_ENERGY", e.getMessage());
        } catch (BusinessException e) {
            log.warn("⚠️ [V2-STREAM] business error | roomId={} | code={}", roomId, e.getErrorCode(), e);
            sendSseError(emitter, e.getErrorCode().name(), e.getMessage());
        } catch (Exception e) {
            log.error("❌ [V2-STREAM] Unexpected error | roomId={}", roomId, e);
            sendSseError(emitter, "UNEXPECTED_ERROR", "예기치 않은 오류가 발생했습니다.");
        }
```
373-376행(오프닝 스트림 최외곽)도 같은 패턴으로 함께 교체할 것 — 오프닝은 에너지를 소비하지 않지만 BusinessException 코드 보존 이득은 동일하다.

**제품 결정 연동**: §G #2에서 V2가 STORY 정식 트랙으로 확정 → V1(F-8.a)보다 이쪽이 우선. 블록 C의 부스트 모델 치환이 `boostModeResolver.resolveEnergyCost`를 손대므로, 그 작업과 같은 파일을 건드린다는 점만 스케줄링에서 고려하면 된다.

---

### F-8.c. V1 보조 SSE 4경로(이벤트 선택·지켜보기·시간 넘기기·디렉터 자동응답)도 에너지 부족을 삼킴

**🔴 잔존** · P3 · MEDIUM · BE  
`aichat/src/main/java/com/spring/aichat/service/stream/ChatStreamService.java:596-599, 706-709, 884-887, 1749-1752`

**근거**

차감 지점 → 삼키는 catch 쌍(모두 ChatStreamService.java):
1) 이벤트 선택 — 517행 `room.getUser().consumeEnergy(energyCost);` → 596-598행
```
        } catch (Exception e) {
            log.error("❌ Event select error | roomId={}", roomId, e);
            sendSseError(emitter, "UNEXPECTED_ERROR", "이벤트 처리 중 오류 발생");
```
2) 지켜보기 — 626행 `consumeEnergy(cost)` → 706-708행 `sendSseError(emitter, "UNEXPECTED_ERROR", "지켜보기 처리 중 오류 발생");`
3) 시간 넘기기 — 755행 `consumeEnergy(TIME_SKIP_ENERGY_COST)` → 884-886행 `sendSseError(emitter, "UNEXPECTED_ERROR", "시간 넘기기 처리 중 오류 발생");`
4) 디렉터 자동응답 — 1586행 `consumeEnergy(cost)` → 1749-1751행 `sendSseError(emitter, "UNEXPECTED_ERROR", "자동 응답 처리 중 오류 발생");`
메시지 문구가 경로별로 달라 '무슨 기능이 실패했는지'는 알 수 있지만, 어느 경우도 INSUFFICIENT_ENERGY 코드를 실어 보내지 않아 프론트의 결제 모달 분기가 동일하게 죽는다.

**수정안**

파일 …\aichat\src\main\java\com\spring\aichat\service\stream\ChatStreamService.java — 4곳 각각의 catch(Exception) 앞에 에너지 분기를 삽입(경로별 메시지는 보존):
```java
        } catch (InsufficientEnergyException e) {
            log.info("⚡ [STREAM] 에너지 부족 | roomId={} | {}", roomId, e.getMessage());
            sendSseError(emitter, "INSUFFICIENT_ENERGY", e.getMessage());
        } catch (Exception e) {
            ... 기존 그대로 ...
        }
```
반복이 4회라, 사설 헬퍼로 묶는 편이 낫다 — sendSseError(1304행) 옆에 추가:
```java
    /** SSE 최외곽 공통 처리 — 도메인 에러코드 보존, 그 외는 경로별 폴백 문구. */
    private void sendSseFailure(SseEmitter emitter, Exception e, Long roomId, String fallbackMessage) {
        if (e instanceof InsufficientEnergyException ie) {
            log.info("⚡ [STREAM] 에너지 부족 | roomId={} | {}", roomId, ie.getMessage());
            sendSseError(emitter, "INSUFFICIENT_ENERGY", ie.getMessage());
        } else if (e instanceof BusinessException be) {
            log.warn("⚠️ [STREAM] business error | roomId={} | code={}", roomId, be.getErrorCode(), be);
            sendSseError(emitter, be.getErrorCode().name(), be.getMessage());
        } else {
            log.error("❌ [STREAM] Unexpected error | roomId={}", roomId, e);
            sendSseError(emitter, "UNEXPECTED_ERROR", fallbackMessage);
        }
    }
```
그 뒤 5개 최외곽(488·596·706·884·1749)을 `} catch (Exception e) { sendSseFailure(emitter, e, roomId, "이벤트 처리 중 오류 발생"); }` 형태로 통일. F-8.a와 한 번에 처리하는 것이 효율적.

**제품 결정 연동**: §G #13이 '디렉터 3분기 카드 — 골격 유지, energyCost 서버 판정(docs/13 P0 픽스 세트)'을 지시했고 §G #7이 'V1 디렉터 잔여 정리(INTERLUDE/TRANSITION/AWAY 소비 경로·activeDirector* 필드)'를 게이트오프 대상으로 올렸다 → **4)디렉터 자동응답 경로(1586/1749)는 §G #7로 제거될 가능성**이 있으니 블록 D 진행 상황을 확인하고 착수할 것. 1)~3)은 SANDBOX 존치 기능이라 영향 없음. 또한 B-3(energyCost 클라이언트 지정) 픽스가 이 4경로와 같은 코드를 건드리므로 **B-3 담당자와 병합 충돌 조율 필요**.

---

### F-8.d. 프론트의 에너지 부족 분기가 SSE에 없는 `error.status === 402`를 검사 — 영구 사문 (V1·V2 각 1곳)

**🔴 잔존** · P3 · SMALL · FE  
`FE/src/pages/ChatPage.jsx:1680 / …/src/pages/ChatPageV2.jsx:2506`

**근거**

ChatPage.jsx:1677-1682 (SSE onError):
```
        if (error.errorCode === "CONTENT_BLOCKED") {
          showToast(error.message || "부적절한 내용이 포함되어 있습니다.", "warning");
        } else if (error.status === 402) {
          showToast("에너지가 부족합니다.", "error");
        } else if (error.status === 429) {
```
ChatPageV2.jsx:2504-2508에 동일 블록.
SSE 에러 객체에는 `status`가 없다 — UseChatStream.js:261 `catch (e) { callbacks.onError?.({ errorCode: 'PARSE_ERROR', message: parsed.data }); }`, :288 `errorCode: 'NETWORK_ERROR'`, UseStoryV2Stream.js:151/186 동일. `status`가 실리는 건 스트림 수립 실패 시 HTTP 응답 경로뿐(UseChatStream.js:213 `errorCode: errorData.errorCode || \`HTTP_${response.status}\``) — 그 경우조차 `status` 키가 아니라 errorCode에 `HTTP_402` 형태로 들어간다.
→ 백엔드가 INSUFFICIENT_ENERGY를 보내기 시작해도 이 두 분기는 여전히 else로 떨어진다. 반면 ChatPageV2.jsx:1820의 다른 핸들러는 `err.errorCode === "INSUFFICIENT_ENERGY"`로 올바르게 되어 있어, 같은 파일 안에서도 계약이 두 갈래다.
부수 확인: ChatPageV2.jsx:1822 `else if (err.errorCode === "PREMIUM_REQUIRED")` — BE ErrorCode enum(ErrorCode.java:8-40)에 PREMIUM_REQUIRED 값이 없어 이 분기도 사문이다.

**수정안**

두 파일에서 동일 처리 — 계약을 errorCode 기준으로 통일하고 결제 모달까지 연결한다.
ChatPage.jsx:1680 / ChatPageV2.jsx:2506:
- 현재: `        } else if (error.status === 402) {\n          showToast("에너지가 부족합니다.", "error");`
- 교정: 
```js
        } else if (error.errorCode === "INSUFFICIENT_ENERGY" || error.errorCode === "HTTP_402") {
          sfx.locked();
          setPaymentInitialTab("energy");
          setShowPayment(true);
```
(ChatPageV2.jsx:1820-1823의 기존 올바른 핸들러와 동일한 동작 — 토스트만 띄우고 끝내면 충전 퍼널이 여전히 안 열린다. ChatPage.jsx에 setPaymentInitialTab/setShowPayment 상태가 있는지 확인 후 없으면 기존 결제 모달 오픈 헬퍼를 사용.)
다음 429 분기도 같은 이유로 `error.errorCode === "HTTP_429"` 병행 검사를 권장.
F-8.a/b(백엔드)와 **반드시 세트로** 배포할 것 — 한쪽만 고치면 여전히 안 걸린다.

**제품 결정 연동**: F-8.a와 동일하게 BM 퍼널 직결. §G #2로 ChatPage(V1)가 SANDBOX 전용이 되어도 이 핸들러는 남는다.

---
