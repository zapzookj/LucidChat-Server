package com.spring.aichat.dto.lobby;

import com.spring.aichat.dto.theater.TheaterResponses.HeroineSummary;

import java.util.List;

/**
 * [블록 A] 공개 로비(게스트 포함) 응답 DTO 모음.
 *
 * <p><b>스코프 계약</b>: 이 파일의 레코드들은 비로그인 게스트에게 그대로 직렬화된다.
 * 시크릿 메타(secretAllowed·secretEligible 등)·프롬프트성 내부 필드(personality/tone/lore/
 * baseSystemPrompt 계열)·유저 종속 필드(hasExistingRoom 등)를 <b>필드 자체로 갖지 않는 것</b>이
 * 계약이다(docs/14 부록 §3 — 게스트=비성인 취급, 필드 제외 원칙). 새 필드 추가 시 게스트 노출
 * 관점에서 재검수할 것.
 */
public final class LobbyPublicDtos {

    private LobbyPublicDtos() {}

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  홈 피드 — 공식 + PUBLIC UGC 통합 (회원·게스트 공용)
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /** 홈 탭 캐릭터 피드 아이템 — 배지(난이도·UGC·성별) 포함. */
    public record FeedItem(
        Long characterId,
        String name,
        String slug,
        String tagline,
        String thumbnailUrl,
        String defaultImageUrl,
        String difficulty,        // EASY/NORMAL/HARD/EXTREME — 난이도 배지
        String gender,            // FEMALE/MALE — 남캐 배지
        boolean ugc,              // UGC 배지
        String creatorNickname,   // UGC만 (공식은 null)
        String worldId            // 소속 공식 세계관 (nullable)
    ) {}

    public record FeedResponse(List<FeedItem> items) {}

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  게스트 월드 카드 — 회원용 카드에서 시크릿·유저 종속 필드를 제거한 변형
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /** 스토리(V2) 월드 카드 — 게스트 스코프. (회원용 WorldCardResponse의 secretAllowed·hasExistingRoom·existingRoomId 제외) */
    public record GuestWorldCard(
        String worldId,
        String displayName,
        String tagline,
        String description,
        String heroImageUrl,
        String thumbnailUrl,
        String moodKeywords,
        int heroineCount
    ) {}

    /** 극장 월드 카드 — 게스트 스코프. (회원용 TheaterResponses.WorldCard의 secretAllowed 제외) */
    public record GuestTheaterWorldCard(
        String id,
        String displayName,
        String tagline,
        String description,
        String heroImageUrl,
        String thumbnailUrl,
        List<String> moodKeywords,
        int heroineCount,
        List<HeroineSummary> heroines
    ) {}
}
