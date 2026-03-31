package com.spring.aichat.service.illustration;

import com.fasterxml.jackson.databind.JsonNode;
import com.spring.aichat.domain.illustration.BackgroundCache;
import com.spring.aichat.domain.illustration.BackgroundCacheRepository;
import com.spring.aichat.external.FalAiClient;
import com.spring.aichat.external.FalAiClient.QueueResponse;
import com.spring.aichat.external.FalAiClient.PollResult;
import com.spring.aichat.service.cache.RedisCacheService;
import com.spring.aichat.service.prompt.BackgroundPromptAssembler;
import com.spring.aichat.service.storage.S3StorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * [Phase 5.5-Illust] 장소 배경 생성 서비스
 *
 * [Phase 5.5-RunPod] Fal.ai → RunPod 전환에 따른 변경:
 *   - 이미지가 URL이 아닌 Base64 raw data로 반환
 *   - pollUntilComplete() → extractImageBase64() → uploadBackgroundFromBase64()
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class BackgroundGenerationService {

    private final FalAiClient falAiClient;
    private final S3StorageService s3StorageService;
    private final BackgroundPromptAssembler promptAssembler;
    private final BackgroundCacheRepository backgroundCacheRepository;
    private final RedisCacheService cacheService;

    private static final String REDIS_BG_PREFIX = "bg:";
    private static final long REDIS_BG_TTL_SECONDS = -1;

    /** 폴링 최대 시도 횟수 — ComfyUI cold start(모델 DL) 고려하여 3분 */
    private static final int MAX_POLL_ATTEMPTS = 180;
    private static final long POLL_INTERVAL_MS = 1000;
    /** 연속 에러 허용 횟수 — 초과 시 조기 포기 */
    private static final int MAX_CONSECUTIVE_ERRORS = 10;

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  1. 메인 진입점: 배경 URL 조회/생성
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    public BackgroundResult resolveBackground(
        String locationName, String locationDescription,
        String timeOfDay, Long characterId
    ) {
        String cacheHash = BackgroundCache.computeHash(locationName, timeOfDay);

        // Layer 1: Redis
        String redisKey = REDIS_BG_PREFIX + cacheHash;
        String cachedUrl = cacheService.getBackgroundCache(redisKey);
        if (cachedUrl != null) {
            log.info("[BG] Redis cache HIT: {} → {}", locationName, cachedUrl);
            return BackgroundResult.hit(cachedUrl);
        }

        // Layer 2: DB
        Optional<BackgroundCache> dbCache = backgroundCacheRepository.findByCacheHash(cacheHash);
        if (dbCache.isPresent()) {
            BackgroundCache cache = dbCache.get();
            cache.incrementHitCount();
            backgroundCacheRepository.save(cache);
            cacheService.setBackgroundCache(redisKey, cache.getImageUrl());
            log.info("[BG] DB cache HIT: {} → {}", locationName, cache.getImageUrl());
            return BackgroundResult.hit(cache.getImageUrl());
        }

        // Layer 3: Cache MISS
        log.info("[BG] Cache MISS: {}_{} → generating...", locationName, timeOfDay);
        return BackgroundResult.generating(cacheHash, locationName, timeOfDay);
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  2. 비동기 배경 생성 (Cache MISS 시)
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    @Async
    public CompletableFuture<String> generateBackgroundAsync(
        String locationName, String locationDescription,
        String timeOfDay, Long characterId
    ) {
        try {
            String imageUrl = generateBackgroundSync(locationName, locationDescription, timeOfDay, characterId);
            return CompletableFuture.completedFuture(imageUrl);
        } catch (Exception e) {
            log.error("[BG] Async generation failed: {}", locationName, e);
            return CompletableFuture.completedFuture(null);
        }
    }

    /**
     * 동기 배경 생성 (폴링 대기 포함, 최대 ~3분)
     *
     * [Phase 5.5-RunPod] Base64 흐름:
     *   1. ComfyUI 워크플로우 제출
     *   2. 폴링 → COMPLETED 시 extractImageBase64()로 base64 추출
     *   3. uploadBackgroundFromBase64()로 S3 직접 업로드
     */
    public String generateBackgroundSync(
        String locationName, String locationDescription,
        String timeOfDay, Long characterId
    ) {
        String positivePrompt = promptAssembler.assemblePositivePrompt(locationDescription, timeOfDay);
        String negativePrompt = promptAssembler.getNegativePrompt();

        JsonNode workflow = falAiClient.buildLocationWorkflow(positivePrompt, negativePrompt);

        QueueResponse queueResp = falAiClient.submitToQueue(workflow);
        log.info("[BG] Queue submitted: requestId={}, location={}", queueResp.requestId(), locationName);

        // 폴링 → Base64 데이터 반환
        String base64ImageData = pollUntilComplete(queueResp);
        if (base64ImageData == null) {
            log.error("[BG] Generation failed or timed out: {}", locationName);
            return null;
        }

        // Base64 → S3 직접 업로드
        String cacheHash = BackgroundCache.computeHash(locationName, timeOfDay);
        String s3Url = s3StorageService.uploadBackgroundFromBase64(base64ImageData, cacheHash);

        // DB 캐시 등록
        persistCache(locationName, timeOfDay, s3Url, positivePrompt, characterId, queueResp.requestId());

        // Redis 캐시 등록
        String redisKey = REDIS_BG_PREFIX + cacheHash;
        cacheService.setBackgroundCache(redisKey, s3Url);

        log.info("[BG] Generated & cached: {} → {}", locationName, s3Url);
        return s3Url;
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  3. 웹훅 콜백 처리
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    @Transactional
    public void handleWebhookCallback(String requestId, JsonNode payload) {
        log.info("[BG-WEBHOOK] Received: requestId={}", requestId);

        backgroundCacheRepository.findByFalRequestId(requestId)
            .ifPresent(cache -> {
                if (cache.getImageUrl() != null && !cache.getImageUrl().isBlank()) {
                    log.info("[BG-WEBHOOK] Already processed (idempotent): {}", requestId);
                    return;
                }

                // [RunPod] Base64 데이터 추출
                String base64Data = falAiClient.extractImageBase64(payload);
                if (base64Data == null) {
                    log.warn("[BG-WEBHOOK] No image data in payload: {}", requestId);
                    return;
                }

                try {
                    // Base64 → S3 직접 업로드
                    String s3Url = s3StorageService.uploadBackgroundFromBase64(base64Data, cache.getCacheHash());

                    cache = backgroundCacheRepository.findByFalRequestId(requestId).orElse(null);
                    if (cache != null) {
                        String redisKey = REDIS_BG_PREFIX + cache.getCacheHash();
                        cacheService.setBackgroundCache(redisKey, s3Url);
                    }
                    log.info("[BG-WEBHOOK] Processed: requestId={}, s3Url={}", requestId, s3Url);
                } catch (Exception e) {
                    log.error("[BG-WEBHOOK] Processing failed: {}", requestId, e);
                }
            });
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  내부 헬퍼
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /**
     * 폴링 → COMPLETED 시 Base64 이미지 데이터 반환
     */
    private String pollUntilComplete(QueueResponse queueResp) {
        String lastStatus = "";
        int consecutiveErrors = 0;
        long startTime = System.currentTimeMillis();

        for (int i = 0; i < MAX_POLL_ATTEMPTS; i++) {
            try {
                Thread.sleep(POLL_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }

            PollResult poll = falAiClient.pollStatus(queueResp.statusUrl());
            String currentStatus = poll.status();

            // 상태 변화 감지 로깅
            if (!currentStatus.equals(lastStatus)) {
                long elapsed = (System.currentTimeMillis() - startTime) / 1000;
                log.info("[BG] Status changed: {} → {} | elapsed={}s | requestId={}",
                    lastStatus.isEmpty() ? "(start)" : lastStatus,
                    currentStatus, elapsed, queueResp.requestId());
                lastStatus = currentStatus;
            }

            // 10초마다 진행률 로깅
            if (i > 0 && i % 10 == 0) {
                long elapsed = (System.currentTimeMillis() - startTime) / 1000;
                log.info("[BG] Still polling: status={} | attempt={}/{} | elapsed={}s | requestId={}",
                    currentStatus, i, MAX_POLL_ATTEMPTS, elapsed, queueResp.requestId());
            }

            // 완료 → Base64 추출
            if (poll.completed()) {
                long elapsed = (System.currentTimeMillis() - startTime) / 1000;
                log.info("[BG] ✅ Generation completed in {}s | requestId={}", elapsed, queueResp.requestId());

                JsonNode fullResult = poll.payload(); // RunPod은 폴링 결과에 페이로드 포함
                return falAiClient.extractImageBase64(fullResult);
            }

            if ("FAILED".equalsIgnoreCase(currentStatus)) {
                log.error("[BG] ❌ RunPod generation FAILED: requestId={}", queueResp.requestId());
                return null;
            }

            // 연속 에러 감지
            if ("ERROR".equalsIgnoreCase(currentStatus)) {
                consecutiveErrors++;
                if (consecutiveErrors >= MAX_CONSECUTIVE_ERRORS) {
                    log.error("[BG] ❌ {} consecutive poll errors, aborting | requestId={}",
                        consecutiveErrors, queueResp.requestId());
                    return null;
                }
            } else {
                consecutiveErrors = 0;
            }
        }

        long elapsed = (System.currentTimeMillis() - startTime) / 1000;
        log.warn("[BG] ⏱ Polling timed out after {}s | lastStatus={} | requestId={}",
            elapsed, lastStatus, queueResp.requestId());
        return null;
    }

    @Transactional
    protected void persistCache(
        String locationName, String timeOfDay, String imageUrl,
        String promptUsed, Long characterId, String falRequestId
    ) {
        String cacheHash = BackgroundCache.computeHash(locationName, timeOfDay);
        if (backgroundCacheRepository.findByCacheHash(cacheHash).isPresent()) {
            log.info("[BG] Cache already exists (race condition handled): {}", cacheHash);
            return;
        }

        BackgroundCache cache = BackgroundCache.create(
            locationName, timeOfDay, imageUrl, promptUsed, characterId, falRequestId);
        backgroundCacheRepository.save(cache);
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  DTO
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    public record BackgroundResult(
        boolean isCacheHit, String imageUrl,
        String cacheHash, String locationName, String timeOfDay
    ) {
        public static BackgroundResult hit(String imageUrl) {
            return new BackgroundResult(true, imageUrl, null, null, null);
        }
        public static BackgroundResult generating(String cacheHash, String locationName, String timeOfDay) {
            return new BackgroundResult(false, null, cacheHash, locationName, timeOfDay);
        }
    }
}