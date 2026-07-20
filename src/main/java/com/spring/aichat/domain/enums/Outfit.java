package com.spring.aichat.domain.enums;

/**
 * 캐릭터 복장 (캐릭터 이미지 프리픽스)
 *
 * [Phase 4] 시각 엔진 — 동적 복장 전환
 *
 * 이미지 경로 규칙: /characters/{outfit}_{emotion}.png
 * Ex: maid_neutral.png, pajama_shy.png, swimwear_heated.png
 */
public enum Outfit {
    MAID,       // 기본 메이드 복장
    HANBOK,     // 한복 (구미호 기본)
    PAJAMA,     // 잠옷
    DATE,       // 데이트룩
    SWIMWEAR,   // 수영복
    NEGLIGEE,    // 네글리제 (시크릿 전용)
    DAILY,      // 일상복 (서태리, 백루나 기본)
    /**
     * [UGC v1] UGC 캐릭터 단일 복장 — 에셋 규약 characters/{slug}/default_{emotion}.png.
     * 이 상수가 없으면 ChatRoom.parseOutfitOrDefault("DEFAULT")가 MAID로 폴백되어
     * UGC 방의 스탠딩 URL이 maid_*.png(404)로 조립되는 버그가 있었다 (2026-07-20 수정).
     */
    DEFAULT
}