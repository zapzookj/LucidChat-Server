package com.spring.aichat.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * [Phase 5.5-Illust] Fal.ai ComfyUI API 설정
 *
 * application.yml 예시:
 *   fal:
 *     api-key: "YOUR_FAL_AI_KEY"
 *     endpoint-id: "comfy/run"
 *     base-model-url: "https://lucid-chat-model.s3.ap-northeast-2.amazonaws.com/my_model.safetensors"
 *     location-lora-url: "https://lucid-chat-model.s3.ap-northeast-2.amazonaws.com/makoto_shinkai_lora.safetensors"
 *     webhook-base-url: "https://api.lucidchat.com"
 *     use-webhook: false
 */
@ConfigurationProperties(prefix = "fal")
public record FalAiProperties(
    String apiKey,
    String endpointId,
    String baseModelUrl,
    String locationLoraUrl,
    String webhookBaseUrl,
    boolean useWebhook
) {
    /** 동기 실행 URL */
    public String syncUrl() {
        return "https://fal.run/" + endpointId;
    }

    /** 비동기 큐 URL */
    public String queueUrl() {
        return "https://queue.fal.run/" + endpointId;
    }

    /** 웹훅이 활성화된 비동기 큐 URL */
    public String queueUrlWithWebhook() {
        return queueUrl() + "?fal_webhook=" + webhookBaseUrl + "/api/v1/webhook/fal";
    }
}