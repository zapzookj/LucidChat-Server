package com.spring.aichat.domain.enums;

/**
 * 채팅 로그 Role (시스템 메시지는 저장하지 않음)
 * * - USER: 사용자
 * * - ASSISTANT: AI 캐릭터
 * * - SYSTEM: 나레이터/시스템 이벤트 (Phase 2 추가)
 */
public enum ChatRole {
    USER,
    ASSISTANT,
    SYSTEM
}
