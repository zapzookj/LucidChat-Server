package com.spring.aichat.dto.support;

import jakarta.validation.constraints.NotBlank;

/** 유저 문의/피드백/버그 제출. type = SupportTicketType 이름(INQUIRY/FEEDBACK/BUG). */
public record CreateTicketRequest(
    @NotBlank String type,
    String category,
    @NotBlank String subject,
    @NotBlank String body
) {}
