package com.spring.aichat.service.illustration;

import com.fasterxml.jackson.databind.JsonNode;
import com.spring.aichat.domain.character.CharacterRepository;
import com.spring.aichat.domain.illustration.BackgroundCache;
import com.spring.aichat.domain.illustration.BackgroundCacheRepository;
import com.spring.aichat.domain.ugc.UgcWorldLocation;
import com.spring.aichat.domain.ugc.UgcWorldLocationRepository;
import com.spring.aichat.domain.ugc.UgcWorldRepository;
import com.spring.aichat.domain.world.World;
import com.spring.aichat.external.FalAiClient;
import com.spring.aichat.external.ModelsLabClient;
import com.spring.aichat.service.cache.RedisCacheService;
import com.spring.aichat.service.prompt.BackgroundPromptAssembler;
import com.spring.aichat.service.storage.S3StorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * [Phase 5.5-Illust] 장소 배경 생성 서비스
 *
 * [Phase 6-Illust] 두 트랙 결선:
 * <ul>
 *   <li><b>일반 배경</b> → Fal.ai Flux 2 Dev (검열 무관, 자연어 친화, $0.012/MP)</li>
 *   <li><b>Secret Mode 분위기 배경</b> → ModelsLab (NSFW 정식 허용, 동일 화풍 유지)</li>
 * </ul>
 *
 * <p>캐시 키는 canonical_key 기반 SHA-256으로 전환. 시그니처가 변경되어 호출처
 * (ChatStreamService, TheaterBatchGenerator)도 함께 갱신 필요.
 *
 * <p>구버전 시그니처(locationName 직해싱)는 deprecated 유지 — 점진 이행.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class BackgroundGenerationService {

    private final FalAiClient falAiClient;
    private final ModelsLabClient modelsLabClient;
    private final S3StorageService s3StorageService;
    private final BackgroundPromptAssembler promptAssembler;
    private final BackgroundCacheRepository backgroundCacheRepository;
    private final RedisCacheService cacheService;
    // [세계관 빌더] UGC 월드 장소 풀 인터셉트·무드 주입용
    private final CharacterRepository characterRepository;
    private final UgcWorldRepository ugcWorldRepository;
    private final UgcWorldLocationRepository ugcWorldLocationRepository;

    private static final String REDIS_BG_PREFIX = "bg:";

    // [Phase 6-Illust] 폴링 간격 단축 (1000ms × 180회 = 3min → 500ms × 60회 = 30s)
    private static final int MAX_POLL_ATTEMPTS = 60;
    private static final long POLL_INTERVAL_MS = 500;
    private static final int MAX_CONSECUTIVE_ERRORS = 10;

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  1. 캐시 조회 — Phase 6 신 시그니처 (canonical_key 기반)
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /**
     * 배경 캐시 조회. canonical_key가 hash source.
     *
     * @param locationName         표시용 이름 (UI에 노출, 미스 시 신 entry에 저장)
     * @param canonicalKey         정규화 캐시 키 ({@code <WORLD>__<CATEGORY>_<MOD>}). null이면 폴백으로 locationName 직해싱.
     * @param locationDescription  Flux/ModelsLab에 전달할 LLM 자연어 묘사
     * @param timeOfDay            시간대 enum
     * @param characterId          연관 캐릭터 ID
     */
    public BackgroundResult resolveBackground(
        String locationName, String canonicalKey, String locationDescription,
        String timeOfDay, Long characterId
    ) {
        // [세계관 빌더] UGC 월드 장소 풀 인터셉트 — 사전 확정 대표 배경 1장을 즉시 서빙.
        //   BackgroundCache(전역 공유 캐시)를 경유하지 않아 canonical key 오염/timeOfDay 차원 문제가 없다.
        //   미스매치(LLM 즉석 장소)면 기존 동적 생성 경로로 폴스루.
        String ugcWorldUrl = resolveUgcWorldBackground(characterId, canonicalKey, locationName);
        if (ugcWorldUrl != null) {
            log.info("[BG] UGC world location HIT: ckey={} → {}", canonicalKey, ugcWorldUrl);
            return BackgroundResult.hit(ugcWorldUrl);
        }

        String cacheHash = BackgroundCache.computeHash(canonicalKey, timeOfDay, locationName);

        // Layer 1: Redis
        String redisKey = REDIS_BG_PREFIX + cacheHash;
        String cachedUrl = cacheService.getBackgroundCache(redisKey);
        if (cachedUrl != null) {
            log.info("[BG] Redis cache HIT: ckey={} → {}", canonicalKey, cachedUrl);
            return BackgroundResult.hit(cachedUrl);
        }

        // Layer 2: DB
        Optional<BackgroundCache> dbCache = backgroundCacheRepository.findByCacheHash(cacheHash);
        if (dbCache.isPresent()) {
            BackgroundCache cache = dbCache.get();
            cache.incrementHitCount();
            backgroundCacheRepository.save(cache);
            cacheService.setBackgroundCache(redisKey, cache.getImageUrl());
            log.info("[BG] DB cache HIT: ckey={} → {}", canonicalKey, cache.getImageUrl());
            return BackgroundResult.hit(cache.getImageUrl());
        }

        // Layer 3: Cache MISS
        log.info("[BG] Cache MISS: ckey={}_{} → generating...", canonicalKey, timeOfDay);
        return BackgroundResult.generating(cacheHash, locationName, canonicalKey, timeOfDay);
    }

    /** 구버전 호환 시그니처 — 점진 이행 중 호출처 보호용. canonical_key 부재 시 폴백. */
    @Deprecated
    public BackgroundResult resolveBackground(
        String locationName, String locationDescription, String timeOfDay, Long characterId
    ) {
        return resolveBackground(locationName, null, locationDescription, timeOfDay, characterId);
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  1-b. 캐시 상태 조회 (프론트 폴링용 — 생성 트리거 없음)
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /**
     * [Phase 6-Illust hotfix] cacheHash로 배경 완성 여부만 조회.
     *
     * <p>장소 전환이 캐시 미스로 떨어지면 ChatStreamService가 비동기 생성을 트리거하고
     * 응답에 cacheHash만 실어 보낸다(backgroundUrl=null). 프론트는 이 cacheHash로
     * 본 메서드를 노출하는 엔드포인트를 폴링하여 완성을 감지한다.
     *
     * <p><b>resolveBackground와 달리 생성 트리거/락 획득을 하지 않는다</b> — 순수 read-only.
     * Redis(L1) → DB(L2) 순으로 조회만.
     *
     * @return 완성됐으면 imageUrl, 아직이면 null
     */
    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    public String peekByCacheHash(String cacheHash) {
        if (cacheHash == null || cacheHash.isBlank()) return null;

        // L1: Redis
        String redisUrl = cacheService.getBackgroundCache(REDIS_BG_PREFIX + cacheHash);
        if (redisUrl != null && !redisUrl.isBlank()) {
            return redisUrl;
        }

        // L2: DB (Redis 누락/만료 대비)
        return backgroundCacheRepository.findByCacheHash(cacheHash)
            .map(BackgroundCache::getImageUrl)
            .filter(u -> u != null && !u.isBlank())
            .map(u -> {
                // DB엔 있는데 Redis에 없으면 워밍업 (다음 요청 빠르게)
                cacheService.setBackgroundCache(REDIS_BG_PREFIX + cacheHash, u);
                return u;
            })
            .orElse(null);
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  2. 비동기 배경 생성
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /**
     * 비동기 배경 생성. In-flight 락으로 중복 차단.
     *
     * @param world          세계관 (mood prefix용, nullable)
     * @param secretMode     true면 ModelsLab 트랙으로 라우팅 (NSFW 분위기 배경)
     */
    @Async("backgroundGenExecutor")
    public CompletableFuture<String> generateBackgroundAsync(
        String locationName, String canonicalKey, String locationDescription,
        String timeOfDay, Long characterId, World world, boolean secretMode
    ) {
        String cacheHash = BackgroundCache.computeHash(canonicalKey, timeOfDay, locationName);

        if (!cacheService.tryAcquireBgGenerationLock(cacheHash)) {
            log.info("[BG] In-flight lock busy — skip duplicate generation: ckey={}/{} (hash={})",
                canonicalKey, timeOfDay, cacheHash);
            return CompletableFuture.completedFuture(null);
        }

        try {
            // race-condition 차단: 락 획득 직후 영구 캐시 재확인
            String redisKey = REDIS_BG_PREFIX + cacheHash;
            String cached = cacheService.getBackgroundCache(redisKey);
            if (cached != null) {
                log.info("[BG] Skipped — became cached during lock acquisition: {}", cached);
                return CompletableFuture.completedFuture(cached);
            }
            if (backgroundCacheRepository.findByCacheHash(cacheHash).isPresent()) {
                log.info("[BG] Skipped — DB has it: hash={}", cacheHash);
                return CompletableFuture.completedFuture(null);
            }

            String imageUrl = generateBackgroundSync(
                locationName, canonicalKey, locationDescription, timeOfDay, characterId, world, secretMode
            );
            return CompletableFuture.completedFuture(imageUrl);
        } catch (Exception e) {
            log.error("[BG] Async generation failed: ckey={}", canonicalKey, e);
            return CompletableFuture.completedFuture(null);
        } finally {
            cacheService.releaseBgGenerationLock(cacheHash);
        }
    }

    /** 구버전 호환 시그니처 — World/secretMode 모름. */
    @Deprecated
    @Async("backgroundGenExecutor")
    public CompletableFuture<String> generateBackgroundAsync(
        String locationName, String locationDescription, String timeOfDay, Long characterId
    ) {
        return generateBackgroundAsync(locationName, null, locationDescription, timeOfDay, characterId, null, false);
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  3. 동기 배경 생성 (실제 호출)
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /**
     * 동기 배경 생성 (폴링 포함).
     *
     * <p>provider 분기:
     * <ul>
     *   <li>secretMode=false → Fal.ai Flux 2 Dev</li>
     *   <li>secretMode=true  → ModelsLab (캐릭터 트랙과 동일 플랫폼으로 화풍 일치)</li>
     * </ul>
     */
    public String generateBackgroundSync(
        String locationName, String canonicalKey, String locationDescription,
        String timeOfDay, Long characterId, World world, boolean secretMode
    ) {
        // [Phase 6 v2] Flux 2는 positive-only. 시간대 분위기는 LLM이 locationDescription에 포함.
        //   negative 금지사항은 promptAssembler가 prompt 후미에 자연어로 통합함.
        // [세계관 빌더 수정] 기존 String.valueOf(world)가 deprecated (String,String) 오버로드에
        //   바인딩되어 World 인자가 통째로 무시되던 버그 정리 + UGC 월드는 moodTags를 무드로 주입
        //   (즉석 동적 장소의 세계관 정합성 이중 안전망).
        String ugcMood = (world == null) ? resolveUgcWorldMood(characterId) : null;
        String positivePrompt = (ugcMood != null)
            ? promptAssembler.assembleWithMood(locationDescription, ugcMood)
            : promptAssembler.assemblePositivePrompt(locationDescription, world);
        String cacheHash = BackgroundCache.computeHash(canonicalKey, timeOfDay, locationName);

        String s3Url;
        String providerRequestId;

        if (secretMode) {
            // ── ModelsLab 트랙 (Secret Mode 분위기) ──
            // ModelsLab은 SDXL 기반이라 negative prompt 지원. 배경 표준 negative 사용.
            String trackId = "BG_" + cacheHash;  // "BG_" prefix → webhook이 캐릭터/배경 분기 가능
            ModelsLabClient.SubmitResult submit = modelsLabClient.submit(
                new ModelsLabClient.GenerationRequest(null, positivePrompt,
                    "people, characters, text, watermark, logo, low quality, blurry",
                    null, trackId)
            );
            providerRequestId = submit.generationId();
            log.info("[BG] ModelsLab submitted (secret): id={}, ckey={}", providerRequestId, canonicalKey);

            String imageUrl = submit.syncCompleted() ? submit.imageUrl()
                : pollModelsLabUntilComplete(submit.fetchUrl(), providerRequestId);
            if (imageUrl == null) {
                log.error("[BG] ModelsLab generation failed: ckey={}", canonicalKey);
                return null;
            }
            s3Url = s3StorageService.downloadAndUpload(imageUrl, "backgrounds/", cacheHash);

        } else {
            // ── Fal.ai 트랙 (일반 배경, fal-ai/flux-2 표준 큐, 공식 SDK) ──
            // SDK subscribe가 큐 제출+폴링+결과를 자동 처리. negative_prompt 없음 (positive-only).
            // 현 스레드는 @Async("backgroundGenExecutor") 풀 위 → 블로킹 허용.
            FalAiClient.GenerationResult falResult = falAiClient.generateBlocking(
                FalAiClient.GenerationRequest.background(positivePrompt)
            );
            if (falResult == null || falResult.imageUrl() == null) {
                log.error("[BG] Fal.ai generation failed: ckey={}", canonicalKey);
                return null;
            }
            providerRequestId = falResult.requestId();
            log.info("[BG] Fal.ai completed: requestId={}, ckey={}", providerRequestId, canonicalKey);
            s3Url = s3StorageService.downloadAndUpload(falResult.imageUrl(), "backgrounds/", cacheHash);
        }

        // DB + Redis 캐시 등록
        persistCache(locationName, canonicalKey, timeOfDay, s3Url, positivePrompt, characterId, providerRequestId);
        cacheService.setBackgroundCache(REDIS_BG_PREFIX + cacheHash, s3Url);

        log.info("[BG] Generated & cached: ckey={} → {}", canonicalKey, s3Url);
        return s3Url;
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  4. Webhook 콜백 — ModelsLab 전용 (Fal은 SDK subscribe가 완료 자동 처리, webhook 불필요)
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /**
     * [Phase 6-Illust] ModelsLab webhook 콜백 (Secret Mode 배경 트랙).
     */
    @Transactional
    public void handleModelsLabWebhookCallback(String generationId, JsonNode payload) {
        log.info("[BG-WEBHOOK] (ModelsLab) Received: id={}", generationId);

        backgroundCacheRepository.findByFalRequestId(generationId).ifPresent(cache -> {
            if (cache.getImageUrl() != null && !cache.getImageUrl().isBlank()) {
                log.info("[BG-WEBHOOK] (ModelsLab) Already processed (idempotent): {}", generationId);
                return;
            }
            String imageUrl = modelsLabClient.extractFirstOutputUrl(payload);
            if (imageUrl == null) {
                log.warn("[BG-WEBHOOK] (ModelsLab) No image url in payload: {}", generationId);
                return;
            }
            uploadAndCache(cache, imageUrl, generationId);
        });
    }

    private void uploadAndCache(BackgroundCache cache, String imageUrl, String providerRequestId) {
        try {
            String s3Url = s3StorageService.downloadAndUpload(imageUrl, "backgrounds/", cache.getCacheHash());
            String redisKey = REDIS_BG_PREFIX + cache.getCacheHash();
            cacheService.setBackgroundCache(redisKey, s3Url);
            log.info("[BG-WEBHOOK] Processed: requestId={}, s3Url={}", providerRequestId, s3Url);
        } catch (Exception e) {
            log.error("[BG-WEBHOOK] Processing failed: {}", providerRequestId, e);
        }
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  5. 폴링 — ModelsLab 전용 (Fal은 SDK가 내부 폴링)
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    private String pollModelsLabUntilComplete(String fetchUrl, String generationId) {
        String lastStatus = "";
        int consecutiveErrors = 0;

        for (int i = 0; i < MAX_POLL_ATTEMPTS; i++) {
            try { Thread.sleep(POLL_INTERVAL_MS); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); return null; }

            ModelsLabClient.PollResult poll = modelsLabClient.fetch(fetchUrl, generationId);
            String currentStatus = poll.status();
            if (!currentStatus.equals(lastStatus)) {
                log.info("[BG] (ModelsLab) Status: {} → {} | id={}",
                    lastStatus.isEmpty() ? "(start)" : lastStatus, currentStatus, generationId);
                lastStatus = currentStatus;
            }
            if (poll.completed()) {
                return poll.imageUrl();
            }
            if ("FAILED".equalsIgnoreCase(currentStatus)) {
                log.error("[BG] (ModelsLab) FAILED: id={}", generationId);
                return null;
            }
            if ("ERROR".equalsIgnoreCase(currentStatus)) {
                if (++consecutiveErrors >= MAX_CONSECUTIVE_ERRORS) {
                    log.error("[BG] (ModelsLab) {} consecutive errors, abort: id={}",
                        consecutiveErrors, generationId);
                    return null;
                }
            } else {
                consecutiveErrors = 0;
            }
        }
        log.warn("[BG] (ModelsLab) ⏱ Polling timed out: id={}, lastStatus={}", generationId, lastStatus);
        return null;
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  6. 캐시 영속화
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    @Transactional
    protected void persistCache(
        String locationName, String canonicalKey, String timeOfDay,
        String s3Url, String promptUsed, Long characterId, String providerRequestId
    ) {
        String cacheHash = BackgroundCache.computeHash(canonicalKey, timeOfDay, locationName);
        if (backgroundCacheRepository.findByCacheHash(cacheHash).isPresent()) {
            log.info("[BG] Cache already persisted (idempotent): {}", cacheHash);
            return;
        }
        BackgroundCache cache = BackgroundCache.create(
            locationName, canonicalKey, timeOfDay, s3Url, promptUsed, characterId, providerRequestId
        );
        backgroundCacheRepository.save(cache);
        log.info("[BG] DB cache persisted: ckey={} hash={}", canonicalKey, cacheHash);
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  7. [세계관 빌더] UGC 월드 연동
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /**
     * UGC 월드 캐릭터의 장소 풀 매칭 — canonical key({@code UGCW_{worldId}__{KEY}}) 정확 일치 우선,
     * 표시명 정확 일치 폴백(LLM 키 드리프트 방어). 매칭 실패나 배경 미보유면 null(기존 경로 폴스루).
     */
    private String resolveUgcWorldBackground(Long characterId, String canonicalKey, String locationName) {
        Long ugcWorldId = resolveUgcWorldId(characterId);
        if (ugcWorldId == null) return null;

        List<UgcWorldLocation> locations =
            ugcWorldLocationRepository.findByUgcWorldIdAndActiveTrueOrderByDisplayOrderAsc(ugcWorldId);
        if (locations.isEmpty()) return null;

        // [리뷰 픽스] 2-패스 — canonical key 정확 일치를 전수 확인한 뒤에만 표시명 폴백.
        // (동명 장소가 있을 때 앞순서 장소의 nameMatch가 뒷장소의 keyMatch를 가로채지 않도록)
        String keyPrefix = "UGCW_" + ugcWorldId + "__";
        for (UgcWorldLocation loc : locations) {
            if (canonicalKey != null && canonicalKey.equals(keyPrefix + loc.getLocationKey())
                && loc.getBackgroundUrl() != null && !loc.getBackgroundUrl().isBlank()) {
                return loc.getBackgroundUrl();
            }
        }
        for (UgcWorldLocation loc : locations) {
            if (locationName != null && locationName.trim().equals(loc.getDisplayName())
                && loc.getBackgroundUrl() != null && !loc.getBackgroundUrl().isBlank()) {
                return loc.getBackgroundUrl();
            }
        }
        return null;
    }

    /** UGC 월드의 무드 태그 CSV — 즉석 동적 장소 생성 시 프롬프트 무드 주입용. */
    private String resolveUgcWorldMood(Long characterId) {
        Long ugcWorldId = resolveUgcWorldId(characterId);
        if (ugcWorldId == null) return null;
        return ugcWorldRepository.findById(ugcWorldId)
            .map(w -> w.getMoodTags())
            .filter(m -> m != null && !m.isBlank())
            .orElse(null);
    }

    private Long resolveUgcWorldId(Long characterId) {
        if (characterId == null) return null;
        return characterRepository.findById(characterId)
            .map(c -> c.getUgcWorldId())
            .orElse(null);
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  결과 DTO
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    public record BackgroundResult(
        boolean cacheHit, String imageUrl,
        String cacheHash, String locationName, String canonicalKey, String timeOfDay
    ) {
        public static BackgroundResult hit(String imageUrl) {
            return new BackgroundResult(true, imageUrl, null, null, null, null);
        }
        public static BackgroundResult generating(String cacheHash, String locationName, String canonicalKey, String timeOfDay) {
            return new BackgroundResult(false, null, cacheHash, locationName, canonicalKey, timeOfDay);
        }
    }
}