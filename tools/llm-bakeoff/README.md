# Lucid Chat 로컬 프롬프트·모델 비교실

> **2026-09-28 후속:** Gemini 3 Flash 복귀와 커밋·푸시·프로덕션 적용 상태는 [릴리스 보고서](../../docs/28_assets/prompt-release-2026-09-28.md)를 우선한다. 아래 9/22 상태와 실험 결과는 당시 기록이다.

> **2026-09-22 현재 상태:** `master ca27320`의 로컬 정합 수리와 최종 수용 확인 68회를 마쳤다. 이벤트 속마음 충돌의 국소 수리 뒤 4회 모두 계약 통과, Java 31·Node 107·13후보 smoke·확장 5fixture self-test·bootJar 성공. 최신 결과와 한계는 [P1 수용 보고서](../../docs/28_assets/p1-acceptance-2026-09-22.md)를 따른다. **비교실 UI는 일반 로제타 단일 fixture**, 최종 수용시험 전용 CLI는 별도 5개 확장 fixture를 지원한다. 자유 모드 지연 증가·잔여 의미 문제는 남으며 서비스 배포를 뜻하지 않는다.

2026-09-18 구현·확장. 자유(V1)·스토리(V2)의 수리 전/후 프롬프트와 실험 후보를 같은 입력으로 비교한다. 기본은 **Gemini 3 Flash의 P0/P1/J2 비교**이며 기존 모델 비교도 남아 있다. P0는 보존한 수리 전 system 메시지, P1은 현재 서비스 assembler 출력이다. [최종 분석·수렴](../../docs/28_assets/prompt-evaluation-final-2026-09-18.md)을 따른다. 운영 Spring 서버·DB·FE를 켤 필요가 없다. Node 22.16+ 또는24와 프로젝트 Java/Gradle 환경을 사용하며 npm 설치는 필요 없다.

## 실행

저장소 루트의 PowerShell에서:

```powershell
# 이 PC에서 JAVA_HOME이 비어 있으면 사용
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-21.0.1.12-hotspot'
.\gradlew.bat prepareBakeoff bakeoffSmoke
node tools/llm-bakeoff/server.mjs
```

[비교실 열기](http://127.0.0.1:18767). 127.0.0.1에만 바인딩하며 `http://localhost:18767/`로 들어오면 위 주소로 연결한다. 다른 화면에서 클릭한 링크의 첫 진입도 허용한다. Java 수정 후 prepareBakeoff를 다시 실행한다. 빌드 당시와 현재 소스 hash가 다르면 시작을 거부한다.

키가 없으면 프롬프트 미리보기와 공개 후보 목록만 사용할 수 있다. `.env.example`을 `.env.local`로 복사하고 **로컬 파일에** `OPENROUTER_API_KEY`를 입력한 후 서버를 다시 시작한다. 기존 환경변수가 파일보다 우선한다. 키는 서버에만 보관하고 `.env.local`과 `.local/`은 Git에서 제외한다.

- `OPENROUTER_API_KEY`: 서버 전용 키.
- `BAKEOFF_PORT`: 기본 18767.
- `BAKEOFF_BUDGET_USD`: 기본 $5, 최대 $100. `.local/budget.json`의 누적 한도이며 서버 재시작 시 초기화되지 않는다.

## 사용

1. 자유/스토리와 비교 종류를 선택한다. 기본 프롬프트 비교는 Gemini 3 Flash로 고정하고 provider·reasoning을 세 후보에 공통 적용한다. 기본은 **P0 수리 전 / P1 현재 서비스 / J2 실험 후보**이며 총13후보와 같은 버전 중복 선택을 지원한다. P1의 실제 대사 품질 상승을 입증한 것은 아니다. 모델 비교를 선택하면 기존 **Gemini 3 Flash / Aion 3.0 Mini / Gemini 3.5 Flash-Lite**를 포함한 목록이 나타나고 모두 역사적 P0를 사용한다. Google은 `google-ai-studio`, Aion은 `aion-labs` 우선이며 추론은 provider 기본값이다.
2. ‘호출 없이 프롬프트 보기’로 실제 messages와 순서를 확인한다. P1은 현재 assembler, 이전 후보는 고정 system snapshot을 쓰며 실제 private history builder를 공유한다.
3. 비교 실행 시 endpoint를 다시 조회하고 가격·출력 한도·지원 옵션을 검사한다. 프롬프트 비교에서는 한 endpoint 관측과 같은 모델/실행 조건을 사용한다. 세 호출의 예산을 모두 확보한 후 시작 순서를 무작위화해 병렬 실행한다. 자동 재시도와 provider fallback은 없다.
4. 무작위 A/B/C 대사를 보고 승/무/패, 한국어·캐릭터성·맥락·서사/선택권 점수(1–5), 메모를 작성한다. 0은 미평가다. 평가는 매 턴 단위다. 다음 실행·새 대화·모드 변경·답변 채택 전에 현재 평가를 자동 저장하며, 저장 실패 시 전환을 중단한다. 마지막 턴을 마칠 때는 ‘이번 턴 평가 저장’을 누른다.
5. 평가 저장 후 모델·프롬프트 버전·지표를 공개한다. 블라인드 평가 사본은 이후 수정된 평가와 별도로 보존한다.
6. 완료·기본 계약 검증에 통과한 답변 하나를 채택하면 다음 비교의 공통 history가 된다. 중복 채택은 차단한다. 모드 변경/새 대화는 history만 초기화한다.
7. JSON 내려받기로 입력·프롬프트·조건·원문·정제 결과·usage·평가를 저장한다. 서버에도 `.local/<run-id>.json`으로 저장된다. 화면은 최근 실행만 다루며 과거 결과는 파일로 연다.

입력 작성이 번거로우면 **입력 주제 → 입력 제안**을 사용한다. 로제타 평가용 로컬 예문 32개를 농담·도발·부탁/거절·칭찬·감정·맥락·행동 묘사·스토리 사건으로 나눴다. ‘골고루 제안’은 주제를 순환하고 이미 제안/채택한 같은 예문은 가능한 한 피한다. 맥락 예문은 답변을 1회 이상 채택한 뒤, 사건 예문은 스토리 모드에서 선택할 수 있다. 클릭하면 현재 입력을 대체하며 수정 가능하다. API 호출·자동 전송은 없고, 모델이 대화 내용을 분석해 새로 생성하는 기능은 아니므로 실제 흐름에 맞게 조정한다. 평가 힌트는 화면에만 표시하며 전송 프롬프트에 추가하지 않는다.

## 프롬프트 구조 실험의 첫 단계

초기에는 운영 assembler를 수정하지 않고 bakeoff 경로에서 후보를 변환했다. 마지막 실험 뒤 운영 정합 수리를 적용하면서 이전12후보를 고정 system snapshot으로 보존하고 P1만 현재 assembler에 연결했다. snapshot은 로제타·일반·고정 상태 전용이며 fixture/profile, 후보용 외형·복장·OOC·ID, role/cache 형태가 바뀌면 중단한다. 실제 user/assistant 기록을 snapshot으로 바꾸지 않는다. 역사적 후보의 원문 치환도 검증을 유지한다.

| 버전 | 비교 목적 | 권장 비교 |
|---|---|---|
| P0-service-fixed-state-v1 | 2026-09-18 수리 전 서비스 system snapshot | P0/P0/P0로 생성 변동 확인 가능 |
| C1-repairs-v1 | 외형·행동/대사 충돌·V2 중복 등 결함 수리 | P0 대 C1 |
| S1-character-v1 | C1 기반 로제타 동기·관계·표현 연결 | C1 대 S1 |
| E1-examples-v1 | S1 기반 한국어 행동 예시 | S1 대 E1 |
| F2-appearance-v1 | P0의 공식 외형·복장 전달만 수리 | P0 대 F2 |
| A2-facts-v1 | F2의 사실·서버 상태와 사용자 주장 구별 | F2 대 A2 |
| U2-agency-v1 | F2의 행동/대사·사용자 행동 경계 | F2 대 U2 |
| T2-turn-v1 | F2의 현재 의도·맥락 적용 | F2 대 T2 |
| J2-json-v1 | F2의 유효 JSON 예시·필드 설명 분리 | F2 대 J2 |
| R2-combined-v1 | F2 + A2/U2/T2/J2 | F2 대 R2 |
| D3-turn-boundary-v1 | R2의 기존 씬 구성 지시를 현재 턴 경계 중심으로 재작성 | R2 대 D3 |
| K4-current-context-v1 | J2에 현재 입력·최근 사용자 원문 강조 | J2 대 K4, 최종 미채택 |
| P1-service-repairs-v1 | 정합 수리를 반영한 현재 서비스 assembler | P0 대 P1 대표 수용시험 수행. 대사 품질의 뚜렷한 향상은 입증하지 못했으며 자유 모드 지연 증가 관측 |

프롬프트 미리보기는 선택한 세 후보의 전송본·변경 요약·hash를 보여준다. 이때 후보 번호는 설정 순서이며 블라인드 A/B/C와의 매핑은 공개 전 표시하지 않는다. 결과에는 각 후보의 실제 request/messages·promptVersion/hash·같은 원본 상태/기록에서 파생됐음을 식별하는 snapshotHash가 함께 저장된다. history·fixture·출력 한도 등이 달라지는 후보, 서로 다른 model/provider/reasoning을 섞은 프롬프트 비교는 과금 예약 전에 거부한다.

[평가 카드](../../docs/28_assets/character-evaluation-cards.md)는 로제타 등 4캐릭터의 제작 의도 검토용 초안이다. **UI의 실행 fixture**는 로제타 일반 모드·첫 만남만 지원한다. 아래 9/22 전용 AcceptanceBridge/CLI의5종 확장과 구분한다. [개발 사례](../../docs/28_assets/development-cases.json) 12개는 새 대화에서 시작하는 독립 상황이며, UI에서 선택하면 입력을 준비할 뿐 자동 호출하지 않는다. 평가 힌트는 전송하지 않는다. 프롬프트에 들어가는 예시와 개발 사례는 구분한다. 후속 평가에서는 별도 작성자가 후보 고정 전에 새12사례를 봉인하고 고정 뒤 개봉했다. 초기 계획과 실제 작성 시점의 차이는 후속 보고서에 기록했다.

후보의 실제 모델 비교는 아래 실험 보고서에 기록했다. 창작 구조 후보는 채택하지 않았고 정합 수리만 P1에 적용했다. **UI 비교실**은 시크릿/다중 캐릭터·관계 전이·장기 기억을 검증하지 않는다. 9/22 CLI의 시크릿 확인도 비노골적 성인 대화의8스탯 분기이며 콘텐츠 한계 시험이 아니다. 현재 병렬 비교는 캐시를 강제로 cold/warm 분리하지 않는다. 무작위 시작과 usage 기록이 캐시 편향을 없앴다는 뜻은 아니므로 지연/비용은 관측 조건과 함께 해석한다.

## 범위와 해석

- 공식 캐릭터 **로제타(20)**의 `application-characters.yml` / `slug: rosetta`를 Spring Binder + `Character.applySeed`로 직접 읽는다. 성격·말투·말버릇·배경·약점·관계별 행동 지침·난이도를 유지하고, 연결된 판타지 아카데미 세계와 V2 장소 8개도 원본 시드에서 읽는다. 운영 DB의 현재 레코드를 조회한 것은 아니다.
- 유저는 합성 페르소나 지우(21, 성인 아카데미 편입생). 처음 만남(STRANGER), 스탯 0, 점심시간으로 고정한다. 자유 모드는 공식 기본 장소 TERRACE, 스토리는 GARDEN_OF_ACADEMY에서 시작하며 공식 인트로·첫인사가 초기 history에 들어간다.
- 화면의 ‘캐릭터 설정 · 평가 기준’에서 설정과 지침 원문을 펼쳐 볼 수 있다. fixture ID는 `official-rosetta-stranger-v1`이며 같은 프로필을 결과 JSON에도 저장한다. 이전 서윤 결과 파일은 보존하되 캐릭터/초기 관계가 다른 결과끼리 직접 합산하지 않는다.
- 대화만 이어지며 관계·스탯·장소·시간·기억·이벤트는 고정된다. 게임 상태 전이, 요약 생성, 장기 기억 품질 재현은 후속 범위다.
- 최신 20 **로그**를 서비스 builder에 넘긴다. V1은 첫 로그가 assistant일 때 `(입장)` user를 추가하므로 API 대화 메시지는 21개가 될 수 있다. 현재 입력은 한 번만 전달한다.
- 자유 모드의 활성 프롬프트는 여러 씬을 허용한다. 미사용 `buildSandboxOutputFormat`의 옛 1씬 규칙을 강제하지 않는다.
- Spring Jackson builder와 production DTO, 최종 화자 접두 정제에 production DialogueSanitizer를 사용한다. 원문과 wrapper 제거 후 JSON을 따로 보존한다.
- 기본 계약 검사는 씬 존재, 문자열 대사/지문, 감정 enum, 유저 화자 침범, normal stat 5종·범위, V2 씬 개수/인물 ID/topic_concluded를 다룬다. 전체 JSON Schema와 모든 장소·이벤트·메모리 의미를 검증하는 것은 아니다.
- content TTFT, 첫 완성 씬 객체 TTFS, 스트림 수신 완료, 로컬 검증 포함 총 시간을 구분한다. 첫 씬은 임시 원문이다. 실제 서비스 FE 표시 시각은 측정하지 않는다.
- 최종 `usage.cost`와 token/reasoning/cache 상세를 보존한다. 누락은 추정값으로 채우지 않는다. 완료 없는 부분 usage, 취소, timeout, 전송 오류는 비용 미확정으로 처리한다.
- provider context 전체 × 입력 상한 $1/M + 출력 한도 × $5/M을 예약한다. 실비보다 보수적이다. 비용 불명 항목은 재시작해도 예약을 유지한다. 추후 generation ID로 실제 청구를 확인한 후 정산해야 한다. 중단은 원격 과금 중지를 보장하지 않는다.
- exact model/provider/tier, reasoning, 생략 sampling 옵션, endpoint 단가·context·지원 옵션·조회 시각, Git SHA, Java/실험 도구 hash, prompt hash를 남긴다. API가 실제 provider/tier를 주지 않으면 미제공으로 남긴다.
- 모델명 가리기는 평가용 UI 기능이다. 개발자 도구까지 정보를 숨기는 보안 경계는 아니다.

## 검증

아래 관측 횟수와 UI 기록은 각9/18 단계의 이력이다. 9/22 최종 확인은 맨 아래 절과 최신 수용 보고서를 따른다.

```powershell
.\gradlew.bat prepareBakeoff bakeoffSmoke bootJar
node --test tools/llm-bakeoff/test/*.test.mjs
```

Java self-test: 두 모드 조립 결정성·최신 입력 1회·메시지 순서·20로그/V1 합성 입장·정상/비정상 감정과 스탯·repository 읽기 외 호출 금지.

Node 31개: 기존 Windows argfile/UTF-8, fixture·mapper·정제·멀티씬/history, SSE/usage, provider/가격·예산·실패·인증·평가 보존 검사에 프롬프트별 실제 요청/공통 snapshot, A/A 반복, 다른 실행 조건·상태·출력 한도·초과 입력 거부, 후보/평가사례 API 보호, 수정된 평가 입력의 출처 구분을 추가했다.

외부 추론 없이 화면을 검증하려면:

```powershell
node tools/llm-bakeoff/test/ui-server.mjs
```

18768 포트의 **오프라인 UI 샘플**이다. 실제 Java/화면/runner를 사용하고 catalog/upstream만 수동 샘플로 대체한다. 결과는 `build/bakeoff/ui-test-results`에 별도 저장한다. 이 서버의 대사·시간·비용은 모델 성능 자료가 아니다.

관측(9/18 최초 구현): 로컬 앱 시작, 공개 카탈로그, 무료 프롬프트 확인, 자유·스토리 샘플 비교, 평가/공개/채택, 더블클릭 1회 채택, 다음 history, 390px 반응형을 브라우저에서 확인했다. JSON 다운로드 파일을 열어 3개 결과·블라인드 평가·OFFLINE 표기를 확인했다(내장 브라우저의 download 이벤트는 timeout이었으나 실제 파일은 정상 생성). 이후 사용자가 설정한 API 키가 인식됐으며, 로제타 교체 후에는 두 모드의 무료 프롬프트·프로필 화면을 다시 확인했다. 이번 교체 검증에서 유료 추론·실제 모델 품질/지연시간·운영 통합 테스트·DB·결제·이미지 경로는 실행하지 않았다.

독립 코드 검토를 반영했다. 운영 `src/main`은 수정하지 않았으며 bakeoff sourceSet은 bootJar에 포함되지 않는다. 분석과 후속 방향은 [docs/27](../../docs/27_LLM_Bakeoff_Prompt_Audit.md)을 따른다.

관측(9/18 프롬프트 비교 확장): Java smoke와 Node31개 통과. P0 messages 수정 전후 바이트 일치, 모든 후보의 초기/20로그 계약 보존 확인. 오프라인 브라우저 자유·스토리 비교/공개/채택/평가 자동 저장 및 파일 내용 대조, 기존 모델 비교 미리보기, 390px 가로 넘침 수정·재검증 완료. 기존 로컬 결과/예산 JSON16개 hash 보존. 18767 실서버 재시작 후 실제 키 인식·기본P0/C1/S1·E1 무료 미리보기 확인. 외부 모델 호출·운영 반영은 하지 않았다. [실제 문구 대조](../../docs/28_assets/prompt-draft-comparison.md), [최신 구현 상태](../../docs/28_System_Prompt_Architecture_Plan.md#14-첫-실험-후보와-평가-자료-구현--2026-09-18)를 참조한다.

## 9/18 직접 API 프롬프트 실험

[분석 보고서](../../docs/28_assets/prompt-evaluation-2026-09-18.md)에 148회·$0.52754835 결과를 기록했다. 기존 후보를 수정하지 않고 단발·새 상황·균형 반복·자체 history·공통 history 진단을 실행했다. 익명 품질 검토와 독립 과금 대조 완료. Node42검사 통과. 제작자 검수·운영 채택은 별도다.

experiment.mjs는 이번 승인된 세션 prompt-eval-20260918의 직렬 runner다. 기존 서버와 같은 .local/server.lock을 사용하므로 서버를 중지한 상태에서만 실행한다. 전역 예산과 추가 세션 $3를 함께 적용하며 같은 세션을 재실행해도 한도가 초기화되지 않는다. --run-paid 없는 단발 계획 실행은 무료 조립 확인이다. 새 다중 턴 계획의 무료 실행은 아직 없는 이전 결과를 자동 생성하지 않는다. 이미 완료된 job은 재호출하지 않는다.

비용 누락·저장 실패·알 수 없는 결과에서는 예약을 유지하고 중지한다. 확정 비용의 계약 실패도 일단 중지하며, 원문 hash와 양쪽 원장의 확정 금액을 확인한 reviewed-invalid-jobs.json 항목만 재개 시 건너뛴다. 실패를 정상으로 고치거나 다시 뽑는 기능이 아니다. 현재 runner는 새로운 실험 세션을 자동 생성하는 범용 도구가 아니므로 기존 manifest의 소스 고정 조건을 임의 갱신하지 않는다.

원본은 .local/experiments/prompt-eval-20260918에 보존했다. experiment-review.mjs는 익명 패킷, experiment-statistics.mjs는 비용/형식/지연 집계, experiment-judgements.mjs는 익명 평가의 후보 연결, experiment-export.mjs는 별도의 읽기용 HTML/JSON을 만든다. 이 후처리들은 모델을 호출하지 않는다. HTML 장면 표시는 엄격 JSON 오류 시 기존 parser의 추출 장면/첫 객체를 사용하되 오류 표시를 보존한다. 전체 raw와 요청은 .local 원본에서 확인한다.

이번에 strict JSON 원문 실패18건을 현행 Java 검사와 구분해 발견했다. 대부분은 Java가 첫 객체를 허용해 ok로 판정한다. 실제 운영 실패율과 동일시하지 않는다. 그 밖에 허용되지 않은 감정값 2개를 발견했으며 두 유형의 합집합은19응답이다. 전체 JSON 문자열/enum 검증과 출력 예시의 개선은 다음 작업이다.


## 9/18 후속 개선·구조 비교

[후속 분석](../../docs/28_assets/prompt-evaluation-round2-2026-09-18.md)에 7개 추가 후보와 156회 실측을 기록했다. 단일 축·봉인 새 사례·선정 반복128회와 R2/D3 구조 비교28회다. 추가 $0.62136327, 이전148회를 포함한 원래 $3 승인 누적 $1.14891162다. 모델 비교에 쓴 기존42호출은 이 프롬프트 실험 합계와 구분한다.

비교실 기본 선택은 P0/F2/J2이며 총11후보를 쓸 수 있다. R2/D3는 기존 씬 구성 지시를 바꾼 구조 비교용이다. [Round2 실제 문구](../../docs/28_assets/prompt-round2-comparison.md), [D3 실제 문구](../../docs/28_assets/prompt-round3-comparison.md)를 참조한다. 이 실험으로 운영 프롬프트가 변경되거나 최종 후보가 채택된 것은 아니다. 기본 계약을 통과했어도 전체 raw JSON 파싱이 실패하면 화면에 별도 오류 표시가 나온다. 표시만 추가했고 기존 채택/검증 게이트를 바꾸지는 않았다.

experiment-round2.mjs와 experiment-round3.mjs는 각각 기존 비용·원장 hash를 고정한 전용 runner다. 별도 파일에 저장하더라도 같은 원래 $3에서 이전 실비를 차감한다. 기존 전용 runner의 manifest를 현재 소스에 맞춰 덮어쓰지 않는다. 새 후보 등록 뒤 이전 유료 runner가 source drift로 거부하는 것은 의도한 동작이다. 읽기용 review/statistics/judgements/export 후처리는 모델을 호출하지 않으며 비용 집계는 세션 runId로 구분한다.

검증: Node75개 및 Java11후보 smoke 통과, 기존10후보×2모드×history0/2/20 총60건의 메시지 bytes 동일, D3 6건 구획 역치환 및 양 모드 JSON 예시 검증 통과. 과금 경계 독립 검토와 이전288증거파일 hash 대조 완료. 실서버18767 재시작 후 키 인식·11후보·양 모드12건 무료 미리보기·예산 파일 불변 확인. UI 신규 오류 표시는 실제 정상 J2/오류 F2 raw로 호출 없이 검증했다. 이번에는 브라우저 시각·반응형 재검사는 하지 않았다. 운영 src/main·DB·배포 변경 없음.


## 9/18 마지막 실험 종료·운영 정합 수리

[최종 정본](../../docs/28_assets/prompt-evaluation-final-2026-09-18.md). P0/J2/K4 72회 추가, $0.32467311666666676. 프롬프트 실험 누적376회/$1.4735847333333338, 최초$3 내 미확정0. K4의 대사 품질 우위는 불충분하고 지연 증가가 있어 미채택. 두 운영 assembler에 외형·행동 해석·중복·사용자 내면 충돌·유효 JSON 정합 수리만 적용했다. P1은 다중 인물용 중립 예시여서 실험 J2와 동일하지 않다.

experiment-final.mjs는 이전304회 비용을 차감한 마지막 전용 runner다. 이전 runner들의 source/plan 동결 기록을 보존했으며, 운영 변경 후 hash 불일치는 정상이다. 이를 고쳐 같은 실험을 다시 실행하지 않는다. experiment-final-review/statistics/judgements/export.mjs는 호출 없는 후처리다. 원본은 .local/experiments/prompt-eval-final-20260918에 보존했다.

검증: Java 계약23·13후보 smoke·Node93·bootJar 격리 성공. 독립 검토에서 과거12후보 행렬72개와 최종 실제72요청, 총144개 전송본 바이트 동일 확인. 이전319증거파일·원장 보존 및 출처/캐시/후보사실 변조 거부 확인. 서버18767 재시작, 키 인식·13후보·P0/P1/J2 양 모드6개 무료 미리보기·예산 파일 불변 확인. 새 브라우저 시각 검사·운영 DB·배포는 미실행.

## 9/22 최종 P1 수용 확인

[최신 수용 보고서](../../docs/28_assets/p1-acceptance-2026-09-22.md)와 [동결 계획](../../docs/28_assets/p1-acceptance-2026-09-22/plan.md)을 따른다. `experiment-p1-acceptance.mjs`는 기존 프롬프트 376회 비용을 차감한 **같은 원래 $3** 내에서 대표 P0/P1 비교·확장·연속 대화·지연 재확인 64회를 실행한 전용 runner다. 과거 세션의 manifest를 갱신하지 않는다. 종료된 실험을 다시 뽑거나 실패를 성공할 때까지 반복하는 도구가 아니다.

`AcceptanceBridge`의 고정 fixture는 `claire-free-normal`, `rosetta-story-opening`, `rosetta-sierra-story-multi`, `rosetta-free-event`, `rosetta-free-secret-benign`의 5개다. `profiles`, `fixture`, `prepare`, `validate`와 무료 `--self-test`를 제공한다. 실제 assembler/private history builder/DTO/sanitizer와 공식 성인 캐릭터 시드를 사용하지만 DB·인증·결제·상태 변경·FE 통합을 실행하지 않는다. 오프닝은 사용자 입력을 실행 라벨로만 보존하고 실제 API에는 서비스의 system cue를 보낸다. V1 화자 null, 5/8스탯, topic_concluded Boolean과 오프닝·이벤트 분기를 검사한다. 제 3자 NPC가 없는 고정 fixture의 화자 경계와 서비스 DTO 자체의 허용 범위를 구분한다.

64회 중 이벤트 1건에서 inner_thought=null 계약 위반을 보존·정산 확인한 뒤, 이벤트 때 일반 속마음 생성 블록을 제외하는 국소 수리를 했다. 비이벤트 62개 요청 bytes 동일·이벤트 2개 해당 블록만 제거를 확인했다. `experiment-p1-event-repair.mjs`의 수리 후 이벤트 4회 모두 null/ONGOING/0스탯 등 계약을 통과했고 이전 실패 원본과 계획은 보존했다. 최종 Java 계약 31개·Node 107개·13후보 smoke·확장 5fixture self-test·bootJar 성공. 추가 68회(P0 24회 + P1 44회)/$0.2375572833, 프롬프트 누적 444회/$1.7111420167·원래 $3 잔액 $1.2888579833이다.

목소리 품질의 큰 개선은 입증하지 못했다. 자유 TTFS 중앙값은 초기 P0 2.80초/P1 4.34초, 지연 재확인 3.36초/3.85초로 증가했다. 첫 완성 씬 객체까지의 API 관측이며 실제 FE 표시 시간이 아니다. 장기 기억·다음 턴 서버 상태·UGC·제작자 선호와 실제 배포는 별도 범위다. 키/실험 원본/원장은 공유 `.gitignore` 및 기존 로컬 제외 규칙으로 보호한다.

마감 시 최신 sourceHash `114c7ce08bcc85313552f0e8a8478acf9448e828c8736aec984ba133195a6ece`로 18767 서버를 재기동하고 HTTP 부트스트랩·상태·두 모드 P0/P1 Java 무료 미리보기를 확인했다. 추가 유료 호출 없이 원장 불변을 확인했다. [서버 확인 기록](../../docs/28_assets/p1-acceptance-2026-09-22/local-server-check.json), [최종 검사](../../docs/28_assets/p1-acceptance-2026-09-22/final-verification.json), [독립 비용 감사](../../docs/28_assets/p1-acceptance-2026-09-22/independent-billing-audit.json). 원장 미확정 예약은 0이며, 이 기록은 이후 서버가 계속 실행 중임을 보장하는 상태 감시가 아니다.
