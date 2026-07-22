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
    Generation generation,
    World world,
    /**
     * [2026-07-21] Stage0/W0 구조화 전용 LLM 모델 (OpenRouter 모델 문자열).
     * null이면 openai.model 폴백. 외형 태그 변환 품질이 황금샷 퀄리티를 좌우하므로
     * 전역 채팅 모델과 독립적으로 GPT 계열 등 지정 가능(수작업 시절 GPT 사용 실측).
     */
    String stage0Model
) {
    public UgcPipelineProperties {
        if (energy == null) energy = new Energy(null, null, null, null);
        if (job == null) job = new Job(null, null, null, null);
        if (runpod == null) runpod = new Runpod(null, null, null, null);
        if (qwen == null) qwen = new Qwen(null, null, null, null);
        if (generation == null) generation = new Generation(null, null, null);
        if (world == null) world = new World(null, null, null, null, null);
    }

    /** Stage0/W0 구조화 모델 — 미지정 시 null(호출측이 openai.model 폴백). */
    public String stage0ModelOrNull() {
        return (stage0Model != null && !stage0Model.isBlank()) ? stage0Model : null;
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
    public record Qwen(String model, Integer numInferenceSteps, Double guidanceScale, Boolean useNegativePrompt) {
        public String effectiveModel() {
            return (model != null && !model.isBlank()) ? model : "fal-ai/qwen-image-edit-2511";
        }
        /** 공식 기본값 28. */
        public int steps() { return numInferenceSteps != null ? numInferenceSteps : 28; }
        /** 공식 기본값 4.5. */
        public double guidance() { return guidanceScale != null ? guidanceScale : 4.5; }
        /**
         * [2026-07-21 튜닝 노브] 네거티브 프롬프트 사용 여부 — 플레이그라운드 실측상 비웠을 때
         * 결과 퀄리티가 더 낫다는 관찰. identity 가드는 positive의 Keep-지시가 담당하므로
         * A/B 후 false 전환 가능(전환 전 감정 14종 identity drift 재발 확인).
         */
        public boolean useNegative() { return useNegativePrompt == null || useNegativePrompt; }
    }

    /**
     * [세계관 빌더] 월드 생성 정책 — 캐릭터 트랙과 독립 키(단가 개정 시 상호 비간섭).
     * <b>확정 단가 (2026-07-20 종원)</b>: 기본 패키지 10 / 썸네일·장소 배경 리롤 각 1.
     * 장소는 LLM 제안 6개 기본, 유저 편집 상한 10.
     */
    public record World(Integer basePackageCost, Integer rerollCost, Integer waitTtlHours,
                        Integer staleSweepMinutes, Integer maxLocations) {
        public int basePackage() { return basePackageCost != null ? basePackageCost : 10; }
        public int reroll() { return rerollCost != null ? rerollCost : 1; }
        /** *_WAIT 상태 방치 만료(시간) — 캐릭터와 동일 72h이나 키는 독립. */
        public int ttlHours() { return waitTtlHours != null ? waitTtlHours : 72; }
        /**
         * fal 전용 트랙의 고아 잡 스윕 기준(분) — 웹훅/폴링 폴백이 없어 서버 재시작 시
         * in-flight 콜백이 유실되므로 "N분 무진행" PROCESSING 잡을 회수/환불한다.
         */
        public int staleMinutes() { return staleSweepMinutes != null ? staleSweepMinutes : 30; }
        /** 장소 상한 (LLM 제안 6 + 유저 추가 — 빌더 편집·사후 추가 공용). 2026-07-22 종원: 10→20 상향. */
        public int locationsMax() { return maxLocations != null ? maxLocations : 20; }
    }

    /** 워크플로 치환 노브. 명세된 노브 외 파라미터는 템플릿 JSON(검증본)에 동결. */
    public record Generation(Integer goldenBatchSize, Double refineDenoise, Double bgEmphasisWeight) {
        /** [2026-07-20 개편] 황금샷 배치 4→2 (스탠딩 후보 선택 단계 신설로 역할 축소 — 썸네일·원화 확정용). */
        public int batchSize() { return goldenBatchSize != null ? goldenBatchSize : 2; }
        /** null이면 템플릿 검증값(0.4) 유지. 튜닝 범위 0.30~0.45 권장. */
        public Double refineDenoiseOverride() { return refineDenoise; }
        /**
         * [2026-07-21 누끼 품질] WF-2 positive의 배경 보색 태그 어텐션 가중치 —
         * WF-3 경계 품질(머리카락 계단) 튜닝 노브. 권장 범위 1.1~1.5.
         */
        public double bgEmphasis() { return bgEmphasisWeight != null ? bgEmphasisWeight : 1.3; }
    }
}
