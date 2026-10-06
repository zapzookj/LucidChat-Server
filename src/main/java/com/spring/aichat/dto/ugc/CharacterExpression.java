package com.spring.aichat.dto.ugc;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.spring.aichat.domain.enums.EmotionTag;

/** Immutable per-character expression. IDs are assigned by the server, never by the LLM. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CharacterExpression(String id, String label, String selectionCondition,
                                  EmotionTag emotion, String expression, String pose) {
    public static CharacterExpression neutral() {
        return new CharacterExpression("NEUTRAL", "기본", "평상시 또는 적합한 연출이 없는 경우",
            EmotionTag.NEUTRAL, "a relaxed neutral expression", "the original standing pose");
    }
}
