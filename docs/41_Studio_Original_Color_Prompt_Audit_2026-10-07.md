# UGC 원화 색감 회귀와 프롬프트 조사

관측·기록일2026-10-07, 운영 조회20:38–20:43 KST. 출처: 사용자 최소worker1 임시복구/원화색감 보고, 운영컨테이너/JAR 읽기, DB GOLDEN_SNAPSHOTS, 최근원화8장·패치전원화2장, 현재worker로그608행, `b0f8f1f` diff. 이번 요청은 분석이며 이미지/LLM 추가 생성·운영 수정·배포는 하지 않았다.

## 확인한 모델과 지시

태그 생성에 요청하는 모델은 **`openai/gpt-5.6-sol`**, OpenRouter `/chat/completions`이다. Stage0 전용 `ugc.stage0-model`이 전역채팅Gemini 설정보다 우선한다. 운영JAR의모든application프로필과컨테이너환경/명령 인자를 대조했으며 Stage0 override/외부Spring설정은 발견되지 않았다. 초기생성/외형변경리롤 모두 temperature0.7·초기max_tokens8192·json_object·비스트리밍 호출이다. 일반리롤은LLM재산출 없이 저장태그를 재사용한다. OpenRouter응답의 실제 served model/provider 영수증은 저장되지 않아 요청모델과 공급자의 내부라우팅을 구별한다.

운영SYSTEM_PROMPT는 ‘일본 서브컬처2D 애니메이션 일러스트’, ‘사진·실사·3D렌더·Disney/Pixar 태그 금지’, ‘조명은일러스트의색·명암연출’ 지시다. appearance40–60, persona5–8, scene10–20개를 요청하며 **scene에는 배경·소품·조명·구도를 자유롭게 제안하도록 한다**. 자연스러운피부/의상발색·색조명선택조건은 없다. 외형변경리롤의system은2D방향을명시하지만 scene10–20개 재생성을 요구해 의상을바꿀때씬/조명도변한다. bg_color의저채도팔레트는Neutral용이며원화positive에들어가지않는다.

이미지를 그리는 모델은 별도로 `wai_illustrious.safetensors` 체크포인트 + `detail_lora.safetensors`(model0.6/clip1)이다. 원화는1024²/euler/simple/30step/CFG4, FaceDetailer20step/CFG4/denoise0.4를 사용한다. Qwen은Neutral편집, GPT Image2.5는감정편집이며이원화의직접생성모델이아니다. 실제worker weight파일의버전/해시동일성은미확인이다.

## 이전 패치의 정확한 변경

`b0f8f1f`는 (1) 초기/외형리롤LLM에2D지시 추가, (2) 원화positive에 `anime illustration, 2d, cel shading` 공통강제, (3) 명시사진·3D 태그 정확일치 제외, (4) 원화negative에 `3d, cgi, photorealistic, photo, disney, pixar` 추가다. 체크포인트/LoRA/샘플러 교체와색보정/LUT 노드 추가는없었다. 이전 docs/38은 실제새원화 품질A/B미실행을기록했다. 생성샘플검증없이cel shading을전캐릭터에강제한부분은성급했으며 다음수정전실제대조가필요하다.

## 실제 배치와 색감

최근job25=2장, job26=초기2+외형리롤2+외형리롤2로8장이다. 배치별snapshot startIndex0/2/4를자산인덱스와대조했다. 패치전job23의2장도현재concept가아닌마지막startIndex0 snapshot에연결했다. 전체10=최근8+이전2,배치분해합10·누락0·중복0이다. 원본PNG는전부RGB1024²이며prompt/workflow/ICC메타데이터가없다.

| 배치 | LLM이 만든 씬 태그 중 관련 값 | 원본 PNG 관측 |
|---|---|---|
| job25 0–1 | cool rim lighting, purple accent lighting, soft cel shading, dramatic color contrast | 피부와실내가진한파랑/보라·강한대비 |
| job26 0–1 | spotlights, colored lighting, rim lighting, dramatic shadows, neon accents, city lights | 녹색/청록피부·빨강빛대비 |
| job26 2–3 | ceiling lights, rim lighting, depth of field | 초록피부·청록발색·강한명암 |
| job26 4–5 | evening, rim lighting, soft lighting, depth of field | 피부청록/녹색·옷/윤곽주황대비 |
| 패치전job23 0–1 | soft rim lighting, warm backlighting, shallow depth of field | 입체음영/광택은강하나같은극단적인발색은없음 |

최근8장 모두원본파일에강한색감이있어화면필터가원인이아니다. 두최신빌드의user input은동일한아이돌연습생성격/관계설정이며네온/색조명/색필터를요구하지않는다. LLM이직업·분위기를확장해색조명을추가했다. 해당8장의복원positive에서는새사진/3D제외목록에걸리는태그가0개라, 이최근배치들에서제외필터자체가태그를삭제한것은아니다.

강한색조명태그는명백한개선대상이지만직접colored/neon태그가없는후속배치도색감이강하다. 따라서‘보라조명태그하나때문’으로전체현상을단정하지않는다. 공통스타일추가(특히cel shading)·새negative·checkpoint/LoRA 상호작용이우선비교가설이다. 패치전후비교는seed/외형/worker가통제된실험이아니며특정태그의인과증명이아니다.

## 증거와 한계

실제운영JAR SHA256 `6ea787c479c18d49d51f2060f63283c7fe31cacfb5de23da110b89cef6a51c95`. 구조화/워크플로클래스는로컬compiled class와hash일치,WF1JSON도전체내용일치했다. PromptAssembler 전체classhash는다르지만원화관련6메서드의disassembly는constant-pool 참조번호정규화후일치했다(전체클래스동일성으로표현하지않음). [대조증빙](41_assets/prompt-audit-proof.json).

전체시스템지시문·배치별positive/negative와이미지는Git제외 로컬 [조사보고서](../tools/tts/.local/incident41-prompt-report.md)에 있다. HTTP제출payload원본이나과거seed는저장되지않아 **제출시점snapshot+실행코드로복원한프롬프트**와원본요청영수증을구별한다. 캐릭터raw설정/전체산출·키·원래요청ID는정본증빙에넣지않았다.

사용자가변경한UGCmin1/max1·worker1·queue0/completed1247을20:42에읽기로확인했다. 현재worker는RTX4000Ada이며기동11:29:03UTC,로그608행에Exception/OOM는관측되지않았다. 로그만으로VAE/weight나색감의정확성을배제하지않는다. [설정/health관측](41_assets/runpod-observation.json). 사용자설정을임의복원하지않았다.

## 다음 검증과 수정 방향

동일저장concept·새고정seed·같은worker/image/LoRA/샘플러/FaceDetailer에서 ‘스타일추가전positive/현재positive’×‘추가전negative/현재negative’4셀을1장씩비교한다. 과거seed미보존이므로옛이미지동일seed재현이라고표현하지않는다. 이로스타일추가와negative추가를분리하고필요시cel shading단독/색조명단독을더대조한다. 프롬프트대조에서도남으면실weight hash·LoRA미적용·샘플러/VAE를순차점검한다.

수정후보는cel shading공통강제를제거/완화,LLM의scene연출에자연스러운피부/의상색·일관된조명을기본으로하고색조명은명시요구/의도된장면에서만선택,외형변경리롤에서씬을기본보존하는것이다. 외형40–60+persona5–8+scene10–20의태그수를무조건채우게하는설계도불필요한연출누적을검토할대상이다. 모델교체·조명태그일괄차단·negative추가누적을검증없는해법으로확정하지않는다.

이번은읽기분석과문서화다. 새이미지품질A/B·원화프롬프트수정·worker실weight hash확인·별도컨텍스트독립검토는미실행이다. 기존docs40후속수정·UIUX23·디오라마·실험도구미커밋작업을보존했다.

## 2026-10-07 후속 실험

위 미실행은 이 조사 당시의 상태다. 이후 사용자 승인으로 [태그 단순화 A/B](42_Studio_Simple_Tag_AB_2026-10-07.md)를 실제 실행했다. 2LLM·8원화+후처리전8 완료·독립검토를 기록했다. 같은태그/seed에서도현행조립4/4이상발색·추가강제없는4/4자연색으로, 서버 조립 묶음이 회귀에 기여한다는 통제 근거가 생겼다. cel shading 단독/negative 단독 대조·제품 수정·workerweight 해시·배포는 여전히 미실행이다.
