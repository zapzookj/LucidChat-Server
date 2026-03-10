package com.spring.aichat.dto.chat;

import jakarta.validation.constraints.Pattern;

/**
 * [Phase 5.1] 채팅 로그 평가 요청 DTO
 * [Phase 5.2] dislikeReason 추가
 *
 * rating: "LIKE" | "DISLIKE"
 * - 같은 값을 재전송하면 토글(해제)
 * - null 전송은 허용하지 않음 (해제는 토글로 처리)
 *
 * dislikeReason: "DISLIKE" 시 사유 카테고리 (선택)
 * - OOC: 캐릭터 성격/말투 불일치 (Out of Character)
 * - HALLUCINATION: 기억/설정 오류
 * - BORING: 지루한 응답
 * - REPETITIVE: 반복적 표현
 * - CONTEXT_MISMATCH: 문맥 불일치
 * - OTHER: 기타
 */
public record RateChatLogRequest(
    @Pattern(regexp = "LIKE|DISLIKE", message = "rating must be LIKE or DISLIKE")
    String rating,

    @Pattern(regexp = "OOC|HALLUCINATION|BORING|REPETITIVE|CONTEXT_MISMATCH|OTHER",
        message = "invalid dislike reason")
    String dislikeReason    // nullable — "LIKE" 시 무시됨
) {}