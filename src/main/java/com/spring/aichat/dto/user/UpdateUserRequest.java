package com.spring.aichat.dto.user;

/**
 * 유저 프로필 업데이트 요청 DTO
 *
 * [Phase 5 Fix] isSecretMode 필드 제거
 *
 * 기존: isSecretMode를 이 DTO로 직접 토글 가능 → 결제 우회 취약점
 * 수정: 시크릿 모드 토글은 전용 엔드포인트(PATCH /users/secret-mode)로 분리
 *       → SecretModeService.canAccessSecretMode() 검증 필수
 */
public record UpdateUserRequest(
    String nickname,
    String profileDescription
) {}