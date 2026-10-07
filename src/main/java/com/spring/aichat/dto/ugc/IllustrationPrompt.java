package com.spring.aichat.dto.ugc;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

/** Authoritative WF-1 prompt pair; empty negative is a valid artistic choice. */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record IllustrationPrompt(String positivePrompt, String negativePrompt) {
    public IllustrationPrompt {
        if (positivePrompt == null || positivePrompt.isBlank() || negativePrompt == null)
            throw new IllegalArgumentException("Incomplete illustration prompt pair");
    }
}
