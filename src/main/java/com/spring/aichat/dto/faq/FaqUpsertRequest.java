package com.spring.aichat.dto.faq;

import jakarta.validation.constraints.NotBlank;

public record FaqUpsertRequest(
    @NotBlank String category,
    @NotBlank String question,
    @NotBlank String answer,
    Integer displayOrder,
    Boolean published
) {}
