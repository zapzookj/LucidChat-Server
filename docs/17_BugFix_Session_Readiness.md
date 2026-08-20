# 17. docs/13 버그픽스 세션 착수 준비 — 전수 재검증·배치 재설계·결정 안건 (2026-08-20)

> 선행: [`13_BugSweep_Findings.md`](13_BugSweep_Findings.md)(확정 104건 원본) · [`14_ProductDecisions_Session_Handoff.md`](14_ProductDecisions_Session_Handoff.md)(§C #6 블록 D·§G 처분 21건) · [`14_assets/impl_spec_details.md`](14_assets/impl_spec_details.md)(§5 블록 D 노트·§6 재작업 금지) · [`16_SecretMode_Pivot_Directive.md`](16_SecretMode_Pivot_Directive.md)(시크릿=핵심 BM·C-1 0순위)
> **전수 레지스터(정본 좌표)**: [`17_assets/defect_register.md`](17_assets/defect_register.md) — 원자 245건, 근거·수정안·결정연동 전문.
> 이 문서는 **수정 착수 전 상태**다. 코드 변경 0건.

---

## A. 검증 방법과 범위

docs/13은 2026-08-07~09 작성이고 그 **이후에 블록 A(로비·게스트)·블록 B(페르소나)가 들어갔다.** 따라서 docs/13의 파일:라인은 상당수 낡았고 일부 결함은 이미 소멸했다. 라인 번호를 신뢰하지 않고 **심볼·문자열 grep으로 master HEAD 실코드를 전수 재확인**했다.

기준 커밋 — aichat `14fd094` · LucidChat-Front `55a4b78` · LucidChat-Admin `0188aba`.
12개 섹션 병렬 검증 + 2개 횡단 판정(폐기·충돌 / 실행계획) = 14 에이전트, 툴콜 1,052회. 워크플로 `wf_5e329094-d80`.

**원자화**: docs/13은 한 문단에 여러 결함을 묶어 놓은 곳이 많다(§E-1·§E-4는 한 문단에 15~17건). 수정 단위·파일·레이어가 다르면 쪼갠 결과 **104 → 245 원자**.

---

## B. 검증 결과 — docs/13 대비 정정

| 상태 | 건수 | 비고 |
|---|---|---|
| 🔴 잔존 | **236** | 블록 A·B는 결제·극장·업적·UGC 파이프라인을 한 줄도 건드리지 않았다 |
| ✅ 수정됨 | 6 | B-2, B-3(V2분), C-1.1, C-1.2, C-3.1, E-4.14 |
| 🟠 부분수정 | 2 | D-2.n(NPE 드레인), E-2.15(씬 성별) |
| ⚪ 코드소멸 | 1 | C-0.1(로비 STORY 버튼 — 로비 R2로 소멸) |

심각도 재평가: **P0 30 · P1 70 · P2 103 · P3 42**. 규모: ONE_LINE 102 · SMALL 108 · MEDIUM 28.
대상: BE 155 · FE 60 · YML 41 · DB 마이그레이션 14 · INFRA 8 · Admin 5.

### docs/13 본문 정정 5건

1. **B-3 "V1·V2 양쪽"은 사실이 아니다.** 현재 V2는 `boostModeResolver.resolveEnergyCost()`로 서버 산정한다. 클라 지정은 V1 `/events/select` 하나뿐 — 그리고 그 경로는 **FE 호출처가 0건**(아래 D6).
2. **B-5 제안이 과대**하다. 전용 엔드포인트 `POST /theater/rooms/{id}/prefetch`가 이미 있고 FE 유일 호출부가 `prefetch=false` 고정이라, 플래그 제거는 **FE 무영향 ONE_LINE**이다.
3. **배치3 "Flyway V25"는 무효.** V25는 블록 B가 선점했다. 같은 번호로 파일을 만들면 로컬 dev DB가 checksum mismatch로 부팅 실패하고 프로드는 조용히 통과 — 최악의 분기. **신규는 V26부터**, 관심사별 분리. ⚠ **갱신(2026-08-20): V26은 블록 D가 선점했다** (`V26__drop_bpm_not_null.sql` — §G-8 BPM 폐지 선행 스키마). 버그픽스 세션은 **V28부터**: V28 결제·구독 / V29 에너지 분할 / V30 일러·극장 (V27도 블록 D가 씀 — 승급 NOT NULL 해제).
4. **배치4의 `SWIMSUIT→SWIMWEAR`는 '저비용 고효과'가 아니다.** 해당 맵은 §G-6 동결 대상 트랙이라 고쳐도 유저에게 도달하지 않는다.
5. **docs/13의 "targetCharacterId 서버 요구를 완화하라" 제안이 틀렸다.** 그 값은 지급 트래킹용으로 의도된 것이다(`SecretModeService:148`). FE가 보내도록 고치는 게 맞다.

---

## C. ★ 전제를 바꾸는 발견 3건

### C-1. 극장 엔딩은 **정상 플레이로 도달 불가**하다 — 블록 D "극장 유지"의 전제가 코드에 없다

체인을 끝까지 추적해 확정했다.

```
TheaterState.markEnded()          ← 호출처 단 1곳: TheaterEndingService:120 (triggerEnding 내부)
  ↑ sessionStatus="ENDED"를 세팅하는 유일 지점 (전수 grep 확인)
TheaterArchivePage.jsx:207         ← "엔딩 다시 보기" CTA는 sessionStatus==="ENDED" 카드에만 렌더
  ↓ 유일한 UI 진입
POST /theater/rooms/{id}/ending → triggerEnding
```

**엔딩을 발동시켜야만 켜지는 플래그를, 엔딩 발동 버튼의 표시 조건으로 쓰고 있다.** 게임플레이 쪽(`TheaterPlayPage`)에는 엔딩으로 가는 네비게이션이 없고, `TheaterDirectorEngine`에 ending 참조도 0건이다. URL 직타(`/theater/:id/ending`)만이 유일한 발동 경로다. 게다가 결과를 영속하지 않아 그 CTA는 실제로는 항상 실패한다.

→ **docs/14 §C#6을 문구 그대로 집행하면 "자유·스토리 엔딩은 끄고, 남긴 극장 엔딩은 도달 불가" = 제품에서 엔딩 문법이 전멸한다.**

### C-2. C-1 성인인증은 **"수리 완료"가 아니다** — 경로만 고쳐졌고 체인은 여전히 끊겨 있다

블록 B가 고친 것은 FE의 이중 프리픽스 404 두 곳뿐이다. 그 뒤가 전부 막혀 있다.

| 구간 | 현재 | 좌표 |
|---|---|---|
| NICE 자격증명 | `client-id: YOUR_NICE_CLIENT_ID` 리터럴 | `application.yml:64-66` |
| 콜백 return-url | `https://yourdomain.com/verify/callback` | `application.yml:67` |
| FE 콜백 라우트 | **`/verify/callback` 라우트가 App.jsx에 없음** | FE `App.jsx` |
| PortOne 자격증명 | `api-key: YOUR_PORTONE_API_KEY` 리터럴 | `application.yml:71-72` |
| FE 가맹점 코드 | `IMP.init('imp_YOUR_CODE')` — 그것도 **죽은 PaymentModal 안에만** 존재 | FE `PaymentModal.jsx:72` |

⚠ **정정**: yml 값이 리터럴 플레이스홀더지만 `@ConfigurationProperties(prefix="portone"/"nice")`이므로 **ECS 환경변수(`PORTONE_API_KEY`, `NICE_CLIENT_ID` …)로 코드 변경 없이 주입 가능**하다(Spring relaxed binding). 즉 자격증명은 *계약만 있으면* 인프라 작업으로 끝난다. **진짜 코드 결함은 두 개** — ① FE `/verify/callback` 라우트 부재 ② `IMP.init`이 폐기 예정 모달에만 있어 `PaymentModal`을 지우면 결제 초기화가 코드베이스에서 소멸(순서 역전 시 결제 전면 불능).

→ docs/16이 "C-1 0순위"라 못박았는데 **0순위가 아직 안 끝났다.** 그리고 시크릿 체인 3구간 중 정상인 것은 토글(C-3)뿐이다.

### C-3. §G-7 ↔ §G-13이 **같은 함수에서 충돌**한다 (docs/14 작성 시 미인지)

`sendAutoDirectorResponse`가 두 역할을 겸한다 — AWAY/INTERLUDE 소비 경로(§G-7 "정리" 대상)이면서 동시에 이벤트 카드 처리 경로(§G-13 "골격 유지" 대상).

- 디렉티브 경로: `ChatPage.jsx:498` / `ChatPageV2.jsx:635`
- 이벤트 카드 경로: `ChatPage.jsx:1885` / `ChatPageV2.jsx:2676` (주석에 `sendEventSelectStream`에서 이관됐다고 명시)

→ 이 충돌에 결함 9건이 걸려 있어 범위 정리 전엔 착수할 수 없다.

---

## D. 배치 재설계 (docs/13 §H 대체)

docs/13 §H는 블록 A·B 이전 상태 기준이라 그대로 쓸 수 없다. 아래가 검증 결과를 반영한 새 계획이다.

| 배치 | 내용 | 대상 | 병렬 |
|---|---|---|---|
| **0 프리플라이트** | `flyway.enabled` 원복 · 테스트 베이스라인 · 프로드 DB 사전조사 4쿼리 · Vercel env 확인 | — | 선행 필수 |
| **A 현 스택 배포** | 미푸시 14커밋(BE 8 → 2h → FE 6)을 먼저 프로드 반영 | 배포만 | 순차 강제 |
| **1 착취 차단** | B-3.2 음수 가드 · B-1.1 merchant_uid · B-4 극장 분기 · C-0.3/0.4 · B-10/11/12/13 · B-8.5 | BE 단독 | ✔ |
| **1B DB 무결성** | **V28**(orders.imp_uid unique + 구독 부분 유니크) · D-4 구독 정합 3건 | BE+DB | 배치 1 직후 단독 리비전 |
| **2 결제·인증 개통** | C-1.3~1.5 · C-2 전량 · PortOne 초기화 이관 | BE+FE+INFRA | **행정 선행 확인 필요** |
| **3 자산 손실 정지** | V29 EnergySplit · D-1 호출부 7곳 · D-2 · D-3 UGC 좀비잡 · D-6 | BE 단독 | ✔ |
| **4 극장 구조** | D-5 prefetch batchId · B-4.a 2단 롤아웃 · B-5 · E-4 극장분 | BE(+FE 후속) | ✔ |
| **5 시드·문자열** | E-3 ② 유령키 11행 · ③ BGM · ④.10 blank · E-2.13/14/15 | YML 중심 | ✔ 가장 안전 |
| **6 어드민** | E-6 · F-6 | Admin 독립 | ✔ 완전 독립 |
| **7 FE 단독** | E-1 대부분 · F 카피 | FE | 배치 2와는 순차 |
| **8 블록 D 종속** | ⛔ **착수 금지** — 아래 결정 후 | — | — |

최대 병렬도 5 (1 / 2 / 4 / 5 / 6). **배치 7은 배치 2와 `ChatPageV2.jsx`가 충돌**하므로 2 이후.

### 배치 8로 유보된 것 (지금 고치면 버려짐)

블록 D·§G 처분에 종속돼 **수정 여부 또는 수정 방식 자체가 미정**인 것 — 약 60건.
B-7 업적 / B-8·B-9 엔딩 대부분 / B-3.1 `/events/select` / 디렉터 계열 9건 / 레거시 CG 트랙 15건 / E-2 일러 맵 12건 / E-3 ① V1 장소 12건 / E-3 ④ 엔딩 시드 8건 / E-4.1·4.2 승급 / B-6 극장 리롤 / E-4.7 리셋 씬일러 / E-6.1 남캐 인스펙션.

---

> ⚠ **2026-08-21 갱신 — 이 절은 블록 D 이전 상태다.**
> 블록 D가 완료되어 아래 17건 중 **10건이 해소**됐고 새 결정거리 4건이 생겼다.
> **갱신된 결정 안건 정본은 [`18_Launch_Admin_Runbook.md`](18_Launch_Admin_Runbook.md) §4**다.
> 이 절은 각 결정의 *근거와 선택지*를 남겨두기 위해 원문 그대로 보존한다.

## E. 결정 안건 (블록 D 이전 원문 — 정본은 docs/18 §4)

### E-1. 지금 막혀 있는 것 (이게 정해져야 배치 8이 움직인다)

**① 블록 D를 버그픽스보다 먼저 할 것인가**
- (a) **먼저** → 배치 8의 ~60건 중 30건 남짓이 스스로 소멸. 버그 총량이 30% 줄고 재작업 0.
- (b) 나중 → 그동안 엔딩·업적 착취면이 라이브(현재 게이트 플래그 0건 확인). 임시 가드를 넣었다가 버리게 됨.
- **추천 (a)** — 단 아래 ②③이 선결.

**② 게이트를 어디에 넣을 것인가** ★ 가장 위험한 갈림길
- (a) **서버 가드**(`EndingController`·`AchievementController` 진입부 + yml 노브) → P0 착취 6건이 한 번에 닫힌다.
- (b) docs/14 §E 문구대로 "프론트 진입점"만 제거 → **API가 소유권 검사만으로 열린 채 남는다.** impl_spec §5가 beta-activate에 대해 정확히 이 실수를 경고했다.
- **추천 (a), 강하게.** §E의 "프론트 진입점"은 축약이지 명세가 아니라고 본다.

**③ 극장 엔딩(C-1 발견) 처분**
- (a) **발동 경로를 살린다** — ACT_4 종료 시 endingAvailable 신호 + 결과 영속. 블록 D 규모가 §E 표의 '소'를 넘어선다.
- (b) 코드만 보존, 실사용 안 함 → 제품에서 엔딩이 사실상 전멸.
- (c) 극장 엔딩도 함께 게이트 오프 → §C#6 재결정.
- **추천 (a)**, 블록 D 규모 재산정 전제. 영속 계층을 V1/V2/극장 공용으로 설계하면 B-9.1+B-9.9+E-4.9가 한 작업으로 합쳐진다.

**④ §G-7 ↔ §G-13 범위 정리 (C-3 발견)**
- (a) `sendAutoDirectorResponse` **존치**, AWAY 분기와 `activeDirector*` 필드만 정리 → 이벤트 카드는 §G-13대로 생존.
- (b) 메서드 통째 삭제 → §G-13이 유지하기로 한 이벤트 카드가 함께 죽고 FE 재배선 필요.
- **추천 (a)** — (b)는 §G-13과 정면 모순.

**⑤ §G-4 "엔딩 시드 필드 삭제" 범위 정정**
코드 확인 결과 `ending-role-desc`는 V1 전용(삭제 안전)이지만 **`ending-quote-*` 2필드는 극장 폴백이 공용**(`TheaterEndingService:111,270`)이다.
- (a) role-desc만 삭제 + quote 공란 4캐릭터 채움(8줄 저작).
- (b) 문서대로 일괄 삭제 → 극장 엔딩이 무대사 씬을 낸다.
- **추천 (a)** — 사실상 선택지가 아니라 문서 정정 사항.

**⑥ 레거시 CG 트랙(§G-6) 처분** — 공수 대비 효과 최대
지금도 FE에서 살아 있어(`IllustrationModal.jsx:102`) **유저 10E가 환불 없이 소각 가능**하다.
- (a) 코드 폐지 → E-2 12건 + D-2.h/i/j 소멸. 갤러리 개편 동반.
- (b) **yml 노브로 진입점만 차단, 코드 보존** → 같은 15건 소멸, 공수 최소, 오늘 10E 소각 정지.
- (c) 존치·수리 → 최대 비용(맵 12건 + 환불 설비 + LoRA DB 일반화).
- **추천 (b) 즉시 → (a) 후속.** ※ `D-2.k` 웹훅 시크릿 필수화는 트랙 처분과 무관하게 지금 해야 함(`BG_` 시크릿 배경이 같은 컨트롤러를 탄다).

**⑦ `/events/select` 처분** — FE 호출처 0건 확인(import·주석만 남음)
- (a) **삭제**(BE 엔드포인트+서비스+FE 래퍼) → B-3.1·D-2.d·D-6.1·E-4.17.a·E-5.1.a가 **비용 0으로 동시 소멸**. 기능 회귀 없음.
- (b) 존치·수리 → 옵션 토큰 영속 설계 필요.
- **추천 (a)**, §G-4 데드코드 목록에 추가 등재.

### E-2. 행정·인프라 확인 (배치 2 착수 가부)

**⑧ NICE 본인확인 / PortOne 가맹 계약 상태**
- NICE client-id/secret/product-id가 **발급되어 있는가?**
- PortOne 실 가맹점 코드(`imp_xxxxxxxx`)가 **발급되어 있는가?**
- 미발급이면 docs/16의 "C-1 0순위"는 코드가 아니라 **계약 착수**로 재정의되고, 배치 2는 FE 콜백 라우트·IMP 초기화 이관까지만 하고 대기한다.
- 부수: NICE CheckPlus 콜백이 **GET 쿼리인지 POST 폼인지** — 이에 따라 콜백 수신이 SPA 라우트냐 백엔드 302냐로 갈린다. 연동 스펙 문서 확보 여부.

**⑨ 미푸시 14커밋을 먼저 배포할 것인가 (배치 A)**
BE 8커밋(+2996/−490) · FE 6커밋(+3377/−3731)이 미푸시고 프로드는 ECS rev 45(08-07)다.
- (a) **먼저 배포** → 버그픽스가 독립 리비전이 되어 롤백 가능. V25 파괴적 DDL도 단독으로 검증.
- (b) 버그픽스와 함께 → 첫 배포가 "블록A+블록B+V25+픽스"가 되어 **장애 시 원인 분리 불가·롤백 불능**.
- **추천 (a).** 단 사전에 프로드 DB 스냅샷 + Vercel 프로덕션 env에 `VITE_LOCAL_ASSET_FALLBACK` **부재 확인**(있으면 프로드 전 이미지가 로컬 경로로 리라이트돼 깨진다).

**⑩ `flyway.enabled: false` 미커밋 변경의 처분**
현재 워킹트리 유일 변경이다. 원복이 원칙이지만, 로컬 DB 이력 불일치 때문에 껐던 것이라면 원복 시 로컬 부팅이 깨질 수 있다.
- 원칙: **yml이 아니라 환경변수**로 끌 것 — [`docs/03`](03_UGC_E2E_Runbook.md)이 쓰는 `$env:SPRING_FLYWAY_ENABLED="false"; .\gradlew.bat bootRun` 형태. (단 docs/03의 2단계 부팅 절차 자체는 `ddl-auto=update` 시절 것이라 지금 프로드에는 쓰지 말 것 — prod는 `validate`라 1차 부팅이 실패한다.)
- ⚠ 이 한 줄이 실수로 push되면 프로드가 Flyway 없이 부팅한다 → V27/V28이 미적용인 채 "고쳤다고 믿는" 상태가 된다. **이번 세션 전 커밋에서 `git add .`·`git commit -a` 금지.**

### E-3. 배치 8 전까지 정하면 되는 것

| # | 안건 | 추천 |
|---|---|---|
| ⑪ | V1 장소 enum 노선(E-3 ①, 12건) — enum 5값 확장 / 시드 교정 / 동적배경 일원화 대기 | **확장(5줄, 마이그레이션 불요) 즉시 + 일원화 후속.** 복붙 오타 ①.6/①.9는 노선 무관 즉시 교정 |
| ⑫ | V2 STORY 엔딩(E-4.9, 현재 확정 500 NPE) — 서버에서 닫기 / 멀티히로인 엔딩으로 살리기 | **닫기(3줄).** 기존 `endingReached=true` 잠긴 방 처리도 함께 |
| ⑬ | V2 리셋 시 씬 일러(E-4.7) — 함께 삭제 / `playthroughSeq` 좌표계 분리 | **좌표계 분리.** 삭제는 §G-6 '빈 갤러리 금지' 위반 |
| ⑭ | 극장 스탯 리롤 과금(B-6) — BM에 리롤권이 없다(설계 누락) | **에너지 3E + yml 노브** (UGC 리롤 2E 관례와 정합) |
| ⑮ | 시크릿 '대상 캐릭터 선택' UI(C-2.k) — 접근 게이트가 user-global인데 UI는 캐릭터별로 오표시 | **UI 제거 + 현재 화자 자동 첨부.** 영구해금 카피를 "전 캐릭터"로 |
| ⑯ | 잔존 V1 STORY 방 처분(§G-2) — 마이그레이션 / 읽기 전용 | 방을 고아로 만들지 않는 쪽 |
| ⑰ | PortOne V1 유지 vs V2 전환(B-1.3 웹훅 서명) | **V1 + 공유 시크릿·IP 화이트리스트 선행**, V2 전환은 PG 심사 후 별건 |

---

## F. 결정 없이 오늘 착수 가능 (버려지지 않음)

1. **B-3.2** `User.consumeEnergy` 음수 방어 — **1줄**, 전 경로 착취의 뿌리
2. **B-1.1 + B-1.2 + B-1.3(a)** 결제 정합 — PG 심사 선행 필수
3. **C-0.4 + C-0.3** V1 STORY 생성 400 + IAE→400 매핑 (§G-2 최종 정책이자 C-2.c의 500도 닫힘)
4. **E-3 ②.1~②.12** V2 루틴 유령 장소 키 11행 + 시더 검증기
5. **E-3 ④.10** `!= null` → `isBlank()` — 어떤 노선에서도 유효(극장 폴백 보호)
6. **D-2.k** 일러 웹훅 시크릿 필수화 — 환불 파밍면 선차단
7. **E-1.12a/b** 성인인증 모달 데드엔드 2줄 — docs/16 0순위 플로우의 잔여 고착
8. **B-10.1/10.2 + B-11.1** RT typ/jti 클레임 + XFF 리밋 키(기존 `ClientIpResolver` 재사용)
9. **D-6.5** `saveAssistantLog` persister 위임 — 4경로 중 최소 2개 확정 생존
10. **E-3 ①.6/①.9** 시드 복붙 오타 2줄 · **E-3 ③** worlds.yml BGM 1줄

---

## G. 검증 수단과 회귀 위험

**테스트 자산**: BE 23파일(22개 순수 유닛, 베이스라인 녹색 실측). `src/test/resources` 없음 → `@SpringBootTest`는 CI에서 의도적 제외(사실상 죽은 테스트). 통합·리포지토리·컨트롤러 테스트 **0건**. **FE·Admin은 테스트 프레임워크 자체가 없다** — `npm run lint` + `build` + 수동이 전부.

확장 가능: `ChatStreamServiceV2CompensationTest`(D-2), `SceneManualRequestTest`(D-3.3), `SceneRenderServiceTest`(E-2.15), `ClientIpResolverTest`(B-11). 신설 권장: `UserEnergyTest`(B-3.2·D-1.1), `PaymentServiceTest`(B-1.1), `JwtTokenServiceTest`(B-10).

**회귀 위험 지목 7**:
1. `consumeEnergy` 반환형 변경 시 **1-arg `refundEnergy` 오버로드를 남기지 말 것** — 남기면 낡은 경로로 조용히 컴파일된다.
2. `ChatStreamService`(1700줄) 5개 SSE 엔트리 동시 수정 — 자동 테스트 0 구간. 한 커밋에 몰지 말 것.
3. `IllegalArgumentException → 400` 매핑 — 지금까지 500이던 **진짜 서버 버그가 400에 묻힌다**. 핸들러에 `log.warn(스택)` 필수.
4. `E-4.3` 극장 명령어 분류기 verdict 반환 — 배포 즉시 유저 체감 변화(통과하던 명령이 거부된다). 로그만 먼저 며칠.
5. `D-2.k` 웹훅 실패 전이 — ModelsLab 중간 상태 문자열 집합 미확정. 그대로 켜면 **정상 생성을 죽이고 환불까지 나간다.** 로그 수집 선행.
6. `B-4.a` 극장 분기 — `branchToken` 필수화를 한 번에 하면 구 FE 극장이 전량 400. **3단 롤아웃 필수**(관용 모드 → FE 배포 → 필수화).
7. FE 로컬 에셋 폴백(`b1349e7`)의 전역 `MutationObserver` — 프로드 env에 플래그가 새면 전 이미지 깨짐. 테스트가 못 잡는 구조적 위험.

**커밋 위생**: 파일 명시 `git add`, 매 커밋 전 `git status --porcelain`로 `application.yml` 미스테이징 확인, `./gradlew test --tests '*Test'` 녹색 유지.
