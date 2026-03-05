package com.spring.aichat.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Phase 5 BM: proModel 추가
 *
 * application.yml:
 * openai:
 *   model: google/gemini-2.0-flash-001       # 일반 모드 (저비용)
 *   pro-model: anthropic/claude-sonnet-4-20250514    # 부스트 모드 (고급)
 *   sentiment-model: ...
 */
@ConfigurationProperties(prefix = "openai")
public record OpenAiProperties(
    String apiKey,
    String baseUrl,
    String model,
    String proModel,
    String sentimentModel,
    String appReferer,
    String appTitle
) {}