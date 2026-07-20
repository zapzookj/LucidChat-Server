package com.spring.aichat.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * [UGC v1] 캐릭터 생성 파이프라인(스튜디오) 설정.
 *
 * <p>에너지 단가·잡 정책·RunPod Serverless(ComfyUI)·fal.ai Qwen 편집 트랙을 한 네임스페이스로 묶는다.
 * 레거시 {@link RunPodProperties}(runpod.* — Phase 6 격리 보존)와는 완전히 별개 네임스페이스이며,
 * UGC 트랙은 {@code ugc.runpod.*}만 사용한다.
 *
 * <p><b>확정 단가 (2026-07-17 종원)</b>: 기본 패키지 20 에너지(황금샷 1배치 + 베이스 + 감정 14종 + 누끼),
 * 리롤(황금샷 배치 / 감정 1컷) 각 2 에너지. 자동 재시도는 무과금, 유저 발의 리롤만 과금.
 *
 * <pre>
 * application.yml:
 *   ugc:
 *     energy:
 *       base-package-cost: 20
 *       golden-reroll-cost: 2
 *       emotion-reroll-cost: 2
 *     job:
 *       wait-ttl-hours: 72
 *     runpod:
 *       api-key: ${UGC_RUNPOD_API_KEY:}
 *       endpoint-id: ${UGC_RUNPOD_ENDPOINT_ID:}
 *       webhook-base-url: ${LUCID_WEBHOOK_BASE:}
 *       webhook-secret: ${UGC_RUNPOD_WEBHOOK_SECRET:}
 *     qwen:
 *       model: fal-ai/qwen-image-edit-2511
 *     generation:
 *       golden-batch-size: 4
 * </pre>
 */
@ConfigurationProperties(prefix = "ugc")
public record UgcPipelineProperties(
    Energy energy,
    Job job,
    Runpod runpod,
    Qwen qwen,
    Generation generation
) {
    public UgcPipelineProperties {
        if (energy == null) energy = new Energy(null, null, null, null);
        if (job == null) job = new Job(null, null, null, null);
        if (runpod == null) runpod = new Runpod(null, null, null, null);
        if (qwen == null) qwen = new Qwen(null, null, null);
        if (generation == null) generation = new Generation(null, null);
    }

    /** 에너지 단가. null이면 확정 기본값(20/2/2/2). */
    public record Energy(Integer basePackageCost, Integer goldenRerollCost,
                         Integer baseRerollCost, Integer emotionRerollCost) {
        public int basePackage() { return basePackageCost != null ? basePackageCost : 20; }
        public int goldenReroll() { return goldenRerollCost != null ? goldenRerollCost : 2; }
        /** [2026-07-20 개편] 스탠딩 후보 배치 리롤 (Qwen 2패스×2 + WF-2×2 재파생). */
        public int baseReroll() { return baseRerollCost != null ? baseRerollCost : 2; }
        public int emotionReroll() { return emotionRerollCost != null ? emotionRerollCost : 2; }
    }

    /** 잡 수명·재시도 정책. */
    public record Job(Integer waitTtlHours, Integer stageAutoRetries, Integer emotionMaxRetries, Integer pollFallbackSeconds) {
        /** *_WAIT 상태 방치 만료(시간). */
        public int ttlHours() { return waitTtlHours != null ? waitTtlHours : 72; }
        /** 스테이지 단위 자동 재시도 횟수(무과금). */
        public int autoRetries() { return stageAutoRetries != null ? stageAutoRetries : 2; }
        /** 감정 컷 개별 재시도 상한 — 초과 시 해당 컷만 FAILED 마킹하고 진행. */
        public int emotionRetries() { return emotionMaxRetries != null ? emotionMaxRetries : 3; }
        /** webhook 유실 대비 폴링 폴백 주기(초). */
        public int pollSeconds() { return pollFallbackSeconds != null ? pollFallbackSeconds : 60; }
    }

    /** UGC 전용 RunPod Serverless (worker-comfyui 5.x). */
    public record Runpod(String apiKey, String endpointId, String webhookBaseUrl, String webhookSecret) {
        public String runUrl() { return "https://api.runpod.ai/v2/" + endpointId + "/run"; }
        public String statusUrl(String jobId) { return "https://api.runpod.ai/v2/" + endpointId + "/status/" + jobId; }
        public String healthUrl() { return "https://api.runpod.ai/v2/" + endpointId + "/health"; }
        public boolean configured() {
            return apiKey != null && !apiKey.isBlank() && endpointId != null && !endpointId.isBlank();
        }
    }

    /** fal.ai Qwen 이미지 편집 (자세 변환·감정 파생 — PoseEditClient 구현체가 사용). */
    public record Qwen(String model, Integer numInferenceSteps, Double guidanceScale) {
        public String effectiveModel() {
            return (model != null && !model.isBlank()) ? model : "fal-ai/qwen-image-edit-2511";
        }
        /** 공식 기본값 28. */
        public int steps() { return numInferenceSteps != null ? numInferenceSteps : 28; }
        /** 공식 기본값 4.5. */
        public double guidance() { return guidanceScale != null ? guidanceScale : 4.5; }
    }

    /** 워크플로 치환 노브. 명세된 노브 외 파라미터는 템플릿 JSON(검증본)에 동결. */
    public record Generation(Integer goldenBatchSize, Double refineDenoise) {
        /** [2026-07-20 개편] 황금샷 배치 4→2 (스탠딩 후보 선택 단계 신설로 역할 축소 — 썸네일·원화 확정용). */
        public int batchSize() { return goldenBatchSize != null ? goldenBatchSize : 2; }
        /** null이면 템플릿 검증값(0.4) 유지. 튜닝 범위 0.30~0.45 권장. */
        public Double refineDenoiseOverride() { return refineDenoise; }
    }
}
