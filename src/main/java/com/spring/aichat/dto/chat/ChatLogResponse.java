package com.spring.aichat.dto.chat;

import com.spring.aichat.domain.enums.ChatRole;
import com.spring.aichat.domain.enums.EmotionTag;

import java.time.LocalDateTime;

/**
 * 채팅 로그 조회 응답 DTO
 *
 * [Phase 5] MongoDB 마이그레이션:
 * - logId: Long → String (MongoDB ObjectId)
 *
 * [Phase 5.1] rating 필드 추가:
 * - "LIKE" / "DISLIKE" / null
 * - 프론트엔드에서 좋아요/싫어요 상태를 표시하기 위해 사용
 *
 * [Phase 5.2] dislikeReason 필드 추가:
 * - OOC | HALLUCINATION | BORING | REPETITIVE | CONTEXT_MISMATCH | OTHER | null
 *
 * [Phase 5.5-IT] 속마음 시스템 필드 추가:
 * - hasInnerThought: 속마음 존재 여부 (UI 트리거용)
 * - innerThought: 실제 속마음 텍스트 (해금 전 null, 해금 후 텍스트)
 * - thoughtUnlocked: 해금 완료 여부
 *
 * [Phase 5.5-Fix] scenesJson 필드 추가:
 * - ASSISTANT 메시지의 구조화된 씬 데이터 (재로딩 시 씬별 분리 복원용)
 */
public record ChatLogResponse(
    String logId,
    ChatRole role,
    String rawContent,
    String cleanContent,
    EmotionTag emotionTag,
    LocalDateTime createdAt,
    String rating,              // [Phase 5.1] "LIKE" | "DISLIKE" | null
    String dislikeReason,       // [Phase 5.2] 싫어요 사유 카테고리 | null
    boolean hasInnerThought,    // [Phase 5.5-IT] 속마음 존재 여부
    String innerThought,        // [Phase 5.5-IT] 해금된 경우만 텍스트, 아니면 null
    boolean thoughtUnlocked,    // [Phase 5.5-IT] 해금 완료 여부
    String scenesJson           // [Phase 5.5-Fix] 구조화된 씬 배열 JSON (ASSISTANT만, 나머지 null)
) {
    /** 하위 호환: scenesJson 없는 생성자 */
    public ChatLogResponse(String logId, ChatRole role, String rawContent, String cleanContent,
                           EmotionTag emotionTag, LocalDateTime createdAt,
                           String rating, String dislikeReason,
                           boolean hasInnerThought, String innerThought, boolean thoughtUnlocked) {
        this(logId, role, rawContent, cleanContent, emotionTag, createdAt,
            rating, dislikeReason, hasInnerThought, innerThought, thoughtUnlocked, null);
    }

    /** 하위 호환: 속마음 필드 없는 생성자 */
    public ChatLogResponse(String logId, ChatRole role, String rawContent, String cleanContent,
                           EmotionTag emotionTag, LocalDateTime createdAt,
                           String rating, String dislikeReason) {
        this(logId, role, rawContent, cleanContent, emotionTag, createdAt,
            rating, dislikeReason, false, null, false, null);
    }
}