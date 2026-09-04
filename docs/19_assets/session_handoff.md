# 19-assets. 버그픽스 세션 인계 (2026-08-27/28)

> 컨텍스트 한계로 세션을 끊는다. **다음 세션은 이 문서 → [`../19_Register_Rejudgment.md`](../19_Register_Rejudgment.md) → [`decisions_confirmed.md`](decisions_confirmed.md) 순으로 읽고 시작하라.**
> 결함 좌표 정본은 [`rejudgment_delta.md`](rejudgment_delta.md), 착수 목록은 [`decision_agenda.md`](decision_agenda.md) §D다.

---

## A. 이번 세션이 한 일

1. **레지스터 재판정** — 245건을 블록 D 반영 HEAD로 전수 재판정. 실수정 대상 **201건** 확정(잔존 197 + 부분수정 4). 좌표 114건 갱신.
2. **결정 안건 22건** 도출 → 종원이 21건 확정([`decisions_confirmed.md`](decisions_confirmed.md)).
3. **결정 불요 33건 + 안건 확정분 구현** — 아래 §B.
4. **적대적 리뷰 4라운드** — 매 구현 뒤에 새 컨텍스트 리뷰를 붙였고, 그중 **2건은 내가 넣은 가드가 정상 유저를 막는 P0**여서 철회했다(§D).

---

## B. 닫은 P0 (재판정 시점 18건 기준)

| 축 | 결함 | 비고 |
|---|---|---|
| 블록 D 회귀 | MongoConfig 부팅 불가 · 이스터에그 NPE 턴 소실 · FE 댕글링 import | 레지스터 미등재 신규. 부팅 블로커는 실기동으로 확정 |
| 결제 진입점 | `C-2.a/b/c/e/g/h/i/j` | `PaymentModal` 폐기 → `LucidStore` 일원화 |
| 극장 분기 | `B-4.a/b/c/d/e/f` | `branchToken` 왕복 + 오퍼 원본 재판정 |
| 극장 과금 | `B-5.1` | `prefetch` 플래그 제거 |
| 결제 정합 | `B-1.1/1.2/1.3` | PG 심사 선행 조건 |

**`B-5.2`는 코드 완료·스위치 대기** — §C 참조.

마이그레이션 **V28**(orders.imp_uid unique) · **V29**(theater_branch_choices unique) · **V30**(theater 과금 워터마크) 추가. 전부 멱등·nullable. **다음 세션 신규 번호는 V31부터.** → <sub>2026-09-02 정정: V31(chat_rooms CHECK)·**V32**(에너지 분할)·**V33**(구독 부분 유니크)·**V34**(orders.status CHECK — PAID_UNDELIVERED)·**V35**(구독 회차 스냅샷·이월 출처·tier 정합)까지 점유. **신규는 V36부터** — 착수 전 `git ls-files src/main/resources/db/migration | sort -V | tail -3` + 로컬 `flyway_schema_history`를 둘 다 볼 것(이번 세션에 V31 중복 사고 있었음, 메모리 `bash-initial-cwd-trap`).</sub>

---

## C. ★ 다음 세션이 가장 먼저 확인할 것

### C-1. 극장 과금 워터마크 게이트가 **꺼져 있다**

`theater.paid-batch-gate-enforced` 기본 `false` = **관측 모드**(WARN만, 거부 없음).
코드는 완성돼 있고 스위치만 꺼져 있다. 켜기 전 조건 2가지:

1. 로그 `"Unpaid batch consume detected"` 건수가 **0에 수렴**하는가
2. 배포가 **신·구 태스크 혼재 창을 만들지 않는가**(drain-then-switch)
   → 롤링 창에서 구 태스크는 `markBatchPaid`를 하지 않아 **정상 결제 유저가 거부**당한다

FE 자기 치유(`ErrorCode.UNPAID_BATCH` → `loadNextBatch()` 1회 재시도)는 이미 배선돼 있다.

### C-2. AWS 복구 후 즉시 할 것

- **docs/18 §2-A 0단계 = 컨텍스트 기동 확인** (`Started AichatApplication in`). 이번 세션에 부팅 불가가 컴파일·테스트 녹색인 채로 발견됐다
- **0-B단계 = 외부 자격증명 주입 확인** (기본값 없는 `${ENV}` 21개)
- **베타 가짜 성인인증 계정 정리** (docs/18 §2-A 7단계, `ci_hash LIKE 'BETA_TESTER_%'`)
- prod Mongo 수동 인덱스: `db.ending_results.createIndex({room_id:1},{unique:true})` (필드명 `room_id`)

---

## D. ★★ 이번 세션의 뼈아픈 교훈 — 같은 실수를 반복하지 마라

**가드를 넣을 때 "착취를 막는가"보다 "정상 유저를 막는가"를 먼저 물어라.** 이 세션에서 두 번 걸렸다.

1. **미확정 분기 가드** — 분기를 확정 안 하고 `/next-batch`를 부르면 400. 새로고침·이탈·LLM 실패·에너지 부족·세이브 로드 어느 경우에도 pending 마커(TTL 6h)가 남아 **6시간 잠금**. **철회했다.**
   → 성격 판정 자체가 틀렸다: **분기를 건너뛰는 것은 착취가 아니라 포기다.** 상품을 안 사는 것을 도둑질이라 하지 않는다.
2. **과금 워터마크 거부** — 롤링 배포 혼재 창에서 정상 결제 유저가 거부당하고, FE가 그 실패를 삼켜 **무증상·무기한 정지**. → 관측 모드로 내보냈다.

**판단 기준**: 완주에 90~100E가 드는 트랙에서 1~2E를 지키려고 세션을 세우는 것은 **역전된 거래**다.

부수 교훈:
- **CRLF 다중행 치환이 이번 세션에도 2번 조용히 실패했다**(CLAUDE.md §1-1). 구조적 편집은 Edit 도구로.
- **에이전트가 내 지시를 거부한 것이 옳았던 경우가 있다** — V29 유니크 키를 `(room, act, chapter, level)`로 잡으라 했는데, MINOR가 챕터당 3~4회 정상 발생해서 정상 유저가 DB에서 거부될 뻔했다. `source_batch_id`를 축에 넣어 해결.
- **"FE 변경 0줄"은 해피패스에서만 사실이었다** — 신설한 400을 FE가 삼키면 화면이 조용히 멈춘다. 서버 가드를 추가할 때 클라이언트 복구 경로를 같이 봐야 한다.

---

## E. 남은 작업 (우선순위순)

### E-1. 미수정 P1 (약 47건)
자산 손실 축이 가장 크다:
- **D-1.x 에너지 분할 환불** (8건) — `User.consumeEnergy` 시그니처 + 호출부 7곳 횡단. **단독 커밋으로 분리할 것**, 하위호환 오버로드 금지(§2-6)
- **D-3.x UGC 좀비 잡** (회수·보상 — 안건 21에서 `①누끼 재개 + (b)` 확정)
- **D-4.x 구독 정합** (안건 3·5·6 확정분 — 티어 이월 금액 비례 환산, 환불 회수 실패 처리)

### E-2. 안건 확정분 중 미착수
- 안건 11 V1 장소 enum — enum 확장·시드 교정은 완료. **동적 배경 일원화 후속**은 미착수
- 안건 16 씬 일러 좌표계 비의존 판정 — 미착수(소비처 3곳 동시 변경 필요)
- 안건 18 승급 세리머니 히스테리시스 — 도달 이력 컬럼(V31~)
- 안건 20 (A) UGC 재심사 회귀 — 캐릭터 텍스트·월드 lore·월드 장소 3경로 중 **장소 경로만 완료**, 나머지 확인 필요

### E-3. 이번 세션이 보류한 것
- **안건 9-A′ V2 STORY 나이 게이트 구멍** — `ChatStreamServiceV2:176`·`:1157`, `StoryV2Service:847`, `SceneRequestService:230`이 캐릭터 자격을 조회하지 않는다. 지금 안전한 이유는 `createUgc`의 `storyAvailable = ugcWorldId != null` 불변식이라는 **우연**이다
- **안건 9-E 기존 UGC age 백필** — 게이트는 `age == null` 유예로 배포했다. 백필 정책 미정
- **D-5.1/5.2 극장 prefetch 오프바이원** — 가드가 N+1을 검사하는데 저장은 N. prefetch 히트율 **구조적으로 0%**이고 소비 중인 배치를 덮어쓴다. LLM·CG·배경 비용 2배
- **D-2.k ModelsLab 비-success → FAILED 전이** — status 값 집합 실측 전까지 보류(정상 생성을 죽이고 환불까지 나간다)
- **자동 씬 일러 데드코드** — E-2.15b가 "되살릴 계획이 있는가" 결정에 종속
- **`/choose` 응답 본문화** — FE 에너지 표시 드리프트. 구 FE는 본문을 무시하므로 언제 넣어도 안전

### E-4. 미답 안건 2건
- **#17-①** 극장 스탯 리롤 과금액 (추천: 첫 1회 무료 + 이후 3E)
- **#17-②** §C#6 '극장 무변경'의 경계 문구 확정 (유저 체감 동작만 불변 / 과금·스키마·버그픽스는 예외 — 이번 세션은 이 해석으로 진행했다)
- **#22** `dynamicRelationTag` 존폐 (나중)

---

## F. 검증 베이스라인 (이 커밋 시점 실측)

- `compileJava` 통과 · 테스트 **21클래스 / 116건 / 실패·에러 0**
- **로컬 실기동 성공** — V28·V29·V30 정상 적용
  ```bash
  JWT_SECRET_BASE64="$(node -e "console.log(Buffer.alloc(32,7).toString('base64'))")" ./gradlew bootRun --no-daemon --args='--server.port=8081'
  ```
  ⚠ 8080은 종원의 IntelliJ 세션이 쓰고 있을 수 있다 — 포트를 바꿔 확인하라
- FE `npm run build` 통과 · `not exported` 경고 0 · **신규 lint 에러 0**
- **실행 검증 한계**: AWS 정지로 프로드 확인 0. FE dev 서버 모듈 로드 실증만 있다

---

## G. 2026-09-02 세션 — 버그픽스 2차 (배치 1~3 + 안건 4) · **로컬 6커밋 완료 · 미푸시**

> aichat `2253f0c`(V32) → `e7f9c33`(D-1) → `a2b81ac`(V33·V34·V35) → `b2eec30`(배치 2) → `2f0bc8b`(배치 3+안건 4) → `25381a1`(문서) · FE `72f6a18` · Admin `b9df1ff`. **푸시·Vultr 배포는 다음 세션** — 배포 전 §G-2 ②③.

> 읽는 순서는 그대로: 이 문서 → [`../19_Register_Rejudgment.md`](../19_Register_Rejudgment.md) → [`decisions_confirmed.md`](decisions_confirmed.md). 상태 정본 [`rejudgment_delta.md`](rejudgment_delta.md)는 D-1·D-3·D-4 행이 갱신됐고, 레지스터의 ❓ 9건에 **✅ 답** 줄이 붙었다.

### G-1. 한 일 (종원 지시로 로컬 커밋 완료 — 위 해시)

| 배치 | 내용 | 마이그레이션 | 검증 |
|---|---|---|---|
| **1 · D-1.1~1.8 에너지 분할 환불** | `EnergySplit` 반환·`refundEnergy(EnergySplit)`·1-arg 삭제(§2-6)·지연 환불 4경로 유료분 영속·배포 창 백필·free<0 클램프 | **V32** | 리뷰 4관점(P2 1건 수용) · 유닛 13건 |
| **2 · D-3.1a/1b/1d/2a/2b/3/4/5 UGC 좀비 잡** | 통합 스테일 스윕(`recoverStaleCharacterJobs`→`recoverStaleJob`) · 404=NOT_FOUND 분리 + compare-and-drop 주입 · 부분 누끼 재개 · 리롤 화이트리스트 + 유실 자가치유(5분) · 장소 in-flight 레지스트리 · 씬 렌더 부활 차단 · fal future 타임아웃 · 스케줄러 풀 3 | — (`ugc.job.stale-sweep-minutes`·`external-hard-stale-minutes` 노브) | 리뷰 4관점 · 유닛 8건 |
| **3 · D-4.1~4.5 구독 정합 + 안건 5·6** | 잔여 보존 갱신 + **회차 스냅샷**(더블 결제 환불 시 이전 회차 보존) · 금액 비례 이월 + **이월 출처**(상위 주문 환불 → 이전 행 복원, 원천 회차 환불 사전 거부) · **다운그레이드 거부**(관리자 지급 예외) · User 행 락 · List degrade · 회수 3경로 boolean → `REFUND_CLAWBACK_FAILED` 409 | **V33**(부분 유니크) · **V35**(스냅샷 4컬럼 + tier 정합) | 리뷰 · 유닛 17건 |
| **안건 4 · 지급 실패 결제 처분 (b)+(c)** | `PAID_UNDELIVERED` · 결제 확정 TX-A / 지급 TX-B / 사유 TX-C 분리 · **OSIV refresh**(P0) · 종결 주문 웹훅 무시(REFUNDED→FAILED 차단) · `PAYMENT_DELIVERY_PENDING` 409 + FE '결제 완료·지급 대기' + 재시도 · 스케줄러 자동 재지급(15분·24h) · 관리자 `POST /admin/payments/orders/{uid}/redeliver` · 매출 집계에 미지급 확정금 포함 | **V34**(CHECK 동기화 — Hibernate CHECK 실측 §2-7) | 유닛 10건 |

유닛 **30클래스 / 182건 녹색** · 8081 실기동 V32~V34 확인(V35는 이 문서 작성 직후 재기동 확인 — G-4). FE `npm run build` 통과(Front·Admin).

### G-2. ★ 다음 세션이 가장 먼저 확인할 것

1. **푸시·배포** — 3리포 로컬 커밋은 끝났다(스키마/코드 분리, 위 해시). `git push origin master` ×3 → CI/CD(GHCR+SSH) 배포 → ③ 실측. 프로드 배포 시 Flyway가 V32~V35를 순서대로 적용한다(전부 멱등).
2. **롤백 절차 (V33·V34 이후)** — 앱 이미지만 되돌리면 (a) 구 코드의 티어 변경이 flush 순서(INSERT→UPDATE)로 `uq_sub_user_active` 위반 → 500, (b) `PAID_UNDELIVERED` 행이 있으면 구 enum 역직렬화 실패로 어드민 주문 목록·감시 스캔이 죽는다. 롤백 전: `DROP INDEX IF EXISTS uq_sub_user_active;` + `SELECT count(*) FROM orders WHERE status='PAID_UNDELIVERED'`가 0(재지급·환불로 소진)인지 확인. V32·V35 컬럼은 남겨도 무해.
3. **프로드 배포 후 실측** — `flyway_schema_history` v35 · `orders_status_check` 6값 · `uq_sub_user_active` 존재 · `SELECT id,free_energy,paid_energy FROM users WHERE free_energy<0 OR paid_energy<0`(0건 기대).
4. `theater.paid-batch-gate-enforced=false` 관측 모드 유지(§C-1 조건 그대로 — 이제 Vultr 로그로 확인).

### G-3. 이 세션의 교훈 (★ 반복 금지)

- **세션 첫 Bash의 cwd를 믿지 마라.** 첫 `git log`가 실제 리포와 다른 체크아웃을 보여줘 V31 중복 마이그레이션을 만들었다 → 부팅 실패로 발견. 첫 호출에서 절대경로 `cd` + HEAD를 시스템 스냅샷과 대조(메모리 `bash-initial-cwd-trap`).
- **enum 값 추가 = Hibernate CHECK 동기화(§2-7)는 실측으로 확정했다** — `orders_status_check`가 있었다(V34). "Flyway CREATE 이력 없음 ≠ CHECK 없음".
- **OSIV(open-in-view 기본 true)에서 TransactionTemplate으로 TX를 쪼개면 두 번째 TX의 `FOR UPDATE` 조회가 스테일 managed 인스턴스를 돌려준다** — 락 뒤 `em.refresh` 필수. 단일 @Transactional 시절엔 없던 회귀 유형.
- **정확한 역연산 ≠ 경제적 중립** — 버킷 기준 복원은 지연 환불 대기 중 paid로 흘러간 소비를 되돌리지 않는다(원장 없이는 불가). 결정 사항으로 기록.
- **가드 원칙이 이번에도 두 번 작동했다** — '리롤 in-flight 400'이 유실 케이스에서 유저를 30분 가두는 것(자가치유 5분으로 완화), '최신 회차 환불 = 행 전체 비활성화'가 더블 결제 유저의 이전 회차를 지우는 것(스냅샷으로 회차분만 회수).
- 세션 한도가 리뷰 반박 패널을 3번 죽였다 — 패널 없이 렌즈 발견을 직접 판정할 때는 **코드로 재확인한 것만** 수용했다(위 표의 P0·P1 전부 코드 대조 완료).

### G-4. 남은 것

- **E-1 잔여 P1**: D-2.a/g · D-6.4/6.5(스트림 보상·로그 유실) · D-5.1~5.4(극장 prefetch 오프바이원) · E-4.3/4.4 · E-5.1.b/5.2.a/5.2.b · E-1.1/1.2/1.2b · B-6.1(안건 17-① 대기) · E-6.4 · E-7.1.a · D-2.k(ModelsLab status 실측 대기) → **약 22건**
- 결정불요 잔여: D-9 · D-18(웹훅 시크릿 필수화) · D-23 · D-24 · D-29 일부
- 안건 미착수: 16(씬 일러 좌표계) · 18(승급 히스테리시스, V36~) · 11 후속(동적 배경 일원화) · 9-A′(V2 STORY 나이 게이트) · 9-E(age 백필 — **null 유예 유지**로 결정)
- FE 보조: D-3.4 ③ 낙관 잠금(StudioCreateFlow) · 신규 400 6종 문구 노출은 확인됨(조용히 멈추는 곳 없음)
- 종원 결정 대기: **안건 17-①**(극장 리롤 — 추천 세션당 3회 상한) · **17-②**(§C#6 경계 명문화) · 다운그레이드 '거부'(이번 구현) vs '경고 후 허용'

---

## H. 2026-09-03 세션 — 배포 + 버그픽스 3차 (배치 2 완료 · 배치 1 착수)

> 읽는 순서는 그대로: 이 문서 → [`../19_Register_Rejudgment.md`](../19_Register_Rejudgment.md) → [`decisions_confirmed.md`](decisions_confirmed.md).
> **착수 계획 정본: [`round3_plan.md`](round3_plan.md) · 배포 절차: [`round3_preflight.md`](round3_preflight.md)**

### H-0. ★★ 배포 완료 — 3리포 master = origin = 프로드 동기

§G의 미푸시 9커밋(BE 7 · FE 1 · Admin 1)을 **푸시 순서 FE → Admin → BE**로 배포했다.
Flyway가 V32~V35를 순서대로 적용(72ms) · `Started AichatApplication in 25.824s` · **부팅 후 ERROR 0건**.

배포 후 실측 전량 통과: `flyway v35` · `orders_status_check` **6값**(PAID_UNDELIVERED 포함) ·
`uq_sub_user_active` 존재 · 구독 스냅샷 4컬럼 · V32 에너지 분할 8컬럼 · 음수 에너지 0건.

롤백 좌표: 이미지 `sha256:e082a46…` + **`lucid-rollback:pre-v32-v35` 태그를 프로드에 박아 뒀다**
(`deploy.sh`가 성공 시 `docker image prune -f`로 구 이미지를 지우기 때문). 백업 1회 수행(R2).

### H-1. ★★★ 프로드 실측 — 계획의 전제를 바꾼 3가지

| # | 실측 | 함의 |
|---|---|---|
| 1 | **`ddl-auto`가 `validate`가 아니라 `update`** — `.env`의 `SPRING_JPA_HIBERNATE_DDL_AUTO=update`가 yml을 덮는다(컨테이너 `printenv`로 확정) | **스키마 안전망이 없다.** Hibernate가 프로드 스키마를 조용히 ALTER한다. "부팅했으니 스키마가 맞다"가 성립하지 않는다. CLAUDE.md **§2-0 신설**로 정정 |
| 2 | **실사용이 거의 없다** — `orders` 0 · `theater_states` **0** · `user_illustrations` 0 · 활성 구독 2 · users 10 · 7일 내 활동 유저 **1명**. 라이브 축은 SANDBOX 24방 · STORY 6방 · UGC 월드 6 · 씬 일러 9 | 극장 축(D-5.x · E-4.4 · INT-1/2 · B-6.1) 실피해 **현재 0**. 우선순위는 SANDBOX/STORY/UGC/씬일러로 이동 |
| 3 | **`PORTONE_WEBHOOK_SECRET`이 `.env`에 키 자체가 없다** — `PaymentController:201-208`이 prod fail-closed | **결제 웹훅이 1차 배포분부터 전량 막혀 있다.** `orders` 0건이라 아직 무해하나 **런칭 전 필수 주입**(PortOne 콘솔 값 ↔ 종원 작업). `MODELSLAB_WEBHOOK_SECRET`은 키는 있고 값이 빈 문자열 → 현재 fail-open |

부수 확정:
- **D-5.6(극장 prefetch 전량 실패)을 코드로 확정**했다 — `prefetchNextBatchAsync`가 `@Async`인데 `@Transactional`이 없고, `theaterPrefetchExecutor`에 `TaskDecorator`가 없으며(코드베이스 전체 0건), `open-in-view=true`(프로드 부팅 로그가 재확인)라 async 스레드에는 세션이 없다. `decideNextSpeakerHeroine`이 **모든 경로에서** detached 엔티티의 LAZY `character`를 역참조한다. → **계획서 §6-1 ①(프로드 로그 grep)은 불필요**해졌다(극장 플레이 0건이라 애초에 로그도 없다). 배치 4는 노브 1줄로 확정.
- **9-A′ 불변식 성립 확인** — `story_available=true`인 UGC 6건이 **전부** `ugc_world_id`를 갖고, 공식 월드에 붙은 UGC 5건은 전부 `story_available=false`. 【C】 무회귀.

### H-2. 이번 세션이 닫은 것 (8건 · 6커밋)

| 커밋 | 결함 | 축 |
|---|---|---|
| `07ae9da` | **E-4.16** 동적 배경 캐시 키 정합 — `ChatService:274`가 `@Deprecated` 2인자 폼(canonicalKey 누락)을 쓰는 유일 호출부였다. 오버로드 제거(§2-6) | 🔴 SANDBOX 24방 |
| `81adb50` | **E-5.2.a** UGC 텍스트 수정 경로 하드 키워드 게이트 — **변경된 값만** 검사 | 🔴 UGC |
| `a734815` | **INT-1**(캐시 HIT 재과금 — 1차 B-5.1 회귀) · **INT-3**(evict 누락 극장 4서비스) · **E-4.3**(분류기 verdict 폐기) | ⚪ 극장 |
| `271b9b3` + Admin `0278fef` | **E-6.4** 어드민 UGC 공개 철회 배선 — BE 엔드포인트는 완비돼 있었고 DTO에 `source`·`visibility`가 없어 SPA가 대상을 못 가렸다 | 🔴 운영 |
| `3dbac03` | **안건 16 (b)** = **E-4.7**(리셋 후 5E 구매 409 오차단) + **E-1.8a/8b**(turnIndex↔ordinal 축 불일치). 게이트를 시각 기준 비의존 판정으로, 저장 축을 hidden 제외로. 마이그레이션 불요, **FE 변경 0** | 🔴 씬 일러 |
| FE `51a2dd1` | **E-1.2 · E-1.2b** 토큰 갱신 뮤텍스 일원화(`refreshLock.js` 신설) — axios/SSE 별개 뮤텍스 경합에 의한 **전 기기 강제 로그아웃**, V2 STORY의 갱신 **100% 실패**(httpOnly 쿠키인데 localStorage를 읽었다) | 🔴 전 유저 |

### H-3. ★ 계획서(`round3_plan.md`)를 4곳 정정했다

1. **INT-3는 2곳이 아니라 4곳** — `consumeEnergy` 전수 grep으로 극장 4서비스가 통째로 evict를 안 함을 확인.
2. **E-4.16의 "`updateDynamicBackground`도 3인자로"는 불필요** — 2인자 폼은 `:547` 주석대로 canonicalKey를 의도적으로 보존한다.
3. **E-4.3의 "UNCLEAR는 통과시켜라"는 채택하지 않았다** — 거부 비용이 0E(차감 안 함)이고, LLM 호출 실패 경로가 이미 거부하므로 봐주면 새 비대칭이 생긴다. 근거를 코드 주석에 남겼다.
4. **안건 16은 FE 변경이 필요 없다** — 레지스터 E-1.8b:4574대로 BE에서 축을 맞추면 `goToTurn`·K-윈도우가 코드 변경 없이 정상화된다. 계획서의 "소비처 3~4곳 동시 변경"은 FE 우회안을 택했을 때의 조건이었다.

### H-4. ★★ 이번 세션의 교훈

- **`vite build` 통과가 검증이 아니라는 §3이 또 증명됐다.** `UseStoryV2Stream`에서 `BASE_URL` 정의를 걷어냈는데 `:75`가 아직 쓰고 있었다 — 빌드는 **경고 하나 없이 exit 0**. 그대로 나갔으면 V2 STORY 스트림 전체가 런타임에 죽었다. **dev 서버에서 모듈을 실제 import해 평가**하는 검증이 잡았다.
- **문서 좌표를 믿지 마라 — 파일이 이사했다.** 계획서·레지스터의 `src/hooks/UseChatStream.js`는 실제로 `src/api/`다.
- **에이전트 보고를 grep으로 재확인한 것이 3건에서 값을 했다**(H-3). 특히 "N곳"류 개수는 javadoc 언급이 섞이기 쉽다 — 내 첫 집계도 6곳이었으나 실제 호출은 4곳이었다.
- **세션 도중 브랜치가 바뀔 수 있다.** aichat·FE 워킹트리가 세션 중 `feature/diorama`로 넘어가 있었다. 파일이 "없다"고 나오면 `git branch --show-current`부터 의심하라. 워킹트리를 안 건드리고 조사하려면 `git show <ref>:path` / `git archive <ref> | tar -x`.

### H-5. 남은 것

- **배치 1 잔여**: E-1.1(극장 finalize 실패 시 무증상 정지) · INT-4(`TheaterPlayPage.onError` 토스트 부재) · F2(로비·극장 성인인증 배선 — `SECRET_PRODUCTS_ENABLED` 켜는 날 미드나잇 패스 구매 무반응) · D-29b(死 memo)
- **배치 2 잔여**: **D-18**(웹훅 fail-closed) — `MODELSLAB_WEBHOOK_SECRET`이 빈 값이라 지금 전환하면 씬 일러 웹훅이 전량 401. **종원이 값을 넣은 뒤** 착수
- **배치 3**(스트림 보상·로그·나레이션) — `ChatStreamService`(1680줄)가 5개 결함군의 교차점. **단독 세션**으로 잡을 것
- **배치 4**(극장) — D-5.6 확정으로 `THEATER_PREFETCH_ENABLED=false` 1줄 + E-4.4(V36 불요) + INT-2. 극장 사용량 0이라 우선순위 낮음
- **배치 5** — 안건 18(**V36**) · E-7.1.a(OAuth email UNIQUE 500) · 9-A′【C】【D】
- **안건 16 잔여**: 리셋 후 재입장 시 이전 회차 씬이 K-윈도우에서 여전히 'recent'로 뜬다(logTotal이 0으로 돌아가므로). 회차 분리는 `playthroughSeq` 컬럼(마이그레이션) 또는 시간 기준 FE 판정이 필요 — 확정안의 '마이그레이션 불요' 제약 밖이라 **별도 안건**
- **E-1.2 잔여**: 모듈 스코프 뮤텍스라 **다중 탭 401은 여전히 뚫린다**. BroadcastChannel 또는 서버측 '직전 RT 유예창'(계획서 §4 결정 7) 필요
- **종원 결정 대기**: 계획서 §4의 7건(17-① 극장 리롤 — 추천 **(0) 주석만 정정·P1 강등** / 17-② 경계 명문화 / 다운그레이드 유지 / 레거시 CG 동결 / 안건 18 형태 / 시크릿 구매 동선 / RT 유예창)

### H-6. 검증 베이스라인 (이 시점 실측)

- `compileJava` 통과 · 유닛 **30클래스 / 182건 / 실패·에러 0**
- **로컬 실기동** `Started AichatApplication in 24.658s` — 파생 쿼리 3종(Mongo 2 · JPA 1) 해석 확인
- FE `vite build` 통과 · `not exported` 0건 · **dev 서버 모듈 평가 4/4** · **뮤텍스 기능 검증**(동시 5건 → refresh 1회)
- Admin `vite build` 통과 · `not exported` 0건 · 모듈 평가 확인
- **신규 마이그레이션 번호는 V36부터** (git + 프로드 `flyway_schema_history` 양쪽 대조)

### H-7. ★★ 델타 전수 재대조 (세션 말미) — 표를 다시 신뢰할 수 있게 되돌렸다

§H-2 픽스를 배포한 뒤, **상태가 `잔존`·`부분수정`인 178행을 전부 현재 소스로 재판정**했다
(8묶음 병렬 + 수정됨/잔존 양방향 표본 감사 · 11에이전트 · 기준 `a6f4ec6`).

| | 재대조 전 | **재대조 후** |
|---|---:|---:|
| 살아 있는 행 | 178 | **88** |
| └ **P0** | 19 | **0** |
| └ **P1** | 39 | **10** |
| └ P2 | 85 | 44 |
| └ P3 | 35 | 34 |

**107행의 상태가 바뀌었다.** 각 행 비고 끝에 `⟳재대조 2026-09-03: 이전→최종 · 닫은 세션 · 근거`를 달았고,
판정 전문은 [`delta_reconciliation.md`](delta_reconciliation.md)에 있다.

**오판정의 지배적 원인**: 1차 세션(08-28)이 자기 행을 갱신하지 않았다 — 닫힌 89건 중 약 70건이 1차 소산이다.
한 커밋이 여러 행을 한꺼번에 닫은 사례가 반복된다(`6758b0e` 하나가 E-3 계열 **27행**, FE `7813b1d`가 C-2 계열
**10행**, `05e2368`이 B-10~13 **5행**). 부차 원인 둘: ① 연쇄·게이트로 무해화된 것이 추적되지 않음
(E-3.④.5~8은 ④.10 한 줄로 증상 소멸, E-2.x 9건은 `theater-auto-cg-enabled` 노브 하나로 도달성 차단)
② 좌표 grep으로 못 잡는 유형(D-6.6·D-3.6·E-5.2.b는 표가 지목한 좌표가 무변경인데 **다른 축**에서 닫혔다).

→ **규약: 픽스 커밋마다 해당 델타 행의 상태를 같은 커밋에서 갱신하라.** 이 재대조는 그러지 않은 대가다.

#### 진짜 잔존 P1 10건 — 다음 세션 착수 목록

| ID | 규모 | 위치 |
|---|---|---|
| D-2.a | SMALL | `ChatStreamServiceV2:280-283` 최외곽 catch 무보상 |
| D-2.b · D-2.g | SMALL | `ChatStreamService:484-487` · `:1526-1529` 동형 |
| D-6.4 · D-6.5 | ONE_LINE · SMALL | `ChatStreamService:1518` · `:1032-1042` 로그 유실 |
| E-5.1.b | SMALL | `ChatStreamService:1400` 나레이션 visible SYSTEM 영속 |
| E-4.4 | SMALL | `TheaterService:389` `finalizeChapter` 멱등 가드 부재 |
| E-1.1 | ONE_LINE | 극장 finalize 실패 시 무증상 정지 (FE) |
| E-7.1.a | MEDIUM | OAuth email UNIQUE 충돌 → 500 영구 잠금 |
| B-6.1 | SMALL | 극장 리롤 무과금 — **안건 17-① 대기**(추천: 주석만 정정·P1 강등) |

★ **6건이 `ChatStreamService`(1680줄) 한 파일에 몰려 있다** = 계획서의 배치 3. 좌표가 서로 밀리므로
**단독 세션**으로 잡을 것. 나머지(E-4.4·E-1.1·E-7.1.a)는 독립적이라 먼저 떼도 된다.

#### 게이트차단 10건 — 잠복 덩어리

`E-2.1/2.2/2.3/2.4/2.5/2.6/2.9/2.10/2.12`(전부 `IllustrationPromptAssembler`) + `D-2.k`가
**단일 노브 `LEGACY_THEATER_AUTO_CG_ENABLED`** 하나에 종속돼 있다. 개별 결함이 아니라
"레거시 CG 트랙 존치/폐지" 결정 하나에 묶인 덩어리다 — 노브를 켜는 날 9건이 동시 부활한다.

#### 이 재대조의 한계

표본 감사는 수정됨/잔존 각 12건 이상을 재확인했고 각 1건씩 오판정을 잡아 반영했다.
**내가 직접 코드로 대조한 것은 16건**(수정됨 13 · 잔존 P1 3)이다. 나머지는 에이전트 판정 + 표본 감사 단계이며,
각 행의 `evidence` 인용이 근거로 남아 있다. **착수 직전 해당 심볼 grep은 여전히 권장한다.**

---

## I. 2026-09-04 세션 — 배치 3 + 잔여 P1 + ★배포 파이프라인 장애 해결

### I-1. ★★ 배포가 막혀 있던 진짜 원인 — SSH 무차별 대입이 CI를 튕기고 있었다

배치 3을 푸시했는데 컨테이너가 교체되지 않았다. 진단 순서와 결론:

| 단계 | 결과 | 진단 방법 |
|---|---|---|
| 워크플로 트리거 | ✅ | docs-only 푸시는 `paths-ignore`로 정상 스킵됨을 실측 |
| 빌드·테스트·GHCR 푸시 | ✅ | **`docker manifest inspect ghcr.io/.../lucid-chat-backend:<sha>`** — 해당 커밋 이미지가 GHCR에 존재 |
| SSH 배포 | ❌ | Actions 로그: `ssh: handshake failed: ... read: connection reset by peer` |

**원인**: `sshd`의 `maxstartups 10:30:100`(기본값)이 미인증 연결 10개를 넘으면 확률적으로 드롭한다.
root 패스워드 인증이 열려 있어 무차별 대입이 하루 **7천 건** 쏟아졌고, 그 슬롯 포화에 러너 연결이 걸렸다.
초 단위로 확정한 증거 — 배포 실패 `00:14:02`, 로그 `00:14:56 exited MaxStartups throttling after 00:00:54, 1 connections dropped`
(56초 − 54초 = **02초 시작**, 드롭 1건 = 러너).

즉 **보안 노출이 원인이고 배포 실패는 증상**이었다. 두 문제가 하나였다.
※ 첫 진단("시크릿/키 파싱 실패")은 **틀렸다** — `connection reset by peer`는 연결이 성립한 **뒤**라 시크릿 단계보다 뒤다.
auth.log에 `Accepted publickey`가 없다고 "접속 시도조차 없다"고 읽은 것이 오독이었다(핸드셰이크 전 드롭은 그 줄을 남기지 않는다).

**해결(종원 실행 — 보안 설정은 에이전트가 만지지 않는다)**:
`sed`로 `/etc/ssh/sshd_config`를 고쳤는데 안 먹었다. 두 겹에 막혀 있었다 —
① `Include /etc/ssh/sshd_config.d/*.conf`가 **12번 줄**이라 `50-cloud-init.conf`의 `yes`가
   66번 줄의 `no`를 이긴다(**OpenSSH는 먼저 읽은 값이 이긴다**).
② `/etc/cloud/cloud.cfg.d/95_ds-vultr.cfg`의 `ssh_pwauth: 1`이 재부팅마다 그 파일을 다시 쓴다.
→ **`00-hardening.conf`**(사전순으로 `50-`보다 먼저)에 `PasswordAuthentication no` +
  `PermitRootLogin prohibit-password`를 넣어 두 겹을 모두 통과. fail2ban도 설치·활성.
결과 실측: `passwordauthentication no` · `permitrootlogin without-password` · fail2ban 1 IP 차단 ·
MaxStartups 드롭 0건.

### I-2. 배치 3 (스트림 보상·로그·나레이션)

| 커밋 | 결함 |
|---|---|
| `5eed974` | **D-2.a · D-2.b · D-2.g** — 스트림 5경로 무보상 창 차단(`committed` 플래그로 TX-2 커밋 전/후 분리) |
| `cb7a49f` | **D-6.5 · D-6.4** — ASSISTANT 저장을 `ChatLogPersister`(3회 백오프+deadletter)로 일원화 + 속마음 게이트 |
| `f57d705` | **E-5.1.b(부분)** — eventContext 500자 절단 + 인젝션 적재 |
| `8b3364f` | 적대적 검토 반영 (아래 I-3) |

**★ D-2.b가 두 세션을 살아남은 이유는 주석이 틀렸기 때문이다.** 최외곽 catch에
"여기는 TX-2 커밋 이후 구간 — 레지스터 D-2.b는 '환불하지 않는다'로 종결됐다. 되돌리지 말 것"이라
적혀 있었으나, 그 catch가 감싸는 try는 `:176`부터 시작해 **차감을 포함**한다. 레지스터의 결론은
*커밋 이후 구간에 한정*된 것이었다. 나도 그 주석을 읽고 스킵할 뻔했다.

### I-3. 적대적 검토가 잡은 것 — **내 변경이 만든 새 실패 모드 2개**

1. **이중 환불** — `compensateEnergy`가 환불 TX 커밋 **뒤** try 밖에서 `evictUserProfile`을 부르는데
   `RedisCacheService.evict`에 try/catch가 없었다. Redis 순단 → 예외가 **새로 만든** 최외곽 catch로 →
   `committed=false` → 같은 차감을 **한 번 더 환불**. 배치 이전엔 그 catch가 `log.error` 한 줄이라
   물리적으로 불가능했던 경로다. → `evict`를 감쌌다(3개 무효화 헬퍼가 전부 위임하므로 20여 호출부가 함께 닫힌다).
2. **전액 환불했는데 분기가 살아남음** — TX-1이 `setDirectorInterlude`를 커밋하는데 해제 호출부 3곳이
   전부 TX-2 람다 안이라 보상 경로에서 실행되지 않는다. BRANCH 4E 전액 환불 후에도 constraint가
   장전돼 다음 **1E 턴**이 그 분기를 최우선 지시로 실행한다. → `compensateDirectorState` 신설(BRANCH만;
   AWAY는 직전 값을 캡처하지 않아 복원값을 추측하게 되므로 의도적으로 제외하고 경계를 주석에 남겼다).

**★ 그리고 주석 좌표 10건이 틀렸다** — 이 저장소가 가장 비싸게 치는 실패를 또 냈다.
`CLAUDE.md §D`(**CLAUDE.md에 §D는 없다** — §1~§5뿐) 4곳 · 재주입 `:1348`→실제 1409 ·
인젝션 `:222`→246 · persister `:369-383`→390 · `ChatService:409-411`→415 ·
"모든 후속 턴 영구 부착 → **무한 증폭**"→ `findTop20` = **최근 20건 윈도우**(과장).
→ **라인 번호를 심볼 참조로 교체**했다. 숫자를 고치는 것으로는 재발을 못 막는다(매 편집마다 밀리는 게 원인).
원장(`defect_register.md`)에도 같은 좌표가 있어 함께 고쳤다 — 안 고치면 코드↔원장을 순환한다.

### I-4. 잔여 P1 추가 처리

| 커밋 | 결함 |
|---|---|
| `e870c0e` + FE `6725a4d` | **E-7.1.a · E-7.1.b** — OAuth 이메일 UNIQUE 충돌 500 영구 로그인 불가 |
| `1e50243` | **INT-6**(미등재 신규) — V2 액션 파라미터 주입면 |

- **E-7.1.a**: `@Transactional`이 **자기호출이라 무효**였다(protected + `this.`). `SocialUserUpsertService`로
  분리해 실제 적용 + INSERT 전 `findByEmail` 검사. 충정책은 확정분 §B #19 **(B)** 리다이렉트.
  레이스는 `DataIntegrityViolationException`을 **TX 밖에서** 받아 `REQUIRES_NEW` 재조회 —
  같은 TX 안 재조회는 rollback-only로 죽는다.
- **FE에 쿼리 파싱이 아예 없었다** — 서버는 진작 `account_suspended`·`unknown_provider`로
  리다이렉트하고 있었는데 화면이 안 읽어 **두 경로 모두 조용히 실패**해 왔다. 함께 닫았다.
- **INT-6**: `actionType`·`toLocationKey`가 원문 그대로 `[ACTION:]` SYSTEM 로그로 영속되고
  히스토리 조립이 그것만 예외적으로 **재주입**한다. 게다가 모더레이션·인젝션 스캔이 **둘 다**
  `!userMessage.isBlank()` 게이트라 `message=""`면 두 스캔을 통째로 우회한다.
  → 화이트리스트 정규화 + 개행/대괄호 제거 + 120자 절단(서로게이트 보호) + `ACTION_V2` 적재.
  **거부면은 만들지 않았다.**

### I-5. 남은 것

- **진짜 잔존 P1 3건**: `E-4.4`(극장 finalizeChapter 멱등 가드 — 마이그레이션 불요) ·
  `E-1.1`(극장 finalize 실패 무증상 정지, FE) · `B-6.1`(안건 17-① 대기) — **전부 극장 축, 프로드 사용량 0**
- **E-5.1.b 잔여 2건 = 종원 결정 대기**: ① 안건 13 (나) `hiddenSystem` 복귀(= '[Bug Fix A] 새로고침 시
  히스토리 표시'를 되돌리는 UX 회귀) ② CRITICAL 인젝션 시 차단 vs 로깅만
- **P2 44 · P3 34** — 이번 스코프(P0/P1+자산손실 P2) 밖. 런칭 기준 재확인 필요
- **`PORTONE_WEBHOOK_SECRET` 부재** → 결제 웹훅 fail-closed. `orders` 0건이라 아직 무해하나 **런칭 전 필수**
- **`MODELSLAB_WEBHOOK_SECRET` 빈 값** → D-18(웹훅 fail-closed 전환) 착수 선행 조건

---

## J. 세션 마감 (2026-09-04) — ★P0/P1 종결 · 다음 세션은 P2/P3

### J-1. 도달 상태

| | 재판정 직후(08-21) | **현재** |
|---|---:|---:|
| 살아 있는 결함 | 178 | **79** |
| └ **P0** | 19 | **0** |
| └ **P1** | 39 | **1** |
| └ P2 | 85 | 43 |
| └ P3 | 35 | 35 |

**착수 가능한 P0/P1은 전부 닫혔다.** 남은 P1 1건은 `E-5.1.b`(부분수정)이고 그 잔여는
**코드가 아니라 결정 1건**이다 — 아래 J-4.

### J-2. 이 세션이 닫은 것 (전량 배포·검증 완료)

| 커밋 | 결함 |
|---|---|
| `5eed974` | D-2.a · D-2.b · D-2.g — 스트림 5경로 무보상 창 |
| `cb7a49f` | D-6.5 · D-6.4 — ASSISTANT 로그 유실 |
| `f57d705` | E-5.1.b(부분) — eventContext 하드닝 |
| `8b3364f` | 적대적 검토 반영 (이중 환불 · 분기 constraint · 서로게이트 · 주석 좌표 10건) |
| `e870c0e`+FE`6725a4d` | E-7.1.a · E-7.1.b — OAuth 500 영구 로그인 불가 |
| `1e50243` | INT-6 — V2 액션 주입면 (미등재 신규) |
| `014c8e6`+FE`6844b1f` | 안건 13 재확정(나레이션 삭제 허용) · 안건 17-① (0)안 |
| `e3a9b25`+FE`839cb34` | E-4.4 · E-1.1 · INT-4 — chapter-end 멱등 + 잠금 해제 + 무증상 정지 |

### J-3. ★ 다음 세션(P2/P3)이 먼저 알아야 할 것

1. **델타(`rejudgment_delta.md`)는 2026-09-03 전수 재대조를 거쳐 다시 신뢰할 수 있다.**
   상태가 바뀐 행에는 `⟳재대조` / `[OK]수정` 마커가 붙어 있다. 다만 **착수 직전 심볼 grep은 여전히 권장**한다
   (내가 직접 코드 대조한 것은 표본 16건 + 이번 세션 처리분이다).
2. **픽스 커밋마다 해당 델타 행을 같은 커밋에서 갱신하라.** 재대조가 필요했던 원인이 정확히
   '한 커밋이 여러 행을 닫았는데 표를 안 고친 것'이었다(`6758b0e` 하나가 E-3 계열 27행).
3. **라인 번호 대신 심볼로 인용하라.** 이번 세션에 내가 남긴 주석 좌표 10건이 틀렸고, 그 원인이
   매 편집마다 좌표가 밀리는 것이었다. 원장(`defect_register.md`)의 좌표도 같은 이유로 낡아 있다.
4. **검증은 빌드가 아니라 실행이다.** `vite build`가 `BASE_URL` 미정의를 경고 없이 통과시켜
   V2 스트림이 죽을 뻔했다. **dev 서버에서 `await import()`로 모듈을 실제 평가**하는 패턴을 쓸 것
   (이 세션에서 세 번 값을 했다). BE는 새 빈·파생 쿼리를 넣었으면 반드시 `bootRun`.
5. **새 거부면(400/409)을 만들 때는 FE 복구 경로를 같은 커밋에 배선하라.** 그리고
   `GlobalExceptionHandler`의 상태 매핑에 **반드시 추가**하라 — `default -> 500`이라
   빠뜨리면 클라이언트 귀책 충돌이 5xx로 나간다(이번에 STALE_CLIENT_STATE·UNPAID_BATCH가
   그렇게 누락돼 있던 것을 발견해 고쳤다).

### J-4. 종원 결정 대기 (코드 준비 완료, 결정만 남음)

- **E-5.1.b 잔여 1건** — CRITICAL 인젝션 감지 시 **차단** vs **로깅만**.
  현재는 적재만 한다(`ACTION_V2`·`EVENT` source). 차단은 새 거부면이라 오탐 시 UX가 손상된다.
  프로드에 적재가 쌓이기 시작하면 그 분포를 보고 결정하는 것을 권한다.

### J-5. 런칭 전 필수 (종원 작업 — 코드 아님)

- **`PORTONE_WEBHOOK_SECRET` 주입** — 현재 `.env`에 키 자체가 없어 `PaymentController`가
  prod fail-closed다. `orders` 0건이라 아직 무해하나 **첫 결제에서 터진다.** (행정 절차 대기 중)
- ~~`MODELSLAB_WEBHOOK_SECRET`~~ — **필수 아님으로 정정(2026-09-04)**. 씬 렌더는 RunPod으로
  이전됐고 ModelsLab이 남은 곳은 ① 레거시 캐릭터 CG(노브 차단) ② 시크릿 분위기 배경뿐이며,
  후자는 `pollModelsLabUntilComplete`로 폴링 완결한다(웹훅은 보조). 프로드 시크릿 구매자도 0명.
  시크릿 BM을 실제로 여는 시점에 '시크릿 주입 + D-18 fail-closed'를 세트로 하면 된다.

### J-6. 인프라 잔여

- **`maxstartups 10:30:100`은 기본값 그대로다.** 이번에 `PasswordAuthentication no` +
  fail2ban으로 공격 밀도를 낮춰 배포가 정상화됐으나, 슬롯 상한 자체는 안 올렸다.
  배포가 다시 산발적으로 튕기면 `MaxStartups 100:30:200` 상향을 검토할 것.
- 롤백 태그 `lucid-rollback:pre-v32-v35`가 서버에 있다. `deploy.sh`는 사전 백업·자동 롤백이
  없고 성공 시 `docker image prune -f`로 구 이미지를 지우므로, **배포 전 태그 박기**를 관례로 둘 것.

### J-7. 다음 세션 스코프 제안

P2 43 · P3 35 = **78건**. 전부가 런칭 필수는 아니다. 착수 전에 종원과
**"런칭 기준을 어디까지로 볼 것인가"**를 먼저 정하는 것을 권한다 — P0/P1이 0인 지금이
그 선을 긋기 좋은 시점이다. 자산 손실·데이터 유실 축만 추리면 P2 43건 중 실제 대상은 훨씬 적다.
