package com.spring.aichat.dto.notice;

import jakarta.validation.constraints.NotBlank;

public record NoticeUpsertRequest(
    @NotBlank String title,
    @NotBlank String body,
    Boolean pinned,
    Boolean published
) {}
