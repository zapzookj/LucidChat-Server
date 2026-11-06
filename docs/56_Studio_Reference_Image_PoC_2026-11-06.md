# 56. 스튜디오 레퍼런스 이미지 PoC — B 방식(GPT 2.5 원화 변환) (2026-11-06)

> 상태: PoC 완료(하네스는 워크트리 `MuseLab/aichat-refpoc`, 브랜치 `poc/reference-image`, 미커밋). **§9 파이프라인 변경 2건 구현**(브랜치 `feature/standing-gpt-paste`, 배포 전).
> 비교 화면: `poc/out/refpoc/report.html`(이미지 내장 단일 파일, git 제외 경로).

## 1. 배경과 결정

- 종원 요청: 캐릭터 빌더 외형 입력을 텍스트 대신 레퍼런스 이미지로 받는다.
- 방식 결정(종원): **B 방식** — 레퍼런스를 GPT Image 2.5 편집으로 원화(황금샷) 변환, 이후 기존 스탠딩·감정 체인 유지. 유저가 복장·신체 스펙을 조정할 수 있게 한다.
- 정책(종원): 공개는 어차피 심사를 거치므로 레퍼런스 정책은 큰 문제가 아니다.
- 종원이 제기한 핵심 문제: 텍스트 방식은 외형이 태그로 영속화되지만 B는 이미지가 원인이라 태그를 **역추출**해야 하고, 그 태그의 재현율이 실시간 씬 일러를 좌우한다.
  `appearanceTags`의 소비처는 ①스탠딩 WF-2 positive·얼굴 와일드카드 ②감정 리파인 ③`Character.appearanceTags` → 씬 t2i 세 곳이다.
- 태거 호스팅 결정(종원): 프로덕션은 **UGC 워커에 태거 노드 추가**. PoC는 운영 엔드포인트를 건드리지 않도록 같은 모델(WD EVA02-large v3)을 로컬 ONNX로 실행했다(같은 모델·전처리·임계값 0.35/0.85라 태그가 동일).

## 2. 실행 방법

- 하네스: `src/test/java/com/spring/aichat/service/ugc/ReferenceImagePoc.java`(클래스명이 `*Test`가 아니라 CI 제외). 프로덕션 클래스를 직접 호출한다:
  `UgcPromptAssembler`(Qwen 2패스·refinePositive·gptExpressionPrompt), `UgcWorkflowFactory.buildNeutralRefine`, `NeutralFacePrompt`, `FalGptEmotionEditClient.input`, `ScenePromptAssembler`, `SceneWorkflowFactory`.
  새로 쓴 것은 원화 변환 프롬프트와 LLM 표정 카탈로그 입력용 프로필뿐이다. 카탈로그 LLM 프롬프트는 `ConceptStructuringService.deriveExpressionCatalog`에서 그대로 복사했다.
- 태거: `poc/ref_tagger.py`. 정체성 외 태그(구도·자세·표정·배경·소품)를 걸러 낸다.
- 키: IntelliJ 실행 구성(`aichat/.idea/workspace.xml`, AichatApplication)에서 값 출력 없이 env로 주입했다.
- 외부 호출: fal(GPT Image 2.5 sunburst edit, Qwen 2511) + UGC RunPod(WF-2 neutral) + 씬 RunPod(TIPO).

## 3. 결과

| 단계 | 결과 |
|---|---|
| 1. 원화 변환 (GPT 2.5 high ×2 프레이밍) | **매우 충실** — 안대·하트 앵크 브로치·하네스·사이하이·신발까지 레퍼런스 디자인이 거의 그대로 유지됨 |
| 2. 태그 역추출 | golden_0 39개·golden_1 34개 정체성 태그. 핵심 식별점(금발 로우 트윈테일·yellow eyes·medical eyepatch·세일러 칼라·red bow·black leotard·white thighhighs·bandages)은 전부 잡힘. 신체 강조 태그(`thigh gap`·`covered navel`·`collarbone`)가 섞여 들어옴 |
| 3. 베이스 스탠딩 | **Qwen 패스1이 4번 중 3번 안대를 지웠고, WF-2가 매번 추출 태그로 복원**함(태그 영속화의 실효 증거). 프레이밍(카우보이→전신)·신발 추가(부츠)는 확률적으로 흔들림 — 기존 파이프라인 공통 결함 |
| 4. 감정 파생 (LLM 카탈로그 12 + GPT 2.5 low, 재시도 예산 4회) | 동일성은 우수(안대·의상·헤어 유지). **원 태그 스탠딩: 7/12 통과 · 5컷은 4회 모두 `content_policy_violation`**. 신체 강조 태그 4개를 뺀 `clean` 스탠딩: **12/12 통과**(3컷은 2~3회차) |
| 5. 씬 일러 (추출 태그, 1인 4 + 유저 동석 2) | 큰 틀(금발 트윈테일·안대·세일러·레오타드)은 같은 '타입'으로 읽힘. 눈 색 yellow→red/pink 이탈, 하트 브로치·하네스·크롭 재킷 구조 소실, 레오타드 광택 과장, **1인 씬 2장에서 캐릭터 2~3명 복제**. 예상대로 낮은 재현율 |

### 4단계 차단 원인 분리

원 태그 스탠딩에서 4회 모두 막힌 5개 프롬프트를 다른 입력 이미지에 그대로 걸었다.

- 원화 golden_0: 5/5 통과(1컷 2회차)
- 대조군(기존 텍스트 방식 남캐 스탠딩): 5/5 통과
- clean 스탠딩: 5/5 통과

→ 원인은 프롬프트가 아니라 **입력 이미지**다. WF-2가 신체 강조 태그로 하반신을 강조해 다시 그린 스탠딩이 OpenAI 검사에 걸렸다.
**단서:** clean 스탠딩은 Qwen이 전신으로 줌아웃해 하반신 비중도 같이 줄었다. 태그 효과와 구도 효과는 분리되지 않았다(미실시).

## 4. 판단

- **4단계(관건)는 B 방식에서 성립한다.** 동일성은 기존 텍스트 방식과 같은 GPT 편집 체인이라 유지되고, 차단은 B 고유가 아니라 "노출 많은 디자인 × WF-2 신체 강조 태그" 문제로 관측됐다. 태거 필터에 신체 강조 태그 제외 목록을 두는 것이 1차 대응이다.
- **5단계는 종원 예상과 일치.** 레퍼런스 캐릭터의 실시간 일러 잠금(또는 '타입 근사' 고지)이 현실적이다. 개선 여지(태그 수 축소·중복 제거로 복제/색 번짐 완화)는 미검증 가설이다.
- 3단계의 Qwen 안대 삭제·프레이밍 이탈은 텍스트 방식에도 있는 기존 결함이며, WF-2 + 추출 태그가 보정 장치로 작동했다.

## 5. 2차 실험 — 정체성 유지 + 메이드복 (2026-11-06)

- 가설(종원): 1차 감정 차단 원인은 '교복 느낌 + 높은 노출' 조합이다.
- 방법: 하네스 `stage1Maid`로 원화 두 장을 만들었다. `maid_0`은 레퍼런스에서 바로 메이드복으로 그린 것(업로드 시점 조정), `maid_1`은 `golden_0`의 복장만 편집한 것(원화 확정 후 조정)이다. 이후 `maid_0` → 태거(**신체 강조 필터 미적용 원 태그 40개**) → 스탠딩 2 → 감정 12(같은 카탈로그, 재시도 예산 4회) 순서로 진행했다.
- 결과:

| 스탠딩 | 복장 | 태그 | 구도 | 최종 통과 | 1회차 통과 |
|---|---|---|---|---|---|
| 원 태그 #0 | 세일러 + 레오타드 | 원 태그 | 카우보이샷 | 7/12 | 3/12 |
| clean #0 | 세일러 + 레오타드 | 신체 강조 4개 제거 | 전신 | 12/12 | 9/12 |
| maid #0 | 메이드복 | 원 태그 | 전신 | 12/12 | **12/12** |

- 판단: **가설과 일치한다.** 같은 전신 구도에서도 메이드복은 재시도 없이 전부 통과했고, 교복+레오타드는 태그를 걸러도 3컷이 재시도를 탔다. 복장(노출)이 1차 요인이고, 신체 강조 태그와 클로즈업 구도는 가중 요인으로 보인다.
- 복장 조정 기능 실효: 두 경로 모두 정체성(안대·헤어·초커·눈 색)을 유지한 채 복장을 교체했다. `maid_1`은 자세·구도까지 원화 그대로라 '부분 수정' UX에 적합하지만 thigh strap이 남았다.
- 잔여(기존 파이프라인 공통): Qwen 패스1의 안대 삭제·전신 줌아웃·갈색 부츠 삽입, WF-2 후 스타킹이 배경색(muted teal)으로 물드는 현상이 반복됐다. 태거 필터를 `skirt hold`(자세)·`enmaided`·`alternate costume`(메타)가 빠져나왔다.

## 6. 3차 실험 — 원화→스탠딩 변환 모델 교체 (Qwen 2511 → GPT Image 2.5 high)

- 문제(종원): 원화→스탠딩 변환에서 안대 삭제, 복장과 어울리지 않는 스타킹 색·신발 같은 오류가 잦다.
- 방법: 하네스 `stage3StandingGpt`에서 모델만 바꿨다. 프로덕션 Qwen 패스1·패스2 프롬프트를 그대로 GPT Image 2.5 편집(high)에 넣었고, 이후 WF-2 neutral refine(같은 태그)도 동일하다. 교복(`golden_0`)과 메이드복(`golden_maid_0`)에서 각각 후보 2장씩 만들었다.
- 결과(편집 직후, WF-2 전, 후보 4장씩):

| 항목 | Qwen 2511 | GPT Image 2.5 high |
|---|---|---|
| 안대 유지 | 1/4 (나머지는 WF-2 태그가 복원) | **4/4** |
| 카우보이샷 지시 준수 | 1/4 (3장 전신 줌아웃) | **4/4** |
| 없던 신발·레그웨어 생성 | 3/4 (갈색 부츠·남색 타이츠) | **0/4** |
| 복장 디테일 | 단순화·변형 잦음 | 원화 거의 그대로 |
| 배경 균일도(모서리 표준편차) | 1.1~1.2 | 0.7~0.8 |
| 배경색 | muted teal에 근접 | 더 밝은 청록, 후보마다 다름 |
| 후보 간 다양성 | 큼(대부분 결함에서 나옴) | 작음(거의 같은 자세) |
| 관측 지연 | 2체인 + WF-2 ≈ 1.5~2분 | 4체인 병렬 + WF-2 ≈ 3.4분 |

- 판단: 정체성 보존과 지시 준수는 **GPT가 명확히 우위**다. 지금 보이는 오류(안대·구도·신발·스타킹)는 Qwen 패스에서 생긴다. GPT 입력이면 WF-2가 고칠 것이 거의 없어, 메이드복 Qwen 경로에서 생긴 스타킹 청록 물듦도 나타나지 않았다.
- 대가: 배경색이 지정값에서 벗어나고(누끼 경계 품질에 미치는 영향은 미측정), 후보 2장 선택의 의미가 약해진다. GPT high 단가는 미확인이다.
- 미측정: GPT 경로 스탠딩에서 감정 12컷 정책 차단률, 텍스트 방식(WF-1 원화) 캐릭터에도 같은 우위가 있는지.

- **종원 결정(2026-11-06): 원화→스탠딩 변환은 GPT Image 2.5로 확정.**

## 7. 4차 실험 — WF-2 얼굴 보존

- 문제(종원): WF-2가 전체 질감은 올리지만 얼굴(특히 눈)을 새로 그려 인상이 다른 사람처럼 바뀐다. WF-2 전 얼굴이 원화에 더 충실하다.
- 구조: `wf2_neutral_refine.json` = LoadImage19 → VAEEncode20 → KSampler11(0.4) → VAEDecode8 → 얼굴 FaceDetailer17(0.4, guide 512) → 손 FaceDetailer24(0.3) → Save9.
- 방법(하네스 `stage3FaceVariants`, 입력 = GPT 스탠딩 편집본 `g0gpt_base_edit0`·`maidgpt_base_edit0`):
  - `diag`: 현행 그대로 + 8번·17번 뒤에 중간 저장.
  - `paste`: 현행 전체 → 입력 얼굴을 최종본에 합성(`BboxDetectorSEGS`(anime_face) → `SegsToCombinedMask` → `ImpactGaussianBlurMask`(31, σ10) → `ImageCompositeMasked`).
  - `masked`: 같은 마스크를 `InvertMask` → `SetLatentNoiseMask`로 KSampler 입력에 걸어 얼굴을 제외하고, 17번을 제거해 24번이 8번을 받게 한다.
- 결과:
  - 진단: **KSampler 0.4 단계에서 이미 눈이 둥글고 커지며 인상이 바뀌고**, FaceDetailer가 한 번 더 바꾼다. 메이드복 현행 최종본은 안대가 머리카락처럼 녹았다. FaceDetailer만 끄는 것으로는 부족하다.
  - `paste`: 얼굴이 입력과 동일하다. 다만 얼굴 박스 안팎의 머리결 질감이 미세하게 다르다.
  - `masked`: 얼굴이 입력과 거의 동일하고(VAE 왕복분만 차이), 디노이즈가 경계를 이어 붙여 더 자연스럽다(에이전트 당초 추천).
- **종원 결정(2026-11-06): `paste` 확정.** 근거: 판정 기준은 육안이고, 얼굴 박스 안팎 질감 차이는 사람 눈으로 거의 식별되지 않으며, 원 얼굴 보존이 가장 확실하다. 얼굴 FaceDetailer(17)는 그대로 두고 최종본 위에 입력 얼굴을 합성한다.
- 노드 가용성: Impact Pack 8.28.3·코어 노드만으로 UGC 워커에서 그대로 실행됐다(워커 이미지 변경 불필요).
- 프로덕션 반영 시(`paste` 기준): `wf2_neutral_refine.json`에 노드를 추가한다(60 `BboxDetectorSEGS`(18·19) → 61 `SegsToCombinedMask` → 63 `ImpactGaussianBlurMask`(31, σ10) → 64 `ImageCompositeMasked`(destination 24, source 19), 9번 SaveImage 입력을 64로). 기존 노드는 그대로라 `UgcWorkflowFactory` 치환 계약은 유지되고, 새 노드용 fail-fast 검증만 추가하면 된다. 감정 컷은 GPT 편집이라 WF-2를 타지 않아 영향이 없다.
- 참고: `paste`에서 얼굴 FaceDetailer(17)의 결과는 얼굴 박스 안에서 덮이므로 GPU 시간만 쓴다. 제거 여부는 박스 경계 페더 바깥의 효과를 보고 정한다(미판정).
- 미검증: 얼굴 미검출 시 동작(빈 마스크 → paste는 현행 결과, masked는 얼굴까지 리파인 예상), 얼굴 영역 부분 디노이즈(0이 아닌 약한 값) 변형, 표본 2장.

## 8. 미검증·후속

0. 정책 차단은 '노출 많은 교복류' 디자인에 집중될 가능성이 크다. 업로드 시점 VLM 게이트 또는 노출 경고·복장 조정 유도를 UX로 검토한다.
1. 태그 효과 vs 구도 효과 분리: clean 태그 + 카우보이샷 스탠딩으로 감정 재측정.
2. 표본 1캐릭터 — 노출 적은 디자인·남캐·실사 레퍼런스 등 다양화 필요.
3. 씬 일러 태그 다이어트(상위 N개·중복 제거) 효과.
4. 유저 조정(복장·신체 스펙) GPT 편집 단계와 고정 태그 컴파일은 미구현·미측정.
5. GPT 2.5 high/low 실청구 단가 미확인.
6. 태거 노드 워커 탑재(ComfyUI-WD14-Tagger 등) 미착수.

## 9. 구현 — 스탠딩 GPT 변환 + WF-2 원 얼굴 되붙이기 (2026-11-06, 배포 전)

종원 확정 2건(§6·§7)을 텍스트 방식 스튜디오 파이프라인에 그대로 반영했다. 레퍼런스 업로드 UI는 미착수다.

### 변경

- `UgcPipelineWorker.submitBaseCandidate`: 2패스 편집을 `standingEdit`로 위임한다. `ugc.standing.editor=gpt`(기본)면 `FalGptEmotionEditClient`(quality `ugc.standing.gpt-quality`, 기본 high), `qwen`이면 기존 `PoseEditClient`다. 프롬프트는 동일하고, GPT는 네거티브를 쓰지 않으며 seed는 null이다.
  - 제출(블로킹 POST)은 전용 4스레드 데몬 풀에서 돈다. requestId를 로그로 남긴다.
  - 제출 422(`RequestRejected`, 예: content_policy_violation)는 재시도 없이 그 후보만 FAILED로 둔다. 다른 실패는 기존과 같이 후보 재시도 예산(3)을 쓴다.
  - 레거시(비동적) 감정 파생의 Qwen 경로는 바꾸지 않았다.
- `FalGptEmotionEditClient`: POST 422를 `RequestRejected(reason)`로 구분한다. 응답 본문에 프롬프트 echo가 있어 사유 type만 남긴다. 동적 감정 경로는 제출 예외를 기존처럼 일괄 처리한다(동작 불변).
- `UgcPipelineProperties`: `standing` 컴포넌트를 추가하고, 8인자 하위 호환 생성자와 정식 생성자 `@ConstructorBinding`을 둔다. 허용 밖 값(예: `qwne`, `medium`)은 기동 시점에 `IllegalArgumentException`으로 막는다.
- `application.yml`: `ugc.standing.editor: ${UGC_STANDING_EDITOR:gpt}`, `gpt-quality: high`.
- `wf2_neutral_refine.json`: 노드를 추가했다(60 `BboxDetectorSEGS`(18, 입력 19) → 61 `SegsToCombinedMask` → 63 `ImpactGaussianBlurMask`(31, σ10) → 64 `ImageCompositeMasked`(destination 24, source 19, mask 63)). 저장 노드 9는 64를 받는다. 기존 노드는 그대로다.
- `UgcWorkflowFactory.loadTemplates`: 되붙이기 배선 8개 링크를 fail-fast로 검증한다(`requireLink`).
- 어드민 인스펙션 DTO 주석: 필드명 `qwen*`는 레거시이고 편집 모델은 설정을 따른다고 적었다.

### 검증

- 테스트: UGC 118개 통과(신규 `StandingEditorRoutingTest` 8개 — 라우팅·Spring Binder 바인딩·설정 검증·거절 무재시도·일시 오류 재시도, `NeutralRestorationTest` 배선 단언 갱신).
- 전체 388개 중 실패 2개(`contextLoads` 보안 env 부재, `StoryDirectorPromptAssemblerContractTest` CRLF)는 수정 전 master에서도 동일하게 실패함을 확인했다.
- 실호출(커밋 제외 하네스 `StandingPipelineLivePoc`): 프로덕션 `standingEdit` + 실 fal 클라이언트로 GPT 2패스를 실행했다(안대·카우보이샷 유지, seed null). 이어 새 템플릿을 프로덕션 UGC 워커에서 실행해 1장을 받았다. 입력 대비 차이 맵에서 얼굴 영역만 무변화이고 나머지는 WF-2 질감이다.
- 독립 코드 검토: 고위험 0건. 중위험 2건(설정 검증·422 재시도)과 저위험 4건(전용 실행기·배선 검증·requestId·개행)을 반영했다.

### 미반영·잔여 (검토 지적 중 의도적으로 남긴 것)

- `editor=qwen`은 편집 모델만 롤백한다. 얼굴 되붙이기는 템플릿 되돌리기 + 재배포로만 끌 수 있다(별도 스위치 없음).
- 얼굴 FaceDetailer(17)와 `NeutralFacePrompt`의 효과는 페더 띠 밖에서는 덮인다. 얼굴 박스 안의 손 보정(24)도 되돌려진다. 17번 제거 여부는 미판정이다(GPU 시간만 소모).
- 제출 POST가 모호하게 실패하면(타임아웃 후 접수) 재시도로 이중 과금될 수 있다. 재기동 시 스윕이 진행 중 체인을 다시 돌리는 것도 같다. 기존 Qwen 경로와 같은 계열이지만 GPT high는 단가가 높다.
- 레거시 비동적 잡이 배포 후 스탠딩을 새로 만들면 seed가 null이라 Qwen 감정 seed 고정이 풀린다(신규 잡은 전부 동적이라 해당 없음).
- 원가: 스탠딩 1배치 = GPT high 4콜(후보 2 × 패스 2). 단가는 미확인이고, 에너지 단가(4E)는 그대로 뒀다.
