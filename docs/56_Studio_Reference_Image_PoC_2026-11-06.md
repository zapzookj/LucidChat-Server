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

## 10. UI 목업 확정과 구현 계획 (2026-11-07)

### 확정(종원 "이대로 확정")

- 목업: `C:\Users\zapza\Desktop\LucidChat-Front\mockups\reference-builder\index.html` v2. 다관점 크리틱을 반영했다.
- 목업 기본값을 그대로 채택한다.
  - 그림으로 만든 캐릭터는 대화 중 장면 일러스트를 **잠근다**. 표정 일러스트는 동일하게 만든다.
  - 그림 방식 시작 단가 **6E**(텍스트와 동일), 이후 +4/+8/+2E. 다시 뽑기·**이 그림 고치기 각 +2E**.
  - **"이 그림 고치기"** 포함: 선택한 원화를 구도 유지로 편집해 새 후보를 추가한다(§5 maid_1 방식).
  - 조정 항목: 복장(6종·직접 입력)·체형·가슴·기타 요청. **키 조정은 제외**한다(원화·스탠딩이 허벅지 위 구도라 보이지 않음).
- 업로드 그림 정책은 목업 문구("원화를 그리는 데만 쓰고, 다른 유저에게는 보이지 않아요")를 따르는 **에이전트 기본안**으로 간다. 종원이 바꾸면 수정한다.
  - 비공개 저장소에 보관하고, 캐릭터가 있는 동안 유지한다(공개 심사 시 운영자 열람 — IP 확인용).
  - 캐릭터 삭제 시 함께 삭제한다. 캐릭터 없이 끝난 잡의 그림은 7일 뒤 삭제한다.

### 구현 순서

1. **워커 — 태거 노드 [확인필요 선행]**: `lucid-comfy-worker`에 WD14 태거 노드와 EVA02-large v3 모델을 베이크한다. 단, worker-comfyui 핸들러가 **텍스트 출력**(태그 문자열)을 응답으로 돌려주는지 먼저 확인한다. 돌려주지 않으면 출력 방식(이미지 메타·파일)을 바꾸거나 핸들러를 패치한다.
2. **BE 데이터**: V41 — `ugc_reference_images`(소유자·S3 키·sha256·크기·VLM 판독 JSON·상태·생성 시각), 잡의 `reference_image_id`·`adjustments_json`, 캐릭터의 외형 출처(그림 방식 여부 → 장면 일러 잠금).
3. **BE 업로드·검사**: `POST /ugc/references`(multipart, PNG/JPG/WebP ≤10MB) → 재인코딩·EXIF 제거·≤1536px 축소 → 비공개 S3 → VLM 1회로 판정(정상 / 인식 실패 / 여러 명 / 정책)과 특징 판독(한국어 특징 목록·성별 추정)을 받는다. 에너지는 쓰지 않는다. 남용 방지 레이트 리밋을 둔다.
4. **BE 생성 분기**: `createUgcCharacter`에 `referenceId`·`adjustments`를 추가한다.
   - Stage0은 이미지 프롬프트 LLM을 생략하고 프로필만 만든다(VLM 판독 + 성격 글).
   - 원화는 GPT 2.5 high ×2로 만든다(레퍼런스 + 조정 컴파일 프롬프트, §1-B 프롬프트 기반).
   - 후보마다 태거 → 정체성 태그(신체 강조·메타·자세 태그 필터 보강 — §3·§5 관측) → `GoldenSnapshot`으로 저장한다.
5. **BE 다시 뽑기·고치기**:
   - 레퍼런스 재생성 리롤(+2E, 조정 갱신)을 둔다.
   - 신규 `POST /ugc/characters/{jobId}/golden-shot/{index}/fix`(+2E, 원화 편집 → 후보 추가 + 태거 스냅샷)를 둔다.
6. **BE 잠금·심사**: 씬 일러 요청을 거부한다(그림 방식 캐릭터). 어드민 인스펙션에 레퍼런스 원본을 노출하고, 보관 정책에 따라 정리 스케줄러를 둔다.
7. **FE**:
   - `ConceptStep` 그림 모드(업로드 카드·특징·바꾸기·고지·통합 사유 CTA).
   - `GachaStep` 비교·고치기·다시 뽑기.
   - 채팅의 씬 일러 버튼 숨김, `StudioApi` 함수 추가.
8. **검증**: 단위 테스트, 그리고 IntelliJ 실행 구성의 env를 로더로 주입해 **로컬 bootRun 실제 E2E**를 한다(이전엔 기동 불가였음). FE는 실제 화면으로 375·데스크톱을 확인하고, 독립 검토를 받는다.
9. **배포**: BE(`feature/standing-gpt-paste` 위) + FE + 워커 이미지를 한 번에 배포한다(종원 지시).

## 11. 구현 — 그림 방식 빌더 (2026-11-07, 미커밋·배포 전)

§10 구현 순서 1~7을 반영했다. BE는 이 워크트리(`feature/standing-gpt-paste` 위), FE는 `LucidChat-Front-reference`(`feature/reference-builder`), 워커는 `lucid-comfy-worker-tagger`(`feature/wd14-tagger`)에 있다. 세 곳 모두 미커밋이다.

### 변경 요약

- **워커**: WD14 태거 노드(pythongosssss `9e0a6e7`)와 `wd-eva02-large-tagger-v3`(sha256 고정)를 베이크했다. onnxruntime CPU를 쓴다. worker-comfyui 5.8.6 핸들러는 이미지만 돌려주므로, `patches/handler_text_outputs.py`로 텍스트 출력을 `texts`로 함께 반환하게 했다. 앵커 3곳이 각각 정확히 1회 매칭되지 않으면 빌드가 실패한다.
- **BE 데이터**: V41 — `ugc_reference_images`(sha256 `VARCHAR(64)`; prod `ddl-auto: validate` 부팅으로 타입을 검증했다), 잡의 `reference_image_id`·`adjustments_json`, 캐릭터의 `reference_image_id`(장면 일러 잠금 판정).
- **BE 업로드**: `POST /api/v1/ugc/references`(multipart `file`).
  - 시간당 20회로 제한한다.
  - 디코드 전에 헤더 치수를 확인한다(변당 8000px·40MP 상한, 서브샘플링).
  - RGB PNG로 재인코딩한다(흰 배경, 긴 변 ≤1536). WebP는 TwelveMonkeys로 읽는다.
  - 비공개 S3(`no-store`)에 저장한다.
  - VLM 1회로 판정(OK/UNCLEAR/MULTIPLE/POLICY, 실사 인물 사진은 POLICY)과 한국어 특징을 받는다. VLM이 실패하면 객체를 지운다.
- **BE 생성**:
  - `ReferenceGoldenService`가 GPT 2.5 high ×2로 원화를 만든다.
  - 후보마다 태거를 비동기 폴링(8분 기한, `touch`로 stale 스윕 회피)하고, `IdentityTagFilter`로 거른 뒤 후보별 `GoldenSnapshot`으로 저장한다.
  - 일시 오류는 1회 재시도한다(422 제외). 전 후보가 실패하면 환불한다. 세대 토큰으로 늦게 도착한 결과를 버린다.
- **BE 다시 뽑기·고치기**: 레퍼런스 리롤(+2E, 조정 갱신)과 `POST /{jobId}/golden-shot/{index}/fix`(+2E, 1장, `origin=FIX`·`fixedFrom`)를 두었다.
- **BE 잠금·심사·정리**:
  - 씬 일러 수동 요청과 availability가 거부한다.
  - 자동 경로 `SceneRenderService.resolveForTurn`도 같은 가드를 둔다(현재 휴면, 되살릴 때 우회 방지).
  - 어드민 인스펙션에 레퍼런스 원본과 `standingEditor`를 노출한다.
  - 캐릭터 없이 끝난 잡의 그림은 매시간 스케줄러가 7일 뒤 삭제한다.
- **FE**:
  - `ConceptStep`에 글/그림 모드, 업로드 카드(판정별 안내), 조정 패널, 통합 미충족 사유를 추가했다.
  - `GachaStep`에 올린 그림 블록, 비교 모달, "원화 N 수정본" 배지, 고치기·다시 뽑기 조정 모달을 추가했다.
  - 채팅의 씬 일러 버튼은 기존 `availability.enabled=false` 비노출 로직을 그대로 쓴다.

### 검증

- BE 단위 테스트: `ReferenceBuilderUnitTest` 12개, `ReferenceGoldenServiceTest` 6개가 통과했다. 전체 406개 중 실패 2개는 master와 동일하다(§9).
- FE: 빌드와 TDZ·엔트리 검사를 통과했다. 린트는 기존 오탐만 남았다.
- 개발 프리뷰 `/__design/secondary`(픽스처)에서 다음을 확인했다.
  - 모드 전환, 업로드 checking→ready, 조정 페이로드, 미충족 사유.
  - 비교 모달 열기·닫기(포커스 트랩·복귀).
  - 다시 뽑기 모달: 잡 조정값이 미리 채워진다.
  - 고치기 모달: 변경 없으면 비활성 + 사유, 변경 후 `{body:"slim"}`.
  - 375px: 가로 넘침 0, 44px 미만 탭 영역 0.
- 독립 코드 검토 1회(BE·워커)를 반영했다.
- FE 다관점 크리틱(새 컨텍스트)의 확정 지적 14건을 반영하고 프리뷰에서 실측했다.
  - 원본 없는 잡의 죽은 "비교" 버튼을 숨기고, 이미지 로드에 실패하면 블록을 숨긴다.
  - 고치기·다시 뽑기 모달은 요청이 성공한 뒤에 닫는다. 실패하면 초안을 유지하고 모달 안에 사유를 보인다. 에너지 부족은 "에너지가 2E 필요해요 (지금 0E)"로 막는다.
  - 생성되지 않던 Tailwind 클래스 `accent/12`·`/22`를 `/[0.12]`·`/[0.22]`로 바꿨다(기존 화면 3곳 포함).
  - 성별 자동 선택 안내와 `genderGuess` 값 검증을 넣었다.
  - 직접 입력 복장이 비었을 때 안내한다. 확인된 그림이 있을 때 잘못된 파일을 고르면 기존 그림을 유지한다. 네트워크 오류 시 같은 파일로 재시도한다. 판정별 기본 문구를 둔다.
  - 프리뷰에서 연 모달을 닫으면 카드로 포커스를 되돌린다.
  - 수정본 배지에 토큰을 쓰고 `fixedFrom`이 없을 때도 처리한다. 그림 방식 카피를 정리하고, 탭 영역을 44px로 맞췄다.

### 미검증·잔여

- **실 E2E 미실행** — 로컬 AWS 키가 만료되어 S3가 403을 낸다. R2 자격증명을 갱신하거나 스테이징에서 해야 한다. 워커 이미지 빌드와 RunPod 엔드포인트 갱신 전에는 태거 경로도 실측할 수 없다.
- **모션 미측정** — 숨긴 브라우저 패널에서는 rAF가 멈춰 framer-motion 진입·퇴장을 볼 수 없다. 실기기 확인이 필요하다.
- [확인필요] 레퍼런스 객체가 CDN 공개 경로로 닿는지(버킷·CDN 규칙) 미확인이다. 키는 UUID이고 `no-store`다.
- 정책: 리롤·고치기의 전 후보가 실패하면 잡 전체가 실패한다(텍스트 방식과 동일, 의도적으로 유지).
- POLICY에 실사 인물 사진을 포함한 것은 에이전트 기본안이다.
- FE 크리틱 중 남긴 것:
  - `goldenPending`(고치기 1칸·다시 뽑기 2칸 자리 표시)이 로컬 상태라 새로고침하면 2칸으로 보인다.
  - 컨셉 단계의 "+2E" 안내가 하드코딩이다. 서버 `rerollCosts`는 잡 생성 뒤에야 알 수 있다.
  - 렌더 중 `stableRef` 갱신(동작은 정상).
  - 업로드 후 6일 넘게 머물면 시작할 때 400이 난다.
  - 모달 안 실패 사유 표시는 부모 `runAction` 경로라 프리뷰로 재현하지 못했다(코드 확인만 했다).

## 12. 워커 이미지 운영 반영 (2026-11-09) — BE·FE 배포 전 선반영

종원 지시("BE/FE 배포 전에 워커 이미지 빌드와 RunPod 엔드포인트 갱신")에 따라 워커를 먼저 운영에 반영했다. 새 이미지는 기존 이미지에 태거 노드·모델과 응답 필드 `output.texts`만 더한 것이다. 그래서 현재 운영 BE(master)의 텍스트 방식 파이프라인과 호환된다.

### 커밋

- BE `f57b3b2`(브랜치 `feature/standing-gpt-paste`, `e0e7000` 위)
- FE `d2b4636`(`feature/reference-builder`)
- 워커 `9995274` + `4c3bfe0`(`feature/wd14-tagger`, 원격 푸시). BE·FE는 미푸시다.

### 빌드

- 전체 빌드(run #8)는 2분 55초 만에 실패했다. 원인은 "The security token included in the request is invalid"다. `build.yml`이 대형 모델을 AWS S3 `assets-build/`에서 받는데, 탈AWS 이후 저장소의 AWS 시크릿이 무효다. **전체 재빌드 경로는 현재 막혀 있다**(모델을 R2로 옮기고 시크릿을 갱신해야 한다).
- 그래서 증분 빌드 경로를 추가했다(`4c3bfe0`). `Dockerfile.layer`는 운영 검증 이미지 `@sha256:6a9d54d6…` 위에 태거 노드·모델·핸들러 패치만 얹는다. `build.yml`에는 선택 입력 `dockerfile`·`base_image`를 추가했고, 레이어 빌드일 때는 AWS/S3 단계를 건너뛴다. 기본 동작은 바뀌지 않는다.
- run #9는 성공했다(약 15분). 태그는 `reference-tagger-20261109-4c3bfe0`, 다이제스트는 `sha256:902c4bd2…`(amd64 `406e9e3f…`, 26.7GB)다. 빌드 안에서 부팅 검증(IMPORT FAILED 0건·WD14 노드 로드·onnxruntime import)과 핸들러 앵커 검사를 통과했다.

### RunPod 전환 (UGC만)

- 기준선: 엔드포인트 `lucid-ugc`(min 0 / max 1, CUDA 12.6, version 9), 템플릿 `zouv4t0soq`, 이미지 `@sha256:6a9d54d6…`.
- 템플릿 `cj4es4ls3f`(`lucid-ugc-reference-tagger-20261109`)를 새 이미지 다이제스트로 만들었다. 디스크 40GB·포트·SSH·Jupyter 설정은 그대로이고, env 3개는 해시 대조로 동일함을 확인했다.
- 엔드포인트 templateId만 PATCH했다(version 10). 그 외 설정은 불변이다. 씬 엔드포인트는 건드리지 않았다.
- **롤백**: 엔드포인트 templateId를 `zouv4t0soq`로 되돌린다(기존 템플릿 보존).

### 실호출 스모크 (커밋 제외 하네스 `StandingPipelineLivePoc.liveWorkerSmokeRefineAndTagger`, GPT 미호출)

- 실행 워커가 새 다이제스트 `902c4bd2…`임을 확인했다.
- 원 얼굴 되붙이기 WF-2(기존 노드 전부 + 새 노드 4개): 이미지 1장, 실행 38초, 대기 145초(새 이미지 콜드스타트). 육안으로 안대·복장·트윈테일이 정상이다.
- WD14 태거: `output.texts` 1건, 실행 4.4초. `IdentityTagFilter` 통과 후 35개 태그(`medical eyepatch`·`twintails` 등)가 나왔고, `UgcComfyClient.getTextStatus` 파싱 계약과 일치한다.
- 엔드포인트 min은 바꾸지 않아 복원할 항목이 없다. 큐는 0이다.

### 남은 것

- BE·FE 운영 배포(종원 승인 후 일괄). 워커 브랜치를 master에 병합할지도 정해야 한다.
- 운영 실 E2E(업로드→원화→태거→스탠딩→감정)는 BE·FE 배포 뒤에 한다.
- 모델 원천을 S3에서 R2로 이전하고 GHA 시크릿을 갱신한다(전체 빌드 복구).

## 13. 운영 E2E 장애 — 누끼 단계 lost update 핫픽스 (2026-11-09)

### 증상

종원의 운영 E2E 잡 51(그림 방식)이 마지막 누끼 단계에서 멈췄다. 감정 12컷 중 EX_05·EX_06만 `CUTTING`에 남았다. 30분 뒤 스테일 스윕이 "누끼 요청 접수를 확인하지 못했어요"로 잡 전체를 실패시키고 22E를 환불했다(20:01:59 UTC).

### 원인 (운영 로그로 확정)

- 누끼 12건은 19:27:31~48에 순차 제출됐다(컷마다 예약 → RunPod 제출 → 요청 번호 기록). 워커가 이미 떠 있어 첫 웹훅(NEUTRAL)이 19:27:39에 도착했는데, 이때 아직 나머지 컷을 제출하는 중이었다.
- open-in-view(기본 on) 아래에서 웹훅 요청 스레드는 TX 밖 `findById`로 잡을 먼저 적재한다. 이어진 `@Lock(PESSIMISTIC_WRITE) @Query` 잠금 조회는 행 락만 잡고 **이미 관리 중인 낡은 엔티티를 그대로 돌려줬다**. 그 TX가 `external_jobs_json`을 통째로 다시 쓰면서, 그 사이 기록된 EX_05·06의 요청 번호가 지워지고 예약만 남았다.
- 이후 EX_05·06의 웹훅은 요청 번호 불일치로 조용히 무시됐고, 폴러에는 폴링할 키가 없었다. 결국 스윕이 "예약만 있고 번호가 없다"는 상태를 보고 잡 전체를 실패·환불했다.
- 이 결함은 이번 배포에서 생긴 것이 아니라 동적 감정(10/6)부터 있었다. 워커가 미리 떠 있어 결과가 제출 루프와 겹칠 때만 드러난다.

### 수정 (`9b2cc2d`, master 배포)

- **근본 수정**: `FreshLock` — 캐릭터·월드 잡의 `findByIdForUpdate` = `find` + (TX 안이면) `flush` + `refresh(PESSIMISTIC_WRITE)`. Spring Data fragment로 바꿔서 호출부 40여 곳은 그대로 둔다.
- **누끼 예약 회수**: 120초 넘게 번호 없이 남은 예약은 넘겨받아 그 컷만 재제출한다(컷 재시도 예산을 쓰고, 소진될 때만 실패·환불). 늦게 온 원래 요청은 예약 불일치로 기록되지 않고, 그 웹훅도 버려진다.
- 독립 검토(새 컨텍스트) 결과 차단 이슈는 0건이었다. 지적 3건은 반영했다.
  - 누끼 단계 진입 시 재시도 예산을 0으로 초기화한다. 기존에는 감정 생성과 같은 카운터를 써서, 생성에서 예산을 소진한 컷은 누끼 첫 실패에 곧바로 잡 전체가 실패했다.
  - 최초 누끼 루프에서 한 컷의 제출 예외가 잡 전체를 죽이지 않게 하고, 컷 단위 재시도로 넘긴다.
  - 전송 후 애매하게 실패한 경우 예산을 한 번만 차감한다.
- 테스트:
  - `JobLockFreshReadTest`(H2): OSIV 낡은 읽기를 재현한다. 종전 쿼리가 낡은 값을 돌려준다는 것도 함께 단언해, 원인 분석을 고정한다.
  - `DynamicExpressionPipelineTest`: 누끼 회수 케이스 6건.
  - 전체 413건 중 실패 2건은 master와 동일하다.

### 남은 것

- `UserRepository`·`UgcWorldRepository`·`ChatRoomRepository`의 `findByIdForUpdate`도 같은 `@Lock @Query` 패턴이다. 요청 스레드가 같은 엔티티를 먼저 읽으면 같은 lost update가 생길 수 있다(예: TTS 에너지). `FreshLock` 확대 적용이나 `open-in-view=false` 검토는 후속 과제다.
- H2 테스트는 실제 락 대기는 검증하지 않는다. 운영 재E2E로 확인한다.
