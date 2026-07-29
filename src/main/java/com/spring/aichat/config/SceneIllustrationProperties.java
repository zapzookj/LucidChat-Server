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
    Runpod runpod,
    Generation generation
) {
    public SceneIllustrationProperties {
        if (runpod == null) runpod = new Runpod(null, null);
        if (generation == null) generation = new Generation(null, null, null, null, null);
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
