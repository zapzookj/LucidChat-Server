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
    /**
     * [Phase 5.5 UX Polish · R3] 활성 감독 명령어 TTL.
     * 1배치 일회성이지만 안전망으로 30분 만료 (그 동안 다음 배치가 안 오면 자동 폐기).
     */
    private static final Duration DIRECTOR_COMMAND_TTL = Duration.ofMinutes(30);

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

    /** [R3] 활성 감독 명령어 키 — text와 noteId를 ":"로 구분해 저장 */
    private String directorCommandKey(Long roomId) {
        return "theater:director:command:" + roomId;
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
    //  [Phase 5.5 UX Polish · R3] 활성 감독 명령어
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /**
     * 활성 명령어 페이로드.
     * @param text   명령어 텍스트 (검증 통과한 sanitized 값)
     * @param noteId DB의 DirectorNote ID — consume 시 wasUsed 마킹용
     */
    public record ActiveDirectorCommand(String text, Long noteId) {}

    /** 직렬화 구분자 — 텍스트 안에 거의 등장하지 않는 토큰 */
    private static final String CMD_SEPARATOR = "\u0001";

    /**
     * 활성 명령어 등록 (max=1 — 기존 큐 덮어쓰기).
     * 새 명령어를 발동하면 직전에 발동했지만 아직 소비되지 않은 명령어는 폐기됨.
     */
    public void setActiveDirectorCommand(Long roomId, String text, Long noteId) {
        String payload = (noteId != null ? noteId : 0L) + CMD_SEPARATOR + (text != null ? text : "");
        redisTemplate.opsForValue().set(directorCommandKey(roomId), payload, DIRECTOR_COMMAND_TTL);
    }

    /**
     * 활성 명령어 조회 — 소비하지 않음 (peek).
     * BranchService가 옵션 생성 시너지 컨텍스트로 가볍게 참조하는 용도.
     */
    public Optional<ActiveDirectorCommand> peekActiveDirectorCommand(Long roomId) {
        String payload = redisTemplate.opsForValue().get(directorCommandKey(roomId));
        return parseCommandPayload(payload);
    }

    /**
     * 활성 명령어 소비 (consume) — 조회 + 즉시 삭제.
     * BatchGenerator가 배치 생성 직전 1회 호출 → 프롬프트에 흡수 후 큐 비움.
     */
    public Optional<ActiveDirectorCommand> consumeActiveDirectorCommand(Long roomId) {
        String key = directorCommandKey(roomId);
        String payload = redisTemplate.opsForValue().get(key);
        if (payload != null) redisTemplate.delete(key);
        return parseCommandPayload(payload);
    }

    /** 활성 명령어 강제 삭제 (배치 invalidation 등) */
    public void clearActiveDirectorCommand(Long roomId) {
        redisTemplate.delete(directorCommandKey(roomId));
    }

    private Optional<ActiveDirectorCommand> parseCommandPayload(String payload) {
        if (payload == null || payload.isBlank()) return Optional.empty();
        int idx = payload.indexOf(CMD_SEPARATOR);
        if (idx < 0) {
            // 구버전 — text만 저장된 경우
            return Optional.of(new ActiveDirectorCommand(payload, null));
        }
        Long noteId = null;
        try {
            long parsed = Long.parseLong(payload.substring(0, idx));
            if (parsed > 0) noteId = parsed;
        } catch (NumberFormatException ignored) {}
        String text = payload.substring(idx + 1);
        return Optional.of(new ActiveDirectorCommand(text, noteId));
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  전체 정리 (방 삭제 시)
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    public void purgeRoom(Long roomId) {
        invalidateBatchesFrom(roomId, 0);
        clearRollingSummary(roomId);
        clearActiveDirectorCommand(roomId);
        log.info("🎭 [CACHE] Purged all caches | roomId={}", roomId);
    }
}