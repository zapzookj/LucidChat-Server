package com.spring.aichat.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 기본 캐릭터 시드(초기 데이터) 설정 값
 */
@ConfigurationProperties(prefix = "app.default-character")
public record DefaultCharacterProperties(
    String name,
    String llmModelName,
    String baseSystemPrompt,
    String ttsVoiceId,
    String defaultImageUrl
) {}
