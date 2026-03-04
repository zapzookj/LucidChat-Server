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
}