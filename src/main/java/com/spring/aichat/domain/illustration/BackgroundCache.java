package com.spring.aichat.domain.illustration;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;

/**
 * [Phase 5.5-Illust] 배경 이미지 영구 캐시
 *
 * 생성된 장소 배경을 (시간대 + 장소명) 해시로 관리하여 재활용.
 * S3 URL을 영구 적재하고, Redis에도 캐싱하여 DB 조회 최소화.
 *
 * 캐시 키: SHA-256(locationName + "_" + timeOfDay)
 * 예: "해변_SUNSET" → hash → S3 URL
 */
@Getter
@NoArgsConstructor
@Entity
@Table(name = "background_cache", indexes = {
    @Index(name = "idx_bg_cache_hash", columnList = "cache_hash", unique = true),
    @Index(name = "idx_bg_cache_character", columnList = "character_id")
})
public class BackgroundCache {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 캐시 해시 키 (unique, SHA-256) */
    @Column(name = "cache_hash", nullable = false, unique = true, length = 64)
    private String cacheHash;

    /** 원본 장소명 (LLM이 제공한 이름) */
    @Column(name = "location_name", nullable = false, length = 100)
    private String locationName;

    /** 시간대 */
    @Column(name = "time_of_day", nullable = false, length = 30)
    private String timeOfDay;

    /** S3 영구 URL */
    @Column(name = "image_url", nullable = false, length = 1000)
    private String imageUrl;

    /** 생성 시 사용된 프롬프트 (디버깅용) */
    @Column(name = "prompt_used", columnDefinition = "TEXT")
    private String promptUsed;

    /** 연관 캐릭터 ID (특정 캐릭터의 세계관 내 장소) */
    @Column(name = "character_id")
    private Long characterId;

    /** 조회 횟수 (인기도 추적) */
    @Column(name = "hit_count", nullable = false)
    private int hitCount = 0;

    /** Fal.ai 요청 ID */
    @Column(name = "fal_request_id", length = 100)
    private String falRequestId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void prePersist() {
        this.createdAt = LocalDateTime.now();
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  해시 생성 유틸
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /**
     * 캐시 키 해시 생성
     * @param locationName  장소명 (예: "해변", "놀이공원")
     * @param timeOfDay     시간대 (예: "SUNSET", "NIGHT")
     * @return SHA-256 hex string
     */
    public static String computeHash(String locationName, String timeOfDay) {
        try {
            String raw = normalize(locationName) + "_" + normalize(timeOfDay);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (Exception e) {
            throw new RuntimeException("Hash computation failed", e);
        }
    }

    private static String normalize(String s) {
        return s == null ? "" : s.trim().toUpperCase().replaceAll("\\s+", "_");
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  팩토리 메서드
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    public static BackgroundCache create(
        String locationName, String timeOfDay, String imageUrl,
        String promptUsed, Long characterId, String falRequestId
    ) {
        BackgroundCache cache = new BackgroundCache();
        cache.cacheHash = computeHash(locationName, timeOfDay);
        cache.locationName = locationName;
        cache.timeOfDay = timeOfDay != null ? timeOfDay.toUpperCase() : "DAY";
        cache.imageUrl = imageUrl;
        cache.promptUsed = promptUsed;
        cache.characterId = characterId;
        cache.falRequestId = falRequestId;
        return cache;
    }

    public void incrementHitCount() {
        this.hitCount++;
    }
}