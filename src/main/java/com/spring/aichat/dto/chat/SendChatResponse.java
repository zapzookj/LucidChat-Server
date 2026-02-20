package com.spring.aichat.dto.chat;

import com.spring.aichat.domain.enums.EmotionTag;

import java.util.List;

/**
 * 채팅 전송 응답 DTO
 *
 * [Phase 4]    SceneResponse에 location, time, outfit, bgmMode
 * [Phase 4.2]  PromotionEvent — 관계 승급 이벤트 정보
 * [Phase 4.3]  EndingTrigger — 엔딩 트리거 감지
 * [Phase 4.4]  EasterEggEvent — 이스터에그 트리거 + 업적 정보
 */
public record SendChatResponse(
    Long roomId,
    List<SceneResponse> scenes,
    int currentAffection,
    String relationStatus,
    PromotionEvent promotionEvent,
    EndingTrigger endingTrigger,
    EasterEggEvent easterEgg           // [Phase 4.4] null이면 이스터에그 없음
) {
    /** 기존 호환용 생성자 (이벤트 없음, 엔딩 없음, 이스터에그 없음) */
    public SendChatResponse(Long roomId, List<SceneResponse> scenes, int currentAffection, String relationStatus) {
        this(roomId, scenes, currentAffection, relationStatus, null, null, null);
    }

    /** [Phase 4.2] 호환용 생성자 (승급 이벤트만) */
    public SendChatResponse(Long roomId, List<SceneResponse> scenes, int currentAffection, String relationStatus, PromotionEvent promotionEvent) {
        this(roomId, scenes, currentAffection, relationStatus, promotionEvent, null, null);
    }

    /** [Phase 4.3] 호환용 생성자 (승급 + 엔딩) */
    public SendChatResponse(Long roomId, List<SceneResponse> scenes, int currentAffection, String relationStatus, PromotionEvent promotionEvent, EndingTrigger endingTrigger) {
        this(roomId, scenes, currentAffection, relationStatus, promotionEvent, endingTrigger, null);
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
        String status,
        String targetRelation,
        String targetDisplayName,
        int turnsRemaining,
        int moodScore,
        List<UnlockInfo> unlocks
    ) {}

    public record UnlockInfo(
        String type,
        String name,
        String displayName
    ) {}

    /**
     * [Phase 4.3] 엔딩 트리거
     */
    public record EndingTrigger(
        String endingType
    ) {}

    /**
     * [Phase 4.4] 이스터에그 이벤트
     *
     * trigger: STOCKHOLM | DRUNK | FOURTH_WALL | MACHINE_REBELLION
     * achievement: 업적 해금 정보 (isNew=true일 때 프론트에서 토스트 표시)
     * revertAfter: true면 연출 후 이 대화를 기록에서 제거 (4TH_WALL 2단계)
     */
    public record EasterEggEvent(
        String trigger,
        AchievementInfo achievement,
        boolean revertAfter
    ) {}

    public record AchievementInfo(
        String code,
        String title,
        String titleKo,
        String description,
        String icon,
        boolean isNew
    ) {}
}