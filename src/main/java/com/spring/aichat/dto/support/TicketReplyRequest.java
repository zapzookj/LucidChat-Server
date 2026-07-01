package com.spring.aichat.dto.support;

import jakarta.validation.constraints.NotBlank;

public record TicketReplyRequest(
    @NotBlank String body
) {}
