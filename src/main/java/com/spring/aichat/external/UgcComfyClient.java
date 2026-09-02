package com.spring.aichat.external;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.spring.aichat.config.UgcPipelineProperties;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;

/**
 * [UGC v1] RunPod Serverless(worker-comfyui 5.x) 클라이언트 — WF-1/2/3 실행 전용.
 *
 * <p>레거시 {@link com.spring.aichat.external.runpod.RunPodComfyClient}(Phase 6 격리 보존,
 * 구버전 base64 응답 스키마)와 완전히 별개다 — UGC 트랙은 이 클래스만 사용하며
 * 설정도 {@code ugc.runpod.*} 네임스페이스를 쓴다.
 *
 * <p><b>worker 5.x 계약</b>:
 * <ul>
 *   <li>제출: {@code POST /v2/{endpointId}/run} — {@code {input:{workflow, images?}, webhook?}}</li>
 *   <li>입력 이미지: {@code input.images[] = [{name, image(base64)}]} — {@code name}은 워크플로의
 *       LoadImage {@code inputs.image}와 <b>동일 문자열</b>이어야 함. /run 요청 10MB 제한.</li>
 *   <li>상태: {@code GET /status/{id}} — 폴링 폴백용(웹훅 기본)</li>
 *   <li>완료 응답: {@code output.images[] = {filename, type:"s3_url", data:presigned}}
 *       (구버전 {@code output.message} 아님). presigned URL은 만료가 있으므로 <b>저장 금지</b> —
 *       수신 즉시 서비스 S3로 복사(UgcAssetService 책임).</li>
 *   <li>실패: {@code output.errors[]} 또는 status=FAILED</li>
 * </ul>
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class UgcComfyClient {

    /** /run 요청 바디 상한 (RunPod 계약 10MB) — 초과분은 제출 전 차단. */
    static final int RUN_PAYLOAD_LIMIT_BYTES = 10 * 1024 * 1024;

    private final UgcPipelineProperties props;
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
            log.info("[UGC-COMFY] RestClient initialized (endpoint={})", props.runpod().endpointId());
        } else {
            log.warn("[UGC-COMFY] ugc.runpod 미설정 — 호출 시점에 실패한다 (환경변수 UGC_RUNPOD_API_KEY/UGC_RUNPOD_ENDPOINT_ID)");
        }
        this.restClient = builder.build();
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  제출 (/run — 비동기 + webhook)
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /**
     * 워크플로 제출. webhook 미지정 시 폴링 전용.
     *
     * @param workflow   UgcWorkflowFactory 산출 그래프
     * @param images     입력 이미지 (WF-2/3) — 없으면 null/empty
     * @param webhookUrl 완료 통지 URL (nullable)
     * @return RunPod job id + 초기 상태
     */
    public SubmitResult submit(ObjectNode workflow, List<InputImage> images, String webhookUrl) {
        requireConfigured();

        ObjectNode inputNode = objectMapper.createObjectNode();
        inputNode.set("workflow", workflow);
        if (images != null && !images.isEmpty()) {
            ArrayNode arr = inputNode.putArray("images");
            for (InputImage img : images) {
                ObjectNode node = arr.addObject();
                node.put("name", img.name());
                node.put("image", img.base64());
            }
        }
        ObjectNode body = objectMapper.createObjectNode();
        body.set("input", inputNode);
        if (webhookUrl != null && !webhookUrl.isBlank()) {
            body.put("webhook", webhookUrl);
        }

        guardPayloadSize(body, images);

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
            log.info("[UGC-COMFY] submitted: jobId={}, status={}, webhook={}", jobId, status, webhookUrl != null);
            return new SubmitResult(jobId, status);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            log.error("[UGC-COMFY] submit failed: {}", e.getMessage());
            throw new IllegalStateException("RunPod 제출 실패: " + e.getMessage(), e);
        }
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  상태 조회 (/status — 폴링 폴백)
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    public JobStatus getStatus(String jobId) {
        requireConfigured();
        try {
            String responseStr = restClient.get()
                .uri(props.runpod().statusUrl(jobId))
                .retrieve()
                .body(String.class);
            return parseStatusPayload(jobId, objectMapper.readTree(responseStr));
        } catch (HttpClientErrorException.NotFound e) {
            // [D-3.2a] 404 = 외부 잡 결과 소실(RunPod 보존 기간 경과 퍼지·엔드포인트 교체). 종전엔 아래 catch가
            //   일시 오류와 같은 "ERROR"로 합성해 폴러가 영구 스킵했고(키도 안 지움), 스윕은 키 존재만 보고
            //   위임해 데드락이 됐다. 영구 실패를 별도 상태로 올려 폴러가 실패 이벤트로 전환할 수 있게 한다.
            log.warn("[UGC-COMFY] status 404 — 외부 잡 결과 소실: jobId={}", jobId);
            return new JobStatus(jobId, JobStatus.NOT_FOUND, List.of(), "외부 잡 결과 소실(404)", null, null);
        } catch (Exception e) {
            log.warn("[UGC-COMFY] status poll failed: jobId={}, {}", jobId, e.getMessage());
            return new JobStatus(jobId, JobStatus.TRANSIENT_ERROR, List.of(), e.getMessage(), null, null);
        }
    }

    public boolean health() {
        requireConfigured();
        try {
            String resp = restClient.get().uri(props.runpod().healthUrl()).retrieve().body(String.class);
            return resp != null;
        } catch (Exception e) {
            log.warn("[UGC-COMFY] health check failed: {}", e.getMessage());
            return false;
        }
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  응답 파싱 (webhook 페이로드도 동일 구조 — 컨트롤러가 재사용)
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /**
     * /status 응답 또는 webhook 페이로드(동일 스키마)를 파싱.
     * worker 5.x: {@code {id, status, output:{images:[{filename,type,data}], errors?}, delayTime, executionTime}}
     */
    public JobStatus parseStatusPayload(String jobIdFallback, JsonNode resp) {
        String jobId = resp.path("id").asText(jobIdFallback);
        String status = resp.path("status").asText("UNKNOWN");
        Long delayTime = resp.path("delayTime").isNumber() ? resp.path("delayTime").asLong() : null;
        Long executionTime = resp.path("executionTime").isNumber() ? resp.path("executionTime").asLong() : null;

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

        return new JobStatus(jobId, status, images, error, delayTime, executionTime);
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    private void requireConfigured() {
        if (!props.runpod().configured()) {
            throw new IllegalStateException(
                "UGC RunPod 미설정 — UGC_RUNPOD_API_KEY / UGC_RUNPOD_ENDPOINT_ID 환경변수를 확인하라");
        }
    }

    /** /run 10MB 제한 사전 차단 — 직렬화 바이트 기준 (base64 이미지가 지배 항). 패키지 가시성: 테스트용. */
    void guardPayloadSize(ObjectNode body, List<InputImage> images) {
        long size;
        try {
            size = objectMapper.writeValueAsBytes(body).length;
        } catch (Exception e) {
            throw new IllegalStateException("페이로드 직렬화 실패", e);
        }
        if (size > RUN_PAYLOAD_LIMIT_BYTES) {
            int imageCount = images == null ? 0 : images.size();
            throw new IllegalArgumentException(
                "RunPod /run 페이로드 %dMB 초과 (%.1fMB, images=%d) — 입력 PNG 압축 필요"
                    .formatted(RUN_PAYLOAD_LIMIT_BYTES / (1024 * 1024), size / 1048576.0, imageCount));
        }
    }

    private static String abbreviate(String s) {
        if (s == null) return "null";
        return s.length() <= 300 ? s : s.substring(0, 300) + "…";
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  DTO
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /** {@code name}은 워크플로 LoadImage {@code inputs.image}와 동일 문자열이어야 한다. */
    public record InputImage(String name, String base64) {}

    public record SubmitResult(String jobId, String status) {}

    /**
     * @param images COMPLETED 시 산출 이미지 — {@code data}는 만료 있는 presigned URL(저장 금지, 즉시 복사)
     */
    public record JobStatus(String jobId, String status, List<OutputImage> images,
                            String error, Long delayTime, Long executionTime) {
        /** [D-3.2a] 클라이언트 합성 상태 — RunPod가 보내는 값이 아니다. 일시 오류(네트워크·5xx): 다음 주기 재시도. */
        public static final String TRANSIENT_ERROR = "ERROR";
        /** [D-3.2a] 클라이언트 합성 상태 — /status 404: 외부 결과 소실 확정(영구). 폴러가 실패 이벤트로 전환한다. */
        public static final String NOT_FOUND = "NOT_FOUND";

        public boolean completed() { return "COMPLETED".equalsIgnoreCase(status); }
        public boolean failed() { return "FAILED".equalsIgnoreCase(status) || "CANCELLED".equalsIgnoreCase(status) || "TIMED_OUT".equalsIgnoreCase(status); }
        public boolean inFlight() { return "IN_QUEUE".equalsIgnoreCase(status) || "IN_PROGRESS".equalsIgnoreCase(status); }
        public boolean transientError() { return TRANSIENT_ERROR.equalsIgnoreCase(status); }
        public boolean notFound() { return NOT_FOUND.equalsIgnoreCase(status); }

        /**
         * [D-3.2a/2b] 소실된 외부 잡을 파이프라인의 정상 실패 경로로 주입하기 위한 합성 FAILED —
         * {@code onComfyEvent}가 스테이지별 재시도 예산(무과금)으로 재제출하고, 소진 시에만 실패·환불한다.
         * 블랭킷 failAndRefund보다 완주분을 보존한다(감정 14종을 다 그린 잡의 누끼 1건이 사라졌다고 전부 버리지 않는다).
         */
        public static JobStatus lost(String jobId, String reason) {
            return new JobStatus(jobId, "FAILED", List.of(), reason, null, null);
        }
    }

    public record OutputImage(String filename, String type, String data) {}
}
