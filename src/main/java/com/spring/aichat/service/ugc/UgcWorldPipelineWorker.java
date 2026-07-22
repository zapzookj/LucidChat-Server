package com.spring.aichat.service.ugc;

import com.spring.aichat.config.UgcPipelineProperties;
import com.spring.aichat.domain.ugc.UgcWorld;
import com.spring.aichat.domain.ugc.UgcWorldCreationJob;
import com.spring.aichat.domain.ugc.UgcWorldCreationJobRepository;
import com.spring.aichat.domain.ugc.UgcWorldLocation;
import com.spring.aichat.domain.ugc.UgcWorldLocationRepository;
import com.spring.aichat.domain.ugc.UgcWorldRepository;
import com.spring.aichat.domain.ugc.WorldCreationJobStatus;
import com.spring.aichat.domain.user.UserRepository;
import com.spring.aichat.dto.ugc.StructuredWorld;
import com.spring.aichat.dto.ugc.WorldAssetState;
import com.spring.aichat.dto.ugc.WorldDraft;
import com.spring.aichat.dto.ugc.WorldIllustrationAssets;
import com.spring.aichat.exception.ContentModerationException;
import com.spring.aichat.external.FalAiClient;
import com.spring.aichat.service.cache.RedisCacheService;
import com.spring.aichat.service.notification.NotificationService;
import com.spring.aichat.service.prompt.BackgroundPromptAssembler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * [UGC 세계관 빌더] 파이프라인 워커 — {@link UgcPipelineWorker}의 축소판 (fal flux-2 전용 트랙).
 *
 * <p>캐릭터 트랙과의 핵심 차이 — <b>제출 순서(H-16 절충)</b>: fal은 웹훅/폴링 폴백이 없는
 * SDK 경로라, 외부 제출 <i>전에</i> externalJobs에 PENDING을 선커밋하고 제출 후 requestId로
 * 치환한다. 서버 재시작으로 in-flight future가 유실되면 스테일 스윕이 requestId로
 * {@link FalAiClient#awaitResult} 재부착 또는 PENDING 재제출로 복구한다.
 *
 * <p>불변 원칙(캐릭터 트랙과 동일): 상태 전이는 전부 잡 비관적 락 TX 안 · S3/외부 I/O는 락 밖 ·
 * presigned URL 저장 금지(수신 즉시 서비스 S3 복사) · 파이프라인 귀책 실패 = 전액 환불.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UgcWorldPipelineWorker {

    /** externalJobs 토큰 — 썸네일. */
    static final String THUMB_TOKEN = "THUMB";
    /** externalJobs 토큰 접두 — 장소 배경 ("LOC:{locationKey}"). */
    static final String LOC_TOKEN_PREFIX = "LOC:";
    /** 제출 전 선커밋 센티널 — requestId 미확보 상태 (재시작 시 재제출 대상, 외부 비용 미발생). */
    static final String PENDING_SENTINEL = "PENDING";
    /** 컷 단위 자동 재시도 상한 (무과금 — 소진 시 이전 완성본 복귀 또는 FAILED). */
    static final int CUT_MAX_RETRIES = 3;

    private final UgcWorldCreationJobRepository jobRepository;
    private final UgcWorldRepository worldRepository;
    private final UgcWorldLocationRepository locationRepository;
    private final UserRepository userRepository;
    private final UgcPipelineProperties props;
    private final WorldConceptStructuringService structuringService;
    private final UgcModerationService moderationService;
    private final BackgroundPromptAssembler promptAssembler;
    private final FalAiClient falAiClient;
    private final UgcAssetService assetService;
    private final UgcWorldJobJson json;
    private final RedisCacheService cacheService;
    private final NotificationService notificationService;
    private final TransactionTemplate txTemplate;

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  W0 (컨셉 구조화 → 편집 대기)
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    @Async
    public void runStage0(Long jobId) {
        UgcWorldCreationJob job = jobRepository.findById(jobId).orElse(null);
        if (job == null || job.getStatus() != WorldCreationJobStatus.CONCEPT_PROCESSING) return;

        try {
            runWithRetries(jobId, "WORLD-W0", () -> {
                StructuredWorld structured = structuringService.structure(
                    job.getConceptInputRaw(), job.getRequestedName(), job.getMoodHint());
                moderationService.assertStructuredWorldAllowed(structured);

                String structuredJson = json.writeStructured(structured);
                String draftJson = json.writeDraft(draftFrom(structured));
                mutateJob(jobId, j -> {
                    if (j.getStatus() != WorldCreationJobStatus.CONCEPT_PROCESSING) return; // 멱등 가드
                    j.applyStage0(structuredJson);
                    j.toEditWait(draftJson, props.world().ttlHours());
                });
            });
        } catch (ContentModerationException e) {
            // LLM 판정 차단 — 이미 과금된 상태이므로 전액 환불 (게이트 원칙: 유저 에너지 손실 없음)
            failAndRefund(jobId, UgcModerationService.WORLD_BLOCK_MESSAGE);
        } catch (Exception e) {
            failAndRefund(jobId, "세계관 구조화 실패: " + e.getMessage());
        }
    }

    private static WorldDraft draftFrom(StructuredWorld structured) {
        List<WorldDraft.DraftLocation> locations = structured.locations().stream()
            .map(l -> new WorldDraft.DraftLocation(
                l.locationKey(), l.displayName(), l.description(), l.backgroundPrompt()))
            .toList();
        return new WorldDraft(
            structured.world().name(), structured.world().intro(), structured.world().lore(),
            structured.world().moodTags(), structured.thumbnailPrompt(), locations);
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  W2 (썸네일 + 장소 배경 병렬 — flux-2)
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /** 일러 스테이지 진입 — 유저 추가 장소 프롬프트화 → 상태 초기화·PENDING 선커밋 → 전량 병렬 제출. */
    @Async
    public void runIllustration(Long jobId) {
        UgcWorldCreationJob job = jobRepository.findById(jobId).orElse(null);
        if (job == null || job.getStatus() != WorldCreationJobStatus.ILLUSTRATING) return;

        try {
            WorldDraft initial = json.readDraft(job.getDraftWorldJson());

            // 유저 직접 추가 장소의 배경 프롬프트화 (LLM — 유저 텍스트 직결 금지 원칙)
            WorldDraft draft = initial;
            boolean needsPromptize = initial.locations().stream()
                .anyMatch(l -> l.backgroundPrompt() == null || l.backgroundPrompt().isBlank());
            if (needsPromptize) {
                AtomicReference<List<WorldDraft.DraftLocation>> holder = new AtomicReference<>();
                runWithRetries(jobId, "WORLD-PROMPTIZE", () ->
                    holder.set(structuringService.promptizeLocations(initial.lore(), initial.locations())));
                draft = new WorldDraft(initial.name(), initial.intro(), initial.lore(),
                    initial.moodTags(), initial.thumbnailPrompt(), holder.get());
                String draftJson = json.writeDraft(draft);
                mutateJob(jobId, j -> j.updateDraftWorld(draftJson));
            }

            // 상태 초기화 + PENDING 선커밋 (제출 전 — H-16: 커밋 실패 시 외부 비용 0)
            WorldDraft committed = draft;
            Boolean proceed = txTemplate.execute(tx -> {
                UgcWorldCreationJob locked = jobRepository.findByIdForUpdate(jobId).orElse(null);
                if (locked == null || locked.getStatus() != WorldCreationJobStatus.ILLUSTRATING) return false;
                WorldIllustrationAssets assets = WorldIllustrationAssets.empty()
                    .withThumbnail(WorldAssetState.generating(0));
                Map<String, String> scratch = json.readScratch(locked.getExternalJobsJson());
                scratch.put(THUMB_TOKEN, PENDING_SENTINEL);
                for (WorldDraft.DraftLocation loc : committed.locations()) {
                    assets = assets.withLocation(loc.locationKey(), WorldAssetState.generating(0));
                    scratch.put(LOC_TOKEN_PREFIX + loc.locationKey(), PENDING_SENTINEL);
                }
                locked.updateIllustrationAssets(json.writeAssets(assets));
                locked.updateExternalJobs(json.writeScratch(scratch));
                return true;
            });
            if (!Boolean.TRUE.equals(proceed)) return;

            // 전량 병렬 제출 — fal은 동시성 초과분을 거절 없이 큐 대기시킨다 (계정 동시성 2)
            submitIllustration(jobId, THUMB_TOKEN, promptFor(committed, THUMB_TOKEN));
            for (WorldDraft.DraftLocation loc : committed.locations()) {
                String token = LOC_TOKEN_PREFIX + loc.locationKey();
                submitIllustration(jobId, token, promptFor(committed, token));
            }
        } catch (Exception e) {
            failAndRefund(jobId, "일러스트 시작 실패: " + e.getMessage());
        }
    }

    /** 컷 1건 리롤 재제출 (REVIEW_WAIT — 과금·상태 초기화는 서비스 TX에서 완료된 상태). */
    @Async
    public void runIllustrationReroll(Long jobId, String token) {
        UgcWorldCreationJob job = jobRepository.findById(jobId).orElse(null);
        if (job == null || job.getStatus() != WorldCreationJobStatus.REVIEW_WAIT) return;
        String prompt = promptFor(json.readDraft(job.getDraftWorldJson()), token);
        if (prompt == null) return;
        submitIllustration(jobId, token, prompt);
    }

    /**
     * 큐 제출 → requestId 치환 → 완료 대기 부착. PENDING은 호출 전에 이미 커밋돼 있어야 한다.
     */
    private void submitIllustration(Long jobId, String token, String prompt) {
        // [리뷰 픽스] 재제출 경로 상태 가드 — 실패 판정 커밋과 재제출 사이 abandon/만료가 끼면
        // 종결 잡에 외부 비용이 나가는 것을 차단 (캐릭터 트랙 submitEmotionDerivation 규약 동형)
        UgcWorldCreationJob guard = jobRepository.findById(jobId).orElse(null);
        if (guard == null || guard.getStatus().isTerminal()) return;
        if (prompt == null) {
            log.error("[UGC-WORLD] 프롬프트 없는 토큰 제출 시도: jobId={}, token={}", jobId, token);
            handleIllustrationFailure(jobId, token, PENDING_SENTINEL);
            return;
        }
        falAiClient.submitToQueue(FalAiClient.GenerationRequest.background(prompt))
            .whenComplete((requestId, err) -> {
                if (err != null) {
                    log.warn("[UGC-WORLD] fal 제출 실패: jobId={}, token={}, {}", jobId, token, err.getMessage());
                    handleIllustrationFailure(jobId, token, PENDING_SENTINEL);
                    return;
                }
                recordExternalJob(jobId, token, requestId);
                attachAwait(jobId, token, requestId);
            });
    }

    /**
     * 완료 대기 부착 — 스테일 스윕의 재부착 경로와 공용.
     * [리뷰 픽스] 모든 콜백은 스크래치 현재값과 requestId를 대조한다(세대 검증) —
     * 재부착 중복·리롤 세대 교체 후 도착한 구세대 이벤트가 상태를 오염시키지 않는다.
     */
    void attachAwait(Long jobId, String token, String requestId) {
        falAiClient.awaitResult(requestId)
            .whenComplete((result, err) -> {
                if (err != null) {
                    log.warn("[UGC-WORLD] fal 대기 실패: jobId={}, token={}, {}", jobId, token, err.getMessage());
                    handleIllustrationFailure(jobId, token, requestId);
                    return;
                }
                try {
                    onIllustrationResult(jobId, token, requestId, result.imageUrl());
                } catch (Exception e) {
                    log.warn("[UGC-WORLD] 결과 처리 실패: jobId={}, token={}, {}", jobId, token, e.getMessage());
                    handleIllustrationFailure(jobId, token, requestId);
                }
            });
    }

    private void onIllustrationResult(Long jobId, String token, String requestId, String imageUrl) {
        // 락 밖에서 복사 (S3 왕복을 락 안에 두지 않는다) — 중복 이벤트는 아래 세대 가드로 무해
        String label = THUMB_TOKEN.equals(token) ? "thumb"
            : "loc_" + token.substring(LOC_TOKEN_PREFIX.length()).toLowerCase();
        String storedKey = assetService.storeWorldJobAsset(imageUrl, jobId, label);

        txTemplate.executeWithoutResult(tx -> {
            UgcWorldCreationJob job = jobRepository.findByIdForUpdate(jobId).orElse(null);
            if (job == null || job.getStatus().isTerminal()) return;

            Map<String, String> scratch = json.readScratch(job.getExternalJobsJson());
            // 세대 가드: 처리 완료(토큰 부재)·리롤 세대 교체(값 불일치)·중복 재부착 이벤트 전부 IGNORE
            if (!requestId.equals(scratch.get(token))) return;
            scratch.remove(token);
            job.updateExternalJobs(json.writeScratch(scratch));

            WorldIllustrationAssets assets = json.readAssets(job.getIllustrationAssetsJson());
            WorldAssetState state = stateFor(assets, token);
            if (state == null) state = WorldAssetState.generating(0);
            assets = updated(assets, token, state.readyWith(storedKey));
            job.updateIllustrationAssets(json.writeAssets(assets));

            checkIllustrationsSettled(job, assets);
        });
    }

    /**
     * 컷 실패 — 자동 재시도(무과금, PENDING 재커밋 후 재제출), 소진 시 복귀/FAILED 마킹.
     *
     * @param expectedScratch 이 실패 이벤트가 속한 세대의 스크래치 값 (제출 실패=PENDING, 대기 실패=requestId).
     *                        현재값과 불일치하면 구세대/중복 이벤트로 간주해 무시 — retryCount 오소진 방지.
     */
    private void handleIllustrationFailure(Long jobId, String token, String expectedScratch) {
        String verdict = txTemplate.execute(tx -> {
            UgcWorldCreationJob job = jobRepository.findByIdForUpdate(jobId).orElse(null);
            if (job == null || job.getStatus().isTerminal()) return "IGNORE";

            Map<String, String> scratch = json.readScratch(job.getExternalJobsJson());
            if (!expectedScratch.equals(scratch.get(token))) return "IGNORE"; // 구세대/중복 이벤트

            WorldIllustrationAssets assets = json.readAssets(job.getIllustrationAssetsJson());
            WorldAssetState state = stateFor(assets, token);
            if (state == null) state = WorldAssetState.generating(0);

            int next = state.retryCount() + 1;
            if (next <= CUT_MAX_RETRIES) {
                assets = updated(assets, token, state.generatingAgain(next));
                scratch.put(token, PENDING_SENTINEL);
                job.updateIllustrationAssets(json.writeAssets(assets));
                job.updateExternalJobs(json.writeScratch(scratch));
                return "RETRY";
            }
            // 소진: 이전 완성본이 있으면 복귀(리롤 실패가 기존 결과를 파괴하지 않도록), 없으면 FAILED(무료 재시도 대상)
            scratch.remove(token);
            assets = updated(assets, token, state.hasCompletedVersion() ? state.revertToReady() : state.failed());
            job.updateIllustrationAssets(json.writeAssets(assets));
            job.updateExternalJobs(json.writeScratch(scratch));
            checkIllustrationsSettled(job, assets);
            return "SETTLED";
        });
        if ("RETRY".equals(verdict)) {
            UgcWorldCreationJob job = jobRepository.findById(jobId).orElse(null);
            if (job != null) {
                submitIllustration(jobId, token, promptFor(json.readDraft(job.getDraftWorldJson()), token));
            }
        }
    }

    /** 전 컷 정착(READY/FAILED) 시 REVIEW_WAIT 전이 (ILLUSTRATING에서만). 잡 락 TX 내부 전용. */
    private void checkIllustrationsSettled(UgcWorldCreationJob job, WorldIllustrationAssets assets) {
        if (job.getStatus() != WorldCreationJobStatus.ILLUSTRATING) return;
        if (assets.allSettled()) {
            job.toReviewWait(props.world().ttlHours());
        }
    }

    /** 유저 리롤 진입점 — 기존 버전 보존한 채 GENERATING 복귀 + PENDING 선커밋. 서비스 계층(락 TX) 전용. */
    void resetAssetForReroll(UgcWorldCreationJob job, String token) {
        WorldIllustrationAssets assets = json.readAssets(job.getIllustrationAssetsJson());
        WorldAssetState state = stateFor(assets, token);
        if (state == null) state = WorldAssetState.generating(0);
        job.updateIllustrationAssets(json.writeAssets(updated(assets, token, state.generatingAgain(0))));

        Map<String, String> scratch = json.readScratch(job.getExternalJobsJson());
        scratch.put(token, PENDING_SENTINEL);
        job.updateExternalJobs(json.writeScratch(scratch));
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  W3 (에셋 승격 + UgcWorld 확정 저장)
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    @Async
    public void bindWorld(Long jobId) {
        UgcWorldCreationJob job = jobRepository.findById(jobId).orElse(null);
        if (job == null || job.getStatus() != WorldCreationJobStatus.BINDING) return;

        try {
            WorldDraft draft = json.readDraft(job.getDraftWorldJson());
            WorldIllustrationAssets assets = json.readAssets(job.getIllustrationAssetsJson());

            String slug = "ugc-world-" + jobId;
            String thumbDest = assetService.promoteToWorldAsset(assets.thumbnail().key(), slug, "thumbnail.png");
            List<String> bgDests = new ArrayList<>(draft.locations().size());
            for (WorldDraft.DraftLocation loc : draft.locations()) {
                WorldAssetState state = assets.locations().get(loc.locationKey());
                bgDests.add(assetService.promoteToWorldAsset(state.key(), slug,
                    "bg_" + loc.locationKey().toLowerCase() + ".png"));
            }

            Long worldId = txTemplate.execute(tx -> {
                UgcWorldCreationJob locked = jobRepository.findByIdForUpdate(jobId).orElse(null);
                if (locked == null || locked.getStatus() != WorldCreationJobStatus.BINDING) return null; // 재실행 멱등
                UgcWorld world = worldRepository.save(UgcWorld.create(
                    locked.getUserId(), draft.name(), draft.intro(), draft.lore(),
                    joinMood(draft.moodTags()), assetService.publicUrl(thumbDest)));
                for (int i = 0; i < draft.locations().size(); i++) {
                    WorldDraft.DraftLocation loc = draft.locations().get(i);
                    locationRepository.save(UgcWorldLocation.create(
                        world.getId(), loc.locationKey(), loc.displayName(), loc.description(),
                        loc.backgroundPrompt(), assetService.publicUrl(bgDests.get(i)), i));
                }
                locked.toReady(world.getId());
                return world.getId();
            });
            if (worldId == null) return;

            notificationService.notify(job.getUserId(), "WORLD_CREATION_COMPLETE",
                "세계관이 열렸어요",
                draft.name() + " 세계관이 완성되었어요. 캐릭터와 연결해 보세요.",
                "UGC_WORLD", String.valueOf(worldId));

            log.info("[UGC-WORLD] ✅ READY: jobId={}, worldId={}, slug={}", jobId, worldId, slug);
        } catch (Exception e) {
            log.error("[UGC-WORLD] 바인딩 실패: jobId={}", jobId, e);
            failAndRefund(jobId, "세계관 등록 실패: " + e.getMessage());
        }
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  [2026-07-22] READY 월드 사후 장소 추가 — 단발 배경 생성 (잡 없음)
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /**
     * 사후 추가 장소의 배경 생성 — 프롬프트화(LLM) → flux-2 → 확정 경로 저장 → READY.
     * 실패 시 FAILED 마킹(무료 재시도/삭제+환불은 서비스 담당). 재시도 진입도 이 메서드 공용.
     */
    @Async
    public void generateAddedLocationBackground(Long worldId, Long locationId) {
        UgcWorld world = worldRepository.findById(worldId).orElse(null);
        UgcWorldLocation loc = locationRepository.findById(locationId).orElse(null);
        if (world == null || loc == null || !loc.is(UgcWorldLocation.GENERATING)) return;

        try {
            // 유저 텍스트 직결 금지 — 설명은 LLM 프롬프트화를 거친다 (기존 프롬프트 있으면 승계)
            String bgPrompt = loc.getBackgroundPrompt();
            if (bgPrompt == null || bgPrompt.isBlank()) {
                bgPrompt = structuringService.promptizeLocations(world.getLore(),
                        List.of(new WorldDraft.DraftLocation(
                            loc.getLocationKey(), loc.getDisplayName(), loc.getDescription(), null)))
                    .get(0).backgroundPrompt();
            }
            String prompt = promptAssembler.assembleWithMood(bgPrompt, world.getMoodTags());
            // [리뷰 픽스] 무기한 join 금지 — fal 행에 스레드가 영구 점유되고 markFailed조차 못 하는
            // 고착 방지. 타임아웃 시 예외 → catch에서 FAILED 마킹(무료 재시도 경로).
            FalAiClient.GenerationResult result =
                falAiClient.generate(FalAiClient.GenerationRequest.background(prompt))
                    .orTimeout(5, java.util.concurrent.TimeUnit.MINUTES).join();
            String storedKey = assetService.storeWorldLocationAsset(
                result.imageUrl(), worldId, loc.getLocationKey());

            String finalBgPrompt = bgPrompt;
            txTemplate.executeWithoutResult(tx ->
                locationRepository.findById(locationId).ifPresent(l ->
                    l.markReady(finalBgPrompt, assetService.publicUrl(storedKey))));
            log.info("[UGC-WORLD] ✅ 사후 장소 배경 완성: worldId={}, key={}", worldId, loc.getLocationKey());
        } catch (Exception e) {
            log.warn("[UGC-WORLD] 사후 장소 배경 생성 실패: worldId={}, locationId={}, {}",
                worldId, locationId, e.getMessage());
            txTemplate.executeWithoutResult(tx ->
                locationRepository.findById(locationId).ifPresent(UgcWorldLocation::markFailed));
        }
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  실패·만료·복구
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /** 파이프라인 귀책 실패 — 잡 FAILED + 누적 에너지 전액 환불 ({@link UgcPipelineWorker#failAndRefund} 동형). */
    public void failAndRefund(Long jobId, String reason) {
        txTemplate.executeWithoutResult(tx -> {
            UgcWorldCreationJob job = jobRepository.findByIdForUpdate(jobId).orElse(null);
            if (job == null || job.getStatus().isTerminal()) return;
            job.fail(reason);

            int refund = job.getEnergyCharged();
            if (refund > 0) {
                userRepository.findById(job.getUserId()).ifPresent(user -> {
                    user.refundEnergy(refund);
                    userRepository.save(user);
                    cacheService.evictUserProfile(user.getUsername());
                });
            }
            log.warn("[UGC-WORLD] ❌ FAILED: jobId={}, refund={}, reason={}", jobId, refund, reason);
        });
    }

    /** *_WAIT 방치 만료 — 무환불 정책 (스케줄러 호출). */
    public void expireJob(Long jobId) {
        txTemplate.executeWithoutResult(tx -> {
            UgcWorldCreationJob job = jobRepository.findByIdForUpdate(jobId).orElse(null);
            if (job == null || !job.getStatus().isWait()) return;
            job.expire();
            log.info("[UGC-WORLD] ⏰ EXPIRED: jobId={}", jobId);
        });
    }

    /**
     * 스테일 잡 복구 (스케줄러 — N분 무진행 감지 시). fal 전용 트랙은 웹훅/폴링 폴백이 없어
     * 서버 재시작으로 in-flight future가 유실될 수 있다:
     * <ul>
     *   <li>CONCEPT_PROCESSING — LLM 동기 호출 유실. 복구 수단 없음 → 실패·전액 환불</li>
     *   <li>ILLUSTRATING/REVIEW_WAIT — PENDING은 재제출(외부 비용 미발생분), requestId는
     *       {@link #attachAwait} 재부착(완료됐으면 결과 회수, 진행 중이면 대기 재개)</li>
     *   <li>BINDING — S3 승격/저장 재실행 (BINDING 가드로 멱등)</li>
     * </ul>
     */
    public void recoverStaleJob(Long jobId) {
        UgcWorldCreationJob job = jobRepository.findById(jobId).orElse(null);
        if (job == null || job.getStatus().isTerminal()) return;

        switch (job.getStatus()) {
            case CONCEPT_PROCESSING ->
                failAndRefund(jobId, "세계관 구조화 시간 초과 — 에너지는 전액 환불되었어요.");
            case ILLUSTRATING -> {
                Map<String, String> scratch = json.readScratch(job.getExternalJobsJson());
                if (scratch.isEmpty()) {
                    // [리뷰 픽스] ILLUSTRATING + 빈 스크래치 = toIllustrating 커밋 후 runIllustration
                    // (promptize LLM 구간 포함)이 유실된 상태 — TTL 대상도 아니라 방치 시 영구 좀비.
                    // runIllustration은 상태 가드 + PENDING 선커밋 구조라 재기동이 멱등하다.
                    log.info("[UGC-WORLD] 스테일 ILLUSTRATING 재기동 (빈 스크래치): jobId={}", jobId);
                    runIllustration(jobId);
                    return;
                }
                reattachPending(jobId, job, scratch);
            }
            case REVIEW_WAIT -> {
                Map<String, String> scratch = json.readScratch(job.getExternalJobsJson());
                if (scratch.isEmpty()) return; // 순수 검수 대기 — TTL이 담당
                reattachPending(jobId, job, scratch);
            }
            case BINDING -> {
                log.info("[UGC-WORLD] 스테일 BINDING 재실행: jobId={}", jobId);
                bindWorld(jobId);
            }
            default -> { /* WAIT 순수 대기 — TTL 스윕이 담당 */ }
        }
    }

    /**
     * 미결 토큰 복구 — PENDING은 재제출(외부 비용 미발생분), requestId는 대기 재부착.
     * 재부착 후 updatedAt을 터치해 다음 스윕 창(staleMinutes)까지 같은 잡의 중복 재부착을 막는다
     * (콜백 세대 가드로 상태는 안전하나 중복 future의 S3 낭비를 제한).
     */
    private void reattachPending(Long jobId, UgcWorldCreationJob job, Map<String, String> scratch) {
        WorldDraft draft = json.readDraft(job.getDraftWorldJson());
        for (Map.Entry<String, String> entry : scratch.entrySet()) {
            String token = entry.getKey();
            if (PENDING_SENTINEL.equals(entry.getValue())) {
                log.info("[UGC-WORLD] 스테일 PENDING 재제출: jobId={}, token={}", jobId, token);
                submitIllustration(jobId, token, promptFor(draft, token));
            } else {
                log.info("[UGC-WORLD] 스테일 requestId 재부착: jobId={}, token={}", jobId, token);
                attachAwait(jobId, token, entry.getValue());
            }
        }
        mutateJob(jobId, UgcWorldCreationJob::touchRecovery);
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  내부 헬퍼
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    private String promptFor(WorldDraft draft, String token) {
        String mood = joinMood(draft.moodTags());
        if (THUMB_TOKEN.equals(token)) {
            return promptAssembler.assembleWithMood(draft.thumbnailPrompt(), mood);
        }
        if (token != null && token.startsWith(LOC_TOKEN_PREFIX)) {
            String key = token.substring(LOC_TOKEN_PREFIX.length());
            for (WorldDraft.DraftLocation loc : draft.locations()) {
                if (loc.locationKey().equals(key)) {
                    return promptAssembler.assembleWithMood(loc.backgroundPrompt(), mood);
                }
            }
        }
        return null;
    }

    private static WorldAssetState stateFor(WorldIllustrationAssets assets, String token) {
        if (THUMB_TOKEN.equals(token)) return assets.thumbnail();
        return assets.locations().get(token.substring(LOC_TOKEN_PREFIX.length()));
    }

    private static WorldIllustrationAssets updated(WorldIllustrationAssets assets, String token, WorldAssetState state) {
        if (THUMB_TOKEN.equals(token)) return assets.withThumbnail(state);
        return assets.withLocation(token.substring(LOC_TOKEN_PREFIX.length()), state);
    }

    static String joinMood(List<String> moodTags) {
        if (moodTags == null || moodTags.isEmpty()) return null;
        String joined = String.join(", ", moodTags);
        return joined.length() <= 200 ? joined : joined.substring(0, 200);
    }

    /** 외부 호출을 수반하는 스테이지 본문의 즉시-예외 재시도 래퍼 (백오프 2s — 캐릭터 워커 동형). */
    private void runWithRetries(Long jobId, String stageName, Runnable body) {
        int max = props.job().autoRetries() + 1;
        RuntimeException last = null;
        for (int attempt = 1; attempt <= max; attempt++) {
            try {
                body.run();
                return;
            } catch (ContentModerationException e) {
                throw e; // 정책 차단은 재시도 대상 아님
            } catch (RuntimeException e) {
                last = e;
                log.warn("[UGC-WORLD] {} 시도 {}/{} 실패: jobId={}, {}", stageName, attempt, max, jobId, e.getMessage());
                if (attempt < max) {
                    try {
                        Thread.sleep(2000L * attempt);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw e;
                    }
                }
            }
        }
        throw last;
    }

    private void mutateJob(Long jobId, Consumer<UgcWorldCreationJob> mutation) {
        txTemplate.executeWithoutResult(tx ->
            jobRepository.findByIdForUpdate(jobId).ifPresent(mutation));
    }

    /** PENDING → fal requestId 치환 (제출 성공 직후 — 재시작 복구의 열쇠). */
    private void recordExternalJob(Long jobId, String token, String requestId) {
        mutateJob(jobId, j -> {
            Map<String, String> scratch = json.readScratch(j.getExternalJobsJson());
            // [리뷰 픽스] PENDING일 때만 치환 — 이미 다른 세대 requestId가 기록됐거나 처리 완료된
            // 토큰을 늦은 제출 콜백이 되돌리지 않는다
            if (!PENDING_SENTINEL.equals(scratch.get(token))) return;
            scratch.put(token, requestId);
            j.updateExternalJobs(json.writeScratch(scratch));
        });
    }
}
