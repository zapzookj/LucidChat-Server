# LLM 베이크오프와 프롬프트 구조 검토

> **2026-09-28 후속:** Gemini 3 Flash 복귀와 커밋·푸시·프로덕션 적용 상태는 [릴리스 보고서](28_assets/prompt-release-2026-09-28.md)를 우선한다. 아래 실험 결과와 미배포 표기는 각 기록 당시의 사실이다.

최초 조사: 2026-09-17 · 최종 P1 확인: **2026-09-22**. Gemini 3 Flash 유지·프롬프트 탐색을 종료하고 **최종 수용 확인 68회와 로컬 정합 수리를 마쳤다.** 이번 턴 시작 때 `master ca27320`과 9/18 assembler·bakeoff 빌드·공유 Git 제외 수리가 복원된 상태를 확인했다. 이 세션이 checkout이나 복구 패치를 적용한 것은 아니다. P0/P1 비교와 확장 경로 확인 64회 뒤 이벤트 속마음 충돌을 국소 수리했고 이벤트 4회 재확인 모두 계약을 통과했다. 프롬프트 누적 444회/$1.7111420167, 원래 $3 잔액 $1.2888579833. 최신 정본은 [P1 수용 보고서](28_assets/p1-acceptance-2026-09-22.md)와 [구조 계획 §19](28_System_Prompt_Architecture_Plan.md#19-최종-p1-수용-확인과-마감--2026-09-22)다. 앞선 감사의 미통합·미검증 상태와 아래 9/18 기록은 해당 시점의 관측으로 보존한다. 자유 모드 지연 증가와 의미 제약의 잔여 사례는 남으며, 커밋·푸시·배포는 하지 않았다.

## 1. 결론과 범위

모델을 고를 비교 환경을 먼저 만들고, **현재 프롬프트 고정 → 같은 모델에서 프롬프트 변경 → 조합 재검증** 순서로 진행한다. 모델과 프롬프트를 동시에 바꾸면 품질 변화의 원인을 분리할 수 없다. 최신이라는 이유만으로 Gemini 3 Flash를 교체하지 않는다.

사용자 기준: 텍스트 1M 토큰당 입력 $1 / 출력 $5 이하, 낮은 체감 지연, 자연스러운 한국어·캐릭터성·맥락·서사. 모드별 콘텐츠 호환성도 별도 평가한다. 최종 채택은 사용자의 블라인드 대사 평가와 서비스 측정 결과로 결정한다.

이번 자료는 정상적인 한국어 대화·비노골적 서사와 API/서비스 구조를 다룬다. 공급자의 콘텐츠 정책, 모델 거절, API 필터는 서로 다른 항목이며 `is_moderated=false`가 무제한 생성이나 정책상 허용을 보증하지 않는다. 본 조사에서는 성적 콘텐츠 생성이나 필터 우회 실험을 수행하지 않았다.

조사 시작 시 checkout: BE `master`, HEAD `ca27320`. 구현은 여기서 분기한 `codex/llm-bakeoff-lab`에 미커밋 상태로 진행했다. 작업 전 미추적 `docs/23_UIUX_Design_Study.md`, `docs/23_assets/`를 보존했다. FE `LucidChat-Front/LucidChat-Front`는 `master...origin/master`, 깨끗함을 확인했다. 9/11 인계의 FE checkout 설명은 현재와 다르다. 운영 서버 설정·DB·실제 배포 버전은 미조회.

근거: Inbox 두 노트, Codex 인계 - LucidChat, 저장소 현재 소스, docs/26 §9, docs/19_assets/decisions_confirmed, 아래 공식 문서·공개 API. 사용자 과거 실험 결과와 이번 관측을 구분한다.

## 2. 사용자 사전 테스트 기록

2026-09-17 대화에서 전달받은 관찰. OpenRouter Chat 환경이며 프롬프트·provider·tier·추론 설정·표본 수는 미확인. 단가는 사용자 당시 기록으로 보존한다.

| 모델 | 당시 입력/출력 $/M | 사용자 관찰 | 이번 처리 제안 |
|---|---:|---|---|
| Gemini 3 Flash | 0.5 / 3 | 무난한 기준 | 모든 라운드의 기준 |
| Gemini 3.8 Flash | 0.75 / 3.75 | 조금 나은 품질, 성인 상황극 거절 관찰 | 일반 대화 우선, 할인 이후 원가 검토 |
| HY4 Preview | 0.834 / 2.5 | 살짝 어색, 가격 이점 부족 | 후순위 |
| GPT 5.6 Luna | 0.2 / 1.2 | 짧은 답변, 저렴, 콘텐츠 호환 미확인 | 실제 장면 규격으로 우선 비교 |
| DeepSeek V4 Flash | 0.05 / 0.16 | 품질 낮음, 저렴, 성인 상황극 응답 관찰 | 원본 재시험 후순위, V4.1은 별도 후보 |
| GLM 5.3 Flash | 0.07 / 0.2 | 애매 | 후순위 |
| Solar Pro 4 | 0.03 / 0.12 | 애매 | 한국어 모델이라는 이유만으로 승격하지 않음 |
| Kimi K2.6 | 0.56 / 3.39 | 애매 | 후순위 |
| Qwen3.8 Flash | 0.15 / 0.47 | 평가 내용 미기재 | 예비 후보 |
| MiMo V2.5 | 0.12 / 0.24 | 한자 혼입, 성인 상황극 거절, 탈락 | 우선 재시험 제외 |

## 3. 후보와 비용

아래는 2026-09-17 OpenRouter 공개 모델 카탈로그 관측값. 모델 단위 표시 가격이며 endpoint/tier별 실제 가격과 다를 수 있다. 같은 문자열의 재조회값도 시점에 따라 바뀔 수 있으므로 [카탈로그 스냅샷](27_assets/model-catalog-2026-09-17.json)을 함께 저장했다. 가격 단위는 USD / 1M input, output tokens. 세금·충전 수수료·환율 제외.

| 순서 | 정확한 OpenRouter ID | 입력 / 출력 | 비교 이유와 한계 |
|---|---|---:|---|
| 기준 | `google/gemini-3-flash-preview` | 0.50 / 3.00 | 기존 서비스 기준. preview ID의 지속 제공은 별도 운영 확인 사항 |
| 1차 | `google/gemini-3.8-flash` | 0.75 / 3.75 | 사용자 체감 품질 개선. 현재 standard 할인 가격, 장기 원가 조건부 |
| 1차 | `openai/gpt-5.6-luna` | 0.20 / 1.20 | 비용 여유가 큼. 짧은 답변이 서비스 장면 지시 아래에서도 지속되는지 확인 |
| 2차 | `deepseek/deepseek-v4.1-flash` | 0.15 / 0.60 | 9/10 등록된 별도 버전. V4 Flash의 낮은 사용자 평가를 뒤집는지는 미확인 |
| 2차 | `minimax/minimax-m3` | 0.30 / 1.20 | 미시험 공급사·모델 추가. 한국어 캐릭터 대사 우수성은 입증되지 않음 |
| 예비 | `google/gemini-3.5-flash-lite` | 0.30 / 2.50 | Gemini 계열 비용 절감 경로. Flash보다 대사가 낫다는 근거는 없음 |
| 탐색 | `inception/mercury-2.5` | 0.04 / 0.15 | 낮은 지연을 겨냥한 탐색 1석. 80% 할인 표시, 정가 0.20 / 0.75도 상한 내. 한국어·서사는 미확인 |
| 별도 실험 | `minimax/minimax-m2-her` | 0.30 / 1.20 | 대화·역할극 특화. response_format 미지원, 공개 최대 출력 2,048토큰으로 현재 6,144/8,192 계약보다 작아 즉시 교체 후보에는 부적합 |

1차는 기준+3.8+Luna, 2차는 기준+V4.1+M3의 3개 탭이다. 1차 후보가 만족스러우면 탐색을 무한히 늘리지 않는다. 예비 후보는 1차/2차에서 개선이 부족할 때 추가한다. Qwen3.8은 사용자 평가가 더 들어오면 순서를 조정한다. M2-her가 M3보다 캐릭터에 적합하다는 것도 아직 가설이며, JSON 호환성은 별도로 통과해야 한다. M2-her 공개 페이지는 관측 당시 3일 availability 63.86%도 표시해 대사 성향 탐색과 운영 채택을 구분할 필요가 있다. 이 집계는 LucidChat의 오류율이 아니다.

다른 affordable 최신 모델(Step 3.7 Flash 등)도 공개 카탈로그에 있으나 코딩/에이전트 성능 광고만으로 한국어 대사 우위를 주장하지 않는다. 고가 플래그십·batch 가격·가변 latest alias를 기본 비교군에 섞지 않는다.

### Gemini 3.8 할인과 tier

[Google 공식 가격표](https://ai.google.dev/gemini-api/docs/pricing)의 standard는 **2026-12-31까지 0.75 / 3.75, 2027-01-01부터 1.50 / 7.50**다. 할인 종료 후 사용자의 상한을 넘는다. Flex는 현재 0.375 / 1.875, 이후 0.75 / 3.75로 상한 내이지만 지연·가용성 평가가 필요하다. Priority는 현재도 1.35 / 6.75로 상한을 넘는다. [OpenRouter 제공자 표](https://openrouter.ai/google/gemini-3.8-flash)와 [endpoint 원본](27_assets/gemini38-endpoints-2026-09-17.json)도 확인했다.

따라서 Inbox의 Flex→standard→priority는 자동 채택하지 않는다. 우선 standard에서 모델 품질을 비교하고, 후보 확정 후 tier를 별도 실험한다. 요청을 취소했다고 이미 처리된 입력·출력의 비용이 반드시 0이 되는 것도 아니다. 실측 시 전체 시도 비용을 합산한다. 가격 상한을 엄격히 적용한다면 Priority 폴백은 기본 경로에서 제외해야 한다.

### 공개 레이턴시를 해석하는 법

OpenRouter의 관측 당시 표에서 Gemini 3.8 AI Studio standard는 latency 2.01초 / 124 tps, AI Studio Flex 2.07초 / 181 tps, Vertex Flex 20.51초 / 38 tps로 표시됐다. DeepSeek V4.1의 DeepSeek endpoint는 0.94초 / 122 tps, Mercury 2.5는 0.62초 / 129 tps였다. 이 값은 다른 요청·길이·추론 설정이 섞인 제공자 P50 지표로 LucidChat 결과가 아니다. 논문/모델 홍보의 최대 tps와 서비스 표의 실제 집계 tps도 동일하지 않다.

필수 실측은 **클릭→첫 유효 씬 표시(TTFS)**와 전체 완료 p50/p95, timeout·실패율이다. 현재 BE TTFT는 첫 `delta.content` 기준이며 reasoning delta나 빈 이벤트는 제외한다. 제공자가 정의한 TTFT와 직접 같다고 가정하지 않는다.

비용은 단가 대신 `총 청구 비용 / 사용 가능한 응답 수`도 본다. 캐시·추론 토큰·실패/재시도·장면 수·한국어 토큰화 차이가 반영되어야 한다. 예를 들어 캐시/추론/재시도 없이 입력 8,000, 출력 1,200토큰이라면 Gemini 3은 $0.0076, 3.8 standard 할인 중은 $0.0105, Luna는 $0.00304다. 이는 설명용 가정이며 실제 서비스 토큰 수를 측정한 값이 아니다.

## 4. 현재 호출 구조와 비교를 방해하는 조건

| 경로 | 관측한 구조 | 의미 |
|---|---|---|
| 자유 V1 | `CharacterPromptAssembler` → `ChatStreamService` → `OpenRouterStreamClient` | 기본/boost 모델은 `BoostModeResolver`; Google 제공자 라우팅 강제 |
| 스토리 V2 | `StoryDirectorPromptAssemblerV2` → `ChatStreamServiceV2` → 같은 stream client | 세계/다중 캐릭터 상태와 별도 출력 계약. V1과 같은 Google 전용 전역 서킷 공유 |
| 극장 | `TheaterPromptAssembler` → `TheaterBatchGenerator` → `OpenRouterClient.completeJson` | `TheaterModelResolver`의 별도 모델 정책, 배치 완료 지연이 중요 |
| 요약·보조 생성 | `MemoryService`, `HeroineMemoryService`, 디렉터·엔딩·씬 디렉터 등 | model/sentimentModel/proModel/캐릭터별 설정 등 여러 선택 경로. 채팅 모델 교체와 동시 변경하지 않음 |

근거 좌표(HEAD ca27320): `ChatStreamService:978–990,1048–1080`, `ChatStreamServiceV2:604–651`, `LlmCircuitBreaker:71–88`, `OpenAiChatRequest:16–26`, `OpenRouterStreamClient:71–76,114–119,227–284`, `TheaterBatchGenerator:639–650`.

- **model ID 교체만으로 비-Google 비교 불가**: `provider.order=[google-ai-studio]`, `allow_fallbacks=false`; timeout 때 `google-vertex` 재시도. 공용 서킷은 다른 방·모델의 실패로 상태가 변한다.
- **기능 협상 부재**: 모든 모델에 temperature 0.8, frequency 0.3, presence 0.15를 쓰는 패턴. Luna의 현재 카탈로그에는 이 sampling 항목이 없으며 Gemini도 두 penalty는 미표시. 같은 숫자가 같은 작동을 보장하지 않는다. 지원 항목만 전송하고 unsupported/ignored 설정을 기록해야 한다.
- **추론 제어 부재**: 요청 DTO에 reasoning / service_tier가 없다. 모델 기본 thinking이 달라지면 품질·대기·비용을 동시에 바꾼다. 기본 동작 기준선과 최소/비활성 추론 실험을 구분한다.
- **측정 정보 손실**: StreamResult는 응답 문자열·TTFT·TTFS만 반환. 실제 provider/model, generation ID, token usage/cache/reasoning/cost, finish_reason/error를 결과로 보존하지 않는다. 전체 wall clock과 프론트 표시 시간도 별도로 필요하다.
- **응답 계약은 JSON mode**: `json_object`는 스키마 준수가 아니다. DTO의 `Map<String,String>`도 중첩 json_schema를 담기 어렵고 stream client는 응답 형식을 json_object로 재설정한다. 스키마 실험은 요청과 streaming 양쪽의 변경이 필요하다.

## 5. 프롬프트·컨텍스트 감사

### A. 우선 확인할 실제 불일치

| 발견 | 근거 | 영향 및 조치 제안 |
|---|---|---|
| 사용자 행동 처리 지시 충돌 | `CharacterPromptAssembler:131–145`는 별표 입력을 행동으로 설명, `:270` 부근은 user 메시지가 ALWAYS spoken words라 설명 | 행동을 대사로 읽을 여지. 한 규칙으로 정리하고 행동/대사 혼합 사례 비교 |
| V2 배경 중복 | `StoryDirectorPromptAssemblerV2:339,363–365`의 backstory가 Background와 Extended Backstory에 반복 | 의미 보존 제거 후보. 실행 프롬프트에서 문자·토큰 차이 확인 |
| V1 응답 맨 앞 reasoning | `CharacterPromptAssembler:841–845` 부근, `AiJsonOutput:25` | 유저에게 보이지 않는 분석 필드가 첫 씬보다 먼저 생성됨. 짧게 줄이거나 없애는 실험과 scenes-first 실험을 분리. 스탯 판단 품질도 같이 검사 |
| 죽은 자동 씬 일러 지시 잔여 | `CharacterPromptAssembler:873–879`, docs/26:356 | `generate_illustration` 등 불필요 필드 후보. 살아 있는 수동 씬 렌더/속마음용 hint 소비처까지 확인 후 축소; 이름만 보고 일괄 삭제하지 않음 |
| 세계·인물 기억 무제한 결합 | `MemoryService:71–85,202–208`, V2 assembler `:540–570` | 실제 벡터 검색 RAG가 아니라 전체 요약 주입. 대화가 길수록 비용·충돌 증가 가능. 기존 사실+관계+진행 중 사건+최근 회차로 예산을 배분하는 방식 검토 |
| 최근 20턴이 아니라 20 로그 | 두 서비스의 findTop20, MemoryService:112 | 사용자/응답/이벤트/숨김 로그가 창을 공유. 실제 보존 턴 수가 달라짐. turn ID와 요약 완료 범위를 기준으로 자르는 구조 권고 |
| V2 요약 주기 단위 불일치 | `ChatStreamServiceV2:221,326,1307–1329` | 전송 전 전체 로그 수+1을 currentTurnCount로 써서 %10 검사. 로그 형태에 따라 의도한 10 사용자 턴과 다르게 발동 |
| V2 인물별 delta 미누적 | 같은 파일 `:1324–1329`, `HeroineMemoryService:99–115` | 주기 시점의 `List.of(e.getValue())`만 압축. 중간 턴 delta가 해당 인물 누적 기억으로 전달되지 않음 |

V2 주기 문제의 정적 계산: 오프닝 ASSISTANT 1개 후 USER/ASSISTANT가 쌍으로 늘면 5번째 사용자 요청마다 검사값이 10의 배수가 된다. 초기 로그가 짝수이고 이후 쌍만 늘면 검사값은 계속 홀수라 이 조건에 도달하지 않는다. 실제 초기화/세이브/액션 흐름과 DB 분포를 재현하기 전 서비스 전체에서 항상 발생한다고 단정하지 않는다. **말투 변화의 원인으로 확정된 것은 아니다.**

V1도 매 10 USER마다 최근 20 로그를 요약하므로 SYSTEM 로그가 끼면 마지막 10 사용자 턴의 일부가 요약 대상에서 빠질 수 있다. 요약 실패 후 재시도·누락 범위·중복 처리도 coverage 기준으로 검증해야 한다. 채팅 화면에서 보이는 내용과 저장된 raw/clean/scenesJson, 다음 프롬프트에서 재구성되는 화자 이름을 모두 대조한다.

### B. 구조 개선 실험

현재 static/dynamic 분리는 이미 존재한다. 새 프레임워크를 넣는 것보다 실제로 전송되는 블록의 책임·순서·양을 정리하는 것이 먼저다.

1. **정체성 / 언어 습관 / 현재 말투 상태 분리**: 캐릭터의 고정 습관과 현재 상대에 대한 존대·호칭을 분리. 친밀도가 오른다고 자동 반말로 바뀌지 않도록 전환 근거를 표현한다. 모든 캐릭터에 한 가지 말투를 강요하는 규칙은 피한다.
2. **고정 prefix 안정화**: 제품 규칙·출력 계약 → 캐릭터/세계 설정 → 기억·현재 상태 → 최근 대화/현재 입력. 단 이것을 절대 정답으로 취급하지 않고 V1/V2 각각 순서만 바꾼 A/B를 한다. V1 static에도 이벤트 여부/관계에 따른 값이 들어 있어 이름만 static인 부분이 있다.
3. **실제 예시 소수**: 자연스러운 한국어 존대·농담·행동 해석의 짧은 예시를 캐릭터 유형에 맞춰 추가하는 실험. 밈 목록을 고정 주입하거나 장황한 금지문을 반복하지 않는다.
4. **스키마·의미 검증 분리**: native json_schema 지원 후보에서 실험하되 파싱 성공과 실제 speaker/ID/감정 enum/스탯 범위/유저 대사 대필 여부를 별도로 평가. scenes-first 유지 여부와 TTFS를 확인한다.
5. **기억은 범위 추적 후 압축**: 마지막 요약 turn ID, 원문 포함 범위, 누락/중복을 보존. 단기 원문+누적 사실+미해결 사건을 예산 안에 넣는다. 벡터 DB 신규 도입은 관련 기억 선택이 실제 병목으로 확인될 때 검토한다.
6. **캐시는 실측으로 판단**: OpenAiMessage는 문자열 content와 message-level cache_control을 전송한다. 공식 예시는 provider별 형식·동작이 다르고 Gemini는 implicit caching도 있다. 현재 marker가 효과 있다고도, 캐시가 전혀 없다고도 단정하지 말고 usage.cached_tokens와 warm/cold 재요청으로 확인한다.

[OpenRouter cache 문서](https://openrouter.ai/docs/guides/best-practices/prompt-caching)와 [provider parameter 지원 문서](https://openrouter.ai/docs/guides/routing/provider-selection)를 참고한다. prompt cache는 누적 대화 기억을 대신하지 않는다. 프롬프트 파일 바이트 수는 실행 입력 토큰 수가 아니므로 이번 조사에서 토큰 절감률을 제시하지 않는다.

## 6. 실험 환경 제안

검토용 [Baking Room 목업](27_assets/bakeoff-lab-mockup.html). API 미연결이며 예시 대사는 사람이 작성한 UI 샘플이다. 어떠한 수치도 실측 결과로 표시하지 않는다. 신규 화면 구현은 사용자가 제공한 AGENTS.md의 디자인 승인 후 진행한다. 로컬 서버 실행이 도구 정책으로 거부되고 브라우저 file URL도 차단되어 실제 렌더·클릭·모바일 시각 검증은 미실행이다. 해당 차단을 우회하지 않았으며 HTML/JavaScript 정적 확인만 수행한다.

### 실행 경계

- 로컬/스테이징 전용 실험 runner. 기존 sendMessage endpoint를 세 번 호출하지 않는다. 해당 경로는 에너지 차감, 로그, 스탯, 기억, 배경 생성, 비동기 작업을 바꾼다.
- 합성 데이터로 시작. 실서비스 대표 캐릭터·세계 설정과 동일 조립기를 쓰되, 대화/상태는 실험용 불변 snapshot으로 준비한다. 운영 대화 무단 복제나 외부 전송 없음.
- `ConversationSnapshot -> PromptCompiler -> PreparedRequest -> GenerationRunner -> ExistingParser + ContractValidator -> ExperimentResult` 형태로 분리. 초기에 기존 assembler와 새 compiler의 출력이 같은지 snapshot/hash로 확인한다.
- assembler 내부 repository 조회와 history private builder가 얽혀 있으므로 단순 wrapper만으로 순수 함수가 되지 않는다. context loader와 compiler 분리 범위를 작게 정하고, 운영 호출과 fixture 생성이 같은 직렬화 경로를 공유하도록 한다.
- API 키는 서버 환경에서만 사용. 브라우저·결과 JSON·Git에 키를 넣지 않는다. 실험 사용자 접근 제한, 별도 결과 저장소, 호출 중단, 최대 동시 3개, 요청당 토큰 상한·실험 전체 예산을 둔다.
- 후보별 정확한 ID/provider endpoint/tier, supported parameters, reasoning, max_tokens, temperature 적용 여부, prompt/schema 버전, Git SHA, snapshot hash를 결과에 기록한다. 실험은 자동 provider fallback을 끄고 실패도 표본으로 남긴다. 운영 폴백의 효과는 별도 라운드에서 측정한다.
- provider.max_price의 prompt/completion 단위는 $/M이다. 사용자 상한 1/5를 명시적으로 표현할 수 있다. 총 예산은 per-token cap만으로 제한되지 않으므로 별도 집계 필요.
- 개인정보 제거된 입력·출력만 로컬 결과에 보존하고, 실사용 데이터 반입 시 필요한 데이터만 검토한다. 독립 검토에서도 전역 서킷/운영 state와의 격리를 우선 권고했다.

### 비교 모드

1. **같은 턴 재생**: 동일한 history/state에서 후보 3개를 병렬 실행. A/B/C를 무작위 배정하고 모델명·가격·속도를 숨긴 채 먼저 대사를 평가한다. 같은 snapshot이라는 조건을 깨지 않도록 선택 결과를 자동으로 서로의 다음 입력에 섞지 않는다.
2. **선택한 답변으로 이어가기**: 한 답변을 실험 대화의 공통 다음 history로 채택한 뒤 다음 턴을 다시 비교. 어디까지 공통 history였는지 기록한다.
3. **모델별 장기 대화**: 별도 브랜치로 20~40턴 진행해 말투·기억·자기모순을 본다. 대화 경로가 달라지므로 단일 턴의 공정 비교 결과와 합산하지 않는다.

모형만 바꾸는 `P0 x M*`, 기준 모델에서 프롬프트 한 요소만 바꾸는 `P* x M0`, 최종 후보의 `P* x M*`를 구분한다. 처음부터 모든 조합을 실행하지 않는다.

### 평가 데이터와 지표

초기 12개 합성 상황: 일상 존대 / 반말 캐릭터 / 적당한 줄임말·밈 / 은근한 농담·반어 / 사용자 행동과 대사 혼합 / 감정 갈등 / 이전 약속 회상 / 호칭 유지 / 이벤트 진입 직후 / 다중 인물 화자 전환 / 장소·시간 변화 / 사용자 대사·의사결정 침범 방지. 현대/판타지, 공식/UGC, 자유/스토리를 분산한다. 스크리닝은 상황당 3회 정도로 시작하되 이것으로 p95나 우승을 확정하지 않는다.

자동 측정: 요청 시작→content TTFT→첫 유효 씬 TTFS→완료, FE 실제 표시 시각, 오류/빈 응답/finish_reason, 출력 잘림, JSON/schema/semantic 위반, 실제 input/output/reasoning/cache tokens, 모든 시도의 비용. raw API 결과와 파서가 보정한 결과를 함께 기록한다.

사람 평가: 블라인드 승/무/패와 한국어 자연스러움, 캐릭터성, 맥락·말투 일관성, 서사·유저 선택권. 한자·불필요 외국어 혼입, 기계적인 번역투, 반복 문형을 표기하되 고유명사/의도된 인용은 오탐에서 제외한다. 길이는 동일 장면 요구 아래 비교하고 긴 응답을 자동으로 고득점 처리하지 않는다. LLM judge는 보조이며 최종 한국어 품질 판단은 사용자가 한다.

장기 후보는 같은 사례의 paired preference와 여러 캐릭터에서의 반복 결과를 본다. 실패/거절/잘림도 분모에 포함한다. JSON 파싱 통과율만으로 서비스 적합성을 보증하지 않는다. 비노골적 허용 콘텐츠의 과잉 거절은 실패 유형으로 분류할 수 있으나 공급자 정책 위반을 유도하거나 회피하는 프롬프트는 만들지 않는다.

### 모드별 라우팅의 적용 조건

일반/시크릿 모드에 서로 다른 모델을 배정하는 기술 구조 자체는 가능하다. 다만 room flag만 보지 않고 서버가 계산한 effective mode와 권한을 받아 resolver가 판단해야 한다. 모델의 콘텐츠 정책과 제품 요구가 충돌하는지 먼저 확인하고, 거절을 계기로 자동으로 더 관대한 공급자에 재전송하는 방식은 이 설계에 넣지 않는다.

채팅 교체가 요약·보조 생성·극장 pro 경로까지 자동으로 바꾸지 않도록 use-case별 설정을 둔다. 같은 방에서 모드를 바꿀 때 말투·문체가 달라지는지 평가한다. 극장의 유저 체감 게임 규칙·스탯 체계·기능 범위는 기존 결정 G-1을 유지한다.

## 7. 실행 순서와 완료 기준

1. 분석 문서·목업 검토, 후보 라운드 확정.
2. 부작용 없는 snapshot/compiler/runner와 관측값 보존. 정상/실패/취소 상황에서 에너지·대화·스탯·기억·외부 이미지 호출 불변을 검증.
3. P0 프롬프트로 단기 모델 비교. 모델별 지원 설정만 다르게 적용하고 차이를 기록.
4. V2 메모리 범위/주기 문제를 재현. 실제 turn 경계·누락·중복·비동기 실패를 검증한 뒤 수리. baseline과 수리 이후 결과를 별도 표기.
5. 중복·충돌 제거, scenes-first, 기억 예산, 말투 상태를 한 요소씩 실험. 현재 제품 계약과 살아 있는 소비처를 보존.
6. 최종 후보의 장기 대화·실제 UI 표시·cold/warm·오류/폴백 비용·정가 조건 검증 후 채택. 점진 적용·rollback 설정을 준비하고 배포는 별도 작업으로 명시.

9/17 조사 시점의 미실행 항목은 프롬프트 실행본 추출·실험 서버 구현까지 포함했다. 9/18에는 §9 구현을 완료했다. 유료 모델 호출, 실제 생성 품질/거절/TTFS/비용 실측, 토큰 계산, 운영 DB 대조, 메모리 결함 재현, 정책/계약 적합성 확정, 프로덕션 모델 교체는 여전히 미실행이다.

## 8. 외부 근거

- [OpenRouter model API](https://openrouter.ai/api/v1/models): 모델 ID, 가격, 지원 파라미터. JSON snapshot은 27_assets에 저장.
- [Gemini 3.8 가격](https://ai.google.dev/gemini-api/docs/pricing): 할인 종료일과 tier 정가.
- [GPT-5.6 Luna](https://developers.openai.com/api/docs/models/gpt-5.6-luna): 0.20/1.20, none 포함 추론 effort. OpenRouter의 지원 파라미터는 별도로 확인.
- [DeepSeek V4.1](https://openrouter.ai/deepseek/deepseek-v4.1-flash), [MiniMax M3](https://openrouter.ai/minimax/minimax-m3), [M2-her](https://openrouter.ai/minimax/minimax-m2-her), [Gemini 3.5 Lite](https://openrouter.ai/google/gemini-3.5-flash-lite), [Mercury 2.5](https://openrouter.ai/inception/mercury-2.5): 후보별 사양·제공자 표.
- [Service tiers](https://openrouter.ai/docs/guides/features/service-tiers): flex/priority 라우팅 의미, 실제 응답 tier 기록.
- [Prompt caching](https://openrouter.ai/docs/guides/best-practices/prompt-caching): provider별 지원과 implicit cache.
- [Provider routing](https://openrouter.ai/docs/guides/routing/provider-selection): 지원 파라미터, 가격·데이터 정책 필터.

독립 검토: 별도 컨텍스트 에이전트가 과금·데이터 격리·routing·memory/history를 읽기 전용 검토했다. 핵심 메모리 좌표와 강제 Google 라우팅은 주 에이전트가 소스에서 재확인했다. 정적 검토는 동작 재현을 대신하지 않는다.

## 9. 로컬 비교실 구현 — 2026-09-18

사용자가 테스트 샌드박스의 단순 디자인을 승인하고 구현을 지시했다. [실행 설명](../tools/llm-bakeoff/README.md), [서버](../tools/llm-bakeoff/server.mjs), [runner](../tools/llm-bakeoff/engine.mjs), [Java bridge](../src/bakeoff/java/com/spring/aichat/bakeoff/PromptBridge.java)가 정본이다. 주소는 `http://127.0.0.1:18767`.

완료한 범위:

- 자유 V1·스토리 V2의 기존 assembler/private history builder를 합성 fixture에서 호출. Mockito repository의 읽기 호출만 허용하고 Spring context/DB/client를 시작하지 않는다. 새 bakeoff sourceSet은 운영 bootJar와 분리한다.
- P0 프롬프트 고정, 후보 3개 병렬 비교, 무작위 A/B/C, 사람 평가, 모델·지표 공개, 한 답변 공통 history 채택, 원본 JSON export. 단일 성인 합성 캐릭터이며 상태 전이는 고정이다.
- 7개 allowlist 후보, 공개 catalog의 최신 endpoint 필터링, 일반 tier 기본값, provider 고정·fallback/자동 retry 없음. 입력 $1/M·출력 $5/M cap과 누적 예산 예약을 별개로 적용한다.
- 비용 불명·취소·timeout은 예약을 유지. 실제 usage/cost, provider/model/tier, endpoint 단가·조회시각, 요청 조건, source/prompt hash를 저장한다. 미제공 값을 추정치로 숨기지 않는다.
- 키는 서버 전용, loopback/Host/Origin/임의 capability 검증, CORS 비허용, 응답은 텍스트 렌더링. 로컬 대화/평가/예산 파일과 키는 Git에서 제외한다.

독립 검토에서 Windows classpath 길이, 중복 채택 경쟁, Spring mapper 설정 차이, 최종 대사 정제 불일치, endpoint snapshot 누락을 발견해 수정했다. 실제 연결 테스트에서 Java stdout의 Windows 한글 인코딩 문제도 찾아 UTF-8로 명시했다. 후속 검토로 **자유 모드의 현재 활성 경로가 여러 씬을 허용함**을 재확인해, 미사용 경량 함수의 옛 1씬 규칙을 잘못 적용한 제한을 제거하고 정상 멀티씬 회귀 검증을 남겼다.

검증: `prepareBakeoff bakeoffSmoke bootJar` 성공, Node 20개 통과. bootJar를 열어 bakeoff 클래스/Mockito/spring-test가 없음을 확인했다. 실제 Java 연결로 두 모드의 프롬프트·history/mapper/정제를 확인했다. 브라우저에서 공개 카탈로그와 무료 프롬프트, 별도 오프라인 샘플 서버의 자유/스토리 비교→평가→공개→채택→다음 history를 확인했고, 더블클릭 채택은 1턴만 반영됐다. 390px에서 가로 넘침이 없었다. 샘플 생성 결과는 실제 모델 품질·속도·비용 근거가 아니다.

현재 이 셸에는 API 키가 없다. `.env.local` 설정·서버 재시작 후 사용자가 P0 실측을 시작할 수 있다. 아직 프롬프트 대체안 P1, 여러 캐릭터/세계 fixture, 모델별 장기 분기, 기억·상태 simulator는 구현하지 않았다. V2 기억 문제 수리와 운영 모델 교체도 이후 실험 결과에 따라 별도로 진행한다.

### 9/18 접속 오류 후속 수정

사용자가 `Local origin required`를 보고했다. GET /에서 `Sec-Fetch-Site: cross-site`를 무조건 차단해 외부 화면의 정상 링크 진입까지 거부하는 것을 재현했다. GET / + navigate + document + 사용자 활성(?1)인 첫 화면 진입만 예외로 허용하고, 정확한 localhost:port의 첫 진입은 127.0.0.1 주소로 연결한다. API·iframe·cross-origin fetch·악성 Host의 차단은 유지한다. 독립 읽기 검토와 정상/실패 입력 회귀 검증 2개를 추가했고 전체 Node 22개가 통과했다. 기존 서버를 교체해 적용했다. 브라우저에서 별도 localhost origin의 링크를 실제 클릭해 정상 진입했고, localhost 직접 접속의 정규 주소 연결도 확인했다. 재시작 시 사용자가 설정한 API 키가 인식됐음을 상태 화면으로 확인했다(값 출력·유료 추론 없음).

### 9/18 로제타로 실험 캐릭터 교체

사용자 요청: 불분명한 합성 캐릭터 대신 개성이 뚜렷한 서비스 캐릭터 로제타로 캐릭터성 유지·표현을 평가한다. [RosettaFixture.java](../src/bakeoff/java/com/spring/aichat/bakeoff/RosettaFixture.java)가 저장소의 일반 모드 `application-characters.yml`에서 slug `rosetta`를 Spring Binder로 읽고 production `Character.applySeed`를 적용한다. 연결된 `application-worlds.yml`과 `application-v2.yml`에서 판타지 아카데미와 활성 장소 8개를 읽는다. 운영 DB의 현행 값과 동기화 여부는 미조회다.

로제타(20)의 공식 성격·말투·배경·약점·관계별 지침·HARD 난이도와 인트로·첫인사를 사용한다. 실험용 유저 지우는 21세 성인 편입생으로 바꾸고, 첫 만남(STRANGER), 스탯 0, 점심시간, 자유 TERRACE / 스토리 GARDEN_OF_ACADEMY로 맞췄다. ID 101은 로컬 fixture ID이며 운영 PK가 아니다. 대화만 누적하고 게임 상태는 계속 고정한다. fixture ID `official-rosetta-stranger-v1` 및 설정 원문을 결과 metadata에 보존한다. 기존 서윤 실험 파일은 보존하며 서로 다른 fixture의 품질 점수를 직접 합산하지 않는다.

화면에 성격·말투·약점과 펼칠 수 있는 배경/관계 지침을 추가했다. 프로필 조회는 기존 Host/Origin/capability 검증 안의 `/api/fixture`를 사용하고 같은 Java 객체에서 표시와 프롬프트를 만든다. 평가 안내는 도발·자부심·환영 마법·숨겨진 내면과 초기 관계 경계에 초점을 둔다.

검증: prepareBakeoff/bakeoffSmoke, Node 23개 통과. 두 모드의 프롬프트에 공식 핵심 필드와 첫인사 포함, 장소 8개 전수 일치, 서윤/현대 서점 흔적 제거, 새 API 정상/차단 요청을 검사했다. 재시작한 실제 브라우저에서 프로필과 자유·스토리 무료 미리보기를 확인했다. 별도 컨텍스트 읽기 검토에서 추가 문제 없음. 이번 교체에서 유료 호출·운영 DB 조회·운영 코드 수정은 하지 않았다.

## 10. 사용자 실행 결과 분석 — 2026-09-18

[분석 정본](27_assets/bakeoff-results-2026-09-18.md). 사용자가 로제타 자유 모드에서 Gemini 3/3.8/Luna 5턴 및 별도 저가 후보 3종 2턴을 실행·평가했다. 저장된 실제 결과 21개를 확인했고 이번 분석에서 추가 유료 호출은 하지 않았다.

이번 대화에서 3.8은 기준 3보다 한국어/맥락 평가가 높았으나 캐릭터성 평균 차이는 0.2점이고, 첫 씬 중앙값은 5.079초 대 3.717초, 평균 실제 비용은 $0.009297 대 $0.005076였다. Luna는 $0.001532로 저렴했으나 캐릭터성·맥락이 낮았다. 캐시·기본 추론량이 다르며 단일 연속 대화 5턴이므로 운영 고정 증감률·통계적 우열로 확대하지 않는다. 현 단계에서 기준 모델 유지가 합리적이라는 분석상 제안이며 최종 채택 결정은 아니다.

프롬프트 측면에서 실제 V1 전송본에 외형 정보 누락(금발→붉은 머리 생성 후 채택 history로 전파), 프로필과 캐릭터 지식 경계 불명확, STRANGER/HARD와 칭찬 취약성·공통 예문 사이의 충돌을 확인했다. 첫 턴에만 cache marker가 들어가는 원본 history builder 조건도 관측했다. 세부 원인·후속 실험 분리는 분석 정본 참조. 운영 소스·모델·프롬프트는 변경하지 않았다.

## 11. 개선 진행 상태와 추가 후보 재조회 — 2026-09-18

사용자가 초기 지적의 수정 여부와 추가 베이크오프 가치를 질문했다. 현재 git diff와 소스를 다시 확인했다. **행동/대사 해석 충돌, V2 backstory 중복, 외형/인물 지식 경계, 관계 지침 충돌 모두 아직 미수정**이다. V2 기억 주기/delta 누적 문제도 정적 분석 뒤 동작 재현·수리가 남아 있다. 완료된 것은 기존 P0를 쓰는 로컬 비교실·측정·검증·공식 로제타 fixture·결과 분석이다. P0를 보존한 이유는 모델과 프롬프트를 동시에 바꿔 원인을 섞지 않기 위해서다. 향후 개선은 한 번에 교체하지 않고 변경 성격을 나눠 비교한다.

‘Gemini 3 유지’는 현재 표본에서의 잠정 판단이다. 사용자는 추가 모델 탐색 의사를 밝혔으며 최종 유지 확정·베이크오프 종료를 지시하지 않았다. 첫 후보군은 범용 최신 모델 위주였고, 역할극을 별도 목표로 삼는 Aion 3.0 Mini가 빠져 있었다. 다른 목적의 후보를 제한적으로 더 확인할 여지가 있다.

공개 모델/endpoint 재조회: [카탈로그](27_assets/model-catalog-2026-09-18.json), [endpoint snapshot](27_assets/extra-candidate-endpoints-2026-09-18.json). 아래 단가는 USD/1M input/output, 조회 당시 선택한 비배치 endpoint의 값이며 캐시 쓰기 등 부대 단가는 snapshot에서 별도 확인한다. 모든 4개 endpoint에서 response_format 지원과 현재 6,144/8,192보다 큰 출력 한도를 확인했으나, 실제 호출 성공·JSON 준수·한국어 품질은 미검증이다.

| 우선 | ID / 비교할 provider | 입력 / 출력 | 제안 이유와 한계 |
|---|---|---:|---|
| 우선 | `aion-labs/aion-3.0-mini` / `aion-labs` | 0.70 / 1.40 | DeepSeek 계열 기반 역할극·스토리텔링 시스템이라는 제공자 설명. 단일 Flash와 다른 접근을 시험할 이유. 한국어·첫 씬 지연은 미확인 |
| 우선 | `google/gemini-3.5-flash-lite` / `google-ai-studio` standard | 0.30 / 2.50 | 이미 lab allowlist에 있지만 실험 미실행. Gemini 말맛을 유지하며 비용/속도를 낮출 수 있는지 검증. 품질 우위라는 뜻은 아님 |
| 선택 | `anthropic/claude-haiku-4.5` / `anthropic` | 1.00 / 5.00 | 최신 세대 후보는 아니나 아직 비교하지 않은 Claude 계열. 상한 가격에서 일반 대화 품질이 달라지는지 확인. 비용 절감 후보는 아님 |
| 선택 | `stepfun/step-3.7-flash` / `stepfun/fp8` | 0.20 / 1.15 | 미시험 계열의 저가 비교석. 코딩·에이전트 홍보가 한국어 대사 우수성의 근거는 아님. 더 싼 DeepInfra endpoint는 이번 메타데이터에 response_format이 없어 위 provider를 제안 |

제안 라운드: 기준+ Aion + Gemini Lite, 이후 원하면 기준+Haiku+Step. 매 라운드에 기준을 남기고 도발 외 농담·부탁/거절·감정 상황을 섞는다. 이미 실패한 범용 저가 후보를 같은 조건으로 계속 늘리는 것은 후순위다. 기존 3.8은 추론 minimal 같은 설정을 따로 비교할 여지가 있지만 다른 모델 추가와 같은 실험으로 섞지 않는다.

OpenAI Docs로 Luna/상위 Terra도 재확인했다. Luna는 이미 시험했고 Terra의 일반 단가 2/12는 사용자 cap 초과이므로 추가 우선 후보로 제안하지 않는다. 이번에는 모델 추가·프롬프트 수정·유료 호출을 실행하지 않았다. Aion/Haiku/Step은 현재 lab allowlist에 없으므로 실제 라운드 실행 전 추가가 필요하다.

자료: [Aion 제공자 설명/가격](https://openrouter.ai/provider/aion-labs), [Gemini Lite 모델](https://ai.google.dev/gemini-api/docs/models/gemini-3.5-flash-lite), [Gemini Lite 제공자 가격](https://openrouter.ai/google/gemini-3.5-flash-lite), [Haiku 공식 설명/가격](https://www.anthropic.com/claude/haiku), [Step 제공자별 가격](https://openrouter.ai/stepfun/step-3.7-flash), [Terra 공식 모델/가격](https://developers.openai.com/api/docs/models/gpt-5.6-terra). 역할극/한국어 품질 우위를 자료만으로 확정하지 않았다.

## 12. 추가 라운드 등록·무료 입력 제안 — 2026-09-18

사용자 지시로 기본 3후보를 **Gemini 3 Flash / Aion 3.0 Mini / Gemini 3.5 Flash-Lite**로 바꿨다. 기존 후보는 유지하며 allowlist는 8개다. 공개 endpoint API에서 Google 두 모델의 `google-ai-studio` 일반 tier와 Aion의 `aion-labs` 활성 상태·가격·response_format·출력 한도를 재확인했다. 각각 0.5/3, 0.7/1.4, 0.3/2.5 USD/1M이며 이 provider를 우선 선택한다. 추론은 모두 기존 provider 기본값. 가격 상한·예산·실행 시 재조회·provider 고정 계약은 그대로다. Haiku/Step은 아직 미등록이다.

추가 사용자 요청으로 ‘입력 제안’을 구현했다. [suggestions.js](../tools/llm-bakeoff/public/suggestions.js)에 로제타 평가용 8주제×4개 예문을 두며 별도 추론 비용은 없다. 골고루 주제 순환/중복 회피, 채택 후 직전 대사 이어가기, 스토리 전용 사건 예문을 제공한다. 입력창에 넣은 뒤 수정·수동 실행하며 평가 힌트는 요청에 주입하지 않는다. history 내용의 의미를 분석하는 LLM 생성은 아니며 이 제한을 화면/README에 명시했다.

검증: 기존 Node 23개 통과, 새 기본 후보 3개의 실제 공개 endpoint를 이용한 요청 조립 확인(유료 추론 없음), 예문 32개/초기 모드 24개 중복 없음·모드/채택 조건 확인. 브라우저에서 후보/provider 기본 선택, 입력 제안과 주제 변경, 자유 모드 무료 프롬프트 조립, 스토리 전용 예문, 390px 가로 넘침 없음을 확인했다. 서버 적용 전 진행 중 호출 없음 확인 후 재시작했고 키 인식 정상. 기존 실험/분석/예산 JSON 10개는 SHA-256 전수 일치. 운영 src/main 변경·모델 추론 호출 없음.

별도 컨텍스트 읽기 검토에서 호출·과금·인증·결과 무결성의 차단 결함 없음. 채택 시 이전 제안 힌트가 남는 UI 문제는 함께 비우도록 수정했다. 오프라인 샘플 브라우저에서 제안→비교→채택(입력/힌트 초기화)→맥락 예문 활성화·재제안을 확인했다. 모드/채택별 24/28/28/32개 예문을 소진하기 전 중복 없이 제공하는 것도 독립 확인했다.

## 13. 추가 실측과 이번 베이크오프 종료 — 2026-09-18

**현재 결정: Gemini 3 Flash 유지. 이번 모델 탐색은 종료하고 시스템 프롬프트 개선으로 우선순위를 옮긴다.** 사용자는 추가 후보도 직접 시험한 뒤 즉시 교체할 유의미한 결과가 없고 추가 탐색의 기대 이득이 낮다고 판단했다. 이는 §10–11의 잠정 유지/추가 탐색 제안을 갱신한다. 미시험 Haiku/Step 추가는 진행하지 않는다. 현재 모델의 시장 전체 우위나 다른 조건에서의 우위까지 확정한 결론은 아니다. 비교실과 기존 원본 결과는 후속 프롬프트 비교에 재사용한다.

추가 관측: 로제타 자유 모드 단일 공통 대화 5턴, 각 3모델, 총15개 출력. 전부 기본 계약 검증 통과, 비용 누락 없음. 주제는 첫 만남→교수에게 신고 예고→폭력 위협→진지함 강조→놀리기로 이어졌다. 세 모델의 같은 턴 입력은 같고 모든 요청의 추론은 default다. 새 라운드의 점수는 모두0/미평가, 메모 없음(마지막 run은 ratings 비어 있음)이므로 사용자 선호 점수·승률은 산출하지 않는다. 위 품질 결론의 사용자 근거는 이번 대화의 종합 판단이다.

| 모델 | 첫 씬 중앙값 | 전체 완료 중앙값 | 5회 실제 비용 합계 |
|---|---:|---:|---:|
| Gemini 3 Flash | 3.666초 | 5.580초 | $0.02301200 |
| Gemini 3.5 Flash-Lite | 2.041초 | 3.047초 | $0.01721242 |
| Aion 3.0 Mini | 10.228초 | 16.114초 | $0.02521812 |

Flash-Lite는 이번 5개 대응 턴 모두 첫 씬이 빠르고 실제 비용 합계도 약25.2% 낮았다. 따라서 지표상 개선까지 없었던 것은 아니다. 다만 ‘티타임 상종’, ‘지우를 쾅쾅 노려본다’ 같은 어색한 표현이 출력에 남았다. Aion은 이번 표본에서 기준보다 5턴 모두 느렸고, 마지막 턴의 ‘쫄기는 무습’ 같은 표현도 관측됐다. 이 사례들로 모델 전체 한국어 성능이나 정량 품질 점수를 확정하지 않는다. 실제 비용 차이는 해당 입력·출력량·캐시·기본 추론 설정을 포함한 청구값으로 운영 고정 절감률이 아니다.

프롬프트 개선에 이어갈 관찰:

- 외형 누락 문제가 재현됐다. 첫 턴부터 Gemini3는 보랏빛 눈, Aion은 푸른 머리/호박색 눈을 생성했다. 공식 시드의 금발/붉은 눈과 다르며 V1 전송본의 누락을 먼저 수리한다.
- 기준 모델에도 손볼 여지가 있다. 유저가 ‘농담 같아?’만 입력한 턴에 Gemini3가 ‘얼굴 너무 가깝잖아!’라는 거리 변화를 추가하고 두려움을 크게 증폭했다. 유저가 명시한 행동과 캐릭터의 추측을 구분하는 경계, 내면 약점의 표현 강도를 후속 비교 대상으로 삼는다. 이 한 사례로 특정 교정안의 효과를 확정하지 않는다.
- 2–5턴 공통 history는 모두 Gemini3의 직전 답변을 채택했다. 따라서 다른 모델의 반응은 기준 모델이 만든 공포·위축 흐름을 이어받는다. 모델별 독립 5턴이나 서로 다른 5개 상황으로 집계하지 않는다. 스토리·장기 기억·시크릿 적합성은 이번 추가 실측으로 검증되지 않았다.

원본 run ID(시간 순): `e1b581aa-7ede-4eb2-96c8-7fa0e833095d`, `a31ebb6e-3b74-4298-822d-70aaeac74f8a`, `53dfb916-5a72-4c52-ab97-bf03e058902b`, `5ab77091-2ca1-4c2b-9cf3-4ac12609e371`, `0de624fb-697c-4d97-8770-6e7d952e4f0a`. 로컬 `.local/analysis-additional-2026-09-18.json`에 파일 SHA-256·집계 보존. 총비용 $0.06544254는 15개 합계와 모델별 분해합 일치, run 중복 없음. 이번에는 주 에이전트가 출력/평가와 집계를 확인했고 별도 독립 분석 검토는 수행하지 않았다. 유료 추가 호출·운영 소스 수정·원본 평가 덮어쓰기 없음.

다음 순서: 기존 P0 보존 → 행동/대사 충돌·V2 backstory 중복·외형 누락을 좁은 P1로 수정/비교 → 인물 지식/관계/감정 표현 경계 별도 비교. V2 기억 주기·delta 누적은 동작 재현이 필요한 코드 수리로 분리한다. 본 종료 기록 시점에는 모두 아직 미수정이다.

## 14. 시스템 프롬프트 구조 계획 — 2026-09-18

후속 사용자 지시: Gemini 3 Flash 유지 확정. 바로 수정하기보다 최근 RP 방법론과 현재 구조를 면밀하게 검토하고 개선 방향을 계획한다. 새 정본은 [docs/28 시스템 프롬프트 구조 개선 계획](28_System_Prompt_Architecture_Plan.md)이며 §13의 바로 다음 수정 순서는 이 계획으로 구체화한다. 상태는 **설계 제안/구현 전**이다.

핵심 권고는 역할 부여를 유지하면서 핵심 욕구·상황별 반응·관계/상태·인물 지식·한국어 행동 예시를 연결하는 단일 호출 구조다. 현재도 V1은 풍부한 인물 설정을, V2는 감독 구조를 갖고 있으므로 이미 있는 기법을 신규 도입처럼 취급하지 않는다. context loader/composer 분리, 화자·기억 범위/출처 보존, 행동 예시, 평가 체계를 함께 설계했다. 인물 구조·예시·컨텍스트·출력 순서/캐시는 분리 실험하며 버그 수리의 이득과 구조 변경의 이득도 구분한다.

V1/V2 원본 소스·로제타 두 모드 실조립·공식 가이드·2024~2026 RP 연구를 확인하고 3개 독립 컨텍스트로 검토했다. 현재 전송 system 길이는 V1 27,367 / V2 15,475 UTF-16 코드 단위이며 토큰 수가 아니다. [실측 자료](27_assets/prompt-structure-2026-09-18.json). 장기 기억의 정확한 원문 출처·극중 이름 소개 여부는 기존 저장 필드만으로 확정할 수 없음을 표시했다. 기존 프로필 호칭 정본·유효 시크릿 인물 정의·게임/표시 계약은 보존 조건이다.

이번 작업은 문서와 무료 조립 측정까지다. 운영 src/main·비교실 코드·프롬프트 변경, DB 조회/변경, 유료 추론·새 품질 평가, 커밋/푸시/배포는 하지 않았다. 초기에 지적한 프롬프트 결함은 여전히 미수정이며 다음 구현은 docs/28의 기준선/평가 준비부터 진행한다.

### 9/18 후속 — 첫 프롬프트 후보·평가 자료 구현

사용자가 착수를 지시하여 [docs/28 §14](28_System_Prompt_Architecture_Plan.md)의 비교실 후보를 구현했다. P0 원본을 보존하고 C1 수리·S1 인물 구조·E1 한국어 예시를 lab에서 선택한다. 동일 Gemini/provider/추론 조건, 후보별 전송본/hash와 상태 snapshot, 12개 개발 사례, 다음 실행 전 평가 자동 저장을 추가했다. [문구 대조](28_assets/prompt-draft-comparison.md), [4캐릭터 평가 카드](28_assets/character-evaluation-cards.md), [실행 설명](../tools/llm-bakeoff/README.md).

Java smoke·Node31개·독립 검토·오프라인 브라우저 흐름·실서버 무료 미리보기까지 확인했다. P0 전송본 바이트 보존, 기존 로컬 JSON16개 hash 일치. 현재 18767의 기본은 P0/C1/S1 프롬프트 비교다. 수리는 **비교실 후보에만 적용**됐으며 운영 프롬프트·모델 품질 평가·장기 상태/기억 수리는 아직 미실행이다. 유료 호출·운영 src/main 변경·커밋·배포 없음.

## 15. 프롬프트 실측 비교 완료 — 2026-09-18

사용자 추가 $3 승인 범위에서 Gemini3의 P0/C1/S1/E1을 총148회 실행, 실제 비용 $0.52754835·미확정0. [실측 보고서](28_assets/prompt-evaluation-2026-09-18.md)와 [구조 계획 §15](28_System_Prompt_Architecture_Plan.md#15-실제-프롬프트-비교--2026-09-18)를 따른다. S1/E1의 전반 대사 품질 우위는 불충분하며, C1의 정보 전달 결함 수리와 사실 우선순위·유저 행동 경계·출력 계약을 분리해 다음 실험을 진행하는 것이 권고다. strict JSON18건/감정 enum2건(중복1)은 원문 평가와 현행 검증의 차이를 보여준다. 현재 초안 전체를 운영에 채택하지 않았다. 운영 src/main·모델 변경, DB·배포 없음. 아래/이전 절의 ‘유료 미실행’은 초기 구현 시점이다.

### 9/18 후속 — 축 분리·씬 경계 후보 156회 추가 비교

새 정본은 [후속 분석](28_assets/prompt-evaluation-round2-2026-09-18.md)과 [구조 계획 §16](28_System_Prompt_Architecture_Plan.md#16-축-분리와-씬-구성-재작성-후속-실험--2026-09-18)이다. 같은 $3 승인 잔액에서 추가 $0.6213632666666666, 누적304회 $1.1489116166666666를 사용했다. 사실·행동·현재 의도·유효 JSON을 분리하고, R2의 기존 씬 구성 지시만 재작성한 D3까지 시험했다. F2 대 J2 같은16짝의 전체 JSON 오류5 대0은 후속 검증 가치가 있으며 전반 대사 품질 또는 운영 P0 대비 우위로 일반화하지 않는다. 후보는 비교실에만 적용했고 기존 응답·예산 증거를 보존했다. 현재 기본 P0/F2/J2, 총11후보·실서버18767·Node75/Java smoke/무료 미리보기 검증. 운영 모델은 Gemini 3 Flash이며 `src/main`은 그대로다.
