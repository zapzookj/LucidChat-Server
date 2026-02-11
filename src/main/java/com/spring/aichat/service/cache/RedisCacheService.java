package com.spring.aichat.service.cache;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Redis 캐시 유틸리티 서비스
 *
 * [설계 원칙]
 * - StringRedisTemplate 기반: 기존 JWT 인프라와 동일한 Redis 클라이언트 사용
 * - JSON 직렬화: ObjectMapper로 타입 안전한 캐싱
 * - 키 네이밍: {domain}:{identifier} (예: auth:room_owner:18, character:default)
 * - TTL 정책: 불변 데이터 = 영구, 변동 데이터 = 짧은 TTL + 명시적 eviction
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class RedisCacheService {

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  키 프리픽스 상수
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /** AuthGuard 방 소유권: room_owner:{roomId} → username */
    public static final String ROOM_OWNER_PREFIX = "room_owner:";

    /** 캐릭터 데이터: character:{characterId} → Character JSON */
    public static final String CHARACTER_PREFIX = "character:";

    /** 유저 프로필: user_profile:{username} → UserResponse JSON */
    public static final String USER_PROFILE_PREFIX = "user_profile:";

    /** 채팅방 정보: room_info:{roomId} → ChatRoomInfoResponse JSON */
    public static final String ROOM_INFO_PREFIX = "room_info:";

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  공통 캐시 연산
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /**
     * 캐시에 값 저장 (TTL 있음)
     */
    public <T> void put(String key, T value, long ttl, TimeUnit unit) {
        try {
            String json = objectMapper.writeValueAsString(value);
            redisTemplate.opsForValue().set(key, json, ttl, unit);
        } catch (JsonProcessingException e) {
            log.warn("Redis cache put failed (serialization): key={}", key, e);
        }
    }

    /**
     * 캐시에 값 저장 (TTL 없음 — 영구 캐싱)
     */
    public <T> void putPermanent(String key, T value) {
        try {
            String json = objectMapper.writeValueAsString(value);
            redisTemplate.opsForValue().set(key, json);
        } catch (JsonProcessingException e) {
            log.warn("Redis cache put failed (serialization): key={}", key, e);
        }
    }

    /**
     * 캐시에서 값 조회
     * @return Optional.empty() if cache miss or deserialization error
     */
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

    /**
     * 단순 String 캐시 저장 (직렬화 불필요한 경우)
     */
    public void putString(String key, String value) {
        redisTemplate.opsForValue().set(key, value);
    }

    /**
     * 단순 String 캐시 조회
     */
    public Optional<String> getString(String key) {
        return Optional.ofNullable(redisTemplate.opsForValue().get(key));
    }

    /**
     * 캐시 삭제
     */
    public void evict(String key) {
        redisTemplate.delete(key);
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  도메인별 편의 메서드
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    // ── AuthGuard: 방 소유권 ──

    public void cacheRoomOwner(Long roomId, String username) {
        putString(ROOM_OWNER_PREFIX + roomId, username);
    }

    public Optional<String> getRoomOwner(Long roomId) {
        return getString(ROOM_OWNER_PREFIX + roomId);
    }

    public void evictRoomOwner(Long roomId) {
        evict(ROOM_OWNER_PREFIX + roomId);
    }

    // ── Character: 영구 캐싱 ──

    public <T> void cacheCharacter(Long characterId, T characterData) {
        putPermanent(CHARACTER_PREFIX + characterId, characterData);
    }

    public <T> Optional<T> getCharacter(Long characterId, Class<T> type) {
        return get(CHARACTER_PREFIX + characterId, type);
    }

    // ── User Profile ──

    public <T> void cacheUserProfile(String username, T profile) {
        put(USER_PROFILE_PREFIX + username, profile, 30, TimeUnit.MINUTES);
    }

    public <T> Optional<T> getUserProfile(String username, Class<T> type) {
        return get(USER_PROFILE_PREFIX + username, type);
    }

    public void evictUserProfile(String username) {
        evict(USER_PROFILE_PREFIX + username);
    }

    // ── ChatRoom Info ──

    public <T> void cacheRoomInfo(Long roomId, T roomInfo) {
        put(ROOM_INFO_PREFIX + roomId, roomInfo, 60, TimeUnit.SECONDS);
    }

    public <T> Optional<T> getRoomInfo(Long roomId, Class<T> type) {
        return get(ROOM_INFO_PREFIX + roomId, type);
    }

    public void evictRoomInfo(Long roomId) {
        evict(ROOM_INFO_PREFIX + roomId);
    }
}