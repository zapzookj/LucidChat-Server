# UGC(스튜디오) 에이전트 인수인계서

> 작성 2026-07-20 · 세션 857f246e 산출 · **다른 세션의 에이전트가 이 문서만 읽고 작업을 이어받을 수 있도록 작성됨**
> 동반 문서: `05_UGC_Summary.md`(사람용 요약), `03_UGC_E2E_Runbook.md`(관통 검증 절차)
> 원 스펙(부분 스테일 — 본 문서가 우선): `C:\Users\zapza\Downloads\UGC_Pipeline_Implementation_Spec.md`, `UGC_Frontend_Implementation_Spec.md`

---

## 0. 미션과 현재 상태

**UGC 캐릭터 생성 파이프라인 v1은 구현·E2E 검증·폴리싱 2라운드까지 완료 상태다.**
유저가 컨셉 입력 → 황금샷 가챠 → 스탠딩 후보 선택 → 감정 14종 파생 → 검수 → 누끼 → Character 등록까지
전 구간이 동작하며, 실측 완주 사례 2건(characterId 11=ugc-4, 12=ugc-5, 로컬 DB).

**바로 다음 태스크 = §12 세계관(월드) 빌더** — 설계 확정 완료(2026-07-20 종원 승인), 구현 미착수.

## 1. 리포지토리 배치 (경로 정확성 중요)

| 리포 | 경로 | 비고 |
|---|---|---|
| 백엔드 (본체) | `C:\Users\zapza\Desktop\MuseLab\aichat` | Spring Boot 3.4.2 / Java 17. 브랜치 feat/storyv2 |
| 프런트 (실운영) | `C:\Users\zapza\Desktop\LucidChat-Front\LucidChat-Front` | React 19+Vite+Tailwind3, 전부 .jsx. **`C:\Users\zapza\LUCIDCHAT-FRONT`는 스테일 MVP — 절대 거기서 작업 금지** |
| ComfyUI 워커 | `C:\Users\zapza\Desktop\lucid-comfy-worker` (= github.com/zapzookj/lucid-comfy-worker) | Dockerfile+workflows_json. 커밋·푸시는 종원 확인 후 |

로컬 인프라: PostgreSQL 17 네이티브(localhost:5432/aichat, postgres/application-local.yml 참조),
Redis = docker 컨테이너 `lucid-redis`(6379, restart unless-stopped), MongoDB 별도.
백엔드 실행: `.\gradlew.bat bootRun` (PowerShell). UGC 관련 env: `UGC_RUNPOD_API_KEY`,
`UGC_RUNPOD_ENDPOINT_ID`, `UGC_RUNPOD_WEBHOOK_SECRET`, `LUCID_WEBHOOK_BASE`(로컬은 빈 값 가능 — 폴링 폴백), `FAL_API_KEY`.

## 2. 파이프라인 아키텍처 (현행 — 2026-07-20 개편 반영)

```
[Stage 0] POST /ugc/characters {name?, concept, appearance?{hair,eyes,body,outfit,accessories,extra}}
   ├ 하드 키워드 게이트(차감 전) → 에너지 20 차감(TX) → CharacterCreationJob 생성
   └ @Async: LLM 구조화(ConceptStructuringService — OpenRouter completeJson)
       산출: appearance_tags(40~60)/persona_tags(5~8)/scene_tags/bg_color/character 프로필/moderation
       → minor_signal·age<19 차단 시 전액 환불 → WF-1 제출
[Stage 1] WF-1 황금샷 t2i (RunPod, batch 2) → GACHA_WAIT
   유저: 원화 선택(썸네일 확정) 또는 리롤(2E — 기존 후보 유지, +2장 누적)
[Stage 2] BASE_PROCESSING: 선택 원화에서 Qwen 2패스(자세→배경, fal SDK subscribe) ×2 (서로 다른 seed)
   → 각각 WF-2(NEUTRAL) 리파인 → BASE_WAIT (스탠딩 후보 2장, 리롤 2E 누적)
   유저 선택 → baseStandingKey·baseEditSeed(=선택 후보의 Qwen seed) 확정
[Stage 3] EMOTIONS_PROCESSING: 감정 14종 병렬 — 각: Qwen 감정 편집(베이스에서 직접=스타 토폴로지,
   seed=baseEditSeed 고정, persona 힌트 부착) → WF-2(감정 태그) → REVIEW_WAIT
   유저: 컷별 리롤(2E, 버전 누적) / 버전 골라잡기(무료) / FAILED 컷 무료 재시도
[Stage 4] confirm → POSTPROCESSING: WF-3 누끼 ×15 → BINDING: 에셋 승격 + Character 등록
   → READY (characterId) + 알림(UGC_CREATION_COMPLETE)
```

- **상태 머신**: CONCEPT_PROCESSING → GACHA_WAIT → BASE_PROCESSING → BASE_WAIT → EMOTIONS_PROCESSING
  → REVIEW_WAIT → POSTPROCESSING → BINDING → READY | FAILED | EXPIRED(72h WAIT 방치).
  전이는 전부 `CharacterCreationJob`의 도메인 메서드 + 잡 비관적 락(`findByIdForUpdate`) TX 안에서.
- **이벤트 공급**: RunPod webhook `POST /api/v1/webhook/ugc-comfy?job={id}&stage={UgcStage}&tag={token}&secret=…`
  (문맥이 URL에 있어 역조회 불필요) + **폴링 폴백**(UgcJobScheduler, `poll-fallback-seconds` 기본 15s,
  CONCEPT/BASE/EMOTIONS/REVIEW_WAIT/POSTPROCESSING 상태의 externalJobsJson 미결 잡만 /status 조회).
  fal(Qwen)은 SDK subscribe라 웹훅 무관. 두 경로 모두 `UgcPipelineWorker.onComfyEvent(jobId, stage, token, status)`로 수렴(멱등).
- **토큰 규약**: GOLDEN=null · BASE_REFINE=후보 인덱스("0"/"1"...) · EMOTION_REFINE/CUTOUT=EmotionTag 이름.
  externalJobsJson 키: `"GOLDEN"`, `"BASE_REFINE:0"`, `"EMOTION_REFINE:JOY"`, `"CUTOUT:JOY"` (+`K_` 접두 내부 스크래치는 폴러 제외).
- **에셋 흐름**: 외부 presigned URL 저장 금지 — 수신 즉시 `UgcAssetService.storeFromUrl` →
  서비스 버킷(lucid-chat-assets-v2) `ugc/jobs/{jobId}/{label}_{uuid8}.png`(불변 키, 리롤마다 새 키).
  확정 시 `characters/{slug}/default_{emotion}.png` + `thumbnail.png` 승격(copyObject). slug=`ugc-{jobId}`.
  fal 입력은 서비스 S3 presigned GET(2h), RunPod 입력은 base64 주입(10MB 가드).

## 3. 백엔드 클래스 맵 (`com.spring.aichat`)

| 파일 | 역할 |
|---|---|
| `config/UgcPipelineProperties` | ugc.* 설정 (energy/job/runpod/qwen/generation — 기본값 내장) |
| `config/UgcConfig` | 프로퍼티 활성화 + S3Presigner 빈 |
| `service/ugc/UgcWorkflowFactory` | workflows_json 3종 템플릿 로드+치환(계약 경로만), 기동 시 드리프트 fail-fast, seed 주입, refine-denoise·wildcard 노브 |
| `service/ugc/UgcPromptAssembler` | §4.2~4.4 상수 단일 소스: WF-1/2 positive(persona 병합·중복 제거), Qwen 3종 템플릿(+persona 힌트), FaceDetailer 와일드카드(얼굴 태그 필터), UGC baseSystemPrompt 골격 |
| `service/ugc/ConceptStructuringService` | Stage0 LLM(+스키마·정규화: bg 팔레트 폴백, 이름 우선, **normalizeGreeting**(괄호→나레이션/따옴표 제거) |
| `service/ugc/UgcModerationService` | 좁은 게이트: 영문 단어경계+한국어 하드 키워드, age<19, minor_signal. 차단 메시지 상수 |
| `service/ugc/CharacterCreationService` | 유저 액션 TX: start(+외형 힌트 병합)/select golden·base/rerolls(과금)/selectEmotionVersion/confirm/abandon/profile PATCH(정규화 재적용)/조회. 동시 1잡 정책 |
| `service/ugc/UgcPipelineWorker` | @Async 스테이지 실행+이벤트 처리 전부. 재시도 정책, 전액 환불(failAndRefund), 만료(expireJob), initEmotionAssets/resetEmotionForReroll |
| `service/ugc/UgcAssetService` | storeFromUrl/promote/download/presignGet/publicUrl |
| `service/ugc/UgcJobJson` | 잡 TEXT-JSON 코덱 단일 소스 (emotions/keys/baseCandidates/scratch/concept) |
| `service/ugc/UgcStage` | GOLDEN/BASE_REFINE/EMOTION_REFINE/CUTOUT |
| `service/ugc/UgcCharacterService` | 바인딩 후: publish/secret 신청, texts 수정, mine, explore(커서) |
| `service/admin/AdminUgcReviewService` | 승인 큐(공개+Secret 통합)/상세/판정(감사로그+알림)/**prompts(프롬프트 인스펙션)** |
| `service/scheduler/UgcJobScheduler` | 폴링 폴백(15s)+TTL 만료(10m) |
| `external/UgcComfyClient` | RunPod worker 5.x(/run+webhook, /status, output.images[{filename,type:s3_url,data}], 10MB 가드). 레거시 RunPodComfyClient(휴면)와 별개 |
| `external/FalQwenEditClient` (`PoseEditClient` 구현) | fal-ai/qwen-image-edit-2511, SDK subscribe, **image_urls 배열**, image_size 생략(입력 해상도 유지), safety_checker off |
| `domain/ugc/CharacterCreationJob(+Status,+Repository)` | 잡 엔티티(TEXT-JSON 스크래치, 비관락 리포) |
| `domain/enums/CharacterSource·CharacterVisibility·SecretReviewStatus·Outfit.DEFAULT` | Character 확장 enum |
| `dto/ugc/*` | StructuredConcept(+FlexibleStringDeserializer — 배열→bullet 관용), EmotionAssetState(**버전 history**), BaseCandidate, UgcDtos(전 API 계약) |
| `controller/CharacterCreationController` | 위저드 API 전부 + 레이트리밋(checkUgcMutation) |
| `controller/UgcComfyWebhookController` | 웹훅 수신(secret 쿼리 검증, 항상 200) |
| `controller/admin/AdminCharacterController` | /ugc/queue·/{id}·/{id}/review·**/{id}/prompts** |

**Character 엔티티 확장**: ownerUserId/source/secretEligible/visibility/secretReviewStatus/reviewNote +
`createUgc(UgcCharacterSpec)`(불변식: UGC·PRIVATE·secretEligible=false·story/theater=false), 심사 전이 메서드,
`isAccessibleBy`. `applySeed`는 공식 불변식(OFFICIAL·PUBLIC·secretEligible=true) 강제.
**기존 코드 접점 수정분**: SecretModeService 2-arg에 캐릭터 게이트, LobbyService(UGC 로비 제외+PRIVATE 은닉+SANDBOX 강제),
ChatService initializeChatRoom introNarration null 가드, CharacterSeeder/@Order 시더 체인 픽스(0~4),
ApiRateLimiter.checkUgcMutation.

## 4. API 계약 (전부 구현·검증 완료)

위저드 (`/api/v1/ugc/characters`, 소유자 검증·타인 404 은닉):
```
POST   /                          {name?, concept(30~1000자), appearance?} → 202 {jobId} (20E)
GET    /{jobId}                   CreationJobView — 폴링 2.5s
POST   /{jobId}/golden-shot       {selectedIndex}|{reroll:true(2E, 누적)}
POST   /{jobId}/base-standing     {selectedIndex}|{reroll:true(2E, 누적)}
PATCH  /{jobId}/profile           UpdateProfileRequest(null=유지, 빈문자열도 유지) — Stage0후~REVIEW_WAIT
POST   /{jobId}/emotions/{TAG}/reroll    (READY=2E / FAILED=무료)
POST   /{jobId}/emotions/{TAG}/select    {versionIndex} (무료 버전 골라잡기)
POST   /{jobId}/confirm           15컷 전부 READY 필요
DELETE /{jobId}                   중도 포기(무환불)
GET    /mine                      {characters[], activeJob}
GET    /explore?cursor=&limit=    공개 UGC 피드(닉네임 조인)
POST   /{characterId}/publish-request {cancel?} · /{characterId}/secret-request · PATCH /{characterId}/texts
```
`CreationJobView`: jobId/status/currentStepHint(CONCEPT_ANALYZING|GOLDEN_GENERATING|status)/goldenShots[{index,url}]
/baseCandidates[{index,status,url|null}]/baseStandingUrl/emotionAssets{TAG:{status,thumbUrl,versions[],selectedIndex}}
/profile{14필드}/energySpent/rerollCosts{goldenShot,baseStanding,emotion}/failReason/characterId/expiresAt.
어드민 (`/api/v1/admin/characters/ugc`): GET /queue, GET /{id}, POST /{id}/review {publishApprove?,secretApprove?,note},
GET /{id}/prompts (PromptInspection — 실프롬프트 재구성).

## 5. 데이터 모델·마이그레이션

- V9: `character_creation_jobs` 생성 + characters 6컬럼(owner_user_id/source/secret_eligible/visibility/secret_review_status/review_note) + 기존행 secret_eligible=TRUE
- V10: `chat_rooms_current_outfit_check` DROP + UGC 방 MAID→DEFAULT 보정
- V11: base_candidates_json ADD(IF NOT EXISTS) + `character_creation_jobs_status_check` DROP
- 코드베이스 관례: 구조화 데이터는 **TEXT + Jackson**(JSONB 아님), FK 의도적 미설정(Long 참조)
- ⚠ **Hibernate enum CHECK 제약 함정**: ddl-auto 생성 스키마는 enum 컬럼에 값 목록 체크를 만든다.
  **enum에 값을 추가하면 반드시 해당 `{table}_{col}_check` DROP을 마이그레이션에 포함**할 것 (V10/V11 참조).
- ⚠ 빈 로컬 DB 초기화는 2단계 부팅 필요 (Runbook §1-3) — Flyway가 Hibernate보다 먼저 돌기 때문.

## 6. 확정 정책·결정 이력 (전부 종원 확정)

| 항목 | 결정 |
|---|---|
| 워크플로 진실원본 | **실제 Export JSON** (리소스 `workflows_json/` = 워커 리포와 동기). 유일 예외: WF-1 FaceDetailer cfg 4/denoise 0.4 (JSON에 반영됨) |
| 에너지 | 기본 패키지 20 / 황금샷·스탠딩·감정 리롤 각 2 (설정: ugc.energy.*) |
| 환불 | 파이프라인 귀책 FAILED=전액 / EXPIRED·포기=무환불 / 자동 재시도·FAILED 컷 재시도·버전 선택=무과금 |
| 리롤 | **전부 누적**(2026-07-20): 황금샷·스탠딩 +2장 누적, 감정 컷 버전 history+골라잡기. 리롤 실패 소진 시 이전 완성본 복귀 |
| NEUTRAL 컷 | 리롤 불가(스타 토폴로지 원점 보호) — 베이스는 BASE_WAIT에서 선택·리롤 |
| 편집 모델 | **Qwen 유지 확정**(2026-07-20). GPT Image 2 조사 결론: medium 2배 비용·2~3배 지연·full-redraw 인물 드리프트·모더레이션 리스크로 반대. PoseEditClient로 추상화되어 재검토 시 교체 용이 |
| 공개/Secret | 기본 PRIVATE·공개 승인제·**Secret 단독 신청 경로 존재**. PENDING_PUBLIC 동작=PRIVATE |
| v1 스코프 | SANDBOX 전용(story/theater=false), worldId=null(월드 빌더에서 해소 예정), 동시 1잡/유저 |
| 프로필 편집 | Stage0후~REVIEW_WAIT 상시(레이턴시 하이딩). 외형 태그는 편집 불가(이미지 생성 후) |
| first_greeting | 순수 대사 + intro_narration 분리(정규화 이중 방어 — normalizeGreeting) |

## 7. 프런트 구조 (LucidChat-Front)

신규: `api/StudioApi.js`, `hooks/useUgcCreationJob.js`(2.5s 폴링, hidden 스킵, terminal 중단),
`pages/StudioPage.jsx`(앰버 무드·진행 카드·내 캐릭터·탐색·훅), `components/studio/{StudioCreateFlow,ProfileEditPanel,UgcStatusBadge,CreateHookCard}.jsx`.
수정: App.jsx(/studio 라우트), LobbyPage(허브 메뉴 '스튜디오'+카루셀 훅 카드), CharacterDisplay/ChatPage/ChatPageV2/TheaterPlayPage(CDN 픽스).
- **스테퍼 6단계**: 컨셉(+외형 디테일 6필드) → 원화(GACHA) → 스탠딩(BASE) → 소환(EMOTIONS) → 검수(REVIEW/POST/BINDING) → 완성(READY)
- **설정 다듬기(ProfileEditPanel)**: 편집 가능 전 구간 플로팅 CTA, 열림 시 스냅샷 시딩(폴링 클로버링 방지), dirty만 PATCH
- **CDN 이원화 대응**: `CharacterDisplay.deriveAssetDir(defaultImageUrl)` — 절대 URL에서 디렉터리 유도해
  `{dir}/{outfit}_{emotion}.png` 조립. 공식(d3578f1gfp49r6)·UGC(d3gb5c1krrdbgj) 자동 처리. 없으면 기존 VITE_ASSET_BASE_URL 경로(무회귀)
- 누적 UX: 가챠·스탠딩 그리드 누적+로딩 칸, 검수 확대 뷰 버전 스트립(무료 선택)
- 주의: axios baseURL이 localhost 하드코딩(개발 상태 — 건드리지 말 것, 배포 시 종원이 복원)

## 8. 워커 리포 (lucid-comfy-worker)

- Dockerfile: `FROM runpod/worker-comfyui:5.8.6-base`(필수 — wf3의 RemoveBackground/LoadBackgroundRemovalModel은
  ComfyUI 코어 v0.21+, 5.8.5엔 없음) → `comfy-node-install comfyui-impact-pack@8.28.3 comfyui-impact-subpack@1.3.5`
  → **requirements를 /opt/venv로 미러링**(핵심: comfy-node-install은 /comfyui/.venv에 설치하지만 런타임은 /opt/venv —
  미러링 없으면 IMPORT FAILED→missing_node_type. worker-comfyui#86) → 빌드 시점 quick-test 부팅+IMPORT FAILED grep 검증
  → 모델 COPY: wai_illustrious.safetensors(ckpt)/detail_lora.safetensors/anime_face_yolov8.pt/birefnet.safetensors(background_removal/)
- 빌드: S3 assets-build/ 미러 → GHA `build-comfy-worker` 태그 실행 → 엔드포인트 이미지 교체 → **웜 워커 terminate 필수**
- FIELD_SPEC.md v2(리포 내)가 치환 계약 정본. 치환 경로: wf1(12.text/11.seed/17.seed/6.batch_size/9.prefix),
  wf2(19.image/12.text/11.seed/17.seed/**17.wildcard**/9.prefix/11.denoise 노브), wf3(1.image/23.prefix)
- ⚠ FIELD_SPEC의 wf2 wildcard "동결" 표기는 스테일 — 2026-07-20부터 치환 대상(얼굴 일관성). 다음 커밋 때 문서 정정 필요

## 9. 사건 로그 (재발 시 참조 — 원인→수정)

1. **Stage0 파싱 실패**: LLM이 core_values를 배열 반환 → FlexibleStringDeserializer(배열→bullet)+프롬프트 명시
2. **FaceDetailer missing_node_type**: venv 분리(§8) → /opt/venv 미러링+빌드 검증 (commit 2421429)
3. **감정 리롤 무한 로딩**: 폴러가 REVIEW_WAIT 미포함 → 포함 (UgcJobScheduler)
4. **UGC 일러 미표시 ①**: Outfit enum에 DEFAULT 없음→MAID 폴백→404 → enum 추가+V10
5. **UGC 일러 미표시 ②**: CDN 이원화(공식/UGC 버킷 상이) → deriveAssetDir(§7)
6. **Flyway/체크 제약**: §5의 2단계 부팅·enum check DROP 규칙
7. **감정 15종 얼굴 드리프트**: wildcard가 디테일 패스 프롬프트를 대체(얼굴 태그 실종) → faceDetailWildcard 주입
8. **시더 순서**: @Order는 @Bean 메서드에 붙어야 함(클래스 @Order 무효) — Character(3)→Routine(4)

## 10. 검증 명령

```powershell
# 백엔드 (aichat 루트)
.\gradlew.bat test --tests "com.spring.aichat.service.ugc.*" --tests "com.spring.aichat.dto.ugc.*" --tests "com.spring.aichat.external.UgcComfyClientTest" --console=plain -q
# 프런트 (LucidChat-Front\LucidChat-Front 루트)
npm run build
```
전체 관통 절차·실측 기록: `03_UGC_E2E_Runbook.md`.

## 11. RunPod/fal 운영 메모

- 엔드포인트: 4090, min0/max3, FlashBoot, idle 240s. 실측: WF-1 batch GPU 62s(모델 로드 포함), WF-2 ~15s, WF-3 ~0.6s
- **미해결**: 스케일아웃 보조 워커가 exit 1 crash-loop(부팅 로그 없음 — Container Disk 30GB+ 여부 종원 확인 대기).
  fal 계정 동시성 기본 2(실측은 14콜 53s 완주 — 실한도 더 높아 보임), 출시 전 상향 권장
- 웹훅은 로컬에서 미도달(정상) — 폴링 15s가 커버. prod 배포 시 LUCID_WEBHOOK_BASE 실URL로 자동 활성

---

## 12. ★다음 태스크: 세계관(월드) 빌더 — 확정 설계 (2026-07-20 종원 승인)

**구조**: 독립 빌더 + 지연 바인딩 + 레이턴시 구간 CTA
- 스튜디오에 월드 빌더 신설(캐릭터 빌더와 독립). 월드는 재사용 자산(1 월드:N 캐릭터)
- 캐릭터 위저드 컨셉 스텝: `공식 4종 | 내 커스텀 월드 | 나중에 연결` 3택만 (새로 만들기=월드 빌더 링크)
- 캐릭터 카드 메뉴에 "세계관 연결/변경" (기존 무소속 캐릭터 소급 연결)
- 캐릭터 감정 파생 구간(~7분)에 "이 캐릭터의 세계관 만들기" CTA — **독립 월드 잡**을 병렬 시작, 캐릭터 완성 시 월드 READY면 자동 연결 제안 (상태 얽힘 금지 — 각자 잡)

**파이프라인** (가볍다 — RunPod/Qwen 불필요, 기존 fal flux-2 배경 트랙 재활용):
```
W0. 컨셉 입력(자유 서술+장르/무드 힌트) → LLM 구조화:
    {이름, 소개, lore(채팅 프롬프트 주입용), 무드 태그, 장소 제안 6개(이름+묘사+배경 프롬프트), 썸네일 프롬프트}
W1. 유저 편집: 설정 텍스트 + 장소 리스트(제안 수정/삭제+직접 추가, 상한 10 — 직접 입력분은 LLM이 프롬프트화만)
W2. 일러: 썸네일 1장 + 장소별 대표 배경 1장 (flux-2 병렬, 리롤 누적 패턴 재사용)
W3. 확정 → UgcWorld 저장
```

**확정 정책**:
- 장소: LLM 제안 6개 기본, 유저 편집 상한 10. 배경은 **장소당 대표 1장** (DAY/NIGHT 2장은 백로그)
- 에너지: **기본 패키지 10 / 썸네일·장소 리롤 각 1**
- 공개: 월드 독립 공개 기능 없음 — **캐릭터 공개 심사에 소속 월드 검수 자동 포함** (승인 큐 상세에 월드 섹션 추가)
- 채팅 효과(v1 SANDBOX): 캐릭터 장소 풀=월드 장소(동적 배경이 세계 안에서 이동), lore를 시스템 프롬프트 주입

**기술 결정**: 기존 `World`는 PK가 WorldId **enum**이라 사용 불가 → **`UgcWorld` 별도 엔티티**
(+`UgcWorldLocation`). Character에 `ugcWorldId` 컬럼 병행(공식 연결은 기존 worldId enum 그대로).
전면 마이그레이션(enum PK 제거)은 STORY 개방 시점에 재검토.

**구현 WBS 제안**:
1. W-BE-1: UgcWorld/UgcWorldLocation/UgcWorldCreationJob(상태머신 — 캐릭터 잡 축소판: CONCEPT→EDIT_WAIT→ILLUSTRATING→READY) + V12
2. W-BE-2: WorldConceptStructuringService(Stage W0 LLM — 모더레이션 게이트 재사용) + 장소 프롬프트화
3. W-BE-3: 오케스트레이터(썸네일+장소 배경 병렬 — BackgroundGenerationService/FalAiClient 재활용, 리롤 누적) + API(/ugc/worlds/**)
4. W-BE-4: 캐릭터 연결(Character.ugcWorldId, 위저드 선택 3택, 카드 메뉴 연결 API) + 채팅 주입(CharacterPromptAssembler 장소 풀·lore — V1 SANDBOX 경로)
5. W-BE-5: 승인 큐 상세에 월드 섹션
6. W-FE: 월드 빌더 플로우(캐릭터 위저드 패턴 복제 — 컨셉→편집(레이턴시 하이딩)→일러 선택→완성), 캐릭터 위저드 3택, 감정 구간 CTA, 카드 메뉴
7. E2E 관통 + 원가 실측(flux-2 단가)

## 13. 백로그·열린 항목

**즉시(종원 콘솔 확인 대기)**: RunPod 보조 워커 crash-loop(Container Disk), fal 동시성 상향
**프롬프트 튜닝(종원 진행 중)**: GET /admin/characters/ugc/{id}/prompts 로 실프롬프트 확인 → 튜닝 지점은
UgcPromptAssembler(상수)·ConceptStructuringService(Stage0 지시)
**v1.1+**: STORY 개방(월드 통합·루틴), 캐릭터 LoRA(fal 트레이너 — 대화 중 일러·Secret 에셋), DAY/NIGHT 장소 배경,
VLM 프리필터(PoC-5), Tier2 공유 UGC(크리에이터 이코노미), PUBLIC 철회 API, 프로필 빈값=삭제 의미론,
ugc/jobs/ 중간 산출물 수명주기, CDN/버킷 통일(이원화 해소), 웹훅 실환경 검증
**런칭 전 폴리싱 목록(기존 합의 — 별도)**: 결제 C-5/C-6, Flyway 전체 스키마 단일화, lucid-key.pem, 로비 hidden 필터,
V2 에너지 보상 버그, 감사·rate limit 공백 등 — `01_StaticAnalysisReport.md`·세션 분석 참조
