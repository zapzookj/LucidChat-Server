package com.spring.aichat.dto.support;

/**
 * 채팅 내 "이 응답 신고". 해당 로그 컨텍스트가 티켓(BUG)에 자동 첨부된다.
 * (RLHF 싫어요 버튼과는 별개 채널 — 싫어요는 품질 데이터, 신고는 CS 티켓)
 */
public record ReportRequest(
    Long roomId,
    String logId,
    String role,
    String speaker,
    String message,
    String reason
) {}
