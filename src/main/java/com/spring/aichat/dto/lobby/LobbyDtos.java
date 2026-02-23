package com.spring.aichat.dto.lobby;

import com.spring.aichat.domain.enums.ChatMode;

import java.time.LocalDateTime;
import java.util.List;

/**
 * [Phase 4 — Lobby] 로비 관련 DTO 모음
 */
public final class LobbyDtos {

    private LobbyDtos() {}

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  캐릭터 목록 (새로운 만남)
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /** 로비 카루셀에 표시할 캐릭터 카드 정보 */
    public record CharacterCard(
        Long id,
        String name,
        String subtitle,           // "상냥한 저택의 메이드"
        String description,        // 상세 설명
        String thumbnailUrl,       // 카드 이미지
        String defaultImageUrl,    // 캐릭터 기본 이미지
        boolean isAvailable,       // 선택 가능 여부
        boolean storyAvailable,    // 스토리 모드 지원 여부
        boolean hasExistingStory,  // 이미 스토리 모드 방이 있는지
        boolean hasExistingSandbox // 이미 샌드박스 모드 방이 있는지
    ) {}

    public record CharacterListResponse(
        List<CharacterCard> characters,
        int totalCount
    ) {}

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  진행 중인 채팅방 (기억의 끈)
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /** 진행 중인 채팅방 카드 정보 */
    public record RoomCard(
        Long roomId,
        String characterName,
        String characterThumbnailUrl,
        String chatMode,           // STORY | SANDBOX
        String chatModeDisplayName,// 스토리 모드 | 자유 모드
        int affectionScore,
        String statusLevel,        // STRANGER, ACQUAINTANCE, ...
        String lastEmotion,
        LocalDateTime lastActiveAt,
        boolean endingReached,
        String endingTitle
    ) {}

    public record RoomListResponse(
        List<RoomCard> rooms,
        int totalCount
    ) {}

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  채팅방 생성 (새로운 만남 → 모드 선택 → 입장)
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    public record CreateRoomRequest(
        Long characterId,
        String chatMode   // "STORY" | "SANDBOX"
    ) {}

    public record CreateRoomResponse(
        Long roomId,
        String characterName,
        String chatMode,
        String message
    ) {}
}