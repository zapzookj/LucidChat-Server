package com.spring.aichat.dto.chat;

import com.spring.aichat.domain.enums.EmotionTag;

import java.util.List;

/**
 * 채팅 전송 응답 DTO
 *
 * [Phase 4]  SceneResponse에 location, time, outfit, bgmMode
 * [Phase 4.2]  PromotionEvent — 관계 승급 이벤트 정보
 */
public record SendChatResponse(
    Long roomId,
    List<SceneResponse> scenes,
    int currentAffection,
    String relationStatus,
    PromotionEvent promotionEvent   // [Phase 5] null이면 이벤트 없음
) {
    /** 기존 호환용 생성자 (이벤트 없음) */
    public SendChatResponse(Long roomId, List<SceneResponse> scenes, int currentAffection, String relationStatus) {
        this(roomId, scenes, currentAffection, relationStatus, null);
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
     *
     * status:
     *   STARTED      — 이벤트 시작 (프론트: 배너 표시)
     *   IN_PROGRESS  — 진행 중
     *   SUCCESS      — 승급 성공 (프론트: 해금 축하 연출)
     *   FAILURE      — 승급 실패 (프론트: 실패 알림)
     */
    public record PromotionEvent(
        String status,              // STARTED | IN_PROGRESS | SUCCESS | FAILURE
        String targetRelation,      // 목표 관계 enum name (ACQUAINTANCE, FRIEND, LOVER)
        String targetDisplayName,   // 한글 표시명 (지인, 친구, 연인)
        int turnsRemaining,         // 남은 턴 수
        int moodScore,              // 현재 누적 분위기 점수
        List<UnlockInfo> unlocks    // SUCCESS 시에만 — 해금된 콘텐츠 목록
    ) {}

    public record UnlockInfo(
        String type,        // LOCATION | OUTFIT
        String name,        // enum name (DOWNTOWN, DATE, ...)
        String displayName  // 한글 표시명 (번화가, 외출복, ...)
    ) {}
}