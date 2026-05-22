package com.spring.aichat.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * [Phase 6-Illust] ModelsLab API 설정 — 캐릭터 일러스트 트랙 전용.
 *
 * <p>ModelsLab은 NSFW를 ToS 차원에서 정식 운영하는 카테고리("Uncensored AI API")로 보유한
 * B2B 플랫폼이며, "CivitAI Alternative" 라인으로 외부 SDXL/LoRA 임포트를 공식 지원한다.
 * Phase 6 일러스트 피벗의 캐릭터 트랙에 채택.
 *
 * <p>다중 LoRA: lora_model/lora_strength를 콤마 구분 문자열로 전달 (캐릭터 정체성 + Detail + Style).
 *
 * <pre>
 * application.yml:
 *   modelslab:
 *     api-key: ${MODELSLAB_API_KEY}
 *     base-url: https://modelslab.com/api/v5
 *     text2img-endpoint: /lora/text2img       # PoC 결과에 따라 /images/text2img 등으로 토글
 *     fetch-endpoint:    /images/fetch        # 큐 결과 조회 (POST with key)
 *     default-model-id:  ${MODELSLAB_DEFAULT_MODEL_ID}   # PoC 후속 주입 (NoobAI/Illustrious/Animagine)
 *     webhook-base-url:  ${LUCID_WEBHOOK_BASE}
 *     webhook-secret:    ${MODELSLAB_WEBHOOK_SECRET}
 *     # 글로벌 보조 LoRA (캐릭터별 정체성 LoRA는 IllustrationPromptAssembler에서 동적 주입)
 *     detail-lora-id:    ${MODELSLAB_DETAIL_LORA_ID:}
 *     detail-lora-weight: 0.4
 *     style-lora-id:     ${MODELSLAB_STYLE_LORA_ID:}
 *     style-lora-weight: 0.5
 *     # 생성 파라미터 기본값
 *     width: 1024
 *     height: 1024
 *     steps: 30
 *     guidance-scale: 7.0
 *     sampler: "DPM++ 2M Karras"
 * </pre>
 */
@ConfigurationProperties(prefix = "modelslab")
public record ModelsLabProperties(
    String apiKey,
    String baseUrl,
    String text2imgEndpoint,
    String fetchEndpoint,
    String defaultModelId,
    String webhookBaseUrl,
    String webhookSecret,
    String detailLoraId,
    Double detailLoraWeight,
    String styleLoraId,
    Double styleLoraWeight,
    Integer width,
    Integer height,
    Integer steps,
    Double guidanceScale,
    String sampler
) {
    /** 생성 제출 URL (POST, body에 key 포함). */
    public String text2imgUrl() {
        return baseUrl + text2imgEndpoint;
    }

    /** 큐 결과 조회 URL (POST, body에 key 포함). */
    public String fetchUrl(String generationId) {
        return baseUrl + fetchEndpoint + "/" + generationId;
    }

    /** 우리 서버의 webhook 콜백 URL. */
    public String webhookUrl() {
        return webhookBaseUrl + "/api/v1/webhook/modelslab";
    }

    // ── 기본값 헬퍼 ──

    public int effectiveWidth()  { return width  != null ? width  : 1024; }
    public int effectiveHeight() { return height != null ? height : 1024; }
    public int effectiveSteps()  { return steps  != null ? steps  : 30; }
    public double effectiveGuidance() { return guidanceScale != null ? guidanceScale : 7.0; }
    public String effectiveSampler()  { return sampler != null ? sampler : "DPM++ 2M Karras"; }

    public boolean hasDetailLora() {
        return detailLoraId != null && !detailLoraId.isBlank();
    }
    public boolean hasStyleLora() {
        return styleLoraId != null && !styleLoraId.isBlank();
    }
    public double detailLoraWeightOr(double fallback) {
        return detailLoraWeight != null ? detailLoraWeight : fallback;
    }
    public double styleLoraWeightOr(double fallback) {
        return styleLoraWeight != null ? styleLoraWeight : fallback;
    }
}