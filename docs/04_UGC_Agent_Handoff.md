# UGC(스튜디오) 에이전트 인수인계서

> 작성 2026-07-20 · 세션 857f246e 산출 · **다른 세션의 에이전트가 이 문서만 읽고 작업을 이어받을 수 있도록 작성됨**
> 동반 문서: `05_UGC_Summary.md`(사람용 요약), `03_UGC_E2E_Runbook.md`(관통 검증 절차)
> 원 스펙(부분 스테일 — 본 문서가 우선): `C:\Users\zapza\Downloads\UGC_Pipeline_Implementation_Spec.md`, `UGC_Frontend_Implementation_Spec.md`

---

## 0. 미션과 현재 상태

**UGC 캐릭터 생성 파이프라인 v1은 구현·E2E 검증·폴리싱 2라운드까지 완료 상태다.**
유저가 컨셉 입력 → 황금샷 가챠 → 스탠딩 후보 선택 → 감정 14종 파생 → 검수 → 누끼 → Character 등록까지
전 구간이 동작하며, 실측 완주 사례 2건(characterId 11=ugc-4, 12=ugc-5, 로컬 DB).

**§12 세계관(월드) 빌더 — 구현 완료(2026-07-21, 백엔드+프런트)**. E2E 관통·실측은 미실행(다음 태스크).
어드민 검수 SPA는 종원 결정으로 UGC 폴리싱 완료 후 최후순위(백엔드 피기백 판정은 구현됨 — curl 검수 가능).

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

## 12. 세계관(월드) 빌더 — 구현 완료 (2026-07-21) · 원 확정 설계 (2026-07-20 종원 승인)

> **구현 상태**: 아래 설계 전부 + 종원 추가 확정(2026-07-21: ① 월드 반려 = worldApprove 3축+통반려 ② PUBLIC 캐릭터엔 APPROVED 월드만 연결·READY 후 월드 편집 API 없음 ③ 어드민 SPA 최후순위 ④ assets-v2 lifecycle은 종원 확인 대기)이 코드로 반영됨. 상세는 §12-A(하단) 참조.

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

## 12-A. 세계관 빌더 구현 기록 (2026-07-21 — 다른 세션의 에이전트가 이어받을 수 있게)

**상태머신**: `UgcWorldCreationJob` — CONCEPT_PROCESSING→EDIT_WAIT→ILLUSTRATING→REVIEW_WAIT→BINDING→READY | FAILED | EXPIRED(WAIT 72h). 캐릭터 잡과 **타입별 독립 동시 1잡**(감정 파생 중 CTA 병행이 전제).

**백엔드 신규/수정 클래스 맵**:
| 파일 | 역할 |
|---|---|
| `domain/ugc/UgcWorld(+Location,+CreationJob,+WorldCreationJobStatus,+WorldReviewStatus,리포 3종)` | 월드 도메인. UgcWorld.reviewStatus=NONE/APPROVED/REJECTED(월드 단독 신청 없음—피기백 판정 결과만) |
| `V12__ugc_world_builder.sql` | 테이블 3종 + characters.ugc_world_id + ccj.requested_(ugc_)world_id. 전문 멱등 + enum check 선제 DROP 3종 |
| `service/ugc/WorldConceptStructuringService` | W0 구조화(장소 6 제안·locationKey 정규화 SCREAMING_SNAKE≤40) + promptizeLocations(유저 추가 장소 일괄 프롬프트화). **텍스트 상한 단일 소스**(intro500/lore2000/장소설명300/무드20×10 — updateDraft와 대칭) |
| `service/ugc/UgcWorldPipelineWorker` | fal flux-2 전용. **H-16 절충**: externalJobs에 PENDING 선커밋→`FalAiClient.submitToQueue()`(requestId 선확보)→치환→`awaitResult()` 부착. 콜백은 **scratch 값과 requestId 대조(세대 가드)** — 구세대/중복 이벤트 무해. 전량 병렬 제출(fal이 큐 직렬화) |
| `service/ugc/UgcWorldService` | 유저 액션 TX. GENERATING 컷 리롤/버전선택 400, confirm은 allReady+빈 scratch 요구(바인딩 중 바꿔치기 차단) |
| `service/ugc/UgcWorldJobJson` · `dto/ugc/WorldAssetState(+WorldIllustrationAssets,WorldDraft,StructuredWorld,UgcWorldDtos)` | 코덱·리롤 누적 record(감정 컷 동형: history+selectVersion+revertToReady) |
| `controller/UgcWorldController` | `/api/v1/ugc/worlds/**` (계약은 UgcWorldDtos javadoc). world_mutation 레이트리밋 버킷(ugc_mutation과 분리) |
| `UgcJobScheduler` 확장 | 월드 TTL 만료 + **스테일 스윕**(5분 주기, 30분 무진행): CONCEPT=failAndRefund / ILLUSTRATING 빈 scratch=runIllustration 재기동 / PENDING=재제출·requestId=재부착(+touchRecovery로 중복 재부착 억제) / BINDING=재실행(멱등) |
| `UgcAssetService` 확장 | 중간산출 `ugc/world-jobs/{jobId}/`(캐릭터 잡과 jobId 충돌 방지 분리) → 확정 `worlds/ugc-world-{jobId}/thumbnail.png·bg_{key}.png`. 엔티티엔 publicUrl 저장 |
| 캐릭터 연결 | `Character.ugcWorldId`(worldId enum과 XOR, link/unlink 뮤테이터), 위저드 3택(StartCreationRequest.officialWorldId/ugcWorldId → 잡 기록 → bind 주입 — **공식 선택=worldId enum 실세팅**, 기존 3중 가드로 Theater/STORY 안 열림), `PATCH /ugc/characters/{id}/world`(무료. PUBLIC=APPROVED만·**PENDING_PUBLIC=변경 차단**(심사 TOCTOU)) |
| 채팅 주입(SANDBOX) | `CharacterPromptAssembler`: ugcWorld 분기(# 🌍 World Setting 동일 헤더 — lore는 encapsulate) + 장소 풀 블록(`UGCW_{worldId}__{KEY}` 에코 지시). `BackgroundGenerationService.resolveBackground`에 **장소 풀 인터셉트**(사전 배경 즉시 서빙 — BackgroundCache 미경유) + 즉석 장소는 moodTags 주입(`assembleWithMood`). 방 생성 시 첫 장소 배경 시딩(LobbyService). `ChatRoomInfoResponse.ugcWorldId` 추가 |
| 어드민 피기백 | `ReviewRequest.worldApprove` 3축 + 통반려(월드 반려+공개 승인=400) + 미승인 월드 공개 승인 시 worldApprove 동시 제출 강제. DetailResponse.world 섹션, QueueItem.hasWorld |

**프런트 (LucidChat-Front)**: `api/WorldStudioApi.js`(+OFFICIAL_WORLDS 상수), `hooks/useUgcWorldJob.js`, `pages/StudioWorldPage.jsx`+`components/studio/WorldCreateFlow.jsx`(4스텝: 컨셉→편집→일러검수→완성, /studio/world 라우트), StudioCreateFlow 3택 셀렉터+감정 대기 CTA, StudioPage 내 세계관 섹션+카드 메뉴 연결 시트, ChatPage 클리어 가드(ugcWorldId 방은 enum 폴백이 동적 배경을 지우지 않음).

**검증 상태**: 컴파일·테스트 그린(신규 테스트 5클래스 — contextLoads 실패는 JWT_SECRET 미설정 기존 환경 이슈). V12 로컬 적용 확인. 7렌즈 멀티에이전트 적대 리뷰 → 확정 결함 8건 전부 수정. **미실행: E2E 관통(§10 커맨드+실서버)·flux-2 원가 실측·웹훅 실환경**. 커밋 안 됨(종원 확인 후).

**주의(구현 중 확정)**: fal 계정 동시성 2 공유(Qwen 트랙과) — 11장 순차화로 최악 수 분, 출시 전 상향 필수. 썸네일도 landscape_16_9. UGC 월드는 STORY/THEATER 구조적 차단 유지(worlds 테이블 enum PK 비침투). 시더는 ugcWorldId 비관리.

## 12-B. 캐릭터 빌더 개선 (2026-07-21 — 종원 PoC 피드백 반영)

**폐기 결정**: GPT Image 2 하이브리드(fal 경유 이중 필터·API edit 모더레이션 low 불가 확인 → 거부 리스크로 폐기, Qwen 파라미터 튜닝으로 대체), Danbooru 태그 직접 편집.

**반영 항목** (전부 구현·테스트 그린):
1. **FaceDetailer 와일드카드 재구성** — `faceDetailWildcard(tags, personaTags, emotion)`: detailed beautiful eyes + 눈 관련(eye/pupil/iris/lash/brow/heterochromia)+mole/freckle + persona + **감정 wf2 태그**. 헤어 태그 제외(어텐션 희석 실측). 표정 미포함이 디테일 패스의 감정 중화 원인이었음.
2. **동적 감정·자세 프롬프트** — Stage0가 `base_pose`(캐릭터별 기본 스탠스) 산출, 감정 스테이지 진입 시 `ConceptStructuringService.deriveEmotionPrompts` 1콜로 14종 expression/pose 산출 → `StructuredConcept.emotionPrompts`에 저장(리롤 재현·어드민 인스펙션 노출). 실패 시 상수 폴백(비차단). **구도 가드**: 카메라 방향 기울임·거리·앵글 변경 금지(Pleading 비율 붕괴 완화) — 템플릿 가드 문장 + 기존 상수 5종(JOY/ANGRY/SURPRISE/FLIRTATIOUS/PLEADING) 완화 + LLM 지시.
3. **배경 보색 강화** — WF-2 positive `(simple background:1.2), ({색} background:1.3⟵ugc.generation.bg-emphasis-weight 노브), (flat lighting:1.1)` + Qwen 패스2 균일 단색·실루엣 주변 강조 문구 (누끼 계단 대응).
4. **노브 2종** — `ugc.stage0-model`(Stage0/W0 전용 LLM — 미지정 시 openai.model), `ugc.qwen.use-negative-prompt`(기본 true — 빈 네거티브 실험은 감정 14종 identity drift A/B 후 전환).
5. **황금샷 리롤 외형 수정** — `POST /{jobId}/golden-shot {reroll:true, appearance?:{6필드}}`: 하드 게이트(차감 전)→과금→K_APPEARANCE_EDIT 스크래치→워커가 `restructureAppearance`(외형 태그·씬·bg_color·외형/복장 서술만 교체, 페르소나·서사·유저 편집분 보존)→새 프롬프트 제출. LLM 차단=failAndRefund(전액). **기존 후보는 무효화**(구외형 스테일 — 리롤 누적은 동일 컨셉 전용). FE: GachaStep 리롤 모달(6필드 선택 입력, GoldenRerollModal).

**리뷰 픽스(적대 리뷰 확정 4건)**: ① 외형 재구조화 applyStage0를 락 안 최신본 재조회+외형 필드만 병합으로(동시 프로필 편집 lost update 방지 — `StructuredConcept.withAppearanceFrom`) ② 외형 수정 리롤 시 goldenShotKeys 초기화 ③ onGoldenResult에 GOLDEN 스크래치 리플레이 가드(중복 웹훅이 리롤 삼키는 캐스케이드 차단) ④ **CONCEPT_PROCESSING 스테일 스윕 신설**(30분 무진행+미결 외부 잡 없음 → failAndRefund — Stage0/외형 LLM 구간의 재시작 유실은 폴링 폴백 사각이었음, 기존 Stage0에도 있던 구멍).

**튜닝 루프**: `GET /admin/characters/ugc/{id}/prompts`가 개선 후 실구성(감정 포함 와일드카드·동적 base_pose·JOY 동적 감정) 반영. 미실행: 실이미지 E2E(원가·품질 확인은 실기동 필요).

## 12-C. 도그푸딩 일괄 패치 (2026-07-22 — 종원 테스트 피드백 8건 + 신규 기능 2종)

**결정 (종원)**: 프로필 뷰=혼합안(진입 A안 몰입/스튜디오 상세 B안 도시어, 시안: claude.ai/code/artifact/6095bd17), 신규 신상 필드=키·좋아하는 것·싫어하는 것·취미, 적용 전 지점. 월드 사후 편집 개방(공유 멀티유저 리스크 현 단계 미고려), 장소 상한 20, 장소 추가 1E. GPT Image 2 하이브리드·태그 직접 편집은 §12-B에서 폐기 확정.

**신규 기능**:
1. **캐릭터 프로필 뷰** — `GET /api/v1/lobby/characters/{id}/profile`(공개/소유자, hidden 404). characters에 height/likes/dislikes/hobby/mood_tags(V13, UGC=Stage0 산출·공식=시드 수기 — **공식 12종 시드 입력은 종원 몫**). 티저(introNarration)·발췌(firstGreeting)는 firstSentence 절삭(선행 말줄임표 런 스킵·서로게이트 안전·90자). FE: `CharacterProfileView.jsx`(immersive/dossier variant) — 로비·탐색(immersive), 스튜디오 내 캐릭터(dossier), 챗 PROFILE 버튼(immersive, CTA=닫기). 진입 동선: 카드 클릭→프로필→기존 진입 로직 재사용.
2. **월드 사후 편집** — PATCH /ugc/worlds/{id}(텍스트 무료 — **판정 이력 NONE 리셋**, 빈 이름 400, moodTags []=클리어), POST .../locations(1E, GENERATING row→promptize→flux(5분 타임아웃)→READY, 실패 FAILED), retry(무료 — FAILED/멈춘 GENERATING), DELETE(FAILED만, 1E 환불). 상한 20(월드 row 비관락으로 TOCTOU 차단). **공개 심사 중(PENDING_PUBLIC 연결) 월드 내용 변경 400**(requireNotUnderReview — 월드 교체 차단과 동일 원칙). ugc_world_locations.status(V13). FE: `WorldDetailSheet.jsx`(설정 수정+장소 관리, GENERATING 5s 폴링).

**버그 픽스**: #6 스탠딩 유령 플레이스홀더(FE 휴리스틱), #7 stage0-model yml 위치(ugc 루트 — runpod 하위는 무시됨), #4 스크롤바 전수(전역 textarea 규칙+12곳), #5 확대 프리뷰 92vw/86vh, #8 더블클릭 전면 방어(busyRef 재진입 락·confirm 이중 발화 가드·BiometricStatusPanel excludeRef·BottomSheet 250ms 백드롭 가드), #3-a 외형 리롤 안내 카피. #3-b 리롤 4분 지연은 RunPod 측(-e2=재시도, crash-loop 보조 워커/idle 콜드스타트) — **종원 콘솔 확인 항목**.

**일괄 리뷰 픽스(4렌즈 확정 15건→12근원)**: firstSentence 말줄임표 붕괴(HIGH — 출시 시드 재현), bind 신상·moodTags 무절삭 varchar 초과(완주 잡 최종 실패 — normalizeShort+joinMood 절삭), moodTags [] 클리어 불가, 프롬프트 장소 풀에 배경 미보유 장소 광고(유료 중복 생성), 사후 배경 join() 무한 대기(5분 타임아웃), 장소 상한 TOCTOU(비관락), 동명 장소 키 매칭 가로채기(2-패스), FE: GENERATING 복구 버튼(90s 후 노출)·'최대 10개' 하드코딩·빈 값 저장 재동기화·ESC 폼 유실·종결 잡 재진입 무통보(localStorage).

## 13. 백로그·열린 항목

**즉시(종원 콘솔 확인 대기)**: RunPod 보조 워커 crash-loop(Container Disk), fal 동시성 상향
**프롬프트 튜닝(종원 진행 중)**: GET /admin/characters/ugc/{id}/prompts 로 실프롬프트 확인 → 튜닝 지점은
UgcPromptAssembler(상수)·ConceptStructuringService(Stage0 지시)
**v1.1+**: STORY 개방(월드 통합·루틴), 캐릭터 LoRA(fal 트레이너 — 대화 중 일러·Secret 에셋), DAY/NIGHT 장소 배경,
VLM 프리필터(PoC-5), Tier2 공유 UGC(크리에이터 이코노미), PUBLIC 철회 API, 프로필 빈값=삭제 의미론,
ugc/jobs/ 중간 산출물 수명주기, CDN/버킷 통일(이원화 해소), 웹훅 실환경 검증
**런칭 전 폴리싱 목록(기존 합의 — 별도)**: 결제 C-5/C-6, Flyway 전체 스키마 단일화, lucid-key.pem, 로비 hidden 필터,
V2 에너지 보상 버그, 감사·rate limit 공백 등 — `01_StaticAnalysisReport.md`·세션 분석 참조
