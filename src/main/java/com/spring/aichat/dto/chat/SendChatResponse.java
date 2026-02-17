package com.spring.aichat.dto.chat;

import com.spring.aichat.domain.enums.EmotionTag;

import java.util.List;

/**
 * 채팅 전송 응답 DTO
 *
 * [Phase 4]    SceneResponse에 location, time, outfit, bgmMode
 * [Phase 4.2]  PromotionEvent — 관계 승급 이벤트 정보
 * [Phase 4.3]  EndingTrigger — 엔딩 트리거 감지
 */
public record SendChatResponse(
    Long roomId,
    List<SceneResponse> scenes,
    int currentAffection,
    String relationStatus,
    PromotionEvent promotionEvent,   // [Phase 4.2] null이면 이벤트 없음
    EndingTrigger endingTrigger      // [Phase 4.3] null이면 엔딩 아님
) {
    /** 기존 호환용 생성자 (이벤트 없음, 엔딩 없음) */
    public SendChatResponse(Long roomId, List<SceneResponse> scenes, int currentAffection, String relationStatus) {
        this(roomId, scenes, currentAffection, relationStatus, null, null);
    }

    /** [Phase 4.2] 호환용 생성자 (승급 이벤트만) */
    public SendChatResponse(Long roomId, List<SceneResponse> scenes, int currentAffection, String relationStatus, PromotionEvent promotionEvent) {
        this(roomId, scenes, currentAffection, relationStatus, promotionEvent, null);
    }

    public record SceneResponse(
        String narration,
        String dialogue,
        EmotionTag emotion,
        String location,
        String time,
        String outfit,
        String bgmMode
    ) {}

    /**
     * [Phase 4.2] 관계 승급 이벤트 정보
     */
    public record PromotionEvent(
        String status,              // STARTED | IN_PROGRESS | SUCCESS | FAILURE
        String targetRelation,      // 목표 관계 enum name
        String targetDisplayName,   // 한글 표시명
        int turnsRemaining,
        int moodScore,
        List<UnlockInfo> unlocks    // SUCCESS 시에만
    ) {}

    public record UnlockInfo(
        String type,        // LOCATION | OUTFIT
        String name,        // enum name
        String displayName  // 한글 표시명
    ) {}

    /**
     * [Phase 4.3] 엔딩 트리거 — 호감도 ±100 도달 시 프론트에 알림
     *
     * 프론트는 이 필드를 받으면 엔딩 생성 API를 호출한다.
     */
    public record EndingTrigger(
        String endingType   // HAPPY | BAD
    ) {}
}