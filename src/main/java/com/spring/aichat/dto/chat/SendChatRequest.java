package com.spring.aichat.dto.chat;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 채팅 전송 요청 DTO
 */
public record SendChatRequest(
    @NotNull Long roomId,
    @NotBlank String message
) {}
