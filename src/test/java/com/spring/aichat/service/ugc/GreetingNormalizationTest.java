package com.spring.aichat.service.ugc;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [UGC 폴리싱 2026-07-20] 첫인사 정규화 계약 — 실측 케이스(하린) 회귀 방지.
 * 계약: first_greeting = 순수 대사 / 괄호 지문 = 나레이션 채널 분리 / 마크다운·따옴표 제거.
 */
class GreetingNormalizationTest {

    @Test
    @DisplayName("실측 혼합 포맷: 마크다운 지문 제거 + 괄호 나레이션 분리 + 대사 따옴표 제거")
    void splitsMeasuredMixedFormat() {
        String raw = """
            *하린가 고개를 숙여 인사하며 부드럽게 미소짓는다.*
            (바스락거리는 빗자루 소리가 고요한 신사 마당에 울려 퍼집니다. 이내 인기척을 느낀 그녀가 청소를 멈추고 고개를 돌려 당신을 바라봅니다.)

            "어서 오세요, 길을 잃으신 건가요? 괜찮으시다면 잠시 이곳에서 쉬어가셔요."
            """;

        var parts = ConceptStructuringService.normalizeGreeting(raw);

        assertThat(parts.dialogue())
            .isEqualTo("어서 오세요, 길을 잃으신 건가요? 괜찮으시다면 잠시 이곳에서 쉬어가셔요.");
        assertThat(parts.dialogue()).doesNotContain("*").doesNotContain("(").doesNotContain("\"");
        assertThat(parts.extractedNarration())
            .startsWith("바스락거리는 빗자루 소리")
            .doesNotContain("(").doesNotContain(")");
    }

    @Test
    @DisplayName("이미 순수 대사인 공식 포맷은 그대로 통과한다")
    void plainDialoguePassesThrough() {
        var parts = ConceptStructuringService.normalizeGreeting(
            "어서 오세요, 주인님. 저는 이 저택의 메이드, 아이리입니다.");
        assertThat(parts.dialogue()).isEqualTo("어서 오세요, 주인님. 저는 이 저택의 메이드, 아이리입니다.");
        assertThat(parts.extractedNarration()).isNull();
    }

    @Test
    @DisplayName("전부 지문이면 대사는 null (호출측에서 폴백 인사 사용)")
    void allNarrationYieldsNullDialogue() {
        var parts = ConceptStructuringService.normalizeGreeting("(그녀가 조용히 당신을 바라봅니다.)");
        assertThat(parts.dialogue()).isNull();
        assertThat(parts.extractedNarration()).isEqualTo("그녀가 조용히 당신을 바라봅니다.");
    }

    @Test
    @DisplayName("null/공백 입력은 (null, null)")
    void nullSafe() {
        assertThat(ConceptStructuringService.normalizeGreeting(null).dialogue()).isNull();
        assertThat(ConceptStructuringService.normalizeGreeting("  ").dialogue()).isNull();
    }
}
