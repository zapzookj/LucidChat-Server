package com.spring.aichat.dto.lobby;

/**
 * [Phase 4.5] 로비 캐릭터 카드 응답 DTO
 *
 * 캐릭터 카루셀 (새로운 만남) 화면에서 사용
 * [Phase 7-V2 Pivot] worldId, theaterAvailable 추가 — 통합 로비의 세계관 필터 + 극장 분기용
 */
public record CharacterResponse(
    Long id,
    String name,
    String slug,              // [Phase 5] 에셋 경로 key (예: "airi", "yeonhwa")
    String tagline,           // 한 줄 요약 (예: "상냥한 저택의 메이드")
    String description,       // 상세 설명
    String thumbnailUrl,      // 카루셀 카드용 이미지
    String defaultImageUrl,   // 기본 캐릭터 이미지
    boolean storyAvailable,   // 스토리 모드 지원 여부
    String worldId,           // [Phase 7-V2 Pivot] 소속 세계관 (nullable) — 통합 로비 필터용
    boolean theaterAvailable  // [Phase 7-V2 Pivot] 극장 모드 지원 여부
) {}