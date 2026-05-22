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
 * <p>생성된 장소 배경을 (시간대 + 장소키) 해시로 관리하여 재활용.
 * S3 URL을 영구 적재, Redis에도 캐싱하여 DB 조회 최소화.
 *
 * <p>[Phase 6-Illust] 캐시 키 양식 변경 — 동일 장소 미묘 변동에 따른 캐시 미스 누수 해소.
 *
 * <p>이전: {@code SHA-256(locationName + "_" + timeOfDay)} — LLM 자유 텍스트 직해싱.
 * "심야의 무인 카페" vs "24시 무인 카페"가 다른 해시로 떨어지는 문제.
 *
 * <p>현재: {@code SHA-256(canonicalKey + "_" + timeOfDay)} — LLM이 정규화한 키 직해싱.
 *
 * <p>{@code canonicalKey} 양식: {@code "<WORLD>__<CATEGORY>_<MOD1>_<MOD2>..."} (SCREAMING_SNAKE_CASE)
 * <pre>
 *   "MODERN__CAFE_NIGHT_UNMANNED"
 *   "MEDIEVAL_FANTASY__TAVERN_DUSK_QUIET"
 *   "CYBERPUNK__ALLEY_RAIN_NEON"
 * </pre>
 * 세계관 prefix를 둠으로써 *현대 카페와 중세 카페가 같은 캐시로 묶이는 것*을 방지.
 *
 * <p>기존 데이터는 자연 도태 (옛 hash entry는 그대로, 신규 생성만 새 hash 양식).
 * 안정화 후 일괄 truncate 권장 (옛 entry는 자체 GPU 화풍이라 신 플랫폼과 화풍 불일치 가능).
 */
@Getter
@NoArgsConstructor
@Entity
@Table(name = "background_cache", indexes = {
    @Index(name = "idx_bg_cache_hash", columnList = "cache_hash", unique = true),
    @Index(name = "idx_bg_cache_character", columnList = "character_id"),
    @Index(name = "idx_bg_cache_canonical", columnList = "canonical_key")
})
public class BackgroundCache {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 캐시 해시 키 (unique, SHA-256) — canonicalKey + timeOfDay 기반. */
    @Column(name = "cache_hash", nullable = false, unique = true, length = 64)
    private String cacheHash;

    /** 원본 장소명 (LLM이 제공한 표시용 이름, UI 노출용) */
    @Column(name = "location_name", nullable = false, length = 100)
    private String locationName;

    /**
     * [Phase 6-Illust] 정규화 캐시 키 — hash 산출의 source.
     * 양식: {@code "<WORLD>__<CATEGORY>_<MOD>..."} (SCREAMING_SNAKE_CASE).
     * null 가능 (구버전 entry 호환 / LLM이 키 출력 누락 시 폴백).
     */
    @Column(name = "canonical_key", length = 100)
    private String canonicalKey;

    /** 시간대 */
    @Column(name = "time_of_day", nullable = false, length = 30)
    private String timeOfDay;

    /** S3 영구 URL */
    @Column(name = "image_url", nullable = false, length = 1000)
    private String imageUrl;

    /** 생성 시 사용된 프롬프트 (디버깅용) */
    @Column(name = "prompt_used", columnDefinition = "TEXT")
    private String promptUsed;

    /** 연관 캐릭터 ID */
    @Column(name = "character_id")
    private Long characterId;

    /** 조회 횟수 */
    @Column(name = "hit_count", nullable = false)
    private int hitCount = 0;

    /**
     * 외부 provider request ID (Fal.ai request_id 또는 ModelsLab generation id).
     * 컬럼명은 호환을 위해 fal_request_id 유지 — 의미는 "provider request id"로 재해석.
     */
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
     * [Phase 6-Illust] 신규 양식 — canonicalKey 기반.
     * canonicalKey가 null/blank이면 폴백으로 locationName 직해싱(구버전 호환).
     */
    public static String computeHash(String canonicalKey, String timeOfDay, String fallbackLocationName) {
        String source = (canonicalKey != null && !canonicalKey.isBlank())
            ? canonicalKey
            : (fallbackLocationName != null ? fallbackLocationName : "");
        return sha256(normalize(source) + "_" + normalize(timeOfDay));
    }

    /**
     * 구버전 호환 — locationName + timeOfDay 직해싱.
     * 신규 코드에서는 사용 금지. 호출 전부 신 시그니처로 이행 권장.
     */
    @Deprecated
    public static String computeHash(String locationName, String timeOfDay) {
        return computeHash(null, timeOfDay, locationName);
    }

    private static String sha256(String raw) {
        try {
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

    /**
     * [Phase 6-Illust] 신규 양식 팩토리.
     */
    public static BackgroundCache create(
        String locationName, String canonicalKey, String timeOfDay,
        String imageUrl, String promptUsed, Long characterId, String providerRequestId
    ) {
        BackgroundCache cache = new BackgroundCache();
        cache.cacheHash = computeHash(canonicalKey, timeOfDay, locationName);
        cache.locationName = locationName;
        cache.canonicalKey = canonicalKey;
        cache.timeOfDay = timeOfDay != null ? timeOfDay.toUpperCase() : "DAY";
        cache.imageUrl = imageUrl;
        cache.promptUsed = promptUsed;
        cache.characterId = characterId;
        cache.falRequestId = providerRequestId;
        return cache;
    }

    /**
     * 구버전 호환 팩토리.
     */
    @Deprecated
    public static BackgroundCache create(
        String locationName, String timeOfDay, String imageUrl,
        String promptUsed, Long characterId, String falRequestId
    ) {
        return create(locationName, null, timeOfDay, imageUrl, promptUsed, characterId, falRequestId);
    }

    public void incrementHitCount() {
        this.hitCount++;
    }
}