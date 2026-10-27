package com.spring.aichat.dto.story;

import com.spring.aichat.domain.enums.DayPart;
import com.spring.aichat.domain.enums.EndingType;
import com.spring.aichat.domain.enums.RelationStatus;

import java.time.LocalDateTime;
import java.util.List;

/**
 * [V2 Story] 응답 DTO 컬렉션.
 */
public final class StoryV2Responses {

    private StoryV2Responses() {}

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  로비 — World 카드 + Continue 카드
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    public record WorldCardResponse(
        String worldId,
        String displayName,
        String tagline,
        String description,
        String heroImageUrl,
        String thumbnailUrl,
        String moodKeywords,
        boolean secretAllowed,
        int heroineCount,            // 이 World에 등록된 히로인 수
        boolean hasExistingRoom,     // 유저가 이미 이 World에 V2 방 있는지
        Long existingRoomId          // 있으면 그 roomId
    ) {}

    public record WorldHeroineCardResponse(
        Long characterId,
        String name,
        String profileImageUrl,
        String role,
        int age,
        String tagline,              // 짧은 한 줄 소개 — 저작 전시 필드(Character.tagline)만. 프롬프트 필드 사용 금지
        /** [2026-07-31 난이도] 공략 난이도 — CreateFlow 히로인 선택 카드 표기. */
        String difficulty,
        /** [docs/55 Carry-Over] 이 유저가 이 캐릭터와 가진 자유 모드 방 요약. 없으면 null — 카드의 '관계 이어가기' 토글 근거. */
        SandboxRelationResponse sandboxRelation
    ) {}

    /** [docs/55 Carry-Over] 자유 모드 관계 요약 — CreateFlow 히로인 카드 뱃지/토글용. */
    public record SandboxRelationResponse(
        Long roomId,
        RelationStatus statusLevel,
        String dynamicRelationTag,
        int affectionScore,
        long logCount,               // hidden 제외 가시 로그 수 — 0이면 아직 대화한 적 없는 방
        LocalDateTime lastActiveAt,
        boolean secretModeActive
    ) {}

    public record PersonaPresetResponse(
        String presetKey,
        String name,
        String description,
        String defaultNickname,
        String suggestedStartLocationKey
    ) {}

    public record WorldLocationResponse(
        String locationKey,
        String displayName,
        String description,
        boolean selectableAsStart,
        int displayOrder
    ) {}

    /**
     * StoryCreateFlow 진입 시 — 선택된 World의 모든 정보를 한 번에 보냄.
     * 별도 endpoint 4개로 분리하지 않고 단일 GET /story/v2/worlds/:id/create-context
     */
    public record CreateContextResponse(
        WorldCardResponse world,
        List<WorldHeroineCardResponse> availableHeroines,
        List<PersonaPresetResponse> personaPresets,
        List<WorldLocationResponse> startLocations,
        boolean hasFreePersonaUnlock  // 자유 페르소나 BM 보유 여부
    ) {}

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  방 진입 — Detail
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    public record StoryRoomV2DetailResponse(
        Long roomId,
        String worldId,
        String worldDisplayName,
        String userPersona,
        String userNickname,    // [Phase 7-V2 Pivot] storyUserNickname (null이면 User.nickname 폴백 처리됨)
        String currentUserLocationKey,
        String currentUserLocationDisplayName,
        Integer currentDay,
        DayPart currentDayPart,
        String currentBgmMode,
        String currentDynamicLocationName,
        String currentDynamicBgUrl,
        boolean topicConcluded,
        boolean secretModeActive,
        boolean endingReached,
        EndingType endingType,
        String endingTitle,
        boolean endingEligible,
        List<HeroineStateResponse> heroines,
        List<CharacterPresenceResponse> presences,
        int unreadNotificationCount
    ) {}

    public record HeroineStateResponse(
        Long characterId,
        String name,
        String slug,                    // [Phase 7-V2 Pivot Fix] 스프라이트 에셋 키 (CharacterDisplay용)
        String defaultOutfit,           // [Bug-Sprite] 기본 복장 — 캐릭터별 상이(airi=MAID, yeonhwa=HANBOK). CharacterDisplay outfit
        String profileImageUrl,
        // [2026-08-06 UGC 스프라이트 CDN 픽스] 스탠딩 원본 절대 URL — UGC는 공식 CDN이 아닌 백엔드
        // 에셋 CDN에 있어, 프론트가 이 URL에서 assetDir을 유도해야 403이 안 난다(V1 Fix-UGC-CDN 동일 계약).
        String defaultImageUrl,
        int statIntimacy, int statAffection, int statDependency, int statPlayfulness, int statTrust,
        Integer statLust, Integer statCorruption, Integer statObsession,  // 시크릿 모드일 때만 노출
        RelationStatus statusLevel,
        String dynamicRelationTag,
        String characterThought,        // 해금된 경우만
        boolean thoughtUnlocked,
        LocalDateTime lastSpokenAt
    ) {}

    public record CharacterPresenceResponse(
        Long characterId,
        String currentLocationKey,
        String currentLocationDisplayName,
        LocalDateTime lastMovedAt
    ) {}

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  방 생성 응답
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    public record CreateStoryV2Response(
        Long roomId,
        String worldId,
        boolean isNew,        // true면 신규 생성, false면 기존 방 입장
        boolean wasOverwritten // overwriteExisting=true로 reset됐는지
    ) {}

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  오프스크린 알림
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    public record NotificationResponse(
        Long notificationId,
        Long fromCharacterId,
        String fromCharacterName,
        String content,
        int worldDay,
        String worldDayPart,
        LocalDateTime sentAt,
        boolean read,
        boolean responded
    ) {}
}