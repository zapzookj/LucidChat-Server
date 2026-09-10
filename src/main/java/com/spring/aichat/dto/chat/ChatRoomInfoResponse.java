package com.spring.aichat.dto.chat;

/**
 * 채팅방 정보 응답 DTO
 *
 * [Phase 5.5-EV] 이벤트 시스템 강화 필드 추가:
 *   - topicConcluded: 주제 종료 플래그
 *   - eventActive: 디렉터 모드 이벤트 진행 중 여부
 *   - eventStatus: "ONGOING" | "RESOLVED" | null
 */
public record ChatRoomInfoResponse(
    Long id,
    String characterName,
    String characterSlug,
    Long characterId,
    String defaultImageUrl,
    String backgroundImageUrl,
    int affectionScore,
    String statusLevel,
    String chatMode,
    String currentBgmMode,
    String currentLocation,
    String currentOutfit,
    String currentTimeOfDay,
    String defaultOutfit,
    String defaultLocation,
    boolean endingReached,
    String endingType,
    String endingTitle,
    java.util.List<String> availableOutfits,
    java.util.List<String> availableLocations,
    // ── [Phase 5.5] 입체적 상태창 ──
    SendChatResponse.StatsSnapshot stats,
    String dynamicRelationTag,
    String characterThought,
    // ── [Phase 5.5-EV] 이벤트 시스템 강화 ──
    boolean topicConcluded,
    boolean eventActive,
    String eventStatus,
    // ── [Phase 5.5-Fix] 동적 배경 영속화 ──
    String currentDynamicLocationName,
    String currentDynamicBgUrl,
    // ── [Bug #3 Fix] 도메인 분리 ──
    boolean secretModeActive,       // 이 방에서의 시크릿 모드 활성 여부
    String userPersona,             // 이 방에 스냅샷된 유저 페르소나 소개 (null이면 미적용)
    // ── [2026-09-11] 이 방에서 캐릭터가 유저를 부르는 이름 ──
    //   방 스냅샷의 프로필 이름 우선, 없으면 계정 닉네임(ChatRoom.getEffectiveNickname과 동일 기준).
    //   설정창이 '계정 닉네임'과 이 값을 나란히 보여줘야 유저가 어느 이름이 대화에 쓰이는지 안다.
    String userNickname,
    // ── [세계관 빌더] UGC 월드 연동 ──
    Long ugcWorldId                 // 캐릭터 소속 UGC 월드 (null=미연결). 프론트는 이 값으로 동적 배경 클리어 가드를 스킵
) {}