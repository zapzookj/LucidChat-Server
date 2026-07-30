package com.spring.aichat.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * [2026-07-30 B-2/A-1 재피벗] 실시간 씬 일러 설정 — 전용 RunPod Serverless(ModelsLab 대체 트랙).
 *
 * <p>디오라마 샌드박스에서 검증된 파이프라인(docs/09 §A-1)의 본 서비스 수용부.
 * 워커 이미지 = lucid_comfy_worker_illu (TIPO·ppm·FaceDetailer 탑재, 워크플로 어그노스틱).
 * UGC 빌더의 {@code ugc.runpod.*}와 <b>별개 엔드포인트</b> — 배치(빌더) vs 저지연(실시간)의
 * head-of-line blocking 회피(docs/07 §B-2 운영상 2개 권장).
 *
 * <p><b>기본 비활성</b>({@code enabled=false}) — 켜기 전까지 기존 ModelsLab 트랙·채팅 흐름에
 * 어떤 변화도 없다. 활성 조건: 엔드포인트 가동 + 아래 환경변수 주입.
 *
 * <pre>
 * application.yml:
 *   illustration:
 *     scene:
 *       enabled: ${SCENE_ILLUST_ENABLED:false}
 *       trigger: ${SCENE_ILLUST_TRIGGER:manual}   # manual(유저 요청) | auto(인밴드 휴면)
 *       energy-cost: 5
 *       director:
 *         model: ${SCENE_DIRECTOR_MODEL:}          # 미지정 시 openai.model 폴백
 *       runpod:
 *         api-key: ${SCENE_RUNPOD_API_KEY:}
 *         endpoint-id: ${SCENE_RUNPOD_ENDPOINT_ID:}
 *       generation:
 *         width: 1344      # 실측 확정(docs/09 A-1): 배경 포함 랜드스케이프
 *         height: 768
 * </pre>
 */
@ConfigurationProperties(prefix = "illustration.scene")
public record SceneIllustrationProperties(
    Boolean enabled,
    String trigger,
    Integer energyCost,
    Director director,
    Runpod runpod,
    Generation generation
) {
    public SceneIllustrationProperties {
        if (director == null) director = new Director(null, null, null);
        if (runpod == null) runpod = new Runpod(null, null);
        if (generation == null) generation = new Generation(null, null, null, null, null);
    }

    /** 테스트/하위 호환 편의 생성자 — 트리거·과금 노브 기본값(manual/5). */
    public SceneIllustrationProperties(Boolean enabled, Runpod runpod, Generation generation) {
        this(enabled, null, null, null, runpod, generation);
    }

    /**
     * [2026-07-31 에픽 B] 트리거 모드 — 기본 {@code manual}(유저 요청 + 전용 프롬프트 라이터).
     * {@code auto}는 기존 인밴드 매턴 경로(채팅 LLM이 scene_illustration 필드 출력)의 휴면 보존값 —
     * 프리미엄/이벤트 연출 등 재활성 대비.
     */
    public boolean isAutoTrigger() {
        return "auto".equalsIgnoreCase(trigger);
    }

    /** 수동 요청 1회 에너지 (종원 확정 2026-07-31: 5 — ModelsLab 캐릭터 일러 10의 절반). */
    public int energyCostOrDefault() {
        return energyCost != null ? energyCost : 5;
    }

    /**
     * 전용 프롬프트 라이터(씬 디렉터) 노브 — 대화 맥락→L1 규약 스펙 구조화 LLM.
     * 모델 미지정 시 {@code openai.model}(gemini-3-flash) 폴백으로 시작해 튜닝(종원 확정).
     * 구조화 태스크 비사고 모델 규율(docs/09 §B-3) — 사고 모델 지정은 피할 것.
     */
    public record Director(String model, Integer maxTokens, Integer contextTurns) {
        public String modelOrDefault(String fallback) {
            return (model != null && !model.isBlank()) ? model : fallback;
        }
        public int maxTokensOrDefault() { return maxTokens != null ? maxTokens : 900; }
        /** 라이터에게 주는 최근 대화 로그 수(USER/ASSISTANT 합산). */
        public int contextTurnsOrDefault() { return contextTurns != null ? contextTurns : 14; }
    }

    /** 씬 렌더 트랙 활성 여부 — 기본 <b>비활성</b>(프로드 안전 기본값). */
    public boolean isEnabled() {
        return Boolean.TRUE.equals(enabled);
    }

    /** 활성 + 자격 주입까지 완료됐는지 — 배선 지점의 단일 판정. */
    public boolean ready() {
        return isEnabled() && runpod.configured();
    }

    /** 실시간 씬 일러 전용 RunPod Serverless (worker-comfyui 5.x 계약). */
    public record Runpod(String apiKey, String endpointId) {
        public String runUrl() { return "https://api.runpod.ai/v2/" + endpointId + "/run"; }
        public String statusUrl(String jobId) { return "https://api.runpod.ai/v2/" + endpointId + "/status/" + jobId; }
        public boolean configured() {
            return apiKey != null && !apiKey.isBlank() && endpointId != null && !endpointId.isBlank();
        }
    }

    /** 씬 렌더 치환 노브 — 명세 외 파라미터(30step/cfg4/euler/simple)는 템플릿 JSON에 동결. */
    public record Generation(Integer width, Integer height, String sceneNegative,
                             Boolean tipoEnabled, String tipoModel) {
        public int widthOrDefault() { return width != null ? width : 1344; }
        public int heightOrDefault() { return height != null ? height : 768; }
        /** TIPO 업샘플러 — 실측 유지 확정(off 시 눈색 정합 100→25%, docs/09 A-1). 기본 활성. */
        public boolean tipoEnabledOrDefault() { return tipoEnabled == null || tipoEnabled; }
        public String tipoModelOrDefault() {
            return (tipoModel != null && !tipoModel.isBlank()) ? tipoModel : "KBlueLeaf/TIPO-200M-ft2";
        }
        /** 씬 네거티브 오버라이드 — 미지정 시 템플릿 JSON 동결값 사용. */
        public String sceneNegativeOrNull() {
            return (sceneNegative != null && !sceneNegative.isBlank()) ? sceneNegative : null;
        }
    }
}
