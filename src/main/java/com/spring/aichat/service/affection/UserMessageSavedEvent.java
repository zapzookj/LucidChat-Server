package com.spring.aichat.service.affection;

/**
 * 유저 메시지 저장 이후 비동기 처리 트리거용 이벤트
 */
public record UserMessageSavedEvent(Long roomId, String userMessage) {}
