package com.spring.aichat.domain.enums;

/**
 * BGM 테마 모드
 *
 * [Phase 4] 청각 엔진 — 동적 BGM 전환
 *
 * 파일 경로 규칙: /audio/bgm_{mode}.mp3
 * Ex: bgm_romantic.mp3, bgm_exciting.mp3
 */
public enum BgmMode {
    DAILY,        // 일상적인 분위기 (레거시 — V1 전용. V2는 CALM/BRIGHT 이원화)
    DAILY_CALM,   // [V2] 잔잔하고 조용한 일상 — 차분한 대화, 사색, 늦은 밤
    DAILY_BRIGHT, // [V2] 밝고 활기찬 일상 — 외출, 장난, 즐거운 분위기
    ROMANTIC,     // 설레는 분위기
    EXCITING,     // 신나는 분위기
    TOUCHING,     // 감동적인 분위기
    TENSE,        // 심각한, 긴장되는 분위기
    EROTIC        // 관능적인 분위기 (시크릿 전용)
}