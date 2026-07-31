package com.spring.aichat.domain.enums;

import com.spring.aichat.dto.chat.AiJsonOutput;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * [2026-07-31 난이도] 이중 게이트 결정론 층 계약 — 확률 반올림 배율·음수 통과·프롬프트 지시.
 */
class CharacterDifficultyTest {

    @Test
    @DisplayName("확률 반올림 — roll이 소수부 미만이면 올림, 이상이면 내림 (기대값 보존)")
    void probabilisticRounding() {
        // HARD(0.6): delta=1 → scaled 0.6 → roll<0.6이면 1, 아니면 0
        assertEquals(1, CharacterDifficulty.HARD.scaleGain(1, 0.59));
        assertEquals(0, CharacterDifficulty.HARD.scaleGain(1, 0.60));
        // HARD: delta=3 → 1.8 → 1 또는 2
        assertEquals(2, CharacterDifficulty.HARD.scaleGain(3, 0.79));
        assertEquals(1, CharacterDifficulty.HARD.scaleGain(3, 0.80));
        // EASY(1.5): delta=2 → 3.0 → 정수라 roll 무관 3
        assertEquals(3, CharacterDifficulty.EASY.scaleGain(2, 0.0));
        assertEquals(3, CharacterDifficulty.EASY.scaleGain(2, 0.999));
        // EXTREME(0.35): delta=2 → 0.7
        assertEquals(1, CharacterDifficulty.EXTREME.scaleGain(2, 0.69));
        assertEquals(0, CharacterDifficulty.EXTREME.scaleGain(2, 0.70));
    }

    @Test
    @DisplayName("음수·0·null 델타는 무배율 통과 — 실수·무례의 하락은 난이도와 무관")
    void negativeAndNullPassThrough() {
        assertEquals(-2, CharacterDifficulty.HARD.scaleGain(-2));
        assertEquals(0, CharacterDifficulty.EXTREME.scaleGain(0));
        assertNull(CharacterDifficulty.HARD.scaleGain((Integer) null));
        assertEquals(5, CharacterDifficulty.NORMAL.scaleGain(5));
    }

    @Test
    @DisplayName("StatChanges.scaledGains — NORMAL·null은 자기 자신, 배율은 양수만 변형·null 보존")
    void statChangesScaling() {
        AiJsonOutput.StatChanges sc = new AiJsonOutput.StatChanges(2, 3, null, -1, 0, 1, null, null);
        assertSame(sc, sc.scaledGains(CharacterDifficulty.NORMAL));
        assertSame(sc, sc.scaledGains(null));

        AiJsonOutput.StatChanges scaled = sc.scaledGains(CharacterDifficulty.EASY);
        assertEquals(3, scaled.intimacy());       // 2×1.5=3.0 결정적
        assertNull(scaled.dependency());          // null 보존
        assertEquals(-1, scaled.playfulness());   // 음수 통과
        assertEquals(0, scaled.trust());          // 0 통과
    }

    @Test
    @DisplayName("프롬프트 지시 — NORMAL만 null(무주입), 파서는 대소문자 관용·무효값 null")
    void promptDirectiveAndParsing() {
        assertNull(CharacterDifficulty.NORMAL.promptDirective());
        assertNotNull(CharacterDifficulty.EASY.promptDirective());
        assertNotNull(CharacterDifficulty.HARD.promptDirective());
        assertNotNull(CharacterDifficulty.EXTREME.promptDirective());

        assertEquals(CharacterDifficulty.HARD, CharacterDifficulty.fromStringOrNull("hard"));
        assertEquals(CharacterDifficulty.EXTREME, CharacterDifficulty.fromStringOrNull(" EXTREME "));
        assertNull(CharacterDifficulty.fromStringOrNull("IMPOSSIBLE"));
        assertNull(CharacterDifficulty.fromStringOrNull(null));
    }
}
