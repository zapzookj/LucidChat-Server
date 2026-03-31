package com.spring.aichat.external;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.spring.aichat.config.FalAiProperties;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * [Phase 5.5-RunPod] RunPod Serverless (ComfyUI) API 클라이언트
 *
 * 기존 Fal.ai → RunPod 완전 치환:
 *   1. 인증: Key → Bearer
 *   2. 페이로드: {"input": {"workflow": ...}} 래핑
 *   3. 응답: 이미지가 URL이 아닌 Base64 raw data로 반환
 *   4. RestClient: SimpleClientHttpRequestFactory (JDK HttpClient 헤더 버그 회피)
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class FalAiClient {

    private final FalAiProperties props;
    private final ObjectMapper objectMapper;

    /** RunPod 전용 RestClient (단일 인스턴스 재사용) */
    private RestClient runpodRestClient;

    @PostConstruct
    void init() {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(10_000);
        requestFactory.setReadTimeout(60_000);

        this.runpodRestClient = RestClient.builder()
            .requestFactory(requestFactory)
            .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + props.apiKey())
            .build();

        log.info("[RUNPOD] RestClient initialized (Bearer auth, SimpleClientHttpRequestFactory)");
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  큐 제출 / 폴링 / 결과 조회
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    public QueueResponse submitToQueue(JsonNode workflow) {
        String url = props.queueUrl();

        ObjectNode inputNode = objectMapper.createObjectNode();
        inputNode.set("workflow", workflow);
        ObjectNode body = objectMapper.createObjectNode();
        body.set("input", inputNode);

        log.info("[RUNPOD] Submitting to queue: {}", url);

        try {
            String responseStr = runpodRestClient.post()
                .uri(url)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(String.class);

            JsonNode resp = objectMapper.readTree(responseStr);
            String requestId = resp.path("id").asText(null);
            String status = resp.path("status").asText("UNKNOWN");

            if (requestId == null) {
                throw new RuntimeException("Failed to get request ID from RunPod");
            }

            String statusUrl = url.replace("/run", "/status/") + requestId;
            String responseUrl = statusUrl;

            log.info("[RUNPOD] Queue submitted: requestId={}, status={}", requestId, status);
            return new QueueResponse(requestId, statusUrl, responseUrl);

        } catch (Exception e) {
            log.error("[RUNPOD] Queue submission failed", e);
            throw new RuntimeException("RunPod queue submission failed: " + e.getMessage(), e);
        }
    }

    public PollResult pollStatus(String statusUrl) {
        try {
            String responseStr = runpodRestClient.get()
                .uri(statusUrl)
                .retrieve()
                .body(String.class);

            JsonNode resp = objectMapper.readTree(responseStr);
            String status = resp.path("status").asText("UNKNOWN");

            log.info("[RUNPOD] Poll: status={}", status);

            if ("COMPLETED".equalsIgnoreCase(status)) {
                return new PollResult(true, status, resp);
            } else if ("FAILED".equalsIgnoreCase(status)) {
                log.error("[RUNPOD] Generation failed: {}", resp);
                return new PollResult(false, "FAILED", resp);
            }

            return new PollResult(false, status, resp);

        } catch (Exception e) {
            log.warn("[RUNPOD] Poll failed: {} — {}", e.getClass().getSimpleName(), e.getMessage());
            return new PollResult(false, "ERROR", null);
        }
    }

    /** RunPod은 폴링 결과에 페이로드가 포함됨 → 별도 fetch 불필요 */
    public JsonNode fetchResult(String responseUrl) {
        return pollStatus(responseUrl).payload();
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  [핵심] Base64 이미지 데이터 추출
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /**
     * RunPod/ComfyUI 응답에서 첫 번째 이미지의 Base64 데이터 추출
     *
     * RunPod ComfyUI SaveImage 노드 응답 구조 (유연 탐색):
     *   구조 A: { "output": { "images": [{ "data": "base64..." }] } }
     *   구조 B: { "output": { "message": "base64..." } }
     *   구조 C: { "output": { "52": { "images": [{ "data": "base64..." }] } } }
     *   구조 D: { "output": { "message": { "outputs": { "52": { "images": [{ "data": "..." }] } } } } }
     *
     * @return Base64 인코딩된 이미지 데이터 (null이면 추출 실패)
     */
    public String extractImageBase64(JsonNode resultPayload) {
        try {
            JsonNode output = resultPayload.path("output");
            if (output.isMissingNode() || output.isNull()) {
                log.warn("[RUNPOD] No 'output' field in response");
                return null;
            }

            // 전략 1: output.images[0].data
            String found = tryExtractBase64FromImages(output.path("images"));
            if (found != null) return found;

            // 전략 2: output.message가 직접 base64 문자열
            JsonNode message = output.path("message");
            if (message.isTextual() && message.asText().length() > 1000) {
                log.info("[RUNPOD] Image extracted from output.message (direct base64, {}chars)",
                    message.asText().length());
                return message.asText();
            }

            // 전략 3: output.message.outputs.{nodeId}.images[0].data
            if (message.isObject()) {
                JsonNode outputs = message.path("outputs");
                if (outputs.isObject()) {
                    String fromOutputs = searchNodesForBase64(outputs);
                    if (fromOutputs != null) return fromOutputs;
                }
            }

            // 전략 4: output.{nodeId}.images[0].data (노드 직접 나열)
            String fromNodes = searchNodesForBase64(output);
            if (fromNodes != null) return fromNodes;

            log.warn("[RUNPOD] No base64 image found. output field names: [{}]",
                joinFieldNames(output));
            return null;

        } catch (Exception e) {
            log.error("[RUNPOD] Image data extraction failed", e);
            return null;
        }
    }

    /**
     * @deprecated Fal.ai 레거시 — RunPod에서는 extractImageBase64() 사용
     */
    @Deprecated
    public String extractImageUrl(JsonNode resultPayload) {
        try {
            JsonNode output = resultPayload.path("output");
            JsonNode outputs = output.path("message").path("outputs");
            if (outputs.isMissingNode()) outputs = resultPayload.path("outputs");
            if (outputs.isMissingNode()) outputs = output;

            for (var entry : (Iterable<Map.Entry<String, JsonNode>>) outputs::fields) {
                JsonNode images = entry.getValue().path("images");
                if (images.isArray()) {
                    for (JsonNode img : images) {
                        String url = img.path("url").asText(null);
                        if (url != null && !url.isBlank() && url.startsWith("http")) return url;
                    }
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    // ── Base64 추출 헬퍼 ──

    private String tryExtractBase64FromImages(JsonNode images) {
        if (images.isArray() && !images.isEmpty()) {
            for (JsonNode img : images) {
                String data = img.path("data").asText(null);
                if (data != null && !data.isBlank() && data.length() > 1000) {
                    log.info("[RUNPOD] Image extracted: base64 ({}chars)", data.length());
                    return data;
                }
            }
        }
        return null;
    }

    private String searchNodesForBase64(JsonNode parent) {
        for (var entry : (Iterable<Map.Entry<String, JsonNode>>) parent::fields) {
            String found = tryExtractBase64FromImages(entry.getValue().path("images"));
            if (found != null) return found;
        }
        return null;
    }

    private String joinFieldNames(JsonNode node) {
        StringBuilder sb = new StringBuilder();
        node.fieldNames().forEachRemaining(s -> {
            if (!sb.isEmpty()) sb.append(", ");
            sb.append(s);
        });
        return sb.toString();
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  워크플로우 빌더 (RunPod ComfyUI 순정 노드)
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    public JsonNode buildCharacterWorkflow(String loraUrl, String positivePrompt, String negativePrompt) {
        try {
            ObjectNode workflow = objectMapper.createObjectNode();

            workflow.set("1", objectMapper.readTree("""
                { "inputs": { "ckpt_name": "%s" }, "class_type": "CheckpointLoaderSimple",
                  "_meta": { "title": "체크포인트 로드" } }
                """.formatted(props.baseModelUrl())));

            workflow.set("2", objectMapper.readTree("""
                { "inputs": { "lora_name": "%s", "strength_model": 1, "strength_clip": 1,
                    "model": ["1", 0], "clip": ["1", 1] },
                  "class_type": "LoraLoader", "_meta": { "title": "LoRA 로드" } }
                """.formatted(loraUrl)));

            ObjectNode n48 = objectMapper.createObjectNode();
            ObjectNode n48i = objectMapper.createObjectNode();
            n48i.put("text", positivePrompt);
            n48i.set("clip", objectMapper.readTree("[\"2\", 1]"));
            n48.set("inputs", n48i); n48.put("class_type", "CLIPTextEncode");
            workflow.set("48", n48);

            ObjectNode n47 = objectMapper.createObjectNode();
            ObjectNode n47i = objectMapper.createObjectNode();
            n47i.put("text", negativePrompt);
            n47i.set("clip", objectMapper.readTree("[\"2\", 1]"));
            n47.set("inputs", n47i); n47.put("class_type", "CLIPTextEncode");
            workflow.set("47", n47);

            workflow.set("49", objectMapper.readTree("""
                { "inputs": { "width": 1024, "height": 1024, "batch_size": 1 },
                  "class_type": "EmptyLatentImage" }
                """));

            long seed = ThreadLocalRandom.current().nextLong(0, Long.MAX_VALUE);
            workflow.set("50", objectMapper.readTree("""
                { "inputs": { "seed": %d, "steps": 30, "cfg": 4,
                    "sampler_name": "euler", "scheduler": "normal", "denoise": 1,
                    "model": ["2", 0], "positive": ["48", 0],
                    "negative": ["47", 0], "latent_image": ["49", 0] },
                  "class_type": "KSampler" }
                """.formatted(seed)));

            workflow.set("51", objectMapper.readTree("""
                { "inputs": { "samples": ["50", 0], "vae": ["1", 2] }, "class_type": "VAEDecode" }
                """));
            workflow.set("52", objectMapper.readTree("""
                { "inputs": { "filename_prefix": "LucidChar", "images": ["51", 0] }, "class_type": "SaveImage" }
                """));

            return workflow;
        } catch (Exception e) {
            throw new RuntimeException("Failed to build character workflow", e);
        }
    }

    public JsonNode buildLocationWorkflow(String positivePrompt, String negativePrompt) {
        try {
            ObjectNode workflow = objectMapper.createObjectNode();

            workflow.set("1", objectMapper.readTree("""
                { "inputs": { "ckpt_name": "%s" }, "class_type": "CheckpointLoaderSimple" }
                """.formatted(props.baseModelUrl())));

            workflow.set("67", objectMapper.readTree("""
                { "inputs": { "lora_name": "%s", "strength_model": 0.6, "strength_clip": 1,
                    "model": ["1", 0], "clip": ["1", 1] },
                  "class_type": "LoraLoader" }
                """.formatted(props.locationLoraUrl())));

            ObjectNode n48 = objectMapper.createObjectNode();
            ObjectNode n48i = objectMapper.createObjectNode();
            n48i.put("text", positivePrompt);
            n48i.set("clip", objectMapper.readTree("[\"67\", 1]"));
            n48.set("inputs", n48i); n48.put("class_type", "CLIPTextEncode");
            workflow.set("48", n48);

            ObjectNode n47 = objectMapper.createObjectNode();
            ObjectNode n47i = objectMapper.createObjectNode();
            n47i.put("text", negativePrompt);
            n47i.set("clip", objectMapper.readTree("[\"67\", 1]"));
            n47.set("inputs", n47i); n47.put("class_type", "CLIPTextEncode");
            workflow.set("47", n47);

            workflow.set("49", objectMapper.readTree("""
                { "inputs": { "width": 1344, "height": 768, "batch_size": 1 },
                  "class_type": "EmptyLatentImage" }
                """));

            long seed = ThreadLocalRandom.current().nextLong(0, Long.MAX_VALUE);
            workflow.set("50", objectMapper.readTree("""
                { "inputs": { "seed": %d, "steps": 30, "cfg": 4,
                    "sampler_name": "euler", "scheduler": "normal", "denoise": 1,
                    "model": ["67", 0], "positive": ["48", 0],
                    "negative": ["47", 0], "latent_image": ["49", 0] },
                  "class_type": "KSampler" }
                """.formatted(seed)));

            workflow.set("51", objectMapper.readTree("""
                { "inputs": { "samples": ["50", 0], "vae": ["1", 2] }, "class_type": "VAEDecode" }
                """));
            workflow.set("52", objectMapper.readTree("""
                { "inputs": { "filename_prefix": "LucidBG", "images": ["51", 0] }, "class_type": "SaveImage" }
                """));

            return workflow;
        } catch (Exception e) {
            throw new RuntimeException("Failed to build location workflow", e);
        }
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    public record QueueResponse(String requestId, String statusUrl, String responseUrl) {}
    public record PollResult(boolean completed, String status, JsonNode payload) {}
}