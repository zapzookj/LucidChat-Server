package com.spring.aichat.external;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.spring.aichat.config.SceneIllustrationProperties;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;

/**
 * [2026-07-30 B-2/A-1 재피벗] 실시간 씬 일러 전용 RunPod Serverless(worker-comfyui 5.x) 클라이언트.
 * 디오라마 검증 클라이언트의 개별 포팅(폴링 전용 — 웹훅 없음, docs/09 A-1 오케스트레이션 확정안).
 *
 * <p>UGC 빌더의 {@link UgcComfyClient}({@code ugc.runpod.*})와 완전히 별개 엔드포인트
 * ({@code illustration.scene.runpod.*}). 계약은 동일: {@code POST /run {input:{workflow}}},
 * 완료 {@code output.images[]={filename,type:"s3_url",data:presigned}}. presigned는 저장 금지(즉시 복사).
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class SceneComfyClient {

    static final int RUN_PAYLOAD_LIMIT_BYTES = 10 * 1024 * 1024;

    private final SceneIllustrationProperties props;
    private final ObjectMapper objectMapper;

    private RestClient restClient;

    @PostConstruct
    void init() {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(10_000);
        requestFactory.setReadTimeout(60_000);

        RestClient.Builder builder = RestClient.builder().requestFactory(requestFactory);
        if (props.runpod().configured()) {
            builder.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + props.runpod().apiKey());
            log.info("[SCENE-COMFY] RestClient initialized (endpoint={})", props.runpod().endpointId());
        } else if (props.isEnabled()) {
            log.warn("[SCENE-COMFY] illustration.scene.runpod 미설정 — 호출 시점 실패 "
                + "(환경변수 SCENE_RUNPOD_API_KEY/SCENE_RUNPOD_ENDPOINT_ID)");
        }
        this.restClient = builder.build();
    }

    public boolean configured() {
        return props.runpod().configured();
    }

    /** 씬 워크플로 제출(t2i — 입력 이미지 없음, 폴링 전용). */
    public SubmitResult submit(ObjectNode workflow) {
        requireConfigured();

        ObjectNode inputNode = objectMapper.createObjectNode();
        inputNode.set("workflow", workflow);
        ObjectNode body = objectMapper.createObjectNode();
        body.set("input", inputNode);

        guardPayloadSize(body);

        try {
            String responseStr = restClient.post()
                .uri(props.runpod().runUrl())
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(String.class);

            JsonNode resp = objectMapper.readTree(responseStr);
            String jobId = resp.path("id").asText(null);
            String status = resp.path("status").asText("UNKNOWN");
            if (jobId == null || jobId.isBlank()) {
                throw new IllegalStateException("RunPod /run 응답에 job id 없음: " + abbreviate(responseStr));
            }
            log.info("[SCENE-COMFY] submitted: jobId={}, status={}", jobId, status);
            return new SubmitResult(jobId, status);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            log.error("[SCENE-COMFY] submit failed: {}", e.getMessage());
            throw new IllegalStateException("RunPod 제출 실패: " + e.getMessage(), e);
        }
    }

    public JobStatus getStatus(String jobId) {
        requireConfigured();
        try {
            String responseStr = restClient.get()
                .uri(props.runpod().statusUrl(jobId))
                .retrieve()
                .body(String.class);
            return parseStatusPayload(jobId, objectMapper.readTree(responseStr));
        } catch (Exception e) {
            log.warn("[SCENE-COMFY] status poll failed: jobId={}, {}", jobId, e.getMessage());
            return new JobStatus(jobId, "ERROR", List.of(), e.getMessage());
        }
    }

    /** /status 응답 파싱 (UgcComfyClient와 동일 스키마). */
    public JobStatus parseStatusPayload(String jobIdFallback, JsonNode resp) {
        String jobId = resp.path("id").asText(jobIdFallback);
        String status = resp.path("status").asText("UNKNOWN");

        JsonNode output = resp.path("output");
        List<OutputImage> images = new ArrayList<>();
        JsonNode imagesNode = output.path("images");
        if (imagesNode.isArray()) {
            for (JsonNode img : imagesNode) {
                String data = img.path("data").asText(null);
                if (data == null || data.isBlank()) continue;
                images.add(new OutputImage(
                    img.path("filename").asText(null),
                    img.path("type").asText("s3_url"),
                    data
                ));
            }
        }

        String error = null;
        JsonNode errors = output.path("errors");
        if (errors.isArray() && !errors.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            errors.forEach(e -> {
                if (sb.length() > 0) sb.append("; ");
                sb.append(e.asText());
            });
            error = sb.toString();
        } else if ("FAILED".equalsIgnoreCase(status)) {
            error = output.path("error").asText(resp.path("error").asText("RunPod job failed"));
        }

        return new JobStatus(jobId, status, images, error);
    }

    private void requireConfigured() {
        if (!props.runpod().configured()) {
            throw new IllegalStateException(
                "씬 렌더 RunPod 미설정 — SCENE_RUNPOD_API_KEY / SCENE_RUNPOD_ENDPOINT_ID 환경변수를 확인하라");
        }
    }

    void guardPayloadSize(ObjectNode body) {
        long size;
        try {
            size = objectMapper.writeValueAsBytes(body).length;
        } catch (Exception e) {
            throw new IllegalStateException("페이로드 직렬화 실패", e);
        }
        if (size > RUN_PAYLOAD_LIMIT_BYTES) {
            throw new IllegalArgumentException(
                "RunPod /run 페이로드 %dMB 초과 (%.1fMB)".formatted(
                    RUN_PAYLOAD_LIMIT_BYTES / (1024 * 1024), size / 1048576.0));
        }
    }

    private static String abbreviate(String s) {
        if (s == null) return "null";
        return s.length() <= 300 ? s : s.substring(0, 300) + "…";
    }

    // ── DTO ──
    public record SubmitResult(String jobId, String status) {}

    public record JobStatus(String jobId, String status, List<OutputImage> images, String error) {
        public boolean completed() { return "COMPLETED".equalsIgnoreCase(status); }
        public boolean failed() {
            return "FAILED".equalsIgnoreCase(status) || "CANCELLED".equalsIgnoreCase(status) || "TIMED_OUT".equalsIgnoreCase(status);
        }
        public boolean inFlight() { return "IN_QUEUE".equalsIgnoreCase(status) || "IN_PROGRESS".equalsIgnoreCase(status); }
    }

    public record OutputImage(String filename, String type, String data) {}
}
