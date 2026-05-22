package com.spring.aichat.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * [Phase 6-Illust v3] Fal.ai 설정 — 공식 Java SDK 기반.
 *
 * <p><b>정정 이력</b>:
 * <ul>
 *   <li>v1: RunPod 호환 URL 하드코딩</li>
 *   <li>v2: REST 직접 호출로 회귀 (Java SDK 없다고 잘못 판단)</li>
 *   <li><b>v3 (현재): 공식 Java SDK {@code ai.fal.client:fal-client-async:0.7.1} 채택</b>
 *       — REST URL 패턴/폴링/webhook 수동 구현 전부 SDK에 위임</li>
 * </ul>
 *
 * <p>SDK가 큐 제출·폴링·결과 조회·재시도를 모두 처리하므로 base-url / status-url /
 * response-url / webhook 설정이 불필요해짐. 모델 ID와 API 키만 유지.
 *
 * <p><b>build.gradle 의존성 추가 필요</b>:
 * <pre>
 *   implementation 'ai.fal.client:fal-client-async:0.7.1'
 * </pre>
 *
 * <pre>
 * application.yml:
 *   fal:
 *     api-key: ${FAL_API_KEY}
 *     flux-model: fal-ai/flux-2/stream
 * </pre>
 */
@ConfigurationProperties(prefix = "fal")
public record FalAiProperties(
    String apiKey,
    String fluxModel
) {
    public String effectiveModel() {
        return (fluxModel != null && !fluxModel.isBlank()) ? fluxModel : "fal-ai/flux-2/stream";
    }
}