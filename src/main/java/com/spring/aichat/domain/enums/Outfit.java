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
    PAJAMA,     // 잠옷
    DATE,       // 데이트룩
    SWIMWEAR,   // 수영복
    NEGLIGEE    // 네글리제 (시크릿 전용)
}
