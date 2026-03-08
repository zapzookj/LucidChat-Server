package com.spring.aichat.exception;

/**
 * [Phase 5] 콘텐츠 모더레이션 차단 예외
 *
 * 유해 콘텐츠 감지 시 발생.
 * HTTP 400 Bad Request + 유저 친화적 메시지로 변환.
 * 에너지 차감 전에 발생하므로 유저에게 에너지 손실 없음.
 */
public class ContentModerationException extends BusinessException {

    private final String category;
    private final int blockedAtStep;

    public ContentModerationException(String userMessage, String category, int blockedAtStep) {
        super(ErrorCode.CONTENT_BLOCKED, userMessage);
        this.category = category;
        this.blockedAtStep = blockedAtStep;
    }

    public String getCategory() {
        return category;
    }

    public int getBlockedAtStep() {
        return blockedAtStep;
    }
}