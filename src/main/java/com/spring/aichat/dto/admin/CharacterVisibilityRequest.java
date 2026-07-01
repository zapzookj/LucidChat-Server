package com.spring.aichat.dto.admin;

/** 캐릭터 노출 토글(부분 갱신). null 인 필드는 변경하지 않음. */
public record CharacterVisibilityRequest(
    Boolean hidden,
    Boolean storyAvailable,
    Boolean theaterAvailable,
    String reason
) {}
