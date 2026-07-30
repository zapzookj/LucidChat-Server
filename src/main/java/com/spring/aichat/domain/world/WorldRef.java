package com.spring.aichat.domain.world;

import com.spring.aichat.domain.enums.WorldId;

/**
 * [2026-07-31 에픽 A] 월드 참조 값 타입 — enum PK 브리지의 API 경계.
 *
 * <p>공식 World(WorldId enum PK)와 UgcWorld(Long PK)의 이원화를 API 계약 문자열
 * 하나로 봉합한다: 공식은 enum name({@code "MEDIEVAL_FANTASY"}), UGC는
 * {@code "UGCW_{id}"} 접두 컨벤션(배경 canonical key의 기존 네임스페이스와 동일).
 * 전면 Long PK 전환(B안) 시 이 타입이 code 문자열 계약으로 그대로 승계된다.
 */
public record WorldRef(WorldId officialId, Long ugcWorldId) {

    public static final String UGC_PREFIX = "UGCW_";

    public WorldRef {
        if ((officialId == null) == (ugcWorldId == null)) {
            throw new IllegalArgumentException("WorldRef는 공식 XOR UGC — 정확히 한쪽만 지정");
        }
    }

    public static WorldRef ofOfficial(WorldId id) {
        return new WorldRef(id, null);
    }

    public static WorldRef ofUgc(Long ugcWorldId) {
        return new WorldRef(null, ugcWorldId);
    }

    /**
     * API 문자열 파싱 — {@code "UGCW_123"} 또는 WorldId enum name.
     *
     * @throws IllegalArgumentException 어느 쪽으로도 해석 불가
     */
    public static WorldRef parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("worldId is blank");
        }
        String s = raw.trim();
        if (s.toUpperCase(java.util.Locale.ROOT).startsWith(UGC_PREFIX)) {
            try {
                return ofUgc(Long.parseLong(s.substring(UGC_PREFIX.length())));
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Invalid UGC worldId: " + raw);
            }
        }
        WorldId id = WorldId.fromStringOrNull(s);
        if (id == null) {
            throw new IllegalArgumentException("Invalid worldId: " + raw);
        }
        return ofOfficial(id);
    }

    public boolean isUgc() {
        return ugcWorldId != null;
    }

    /** API/캐시 키 문자열 — 공식 enum name 또는 {@code UGCW_{id}}. */
    public String key() {
        return isUgc() ? UGC_PREFIX + ugcWorldId : officialId.name();
    }
}
