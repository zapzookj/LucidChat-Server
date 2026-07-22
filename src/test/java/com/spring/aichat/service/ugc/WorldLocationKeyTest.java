package com.spring.aichat.service.ugc;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [UGC 세계관 빌더] locationKey 정규화 계약 — canonical key(UGCW_{id}__{KEY}) 안정성의 근간.
 */
class WorldLocationKeyTest {

    @Test
    @DisplayName("LLM 산출 키를 SCREAMING_SNAKE로 정규화하고 40자로 절삭한다")
    void normalizesKeys() {
        Set<String> used = new HashSet<>();
        assertThat(WorldConceptStructuringService.normalizeLocationKey("rooftop garden", 0, used))
            .isEqualTo("ROOFTOP_GARDEN");
        assertThat(WorldConceptStructuringService.normalizeLocationKey("  Neon-Alley!! ", 1, used))
            .isEqualTo("NEON_ALLEY");
        assertThat(WorldConceptStructuringService.normalizeLocationKey("A".repeat(60), 2, used))
            .hasSize(WorldConceptStructuringService.LOCATION_KEY_MAX);
    }

    @Test
    @DisplayName("빈 키(한국어 표시명 등)와 중복 키는 LOC_{n} 폴백으로 해소한다")
    void fallbackForBlankAndDuplicate() {
        Set<String> used = new HashSet<>();
        // 한국어만 있는 이름 → 정규화 결과 빈 문자열 → 폴백
        String k1 = WorldConceptStructuringService.normalizeLocationKey("옥상 정원", 0, used);
        assertThat(k1).isEqualTo("LOC_1");
        used.add(k1);

        // 기존 키와 중복 → 폴백
        used.add("ROOFTOP_GARDEN");
        String k2 = WorldConceptStructuringService.normalizeLocationKey("ROOFTOP_GARDEN", 1, used);
        assertThat(k2).isEqualTo("LOC_2");

        // 폴백끼리도 충돌하면 접미 증가
        used.add("LOC_3");
        String k3 = WorldConceptStructuringService.normalizeLocationKey(null, 2, used);
        assertThat(k3).isEqualTo("LOC_3_1");
    }
}
