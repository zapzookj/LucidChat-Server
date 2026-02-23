package com.spring.aichat.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 기본 캐릭터 시드(초기 데이터) 설정 값
 *
 * [Phase 4.5] 로비 표시용 필드 추가
 */
@ConfigurationProperties(prefix = "app.default-character")
public record DefaultCharacterProperties(
    String name,
    String llmModelName,
    String baseSystemPrompt,
    String ttsVoiceId,
    String defaultImageUrl,
    // [Phase 4.5] 로비 표시용
    String tagline,
    String thumbnailUrl,
    String description
) {}