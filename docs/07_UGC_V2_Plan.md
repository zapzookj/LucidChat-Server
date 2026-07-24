# 07. UGC V2 계획 · UGC V1 마감 (2026-07-24)

> UGC V1 세션 마감 문서. V1에서 무엇을 끝냈고, 이번 세션이 무엇을 확정했으며, V2에서 무엇을 할지.
> 상위 인수인계는 `04_UGC_Agent_Handoff.md`, 요약은 `05_UGC_Summary.md`. 이 문서는 V1→V2 전환점.

---

## A. UGC V1 — 완료 (2026-07-24 마감)

파이프라인·세계관 빌더·프로필·도그푸딩 패치는 04 §12-A/B/C. 2026-07-23~24 세션에서 추가로:

### 버그 수정 (전부 CI 그린 · 재시작 시 적용 · 미커밋)
- **디렉터 ID 오귀속** (V2 전체 버그): `StoryDirectorPromptAssemblerV2`의 [4]/[4-marker]/[5]/[6] 섹션에 Character ID를 안 보여줘 LLM이 순번을 ID로 사용 → 스탯/기억이 엉뚱한 캐릭터에 귀속. `c.getId()` 노출로 수정.
- **relation_transition 파싱 크래시**: DTO는 객체인데 LLM이 문자열(`"LOVER"` 등) 반환 → `MismatchedInputException` 무한루프. `LenientRelationTransitionDeserializer`(문자열→to 관용) + `RelationPromotionService` 단일 활성 자격 복원 + 프롬프트 객체 예시 3곳.
- **profile_quote 전용 필드**: V14 마이그레이션 + UGC Stage0 자동 산출 + 공식 10종 시드.

### 폴리싱 (프론트 빌드 그린 · 미커밋)
- **프로필 신상 5필드 + 전용 문장**: 공식 10종 시드 입력, UGC Stage0 자동 산출.
- **어드민 UGC 심사 화면**: `LucidChat-Admin/src/pages/UgcReviewPage.jsx`(승인 큐 + 상세 모달[설정·감정 15컷·세계관 섹션·프롬프트 인스펙션] + 3축 판정). 백엔드 심사 API 4종 활성화 → **V1 승인 관문 완성**.
- **유저 알림 프론트**: `SupportPanel` 알림 탭 + `linkType` 딥링크 + 조기 read-all 버그 수정.
- **텍스트 드래그 방지**: 유저앱 전역(`index.css`).

---

## B. 이번 세션 확정 결정 (V2 착수 전제)

### B-1. 캐릭터 LoRA 폐기 (백본에서 제거 · 옵션 인핸서로만)
UGC 빌더는 이미 캐릭터 LoRA 학습이 없다(Qwen image-edit star-topology로 15컷 일관성 유지 — 워크플로의 LoRA는 전역 `detail_lora` 하나뿐). 실시간 일러도 "확정 베이스 이미지에서 편집(i2i)" 단일 스택으로 통일 권장. 공식 4캐릭터의 수제 ModelsLab LoRA(`IllustrationPromptAssembler`)가 이원화 부채. LoRA는 공식/프리미엄 옵션으로만 남긴다.
- 유보: 편집식은 캐릭터당 정본 베이스 필요(UGC는 15컷 보유, 공식은 레퍼런스), 자세 다양성이 자유 t2i보다 좁음, Secret/NSFW 포즈는 SFW 베이스 편집으로 못 가는 영역 존재.

### B-2. 실시간 일러 ModelsLab → RunPod 재피벗
- 현행: 실시간 일러 = ModelsLab(SDXL anime) + 캐릭터 LoRA. 공식 4종만 매핑, **UGC slug는 airi 폴백 = 사실상 미지원**.
- 재피벗 마찰 낮음: RunPod 워커가 요청별 workflow JSON 수신(워크플로 어그노스틱), `wai_illustrious` 체크포인트 공유, 레거시 `RunPodComfyClient` + `runpod.enabled` 휴면 플래그가 이 회귀용으로 보존됨.
- **엔드포인트: 기술상 1개 가능, 운영상 2개 권장** — UGC(배치·수분·지연허용) vs 실시간(저지연)의 head-of-line blocking, 콜드스타트 ~3분 → 웜 min-worker 상시 GPU 비용 → **DAU 임계가 게이트**.
- 순서: 정체성 전략=S3 베이스 i2i 편집 확정 → 현 UGC 엔드포인트에 실시간 그래프 얹어 단일 엔드포인트 PoC → 트래픽 붙으면 전용 엔드포인트 분리(리빌드 없이 라우팅 분기).

### B-3. 캐릭터 외형태그 영속화 (실시간 일러 선결)
UGC `appearanceTags`(Danbooru)가 Character에 미영속(`job.structuredConceptJson`에만). `IllustrationPromptAssembler`는 공식 4slug 하드코딩 → UGC는 airi 폴백. 실시간 일러 지원하려면 Character에 `appearanceTags`(+`personaTags`/`basePose`) 컬럼 신설(profileQuote처럼 additive), `UgcPipelineWorker.bind`에서 저장. 하드코딩 맵을 DB 기반 조회로 일반화.

---

## C. V2 백로그 (우선순위)

**[P0 — 출시 직전 · V2 착수 선결]**
- E2E 관통 + flux-2 원가 실측(품질·마진)
- fal 동시성 2→10~20 / RunPod 보조워커 crash-loop(Container Disk)
- 전용 버킷 + lifecycle 30일 + S3 퍼블릭 액세스 차단 원복
- 웹훅 실환경 검증(prod `LUCID_WEBHOOK_BASE`)
- **PUBLIC 철회 API** (부적절 공개 캐릭터 즉시 내릴 수단)
- **VLM 프리필터** (공개 확대 전 이미지 안전망, PoC-5)

**[P1 — 부채·비용]**
- CDN/버킷 이원화 통일 (deriveAssetDir 휴리스틱 제거)
- ugc/jobs 중간 산출물 lifecycle (리롤 누적 스토리지 누수)
- 프로필 빈값=삭제 의미론

**[P2 — 실시간 일러 (B-1/2/3 묶음 에픽)]**
- 외형태그 영속화 → RunPod i2i 재피벗 → UGC 실시간 일러 개방

**[P2 — STORY/THEATER 개방 (3단 에픽)]**
- UGC CharacterRoutine 자동생성 (`Character.java:651` '루틴 별도생성' 주석은 실제 **미이행** — 신규 구현으로 산정)
- → World enum PK 마이그레이션 (공식/UGC 월드 통합 모델)
- → STORY/THEATER 개방
- 단독 선출시 가능: DAY/NIGHT 장소 배경 2종

**[P2 — 배경 정적-우선 배선] (도그푸딩 발견)**
- 공식 월드 정적 배경이 코드에 안 붙음: `WorldLocation`에 `background_url` 없음, `location_change`→배경 무생성. S3 `backgrounds/`는 SHA 해시(동적 캐시)뿐. 기획(정적우선+신장소만 동적)의 **정적-우선 절반이 미배선**. 수정: `ChatStreamServiceV2.processDynamicBackground`(943–1003)에 location_change→배경 브리지 + `WorldLocationRepository` 주입 + 방 진입 배경 시딩(`LobbyService.seedUgcWorldBackground`의 공식판). 캐시 `canonical_key`가 LLM 자유텍스트라 적중률 0%인 것도 결정론 키로 함께 해결.

**[P3 — 전략/R&D]**
- **캐릭터 에셋 애니메이션 (§D) — 백로그 보류 (2026-07-24 종원 결정: "시기상조"). 딥리서치 근거는 §D-1 보존, 착수 시점 미정.**
- **움직이는 에셋 + TTS 립싱크 — 함께 보류.**
- Tier2 공유 UGC · 크리에이터 이코노미
- (어드민 검수 SPA는 이번 세션에 구현 완료 → 백로그에서 제거)

---

## D. 캐릭터 에셋 확장 — 애니메이션 (신규 이니셔티브, 2026-07-24 논의 착수)

정적 PNG 스탠딩 → 생동감 있는 애니메이션 에셋. **세 경로**(성격이 근본적으로 다름):

- **(a) I2V 비디오 루프**: 기존 일러 → Image2Video → 짧은 루프 영상. UGC 스케일 자동화 가능. **블로커**: 알파 비디오 크로스브라우저(WebM/VP9-alpha=Chrome/FF만, HEVC-alpha=Safari만), 루프 심(start≠end 프레임 점프), 모바일 배터리/대역폭(채팅 내내 상시 디코드), 감정×N 저장 폭증(14감정×outfit×수천 UGC).
- **(b) Live2D 리깅**: 진짜 인터랙티브(TTS 립싱크·시선), 초경량 런타임, 감정=파라미터 블렌드(에셋 1개). **블로커**: 단일 일러 자동 리깅 미해결 → UGC 스케일 불가. 공식 소수 캐릭터만.
- **(c) 클라이언트 절차적 애니**: 알파 PNG를 메시 워프/블링크/호흡/패럴랙스로 실시간 애니(가짜 Live2D, 2.5D). 생성비 0·대역폭 0·기존 자산 재사용·알파 문제 없음. UGC 스케일 최적, 다만 표현 상한이 낮음.

**권장 티어링**: UGC=(c) 절차적 기본 / 공식=(b) Live2D 또는 큐레이션 (a) I2V / (a) 순수 비디오는 등장·이벤트 순간 한정(상시 서빙 X). **서빙 미결정 사항**: 알파 합성 방식, 모바일 배터리·데이터 예산, 감정 커버리지 범위, 인터랙티브(립싱크) 필요 여부.

### D-1. 2026 딥리서치 결과 (2026-07-24 · 24소스·교차검증 확정 18/기각 7)

반년 사이 판이 바뀐 지점(신규 실용화):
- **실시간 인터랙티브 아바타 영상이 임계 진입**: LiveTalk(arXiv 2512.23576, GAIR, 2025-12) = 512×512 단일 GPU 24.8FPS, **첫 프레임 0.33s**(기존 83s→250×), 가중치 공개(LiveTalk-1.3B). Ditto(antgroup, ACM MM 2025)=음성+정지 초상→실시간 립싱크 MP4. → 서버 스트리밍 실시간 립싱크 아바타가 이제 테이블 위(단, GPU/세션 비용).
- **음성 구동 전신 아바타 연구 실재**: OmniAvatar(arXiv 2506.18866), StableAvatar(arXiv 2508.08248, 단일 레퍼런스+음성·무한길이). "영상은 립싱크 불가"는 이제 옛말(생성-per-발화 모델 한정).
- **브라우저 메시 퍼펫워프 실툴**: Warp Studio(warpstudio.app) — 이미지 드롭→자동 경량 메시(ARAP 솔버, 스켈레톤·웨이트페인팅 없음)→**알파 WebM(VP9)·애니 WebP·PNG시퀀스 export**. 경로 (c)의 구체 툴 + 알파 서빙 경로 확보.
- **I2V가 싸고 셀프호스팅 가능**: Wan 2.2(오픈 가중치, RTX 4090 24GB 구동, 720p, **5s 클립 <9분·서브달러**, RunPod ComfyUI), LTX-Video(4090에서 5s를 **~4초**·준실시간), 가격 예시 Kling 3.0 $0.084~0.168/s·Runway Gen-4.5 $0.12~0.25/s·LTX-2.3 $0.06/s(오픈). → 캐릭터당 idle 루프 1개 사전생성은 우리 RunPod로 경제성 성립.

바뀌지 않은 것(제약 유지):
- **단일 플랫 PNG→Live2D 자동 리깅은 여전히 미해결**: Live2D Cubism '디포머 자동생성'은 **레이어 분리된 PSD(ArtMesh)** 전제 — 플랫 PNG 1장은 ArtMesh 1개라 리깅 불가(docs.live2d.com). 단일 이미지 리깅은 연구(ACM MMAsia'21)만. → (b) Live2D는 UGC 자동 스케일에 아직 부적합(선행 레이어 세그멘테이션 필요).
- **LivePortrait는 얼굴/머리만**(몸통 정지, 네이티브 음성 립싱크 없음, 4090 67FPS). 전신 스탠딩엔 부적합.
- **알파 비디오 크로스브라우저 분단 지속**: VP9-alpha=크롬/FF, HEVC-alpha=사파리. 상시 스탠딩엔 WebGL 캔버스 합성(=경로 c)이 이 문제를 원천 회피.

**기각된 소문(사용 금지)**: "Wan 2.7 Apache 오픈"(허구 — 오픈은 2.2까지, 2.5/2.6은 API전용), "LTX 5s=90초"(실제 ~4초), "Hunyuan Avatar 40GB+ 필수"(실제 24GB, TeaCache로 10GB), "Wan 2.2 I2V 실험적"(2025-07부터 프로덕션).

**정제 추천**: UGC 기본 = 경로(c) 브라우저 절차적(Warp Studio식 메시워프 or 런타임 WebGL) — 생성비 0·알파문제 회피. UGC 강화(선택) = idle 루프 1개만 Wan 2.2/LTX 사전생성(감정×14 전부 X)→애니 WebP. 공식/프리미엄 = 실시간 립싱크(LiveTalk/Ditto/OmniAvatar, 서버GPU) 또는 수제 Live2D. **핵심 제품 결정: TTS 립싱크 실시간 반응을 목표하는가(→서버 실시간 스택) vs 은은한 생동감이면 충분한가(→절차적).**
