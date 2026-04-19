package com.spring.aichat.service.theater;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.spring.aichat.dto.theater.LlmSceneBatchOutput;
import com.spring.aichat.dto.theater.TheaterResponses.SceneBatch;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Optional;

/**
 * [Phase 5.5-Theater] Theater 배치 캐시 서비스
 *
 * Redis에 prefetch된 Scene 배치를 저장/조회/무효화한다.
 *
 * [Key 스키마]
 * - theater:batch:{roomId}:{batchId}             — 단일 배치 (직렬화된 SceneBatch)
 * - theater:batch:raw:{roomId}:{batchId}         — LLM 원본 응답 (재파싱용, 선택적)
 * - theater:chapter:rolling:{roomId}             — 현재 Chapter의 롤링 요약
 * - theater:branch:ctx:{roomId}:{token}          — 분기 컨텍스트 (1회용)
 *
 * [TTL]
 * - 배치 캐시: 6시간 (세션 길이 대응)
 * - 롤링 요약: 6시간
 * - 분기 컨텍스트: 30분 (유저가 고민하는 시간 고려)
 *
 * [무효화 정책]
 * - 유저가 분기를 선택하면 해당 배치 이후의 모든 prefetch 배치 evict
 * - 난입(Intervention) 시작 시 현재 & 이후 배치 evict
 * - Chapter 종료 시 이전 Chapter의 배치들 evict (자동 TTL 의존)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TheaterBatchCacheService {

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    private static final Duration BATCH_TTL = Duration.ofHours(6);
    private static final Duration ROLLING_TTL = Duration.ofHours(6);
    private static final Duration BRANCH_CTX_TTL = Duration.ofMinutes(30);

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  Key 빌더
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    private String batchKey(Long roomId, int batchId) {
        return "theater:batch:" + roomId + ":" + batchId;
    }

    private String rawBatchKey(Long roomId, int batchId) {
        return "theater:batch:raw:" + roomId + ":" + batchId;
    }

    private String rollingKey(Long roomId) {
        return "theater:chapter:rolling:" + roomId;
    }

    private String branchCtxKey(Long roomId, String token) {
        return "theater:branch:ctx:" + roomId + ":" + token;
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  배치 캐시
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    public void putBatch(Long roomId, int batchId, SceneBatch batch) {
        try {
            String json = objectMapper.writeValueAsString(batch);
            redisTemplate.opsForValue().set(batchKey(roomId, batchId), json, BATCH_TTL);
            log.debug("🎭 [CACHE] Batch stored | roomId={} | batchId={} | scenes={}",
                roomId, batchId, batch.scenes() == null ? 0 : batch.scenes().size());
        } catch (JsonProcessingException e) {
            log.warn("🎭 [CACHE] Failed to serialize batch | roomId={} | batchId={}: {}",
                roomId, batchId, e.getMessage());
        }
    }

    public Optional<SceneBatch> getBatch(Long roomId, int batchId) {
        String json = redisTemplate.opsForValue().get(batchKey(roomId, batchId));
        if (json == null) return Optional.empty();
        try {
            return Optional.of(objectMapper.readValue(json, SceneBatch.class));
        } catch (JsonProcessingException e) {
            log.warn("🎭 [CACHE] Failed to deserialize batch | roomId={} | batchId={}: {}",
                roomId, batchId, e.getMessage());
            return Optional.empty();
        }
    }

    /** 배치 캐시 존재 여부 (prefetch 중복 방지용) */
    public boolean existsBatch(Long roomId, int batchId) {
        return Boolean.TRUE.equals(redisTemplate.hasKey(batchKey(roomId, batchId)));
    }

    /** LLM 원본 응답 캐시 (디버깅용) */
    public void putRawBatch(Long roomId, int batchId, LlmSceneBatchOutput raw) {
        try {
            String json = objectMapper.writeValueAsString(raw);
            redisTemplate.opsForValue().set(rawBatchKey(roomId, batchId), json, BATCH_TTL);
        } catch (JsonProcessingException e) {
            log.debug("🎭 [CACHE] Raw batch serialization failed: {}", e.getMessage());
        }
    }

    /**
     * 분기 발생 또는 난입 시 호출.
     * batchId 이상의 모든 배치 캐시를 무효화.
     *
     * Redis SCAN으로 탐색하되, 규모가 작으므로 범위 기반 삭제(batchId ~ batchId+10)로 충분.
     */
    public void invalidateBatchesFrom(Long roomId, int fromBatchId) {
        int evicted = 0;
        // 최대 10개 forward 제거 (현실적 prefetch 깊이 상한)
        for (int i = 0; i < 10; i++) {
            int target = fromBatchId + i;
            Boolean removed = redisTemplate.delete(batchKey(roomId, target));
            Boolean removedRaw = redisTemplate.delete(rawBatchKey(roomId, target));
            if (Boolean.TRUE.equals(removed)) evicted++;
            if (!Boolean.TRUE.equals(removed) && i > 2) break; // 더 이상 캐시 없음
        }
        log.info("🎭 [CACHE] Invalidated batches | roomId={} | from={} | evicted={}",
            roomId, fromBatchId, evicted);
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  롤링 요약
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    public void putRollingSummary(Long roomId, String summary) {
        if (summary == null || summary.isBlank()) return;
        redisTemplate.opsForValue().set(rollingKey(roomId), summary, ROLLING_TTL);
    }

    public Optional<String> getRollingSummary(Long roomId) {
        String s = redisTemplate.opsForValue().get(rollingKey(roomId));
        return Optional.ofNullable(s);
    }

    public void clearRollingSummary(Long roomId) {
        redisTemplate.delete(rollingKey(roomId));
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  분기 컨텍스트 토큰
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    public void putBranchContext(Long roomId, String token, String context) {
        redisTemplate.opsForValue().set(branchCtxKey(roomId, token), context, BRANCH_CTX_TTL);
    }

    public Optional<String> consumeBranchContext(Long roomId, String token) {
        String key = branchCtxKey(roomId, token);
        String ctx = redisTemplate.opsForValue().get(key);
        if (ctx != null) redisTemplate.delete(key); // 1회용
        return Optional.ofNullable(ctx);
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  전체 정리 (방 삭제 시)
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    public void purgeRoom(Long roomId) {
        invalidateBatchesFrom(roomId, 0);
        clearRollingSummary(roomId);
        log.info("🎭 [CACHE] Purged all caches | roomId={}", roomId);
    }
}