package com.spring.aichat.dto.achievement;

import java.time.LocalDateTime;
import java.util.List;

/**
 * [Phase 4.4] 업적 관련 DTO
 */
public class AchievementResponse {

    /**
     * 개별 업적 정보
     */
    public record AchievementItem(
        String type,         // ENDING | SPECIAL
        String code,         // HAPPY_ENDING, STOCKHOLM 등
        String title,        // 영문 타이틀
        String titleKo,      // 한글 타이틀
        String description,  // 설명
        String icon,         // emoji
        LocalDateTime unlockedAt
    ) {}

    /**
     * 업적 갤러리 전체 응답
     */
    public record Gallery(
        List<AchievementItem> unlocked,   // 해금된 업적들
        List<AchievementItem> locked,     // 미해금 업적들 (힌트 표시)
        int totalCount,                   // 전체 업적 수
        int unlockedCount                 // 해금된 업적 수
    ) {}

    /**
     * 업적 획득 알림 (프론트에서 토스트용)
     */
    public record UnlockNotification(
        String code,
        String title,
        String titleKo,
        String description,
        String icon,
        boolean isNew          // 최초 획득인지 여부
    ) {}
}