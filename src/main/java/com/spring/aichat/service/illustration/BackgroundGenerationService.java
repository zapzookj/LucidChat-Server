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
 * 전체 흐름 (씬 전환 시):
 *   1. LLM Output → new_location_name + location_description 필드 감지
 *   2. ChatStreamService가 이 서비스 호출
 *   3. Cache Check: 해시(locationName + timeOfDay) → BackgroundCache 테이블 조회
 *      - Hit → S3 URL 즉시 반환 (Redis 캐시도 확인)
 *      - Miss → Fal.ai 비동기 생성 → 완료 후 S3 적재 + DB/Redis 캐싱
 *   4. 프론트: 암전 + "새로운 장소로 이동 중..." 타자기 효과로 레이턴시 마스킹
 *
 * ※ 유저 에너지 미차감 (시스템 비용)
 * ※ 스토리 모드 전용
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

    /** Redis 캐시 키 접두사 */
    private static final String REDIS_BG_PREFIX = "bg:";

    /** Redis 캐시 TTL: 영구 (배경은 불변 자산) */
    private static final long REDIS_BG_TTL_SECONDS = -1; // 무기한

    /** 폴링 최대 시도 횟수 */
    private static final int MAX_POLL_ATTEMPTS = 90;

    /** 폴링 간격 (ms) */
    private static final long POLL_INTERVAL_MS = 1000;

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  1. 메인 진입점: 배경 URL 조회/생성
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /**
     * 장소 배경 이미지 URL 조회
     *
     * 캐시 히트 시 즉시 반환, 미스 시 비동기 생성 후 CompletableFuture로 반환.
     *
     * @param locationName        LLM이 제공한 새 장소 이름 (한글 or 영문)
     * @param locationDescription LLM이 제공한 장소 묘사 (영문 프롬프트 수준)
     * @param timeOfDay           시간대 enum 문자열
     * @param characterId         연관 캐릭터 ID
     * @return 배경 이미지 S3 URL 또는 생성 진행 정보
     */
    public BackgroundResult resolveBackground(
        String locationName, String locationDescription,
        String timeOfDay, Long characterId
    ) {
        String cacheHash = BackgroundCache.computeHash(locationName, timeOfDay);

        // ── Layer 1: Redis 캐시 체크 ──
        String redisKey = REDIS_BG_PREFIX + cacheHash;
        String cachedUrl = cacheService.getBackgroundCache(redisKey);
        if (cachedUrl != null) {
            log.info("[BG] Redis cache HIT: {} → {}", locationName, cachedUrl);
            return BackgroundResult.hit(cachedUrl);
        }

        // ── Layer 2: DB 캐시 체크 ──
        Optional<BackgroundCache> dbCache = backgroundCacheRepository.findByCacheHash(cacheHash);
        if (dbCache.isPresent()) {
            BackgroundCache cache = dbCache.get();
            cache.incrementHitCount();
            backgroundCacheRepository.save(cache);

            // Redis에도 적재
            cacheService.setBackgroundCache(redisKey, cache.getImageUrl());

            log.info("[BG] DB cache HIT: {} → {}", locationName, cache.getImageUrl());
            return BackgroundResult.hit(cache.getImageUrl());
        }

        // ── Layer 3: Cache MISS → 생성 필요 ──
        log.info("[BG] Cache MISS: {}_{} → generating...", locationName, timeOfDay);
        return BackgroundResult.generating(cacheHash, locationName, timeOfDay);
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  2. 비동기 배경 생성 (Cache MISS 시)
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /**
     * 비동기로 배경 생성 + S3 적재 + 캐시 등록
     *
     * @return CompletableFuture<String> S3 URL
     */
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
     * 동기 배경 생성 (폴링 대기 포함, 최대 ~90초)
     * SSE 컨텍스트에서 호출 시 별도 스레드에서 실행해야 함.
     */
    public String generateBackgroundSync(
        String locationName, String locationDescription,
        String timeOfDay, Long characterId
    ) {
        // 프롬프트 조립
        String positivePrompt = promptAssembler.assemblePositivePrompt(locationDescription, timeOfDay);
        String negativePrompt = promptAssembler.getNegativePrompt();

        // ComfyUI 워크플로우 빌드 (장소용)
        JsonNode workflow = falAiClient.buildLocationWorkflow(positivePrompt, negativePrompt);

        // Fal.ai 비동기 큐 제출
        QueueResponse queueResp = falAiClient.submitToQueue(workflow);
        log.info("[BG] Queue submitted: requestId={}, location={}", queueResp.requestId(), locationName);

        // 폴링 대기
        String falImageUrl = pollUntilComplete(queueResp);
        if (falImageUrl == null) {
            log.error("[BG] Generation failed or timed out: {}", locationName);
            return null;
        }

        // S3 영구 적재
        String cacheHash = BackgroundCache.computeHash(locationName, timeOfDay);
        String s3Url = s3StorageService.uploadBackground(falImageUrl, cacheHash);

        // DB 캐시 등록
        persistCache(locationName, timeOfDay, s3Url, positivePrompt, characterId, queueResp.requestId());

        // Redis 캐시 등록
        String redisKey = REDIS_BG_PREFIX + cacheHash;
        cacheService.setBackgroundCache(redisKey, s3Url);

        log.info("[BG] Generated & cached: {} → {}", locationName, s3Url);
        return s3Url;
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  3. 웹훅 콜백 처리 (Fal.ai → 백엔드)
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /**
     * Fal.ai 웹훅으로 배경 생성 완료 통보 수신
     * (웹훅 모드에서만 사용)
     */
    @Transactional
    public void handleWebhookCallback(String requestId, JsonNode payload) {
        log.info("[BG-WEBHOOK] Received: requestId={}", requestId);

        // requestId로 대기 중인 캐시 엔트리 찾기
        // 웹훅 모드에서는 사전에 PENDING 상태로 저장해둔 레코드가 있어야 함
        backgroundCacheRepository.findByFalRequestId(requestId)
            .ifPresent(cache -> {
                if (cache.getImageUrl() != null && !cache.getImageUrl().isBlank()) {
                    log.info("[BG-WEBHOOK] Already processed (idempotent): {}", requestId);
                    return;
                }

                String falImageUrl = falAiClient.extractImageUrl(payload);
                if (falImageUrl == null) {
                    log.warn("[BG-WEBHOOK] No image in payload: {}", requestId);
                    return;
                }

                try {
                    String s3Url = s3StorageService.uploadBackground(falImageUrl, cache.getCacheHash());
                    // DB 업데이트는 새 엔트리로 (웹훅에서는 이미 persistCache된 후 URL 업데이트)
                    cache = backgroundCacheRepository.findByFalRequestId(requestId).orElse(null);
                    if (cache != null) {
                        // 직접 업데이트 (imageUrl 세팅은 create 시점에서 하지만, 웹훅에서는 후속 처리)
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

    private String pollUntilComplete(QueueResponse queueResp) {
        for (int i = 0; i < MAX_POLL_ATTEMPTS; i++) {
            try {
                Thread.sleep(POLL_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }

            PollResult poll = falAiClient.pollStatus(queueResp.statusUrl());
            if (poll.completed()) {
                // 전체 결과 조회
                JsonNode fullResult = queueResp.responseUrl() != null
                    ? falAiClient.fetchResult(queueResp.responseUrl())
                    : poll.payload();
                return falAiClient.extractImageUrl(fullResult);
            }

            if ("FAILED".equalsIgnoreCase(poll.status())) {
                log.error("[BG] Fal.ai generation failed: requestId={}", queueResp.requestId());
                return null;
            }
        }

        log.warn("[BG] Polling timed out: requestId={}", queueResp.requestId());
        return null;
    }

    @Transactional
    protected void persistCache(
        String locationName, String timeOfDay, String imageUrl,
        String promptUsed, Long characterId, String falRequestId
    ) {
        String cacheHash = BackgroundCache.computeHash(locationName, timeOfDay);
        // 중복 방지
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
        boolean isCacheHit,
        String imageUrl,        // 캐시 히트 시 즉시 사용 가능한 URL
        String cacheHash,       // 캐시 미스 시 해시 키
        String locationName,    // 캐시 미스 시 장소 이름
        String timeOfDay        // 캐시 미스 시 시간대
    ) {
        public static BackgroundResult hit(String imageUrl) {
            return new BackgroundResult(true, imageUrl, null, null, null);
        }
        public static BackgroundResult generating(String cacheHash, String locationName, String timeOfDay) {
            return new BackgroundResult(false, null, cacheHash, locationName, timeOfDay);
        }
    }
}