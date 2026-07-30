package com.spring.aichat.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * [2026-07-31 에픽 A] UGC 월드 모드 개방 게이트 — docs/07 3단 에픽의 '어드민 토글'.
 *
 * <p>스키마(V18)·도메인 플래그(storyAvailable/theaterAvailable)는 선반영돼 휴면 상태고,
 * 실제 개방은 이 게이트가 통제한다. <b>기본 off</b> — 켜기 전까지 UGC 월드 STORY/THEATER
 * 진입은 400으로 차단되고 기존 동작에 어떤 변화도 없다(씬 렌더 플래그와 동일한 안전 기본값 패턴).
 *
 * <pre>
 * ugc:
 *   modes:
 *     story-enabled: ${UGC_STORY_ENABLED:false}
 *     theater-enabled: ${UGC_THEATER_ENABLED:false}
 * </pre>
 */
@ConfigurationProperties(prefix = "ugc.modes")
public record UgcModeProperties(Boolean storyEnabled, Boolean theaterEnabled) {

    public boolean storyOn() {
        return Boolean.TRUE.equals(storyEnabled);
    }

    public boolean theaterOn() {
        return Boolean.TRUE.equals(theaterEnabled);
    }
}
