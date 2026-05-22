package com.spring.aichat.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * [Phase 6 격리 보존] RunPod Serverless 설정 (Phase 8 회귀 옵션 대비).
 *
 * <p>{@code runpod.enabled=true}일 때만 활성화. 기본 비활성 — 운영 영향 없음.
 *
 * <pre>
 * application.yml (활성화 시):
 *   runpod:
 *     enabled: true
 *     api-key: ${RUNPOD_API_KEY}
 *     endpoint-id: ${RUNPOD_ENDPOINT_ID}
 *     base-model-url: ${S3_BASE_MODEL_URL}
 *     location-lora-url: ${S3_LOCATION_LORA_URL}
 * </pre>
 */
@Configuration
@ConditionalOnProperty(name = "runpod.enabled", havingValue = "true")
@ConfigurationProperties(prefix = "runpod")
public class RunPodProperties {

    private String apiKey;
    private String endpointId;
    private String baseModelUrl;
    private String locationLoraUrl;

    public String apiKey() { return apiKey; }
    public String endpointId() { return endpointId; }
    public String baseModelUrl() { return baseModelUrl; }
    public String locationLoraUrl() { return locationLoraUrl; }

    public void setApiKey(String apiKey) { this.apiKey = apiKey; }
    public void setEndpointId(String endpointId) { this.endpointId = endpointId; }
    public void setBaseModelUrl(String baseModelUrl) { this.baseModelUrl = baseModelUrl; }
    public void setLocationLoraUrl(String locationLoraUrl) { this.locationLoraUrl = locationLoraUrl; }

    public String queueUrl() {
        return "https://api.runpod.ai/v2/" + endpointId + "/run";
    }
}