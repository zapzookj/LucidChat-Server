package com.spring.aichat.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * OpenRouter(OpenAI 호환) 호출을 위한 설정 프로퍼티
 */
@ConfigurationProperties(prefix = "openai")
public record OpenAiProperties(
    String apiKey,
    String baseUrl,
    String model,
    String sentimentModel,
    String appReferer,
    String appTitle
) {}
