package com.spring.aichat.dto.faq;

import com.spring.aichat.domain.faq.FaqEntry;

import java.time.LocalDateTime;

public record FaqResponse(
    Long id,
    String category,
    String question,
    String answer,
    int displayOrder,
    boolean published,
    LocalDateTime updatedAt
) {
    public static FaqResponse from(FaqEntry f) {
        return new FaqResponse(
            f.getId(), f.getCategory(), f.getQuestion(), f.getAnswer(),
            f.getDisplayOrder(), f.isPublished(), f.getUpdatedAt());
    }
}
