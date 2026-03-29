package com.spring.aichat.dto.user;

import jakarta.validation.constraints.Size;

/**
 * 유저 프로필 업데이트 요청 DTO
 *
 * [Phase 5 Fix] isSecretMode 필드 제거
 * [Phase 5.5-Guard] 길이 제한 추가
 *   - nickname: 최대 20자
 *   - profileDescription: 최대 500자
 *   - LLM 시스템 프롬프트에 주입되는 값이므로 토큰 비용 + 프롬프트 인젝션 방어
 */
public record UpdateUserRequest(
    @Size(max = 20, message = "닉네임은 20자 이내로 입력해주세요.")
    String nickname,

    @Size(max = 500, message = "프로필 설명은 500자 이내로 입력해주세요.")
    String profileDescription
) {}