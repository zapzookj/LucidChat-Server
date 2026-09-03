HEAD `a6f4ec6`, tree clean, delta table measured at 246 rows (178 of them live-status). The audit's `E-3.④.9` override checks out — 11 empty-string seeds, not 8, and `getEffectiveOocExample()` (Character.java:485) uses a bare `!= null`, so `ooc-example: ""` (application-characters.yml:1152) reaches CharacterPromptAssembler.java:151 as an empty string. Adopting it.

---

# 결함 레지스터 상태 재대조 — 정정된 상태표

> 기준: aichat `a6f4ec6` · LucidChat-Front `51a2dd1` · LucidChat-Admin `0278fef` (2026-09-03)
> 대상: `docs/19_assets/rejudgment_delta.md`(245행 표, 실측 246행) 중 **상태가 `잔존`·`부분수정`인 178행**
> 방법: 8묶음 병렬 코드 실측 + 표본 감사 2회(감사 지적은 감사 채택)

---

## §1. 요약

### 상태 분포 — 재대조 전/후

| 상태 | 표(재대조 전) | **재대조 후** | 증감 |
|---|---:|---:|---:|
| 잔존 | 173 | **71** | −102 |
| 부분수정 | 4 | **18** | +14 |
| 수정됨 | 0 | **68** | +68 |
| 게이트차단 | 0 | **10** | +10 |
| 소멸 | 0 | **10** | +10 |
| MOOT | 0 | **1** | +1 |
| UNVERIFIED | — | **0** | — |
| **합계** | **178**\* | **178** | |

\* 표의 `잔존` 173 + `부분수정` 4 + `잔존·↔재분류(P0)` 1(D-3.6) = 178.

### 심각도 분포 — 살아 있는 행 기준

| 심각도 | 표(재대조 전, 178건) | **재대조 후(잔존+부분수정 89건)** | 증감 |
|---|---:|---:|---:|
| **P0** | 19 | **0** | **−19** |
| **P1** | 39 | **10** | −29 |
| P2 | 85 | 44 | −41 |
| P3 | 35 | 35 | ±0 |
| **합계** | **178** | **89** | −89 |

> **표가 '잔존'이라 했던 178건 중 실제로 살아 있는 것은 89건(잔존 71 + 부분수정 18)이고, 그중 P0은 0건·P1은 10건이다 — 표가 말한 잔존 P0 19건은 전부 이미 닫혀 있었다.**

### 오판정의 원인 (실측된 세 갈래)

1. **1차 세션(08-28)이 자기 행을 갱신하지 않았다 — 지배적 원인.** 닫힌 89건 중 약 70건이 1차 소산이다. 커밋 하나가 여러 행을 한꺼번에 닫은 사례가 반복된다: `6758b0e` 하나가 E-3.①/② 계열 **27행**을, `7813b1d`(FE)가 C-2 계열 **10행**을, `05e2368`이 B-10/11/12/13 **5행**을 닫았다.
2. **연쇄·게이트로 무해화된 것이 추적되지 않았다.** E-3.④.5~8은 자기 좌표(yml)가 그대로인 채 ④.10 한 줄 픽스로 증상만 사라졌고, E-2.x 아홉 건은 `66bb880`의 `theater-auto-cg-enabled` 노브 하나로 도달성이 끊겼다.
3. **좌표 grep만으로는 못 잡는 유형이 있다.** D-6.6·D-3.6·E-5.2.b는 표가 지목한 좌표가 실제로 무변경이지만 **다른 축**(소비 측 캐시 오버레이 / 입력 검증 / 도메인 불변식)에서 닫혔다.

### 게이트차단 10건 — 잠복

`legacy.*` 노브 기본 off로 현재 도달 불가일 뿐 코드는 그대로다. **E-2.1·2.2·2.3·2.4·2.5·2.6·2.9·2.10·2.12**(전부 `IllustrationPromptAssembler.java`, 단일 노브 `LEGACY_THEATER_AUTO_CG_ENABLED`에 종속) + **D-2.k**. 개별 수정 대상이 아니라 "레거시 CG 트랙 존치/폐지" 결정 하나에 묶인 덩어리다. 노브를 켜는 날 아홉 건이 동시 부활하며, 그때 **E-2.9의 `LOVE` 키 삭제 금지**와 **E-2.12 선행 판단**(맵을 채우면 이중 주입이 악화) 경고가 되살아난다.

---

## §2. 상태가 바뀐 행 (103건)

| ID | 이전 → 최종 | 심각도 | 닫은 세션 | 근거 |
|---|---|---|---|---|
| B-1.1 | 잔존 → **수정됨** | P0 | 1차 | PaymentService.java:575-583 merchant_uid 대조, status·금액 검사보다 앞 |
| B-1.2 | 잔존 → **수정됨** | P0 | 1차 | Order.java:20 `uk_order_imp_uid` + V28 마이그레이션 + :614-623 DataIntegrityViolation catch |
| B-1.3 | 잔존 → **수정됨** | P0 | 1차 | PaymentController.java:141-145 시크릿 검증, :200 prod fail-closed |
| B-3.2 | 잔존 → **수정됨** | P0 | 1차 | User.java:172-178 `if (amount < 0) throw` + :188 free 클램프 |
| B-4.a | 잔존 → **수정됨** | P0 | 1차 | TheaterBranchService.java:595-601 오퍼 원본 재판정, 클라 optionsSnapshot 미참조 |
| B-4.b | 잔존 → **수정됨** | P1 | 1차 | :624 `level.getEnergyCost()`, level은 :561 오퍼 원본. 컨트롤러 MINOR 폴백 제거 |
| B-4.c | 잔존 → **수정됨** | P1 | 1차 | :609-620 방 소속 집합 필터 + :726-731 일러 트리거 경로 차단 |
| B-4.d | 잔존 → **수정됨** | P2 | 1차 | :635-641 label·contextSummary 전부 서버 원본, DB 영속도 오퍼 원본 |
| B-4.e | 잔존 → **수정됨** | P1 | 1차 | :547-590 branchToken fail-closed + 좌표 정합 + alreadyResolved 게이트 |
| B-4.f | 잔존 → **소멸** | P2 | 1차 | optionsSnapshot 역참조 소멸, 누락 입력은 500이 아닌 400(컨트롤러 :94-96) |
| B-5.1 | 잔존 → **수정됨** | P0 | 1차 | TheaterController.java:56-61 2-arg 위임, 클라 prefetch 플래그 미독 |
| B-5.2 | 잔존 → **부분수정** | P2 | 1차(코드) | 워터마크 완비(TheaterState:327-332)하나 **기본 fail-open** — application.yml:210 노브 대기 |
| B-9.9 | 잔존 → **수정됨** | P1 | 1차 | MongoConfig.java:34-39에 `domain.ending` 포함 — 표의 '미해결 블로커' 문구 무효 |
| B-9.10 | 잔존 → **수정됨** | P1 | 1차 | TheaterStateRepository:30-32 `findByRoomIdForUpdate` 비관적 락 + 조기 반환 |
| B-10.1 | 잔존 → **수정됨** | P1 | 1차 | JwtTokenService:42/:301-320 `typ` 클레임 + JwtBlacklistFilter:49-64 RT 거부 |
| B-10.2 | 잔존 → **수정됨** | P1 | 1차 | :102 jti + :155-165 회전 시 구 RT 블랙리스트 + 3-arg logout 배선 |
| B-11.1 | 잔존 → **수정됨** | P1 | 1차 | AuthController:59/:119 `ClientIpResolver.resolve`, 구 XFF 최좌측 메서드 삭제 |
| B-12 | 잔존 → **수정됨** | P1 | 1차 | NoticeService.get이 미게시 공지를 404로 은닉 |
| B-13 | 잔존 → **수정됨** | P1 | 1차 | `findByIdAndChatRoom_Id` + 2-arg markRead + StoryV2Controller:221 배선 |
| C-1.3 | 잔존 → **부분수정** | P2 | 1차(코드) | NiceApiClient:55-68 `isUnset` 가드로 '조용한 401' 해소. yml:110-113 `YOUR_*` 리터럴 잔존(행정) |
| C-1.5 | 잔존 → **수정됨** | P1 | 1차 | App.jsx:71 `/verify/callback` 라우트, catch-all(:184)보다 앞 |
| C-2.a | 잔존 → **소멸** | P0 | 1차(FE) | PaymentModal.jsx 파일 부재. 실행 코드 참조 0건(주석 12건) |
| C-2.b | 잔존 → **소멸** | P0 | 1차(FE) | 동일. confirm은 LucidStore.jsx:180 하나로 일원화 |
| C-2.c | 잔존 → **부분수정** | P3 | 1차(FE) | FE `LUCID_PASS_MONTHLY` 실행 코드 0건(주석 1건 ChatPageV2.jsx:2098). BE 400 하드닝 미이행 |
| C-2.d | 잔존 → **소멸** | P0 | 1차(FE) | LucidStore 가격 7종이 ProductType.java:22-32와 전수 일치 |
| C-2.e | 잔존 → **소멸** | P1 | 1차(FE) | LucidStore.jsx:236-243이 시크릿 2종에 targetCharacterId 명시 첨부 |
| C-2.f | 잔존 → **소멸** | P2 | 1차(FE) | 허위 보너스 표기 소멸 — FE potion grep = 주석 1건, BE 0건 |
| C-2.g | 잔존 → **소멸** | P2 | 1차(FE) | ChatPageV2.jsx:1777-1781 `packages`→`pass` + LucidStore:77 미드나잇 패스 실재 |
| C-2.h | 잔존 → **소멸** | P1 | 1차(FE) | portone.js:19/:25/:42 — 플레이스홀더를 거부하는 검사로 대체 |
| C-2.i | 잔존 → **수정됨** | P1 | 1차(FE) | ChatPageV2 7개 좌표 전부 LucidStore로 교체(:317/:1775/:1781/:4342/:4408/:4575) |
| C-2.j | 잔존 → **수정됨** | P1 | 1차(FE) | App.jsx:58 부트스트랩 `initPortOne()` + LucidStore:251-258 결제 직전 게이트 |
| C-2.k | 잔존 → **MOOT** | P2 | 안건 8 확정 | LucidStore:140-142 characters prop 폐기 — 계정 단위 해금으로 결함 정의가 무효화 |
| C-2.l | 잔존 → **수정됨** | P2 | 1차(코드) | PortOneProperties:62 `assertConfigured` 4곳 배선. 환경변수 주입은 행정 잔여 |
| D-2.k | 잔존 → **게이트차단** | P3 | 1차(안건 10 c) | IllustrationService:216 `theaterAutoCgEnabled` 게이트로 유해 경로 차단. BG_ 무과금 트랙만 생존 |
| D-3.6 | 잔존(재분류 P0) → **수정됨** | P0 | 1차 | UgcTextLimits 신설 + CharacterCreationService:279 입력 400 + ConceptStructuringService 4필드 절삭 |
| D-5.1 | 잔존 → **부분수정** | P2 | 3차(a6f4ec6) | 워터마크 가드(TheaterService:231-236)가 유료배치 덮어쓰기 차단. 저장 키 오프바이원은 미착수 |
| D-5.2 | 잔존 → **부분수정** | P3 | 3차 | 같은 가드로 '배치당 LLM 2회' 소멸. 근본 키 정합 미이행(커밋 주석이 연기 명시) |
| D-5.5 | 잔존 → **부분수정** | P3 | 1차+3차 | IllustrationService:216-219 게이트로 ModelsLab 지출 정지. 배경 레그·조건부 호출 미이행 |
| D-6.6 | 잔존 → **수정됨** | P1 | 1차 | UserService:54 `overlayFreshEnergy` + :87-104 — 표가 지목한 스케줄러가 아닌 소비 측에서 닫힘 |
| E-1.2 | 잔존 → **수정됨** | P1 | 3차(FE) | refreshLock.js 신설, axios·SSE 2트랙이 단일 뮤텍스로 합류 |
| E-1.2b | 잔존 → **수정됨** | P1 | 3차(FE) | UseStoryV2Stream.js:98 공용 refreshAccessToken, `refreshToken` 실코드 0건 |
| E-1.8a | 잔존 → **수정됨** | P2 | 3차(3dbac03) | SceneRequestService:145 `countByRoomIdAndHiddenNot` — turnIndex를 ordinal 축에 정렬 |
| E-1.8b | 잔존 → **수정됨** | P2 | 3차 | BE에서 축을 맞춰 FE 무변경으로 정상화(goToTurn 계약 성립) |
| E-1.12a | 잔존 → **수정됨** | P2 | 1차(FE) | AdultVerificationModal:135 함수형 갱신 + deps에서 step 제거 |
| E-1.12b | 잔존 → **수정됨** | P2 | 1차(FE) | :105-112 else 분기에 setStep('error')+setErrorMsg 채워짐 |
| E-1.13a | 잔존 → **수정됨** | P2 | 1차(FE, 안건 8) | 대상 선택기 블록 제거 + :351 시크릿 탭 게이트로 도달성 차단 |
| E-2.1 | 잔존 → **게이트차단** | P3 | 1차(66bb880) | 결함 코드 그대로(:270-272). 공개 진입점 2곳 모두 노브 뒤 |
| E-2.2 | 잔존 → **게이트차단** | P3 | 1차 | :211-212 아이리 폴백 그대로, 유일 호출처가 게이트 아래 |
| E-2.3 | 잔존 → **게이트차단** | P3 | 1차 | :296 MAID 폴백 그대로, 극장 사슬 종점이 게이트 뒤 |
| E-2.4 | 잔존 → **게이트차단** | P3 | 1차 | SWIMSUIT↔SWIMWEAR 키 불일치 4곳 그대로, CG 조립 미도달 |
| E-2.5 | 잔존 → **게이트차단** | P3 | 1차 | PAJAMA·NEGLIGEE 키 부재 그대로 |
| E-2.6 | 잔존 → **게이트차단** | P3 | 1차 | `"DEFAULT"` 키 부재 → MAID 폴백 구조 그대로 |
| E-2.9 | 잔존 → **게이트차단** | P3 | 1차 | 감정 키 맵 그대로. **LOVE 삭제 금지 경고는 노브 복원 시 부활** |
| E-2.10 | 잔존 → **게이트차단** | P3 | 1차 | `simple background` 폴백 그대로 |
| E-2.12 | 잔존 → **게이트차단** | P3 | 1차 | 정체성+복장 이중 주입 구조 그대로 |
| E-2.13 | 잔존 → **수정됨** | P2 | 1차(6758b0e) | CharacterPromptAssembler:81-83 ageLine null 가드 |
| E-2.14 | 잔존 → **수정됨** | P2 | 1차 | TheaterPromptAssembler:212-217 Age null 억제 |
| E-3.①.1 | 잔존 → **수정됨** | P2 | 1차(안건 11 a) | `domain/enums/Location.java`에 CATHEDRAL 추가 + V31 CHECK 동기화 |
| E-3.①.2 | 잔존 → **수정됨** | P2 | 1차 | baseLocations `CATHEDRAL`이 유효 키가 됨 |
| E-3.①.3 | 잔존 → **수정됨** | P2 | 1차 | TERRACE enum 등재 |
| E-3.①.4 | 잔존 → **수정됨** | P2 | 1차 | baseLocations `TERRACE` 유효화 |
| E-3.①.5 | 잔존 → **수정됨** | P2 | 1차 | characters.yml:835 `STREET`으로 교정(TERRACE 복붙 오배정 해소) |
| E-3.①.6 | 잔존 → **수정됨** | P2 | 1차 | :835 ↔ :850 값 일치 |
| E-3.①.7 | 잔존 → **수정됨** | P2 | 1차 | STREET enum 등재 + V31 CHECK 포함 |
| E-3.①.8 | 잔존 → **수정됨** | P2 | 1차 | :1061 `LIBRARY`로 교정 |
| E-3.①.9 | 잔존 → **수정됨** | P2 | 1차 | :1061 ↔ :1077 일치 |
| E-3.①.10 | 잔존 → **수정됨** | P2 | 1차 | LIBRARY enum 등재 — LOCATION_PROMPTS:123과도 정합(E-2 동반 불요) |
| E-3.①.11 | 잔존 → **수정됨** | P2 | 1차 | ❓결정 확정 — `ANCIENT_SHRINE`으로 V1/V2 어휘 통일 |
| E-3.①.12 | 잔존 → **수정됨** | P2 | 1차 | baseLocations도 ANCIENT_SHRINE 통일 |
| E-3.①.13 | 잔존 → **수정됨** | P3 | 1차 | ChatRoom.java:1014-1045 폴백 3인방에 WARN 부착 |
| E-3.①.14 | 잔존 → **수정됨** | P3 | 1차 | CharacterSeeder:60/:109-140 enum 키 대조기 신설 |
| E-3.①.15 | 잔존 → **수정됨** | P3 | 1차 | charactersm.yml 유령 키 소멸(:402 CATHEDRAL · :481 TERRACE) |
| E-3.②.1~②.12 | 잔존 → **수정됨** (12행) | P2 | 1차 | v2.yml 유령 키 치환(MOONLIT_FOREST→DEEP_FOREST · GARDEN_OF_MIRRORS→GARDEN_OF_ACADEMY, 설정값 0건) + ②.12는 CharacterRoutineSeeder:134-146 선언 대조기 |
| E-3.②.13 | 잔존 → **부분수정** | P3 | 1차(부수) | 시드발 유령 키는 상류 차단. LLM 디렉터 키(WorldRoutingService:196)는 무검증 잔존 |
| E-3.③.1 | 잔존 → **수정됨** | P2 | 1차 | worlds.yml:54 `TOUCHING` — MYSTERIOUS 설정값 0건 |
| E-3.③.2 | 잔존 → **부분수정** | P3 | 1차 | ChatRoom:1038-1045 폴백 목적지·로그 수정. WorldSeeder 검증 미이행 |
| E-3.④.5 | 잔존 → **부분수정** | P3 | 1차(연쇄) | yml:841-842 빈 값 그대로. ④.10 isBlank 폴백으로 증상만 소멸 |
| E-3.④.6 | 잔존 → **부분수정** | P3 | 1차(연쇄) | yml:952-953 동일 |
| E-3.④.7 | 잔존 → **부분수정** | P3 | 1차(연쇄) | yml:1067-1068 동일 |
| E-3.④.8 | 잔존 → **부분수정** | P3 | 1차(연쇄) | yml:1188-1189 동일 |
| E-3.④.9 | 잔존 → **부분수정** | P3 | 1차(부분) | **감사 채택** — 엔딩 3필드는 blank 가드(:417-420). 그러나 `ooc-example: ""`(yml:1152)는 가드 밖이고 `getEffectiveOocExample()`(:485)이 `!= null` 단독이라 여전히 도달 |
| E-3.④.10 | 잔존 → **수정됨** | P2 | 1차(c9a7d2b) | Character.java:505-518 getter 3종 전부 isBlank 가드 |
| E-4.3 | 잔존 → **수정됨** | P2 | 3차 | TheaterCommandClassifier:341 verdict 반영 + :322 정규화 + :327 WARN |
| E-4.7 | 잔존 → **수정됨** | P2 | 3차(3dbac03) | SceneRequestService:104-110 게이트를 좌표계 비의존으로 재정의 |
| E-4.16 | 잔존 → **수정됨** | P2 | 3차(07ae9da) | ChatService:278-279 canonicalKey 사용 + 2인자 오버로드 제거 |
| E-5.2.a | 잔존 → **수정됨** | P1 | 3차(81adb50) | UgcCharacterService:43/:117 하드 키워드 게이트 — 변경분만 검사 |
| E-5.2.b | 잔존 → **수정됨** | P1 | 1차(안건 20 A) | Character.java:884-893 승인 후 텍스트 수정 → PENDING_PUBLIC 회귀 |
| E-5.3.a | 잔존 → **수정됨** | P2 | 1차 | UgcWorldService:480-483 `markNeedsRereview()` |
| E-6.4 | 잔존 → **수정됨** | P1 | 3차 | BE `271b9b3`(source·visibility DTO) + Admin `0278fef` unpublish 4곳 |
| E-7.2 | 잔존 → **부분수정** | P3 | 1차(BE) | BE 400 거부 완비(:105-109). FE textarea maxLength 미전달(StudioCreateFlow:1768-1782) |
| F-3.b | 잔존 → **소멸** | P3 | 1차(부수) | useInvisibleMan.js 재작성으로 dialogueMap 선언 자체가 사라짐(grep 0건) |
| F-4.b | 잔존 → **소멸** | P3 | 1차(FE) | catch 전용 나레이션 소멸 + josaIGa 조사 처리로 통합 |

---

## §3. 진짜 잔존 목록 (89건) — 다음 세션 착수 목록

> P0 **0건**. 심각도 순, 같은 심각도 안에서는 축별로 묶었다.

### P1 (10건)

| ID | 심각도 | 규모 | 현재 좌표 | 요약 |
|---|---|---|---|---|
| D-2.a | P1 | SMALL | ChatStreamServiceV2.java:283-286 (차감 :198) | V2 최외곽 catch 무보상 — TX-1 차감 후 route까지 예외 시 에너지 증발 |
| D-2.b | P1 | SMALL | ChatStreamService.java:487-494 (try :176 · 차감 :213) | V1 동일. ⚠catch 주석이 "TX-2 이후"라 단정하나 try는 TX-1 이전부터 — **주석이 결함을 은폐** |
| D-2.g | P1 | SMALL | ChatStreamService.java:1556-1559 (차감 :1383-1386) | 디렉터 자동응답 무보상. 비용이 서버 가격표 재판정이라 손실 폭이 크다 |
| D-6.4 | P1 | ONE_LINE | ChatStreamService.java:1548 (:1546 applyParsedToRoom) | 자동응답 ASSISTANT 저장이 무보호 헬퍼 직행 + 방 상태만 먼저 반영되는 비대칭 |
| D-6.5 | P1 | SMALL | ChatStreamService.java:1047-1057 (호출 :592/:717/:1548) | `saveAssistantLog`가 retry·deadletter 없이 예외 삼킴 — D-6.2/6.3/6.4의 공통 뿌리 |
| E-5.1.b | P1 | SMALL | ChatStreamService.java:1394-1397·1420-1422 · StoryController.java:158 | **부분수정** — 원 경로는 소멸했으나 승계 경로가 악화: 무검증 eventContext가 visible SYSTEM 로그로 저장·매 턴 재주입·삭제 불가 |
| E-7.1.a | P1 | MEDIUM | OAuth2LoginSuccessHandler.java:126-207 | 소셜 3경로 모두 email UNIQUE에 무방비 INSERT(`existsByEmail` 0건) — 계정 영구 잠금 |
| E-4.4 | P1 | SMALL | TheaterService.java:388-525 · TheaterController.java:87-93 | `finalizeChapter` 멱등 가드 0건 — 연타로 스태미나 리필·챕터 진행 중복 |
| E-1.1 | P1 | ONE_LINE | useTheaterStream.js:160-166 (catch :170) | finalizeChapter 실패 시 `chapterEnding`이 안 풀려 극장 UI 영구 잠금 |
| B-6.1 | P1 | SMALL | TheaterLobbyService.java:513-536 | 스탯 리롤에 차감·리롤권 소모 전무(`consumeEnergy` 0건) — 무한 무료 리롤 |

### P2 (44건)

| ID | 심각도 | 규모 | 현재 좌표 | 요약 |
|---|---|---|---|---|
| D-2.c | P2 | ONE_LINE | ChatStreamService.java:487-494 (저장 :232-235) | 최외곽 catch가 compensateFullRollback 미호출 — 고아 USER 로그 영구 잔류 |
| D-2.e | P2 | SMALL | ChatStreamService.java:600-603 (차감 :521) | 지켜보기 경로 무보상. StoryController:120 무게이트 생존 |
| D-2.f | P2 | SMALL | ChatStreamService.java:783-786 (차감 :649) | 시간 넘기기 무보상. §G-7 명시 존치 경로 |
| D-2.n | P2 | SMALL | WorldRoutingService.java:90/94/106/125/159/164 · 호출 ChatStreamServiceV2:236 | **부분수정** — 무가드 히로인 역참조 6곳 + 보상 구간 직전 배치. D-2.a 수정으로 핵심 동반 해결 |
| D-6.2 | P2 | ONE_LINE | ChatStreamService.java:592 (대조군 :361) | 지켜보기 ASSISTANT 저장이 persister 우회 |
| D-6.3 | P2 | ONE_LINE | ChatStreamService.java:717 | 시간 넘기기 동일 |
| D-6.7 | P2 | MEDIUM | ChatLogPersister.java:74-95 · ChatLogDeadletter.java:30 | 데드레터 싱크가 같은 Mongo — 최소안(payload 로그 출력)조차 미적용 |
| D-2.l | P2 | SMALL | UgcPipelineWorker.java:467-489 (복귀 :481) | 유료 리롤 실패 시 revertToReady 경로에 환불 없음(failed 경로와 비대칭) |
| F-8.a | P2 | SMALL | ChatStreamService.java:487-494 (sendSseError :1148-1156) | InsufficientEnergyException이 UNEXPECTED_ERROR로 삼켜짐 |
| F-8.b | P2 | SMALL | ChatStreamServiceV2.java:283-286 · 오프닝 :376-379 | V2 동일 — **FE(ChatPageV2:1774-1776)는 올바른 코드를 기다리는데 BE가 안 보냄** |
| E-4.17.b | P2 | ONE_LINE | ChatStreamService.java:1463-1466 (V1 STORY 배제 :180-184) | 참이 될 수 없는 죽은 게이트 — 정책 게이트판(:302-305)과 불일치 |
| E-4.15 | P2 | MEDIUM | BackgroundGenerationService.java:300-316·249-267·286·373-388 | 실패 시 앵커 캐시 행 미생성 → 웹훅이 통째 스킵. 배경 폴링·웹훅 라우팅 무게이트 |
| E-4.5.a | P2 | ONE_LINE | TheaterState.java:553-586 · TheaterSaveLoadService:216-218 | restoreFromSnapshot이 sessionStatus를 복원·리셋하지 않음 |
| E-4.6 | P2 | ONE_LINE | StoryV2Service.java:808-809 (우회된 API MemoryService:168-176) | cascadeResetRoom이 리포지토리 직행 — Redis 메모리 캐시(TTL 2h) 미무효화 |
| E-4.11 | P2 | ONE_LINE | TheaterModelResolver.java:111 (실사용 TheaterEndingService:326-327) | `resolveEndingModel` 사문(호출처 0건) — 무거운 지시가 저비용 모델에 |
| E-4.13 | P2 | SMALL | service/story/OffscreenNotificationService.java:157-159·190-191 | 토스트·카운트가 readAt만 봄. 올바른 3조건 필터가 :146-149에 방치 |
| E-2.15 | P2 | SMALL | SceneRenderService.java:239-255 · SceneRequestService:182 | **부분수정** — 페르소나 성별이 유저 액터에 미배선. **씬 일러는 게이트 비의존 라이브 트랙** |
| E-1.4 | P2 | SMALL | ChatPageV2.jsx:620/2676/2775 (배선 :3357-3358) | abortController 미전달 3곳. V2 실도달면은 자동응답 1곳 |
| E-1.4b | P2 | SMALL | ChatPage.jsx:473/1832/1931 (:209 · :2515-2516) | V1 사본 3곳 — SANDBOX 방 전부에서 3지점 배선 → **E-1.4보다 급하다** |
| E-1.5 | P2 | ONE_LINE | ChatPageV2.jsx:1703-1714 (대조군 :787) | 메시지 인라인 빌더에 parentLogId 누락 → 멀티씬 턴 삭제 시 마지막 씬만 사라짐 |
| E-1.6a | P2 | ONE_LINE | ChatPageV2.jsx:1840-1855 (:1846) | 액션 빌더가 role 2축(NPC 미판정) |
| E-1.6b | P2 | ONE_LINE | ChatPageV2.jsx:1962-1978 (:1969) | 오프닝 빌더 동일 — 모든 V2 방 첫 턴이 타는 최대 도달률 |
| E-1.6c | P2 | ONE_LINE | ChatPageV2.jsx:1845-1855 · :1968-1978 | 액션·오프닝에 emotionTag/outfit 키 부재 → 리플레이 재현 불가 |
| E-1.6d | P2 | ONE_LINE | 동일 두 블록 (소비처 :2926) | 같은 두 블록에 parentLogId도 부재 |
| E-1.7 | P2 | SMALL | ChatPage.jsx:673-704 (deps :704 ↔ effect deps :1038) | 스테일 클로저 — 첫 진입 시 히로인 대사가 전부 NPC role |
| E-1.9 | P2 | MEDIUM | sceneReplay.js:34-42·51-62 · ChatPage.jsx:242-253 | 리플레이 3단 배선에 location/time/bgmMode 전부 누락 |
| E-1.10a | P2 | ONE_LINE | ChatPageV2.jsx:1651 (가드 :1635, 대조군 :2675-2676) | 낙관적 차감 `-2` 하드코딩 — 부스트·구독 미반영 |
| E-1.10b | P2 | ONE_LINE | ChatPageV2.jsx:1795-1905 | 액션 경로에 setEnergy·에너지 가드 0건, onError 롤백 없음 |
| E-1.13b | P2 | SMALL | LobbyShell.jsx:325-336 · TheaterPortalPage.jsx:592-606 (소비 LucidStore:706) | onRequestAdultVerify 미전달. **`bm.secret-products-enabled` 토글을 켜는 순간 라이브화** |
| E-6.1.a | P2 | ONE_LINE | AdminUgcReviewService.java:248-267 | 인스펙션이 성별 없는 오버로드 호출 — 남캐 프롬프트 오표시 |
| E-6.2 | P2 | SMALL | SupportTicketService.java:104-108 | status·type 동시 필터 시 type이 조용히 버려짐 |
| E-6.3.a | P2 | SMALL | AdminAuditController.java:39-49 | actor·action else-if 사슬 — 동시 조건 무시 |
| E-6.5 | P2 | SMALL | UserDetailPage.jsx:95-101·399-416 · AdminChatLogController:27-33 | 로그 100건 하드코딩 + ASC 고정 — '가장 오래된 100건'만 보임 |
| E-7.1.b | P2 | SMALL | OAuth2LoginSuccessHandler.java:58-119 | onAuthenticationSuccess 전체에 try/catch 없음 → 톰캣 원시 500 |
| E-5.3.b | P2 | MEDIUM | AdminUgcReviewService.java:65-77 · UgcWorldService:397-436 | 월드 재검수 리셋이 실효 없음(큐가 world.reviewStatus 미조회). **E-5.2.b만 닫혀 정책 비대칭 발생 — 우회면** |
| C-0.3 | P2 | SMALL | GlobalExceptionHandler.java:20/52/64/75-79 | @ExceptionHandler 4건뿐 — 역직렬화 실패가 400이 아닌 500 |
| C-1.3 | P2 | ONE_LINE | application.yml:110-113 | **부분수정** — 코드 가드 완비, NICE 자격증명 3종 환경변수 주입 미완(행정) |
| C-1.4 | P2 | ONE_LINE | application.yml:114 | `yourdomain.com` 플레이스홀더. C-1.5가 닫혀 이제 단독 수정 가능 |
| B-11.2 | P2 | SMALL | AuthController.java:118-122 · ApiRateLimiter:147-148·101-105 | 계정 단위 버킷 부재 + fail-open — 분산 IP 크리덴셜 스터핑 무제한 |
| B-6.2 | P2 | SMALL | TheaterLobbyService.java:522-533 | 리롤 횟수 카운터 부재(구간 제한만). `rerollCount` 전 코드베이스 0건 |
| B-5.2 | P2 | SMALL | TheaterService.java:69-70·303-333 · application.yml:210 | **부분수정** — 워터마크 완비, 기본 fail-open. ⚠D-5.6 수정 시 즉시 활성 착취면 |
| D-5.1 | P2 | SMALL | TheaterService.java:238-243 · TheaterBatchGenerator:318 | **부분수정** — 유료배치 덮어쓰기는 차단. 저장 키 오프바이원 잔존(prefetch 히트율 0%) |
| F-1.a | P2 | ONE_LINE | TheaterCreateFlow.jsx:741 | "최대 40 P" 표기가 BE 정본(20)과 2배 괴리 |
| F-6 | P2 | SMALL | ModerationPage.jsx:86 | blockedAtStep 2단 라벨 — BE는 3·4단계도 적재 |

### P3 (35건)

| ID | 심각도 | 규모 | 현재 좌표 | 요약 |
|---|---|---|---|---|
| E-3.④.9 | P3 | SMALL | Character.java:485 · application-characters.yml:1152 | **부분수정(감사 채택)** — `ooc-example: ""`가 blank 가드 밖 → CharacterPromptAssembler:151에 빈 문자열 주입(게이트 없는 V1 경로) |
| E-3.④.5 | P3 | ONE_LINE | yml:841-842 (폴백 Character:510-517) | **부분수정** — 증상 소멸, 캐릭터 고유 대사 부재 잔여 |
| E-3.④.6 | P3 | ONE_LINE | yml:952-953 | **부분수정** — 동일 |
| E-3.④.7 | P3 | ONE_LINE | yml:1067-1068 | **부분수정** — 동일 |
| E-3.④.8 | P3 | ONE_LINE | yml:1188-1189 | **부분수정** — 동일 |
| E-3.②.13 | P3 | SMALL | WorldRoutingService.java:196/255/269-305 | **부분수정** — LLM 디렉터 movement 키가 무검증 수용(유일 잔여 유입구) |
| E-3.②.14 | P3 | SMALL | StoryDirectorPromptAssemblerV2.java:832-840 · StoryV2Service:863·868 | raw location key 노출 + orElse 로그 부재 |
| E-3.③.2 | P3 | SMALL | WorldSeeder.java:88/102/117 | **부분수정** — ChatRoom 폴백은 수정. 시더 BgmMode 검증 미도입 |
| D-5.2 | P3 | ONE_LINE | TheaterService.java:231-244 · TheaterBatchGenerator:318-319 | **부분수정** — 증상 차단, 근본 키 정합 미이행 |
| D-5.3 | P3 | SMALL | TheaterService.java:336-357 | 소비 배치 ID 대조 가드 부재(회귀 탐지 수단 없음) |
| D-5.4 | P3 | SMALL | TheaterBatchGenerator.java:297·459-495·90-115 | prefetch 시 씬로그 이중 기록 방지 미도입 |
| D-5.5 | P3 | SMALL | TheaterBatchGenerator.java:330-340·379 | **부분수정** — 일러 레그 게이트됨. 배경(Fal.ai) 레그·조건부 호출 미이행 |
| D-2.m | P3 | SMALL | UgcWorldPipelineWorker.java:262-289 (복귀 :284) | 월드 트랙 동일 결함(D-2.l과 한 커밋 권고) |
| E-7.2 | P3 | SMALL | StudioCreateFlow.jsx:1768-1782 | **부분수정** — BE 400 완비. FE textarea maxLength·글자수 카운터 부재 |
| C-2.c | P3 | ONE_LINE | GlobalExceptionHandler.java:20/52/64/75 | **부분수정** — FE 소멸. HttpMessageNotReadableException→400 미이행(실체는 C-0.3) |
| E-1.11a | P3 | SMALL | ChatPageV2.jsx:1759·1889·2003 (소비 :3228) | **부분수정** — 매퍼는 배선됨. SSE 재조회 3곳에 setCharacterStats 없어 스탯 동결 |
| E-1.3 | P3 | SMALL | src/api/UseChatStream.js:54-75·88-112·122-147 | director peek/consume/request가 raw fetch + 401 무처리. **E-1.2b의 공용 구현으로 비용 급락** |
| E-1.14 | P3 | ONE_LINE | SupportPanel.jsx:430-435·368 · HelpButton.jsx:55-56·22 | 알림 탭 5단 연쇄로 열자마자 qna 탭으로 튐 |
| E-2.15b | P3 | SMALL | ChatStreamService.java:459-465 · application.yml:217 | 씬 일러 AUTO 경로 성별 미전달. `trigger: manual`이라 현재 휴면 |
| E-4.5.b | P3 | SMALL | TheaterSaveLoadService.java:268-281·200-218 | majorBranchDoneInChapter가 직렬화조차 안 됨 |
| E-4.8 | P3 | ONE_LINE | StoryV2Service.java:482-490 (↔ UGC :563) | 공식 경로에 isHidden 검사 누락 — API 직접 호출 우회면 |
| E-5.1.a | P3 | ONE_LINE | ChatStreamService.java:1360-1364 (유일 moderate :199) | 승계 경로에 모더레이션 미통과. 노브 off라 착취면 0 |
| E-6.1.b | P3 | SMALL | AdminUgcReviewService.java:270 · UgcWorkflowFactory:250 | templateNegative에 성별 오버로드 부재 |
| E-6.3.b | P3 | ONE_LINE | AdminAuditController.java:44 | 같은 사슬 3번째 가지 — **E-6.3.a 수정 시 자동 해소** |
| B-8.2 | P3 | ONE_LINE | GlobalExceptionHandler.java:20/52/64/75 · JwtTokenService:132/140 | IllegalArgumentException→400 핸들러 부재(절 밖 잔존) |
| B-9.3 | P3 | SMALL | ApiRateLimiter.java:108-172 · TheaterFinalityController:50-57 | 엔딩 버킷 부재. B-9.10 락이 방당 LLM 1회 상한 |
| B-9.6 | P3 | SMALL | ChatPage.jsx:1240-1265 · ChatPageV2.jsx:1554-1579 | 4xx 제외 없는 3회 재시도 — 400 3연타 + 실패 토스트 |
| F-7 | P3 | SMALL | TheaterInterventionService.java:119-124 | 화자 ID는 현재 히로인, 이름은 방 lead로 갈림 |
| F-8.c | P3 | SMALL | ChatStreamService.java:600-603·783-786·1556-1559 | 보조 3경로도 402 미전달 |
| F-8.d | P3 | SMALL | ChatPage.jsx:1624 · ChatPageV2.jsx:2470 | `error.status === 402` 사문 분기(SSE 페이로드에 status 없음) |
| F-1.b | P3 | ONE_LINE | TheaterCreateFlow.jsx:720 (대조 :66-72·:801) | 스탯 축 라벨 3종 불일치 |
| F-1.c | P3 | SMALL | TheaterLobbyService.java:91-93·76·80·657 · SubscriptionType:15 | 코드 500/100 ↔ 주석·FE 전부 40/20. **코드 수정이 아니라 정본 결정 대기** |
| F-2 | P3 | ONE_LINE | TheaterIntermissionPage.jsx:105-107 | GREAT_SUCCESS에 sfx 분기 없음 — 최고 등급 결과 무음 |
| F-3.c | P3 | SMALL | ChatPage.jsx:1629-1636 · ChatPageV2.jsx:2475-2482 | 에러 폴백 narrationMap 공식 4인 하드코딩 2곳 |
| F-5 | P3 | SMALL | EasterEggEffects.jsx:197·111·333 | `Airi.exe` 하드코딩 — characterSlug 미전달 |

### 착수 레버리지 — 수정 3건이 잔존 9건을 닫는다

| 수정 | 닫히는 행 |
|---|---|
| `saveAssistantLog` 시그니처 교체 (호출부 3곳) | D-6.2 · D-6.3 · D-6.4 · **D-6.5** |
| D-2.a 3상태 분기(charged/rollbackCtx/committed) | D-2.a · D-2.n 핵심 · F-8.b 절반 |
| `sendSseFailure` 공통 헬퍼 1개 (catch 4곳) | F-8.a · F-8.b · F-8.c |
| `buildHistoryEntries` 공용 빌더로 3중 복제 치환 | E-1.5 · E-1.6a · E-1.6b · E-1.6c · E-1.6d |
| E-6.3.a @Query 단일화 | E-6.3.a · **E-6.3.b 자동 해소** |

### 순서 의존 — 표에 없던 위험

- **D-5.6 → B-5.2**: B-5.2의 무과금 착취 경로가 지금 안 도는 유일한 이유가 D-5.6(prefetch `@Async`에 `@Transactional` 부재)의 우연한 실패다. 코드 주석(TheaterService.java:225-227)이 스스로 "그 우연에 과금 정합을 걸어 두지 않는다"고 적었다. **D-5.6을 먼저 고치면 B-5.2의 fail-open이 즉시 활성 착취면이 된다.**
- **E-7.1.a + E-7.1.b는 반드시 한 커밋.** 정책(동일 이메일 다른 provider) 확정 전에도 "email UNIQUE에 무방비 INSERT하지 않는다" + try/catch 최소 안전판만 넣으면 500은 즉시 막힌다.
- **E-1.13b는 시크릿 상품 오픈과 같은 릴리즈에 묶어라.** `bm.secret-products-enabled`는 `legacy.*` 게이트가 아니라 롤아웃 토글이다.

---

## §4. UNVERIFIED

**상태 컬럼 기준 UNVERIFIED는 0건이다.** 178행 전부 현재 소스를 직접 읽어 판정했다. 다만 아래는 **코드로는 판정할 수 없어 상태에 반영하지 않은** 잔여 확인 항목이다.

| 항목 | 관련 행 | 무엇을 실측해야 하는가 |
|---|---|---|
| Vultr `.env` 환경변수 주입 | C-1.3 · C-1.4 · C-2.l | `ssh -i ~/.ssh/lucid_deploy root@141.164.37.146 "docker exec lucid-app printenv \| grep -E 'NICE_\|PORTONE_'"` — 코드 가드는 이미 있어 미주입 시 명시적으로 실패한다 |
| 댕글링 히로인 참조 존재 여부 | D-2.n | `SELECT h.id FROM chat_room_heroines h LEFT JOIN characters c ON c.id=h.character_id WHERE c.id IS NULL;` — 1행 이상이면 **P1 승격** |
| prefetch 런타임 실패 로그 | D-5.2~5.5 심각도 근거 | `🎭 [PREFETCH] Failed` 관측. 프로드 극장 플레이 0건이라 현재 불가. ※ 심각도 강등은 워터마크 가드 **단독으로도** 성립하므로 이 항목이 뒤집혀도 판정은 유지된다 |
| 리버스 프록시 경유 전제 | B-11.1(닫힘) | `ClientIpResolver`의 XFF 최우측 신뢰는 '모든 트래픽이 프록시 경유'가 전제. 탈AWS 이후 Caddy·방화벽 기준으로 재확인 필요 — 코드 결함은 아님 |
| Vercel 환경변수명 | C-2.h(닫힘) | 구현은 **`VITE_PORTONE_MERCHANT_CODE`**인데 레지스터 수정안은 `VITE_PORTONE_IMP_CODE`였다. docs/18 시트가 후자면 **결제가 조용히 비활성**된다(portone.js:38이 warn만 남기고 false 반환) |

---

## §5. 이 재대조의 한계

### 표본 감사가 실제로 본 것

| 감사 | 대상 | 표본 | 발견 |
|---|---|---|---|
| 감사 1 | 수정됨·소멸·MOOT·게이트차단 쪽 (89건) | **26건 (~29%)** | 틀린 판정 **1건** — E-3.④.9 |
| 감사 2 | 잔존·부분수정 쪽 (89건) | **50건+ (~56%)** | 틀린 판정 **0건**. 근거 문장 오류 1건(C-2.c의 grep '0건' → 실제 주석 1건) |

**감사 방향의 비대칭이 의도적이다.** 오탐 비용이 큰 쪽은 "잔존인데 수정됨이라 적는 것"(살아 있는 결함을 닫힌 것으로 믿음)이므로 닫힘 판정을 감사 1이 집중 검증했고, "수정됐는데 잔존으로 남기는 것"(이미 고친 것을 다시 파는 비용)을 감사 2가 더 넓게 훑었다. 감사 2에서 **'사실은 이미 고쳐졌는데 잔존으로 남은 것'이 0건**이었다는 점이 §3 착수 목록의 신뢰도를 뒷받침한다.

### 신뢰 구간

- **§3의 P1 10건은 가장 강하다.** 전부 감사 2가 개별 실측으로 재확인했다(E-4.4의 `if` 분기 전수, D-6.5의 `grep saveAssistantLog` = 정의 1 + 호출 3, E-7.1.a의 `existsByEmail` 0건, B-6.1의 `consumeEnergy` 0건, E-1.1의 `finally` 위치 등).
- **§2의 닫힘 판정 중 미감사 63건**은 각 묶음의 단일 관측에만 근거한다. 다만 그 대부분이 **동일 커밋으로 일괄 종료된 덩어리**(E-3.①/② 27행 = `6758b0e`, C-2 10행 = `7813b1d`, E-2.x 9행 = `66bb880`)라 검증 단위는 63건이 아니라 사실상 **커밋 6~7개**이고, 각 덩어리의 대표 행이 감사를 통과했다.
- **가장 약한 고리는 심각도 재평가다.** 상태(잔존/수정됨)는 코드로 이진 판정되지만, 심각도 하향(B-9.3 P2→P3, D-5.1 P1→P2, D-5.3/5.4 P1→P3, E-3.④.5~8 P2→P3)은 **도달성 추론**에 근거한다. 특히 D-5.x 4건의 강등은 "워터마크 가드 + D-5.6의 우연한 실패"라는 두 조건 위에 서 있고, 그중 후자는 §4대로 런타임 미관측이다. 상태는 믿어도 되지만 **이 4건의 심각도는 D-5.6 착수 시 재평가하라.**

### 이 문서가 대체하지 않는 것

- 표의 **비고란 서술 다수가 여전히 낡았다.** 특히 즉시 정정이 필요한 4건: ① B-9.9의 '미해결 블로커'(MongoConfig에 `domain.ending` 있음) ② D-5.5의 '살아 있는 GPU 지출 경로'(`66bb880` 이전 관측) ③ D-2.k의 '게이트 뒤가 아니다'(현재 legacy 참조 3건) ④ F-8.d의 'PREMIUM_REQUIRED가 enum에 없다'(ErrorCode.java:87에 존재).
- **`docs/17_assets/defect_register.md` 본문은 손대지 않았다.** 최소 23개 섹션(E-3.①/② 계열)이 아직 "🔴 잔존"이다 — 두 문서가 다시 어긋나 있다.
- **좌표 드리프트가 전면적이다.** `ChatStreamService.java`만 +30줄 안팎 이동했고, BE도 예외가 아니다(`AdminUgcReviewService` +13, `TheaterBranchService.applyBranchChoice` 304→539). §3의 좌표는 `a6f4ec6` 기준 실측값이지만, **다음 커밋 이후에는 다시 심볼 grep을 기본으로 삼아라.**
- **경로 표기 오류 3건**(레지스터/델타 공통): `domain/chat/Location.java` → 실제 **`domain/enums/Location.java`** · `global/exception/GlobalExceptionHandler.java` → 실제 **`exception/`** · `OffscreenNotificationService`는 **`service/story/`**. 첫 번째는 grep이 "파일 없음"을 반환해 오판의 씨앗이 된다.