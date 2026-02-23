package com.spring.aichat.dto.lobby;

/**
 * [Phase 4.5] 로비 캐릭터 카드 응답 DTO
 *
 * 캐릭터 카루셀 (새로운 만남) 화면에서 사용
 */
public record CharacterResponse(
    Long id,
    String name,
    String tagline,          // 한 줄 요약 (예: "상냥한 저택의 메이드")
    String description,      // 상세 설명
    String thumbnailUrl,     // 카루셀 카드용 이미지
    String defaultImageUrl,  // 기본 캐릭터 이미지
    boolean storyAvailable   // 스토리 모드 지원 여부
) {}