# 19. 레지스터 재판정 — 블록 D 반영 후 실잔량·회귀·결정 안건 (2026-08-21)

> 선행: [`17_BugFix_Session_Readiness.md`](17_BugFix_Session_Readiness.md)(배치 계획·원 안건 17건) · [`17_assets/defect_register.md`](17_assets/defect_register.md)(원자 245건 근거·수정안 전문) · [`18_Launch_Admin_Runbook.md`](18_Launch_Admin_Runbook.md)(행정 정본·§4 결정 안건)
> **기준 커밋** — aichat `20c4cf9` · LucidChat-Front `b062997` · LucidChat-Admin `0188aba`
> docs/18 §4-D가 "다음 세션 첫 작업은 레지스터 재판정"이라 지목한 그 작업의 결과다. **코드 변경 0건.**

## 부속 정본

| 문서 | 내용 |
|---|---|
| [`19_assets/rejudgment_delta.md`](19_assets/rejudgment_delta.md) | **원자 245건 최종 상태 + 갱신 좌표 114건** — docs/17_assets와 어긋나면 이쪽이 정본 |
| [`19_assets/blockd_regressions.md`](19_assets/blockd_regressions.md) | 블록 D가 만든 회귀 22건 + 레지스터 미등재 신규 21건 |
| [`19_assets/decision_agenda.md`](19_assets/decision_agenda.md) | **결정 안건 22건 + 결정 불요 즉시 착수 33건** (전문) |
| [`19_assets/decisions_confirmed.md`](19_assets/decisions_confirmed.md) | **★ 종원 확정 답변 (2026-08-21)** — 안건 문서의 '추천'이 아니라 **이 표가 결정 정본**. 안건 9(나이 게이트) 판정·안건 12(복장) 재판정 포함 |

---

## A. 방법과 검증 수단

레지스터의 파일:라인은 블록 D로 대부분 낡았다 — `ChatStreamService.java` 순 -373줄, `ChatRoom.java` -137줄, `CharacterPromptAssembler.java` -130줄, FE `ChatPage.jsx` -307줄 / `ChatPageV2.jsx` -330줄, `BiometricStatusPanel.jsx` 743줄 전면 재작성. 라인 번호를 신뢰하지 않고 **심볼·문자열 grep으로 245건 전수 재확인**했다.

9개 그룹 병렬 재판정 → 상태가 바뀐 판정만 **적대적 역검증**(거짓 소멸 차단) → 블록 D 회귀 스캔 3축 → 완결성 비평. 20 에이전트 · 툴콜 830회.

**판정 어휘를 하나 늘렸다 — `게이트차단`.** 블록 D의 원칙은 "코드 보존, 진입만 차단"이므로 `legacy.*` 노브 뒤로 들어간 결함은 **소멸이 아니다.** 노브를 켜면 그대로 부활한다. 이 구분이 없으면 "런칭 후 엔딩을 되살리자"는 순간 P0 6건이 함께 돌아온다.

**베이스라인 실측** — `compileJava` 통과 · 테스트 21클래스 / **116건 전부 녹색**(`cleanTest` 강제 재실행). 단 아래 §C의 P0 2건은 이 녹색 상태에서 나왔다.

---

## B. 재판정 결과

| 상태 | 건수 | 이전(docs/17) |
|---|---|---|
| 🔴 **잔존** | **197** | 236 |
| 🟡 **게이트차단** | **24** | (어휘 없음) |
| 🟠 부분수정 | 4 | 2 |
| ✅ 수정됨 | 13 | 6 |
| ⚪ 소멸 | 6 | 1 |
| ↔ 재분류 | 2 | — |

**실제 수정 대상(잔존 + 부분수정) = 201건.** 심각도 **P0 19 · P1 59** · P2 86 · P3 37. 규모 ONE_LINE 87 · SMALL 93 · MEDIUM 20.
<sub>(2026-08-26 갱신 — D-3.6이 D절 P1 → **B절 P0-A 착취**로 재분류됐다. 순 0E 무한 GPU 드레인이 성립한다.)</sub>
**좌표 갱신 114건.** 역검증에서 **9건 번복**(전부 "너무 낙관적인 판정"을 되돌리는 방향).

### 블록 D로 실제로 정리된 것

| 구분 | ID | 근거 |
|---|---|---|
| **소멸 5** | B-3.1 · D-2.d · D-6.1 · E-4.17.a · E-4.2 | `/events/select` 체인이 BE에서 **완전 삭제**(StoryController 159줄 전체) · `totalNormalStatDelta`·`promotion_*` 심볼 전 소스 grep 0건 |
| **수정됨 7** | B-8.5 · B-9.8 · E-4.12 · E-4.1 · E-1.11b · C-0.2 · C-0.4 | 극장 엔딩 발동 경로 · 기억 하이라이트 · isUpgrade rank 비교 · V2 statusLevel · §G-2 무조건 400 |
| **게이트차단 24** | B-7 · B-8.1/8.3/8.4 · B-9.1/9.2/9.4/9.5/9.7 · E-1.15 · E-3.④.1~4 · E-4.9 · E-4.10 · F-3.a · F-4.a · D-2.h/2.i/2.j · E-2.7/2.8/2.11 | 코드 무변경 — 노브를 켜면 전부 부활 |

엔딩 게이트는 **3층으로 서버측에** 걸렸다(EndingController 진입부 · V1 트리거 발화 · V2 자격 활성). 업적은 AchievementService 4개 진입부 전부. **docs/17 §E ②의 "게이트는 반드시 서버측" 결정은 제대로 집행됐다.**

### 블록 D가 손대지 않은 축 (판정 변화 0)

결제·구독·성인인증(G7 33건) · 에너지 환불·UGC 잡(G8 28건) · 인증·보안·어드민(G9 26건) — **87건이 전량 잔존**이고 좌표도 대부분 유효하다. 즉 **버그 총량은 245 → 201로 18% 줄었을 뿐이다.** docs/18 §4-D가 기대한 "배치 8이 거의 비었다"는 엔딩·업적 축에 한정된 이야기다.

---

## C. ★ 블록 D가 만든 신규 결함 — P0 2건은 지금 라이브를 막는다

전문은 [`19_assets/blockd_regressions.md`](19_assets/blockd_regressions.md).

### ~~C-1. `EndingResultRepository`가 Mongo 스캔 범위 밖 — **서버가 부팅되지 않는다**~~ ✅ **해소 (2026-08-21)**

> ✅ **해소 · 실기동으로 검증됨.** `MongoConfig.java`의 `basePackages`에 `"com.spring.aichat.domain.ending"` 추가((a)안).
> 로컬 `bootRun` 결과 **`Started AichatApplication in 22.86 seconds`** — Tomcat 8080, Flyway(`Schema "public" is up to date`), JPA EMF, 시더까지 전부 통과.
> **이 프로젝트에서 컨텍스트 기동이 실측 검증된 첫 사례다.** 재현 명령은 CLAUDE.md §3에 넣었다(`JWT_SECRET_BASE64` 더미 필요).

`config/MongoConfig.java:34-38`
```java
@EnableMongoRepositories(basePackages = {
    "com.spring.aichat.domain.chat",
    "com.spring.aichat.domain.theater"      // ← domain.ending 이 없다
})
```
`25d0fb0`이 신설한 `EndingResultRepository`는 `com.spring.aichat.domain.ending` 패키지다. 저장소 전체에 `@EnableMongoRepositories`는 이 1건뿐이고 `@EnableJpaRepositories`는 아예 없다(주석만 `MongoConfig.java:23`). spring-boot 3.4.2의 `MongoRepositoriesAutoConfiguration`은 `@ConditionalOnMissingBean(MongoRepositoryFactoryBean, MongoRepositoryConfigurationExtension)`이라 명시적 `@EnableMongoRepositories`가 있으면 **back-off한다**(3.4.2 클래스파일 실물로 확인). 빈이 만들어지지 않고 `TheaterEndingService.java:57`의 `private final ... EndingResultRepository` 생성자 주입이 실패한다.

**극장 엔딩이 아니라 서비스 전체가 안 뜬다.** 잡히지 않은 이유는 CLAUDE.md §3이 적어 둔 그대로다 — 통합·컨텍스트 테스트가 0건이고 `@SpringBootTest`는 CI 글롭에서 의도적으로 제외돼 있다. 수정은 `basePackages`에 한 줄.

> <sub>(원래 기록: "실기동 확인은 못 했다 — 로컬 Postgres가 내려가 있고 AWS도 정지 중이다." → 로컬 Postgres 17.2가 살아 있었고, 위와 같이 확인 완료.)</sub>

### C-2. 업적 게이트 오프에서 이스터에그 발동 시 NPE — 턴이 통째로 소실 (P0 확정)

`AchievementService.java:67`이 게이트 오프(기본값)에서 `null`을 반환하는데, 유일 호출부가 무가드로 역참조한다:
```java
// ChatStreamService.java:1096-1099
var unlock = achievementService.unlockEasterEgg(userId, eggType);
return new EasterEggEvent(eggType.name(),
    new AchievementInfo(unlock.code(), unlock.title(), ...), revert);   // ← NPE
} catch (IllegalArgumentException ignored) { return null; }             // ← NPE를 못 잡는다
```
NPE가 TX-2 밖 `catch (Exception e)`(`:340`)로 전파돼 `compensateFullRollback` + `TX_ERROR`. **유저는 스트리밍 본문을 다 본 뒤 에러를 받고, assistant 로그·유저 메시지·스탯 변화가 전부 사라진다.** 프롬프트(`CharacterPromptAssembler.java:543·565`)가 여전히 이스터에그를 유도하고 `supportsEasterEggs`는 SANDBOX 전용 — **주력 채팅 표면**이다.

### C-3. FE 댕글링 import — dev에서 채팅 화면이 로드되지 않는다 (P0, **브라우저 실증**)

`ChatPage.jsx:26`·`ChatPageV2.jsx:40`이 `sendEventSelectStream`을 named import하는데 `UseChatStream.js`는 더 이상 export하지 않는다. dev 서버에서 실제로 재현:

```
SyntaxError: The requested module '/src/api/UseChatStream.js'
             does not provide an export named 'sendEventSelectStream'
```

`TheaterPlayPage`·`BiometricStatusPanel`은 정상 로드된다. **프로덕션 빌드는 트리셰이킹으로 통과**하므로 `npm run build` 검증이 못 잡았다. 이 상태로는 docs/18 §2-A의 수동 검증 4~6단계를 로컬에서 시작조차 못 한다.

### C-4. P1 확정 4건

| 결함 | 좌표 |
|---|---|
| BRANCH 서버 권위 과금이 `chosenIndex` 미전송·재시도에서 `cost=1`로 무너짐 (가격표를 성공 시 evict) | `DirectorService.java:270-288` · `ChatStreamService.java:1361-1362` |
| `ending_results` 유니크 인덱스가 prod에 생성되지 않음(`application-prod.yml:20 auto-index-creation:false`) → 중복 시 조회 영구 500 | `EndingResultDocument.java:37-40` |
| `triggerEnding` 락·멱등성 없음 — GET 우선 → 404 → POST 구조가 경합을 실제 경로로 만듦 | `TheaterEndingService.java:75-92` |
| FE BRANCH 에너지 **이중 차감** (블록 D가 오차를 +1에서 정확히 2배로 키움) | `ChatPage.jsx:1809+1813` · `ChatPageV2.jsx:2646+2650` |

---

## D. ★ docs/18 §4-A 정정 2건 — "다시 묻지 않는다" 표에 틀린 항목이 있다

| 원 번호 | docs/18의 기재 | 실측 |
|---|---|---|
| **⑫** | "V2 STORY 엔딩 NPE(E-4.9) — 엔딩 게이트로 **도달 불가**" | **틀렸다.** 게이트는 `EndingEligibilityService.java:62` `checkAndActivateEligibility`에만 있고, 실제로 엔딩을 확정하는 `processDirectorTrigger`(`:96`)에는 없다. 방어가 `:104 room.isEndingEligible()` 하나뿐이라 **게이트 도입 이전에 `ending_eligible=true`가 저장된 기존 V2 STORY 방은 `ChatStreamServiceV2.java:685` 경로로 여전히 `:114 markEndingReached`에 도달한다.** 그리고 감상 경로는 게이트로 400이라 '도달만 하고 볼 수는 없는' 상태로 영구 고정된다 |
| **④** | "§G-7 ↔ §G-13 범위 — (a) 확정·**구현**" | 결정은 (a)로 확정됐으나 V1 디렉터 정리는 **미집행 상태로 남았다**(E-1.3). §4-B로 내려야 한다 |

**틀린 '해소됨' 표기는 다음 세션이 끝난 일로 착각해 잔존 엔드포인트를 감사 대상에서 빼게 만든다.** 두 건 모두 §4-B로 되돌릴 것.

---

## E. 결정 안건 — 22건 (전문: [`19_assets/decision_agenda.md`](19_assets/decision_agenda.md))

재판정 원자에서 나온 결정거리 65 + 회귀 22 + 미등재 21 + 1차 비평 10 = **후보 57건을 병합 → 적대적 가지치기 → 22건**. 걷어낸 기준은 ① 이미 답이 있는 것 ② 코드·쿼리 1회로 답이 나오는 것 ③ 지금 물으면 답할 수 없는 종속 안건 ④ "고칠까 말까" 수준.

### E-1. 착수 전 필수 (14건)

| # | 안건 | 추천 | 막고 있는 것 |
|---|---|---|---|
| 1 | **부팅 블로커 — 극장 엔딩 리버트 후퇴 기준선** | 한 줄 픽스 선행 푸시 + "배포 D-1까지 로컬 bootRun 미수행이면 `25d0fb0` 리버트"처럼 **날짜형 기준선**을 받아 둘 것 | AWS 복구 후 첫 배포 전체. 극장 엔딩 계열 6건의 판정이 의미를 가지려면 이게 먼저 |
| 2 | **엔딩 게이트 구멍 + 이미 잠긴 방 처분 (V28 내용물)** | 코드 구멍은 즉시 닫고, 데이터는 **프로드 1쿼리 후** 결정 | 배치 1B 전체. §4-A ⑫ 정정 |
| 3 | 구독 중복 활성 행 정리 (V28 부분 유니크) | 만료일 최장 우선 | V28 · 배치 1B |
| 4 | **지급 실패 결제 처분** — 돈은 나갔는데 지급도 환불도 없다 | 재시도 큐 + 감사 로그 | PG 심사 전 결제 정합 커밋 |
| 5 | 구독 티어 이월 산식 (현행: 조용한 소멸) | 일할 이월 | 약관 환불 산식 TODO |
| 6 | 환불 시 회수 실패 처리 | 회수 선검증 후 환불 | 약관 TODO · CS 정책 |
| 7 | 시크릿 노출 토글 범위 (PG 심사) | env 토글로 완전 게이팅 | docs/18 §1-D 심사 제출 |
| 8 | 시크릿 상점 진입면 정리 (극장 판매 중단 포함) | 대상 캐릭터 UI 제거 + '전 캐릭터' 카피 | 배치 2 |
| 9 | UGC 캐릭터 나이 필드 | 도입 (시크릿 나이 하드 게이트 범위 동반) | docs/16 §A |
| 10 | **레거시 CG 트랙 최종 처분 (§G-6 ↔ §C#6 원칙 충돌)** | **전용 노브 분리**(`theater-auto-cg-enabled`, 기본 off) | 잔존 197건 중 **27건 남짓의 존폐**. 안건 11·16의 작업량 |
| 11 | V1·V2 장소 어휘 정본 | enum 확장 + 후속 일원화 | 배치 5 (E-3 ① 15 · ② 14) |
| 12 | §G-5 해금 노브 사각 — 시크릿 전용 복장(NEGLIGEE) 예외 | `isSecret` 차집합 분리 | 배치 2 · 3-F 성인 자산 목록 |
| 13 | BRANCH `eventContext` 신뢰 경계 | 서버 원본 확정 | 배치 1 |
| 14 | **BRANCH 과금 폴백 정책** (V2 + 극장 토큰 만료 통합) | 2단 롤아웃(관용 → 계측 → 조임) | §G-13 실행 검증의 판정 기준 |

> **안건 10이 최대 레버다.** `legacy.illustration.legacy-cg-enabled` 게이트는 `IllustrationController.java:115` **한 곳뿐**이라, 같은 ModelsLab 트랙을 타는 `IllustrationService.generateAutoIllustration`을 극장 자동 노트가 3경로에서 호출한다 → **트랙 동결이 실효되지 않고 극장 세션마다 외부 과금이 계속 나간다**(유저 에너지 차감이 없어 지표에도 안 잡힌다). 서비스 계층까지 막으면 §C#6 '극장 무변경'과 정면 충돌하므로 **원칙 충돌은 종원만 해소할 수 있다.**

### E-2. 배치 중 필요 (7건) · 나중 (1건)

15 legacy 게이트 상태를 클라이언트에 내려줄 것인가(선행 질문: **노브를 런칭 후 되켤 계획이 있는가**) · 16 씬 일러 좌표계·보존 정본 · 17 극장 리롤 과금 + §C#6 경계 + 미드나잇 초기 스탯 정본 · 18 승급 세리머니 진동 완충 · 19 동일인 다계정·CI 중복 · 20 승인 후 텍스트 수정 정책 · 21 UGC 좀비잡 회수·보상 / 22 `dynamicRelationTag` 존폐.

### E-3. 가지치기로 걷어낸 것 (36건) — 대표

- **이스터에그 연출 존폐** → docs/14 §C#6이 이미 '연출 유지 + 업적만 오프'로 확정. 안건이 아니라 **미이행**(서버 3줄 + FE 2줄)
- **극장 종착 지점을 상태로 남길 것인가** → 스키마 없이 `isEndingPoint`를 `> threshold`로 좁히면 조기 확정·무한 진행이 둘 다 닫힌다. 결정 소멸
- **극장 label 프롬프트 인젝션 근본해** → `branchToken`이 **이미 존재하고 FE가 이미 보내고 있다**(`TheaterGameplayApi.js:24-28`). FE 무수정·BE 단독으로 닫혀 롤아웃 창 결정이 불필요. ※ docs/17 §G 회귀위험 6번의 "B-4.a 3단 롤아웃 필수"는 `branchToken` 도입 이전 기준이라 정정 대상
- **엔딩 인용구 8줄 저작** → docs/17 §E ⑤ (a)로 이미 확정. 남은 건 작업 배정
- **§G-5 노브가 E-3 ①/②의 도달성을 올렸는가** → **반증됨.** ①은 base tier라 원래 첫 턴부터 실렸고 ②는 `getAllowedLocations`를 타지 않는다. 배치 5는 이 답을 기다릴 필요가 없다

---

## F. 결정 불요 — 지금 착수 가능 (33건, 전문은 assets)

상위 3건은 **다른 모든 검증의 선행 조건**이다. 지금 서버가 뜨지 않고 dev 채팅 화면이 로드되지 않는다.

| # | 규모 | 내용 | 좌표 |
|---|---|---|---|
| 1 | ONE_LINE | `basePackages`에 `com.spring.aichat.domain.ending` 추가 — **단독 커밋 선행 푸시** | `config/MongoConfig.java:34-38` |
| 2 | ONE_LINE | `processEasterEgg` null 가드 (achievement=null인 EasterEggEvent 반환) | `ChatStreamService.java:1096-1100` |
| 3 | ONE_LINE | FE 댕글링 named import 2줄 제거 | `ChatPage.jsx:26` · `ChatPageV2.jsx:40` |
| 4 | ONE_LINE | `processDirectorTrigger`에 동일 게이트 추가 (§D ⑫) | `EndingEligibilityService.java:96-118` |
| 5 | ONE_LINE | `EndingService` null 가드 3줄 — 노브 부활 전 선행 조건 | `EndingService.java:69-74` |
| 6 | ONE_LINE | `User.consumeEnergy` 음수 가드 — 전 경로 착취의 뿌리 | `User.java:166` |

이하 7~33: BRANCH 이중차감·evict 시점·극장 동시 발동 차단·종착 가드 3건·감독 노트 IDOR·이스터에그 연출 복구·결제 정합 3종·웹훅 시크릿·에너지 캐시/분할 환불·시드 정리 묶음·데드코드 정리 묶음·성인인증 잔여 개통·보안 잔여 묶음·문서 정정 묶음.

> **성인인증(#30)에 순서 단축이 있다.** docs/18 §1-E는 "NICE 콜백이 GET인지 POST인지 답을 받기 전에는 §3-B를 반쪽만 만들라"고 했으나, **백엔드가 GET/POST 양쪽을 받아 검증 후 SPA로 302**하면 그 답을 기다릴 필요 자체가 사라진다. 지금 정정하지 않으면 docs/16의 'C-1 0순위'가 계약 리드타임에 인질로 잡힌다.

---

## G. 이 재판정의 한계

1. **실행 검증이 0이다.** AWS 정지 + 로컬 Postgres 미기동으로 부팅·통합 확인을 못 했다. 유일한 런타임 관찰은 FE dev 서버 모듈 로드(§C-3)뿐이다.
2. 따라서 **극장 엔딩 부활 계열 판정(B-9.8 수정됨 · B-9.9 · E-4.12 · E-4.5.a)은 전부 "코드를 읽은 결과"**다. §F #1이 들어가고 실제 부팅으로 확인되기 전까지는 그 지위를 넘지 않는다.
3. **부팅 블로커를 감지할 자동 수단이 구조적으로 0이었다.** 컴파일러는 통과했고 유닛테스트 116건도 전부 녹색이었다. 최소한 컨텍스트 로드 테스트 1건을 상시 자산으로 둘지가 검증 체계의 잔여 결정이다(안건화하지 않고 §F 문서 정정 묶음에 포함).
4. 1차 완결성 비평이 낸 "재판정 커버리지 26%"는 **오류**다 — 비평 에이전트에게 집계가 절단 전달된 아티팩트이며, 실제로는 245건이 전수 판정됐다(그룹별 29/26/21/24/22/36/33/28/26).

---

## H. 안건 10 상세 — 레거시 CG 트랙이란 무엇이고 왜 결정이 필요한가

> 종원 요청으로 §E-1 #10을 풀어 쓴다. 확정 답변 기록은 [`19_assets/decisions_confirmed.md`](19_assets/decisions_confirmed.md).
> 아래 사실은 전부 실코드 grep으로 확인했다.

### H-1. 이미지 트랙이 4개다 — 서로 완전히 분리돼 있다

| 트랙 | 무엇을 그리나 | 외부 API | 유저 과금 | 프롬프트 조립기 |
|---|---|---|---|---|
| **① 레거시 캐릭터 CG** | 캐릭터 인물화 (LoRA) | ModelsLab | 수동 10E / **자동 0E** | `IllustrationPromptAssembler` |
| ② 씬 일러 (**핵심 BM**) | 대화 장면 | RunPod ComfyUI | 5E + 실패 자동환불 | `ScenePromptAssembler` |
| ③ 동적 배경 | 장소 배경 | Fal.ai / ModelsLab(시크릿) | 0E | `BackgroundPromptAssembler` |
| ④ UGC 스튜디오 | 캐릭터 제작 | RunPod + fal.ai Qwen | 전 단계 유료 | `UgcPromptAssembler` |

**프롬프트 맵이 트랙 간에 공유되지 않는다.** `service/illustration/scene/` 7파일 전수 grep에서 `outfit` **0건** — 씬 일러는 복장·LoRA 맵을 아예 읽지 않는다. 그래서 **E-2 계열 12건은 전부 ①번 트랙 전용 결함**이고, ①을 닫으면 12건이 통째로 무수정 종결된다.

### H-2. 게이트가 정확히 한 문장뿐이다

```
grep -rn "legacyCgEnabled|getIllustration()" src/main/java/
→ LegacyFeatureProperties.java:57   (정의)
→ IllustrationController.java:115   (사용 — 유일)
```
`IllustrationService.java`의 legacy 참조는 **0건**이다. 즉 게이트는 "유저가 버튼 눌러 10E 내는 수동 경로" 하나만 막았고, **서비스 계층은 열려 있다.**

대조군: `AchievementService`는 서비스 계층 게이트가 **4곳**(`:67`·`:96`·`:124`·`:156`)이다. 업적은 제대로 닫혔고 CG는 반만 닫혔다.

### H-3. 그래서 지금 극장을 플레이하면 CG가 계속 그려진다

```
TheaterAutoNoteService.java:72  AUTO_MOMENT   (히로인 호감도 ±2인 배치)
                        :126  BRANCH_TAKEN  (MAJOR·CLIMAX·LOCATION 분기 선택 시)
                        :166  CHAPTER_END   (챕터 종료)
   → :193 triggerIllustration → :198
   → IllustrationService.java:208 generateAutoIllustration   ← 게이트 없음
   → :232 submitGeneration → ModelsLabClient.java:56
```
- **유저 에너지 차감 0.** `IllustrationService`에서 `consumeEnergy`는 `:119`(수동 경로) 단 1곳뿐이다.
- 유저는 버튼을 누르지 않는다 — 극을 진행하기만 하면 발동한다.
- 결과물은 **극장 다이어리 카드**에 붙는다(`TheaterDiaryPanel.jsx:397`·`TheaterDirectorNotePanel.jsx:527-534`, 둘 다 조건부 렌더).
- 빈도(구조 추정): 챕터 목표 30씬 / 배치 5~8씬 → 챕터당 4~6배치. 배치마다 AUTO_MOMENT 최대 1장 + 챕터 끝 1장 + 유저가 고른 분기마다 1장. **여기에 D-5.2 prefetch 버그로 ×2.** 실측치는 미확인(AWS 정지). 판정 수단은 `[ILLUST] Auto generation submitted: trigger=` 로그(`IllustrationService.java:255`) 카운트다.

### H-4. 왜 §C#6(극장 무변경)과 충돌하는가

**씬 일러가 극장에서 명시적으로 차단돼 있다** — `SceneRequestService.java:100` `if (room.getChatMode() == ChatMode.THEATER) throw ... "극장 모드는 씬 일러를 지원하지 않습니다."`

즉 **극장의 유일한 캐릭터 이미지 산출물이 이 레거시 CG**다. 트랙을 완전히 동결하면 극장 다이어리에서 그림이 사라지고 텍스트 노트만 남는다. 그건 명백히 유저 체감 영역이므로, §G-6(트랙 동결)을 끝까지 집행하는 것은 **§C#6 예외를 승인받는 결정**이지 기술적 처리가 아니다.

### H-5. 지금 그려지는 그림의 품질

존치를 고른다면 이걸 먼저 알아야 한다.

| 결함 | 증상 | 좌표 |
|---|---|---|
| E-2.1 | LoRA 맵이 airi·taeri·luna·yeonhwa 4인 하드코딩 → **나머지 공식 6인·전 UGC가 아이리 얼굴로 그려진다** | `IllustrationPromptAssembler.java:52-67` · `:271-272` |
| E-2.3 / E-2.6 | 복장 맵 AIRI 키가 MAID·DATE·SWIMSUIT 3개뿐 → 극장 방은 `DAILY`/`DEFAULT`로 시작하므로 **전원 메이드복** | `:80-82` · `:290-297` · `ChatRoom.java:349` |
| E-2.10 | 장소 맵 11키 중 enum과 겹치는 게 3개 → **배경이 'simple background'** | `IllustrationPromptAssembler` LOCATION 맵 |
| D-5.2 / D-5.5 | prefetch 가드가 N+1을 검사하는데 저장 키는 N → 가드 영구 무력 → **모든 트리거가 배치마다 2번** | `TheaterService.java:145-146` vs `TheaterBatchGenerator.java:292` |

즉 **품질이 나쁜 그림을 두 배로 그리면서 유저에게는 한 푼도 안 받고 있다.**

### H-6. 선택지 — 유저가 보는 화면 기준

| | 코드 변경 | 극장 다이어리 | 결함 효과 | §C#6 |
|---|---|---|---|---|
| **(a) 완전 동결** | `IllustrationService.java:208` 진입부 1줄 | **그림 사라짐**, 텍스트 노트만 (레이아웃은 안 깨짐) | E-2.1~2.12 전량 + D-2.h/i/j 무수정 종결. 안건 11의 LOCATION 맵 확장도 불요 | **정면 위반** |
| **(b) 극장 예외 존치** | 없음 (현상 추인) | 지금 그대로 | E-2.1·2.3·2.6·2.10·2.12가 **배치 8 → 배치 4로 승격**(선행 수정 필수). D-5.2도 필수 | 준수 |
| **(c) 전용 노브 분리** ★ | 필드 1 + yml 1줄 + 게이트 1줄 (기본 off) | **(a)와 동일** | (a)와 동일하되 판정이 '소멸'이 아니라 **'게이트차단'** — 환경변수로 되살릴 수 있고 데이터 정합이 유지된다 | 원칙(코드 보존)과는 정합, **체감 결과는 (a)와 같음** |

### H-7. 한 문장으로

> **극장 다이어리에 붙는 그림 — 유저가 에너지를 한 톨도 안 내는데 우리가 GPU 값을 무는 그 그림 — 을 계속 그릴 것인가?**

- **계속 그린다 → (b).** 단 E-2.1(아이리 얼굴)·E-2.3/2.6(메이드복)·E-2.10(빈 배경)·D-5.2(2배 지출)를 **먼저 고쳐야** 한다. 배치 4 규모다.
- **안 그린다 → (c) 권장.** 화면 결과는 (a)와 같으면서 되돌릴 여지가 남고, E-2 계열 12건이 작업 목록에서 통째로 빠진다.

### H-8. ★ 답과 무관하게 지금 고쳐야 할 것

**D-5.2** — `TheaterService.java:145-146`의 prefetch 가드가 `N+1`을 검사하는데 실제 저장은 `TheaterBatchGenerator.java:292`에서 `N` 키로 한다. 결과가 재판정 당시 서술보다 나쁘다: prefetch 히트율이 **구조적으로 0%**이고(N+1 키는 아무도 읽지 않는다), 생성된 배치는 폐기되는 게 아니라 **유저가 지금 소비 중인 배치 N을 덮어쓴다.** 다음 배치는 매번 새로 생성되고 에너지도 재과금된다(`:91`/`:98 chargeBatchEnergy`). 극장의 LLM·CG·배경 비용을 2배로 만들면서 docs/14 §C의 원가 실측까지 오염시킨다.
