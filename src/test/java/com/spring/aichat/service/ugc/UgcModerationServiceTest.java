package com.spring.aichat.service.ugc;

import com.spring.aichat.dto.ugc.StructuredConcept;
import com.spring.aichat.exception.ContentModerationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * [UGC v1] 좁은 생성 게이트 계약 테스트 — 명백한 시그널만 차단, 정상 컨셉은 통과.
 */
class UgcModerationServiceTest {

    private final UgcModerationService service = new UgcModerationService();

    @Test
    @DisplayName("정상 성인 컨셉은 통과한다 — childhood friend 같은 무해 표현 포함")
    void allowsNormalConcepts() {
        assertThatCode(() -> service.assertRawConceptAllowed(
            "은발의 마법학원 교수. 차갑지만 어딘가 외로운 분위기. childhood friend와 재회한 설정."))
            .doesNotThrowAnyException();
        assertThatCode(() -> service.assertRawConceptAllowed(
            "유치원 교사 출신의 다정한 26세 캐릭터")) // '유치원생'이 아닌 '유치원'은 통과
            .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("영문 하드 키워드는 단어 경계로 차단한다")
    void blocksEnglishHardKeywords() {
        for (String bad : List.of("cute loli character", "a shota boy", "child character please")) {
            assertThatThrownBy(() -> service.assertRawConceptAllowed(bad))
                .isInstanceOf(ContentModerationException.class);
        }
    }

    @Test
    @DisplayName("한국어 하드 키워드를 차단한다")
    void blocksKoreanHardKeywords() {
        for (String bad : List.of("초등학생 컨셉", "귀여운 중학생", "미성년 캐릭터")) {
            assertThatThrownBy(() -> service.assertRawConceptAllowed(bad))
                .isInstanceOf(ContentModerationException.class);
        }
    }

    @Test
    @DisplayName("Stage 0 산출: minor_signal=true 또는 age<19면 차단한다")
    void blocksStructuredSignals() {
        StructuredConcept minorSignal = concept(23, true);
        assertThatThrownBy(() -> service.assertStructuredConceptAllowed(minorSignal))
            .isInstanceOf(ContentModerationException.class);

        StructuredConcept underage = concept(17, false);
        assertThatThrownBy(() -> service.assertStructuredConceptAllowed(underage))
            .isInstanceOf(ContentModerationException.class);

        assertThatCode(() -> service.assertStructuredConceptAllowed(concept(23, false)))
            .doesNotThrowAnyException();
        assertThatCode(() -> service.assertStructuredConceptAllowed(concept(null, false)))
            .doesNotThrowAnyException(); // 나이 미기재는 차단하지 않는다 (좁은 게이트)
    }

    private StructuredConcept concept(Integer age, boolean minorSignal) {
        return new StructuredConcept(
            List.of("1girl", "silver hair"), List.of("kuudere"), List.of("library"), "light gray",
            new StructuredConcept.CharacterProfile("설아", "차가운 교수", age, "교수",
                "차분함", "존댓말", "은발", "터틀넥", "과거사", "가치관", "약점", "말버릇", "첫인사", "장면 묘사"),
            new StructuredConcept.Moderation(minorSignal, ""));
    }
}
