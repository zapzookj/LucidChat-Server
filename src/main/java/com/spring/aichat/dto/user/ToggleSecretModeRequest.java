package com.spring.aichat.dto.user;

import jakarta.validation.constraints.NotNull;

/**
 * [Phase 5 Fix] 시크릿 모드 토글 요청 DTO
 *
 * 시크릿 모드 활성화 시 반드시 characterId를 함께 전송하여
 * SecretModeService.canAccessSecretMode()로 캐릭터별 접근 권한을 검증한다.
 *
 * enabled=false 시에는 검증 없이 즉시 비활성화 (권한 해제는 자유롭게).
 */
public record ToggleSecretModeRequest(
    @NotNull(message = "enabled is required")
    Boolean enabled,

    Long characterId   // enabled=true 시 필수, enabled=false 시 선택
) {}