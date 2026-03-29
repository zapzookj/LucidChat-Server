package com.spring.aichat.dto.chat;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 채팅 전송 요청 DTO
 *
 * [Phase 5.5-Guard] 메시지 길이 제한 추가
 * - 최대 200자: LLM 입력 토큰 비용 폭발 방지
 * - @Size 어노테이션으로 Spring Validation이 자동 검증
 * - 프론트엔드에서도 동일한 제한을 걸지만, 백엔드가 최종 방어선
 */
public record SendChatRequest(
    @NotNull Long roomId,
    @NotBlank
    @Size(max = 200, message = "메시지는 200자 이내로 입력해주세요.")
    String message
) {}