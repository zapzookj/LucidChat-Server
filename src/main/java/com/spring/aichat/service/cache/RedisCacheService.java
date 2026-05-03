package com.spring.aichat.service.cache;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.concurrent.TimeUnit;

@Service
@Slf4j
@RequiredArgsConstructor
public class RedisCacheService {

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public static final String ROOM_OWNER_PREFIX = "room_owner:";
    public static final String CHARACTER_PREFIX = "character:";
    public static final String USER_PROFILE_PREFIX = "user_profile:";
    public static final String ROOM_INFO_PREFIX = "room_info:";

    public <T> void put(String key, T value, long ttl, TimeUnit unit) {
        try {
            String json = objectMapper.writeValueAsString(value);
            redisTemplate.opsForValue().set(key, json, ttl, unit);
        } catch (JsonProcessingException e) {
            log.warn("Redis cache put failed: key={}", key, e);
        }
    }

    public <T> void putPermanent(String key, T value) {
        try {
            String json = objectMapper.writeValueAsString(value);
            redisTemplate.opsForValue().set(key, json);
        } catch (JsonProcessingException e) {
            log.warn("Redis cache put failed: key={}", key, e);
        }
    }

    public <T> Optional<T> get(String key, Class<T> type) {
        try {
            String json = redisTemplate.opsForValue().get(key);
            if (json == null) return Optional.empty();
            return Optional.of(objectMapper.readValue(json, type));
        } catch (Exception e) {
            log.warn("Redis cache get failed: key={}", key, e);
            return Optional.empty();
        }
    }

    public void putString(String key, String value) {
        redisTemplate.opsForValue().set(key, value);
    }

    public Optional<String> getString(String key) {
        return Optional.ofNullable(redisTemplate.opsForValue().get(key));
    }

    public void evict(String key) {
        redisTemplate.delete(key);
    }

    // Phase 5: TTL-based string storage for verification/payment sessions
    public void setWithTTL(String key, String value, long ttlSeconds) {
        redisTemplate.opsForValue().set(key, value, ttlSeconds, TimeUnit.SECONDS);
    }

    // Phase 5: Get and delete atomically (one-time token/session, replay attack prevention)
    public String getAndDelete(String key) {
        String value = redisTemplate.opsForValue().get(key);
        if (value != null) {
            redisTemplate.delete(key);
        }
        return value;
    }

    public void cacheRoomOwner(Long roomId, String username) { putString(ROOM_OWNER_PREFIX + roomId, username); }
    public Optional<String> getRoomOwner(Long roomId) { return getString(ROOM_OWNER_PREFIX + roomId); }
    public void evictRoomOwner(Long roomId) { evict(ROOM_OWNER_PREFIX + roomId); }

    public <T> void cacheCharacter(Long characterId, T data) { putPermanent(CHARACTER_PREFIX + characterId, data); }
    public <T> Optional<T> getCharacter(Long characterId, Class<T> type) { return get(CHARACTER_PREFIX + characterId, type); }

    public <T> void cacheUserProfile(String username, T profile) { put(USER_PROFILE_PREFIX + username, profile, 30, TimeUnit.MINUTES); }
    public <T> Optional<T> getUserProfile(String username, Class<T> type) { return get(USER_PROFILE_PREFIX + username, type); }
    public void evictUserProfile(String username) { evict(USER_PROFILE_PREFIX + username); }

    public <T> void cacheRoomInfo(Long roomId, T info) { put(ROOM_INFO_PREFIX + roomId, info, 60, TimeUnit.SECONDS); }
    public <T> Optional<T> getRoomInfo(Long roomId, Class<T> type) { return get(ROOM_INFO_PREFIX + roomId, type); }
    public void evictRoomInfo(Long roomId) { evict(ROOM_INFO_PREFIX + roomId); }

    //   /** [Phase 5.5-Illust] 배경 캐시 조회 */
    public String getBackgroundCache(String key) {
        try {
            return redisTemplate.opsForValue().get(key);
        } catch (Exception e) {
            log.warn("Redis bg cache get failed: {}", e.getMessage());
            return null;
        }
    }

    /** [Phase 5.5-Illust] 배경 캐시 저장 (영구) */
    public void setBackgroundCache(String key, String url) {
        try {
            redisTemplate.opsForValue().set(key, url);
        } catch (Exception e) {
            log.warn("Redis bg cache set failed: {}", e.getMessage());
        }
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  [Polish · P1 #6] 배경 생성 In-Flight 락
    //
    //  같은 (locationName, timeOfDay)에 대해 다수의 배치가 거의 동시에
    //  cache MISS를 만나 N번 비동기 생성을 시작하던 race condition 차단.
    //  setIfAbsent로 atomic하게 락을 획득하고, generation 완료 시 release.
    //
    //  TTL은 ComfyUI 폴링 최대 시간(~3분)보다 안전하게 길게 (5분).
    //  generation이 정상 종료/실패하면 release()로 즉시 해제 → 다음 요청은
    //  caching 결과를 보거나(영구 캐시) 새로 시도(실패 시).
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    private static final String BG_INFLIGHT_LOCK_PREFIX = "bg:inflight:";
    private static final long BG_INFLIGHT_LOCK_TTL_SECONDS = 5L * 60L; // 5분

    /**
     * BG generation 락 획득 시도.
     * @return true면 락 획득 (caller가 generation 진행), false면 다른 generation이 진행 중
     */
    public boolean tryAcquireBgGenerationLock(String cacheHash) {
        try {
            String key = BG_INFLIGHT_LOCK_PREFIX + cacheHash;
            Boolean ok = redisTemplate.opsForValue()
                .setIfAbsent(key, "1", BG_INFLIGHT_LOCK_TTL_SECONDS, TimeUnit.SECONDS);
            return Boolean.TRUE.equals(ok);
        } catch (Exception e) {
            log.warn("Redis bg in-flight lock acquire failed: {}", e.getMessage());
            // 락 시스템 자체 실패 시엔 조심스럽게 false 반환 — 중복 생성 방지가 정확성보다 우선
            return false;
        }
    }

    /** BG generation 완료/실패 시 락 해제 (TTL이 있어도 즉시 해제하는 것이 다음 요청에 유리) */
    public void releaseBgGenerationLock(String cacheHash) {
        try {
            redisTemplate.delete(BG_INFLIGHT_LOCK_PREFIX + cacheHash);
        } catch (Exception e) {
            log.warn("Redis bg in-flight lock release failed: {}", e.getMessage());
        }
    }
}