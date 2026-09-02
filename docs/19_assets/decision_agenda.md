# 19-assets. 결정 안건 정본 — 버그픽스 착수 전 (22건) (2026-08-21)

> **갱신 2026-08-21** — 안건 **1(부팅 블로커)은 결정 불요로 소멸**했다(한 줄 핫픽스 + 로컬 bootRun 성공). 따라서 **실효 21건 / A절 13건**이다.
> 같은 날 런타임 전용 결함 2건이 추가 발견·수정됐다(FE 댕글링 import · 미선언 setter) — 상세는 [`blockd_regressions.md`](blockd_regressions.md) 부록. 둘 다 결정 불요.

> 재판정 원자에서 나온 결정거리 65건 + 블록 D 회귀 22건 + 미등재 신규 21건 + 1차 비평 10건 = **후보 57건을 병합·적대적 가지치기 후 22건**.
> 걷어낸 기준 — ① docs/14 §G·docs/16·docs/18 §4-A에 이미 답이 있는 것 ② 코드·쿼리 1회로 답이 나오는 것 ③ 지금 물으면 답할 수 없는 종속 안건 ④ "고칠까 말까" 수준.

## A. 착수 전 필수 (14건)

### ~~1.~~ ✅ **결정 불요 (2026-08-21)** —  부팅 블로커 — 극장 엔딩 리버트 후퇴 기준선  ·  `롤아웃순서`

> ✅ **전제가 소멸했다.** 한 줄 핫픽스 적용 후 **로컬 bootRun 성공**(`Started AichatApplication in 22.86 seconds`).
> 이 안건의 질문은 "실기동을 확인 못 한 채 배포일이 오면 리버트할 것인가"였는데 **확인됐으므로 (b)의 기준선 자체가 불필요**하다. 25d0fb0 리버트 불요.
> 남는 실행 항목 하나 — docs/18 §2-A 배포 후 확인 순서에 **0단계 "컨텍스트 기동 확인"**을 추가할 것. 극장 엔딩 계열 미해결 결함 5건은 별건으로 유효하다.

**질문** — HEAD 20c4cf9는 지금 기동되지 않는다(MongoConfig 스캔 범위 밖 EndingResultRepository). 한 줄 핫픽스는 결정이 아니라 실행이다. 진짜 질문은 하나다 — 로컬 bootRun으로 실제 기동을 한 번도 확인하지 못한 채 배포일이 오면, 극장 엔딩 부활(25d0fb0)을 리버트하고 다음 릴리즈로 미룰 것인가?

**선택지** — (a) 핫픽스만 넣고 스택 20커밋을 그대로 올린다 — 극장 엔딩은 실행 검증 0이고 미해결 결함 5건(TOCTOU 이중 발동 · isEndingPoint 조기 통과 · prod 유니크 인덱스 부재 · 분기 label 인젝션 · 인터미션 영구 소멸)이 딸려 있다. (b) 조건부 후퇴 — '배포 D-1까지 로컬 bootRun 성공을 확인하지 못하면 25d0fb0을 리버트한다'는 날짜형 기준선을 지금 못 박는다. (c) 무조건 리버트 — 극장 엔딩은 다음 릴리즈로 미루고 이번엔 블록 A·B·D만 올린다.

**추천** — (b). 수정 방향은 코드가 이미 답했다 — MongoConfig.java:34-38 basePackages에 "com.spring.aichat.domain.ending"을 추가하는 단독 커밋이 맞고, basePackages를 "com.spring.aichat.domain"으로 상향하는 안은 다른 domain 하위에 JPA 엔티티가 다수라 Spring Data strict 매칭 판정에 상시 의존하게 되므로 지금 바꿀 이유가 없다. 남는 것은 리스크 수용 판단뿐이다: 리버트는 과잉이지만, 컴파일·유닛테스트 116건이 전부 녹색인데도 서버가 안 뜬 이번 사례가 보여주듯 현재 검증 수단으로는 부팅 계열 결함을 원리적으로 못 잡는다(@SpringBootTest는 CI 글롭 제외). 그래서 '검증 못 하면 뺀다'는 기준선이 필요하다. 어느 쪽이든 docs/18 §2-A 배포 후 확인 순서에 0단계 '컨텍스트 기동 확인'을 추가해야 한다 — 지금 절차에는 부팅 확인 자체가 없다.

**이 답 없이 막히는 것** — AWS 복구 후 첫 배포 전체(문자 그대로 서버가 안 뜬다). 극장 엔딩 계열 판정 6건(B-9.8/9.9/9.10 · E-4.5.a · E-4.11 · E-4.12)의 실효 확인. 배치 4(극장 구조) 착수 가부. docs/18 §4-A ③('극장 엔딩 처분 = 부활 구현 완료')의 지위 자체.

**선행 조사** — AWS 무관 — 지금 가능. 로컬 bootRun(local 프로파일) 1회로 핫픽스 후 기동을 확인할 수 있는지 종원이 판단해 답하라. 로컬 실행이 불가능하다면 그 사실 자체가 (b)/(c)를 가른다.

<sub>근거: 회귀스캔 #8(MongoConfig) · newDefects #5 · critic #1 · B-9.9</sub>

### 2. 엔딩 게이트 구멍과 이미 잠긴 방 처분 (V28 내용물)  ·  `운영정책`

**질문** — 엔딩 게이트가 EndingEligibilityService:62(checkAndActivateEligibility)에만 있고 :96 processDirectorTrigger에는 없다. 기존 ending_eligible=true인 V2 STORY 방은 지금도 :114 markEndingReached에 도달해 '도달은 했으나 감상은 400'인 상태로 영구 잠긴다. 이 방들을 데이터로 풀 것인가, 코드만 막고 방치할 것인가?

**선택지** — (a) V28에 ending_eligible/ending_reached 해제 마이그레이션을 넣어 잠금을 푼다 — 되돌리기 어렵고 정당하게 도달했던 방의 이력까지 지운다. (b) 코드만 막는다(processDirectorTrigger에 동일 게이트 추가) + 기존 데이터 방치 — 유저는 '엔딩 도달' 표기만 남고 감상은 못 한다. (c) 노출만 정리한다 — roomInfo에 게이트 상태를 실어 '엔딩 다시 보기' 버튼을 숨기거나 문구를 바꾼다(현재는 400을 2s·4s 백오프로 3회 재시도한 뒤 실행 불가능한 안내 토스트가 뜬다).

**추천** — (b)+(c). (a)는 프로드에 잠긴 방이 몇 개인지 모르는 상태에서 파괴적이다. 선행 조회 1쿼리를 AWS 복구 직후 §2-A에 끼워 넣고, 0~수십 건이면 (b)+(c)로 자동 종결시켜 종원을 두 번 부르지 않는다. 유의미하게 많으면 그때 (a)를 재론한다. ★ 이 안건의 첫 산출물은 문서 정정이다 — docs/18 §4-A ⑫('E-4.9는 엔딩 게이트로 도달 불가, 다시 묻지 않는다')는 실측과 다르므로 '해소됨'에서 §4-B '남은 결정'으로 되돌려야 한다. ⚠ 함께 확정할 것: 엔딩 노브를 나중에 켤 경우 EndingService.java:69-74에는 여전히 null 가드가 없어 P0 500이 그대로 부활한다 — '노브 부활 전 최소 3줄 가드'를 선행 조건으로 못 박을 것.

**이 답 없이 막히는 것** — V28 마이그레이션 내용물 확정 → 배치 1B 전체(V28은 orders.imp_uid unique·구독 부분 유니크와 같은 리비전을 공유한다). E-4.9 · E-4.10 · newDefects #3/#4 · 회귀스캔 #3의 수정 형태. docs/18 §4-A ⑫의 정정.

**선행 조사** — ⚠ AWS 정지로 지금 불가 — 복구 직후 §2-A에 1쿼리: V2 STORY 방 중 ending_eligible=true 또는 ending_reached=true인 방 개수(chat_rooms). 0~수십 건이면 (b)+(c)로 자동 종결.

<sub>근거: E-4.9 · E-4.10 · newDefects #3/#4 · 회귀스캔 #3 · critic #3 · docs/18 §4-A ⑫(정정 대상)</sub>

### 3. 구독 중복 활성 행 정리 (V28 부분 유니크)  ·  `스키마결정`

**질문** — 프로드 user_subscriptions에 active=true 행이 유저당 2건 이상 존재하는가, 존재한다면 어느 행을 살리고 죽는 행을 보상할 것인가?

**선택지** — 선행 조회가 0건이면 안건 소멸 — V28에 부분 유니크 인덱스만 넣으면 끝난다. 1건 이상이면 살릴 행: (a) 만료일이 가장 늦은 행 (b) 가장 최근 결제 행. 죽는 행 보상: (가) 무보상 (나) 잔여일 이월.

**추천** — (a)+(나). 중복 자체가 우리 코드의 버그(활성 유일성 미보장)이므로 유저가 손해 보는 정리는 부적절하다. 만료일 최장을 살리면 대부분 케이스에서 이월이 자동으로 성립하고, 그래도 손실이 남는 행만 잔여일을 가산하면 된다. ★ 마이그레이션 번호는 V26이 아니라 V28이다 — 블록 D가 V26·V27을 선점했다(레지스터의 V26 지시는 무효).

**이 답 없이 막히는 것** — 배치 1B의 V28 내용물 확정(orders.imp_uid unique와 같은 마이그레이션에 묶을지 포함). 확정 없이는 배치 1B가 통째로 대기한다.

**선행 조사** — ⚠ AWS 정지로 지금 불가 — 복구 직후 §2-A에: SELECT user_id, count(*) FROM user_subscriptions WHERE active GROUP BY 1 HAVING count(*)>1

<sub>근거: D-4.4 · B-1.2(orders.imp_uid unique, 같은 V28)</sub>

### 4. 지급 실패 결제 처분 (돈은 나갔는데 지급도 환불도 없음)  ·  `과금설계`

**질문** — 결제는 승인됐는데 deliverProduct가 던져 주문이 PENDING으로 롤백되는 경로(돈은 나갔는데 지급도 환불도 실패 기록도 없음)를 어떻게 닫을 것인가? 재지급 큐를 런칭 전에 만들 것인가, 런칭 후 CS 수동으로 버틸 것인가?

**선택지** — (a) 금액 불일치 경로처럼 자동 cancelPayment — 부분 지급이 이미 일어난 상품(구독 활성 후 캐시 evict 실패 등)에서는 돈만 돌려주고 혜택은 남는 역위험. (b) markPaid를 별도 트랜잭션으로 커밋하고 지급 실패를 PAID_UNDELIVERED 상태로 남겨 관리자 재지급 큐에 올린다(스키마 + 어드민 화면 동반). (c) prepareOrder(PaymentService.java:59-75)에서 targetCharacterId 실존·시크릿 승인 여부를 선검증해 발생 창을 줄이고 나머지는 CS 수동.

**추천** — (b)+(c). (c)만으로는 prepare~결제 사이에 캐릭터가 숨김·삭제되는 TOCTOU 창이 남고(SecretModeService.java:223-225가 지급 시점에야 존재를 확인한다), (a)는 이미 일부 지급된 상태에서 되레 손실을 만든다. PaymentService.java:202-204를 'markPaid 커밋 → 지급 시도 → 실패 시 상태 전이 + 감사로그'로 쪼개면 돈의 흐름과 지급의 흐름이 분리된다. RefundService가 이미 있으므로 회수는 기존 관리자 도구로 처리 가능하고, 런칭 전에는 '상태 + 감사로그'까지만 만들고 재지급 큐 UI는 런칭 후로 미뤄도 된다 — 다만 상태 전이 자체는 지금 넣어야 사고가 기록으로 남는다. PG 심사 지적 후보이기도 하다.

**이 답 없이 막히는 것** — 배치 1B(DB 무결성) · 배치 2(결제 개통). docs/18 §3-A 결제 정합 3종(B-1.1/1.2/1.3)과 같은 커밋 단위 — PG 심사 제출 전 선행.

> **✅ 2026-09-02 구현 (b)+(c)** — `OrderStatus.PAID_UNDELIVERED` 신설(**V34**로 `orders_status_check` 동기화 — Hibernate CHECK 실측, §2-7). `PaymentService`가 단일 @Transactional을 버리고 TX-A(잠금·검증·`markPaid`→PAID_UNDELIVERED 커밋) → TX-B(`deliverProduct`→`markDelivered`→PAID) → 실패 시 TX-C(사유 `failed_reason` + 감사로그 `PAYMENT_UNDELIVERED`) + `DeliveryFailedException`(INTERNAL_ERROR → 웹훅 503 재시도 / confirm 500 안내문구). 재시도(/confirm 재호출·웹훅)는 PAID_UNDELIVERED를 보고 PortOne 재검증 없이 지급만 다시 한다. 캐시 무효화는 커밋 뒤로 빼 Redis 순단이 지급을 되돌리지 않게 했다. `RefundService`는 미지급 주문을 회수 없이 취소한다. (c) `prepareOrder`가 시크릿 2종의 대상 캐릭터 실존을 결제 전 확인(자격은 계정 단위 해금이라 미검사). 감시 스케줄러 스캔 A가 PAID_UNDELIVERED를 '기록된 미지급'으로 관측(5분 유예). 재지급 큐 **UI**는 런칭 후(확정대로).

**선행 조사** — 없음 — AWS 무관, 코드만으로 확정 가능

<sub>근거: 신규결함 #16(PaymentService.java:202-204 · SecretModeService.java:163-165·223-225) · B-1.1 인접</sub>

### 5. 구독 티어 이월 산식  ·  `과금설계`

**질문** — LUCID_PASS(14,900) → LUCID_MIDNIGHT_PASS(24,900) 업그레이드 시 하위 티어 잔여 기간을 어떻게 처리할 것인가?

**선택지** — (a) 현행 — SubscriptionService.java:57-66이 기존 구독을 deactivate하고 새로 30일을 발급, 잔여 기간은 조용히 소멸. (b) 잔여 일수를 금액 비례로 상위 티어 일수로 환산해 30일에 가산(잔여일 × 14,900/24,900). (c) 잔여일을 그대로 더한다(유저 최대 유리).

**추천** — (b). (a)는 업그레이드할수록 손해라 상위 티어 전환을 억제하는 역인센티브이고, 소비자분쟁해결기준 관점에서도 방어가 어렵다. (c)는 저가 티어를 사서 즉시 업그레이드하는 차익 경로가 생긴다. 구현은 UserSubscription.create에 expiresAt을 받는 오버로드 하나(SMALL). ★ 다운그레이드는 이번 범위에서 제외하고 현행 유지한다 — 케이스가 드물고 예약 전환 설계가 딸려 온다. ★ 이 산식이 확정돼야 docs/18 §1-B 약관 TODO의 '환불 산식·유료 재화 소멸시효' 항목을 채울 수 있다. 코드보다 약관이 먼저 막힌다.

**이 답 없이 막히는 것** — 배치 1B(D-4 구독 정합 3건) · docs/18 §1-B terms_of_service_draft.md TODO 21건 중 환불 산식 항목.

> **✅ 2026-09-02 구현 (b)** — `SubscriptionService.carryover` = 잔여 × (하위 월액/상위 월액), 상위 티어 30일에 가산(V35 `carried_from_id`·`carried_seconds`로 출처 기록 → 상위 주문 환불 시 이전 행 복원). **다운그레이드**는 "범위 밖"이었으나 적대적 리뷰가 '상위 구독 활성 중 하위 구매 1클릭 실수로 최대 24,900원치 무경고 소각'을 지적해 **서버 거부(400 "N일 남아 있어요 — 만료 후 변경") + FE 카드 잠금**으로 구현했다(관리자 지급은 예외). ⚠ 종원 확인 대기: 거부 유지 vs 경고 후 허용.

**선행 조사** — 없음 — AWS 무관

<sub>근거: D-4.1(UserSubscription.java:78-83 · SubscriptionService.java:57-66 · PaymentService.java:246-251)</sub>

### 6. 환불 시 회수 실패 처리  ·  `과금설계`

**질문** — ① 갱신 이력이 있는 구독의 과거 회차 환불을 어디까지 허용할 것인가(CS 정책)? ② 회수 대상을 못 찾았을 때 환불을 막을 것인가, 진행하되 사실을 남길 것인가?

**선택지** — ①에 대해: (a) 회차분 30일 차감 — subscription_payments 이력 테이블 신설 필요(런칭 전 스키마 비용). (b) 전체 즉시 해지. (c) 최근 회차만 환불 허용하고 과거 회차는 CS에서 거부. ②에 대해: (가) 회수 실패 시 PortOne 취소 자체를 막는다(선검증) (나) 환불은 진행하되 예외·감사로그로 승격해 관리자가 사실을 알게 한다.

**추천** — (c)+(나). 근거: UserSubscription.renew(:79-83)가 merchantUid를 덮어쓰기 때문에 과거 회차 merchantUid로는 findByMerchantUid가 아무것도 못 찾고, RefundService.clawback → SubscriptionService.deactivateByMerchantUid(:103)의 .ifPresent가 조용히 통과해 '돈은 돌려주고 구독은 살아 있는' 상태가 지금 무성으로 만들어진다. 같은 무성 패턴이 시크릿 회수 2경로(RefundService.java:93-94)에도 있으므로 한 커밋으로 묶는다. (a)는 런칭 전에 낼 값어치가 없는 스키마 비용이고, RefundService.java:27-30이 명문화한 '유저 유리' 원칙은 유지하되 관리자가 사실을 모르는 상태만 없애는 것이 최소 정합이다.

**이 답 없이 막히는 것** — 배치 1B(D-4 구독 정합) · 배치 6(어드민 환불 도구) · docs/18 §1-B 약관 환불 조항 문구.

> **✅ 2026-09-02 구현 (c)+(나)** — 과거 회차·이월 원천 회차는 `SubscriptionService.assertRefundableRound`가 PortOne 취소 **전** 400 거부. 최신 회차는 V35 스냅샷으로 **그 회차분만** 회수(적대적 리뷰 P1: 종전 구현은 행 전체를 꺼 더블 결제 유저가 이전 회차까지 잃었다). 회수 미발견은 감사로그 `REFUND_CLAWBACK_FAILED` + 409 예외(롤백 없음). 관리자 지급은 `merchantUid=null`로 유료 회차 키를 덮지 않는다(P2). 어드민 화면은 409를 '환불 완료·회수 실패' 안내로 처리하고 목록을 갱신한다.

**선행 조사** — 없음 — AWS 무관

<sub>근거: D-4.2 · D-4.3(RefundService.java:27-30·93-94 · SubscriptionService.java:102-117)</sub>

### 7. 시크릿 노출 토글 범위 (PG 심사)  ·  `롤아웃순서`

**질문** — PG 심사용 '시크릿 노출 env 토글'에 LUCID_MIDNIGHT_PASS(24,900원) 상품 카드까지 묶을 것인가?

**선택지** — (a) 토글은 시크릿 콘텐츠 진입만 가리고 미드나잇 패스는 상시 노출 — 심사자가 '성인 콘텐츠 포함 구독'을 상품 설명에서 보게 된다. (b) 미드나잇 패스 카드도 같은 토글에 묶어 심사 중 미노출. (c) 미드나잇 패스를 심사 통과 후 별도 릴리즈로 출시.

**추천** — (b). docs/14 §C-#3이 확정한 '시크릿 정면 전략'은 성인 콘텐츠를 수용하는 PG를 미리 확보하는 것이지 심사 화면에서 감추는 것이 아니지만, docs/18 §1-D D2는 '시크릿을 완전 게이팅된 상태로 제출'을 요구한다. 상품 카드가 남으면 '게이팅됐다'는 주장과 화면이 어긋난다. 하나의 env 플래그가 콘텐츠 진입·상품 카드·카피를 동시에 제어하도록 설계하면 심사 후 노출이 스위치 1개로 끝나고, 승인 PG가 성인 수용사라면 (b)로도 정면 전략은 훼손되지 않는다. ⚠ '심사 후 몰래 활성화'는 docs/14가 배제한 경로이므로, 토글 해제 시점을 '승인 PG의 성인 콘텐츠 정책 확인 이후'로 문서에 명문화하는 것까지 이 안건의 산출물에 포함한다.

**이 답 없이 막히는 것** — 배치 2의 C-2.a~h(폐기 카탈로그 정리)와 docs/18 §3-D 문서 페이지·푸터 작업. 토글 범위가 정해져야 어느 카드를 지우고 어느 카드를 감출지 갈린다.

**선행 조사** — 없음 — 단 §1-D의 PG 복수 사전 문의 결과(성인 수용 여부)가 나오면 (b)의 해제 시점 문구가 확정된다

<sub>근거: F-1.d(재분류 — C-2.c/C-2.d/C-2.g로 흡수) · docs/18 §3-D '시크릿 노출 토글(env)' · §1-D D2</sub>

### 8. 시크릿 상점 진입면 정리 (극장 판매 중단 포함)  ·  `제품정책`

**질문** — 시크릿 상품의 '대상 캐릭터 선택' UI 제거를 확정하고, 극장(TheaterPortalPage)에서 시크릿 상품을 파는 것 자체를 중단할 것인가?

**선택지** — (a) docs/18 §4-B #5 추천대로 선택 UI 제거 + 현재 화자 자동 첨부 + 영구해금 카피를 '전 캐릭터'로. (b) 추가로 극장 상점에서 시크릿 탭 자체를 숨긴다. (c) 유지·수리.

**추천** — (a)+(b). ★ 재판정으로 전제가 갱신됐다: C-2.k와 E-1.13a는 중복이 아니라 별개 결함이다. C-2.k는 ChatPageV2 V2 init(:1113-1148)이 setCharacters를 호출하지 않아 선택 UI가 숨겨지되 currentCharacterId 폴백으로 구매가 성립해 '첫 히로인에 조용히 귀속'되는 오귀속이고, E-1.13a는 TheaterPortalPage가 리터럴 characters={[]} + currentCharacterId 미전달이라 구매 자체가 복구 불가 에러로 끝난다. (a)만 하면 극장 경로는 여전히 대상이 없어 깨진다 — 극장에는 '현재 화자' 개념이 없기 때문이다. 따라서 (b)가 논리적 귀결이고, LucidStore.jsx:486의 방어적 렌더링이 두 경로의 공통 안전망이다. ⚠ 결정 전에 E-1.13a를 개별 수정하면 헛일이 된다.

**이 답 없이 막히는 것** — 배치 2(결제 진입점 정리, C-2 전량)와 한 덩어리. 상태창 시크릿 업셀 배선(BiometricStatusPanel.jsx:103·222-236)도 같은 화면·같은 커밋.

**선행 조사** — 없음 — AWS 무관

<sub>근거: C-2.k(ChatPageV2.jsx:1113-1148·4336-4337 · LucidStore.jsx:486) · E-1.13a · docs/18 §4-B #5</sub>

### 9. UGC 캐릭터 나이 필드  ·  `스키마결정`

**질문** — UGC 캐릭터에 나이 필드를 도입할 것인가 — 도입 시 시크릿 나이 하드 게이트(PERSONA_UNDERAGE)를 UGC에도 적용할 것인가?

**선택지** — (a) 도입 + UGC 생성 폼 필수 입력 + 19세 미만 하드 거부(블록 B 페르소나와 동일 게이트 재사용). (b) 도입 + 어드민 심사에서만 검증 — 심사 전 SANDBOX 사용 구간이 뚫린다. (c) 미도입 — 프롬프트에서 age==null이면 Age 줄 자체를 생략(1줄).

**추천** — (a). 코드로 확정된 사실: Character.age는 필드로 존재하지만(Character.java:95) createUgc(:737-777)가 설정하지 않고 UgcCharacterSpec에도 age가 없어, CharacterPromptAssembler.java:135 character.getAge()가 null이 되고 프롬프트에 '- Age: null' 리터럴이 실린다(TheaterPromptAssembler:212도 동일). 게이트 사정권 밖이라 100% 도달한다. docs/16이 시크릿을 핵심 BM으로 올린 이상 '나이 미상 캐릭터에 시크릿 접근이 열리는' 상태는 법적 그라디언트의 가장 약한 고리이고, 블록 B(V25)가 페르소나에 이미 같은 하드 게이트를 넣어 문구·에러코드 선례가 있으므로 재사용 비용이 낮다. ★ 다만 (c)의 프롬프트 생략은 (a)를 택해도 기존 UGC 캐릭터 소급분을 위해 함께 필요하다 — 백필 전까지는 계속 null이 나간다(결정 불요 즉시 픽스로 분리했다).

**이 답 없이 막히는 것** — 배치 2의 '시크릿 게이팅 범위' 정의가 나이 없이는 성립하지 않는다. 배치 5(E-2.13/2.14) 착수.

**선행 조사** — 없음 — AWS 무관. 도입 시 기존 UGC 캐릭터 백필 정책(어드민 일괄 입력 vs 창작자 재입력 요청)을 함께 정할 것

<sub>근거: E-2.13(CharacterPromptAssembler.java:89·135) · E-2.14(TheaterPromptAssembler.java:212) · docs/16 §A · 블록 B PERSONA_UNDERAGE</sub>

### 10. 레거시 CG 트랙 최종 처분 (§G-6 ↔ §C#6 원칙 충돌)  ·  `제품정책`

**질문** — §G-6 레거시 캐릭터 CG 트랙을 서비스 계층까지 완전 동결할 것인가, 극장 자동 CG만 예외로 존치할 것인가, 전용 노브로 분리할 것인가?

**선택지** — (a) generateAutoIllustration(IllustrationService.java:208) 진입부에도 게이트 → 완전 동결. E-2.1·2.3·2.5·2.6·2.10·2.11·2.12가 전부 '무수정 종결'(배치 8의 레거시 CG 15건 + E-2 일러 맵 12건 소멸). 대가: 극장 챕터의 시각 산출물이 0이 된다. (b) 극장만 예외 명시 → §G-6이 '유저 요청 CG만 동결'로 축소되고 E-2.1(LoRA 맵 DB 일반화)·E-2.10·E-2.12가 되살아나 배치 8에서 배치 4로 승격. (c) ★ 전용 노브 분리 — legacy.illustration.theater-auto-cg-enabled를 신설하고 기본 오프. 세 안 중 유일하게 §C#6(극장 무변경)과 §G-6(트랙 동결)을 동시에 만족시키면서 되돌릴 수 있다. (d) 코드 폐지 + 갤러리를 씬 일러 열람처로 개편 → §C#6 '코드 보존'과 충돌, 작업량 최대.

**추천** — (c)를 우선 확정하고 (a)를 후속으로 둘 것을 권한다. 답을 위해 알아야 할 실측 3건: ① 게이트가 미이행이다 — legacy 검사는 IllustrationController.java:115 한 곳뿐이고 IllustrationService에는 0건이라 docs/18 §4-A ⑥의 '(b) 노브 차단 구현'은 절반만 적용됐다. ② 극장 세션마다 TheaterAutoNoteService:72/126/166 → :197 → generateAutoIllustration으로 ModelsLab 외부 과금이 계속 나가는데 유저 에너지 차감이 0이라 지표에도 안 잡히는 순지출이다. ③ processPollingInBackground가 자기호출이라 @Async가 무효이고(IllustrationService.java:252→:528), 최대 180초 폴링이 illust- 풀을 점유하며 CallerRunsPolicy 때문에 극장 배치 스레드가 폴링을 떠안는다 — (a)/(c)면 자동 소멸, (b)면 배치 4의 필수 항목으로 승격된다. '극장 무변경'(§C#6)의 대상은 극에 대한 유저 체감 동작이지 외부 GPU 과금이 아니라고 본다. ⚠ (a)를 서비스 계층 게이트로 곧장 넣는 것은 §C#6 정면 위반이라 종원 승인 없이는 불가하다. ⚠ D-2.k의 '비-success=실패' 전이는 ModelsLab status 값 집합 실측 전까지 보류 — 그대로 켜면 진행 중 생성을 죽이고 환불까지 나간다(웹훅 시크릿 필수화는 별개이며 즉시 가능).

**이 답 없이 막히는 것** — 배치 8 전체(레거시 CG 트랙 15건 · E-2 일러 맵 12건)와 배치 5의 E-2.10/2.11이 이 답을 기다린다. 안건 11(장소 어휘)의 실제 작업량도 여기서 갈린다 — 동결이면 IllustrationPromptAssembler.LOCATION_PROMPTS 맵 확장이 통째로 불요해진다.

**선행 조사** — 없음 — AWS 무관. 단 D-2.k '비-success=실패' 전이는 ModelsLab 중간 status 문자열 집합의 로그 수집(또는 벤더 문서 확인)이 선행이며, 이는 이 안건과 독립이다

<sub>근거: D-2.h · E-2.1 · E-2.10 · E-2.12 · R4 · N17 · N6(죽은 SANDBOX CG FAB) · N7(@Async 무효) · N8(무게이트 갤러리) · docs/18 §4-A ⑥ · §4-C #8 · docs/14 §G-6</sub>

### 11. V1·V2 장소 어휘 정본  ·  `스키마결정`

**질문** — V1 Location enum 노선을 (a)5값 확장 (b)시드 교정 (c)동적 배경 일원화 중 무엇으로 확정하고, 류설아 '옛 사당'·연화 '달빛 숲'을 V1·V2 어느 어휘로 통일할 것인가?

**선택지** — (a) enum 확장 5값(CATHEDRAL/TERRACE/STREET/LIBRARY + 사당) — 5줄, 컬럼이 varchar(20)에 CHECK가 없어 마이그레이션 불요. §G-5 '일원화' 방향엔 역행. (b) 기존 enum으로 시드 교정 — 성당/테라스에 대응 값이 없어 의미 손실. (c) 동적 배경 일원화 — §G-5 정합이나 방 생성부 신규 배선(MEDIUM), 그때까지 5캐릭이 계속 '저택 현관'에서 시작. 별도 축(V2): MOONLIT_FOREST/GARDEN_OF_MIRRORS를 기존 장소로 흡수(에셋 0) vs 정식 신설(배경 3장).

**추천** — (a) + V2는 흡수. docs/18 §4-B #1의 추천과 같되 두 가지를 더한다. 첫째, ①.11의 '옛 사당'은 V2의 ANCIENT_SHRINE으로 어휘를 맞춰 enum에도 ANCIENT_SHRINE으로 넣는다 — 같은 장소에 네임스페이스별로 다른 토큰을 쓰면 나중 (c) 일원화 때 매핑표를 또 만들어야 한다. 둘째, ★ 이 안건은 안건 10보다 뒤에 답해야 한다 — 레거시 CG가 동결되면 (a)의 통상 동반 수정이던 IllustrationPromptAssembler.LOCATION_PROMPTS 맵 확장이 사문화돼 실제 작업량이 바뀐다. 노선과 무관하게 즉시 넣을 것 3건은 결정 불요 목록으로 분리했다(①.6/①.9 복붙 오배정 · ①.13/①.14 무로그 파서와 시더 검증기 · N15 死코드).

**이 답 없이 막히는 것** — 배치 5 전체 — E-3 ① 15건 · ② 14건 · E-2.10 · E-2.11. 시크릿 시드 파일(charactersm.yml 4행: claire CATHEDRAL @399/411 · rosetta TERRACE @476/488) 활성화도 이 값이 정해져야 동기화된다.

**선행 조사** — 안건 10(레거시 CG 처분)을 먼저 답할 것. AWS 무관.

<sub>근거: E-3.①.1(application-characters.yml:599 · ChatRoom.java:348·380·774·978·1015) · E-3.①.11 · E-3.②.3 · E-2.10 · N15 · docs/18 §4-B #1</sub>

### 12. §G-5 해금 노브 사각지대 — 시크릿 전용 복장 예외  ·  `제품정책`

**질문** — NEGLIGEE(Outfit.java:17이 '시크릿 전용'으로 선언)만 isSecret 차집합으로 분리하는 것이 §G-5 '해금 게이트 오프' 철회에 해당하는가? 즉 성인인증 없는 유저에게 negligee 자산이 첫 턴부터 열리는 현 상태를 허용할 것인가?

**선택지** — (a) 현행 유지 — §G-5를 문자 그대로 적용. (b) 시크릿 전용 복장(NEGLIGEE)만 isSecret 게이트로 분리 — Character.java:525에 secretOnly 차집합 1줄, 나머지 개방은 §G-5 의도대로 유지. (c) 노브를 true로 되돌려 §G-5 철회 — 승급 세리머니 unlocks(ChatStreamService:820-825)도 함께 부활, 제품 결정 번복.

**추천** — (b). 실측 3건으로 정리한다. ① 노브 동작은 확정이다 — Character.java:525·:543 `if (isSecret || !relationGated) return all;`이 STRANGER 첫 턴에 전 tier 합집합을 반환하고, 소비처 4곳(CharacterPromptAssembler:456-457·797-798, ChatService:304-305, DirectorPromptAssembler:44-45)이 전부 노브를 읽는다. 극장 2곳만 `/* relationGated */ true` 하드코딩이라 CLAUDE.md §2-5 규약대로 고정돼 있다. ② ★ 지시받은 가설(E-3 ①/②의 도달성이 '승급 후'에서 '첫 턴부터'로 상승)은 반증됐다 — ①의 유령 키는 전부 default-location/baseLocations(base tier)라 getBaseLocationSet()을 거쳐 노브와 무관하게 원래부터 첫 턴에 실렸고, ②는 WorldLocation/루틴 시더 경로라 getAllowedLocations를 아예 타지 않는다. 따라서 배치 5 시드 교정은 이 답을 기다릴 필요가 없다. ③ 실제 영향은 3캐릭터뿐이다 — characters.yml에서 unlock 시드가 살아 있는 건 아이리(153-158: DATE,PAJAMA/DOWNTOWN · SWIMWEAR/BEACH · NEGLIGEE/BAR)·태리(376,378)·루나(499)이고 나머지 6캐릭은 전부 주석 처리다. 남는 진짜 위해는 하나다: Outfit.java:17이 NEGLIGEE를 시크릿 전용으로 선언하는데 실제 게이트는 LOVER뿐이었고 그마저 사라져, 성인인증 없는 유저에게 UseResourcePreloader.js:158이 negligee_*.png를 첫 턴부터 프리로드한다. docs/16 법적 그라디언트에 어긋나는 유일한 지점이므로 1줄로 좁히는 게 맞다. 트레이드오프: (b)는 §G-5와 미세하게 어긋나지만, §G-5가 죽인 대상은 LOCK 규칙이지 성인 자산 게이트가 아니다.

**이 답 없이 막히는 것** — 배치 2(성인인증 게이팅 범위)와 3-F(시크릿 이미지 마스킹 파이프라인)의 '무엇이 성인 자산인가' 목록. 배치 5(E-3 시드 교정)는 위 ②로 종속이 풀렸다.

**선행 조사** — 없음 — AWS 무관

<sub>근거: 신규(지시 조사) · Outfit.java:17 · Character.java:525·543 · UseResourcePreloader.js:158 · application-characters.yml:153-158 · docs/14 §G-5 · docs/16 §A</sub>

### 13. BRANCH eventContext 신뢰 경계 — 나레이션 저장 가시성  ·  `운영정책`

**질문** — /director/auto-respond가 directive 발급 사실을 검증하지 않고 클라이언트 eventContext를 프롬프트와 영구 SYSTEM 로그에 그대로 싣는다. 부속이 아니라 핵심 질문 — BRANCH 나레이션을 visible SYSTEM으로 남길 것인가, hiddenSystem으로 되돌릴 것인가?

**선택지** — 저장 가시성: (가) 현행 visible SYSTEM 유지 (나) hiddenSystem 복귀. 검증 방식: (a) 서버 발급 branchToken으로 옵션 원본을 조회해 label·본문을 서버가 확정 — 근본해. (b) 길이·인젝션 가드 + encapsulate 3단 방어(E-5.1.b 원 수정안, 좌표를 ChatStreamService:1338 진입부로 이전). (c) 인젝션 CRITICAL 감지 시 요청 자체를 400.

**추천** — (나) + (a)+(c) 병행. 사람 결정은 (나)다 — 블록 D 이후 BRANCH 나레이션이 ChatLogDocument.system(visible, ChatStreamService.java:1400)으로 저장돼 LLM 컨텍스트와 유저 히스토리 양쪽에 남고, ChatService의 SYSTEM 삭제 금지 때문에 유저가 지울 수도 없다 — 오염이 영구화되는 유일한 채널이다. 검증 쪽은 코드가 답한다: (b) 단독은 부족하다(N9가 지적한 '임의 나레이션 무제한 주입'은 인젝션이 아닌 평범한 문장으로도 성립하므로 인젝션 가드로는 안 막힌다). (a)가 근본해이고, 같은 취약을 극장(B-4.e '분기가 실제로 제시됐는지 검증 없음')과 정확히 동형으로 공유하므로 branchToken 설계 1회로 두 트랙이 함께 닫힌다 — 이게 (a)를 택할 결정적 이유이자 순서를 명시해야 하는 이유다(따로 고치면 branchToken을 두 번 만든다). (c)는 (a) 배포 전까지의 임시 방어로 즉시.

**이 답 없이 막히는 것** — 배치 1(N9 착취 차단)과 E-5.1.b 수정. 극장 B-4.e도 같은 설계를 기다린다.

**선행 조사** — 없음 — AWS 무관. 극장 branchToken은 이미 존재하고 FE가 보내고 있다(TheaterGameplayApi.js:24-28 · TheaterPlayPage.jsx:349 · TheaterBranchService.java:327-329) — docs/17 §G 회귀위험 6번의 'B-4.a 3단 롤아웃 필수'는 그 이전 기준이므로 정정이 필요하다

<sub>근거: E-5.1.b(ChatStreamService.java:1400·1372-1375·1255-1257 · StoryController.java:99·111·158) · N9 · B-4.e</sub>

### 14. BRANCH 과금 폴백 정책 (V2 + 극장 토큰 만료 통합)  ·  `과금설계`

**질문** — 서버 발급 가격표·토큰이 없거나 만료됐을 때 거부할 것인가 관용할 것인가 — V2 BRANCH(가격표 캐시 TTL 600s)와 극장 branchToken(스냅샷 폴백)에 대해 같은 답을 쓴다.

**선택지** — (a) 캐시/토큰 부재 시 400 거부 — 착취면은 닫히지만 정상 유저가 모달을 오래 열어둔 것만으로 실패한다. (b) 관용 유지(현행 orElse(1) / 스냅샷 폴백) — 4E 카드를 1E에 사는 창이 남고 FE 표기와 실제 차감이 어긋난다. (c) ★ 가격표를 Redis TTL이 아니라 directiveId/turn 멱등 키로 묶어 만료 자체를 없앤다 — 거부도 관용도 필요 없어진다.

**추천** — (c)를 우선 검토하고, 불가하면 (a). 지금의 orElse(1) 폴백은 chosenIndex를 빼고 보내기만 해도 4E 분기가 1E가 되는 순수 착취면이라(DirectorService.java:271·277-284) 관용의 대가가 착취면인 구조다. (c)는 '만료'라는 개념을 없애 두 갈래를 동시에 해소하고, 극장 branchToken도 putBranchContext에 옵션 원본을 함께 담으면 FE 무수정·BE 단독으로 닫힌다. ⚠ 두 트랙이 다른 답을 쓰면 안 된다 — 같은 질문이므로 한 번에 확정할 것. 결정 불요 즉시 3건은 이 안건에서 분리했다(가격표 evict 위치 이동 · final_result에 서버 확정 cost·잔여 에너지 · FE 이중차감).

**이 답 없이 막히는 것** — 배치 1의 착취 차단 범위. docs/18 §2-A 5단계(3분기 카드 실행 검증)의 '무엇이 정상인가' 판정 기준. 극장 B-4.a 롤아웃 형태.

**선행 조사** — 없음 — AWS 무관

<sub>근거: 회귀스캔 #2 · N9 · N12(DirectorService.java:285-286) · newDefects의 BRANCH 폴백 항목 · B-4.a · docs/14 §G-13</sub>

## B. 배치 진행 중 필요 (7건)

### 15. legacy 게이트 상태를 클라이언트에 내려줄 것인가  ·  `제품정책`

**질문** — 선행 질문 — 엔딩·업적·레거시 CG 노브를 런칭 후 되켤 계획이 있는가(영구 폐지인가 임시 차단인가)? 그 답에 따라: 차단된 기능의 FE 진입점을 계약(플래그)으로 숨길 것인가, 파일마다 개별 삭제할 것인가?

**선택지** — (a) /users/me 또는 로비 부트스트랩 응답에 features 플래그(ending·achievement·legacyCg) 3종을 노출하고 FE가 진입점을 조건부 렌더 — 노브를 되켜면 FE 수정 없이 되살아난다. (b) FE에서 해당 진입점을 개별 삭제/주석 — 지금은 가장 싸지만 노브 부활 시 FE를 다시 고쳐야 한다. (c) 현행 유지 — 유저에게 '고장'으로 보이는 상태(빈 갤러리 탭·400 뜨는 버튼)를 감수한다.

**추천** — (a). 현재 서버가 게이트 상태를 응답에 실어주는 경로는 0건이다(LegacyFeatureProperties 참조 9파일 전수 — 전부 서비스 내부 가드). 이 결정 하나가 최소 4개 결함의 수정 형태를 동시에 정한다 — 자유·스토리 엔딩 '다시 보기' 버튼(ChatPage.jsx:3337 / ChatPageV2.jsx:4238, 400을 3회 재시도), 로비 보관함 '업적' 세그(ArchiveTab.jsx:33·88, 항상 0/0), 채팅 내 업적 버튼(ChatPage.jsx:3022 / ChatPageV2.jsx:3897), 레거시 CG 갤러리(IllustrationController.java:146-154 무게이트, 신규 유저 전원에게 영구 빈 목록). (b)를 택하면 같은 파일을 두 번 고치게 되고 §C#6의 '코드 보존, 진입만 차단' 원칙과도 어긋난다. 트레이드오프: /users/me 응답 계약이 넓어지고, 이 응답이 프로필 캐시를 타므로 노브 변경 시 캐시 무효화 경로를 함께 봐야 한다(D-6.6과 물린다).

**이 답 없이 막히는 것** — 배치 7(FE 단독) 전체의 착수 형태 — 지금 개별 삭제로 진행하면 (a) 채택 시 버려진다. newDefects #4 · #9 · #21, F-3.a의 FE 수정 지점. docs/14 §G-6 '빈 갤러리 방치 금지'의 이행 방식.

**선행 조사** — 안건 10(레거시 CG 처분)과 안건 2(엔딩 게이트)의 답이 '영구/임시' 판단의 재료다 — 세 노브의 영구성을 한 번에 답할 것

<sub>근거: newDefects #4 · #9 · #21 · F-3.a · docs/14 §G-6 · §C#6</sub>

### 16. 씬 일러 좌표계·보존 정본 (docs/18 §4-B #3 통합)  ·  `스키마결정`

**질문** — 씬 일러 게이트를 turnIndex(hidden 로그 포함) 좌표계로 유지하며 마이그레이션할 것인가 좌표계 비의존으로 갈아탈 것인가 — 그리고 V2 리셋 시 기존 씬 일러를 삭제할 것인가 playthroughSeq로 분리 보존할 것인가? (두 질문은 하나의 정본 선택이다)

**선택지** — (a) 마이그레이션 + 삭제: SceneIllustration.turnIndex를 hidden 제외 좌표계로 재계산(V28) + cascadeResetRoom(StoryV2Service.java:781-819)이 씬 일러도 삭제. 코드 최소지만 TIME_SKIP hidden 로그 존치가 확정이라 드리프트가 재발하고 §G-6 '빈 갤러리 금지' 위반. (b) 비의존 판정 + 보존: 게이트를 '마지막 완료 씬 id' 기준으로 바꾸고 리셋은 playthroughSeq만 증가. 마이그레이션 불요, 갤러리 보존. (c) 현행 유지.

**추천** — (b). docs/18 §4-B #3이 리셋 쪽만 이미 (b)를 추천하는데, turnIndex 쪽을 (a)로 두면 같은 기능에 좌표계가 둘 공존한다. 결정적 근거는 TIME_SKIP hidden 로그가 존치 확정이라는 점 — 마이그레이션은 1회성인데 원인이 그대로 살아 있어 다음 릴리즈에 또 어긋난다. V28 한 번을 아끼는 게 아니라 재발을 닫는 선택이다. ★ 실행 조건: 소비처 3곳(useSceneIllustrations.js:148-155 · SceneRequestService.java:75·123 · ChatController.java:94-99)을 같은 커밋에서 함께 바꿔야 E-1.8a/8b가 갈라지지 않는다.

**이 답 없이 막히는 것** — 배치 3(E-4.7 cascadeResetRoom)과 배치 7(E-1.8a/8b FE 판정)이 동시에 막혀 있다. 안건 10이 완전 동결로 가면 갤러리의 유일한 콘텐츠 공급원이 씬 일러가 되므로 보존 노선의 중요도가 더 올라간다.

**선행 조사** — 안건 10(레거시 CG 처분) 이후가 자연스럽다. AWS 무관.

<sub>근거: E-4.7(StoryV2Service.java:781-819 · SceneRequestService.java:75-78·82-85·123-128) · E-1.8a(useSceneIllustrations.js:148-155 · ChatController.java:94-99) · E-1.8b · N8 · docs/18 §4-B #3</sub>

### 17. 극장 스탯 리롤 과금 + §C#6 경계 + 미드나잇 초기 스탯  ·  `과금설계`

**질문** — 세 가지를 한 번에 — ① 극장 스탯 리롤 과금 모델(현재 무제한·무과금)을 무엇으로 확정할 것인가? ② §C#6 '극장 무변경'의 경계는 어디까지인가 — 과금 정합·스키마 변경·버그픽스는 예외로 취급해도 되는가? ③ 미드나잇 극장 초기 스탯 정본이 40/20이 맞는가(코드는 500/100)?

**선택지** — ①: (a) 에너지 N 차감(UGC 리롤 2E 관례와 정합) (b) 리롤권 상품 신설(ProductType + 인벤토리) (c) 기능 제거. ②: (가) 유저 체감 동작만 불변, 과금·스키마·버그픽스는 예외 (나) 코드 전체 동결. ③: (가) 40/20으로 교정 (나) 500/100 유지.

**추천** — ①은 docs/18 §4-B ④의 추천안 '에너지 3E + yml 노브'를 그대로 확정하되 보완 하나 — 극장 진입 자체가 90~100 에너지짜리 큰 지출이므로 '첫 리롤 1회 무료 + 이후 3E'가 이탈 방지 측면에서 낫다. (b)는 상품 신설·인벤토리·환불 경로까지 딸려 와 런칭 전 공수로 과하고, (c)는 아바타 스탯이 극 전개에 직접 관여하는 구조상 UX 후퇴다. 지금 상태로 라이브하면 무제한 무과금이 선례가 되어 이후 과금 도입이 '기능 회수'로 보인다. ②는 (가)를 권한다 — 이 한 줄이 확정되면 과금 워터마크 영속·극장 자동 CG 노브·종착 가드가 한꺼번에 풀린다. ③은 (가) 40/20 — 같은 파일에서 STANDARD가 20/10이고 javadoc이 '총 40포인트, 단일 스탯 최대 20'을 명시하는데 상수만 500/100이다(TheaterLobbyService.java:84-90). 2배 티어 패턴 + javadoc + FE 카피 세 신호가 일치하므로 디버그 잔재로 확정적이나, BM 의도만 한 줄 확인받는다.

**이 답 없이 막히는 것** — 배치 4(극장 구조) — B-6.1. ② 답이 없으면 극장 과금 워터마크 영속(컬럼)·자동 CG 노브 분리·엔딩 종착 가드가 전부 '§C#6 위반인가'로 막힌다.

**선행 조사** — 없음 — AWS 무관

<sub>근거: B-6.1 · docs/18 §4-B ④ · TheaterLobbyService.java:84-90 · docs/14 §C#6</sub>

### 18. 승급 세리머니 진동 완충  ·  `제품정책`

**질문** — '임계 도달 즉시 승급'(종원 확정 (b))을 유지하면서 경계선 진동 시 세리머니가 무제한 반복되는 것을 어떻게 막을 것인가?

**선택지** — (b) 단계별 세리머니 1회만 기록 — 도달 이력 컬럼 신설(V28 이후 1컬럼). (c) 재승급 쿨다운 N턴. (d) 현행 유지 — 유저는 몇 턴 간격으로 같은 'Relationship Up' 모달을 다시 본다.

**추천** — (b). 실측으로 진동은 실재한다 — RelationStatusPolicy.fromStats의 maxStat이 현재 스탯에서 매번 계산되므로 39↔40 왕복이 가능하고, 강등은 무연출·승급만 세리머니라 같은 모달이 반복된다(ChatStreamService.java:792-828). (c)는 쿨다운 중에 일어난 진짜 승급을 놓친다. 원래 후보였던 'maxStat을 임계값으로 스냅'은 배제했다 — 서버가 스탯 값을 임의 조작하는 것이라 상태창 5축 수치의 신뢰를 깨고, 블록 B의 렌즈 서술자가 바로 그 수치를 읽어 문장을 만든다. (b)는 '연출은 최초 도달 1회'라는 자연스러운 계약이고 블록 D가 확정한 '강등은 무연출'과도 대칭이 맞는다. 비용은 마이그레이션 1컬럼.

**이 답 없이 막히는 것** — 배치 3(BE 단독)의 승급 계열 E-4.1/4.2 후속. 안건 22(dynamicRelationTag)를 포함한 상태창 재작성도 승급 이벤트 계약이 확정돼야 표시를 맞출 수 있다.

**선행 조사** — 없음 — AWS 무관. 마이그레이션 번호는 V28 이후(V26·V27은 블록 D 선점)

<sub>근거: R3(ChatStreamService.java:792-830) · R5 · R6 · N13 · docs/14 §G-1 · 종원 확정 (b)</sub>

### 19. 동일인 다계정·CI 중복  ·  `제품정책`

**질문** — 이미 가입된 이메일로 다른 provider 로그인을 시도했을 때의 정책과, 성인인증 CI가 다른 계정에 이미 묶여 있을 때 유저에게 무엇을 보여줄 것인가?

**선택지** — (A) 동일 이메일 자동 계정 연동. (B) '해당 provider로 로그인해 주세요' 안내 리다이렉트. (C) 이메일 없이 별도 계정 허용. 그리고 성인인증 실패 사유(미성년/CI 중복/기관 오류)를 (가) 전부 구분 노출 (나) CI 중복만 구분 (다) 통합 문구.

**추천** — (B)+(나). 근거: OAuth2LoginSuccessHandler는 findByProviderAndProviderId 미스 시 무조건 새 User를 만들고 username 충돌만 회피하는데(:129-140, :162-172, :190-200), User 엔티티는 email·ci_hash 양쪽에 unique 인덱스(User.java:18-19)가 걸려 있다. 결과가 두 겹이다 — ① 같은 이메일이면 신규 저장이 DataIntegrityViolation 500으로 영구 잠기고 ② 500을 피하더라도 ci_hash unique 때문에 같은 사람이 두 번째 계정에서 성인인증을 절대 통과할 수 없다. 즉 다계정을 허용하는 순간 결제한 시크릿이 '인증 안 되는 계정'에 묶이는 CS가 확정적으로 발생한다. (A) 자동 연동은 이메일 소유 검증 없이 계정을 합치는 것이라 계정 탈취면이 열려 권하지 않는다. ⚠ 제약: CI 중복 문구는 '이미 다른 계정에서 인증된 정보입니다'까지만 — 어느 계정인지 특정하면 개인정보 노출이다. 미성년 판정은 사유를 구분해 노출하되 재시도를 유도하지 않는 문구로.

**이 답 없이 막히는 것** — 배치 2(성인인증 개통) · 배치 7(FE 성인인증 모달 E-1.12a/b). 시크릿 BM 오픈 전에 정해지지 않으면 결제-인증 귀속 사고가 라이브에서 발생한다.

**선행 조사** — 없음 — AWS 무관

<sub>근거: E-7.1.a(OAuth2LoginSuccessHandler:129-140·162-172·190-200 · User.java:18-19) · E-1.12b</sub>

### 20. 승인 후 텍스트 수정 정책 (UGC)  ·  `제품정책`

**질문** — 공개 승인된 UGC 캐릭터·월드의 텍스트를 사후 수정했을 때 심사로 되돌릴 것인가?

**선택지** — (A) 수정 허용 + 심사 대상 필드가 바뀌면 PENDING_PUBLIC으로 자동 회귀. (B) 공개 중에는 수정 금지(비공개 전환 후 수정). (C) 마지막 승인본을 스냅샷으로 저장해 프롬프트에는 승인본만 주입하고 편집본은 비공개에서만.

**추천** — (A). 근거는 같은 저장소의 선례다 — UgcCharacterService.linkWorld는 'PUBLIC 캐릭터엔 APPROVED 월드만' + '심사 중 월드 교체 금지'를 2026-07-20 종원 확정으로 이미 강제하는데, 정작 updateTexts(:88-93)에는 visibility 검사가 한 줄도 없고 UgcWorldService.updateWorld(:396-435)도 requireNotUnderReview(심사 '중'만)뿐이다. 즉 심사를 통과시킨 뒤 personality/tone/firstGreeting을 통째로 갈아끼우는 것이 지금 무료·무제한으로 가능하고, 그 텍스트는 CharacterPromptAssembler.java:227-228로 그대로 주입된다. (B)는 창작자 마찰이 크고, (C)는 스냅샷 스키마가 붙는데 '보이는 것과 동작하는 것이 다른' 새 혼선을 만든다. ★ 캐릭터 텍스트·월드 lore·월드 장소 세 경로에 같은 원칙을 동시에 적용해야 우회면이 안 생긴다 — 하나라도 빠지면 그쪽으로 몰린다.

**이 답 없이 막히는 것** — 배치 6(어드민 심사) · E-5.2.a·E-5.3.a·E-7.2가 같은 메서드라 한 커밋. 결정 전에 E-5.3.a의 리셋만 넣으면 실효가 없다.

**선행 조사** — 없음 — AWS 무관

<sub>근거: E-5.2.b(UgcCharacterService.java:88-93) · E-5.3.b(UgcWorldService.java:396-435 · AdminUgcReviewService.java:63-74 · CharacterPromptAssembler.java:227-228)</sub>

### 21. UGC 좀비잡 회수·보상  ·  `운영정책`

**질문** — POSTPROCESSING/BINDING 구간에서 고착된 캐릭터 생성 잡과, 서버 귀책(배포·크래시)으로 죽은 잡을 어떻게 회수·보상할 것인가?

**선택지** — 좀비 회수: ① 누끼 재개(추가 GPU 소량, 유저는 캐릭터 수령) ② 실패 처리 + 전액 환불(완주분 폐기). abandon 보상: (a) 최종 구간 도달 잡은 전액 환불 (b) 스윕이 회수 실패로 마킹한 잡만 환불 (c) 현행 무환불 + CS 수동.

**추천** — ①+(b). GPU 완주분은 이미 지출됐으므로 ②는 '돈도 쓰고 결과도 버리는' 최악의 조합이고, 누끼 재개는 잔여 비용이 가장 싼 마지막 단계다. abandon은 (a)로 넓히면 '최종 구간까지 진행시킨 뒤 포기'로 전액 회수하는 파밍이 성립하므로, 서버가 스스로 실패로 마킹한 잡만 환불하는 (b)가 착취면 없이 귀책을 반영한다. ⚠ 시급성의 근거: 현재는 D-3.1a/b/d가 안 고쳐진 탓에 좀비 유저의 유일한 탈출구가 '무환불 abandon'이라 사실상 유저가 비용을 전부 문다 — CS 폭탄이 되기 전에 닫아야 한다.

**이 답 없이 막히는 것** — 배치 3(자산 손실 정지)의 D-3 전량. CharacterCreationService.java:467-489 confirmReview와 스윕 스케줄러를 같은 커밋에서 손대야 해 순서가 물린다.

**선행 조사** — 없음 — AWS 무관. 다만 실제 좀비 잡 건수는 복구 후 1쿼리로 확인하면 보상 규모를 가늠할 수 있다

<sub>근거: D-3.1b · D-3.1c · CharacterCreationService.java:467-489</sub>

## C. 나중 (1건)

### 22. dynamicRelationTag 존폐  ·  `제품정책`

**질문** — 상태창 재작성에서 렌더가 사라진 dynamicRelationTag를 복원할 것인가, 폐지하고 BE 생성 경로까지 걷어낼 것인가?

**선택지** — (a) 헤드라인 카드에 복원. (b) 폐지 + FE의 setDynamicRelationTag와 BE 생성 프롬프트까지 제거 — 매 턴 토큰 절감이 실현된다. (c) 현행 방치 — BE는 매 턴 만들어 저장하고 FE는 prop으로 받아 버린다. 어느 쪽 이득도 없다.

**추천** — (a). 지금 상태창의 '지금 이 관계'는 statusLevel 5종 고정 문구만 돌려주므로 20턴을 진행해도 같은 카피가 반복되는데, 이는 블록 B가 렌즈 서술자로 '이 관계만의 문장'을 만든 방향과 정면으로 어긋난다. 매 턴 LLM이 생성하는 유일한 관계 고유 문장이고 이미 BE 비용을 치르고 있으므로 화면에 되돌리는 쪽이 한계비용 0이다. ⚠ 다만 (c)만은 반드시 종결해야 한다 — 커밋 메시지에 폐지 언급이 없는 채로 렌더만 사라진 상태라, 방치하면 다음 세션이 또 같은 판단을 반복한다. (b)를 택한다면 BE 생성까지 같은 커밋에서 지워야 절감이 실현된다. 복원 위치는 docs/17_assets/hud_redesign_mockup.html 설계와 대조해 확정할 것.

**이 답 없이 막히는 것** — 배치 7(R16). 안건 18(승급 계약)·상태창 재작성과 같은 파일이므로 함께 결정하는 편이 낫다.

**선행 조사** — 없음 — AWS 무관. docs/17_assets/hud_redesign_mockup.html 대조 필요

<sub>근거: R16(BiometricStatusPanel.jsx:94-105 · 구 fbc27ac^:378-384 · ChatPage.jsx:2387 · ChatPageV2.jsx:3222)</sub>

---

## D. 결정 불요 — 지금 착수 가능 (33건, 우선순위순)

> 어떤 결정으로도 버려지지 않는 것만 담았다. 상위 3건은 **지금 서버가 뜨지 않고 dev 채팅 화면이 로드되지 않는 상태**라 다른 모든 검증의 선행 조건이다.

### D-1. [P0 · ONE_LINE] MongoConfig basePackages에 "com.spring.aichat.domain.ending" 추가 — 단독 커밋으로 선행 푸시

`aichat/src/main/java/com/spring/aichat/config/MongoConfig.java:34-38 (관련: domain/ending/EndingResultRepository.java:1,8 · service/theater/TheaterEndingService.java:57)`

HEAD가 기동되지 않는다. EndingResultRepository 빈이 생성되지 않아 TheaterEndingService 생성자 주입이 실패하고 ApplicationContext 로딩이 죽는다. 극장 엔딩이 아니라 서비스 전체가 안 뜬다. 컴파일·유닛테스트 116건으로는 원리적으로 못 잡는다.

### D-2. [P0 · ONE_LINE] processEasterEgg에 unlock null 가드 — null이면 achievement=null인 EasterEggEvent를 반환

`aichat/src/main/java/com/spring/aichat/service/stream/ChatStreamService.java:1096-1100 (게이트 AchievementService.java:67 · 전파 도착 :340-345)`

업적 게이트 오프(기본값)에서 AchievementService.java:67이 null을 반환하는데 유일 호출부가 unlock.code()를 무가드 역참조한다. catch가 IllegalArgumentException만 잡아 NPE가 TX-2 밖 catch(Exception)(:340)로 가서 compensateFullRollback + TX_ERROR — 유저는 본문을 다 본 뒤 턴 전체를 잃는다. SANDBOX 주력 표면이고 프롬프트(CharacterPromptAssembler.java:543·565)가 여전히 유도한다.

### D-3. [P0 · ONE_LINE] FE 댕글링 named import 2줄 제거 — sendEventSelectStream

`LucidChat-Front/src/pages/ChatPage.jsx:26 · LucidChat-Front/src/pages/ChatPageV2.jsx:40`

UseChatStream.js에서 export가 삭제됐는데 import가 남아, vite dev에서 두 채팅 페이지 모듈이 SyntaxError로 로드 실패한다(브라우저 실증 완료: 'does not provide an export named sendEventSelectStream'). 프로덕션 빌드는 트리셰이킹으로 통과해 CI가 못 잡는다. 로컬 개발이 통째로 막힌다.

### D-4. [P1 · ONE_LINE] EndingEligibilityService.processDirectorTrigger에 checkAndActivateEligibility와 동일한 게이트 추가 + 'V2 경로는 원래 업적 배선이 없었음' 주석

`aichat/src/main/java/com/spring/aichat/service/story/EndingEligibilityService.java:96-118 (발동 :114 · 호출부 ChatStreamServiceV2.java:685)`

게이트가 :62에만 있어 기존 ending_eligible=true 방이 :114 markEndingReached까지 도달한다. 안건 2의 데이터 처분이 어느 쪽으로 결정되든 코드 구멍은 닫아야 하고, 주석이 없으면 다음 세션이 같은 추적을 반복한다.

### D-5. [P1 · ONE_LINE] EndingService에 null 가드 3줄 (E-4.9 원 결함) — 노브 부활 전 선행 조건

`aichat/src/main/java/com/spring/aichat/service/EndingService.java:69-74 (게이트 EndingController.java:44)`

엔딩 노브를 나중에 켜면 P0 500이 그대로 부활한다. 지금 게이트 뒤라 도달 불가일 뿐 결함은 살아 있고, 3줄이라 지금 넣는 비용이 사실상 0이다.

### D-6. [P0 · ONE_LINE] User.consumeEnergy 음수 인자 가드

`aichat/src/main/java/com/spring/aichat/domain/user/User.java:166 (역연산 refundEnergy :187)`

docs/17 §F #1 — 1줄이고 전 경로 착취의 뿌리다. 어떤 결정에도 종속되지 않는다.

### D-7. [P1 · SMALL] FE BRANCH 이중차감 제거 — handleSelectEvent의 낙관 차감을 없애고 triggerAutoDirectorResponse에만 맡긴다

`LucidChat-Front/src/pages/ChatPage.jsx:1809+1813 → :468-469 · ChatPageV2.jsx:2646+2650 → :618-619`

같은 energyCost를 두 번 깎아 화면 잔여가 서버의 2배로 줄어들고, isNoEnergy 판정이 클라 값으로 이뤄져 살 수 있는 카드가 잠긴다(구매 유도로 오인될 여지). 블록 D가 오차를 +1에서 정확히 2배로 키웠다.

### D-8. [P1 · SMALL] resolveBranchCost의 가격표 evict를 TX-1 밖·차감 성공 후로 이동

`aichat/src/main/java/com/spring/aichat/service/director/DirectorService.java:285-286 (호출 ChatStreamService.java:1361-1364)`

Redis evict는 롤백 대상이 아니라, consumeEnergy 실패나 스트림 예외로 TX가 롤백돼도 가격표만 사라진다. 정상 유저가 재충전 후 같은 분기를 다시 고르면 캐시 미스로 4E 카드가 1E가 된다 — 착취면이자 정상 유저 손해.

### D-9. [P2 · SMALL] final_result에 서버 확정 cost·잔여 에너지를 실어 FE가 되맞추도록 계약 확장

`aichat/.../ChatStreamService.java:1362-1364 · LucidChat-Front/src/api/UseChatStream.js:160-164`

서버는 chosenIndex로 비용을 재판정하는데 FE는 카드 표시가로만 차감하고 재동기화 경로가 없다. SSE 응답 어디에도 잔여 에너지가 없어 새로고침 전까지 복구되지 않는다. 안건 14의 답이 무엇이든 이 계약은 필요하다.

### D-10. [P1 · MEDIUM] 극장 엔딩 동시 발동 차단 — TheaterState 조회를 PESSIMISTIC_WRITE로 분리 + persistEnding upsert + prod Mongo 인덱스 수동 생성을 운영 런북에 등재

`aichat/.../service/theater/TheaterEndingService.java:75-92·137-166·175-197 · domain/ending/EndingResultDocument.java:37-40 · src/main/resources/application-prod.yml:20 · domain/theater/TheaterStateRepository.java:11`

triggerEnding이 락 없이 읽고 LLM 20~60초를 기다리는 사이 새로고침하면 두 요청이 모두 가드를 통과한다. prod는 auto-index-creation:false라 유니크 인덱스가 없어 중복 문서가 남고, 이후 findByRoomId가 IncorrectResultSizeDataAccessException으로 영구 500이 된다(로컬은 인덱스가 있어 재현되지 않는 'works on my machine'). 인덱스 키는 @Field("room_id")이므로 room_id다.

### D-11. [P2 · SMALL] TheaterEndingCredits에 진행 중 POST ref 가드 + '엔딩을 쓰는 중입니다(최대 1분)' 로딩 문구

`LucidChat-Front/src/pages/TheaterEndingCredits.jsx:91-101 · :155-162`

텍스트 없는 스피너가 20~60초 떠 있어 새로고침을 유발하고, 그 새로고침이 위 TOCTOU의 실제 발생 경로다. 같은 커밋에서 처리하면 재발 원인이 함께 닫힌다.

### D-12. [P2 · SMALL] buildDramaContext의 분기 label 국소 완화 — 개행 제거 + 80자 truncate + 구분자 이스케이프

`aichat/.../service/theater/TheaterEndingService.java:399-428 (특히 414-417, 주입 :262-266·277) · 오염원 TheaterBranchService.java:314·341`

클라이언트가 자유 입력한 최대 200자 label 8건이 '유저가 실제로 겪은 이야기다 / 반드시 반영하라'는 신뢰 문구와 함께 엔딩 시스템 프롬프트에 무검증 주입된다. 안건 13의 branchToken 근본해가 배포될 때까지의 임시 방어이며, TheaterAutoNoteService.java:115가 이미 truncate(...,80) 선례를 만들어 뒀다.

### D-13. [P2 · MEDIUM] 극장 종착 가드 3건 — ① isEndingPoint를 isLastAct && currentChapter > threshold로 좁힘 ② requestNextBatch 종료 가드를 isEndingReached() || isEndingPoint()로 확장 ③ progress DTO의 transitionToNewAct를 !isLastAct로 좁히고 endingReady를 노출

`aichat/.../service/theater/TheaterDirectorEngine.java:279-291 · TheaterService.java:64 · :264·:308-323·:338-350 · FE TheaterPlayPage.jsx:384-395 · TheaterResponses.java:318`

현재는 ACT_4 Ch4를 '시작한 순간'부터 엔딩 API가 열려 직접 호출로 27씬을 영구히 잃을 수 있고, 엔딩을 발동하지 않으면 Chapter 5·6·7이 계속 진행되며 인터미션이 영구히 안 열린다. 그리고 마지막 리포트가 '막이 바뀝니다 — 방금 끝낸 Act 제목'을 잘못 띄운다. 세 건 모두 사양이 아니라 결함이고 스키마 신설 없이 닫힌다.

### D-14. [P2 · SMALL] 극장 세이브 load 경로에 archiveCurrentActiveIfAny 호출 추가 (resume 경로와 대칭 복구)

`aichat/.../service/theater/TheaterSaveLoadService.java:176 (재사용 대상 TheaterLobbyService.java:596, 호출 선례 :394·:569)`

resume에는 있고 load에만 빠진 비대칭이라 기존 메서드 재사용으로 끝난다. 블록 D로 ENDED가 정상 플레이로 도달 가능해져 '엔딩 본 방에 세이브 로드' 조합이 실현 가능해졌으므로 우선순위가 올라갔다.

### D-15. [P2 · SMALL] 극장 감독 노트 update/delete IDOR — findByIdAndChatRoom_Id 스코프 질의로 교체 (B-13과 같은 커밋)

`aichat/.../service/theater/TheaterDirectorNoteService.java:96-121 (컨트롤러 TheaterFinalityController.java:132-135·144-147)`

컨트롤러가 방 소유권만 검사하고 서비스가 findById로 노트를 꺼내므로, 본인 소유 극장 roomId + 타 유저 노트 id 조합으로 남의 MANUAL 감독 노트를 수정·삭제할 수 있다. B-13과 정확히 같은 패턴이다.

### D-16. [P2 · SMALL] 이스터에그 연출 복구 (§C#6 (a) 이행) — 투명인간 훅의 성공 게이트 제거 + 서버가 204/명시 플래그 반환

`LucidChat-Front/src/hooks/useInvisibleMan.js:51-57 (소비 :70, 호출부 ChatPage.jsx:725) · aichat/.../AchievementService.java:153 · controller/AchievementController.java:59-61`

게이트가 null을 반환하고 컨트롤러가 200+빈 바디를 내려주는데 FE 훅이 if(res.data)로 감싸고 있어 10분 방치 연출 자체가 사라졌다. docs/14 §C#6이 '연출 유지 + 업적만 게이트 오프'를 확정했으므로 이건 결정이 아니라 미이행이다. 서버 3줄 + FE 2줄.

### D-17. [P1 · MEDIUM] 결제 정합 3종 — B-1.1 merchant_uid 대조 · B-1.2 orders.imp_uid unique(V28) · B-1.3 PortOne V1 웹훅 공유 시크릿 + IP 화이트리스트

`aichat/.../controller/PaymentController.java:98-121 · config/SecurityConfig.java:64 · service/payment/PaymentService.java:110-127 · 신규 마이그레이션 V28`

지금은 imp_uid 재사용으로 1건 결제 → N건 지급이 되고, 동일가 교차 상품(14,900원 LUCID_PASS ↔ SECRET_UNLOCK_PERMANENT)까지 통과한다. 웹훅이 permitAll이라 로그인 없이 타인 주문 확정도 가능하다. docs/18 §4-B #6과 docs/17 §E ⑰이 이미 같은 추천을 냈으므로 도장만 없는 상태 — PG 심사 제출 전 완료가 조건이다.

### D-18. [P1 · SMALL] 일러/배경 웹훅 시크릿 필수화 (D-2.k의 시크릿 부분만 — '비-success=실패' 전이는 제외)

`aichat/.../controller/IllustrationController.java (웹훅 핸들러) · BackgroundGenerationService.java:301-317`

환불 파밍면을 선차단한다. 시크릿 필수화는 어느 노선에서도 유효하지만, 같은 ID로 묶여 있는 '비-success를 실패로 전이'는 ModelsLab 중간 status 문자열 집합이 미확정이라 그대로 켜면 정상 생성을 죽이고 환불까지 나간다 — 반드시 분리할 것.

### D-19. [P1 · SMALL] UGC 월드 편집 착취면 — Character.createUgc의 tagline/role/tone 조용한 절삭을 400 거부로 전환하고 D-3.6을 B절(P0-A 착취)로 재분류

`aichat/.../domain/character/Character.java:737-771 (대입 :748·:750·:752) · 선례 UgcWorldService.java:404-421`

같은 파일의 UgcWorldService.updateWorld(:404-421)가 name/intro/lore/moodTags를 이미 400으로 거부하는데 createUgc만 조용히 자른다. 순 0E로 GPU를 무한히 태울 수 있어 배치 1로 앞당겨야 한다. 레지스터 재분류는 결정이 아니라 정리 작업이다.

### D-20. [P3 · ONE_LINE] 프롬프트 'Age: null' 리터럴 억제 — age가 null이면 Age 줄 자체를 생략

`aichat/.../service/prompt/CharacterPromptAssembler.java:89(템플릿)·:135(인자) · TheaterPromptAssembler.java:212`

안건 9에서 나이 필드 도입을 택하더라도 기존 UGC 캐릭터 소급분은 백필 전까지 계속 null이 나간다. 어느 결정에서도 버려지지 않는다.

### D-21. [P2 · SMALL] 에너지 캐시 정합 — /users/me가 캐시 대신 PK 단건 조회로 잔량을 읽도록 (A안)

`aichat/.../scheduler/EnergyRegenScheduler.java:27-45 · ChatStreamService.java:1150 · FE 갱신 지점 DialogueBox.jsx:472·997 · ChatPage.jsx:1316 · ChatPageV2.jsx:2156`

EnergyRegenScheduler가 벌크 UPDATE만 하고 갱신된 유저 목록을 돌려받지 않으므로(:29-45) 개별 evict가 원리적으로 불가능하다. ChatStreamService가 이미 턴마다 evictUserProfile(:1150)을 부르고 있다는 비대칭이 곧 근거다. 추가 비용은 호출당 PK 조회 1회.

### D-22. [P2 · MEDIUM] 에너지 분할 환불 — 원 분할(free/paid) 복원 방식으로 구현. D-1.1 시그니처 교체 시 하위호환 오버로드를 남기지 말 것

`D-1.1 시그니처 + 호출부 7곳(배치 3) · User.java:166·187 · 환불 선례 UgcWorldService.java:488-502`

초과분 paid 승급이나 paid 우선 폴백은 deleteFailedLocation의 무조건 1E 환불과 결합해 free→paid 전환 파밍이 성립하고, ~~폐기는 유저 손실이라~~ 정합해가 하나뿐이다. ~~V29~~ EnergySplit 마이그레이션이 이미 배치 3에 예정돼 추적 컬럼의 한계 비용도 낮다. 오버로드를 남기면 호출부가 조용히 낡은 경로로 컴파일된다

> **✅ 2026-09-02 구현·정정** — 구현 완료(V32 · `EnergySplit` · 호출부 8곳). D-1.1 ❓(상한 초과분)는 **(a) 버림**으로 확정했다: "폐기는 유저 손실"은 **틀린 서술**이다 — free는 regen이 상한까지 공짜로 채우므로 지연 환불 시점에 이미 상한이면 그 free분은 이미 회복돼 있고, 얹으면 상한 초과 순증(공짜 발행)이다. 적대적 리뷰 2렌즈가 독립 검토해 정상 유저 손해 경로 없음을 확인. 배포 시점 진행 중 행은 V32가 유료분=총액으로 1회 백필(구 코드 대비 회귀 방지). 근거 전문: [`../17_assets/defect_register.md`](../17_assets/defect_register.md) D-1.1 "✅ 답".(CLAUDE.md §2-6) — 컴파일러가 호출부를 전수로 드러내는 것이 유일한 검증 수단이다.

### D-23. [P2 · SMALL] final_result 전송 실패 시 환불하지 않고 방 재조회로 복구 + TX 밖 지연 역참조를 지역변수로 걷어냄

`aichat/.../service/stream/ChatStreamService.java:414 · :424 · :461 (TX 성공 지점 :464)`

TX-2가 커밋됐다는 것은 유저가 대금에 상응하는 것을 이미 받았다는 뜻이라 결손은 '전달'뿐이고, 환불은 이중 지급이자 전송 실패 유도 시 무료 획득면이 된다. 같은 커밋에서 LazyInitialization 위험을 정리한다.

### D-24. [P3 · SMALL] 배경 생성 웹훅 폴백 — 실패 시 1회 재제출 + persistCache 중복 return 정리

`aichat/.../service/illustration/BackgroundGenerationService.java:301-317 · :252-266 · :286 · :374-379`

폴링이 이미 동작하므로 두 번째 웹훅 표면(공인 URL + 서명 인프라)을 런칭 전에 얹을 이유가 없다. ★ 착수 노트에 남길 것: 이 트랙은 §G-6 레거시 CG가 아니라 배경 트랙이고 무게이트라 CG 결정과 무관하게 살아남는다 — 이 오독이 여러 분석에서 반복됐다.

### D-25. [P3 · SMALL] 시간 넘기기 경로에 resolvePromotionLogic 호출 추가 + refreshRelationFromStats javadoc 계약 정정

`aichat/.../service/stream/ChatStreamService.java:678-684 (자동응답 경로 :1437-1440) · domain/chat/ChatRoom.java:745-766 (:757 주석)`

블록 D가 refreshRelationFromStats에서 statusLevel 대입을 뺐는데 시간 넘기기 경로에는 판정 주체를 안 붙여, 승급·강등이 1턴 지연되고 SSE의 relationStatus/dynamicRelationTag가 실제 스탯과 어긋난 채 내려간다. 그리고 javadoc은 '변경됨'이라 선언하는데 실제 반환은 '아직 반영 안 됨'이라, 다음 세션이 문구를 믿고 재배선하면 매 턴 오발동한다.

### D-26. [P2 · SMALL] 상태창 시크릿 업셀 배선 — onUnlockSecret을 스토어 진입으로 연결하고 카드 카피를 '전 캐릭터 영구 해금'으로 교정

`LucidChat-Front/src/components/BiometricStatusPanel.jsx:103 · :222-236 (미전달 ChatPage.jsx:2381-2393 · ChatPageV2.jsx:3216-3228)`

onUnlockSecret을 전달하는 호출부가 저장소 전체 0건이라 CTA가 영구 disabled고 '해금하고 보기 →' 문구조차 렌더되지 않는다. docs/16이 핵심 BM으로 지정한 시크릿의 인게임 업셀 진입점이 통째로 죽어 있다. 안건 8과 같은 화면·같은 커밋.

### D-27. [P2 · ONE_LINE] 엔딩 인용구 폴백의 isBlank 미검사 교정 (E-3 ④.10)

`aichat/.../domain/character/Character.java:495-501 (소비 TheaterEndingService.java:125-126·325-335 · EndingService.java:169-171)`

현재 `!= null` 검사라 빈 문자열 시드가 통과해 극장 엔딩이 무대사 씬을 낸다. 시드 8줄을 채우는 것과 무관하게 어느 노선에서도 유효한 폴백 보호다(시드 저작은 극장 엔딩 라이브 전 작업 항목으로 일정에만 올릴 것).

### D-28. [P3 · MEDIUM] 시드 정리 묶음 — E-3 ②.1~②.12 유령 장소 키 11행 + CharacterSeeder 검증기 · ①.6/①.9 복붙 오배정 2줄 · ①.13/①.14 parseLocationOrDefault 무로그 · ③ BGM DAILY_CALM + ChatRoom.java:447 하드코딩

`aichat/src/main/resources/application-characters.yml (①.1은 :599) · application-worlds.yml(②·③) · domain/chat/ChatRoom.java:447 · ChatRoom.java:348·380·774·978·1015 · BgmMode.java:11-12`

전부 노선 무관이다. ②는 getAllowedLocations를 아예 타지 않는 시더 경로라 안건 12와 무관하고, ①.6/①.9는 어느 enum 노선에서도 오류다. BGM은 DAILY가 'V1 전용'으로 못박혀 있는데 V2 트랙 두 곳이 그 값에서 출발하는 문제라 DAILY_CALM 하나로 닫히지만, ChatRoom.java:447의 명시적 하드코딩은 시드 교정만으로는 사라지지 않으므로 같은 커밋에 반드시 포함할 것.

### D-29. [P3 · MEDIUM] 데드코드 정리 묶음 — 자동 씬 일러 제거 · ChatRoom.createSandbox 死코드 · 극장 난입(intervention) · v2DerivedRoomInfo 死 memo, 모두 §G-4 목록에 등재

`aichat/.../ChatStreamService.java:456-470(호출 :460-462) · SceneRenderService.java:97-99 · application.yml:162 · domain/chat/ChatRoom.java:367-381 · service/theater/TheaterInterventionService.java · LucidChat-Front/src/pages/ChatPageV2.jsx:344-357`

자동 씬 일러는 턴당 GPU 비용을 예측 불가로 만들어 되살리려면 과금 재설계가 선행이고, 극장 난입은 FE 호출부 0건이며, createSandbox는 호출부 0건인데 E-3 ① 감사에서 실재하지 않는 경로를 세게 만든다. v2DerivedRoomInfo는 블록 D가 '고쳤지만' 소비처가 0인 memo다. git으로 복원 가능해 되돌릴 수 없는 결정도 아니다. §G-4 목록에 등재해야 다음 감사에서 같은 질문이 반복되지 않는다.

### D-30. [P1 · MEDIUM] 성인인증 잔여 개통 — E-1.12a/b 모달 데드엔드 2줄 + C-1.5 콜백을 백엔드 GET/POST 양수신 후 SPA로 302

`LucidChat-Front/src/App.jsx(/verify/callback 라우트 부재) · 성인인증 모달(E-1.12a/b) · aichat/src/main/resources/application.yml:85-90 · config/NiceApiProperties.java:22 · external/NiceApiClient.java:50-51`

§F #7의 모달 고착은 지금 가능하고, C-1.5는 백엔드가 양쪽을 받아 검증 후 302하면 NICE 콜백 스펙(GET 쿼리/POST 폼) 답을 기다릴 필요 자체가 사라진다. ★ docs/18 §1-E의 '이 답을 받기 전에는 §3-B를 반쪽만 만들어 두는 게 맞다'를 이 방식으로 정정하지 않으면 C-1 0순위가 계약 리드타임에 인질로 잡힌다.

### D-31. [P2 · MEDIUM] 보안 잔여 묶음 — B-10.1/10.2 RT typ/jti 클레임 · B-11.1 XFF 리밋 키(ClientIpResolver 재사용) · 로그인 실패 지수 백오프(성공/실패 미구분 카운트 교정) · 자격증명 미주입 시 진입부 503 · 공지 게스트 개방

`aichat/.../config/SecurityConfig.java:64 · JwtTokenService(B-10) · ClientIpResolver(B-11) · GuestBrowseRateLimitFilter · 공지 컨트롤러의 '게스트 개방 보류' 주석`

전부 엔지니어링 판정이고 §F에 이미 올라 있다. 로그인은 락아웃이 표적 DoS를 새로 만들고 CAPTCHA는 런칭 규모에 과해 백오프만 남는다. 자격증명은 전역 fail-fast가 이번 부팅 블로커와 같은 위험을 만드니 진입부 503이 맞고, docs/18 §2-A에 '자격증명 주입 확인' 단계를 함께 추가한다. 공지는 정의상 공개 정보이며 장애 공지가 로그인해야 보이는 구조는 가장 필요한 순간에 안 보인다 — 단 SecurityConfig permitAll과 GuestBrowseRateLimitFilter 프리픽스를 같은 커밋에 동기화하지 않으면 게스트 무제한 호출이 열린다.

### D-32. [P3 · MEDIUM] 기타 FE 정합 3건 — derivePulse 인자를 상위에서 한 번 계산해 두 컴포넌트에 같은 pulse 전달 · prevStats에 characterId key 부여 · D-6.5 saveAssistantLog persister 위임

`LucidChat-Front/src/components/DialogueBox.jsx:475 ↔ BiometricStatusPanel.jsx:130-133 · BiometricStatusPanel.jsx:117-127·70·195 · aichat saveAssistantLog(D-6.5)`

같은 화면의 '심박' 두 개가 서로 다른 값을 표시하고, 히로인 전환 시 다른 캐릭터 값과 비교돼 5축 전부에 거짓 '직전 턴 ↓'가 붙는다(E-1.11 픽스로 V2 수치가 0이 아니게 되면서 비로소 눈에 보이게 됐다). D-6.5는 4경로 중 최소 2개가 확정 생존이라 §F에 그대로 유효하다. ⚠ 단 V2 상태창 파생 상태 전면 재작성(ChatPageV2.jsx:1763·1893·2007 갱신 누락 포함)은 배치 2 완료 전 착수 금지 — 지금 국소 패치를 넣으면 재작성에서 버려진다.

### D-33. [P2 · MEDIUM] 문서 정정 묶음 — docs/18 §4-A ⑫를 §4-B로 되돌림 · §4-A ④(§G-7)를 '(a) 확정 · 구현 미집행'으로 정정해 §4-B로 내림 · §4-C ⑩에 '§G-4 role-desc 삭제는 §C#6 코드 보존으로 대체됨' 1줄 · §1-E의 C-1.5 지침 정정 · §2-A에 0단계 '컨텍스트 기동'과 '자격증명 주입 확인' 추가 · docs/17 §G 회귀위험 6번(B-4.a 3단 롤아웃)은 branchToken 도입 이전 기준이라 정정 · storyAvailable javadoc 1줄 후 C-0.5 폐기 · 베타 가짜 인증 계정 정리를 §2-A 실행 항목으로 등재

`aichat/docs/18_Launch_Admin_Runbook.md §4-A ④·⑫ · §4-C ⑩ · §1-E · §2-A · docs/17_BugFix_Session_Readiness.md §G 6번 · aichat/.../service/story/StoryV2Service.java:126·165·487·563 · docs/17_assets/defect_register.md(C-0.5 폐기, D-3.6 재분류, V26→V28 정정)`

틀린 '해소됨' 표기는 다음 세션이 끝난 일로 착각해 잔존 엔드포인트를 감사 대상에서 빼게 만든다 — §4-A ⑫는 실측으로 반증됐고 §4-A ④는 결정만 있고 구현이 없다. 베타 계정(ci_hash LIKE 'BETA_TESTER_%')은 '결제 사실이 연령을 증명하지 않는다'는 이유로 유지·예외가 청소년보호정책·PG 심사에서 방어 불가라 실질 선택지가 없고, 1회 SQL + 안내가 전부다(시크릿 BM 오픈 전 완료 조건). storyAvailable은 V2 STORY 히로인 풀 필터라 레지스터 원 수정안(시드 일괄 false)이 라이브 기능을 죽인다.

---

## E. 총평

답이 오면 실제로 움직인다. 22건 중 14건이 '착수전필수'인데, 그 대부분이 단일 산출물로 수렴한다 — 안건 2·3이 V28 마이그레이션 내용물을 확정해 배치 1B를 열고, 안건 4·5·6이 PG 심사 제출 전 결제 정합 커밋과 약관 TODO를 동시에 채우며, 안건 10 하나로 잔존 197건 중 27건 남짓의 존폐가 갈린다. 다만 순서 의존이 두 곳 있다: 안건 10(레거시 CG)을 먼저 답해야 안건 11(장소 어휘)·16(씬 일러)의 실제 작업량이 정해지고, 안건 1의 후퇴 기준선이 먼저 서야 극장 계열 6건의 판정이 의미를 갖는다. 반대로 지금 답이 없어도 막히지 않는 것을 33건 분리해 뒀고, 그중 상위 3건(MongoConfig·이스터에그 NPE·FE 댕글링 import)은 답을 기다릴 이유가 전혀 없다 — 지금 서버가 뜨지 않고 dev 채팅 화면이 로드되지 않는 상태라, 이 셋을 먼저 넣지 않으면 어떤 결정도 검증할 수단이 없다. 가장 큰 잔여 불확실성은 AWS 정지다: 안건 2와 3은 프로드 1쿼리로 자동 종결될 수 있는 구조라 복구 직후 §2-A에 조회를 끼워 넣어야 종원을 두 번 부르지 않는다. 그리고 이번 재판정이 문서 오류 두 건(§4-A ⑫ '도달 불가', §4-A ④ '구현 완료')을 실측으로 뒤집었으므로, 정정 없이 다음 세션이 시작되면 같은 결함을 감사 대상에서 다시 빼게 된다.
