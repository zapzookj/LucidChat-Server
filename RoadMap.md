# Lucid Chat Development Roadmap (Revised - Phase 4 Completed / Phase 5 Redefined)

## Phase 1 & 2 (Complete)
**MVP 구축 및 기반 아키텍처 정립**
- Spring Boot / React 기반 기본 아키텍처
- 인증 / 보안 시스템
- 나레이션 엔진 기초 구현

---

## Phase 2.5 (Complete): **The Show**
**베타 배포 및 몰입 요소 강화**
- AWS 베타 배포 및 시크릿 모드 도입
- 선택형 이벤트 시스템
- 시네마틱 인트로 연출 및 프롬프트 엔지니어링 (Reasoning 중심)

---

## Phase 3 (Complete): **The Brain**
**지능과 백엔드 성능 최적화**
- RAG (Vector DB) 기반 장기 기억 구현
- 트랜잭션 분리 및 Redis Caching 성능 튜닝
- 프론트엔드 Typing Streaming (SSE) 도입

---

## Phase 4 (Complete): **The Soul**
**극강의 몰입감, 게이미피케이션 및 세계관 확장**
- **Cinematic Ending System:** 호감도 기반 동적 엔딩(해피/배드) 연출 및 회고 로직
- **Easter Eggs & Achievements:** 하이브리드 트리거 기반 특수 씬 및 훈장 시스템
- **Two-Track Architecture:** 샌드박스(자유도/저비용) vs 스토리(서사/고비용) 모드 분리
- **Lobby System:** 다중 캐릭터 및 모드 진입을 위한 허브(로비) UI (Lucid Station) 구축
- **Character Pipeline:** 프롬프트 다이어트 적용 및 신규 캐릭터 3종 에셋/페르소나 양산 완료
- **Polishing:** RAG 장기 기억 과적합 보정 및 무한 스크롤 도입

---

## Phase 5 (complete): **The Business (비즈니스 및 운영 환경 안정화)**
**수익화 시스템(BM), 보안 체계 구축 및 차세대 파이프라인 준비**
- **Monetization & Economy (수익화 및 경제)**
  - Dynamic Energy Economy: LLM 비용 비례 에너지 차감 결제(PG) 연동 및 리워드(AD 등) 체계
  - Tiered Subscriptions / Premium: 구독 기반 프리미엄 기능 (특수 페르소나, 스킨, 무제한 에너지) 및 특수 재화 도입
- **Safety & Verification (안전 및 인증)**
  - Age Verification (성인 인증): NICE/KCB 연동 및 API Gateway 단 시크릿 모드 진입 차단 기능
  - Content Moderation (필터링): 금지어 확장, OpenAI Moderation API 등 연동 및 어뷰징 관리
- **Data & Evaluation Pipeline (데이터 및 평가 파이프라인)**
  - RLHF Feedback System: 유저 응답 평가 (좋아요/싫어요) 로깅 인터페이스 및 비동기 수집 로직
  - Log Export Pipeline: 추후 파인튜닝용 대화/피드백 데이터 ETL(추출/정제) 파이프라인 기반 마련
- **Security Enhancements (보안 강화)**
  - Anti-Prompt Injection: 악성 지시어/탈옥 패턴 감지 방어 시스템 (`PromptInjectionDetector`) 고도화
  - System Prompt Encapsulation: 유저 입력과 시스템 프롬프트의 완전한 논리적 분리 및 보호벽 설치

---

## Phase 5.5 (In progress): **The Dopamine**
**몰입과 쾌락의 극대화**
- Session 1 (Complete)
  - **입체적 상태창 (Biometric UI)**
    - 기존의 평면적인 호감도를 넘어 시각적 타격감을 주는 스탯 추가.
    - 심박수 (BPM): 대화 텐션에 따라 60~180 사이로 변동하며, 프론트엔드 심장 아이콘 애니메이션 속도와 동기화.
    - 기타 재밌는 스탯 추가
    - 호감도에 따른 정적인 관계 텍스트(지인, 친구, 연인 등)가 아닌, 대화 내용과 감정에 실시간으로 반응하는 '동적인 관계' 구현 (관계 승급 이벤트마다 갱신)
    - 5번의 대화 핑퐁마다 캐릭터의 유저에 대한 생각을 짧은 텍스트로 요약하여 보여주는 '캐릭터의 생각' 기능 도입 (예: "이 유저는 정말 재미있어 보여!", "조금 지루한 대화였어.")
  - **속마음 시스템 (Inner Thought)**
    - 구현 방식: 매 턴 LLM Output JSON에 inner_thought 필드를 분리하여 응답받음.
    - UX/BM: 프론트엔드 대사 아래에 블러(Blur) 처리된 버튼 배치. 클릭 시 에너지(-1)를 소모하여 캐릭터의 진짜 속마음 텍스트 언락(Unlock).
- Session 2 (Complete)
  - **이벤트 시스템 고도화**
    - 기존 서비스의 '단조로운 1:1 대화 핑퐁(Ping-pong)'에서 오는 유저의 피로도와 지루함을 극복하기 위해 기획.
    - 기존 이벤트 시스템의 단조로운 상황 연출, 유저가 항상 텍스트를 입력해야만 스토리가 진행되는 구조를 탈피하도록 아키텍처를 재설계.
    - topic_conclude 트리거 필드 도입: - LLM이 뱉어내는 JSON 아웃풋에 topic_conclude (boolean) 필드를 신설. LLM 스스로 현재의 대화 주제나 상황이 자연스럽게 마무리되었는지를 판단하게 함.
    - 관찰자 시점 UI : '계속 지켜보기' 버튼, '시간 넘기기' 버튼 구현.
    - 디렉터 모드 전용 파이프라인 : 추후 다인큐 채팅 및 디렉터 모드 구현에 활용될 유연한 LLM Output 파이프라인 구축.
  - **캐릭터 전용 LoRA 파인튜닝 모델 생성**
    - 각 캐릭터의 이미지를 학습시킨 파인튜닝 LoRA 모델을 생성하여, 대화 맥락에 따른 실시간 일러스트 생성 기능 구현 준비.
  - **캐릭터 이미지 에셋 업데이트**
    - LoRA 파인튜닝 모델을 기반으로, 캐릭터 스탠딩 일러스트 업데이트
    - 기존 에셋 업데이트 + 복장(수영복, 데이트룩) 추가 + 감정(황당함, 삐짐, 간절함) 추가
- Session 3 (Complete)
  - **프롬프트 캐싱 (LLM 호출 비용 최적화)**
    - 기존 시스템 프롬프트를 정적인 룰 + 동적인 룰 + 대화 기록 구조로 분리
    - cache_control 필드를 추가하여 정적인 룰을 캐싱
  - **SSE 도입을 통한 체감 응답 속도 최적화**
    - SSE 스트리밍을 통한 첫 씬 선행 추출 로직 구현
    - 첫 씬이 도착하는 즉시 프론트엔드에 전달하여, LLM의 나머지 응답이 도착하기 전에도 첫 씬부터 바로 렌더링 시작
    - 체감 응답 속도 약 40% 이상 개선
- Session 4 (Complete)
  - **실시간 이미지(일러스트) 생성 기능 (Complete)**
    - LoRA 파인튜닝 모델을 활용해, 대화 맥락에 따른 실시간 일러스트 생성 기능
    - Fal.ai 연동
  - **장소 전환 시스템 고도화 (Complete)**
    - 기획 변경 : 정적 하드코딩 배경(정적인 배경 일러스트 에셋 활용)의 제한된 장소 전환에서 벗어나, LLM이 대화 맥락에 따라 새로운 장소를 자유롭게 생성할 수 있도록 시스템 재설계.
    - 대화 맥락에 맞는 장소 일러스트를 Fal.ai API를 통해 실시간으로 생성하여, 대화의 몰입감과 다양성 극대화.
    - 비용/속도 방어선
      - 생성된 배경은 서버 DB/S3에 영구 적재(자산화)하여 이후 동일 장소 이동 시 API 호출 없이 캐시 로드.
      - 잦은 배경 전환을 방지하기 위한 추가 로직(시스템 프롬프트 등) 필요
    - Latency Masking: 프론트엔드에서 SSE 이벤트를 인터셉트하여, 배경이 생성되는 3~5초 동안 "OO로 이동 중..." 애니메이션을 띄워 로딩을 연출로 승화시킴.
  - **다인큐 채팅 / 하렘 그룹 채팅 (Lucid Lounge) - 보류 결정**
    - 진입 조건: 특정 캐릭터와의 호감도 70 이상 달성 시 '라운지 초대장' 획득.
    - 세계관 설정: '자각몽(무의식의 방)'이라는 컨셉을 통해 서로 다른 세계관의 캐릭터 간 크로스오버(뇌절) 허용.
    - UX/UI: 최대 3명 참여. 발화자(Speaker)의 스탠딩 일러스트가 밝아지고 커지는 '동적 스포트라이트' 연출.
  - **디렉터 모드 (커스텀 조연 소환) - 보류 결정**
    - 대화창에 @이름 (예: @깐깐한 점원) 입력 시, 시스템 프롬프트 지시를 통해 LLM이 메인 캐릭터와 조연의 1인 2역을 즉석에서 수행. 별도 리소스 없이 샌드박스 자유도 극대화.
    - 대화창에 *상황 (예: *갑자기 비가 내리기 시작함) 입력 시, 나레이션 메시지로 상황 설명이 추가되어 대화 몰입감 극대화.
  - **키네틱 타이포그래피 (Kinetic Typography) - 보류 결정**
    - 구현 방식: 감정이나 상황에 따라 텍스트 자체에 CSS 애니메이션 부여 (예: [SHAKY] - 떨림, [SHOUT] - 굵고 흔들림). 비용 없이 텍스트의 시각적 타격감을 부여.
- Session 5 (Complete) — **Theater Mode**
  - **기획 의도**: 기존 1:1 대화 핑퐁의 피로감 극복. 유저가 *감독*이 되어 *시스템(액터)*을 지휘하는 자동 진행 비주얼 노벨. 매 턴 입력 부담 X, 배치 단위로 LLM이 4~12개 씬을 생성해 자동 재생.
  - **서사 구조**: World → Act (3) → Chapter (가변) → Batch → Scene. Act 사이 인터미션(스탯 분배), Chapter 끝에 회고 모달, Act 끝에 CLIMAX 분기. 다중 엔딩(메인/서브/배드/하이든).
  - **배치 단위 LLM 호출**: SSE 스트리밍으로 첫 씬을 즉시 추출 → 나머지는 백그라운드 적재. 체감 latency 마스킹.
  - **분기 시스템**: LOCATION (장소 선택, 별도 trigger) / MINOR (대사 톤) / MAJOR (관계 변화) / CLIMAX (Act 종료 후 다음 방향). 결정론적 빈도(폴리싱 7에서 도입)로 일관된 리듬 보장.
  - **감독 노트 + 인터미션 + 난입(Intervention)**: 유저가 적극 개입할 수 있는 3가지 채널. 인터미션은 Act 사이에 스탯 ±N 분배 + 도구 사용. 난입은 일종의 챕터 끝 미니 이벤트.
  - **2단 모델 라우팅**: 일반 배치는 fastModel(저비용), CLIMAX 분기와 엔딩 생성만 proModel(고품질).
  - **데이터 모델**: TheaterState (한 방당 1개), TheaterSceneLog (Mongo 영구 저장), TheaterDirectorNote, TheaterHeroineAffection, TheaterBranchChoice, TheaterSaveSlot.
  - **UI**: TheaterPortalPage(시네마틱 극장 입구) → TheaterCreateFlow(4-step) → TheaterPlayPage(자동 진행 + 컨트롤) → TheaterIntermissionPage → TheaterEndingCredits.
  - **유저 테스트 결과**: 진행 방식의 신선함은 호평, 다만 *수동성* 우려 + 분기 빈도의 일관성 부족 + 시각 자산 미흡 + 무료 유저의 가치 제안 미노출 + 활성 극 1개 정책의 거부감 → Polish 6 + Polish 7로 대응.
- Polish Session 6 (Complete) — **유저 테스트 1차 피드백 대응**
  - 시네마틱 톤 강화 (TheaterDoorway, TheaterPortalPage)
  - 감독 노트 패널 신규 (수동 메모 CRUD)
  - 챕터 종료 회고 모달 / 분기 모달 / 세이브 슬롯 패널
  - 2단 모델 라우팅 (TheaterModelResolver)
  - 인터벤션-ChatStream 통합
- Polish Session 7 (Complete) — **유저 테스트 2차 피드백 대응 (6 라운드)**
  - **R1 진행 방식**: protagonist_inner / heroine_inner 스키마 분리 + scene_type. Speaker Anchoring 강화. 화자 모호성 해소.
  - **R2 분기 빈도 재설계**: 결정론적 빈도(LOCATION 0~1 + MAJOR Chapter당 1회 + MINOR 결정론 3~4회 + CLIMAX Act 끝 1회). MINOR는 인라인 슬라이드업으로 흐름 보전.
  - **R3 감독 명령어 시스템 (V2 신의 명령)**: 유저가 환경/NPC/음향/사물에만 영향을 주는 권능. 캐릭터 직접 조작은 차단(룰 + LLM 분류기). 1배치당 1회, 시크릿 모드 분기, 거부 사유까지 영구 보관(유저 학습). UI는 좌측 명령어 + 우측 다이어리로 분리. 마지막 씬 도달 시 펄스 알림.
  - **R4 극 초기화 (모델 C-2)**: 활성 1 + 아카이브 N + 중단 시 ARCHIVED 보존(resume 가능) + 엔딩 시 ENDED 영구 완결. 새 극 시작 시 활성극 자동 archive (409 Conflict + confirm 모달). 새로운 TheaterArchivePage.
  - **R5 스탯 락 화면**: 무료 유저도 step 4 항상 표시 + 자물쇠 인디케이터 + 무료 vs Pass 비교 카드 + Lucid Pass CTA.
  - **R6 일러스트 + 백그라운드 prefetch + 자동 노트**: 배치 도착 시 location 변화 prefetch (cache miss 시 generateBackgroundAsync). AUTO_MOMENT(호감도 ±2) / BRANCH_TAKEN / CHAPTER_END 자동 캡처 + 일러스트 동기 생성. UserIllustration.linkedNoteId로 노트와 cross-reference → 다이어리가 시간이 지남에 따라 사진첩화.
  - **산출물**: 백엔드 신규 4 + 변경 15 + 프론트 신규 3 + 변경 10 = 36 파일. 핵심 사양 47/47 검증 통과.


---

## Phase 6: **The Foundation**
**운영 관리 고도화 및 폴리싱**
- SFX(효과음) 보강 작업 - (Complete)
- 서비스 품질 최종 점검 및 폴리싱 - (Complete)
- **실시간 일러스트 생성 인프라 피벗 (Complete)** — `Phase6_IllustrationPivot.md` 참조
  - **배경 (Context)**: Phase 5.5 Session 4에서 채택한 자체 GPU(RunPod Serverless + ComfyUI) 환경이 (a) ComfyUI 태그 번역기 튜닝의 무한성, (b) 콜드 스타팅 vs 비용 누수 딜레마, (c) 런칭 전 단계의 over-engineering이라는 3대 문제를 노출. *LLM에 OpenRouter라는 B2B를 쓰듯, 이미지 생성에도 B2B를 쓰는 자연스러운 순서*로 회귀.
  - **핵심 결정 — 두 트랙 분기**: 단일 게이트웨이 단일 모델로 통합하려던 1~3차 가설은 모두 실패. 사유는 다음과 같음.
    - Fal Flux 2 Pro는 LoRA 미지원 (BFL 라인업 분기). Flux 2 Dev는 LoRA 가능하나 SDXL LoRA와 비호환(베이스 모델 의존성).
    - SDXL anime 생태계가 화풍·LoRA 자산에서 압도적. 그러나 Fal.ai의 SDXL 엔드포인트(`fal-ai/lora`)도 BFL Flux 라인과 동일하게 *입력 프롬프트 단계의 NSFW 모더레이션*이 강제됨 (`enable_safety_checker: false`는 출력 후처리만 비활성화). **NSFW가 우리 핵심 BM(Secret Mode)인 한 Fal.ai 단독은 캐릭터 트랙 부적합**.
    - Flux PuLID(`fal-ai/flux-pulid`)는 InsightFace 기반 face detection이 anime 캐릭터를 인식 못 함(`facexlib align face fail`). SDXL의 IP-Adapter / IP-Adapter-PuLID도 동일한 face detection 한계 또는 *post-hoc bolt-on*의 vibe transfer 수준에 머무는 한계. 우리는 ComfyUI 시기 이미 이 한계 직접 검증.
  - **확정 스택**:
    - **캐릭터 일러스트 = ModelsLab + 상업 친화 anime SDXL base + LoRA Stack**
      - ModelsLab은 "Uncensored AI API"를 정식 제품 라인으로 보유 (NSFW가 ToS 차원 운영 카테고리), "CivitAI Alternative" 라인으로 임의의 SDXL 체크포인트·LoRA 임포트 공식 지원, NSFW 모더레이션은 클라이언트 옵트인.
      - 베이스 모델 후보: NoobAI-XL / Illustrious-XL 본가 / Animagine XL (Nova Anime XL은 라이선스 grey zone으로 production 사용 중단). CTO PoC로 최종 선정.
      - LoRA Stack: 캐릭터별 정체성 LoRA(기존 자산 호환 시 재사용 / 비호환 시 재학습) + Detail/Quality enhancer + (옵션) Style LoRA.
    - **배경 일러스트 = Fal.ai + Flux 2 Dev**
      - PoC에서 anime 화풍 prefix(`"High-quality 2D anime visual novel background art..."`) + 자연어 prompt 조합으로 검증된 화풍 산출 확인.
      - Flux 2 Dev: Pro 대비 60% 저렴($0.012/MP), LoRA 지원, 충분한 레이턴시, 오픈 웨이트(폴백 경로 확보).
      - Secret Mode NSFW 분위기 배경은 ModelsLab으로 라우팅 분기.
    - **태그 번역기 통째 제거**: 자연어 prompt 활용으로 ComfyUI 태그 번역 로직 폐기 → 큰 코드 감량.
  - **코드 마이그레이션 범위**: `FalAiClient`(현 RunPod 호출) → 진짜 Fal.ai 호출로 재구성 + 신규 `ModelsLabClient`/`ModelsLabWebhookController`/`ModelsLabProperties`. `IllustrationService`(캐릭터 → ModelsLab) / `BackgroundGenerationService`(배경 → Fal.ai + Secret Mode 분기). `IllustrationPromptAssembler` / `BackgroundPromptAssembler` 재설계. S3 캐싱 / `BackgroundCache` / `UserIllustration.linkedNoteId` 영속화 로직은 최종 URL 기준이라 변경 없음. RunPod ComfyUI 워크플로우와 Base64 추출 4단계 전략(~150줄) 폐기.
- 백오피스(Admin) 구축
- 로그 처리 및 모니터링 시스템 구축 (PGL 스택)

> **우선순위 변경**: RunPod 비용 누수의 즉시 차단을 위해 일러스트 마이그레이션을 백오피스/모니터링보다 앞에 배치.

---

## Phase 7: **The Production**
**공식 론칭 및 스케일업**
- 공식 MVP 프로덕션 런칭 및 초기 트래픽 방어 테스트
- 바이럴 마케팅 (숏폼 밈 콘텐츠 배포 등)
- AWS 인프라(Auto Scaling) 및 LLM 프롬프트 캐싱 최적화
- 웹, 모바일 앱, 콘솔(스팀) 등 멀티 플랫폼 지원 및 UX/UI 개선

---

## Phase 8+ (Long-Term Vision)
**독자 생태계 및 모델 파인튜닝**
- **UGC Pipeline:** 유저 텍스트 커스텀 기반의 캐릭터 자율 생성 생태계 마련
  - **R&D Backlog — 캐릭터 일관성 (LoRA-free)**: Phase 6 피벗 과정에서 분리된 R&D 트랙. *유저가 reference 이미지 1장만으로 일관된 캐릭터 생성 + LoRA 학습 비용·시간 없이 + anime 화풍 유지 + NSFW 호환*이라는 5중 교집합 검증. 후보 옵션: (X) Flux 2 native multi-reference — 학습 단계부터 multi-ref consistency를 task로 학습한 native 기능, ModelsLab 등에서 NSFW 호환 접근 가능, anime 실증 미검증. (Y) Hunyuan Image 3.0 — anime 친화 + 무검열 평가, 검증 필요. (Z) Auto-LoRA 백엔드 — 등록 시 5~10분 백그라운드 학습 후 무한 일관 생성, 검증된 기술. (Hybrid) 초기 multi-ref → 백그라운드 LoRA 학습 → 자동 스위치. *Production 마이그레이션과 시간·자원 분리, frontier 영역이므로 도구 성숙 후 재평가*.
- **Custom Fine-Tuned LLM:** 누적된 RLFH 데이터를 바탕으로 오픈소스 LLM (Llama 등) 기반 독자적 미연시 특화 모델(Lucid-Model) 구축 및 전환
- **Self-Hosted GPU Inference:** DAU 확보 및 트래픽이 자체 인프라 단가를 정당화하는 시점에 자체 GPU 환경 재구축 (현 시점 ModelsLab/Fal.ai B2B 의존에서 점진 탈피, Phase 8 비전과 정합)