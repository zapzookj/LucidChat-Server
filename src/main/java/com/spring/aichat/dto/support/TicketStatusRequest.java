package com.spring.aichat.dto.support;

import jakarta.validation.constraints.NotBlank;

/** status = SupportTicketStatus 이름(OPEN/IN_PROGRESS/RESOLVED). */
public record TicketStatusRequest(
    @NotBlank String status
) {}
