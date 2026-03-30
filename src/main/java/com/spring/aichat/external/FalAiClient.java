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
 * [Phase 5.5-Illust] Fal.ai ComfyUI API 클라이언트
 *
 * 두 가지 모드를 지원:
 * 1. 비동기 큐 (queue.fal.run) → 웹훅 또는 폴링으로 결과 수신
 * 2. 폴링 (status_url로 반복 조회)
 *
 * 캐릭터 일러스트와 장소 배경 모두 이 클라이언트를 통해 생성.
 * ComfyUI 워크플로우 JSON을 { "workflow": {...} } 형태로 래핑하여 전송.
 *
 * [Phase 5.5-Fix] JDK HttpClient 헤더 버그 회피
 *   - JdkClientHttpRequestFactory(기본값)는 GET 요청에도 Content-Length: 0 또는
 *     Transfer-Encoding: chunked를 강제 삽입하는 JDK 버그(JDK-8283544)가 있음.
 *   - Fal.ai 백엔드(Python/uvicorn)가 이 두 헤더의 공존을 400으로 거부.
 *   - 해결: SimpleClientHttpRequestFactory(HttpURLConnection 기반)를 명시 지정하여 회피.
 *   - 부수 효과: 매 호출마다 RestClient 재생성 → 단일 인스턴스 재사용으로 성능 개선.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class FalAiClient {

    private final FalAiProperties props;
    private final ObjectMapper objectMapper;

    /**
     * [Phase 5.5-Fix] Fal.ai 전용 RestClient (단일 인스턴스 재사용)
     *
     * SimpleClientHttpRequestFactory를 명시하여 JDK HttpClient의
     * Content-Length/Transfer-Encoding 충돌 버그를 회피한다.
     */
    private RestClient falRestClient;

    /**
     * [Phase 5.5-Fix] Bean 초기화 시 단일 RestClient 생성
     */
    @PostConstruct
    void init() {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(10_000);  // 10초
        requestFactory.setReadTimeout(30_000);     // 30초

        this.falRestClient = RestClient.builder()
            .requestFactory(requestFactory)
            .defaultHeader(HttpHeaders.AUTHORIZATION, "Key " + props.apiKey())
            .build();

        log.info("[FAL] RestClient initialized with SimpleClientHttpRequestFactory (JDK HttpClient header bug bypass)");
    }

    /**
     * 비동기 큐에 ComfyUI 워크플로우 제출
     *
     * @param workflow  ComfyUI 워크플로우 JSON (노드별 설정이 완료된 상태)
     * @return QueueResponse (requestId, statusUrl, responseUrl)
     */
    public QueueResponse submitToQueue(JsonNode workflow) {
        String url = props.useWebhook()
            ? props.queueUrlWithWebhook()
            : props.queueUrl();

        ObjectNode body = objectMapper.createObjectNode();
        body.set("workflow", workflow);

        log.info("[FAL] Submitting to queue: {}", url);

        try {
            String responseStr = falRestClient.post()
                .uri(url)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(String.class);

            JsonNode resp = objectMapper.readTree(responseStr);
            String requestId = resp.path("request_id").asText(null);
            String statusUrl = resp.path("status_url").asText(null);
            String responseUrl = resp.path("response_url").asText(null);

            log.info("[FAL] Queue submitted: requestId={}", requestId);
            return new QueueResponse(requestId, statusUrl, responseUrl);

        } catch (Exception e) {
            log.error("[FAL] Queue submission failed", e);
            throw new RuntimeException("Fal.ai queue submission failed: " + e.getMessage(), e);
        }
    }

    /**
     * 폴링으로 생성 결과 조회
     *
     * @param statusUrl  큐 응답에서 받은 status_url
     * @return 완료 시 이미지 URL이 담긴 JsonNode, 미완료 시 null
     */
    public PollResult pollStatus(String statusUrl) {
        try {
            String responseStr = falRestClient.get()
                .uri(statusUrl)
                .retrieve()
                .body(String.class);

            JsonNode resp = objectMapper.readTree(responseStr);
            String status = resp.path("status").asText("UNKNOWN");

            // [Phase 5.5-Fix] DEBUG → INFO: 폴링 상태 가시성 확보 (호출부에서 주기 제어)
            log.info("[FAL] Poll: status={}", status);

            if ("COMPLETED".equalsIgnoreCase(status)) {
                return new PollResult(true, status, resp);
            }
            return new PollResult(false, status, resp);

        } catch (Exception e) {
            log.warn("[FAL] Poll failed: {} — {}", e.getClass().getSimpleName(), e.getMessage());
            return new PollResult(false, "ERROR", null);
        }
    }

    /**
     * response_url로 최종 결과 직접 조회
     */
    public JsonNode fetchResult(String responseUrl) {
        try {
            String responseStr = falRestClient.get()
                .uri(responseUrl)
                .retrieve()
                .body(String.class);

            return objectMapper.readTree(responseStr);
        } catch (Exception e) {
            log.error("[FAL] Result fetch failed: {}", e.getMessage());
            throw new RuntimeException("Fal.ai result fetch failed", e);
        }
    }

    /**
     * Fal.ai 응답에서 첫 번째 이미지 URL 추출
     * ComfyUI 결과 구조: { "outputs": { "52": { "images": [{"url": "..."}] } } }
     */
    public String extractImageUrl(JsonNode resultPayload) {
        try {
            // 응답 최상위에 output이 있는 경우 (웹훅)
            JsonNode outputs = resultPayload.path("output");
            if (outputs.isMissingNode() || outputs.isNull()) {
                // 직접 조회 시 outputs 경로
                outputs = resultPayload.path("outputs");
            }

            // ComfyUI SaveImage 노드(52)의 출력에서 이미지 추출
            for (var entry : (Iterable<Map.Entry<String, JsonNode>>) outputs::fields) {
                JsonNode nodeOutput = entry.getValue();
                JsonNode images = nodeOutput.path("images");
                if (images.isArray() && !images.isEmpty()) {
                    String url = images.get(0).path("url").asText(null);
                    if (url != null && !url.isBlank()) {
                        log.info("[FAL] Image extracted: {}", url);
                        return url;
                    }
                }
            }

            log.warn("[FAL] No image found in response: {}", resultPayload);
            return null;
        } catch (Exception e) {
            log.error("[FAL] Image URL extraction failed", e);
            return null;
        }
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  캐릭터 일러스트 워크플로우 빌더
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /**
     * 캐릭터 일러스트용 ComfyUI 워크플로우 생성
     *
     * 노드 구조:
     *   1: CheckpointLoaderSimple (base model)
     *   2: LoraLoader (character LoRA)
     *  48: CLIPTextEncode (positive prompt)
     *  47: CLIPTextEncode (negative prompt)
     *  49: EmptyLatentImagePresets (1024x1024)
     *  50: KSampler
     *  51: VAEDecode
     *  52: SaveImage
     */
    public JsonNode buildCharacterWorkflow(String loraUrl, String positivePrompt, String negativePrompt) {
        try {
            ObjectNode workflow = objectMapper.createObjectNode();

            // Node 1: Checkpoint Loader
            workflow.set("1", objectMapper.readTree("""
                {
                  "inputs": { "ckpt_name": "%s" },
                  "class_type": "CheckpointLoaderSimple",
                  "_meta": { "title": "체크포인트 로드" }
                }
                """.formatted(props.baseModelUrl())));

            // Node 2: LoRA Loader
            workflow.set("2", objectMapper.readTree("""
                {
                  "inputs": {
                    "lora_name": "%s",
                    "strength_model": 1,
                    "strength_clip": 1,
                    "model": ["1", 0],
                    "clip": ["1", 1]
                  },
                  "class_type": "LoraLoader",
                  "_meta": { "title": "LoRA 로드" }
                }
                """.formatted(loraUrl)));

            // Node 48: Positive Prompt
            ObjectNode node48 = objectMapper.createObjectNode();
            ObjectNode node48Inputs = objectMapper.createObjectNode();
            node48Inputs.put("text", positivePrompt);
            node48Inputs.set("clip", objectMapper.readTree("[\"2\", 1]"));
            node48.set("inputs", node48Inputs);
            node48.put("class_type", "CLIPTextEncode");
            node48.set("_meta", objectMapper.readTree("{\"title\": \"CLIP 텍스트 인코딩 (프롬프트)\"}"));
            workflow.set("48", node48);

            // Node 47: Negative Prompt
            ObjectNode node47 = objectMapper.createObjectNode();
            ObjectNode node47Inputs = objectMapper.createObjectNode();
            node47Inputs.put("text", negativePrompt);
            node47Inputs.set("clip", objectMapper.readTree("[\"2\", 1]"));
            node47.set("inputs", node47Inputs);
            node47.put("class_type", "CLIPTextEncode");
            node47.set("_meta", objectMapper.readTree("{\"title\": \"CLIP 텍스트 인코딩 (프롬프트)\"}"));
            workflow.set("47", node47);

            // Node 49: Empty Latent (1024x1024)
            workflow.set("49", objectMapper.readTree("""
                {
                  "inputs": { "dimensions": "1024 x 1024 (1:1)", "invert": false, "batch_size": 1 },
                  "class_type": "EmptyLatentImage",
                  "_meta": { "title": "Empty Latent Image" }
                }
                """));

            // Node 50: KSampler (랜덤 시드)
            long seed = ThreadLocalRandom.current().nextLong(0, Long.MAX_VALUE);
            workflow.set("50", objectMapper.readTree("""
                {
                  "inputs": {
                    "seed": %d, "steps": 30, "cfg": 4,
                    "sampler_name": "euler", "scheduler": "normal", "denoise": 1,
                    "model": ["2", 0], "positive": ["48", 0],
                    "negative": ["47", 0], "latent_image": ["49", 0]
                  },
                  "class_type": "KSampler",
                  "_meta": { "title": "KSampler" }
                }
                """.formatted(seed)));

            // Node 51: VAE Decode
            workflow.set("51", objectMapper.readTree("""
                {
                  "inputs": { "samples": ["50", 0], "vae": ["1", 2] },
                  "class_type": "VAEDecode",
                  "_meta": { "title": "VAE 디코드" }
                }
                """));

            // Node 52: Save Image
            workflow.set("52", objectMapper.readTree("""
                {
                  "inputs": { "filename_prefix": "LucidChar", "images": ["51", 0] },
                  "class_type": "SaveImage",
                  "_meta": { "title": "이미지 저장" }
                }
                """));

            return workflow;
        } catch (Exception e) {
            throw new RuntimeException("Failed to build character workflow", e);
        }
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  장소 배경 워크플로우 빌더
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /**
     * 장소 배경용 ComfyUI 워크플로우 생성
     *
     * 노드 구조:
     *   1: CheckpointLoaderSimple (base model)
     *  67: LoraLoader (makoto shinkai LoRA, strength 0.6)
     *  48: CLIPTextEncode (positive prompt)  → clip from 67
     *  47: CLIPTextEncode (negative prompt)  → clip from 67
     *  49: EmptyLatentImagePresets (1344x768)
     *  50: KSampler                          → model from 67
     *  51: VAEDecode
     *  52: SaveImage
     */
    public JsonNode buildLocationWorkflow(String positivePrompt, String negativePrompt) {
        try {
            ObjectNode workflow = objectMapper.createObjectNode();

            // Node 1: Checkpoint Loader
            workflow.set("1", objectMapper.readTree("""
                {
                  "inputs": { "ckpt_name": "%s" },
                  "class_type": "CheckpointLoaderSimple",
                  "_meta": { "title": "체크포인트 로드" }
                }
                """.formatted(props.baseModelUrl())));

            // Node 67: Location LoRA Loader (strength 0.6)
            workflow.set("67", objectMapper.readTree("""
                {
                  "inputs": {
                    "lora_name": "%s",
                    "strength_model": 0.6,
                    "strength_clip": 1,
                    "model": ["1", 0],
                    "clip": ["1", 1]
                  },
                  "class_type": "LoraLoader",
                  "_meta": { "title": "LoRA 로드" }
                }
                """.formatted(props.locationLoraUrl())));

            // Node 48: Positive Prompt (from node 67)
            ObjectNode node48 = objectMapper.createObjectNode();
            ObjectNode node48Inputs = objectMapper.createObjectNode();
            node48Inputs.put("text", positivePrompt);
            node48Inputs.set("clip", objectMapper.readTree("[\"67\", 1]"));
            node48.set("inputs", node48Inputs);
            node48.put("class_type", "CLIPTextEncode");
            node48.set("_meta", objectMapper.readTree("{\"title\": \"CLIP 텍스트 인코딩 (프롬프트)\"}"));
            workflow.set("48", node48);

            // Node 47: Negative Prompt (from node 67)
            ObjectNode node47 = objectMapper.createObjectNode();
            ObjectNode node47Inputs = objectMapper.createObjectNode();
            node47Inputs.put("text", negativePrompt);
            node47Inputs.set("clip", objectMapper.readTree("[\"67\", 1]"));
            node47.set("inputs", node47Inputs);
            node47.put("class_type", "CLIPTextEncode");
            node47.set("_meta", objectMapper.readTree("{\"title\": \"CLIP 텍스트 인코딩 (프롬프트)\"}"));
            workflow.set("47", node47);

            // Node 49: Empty Latent (1344x768 — 와이드 배경)
            workflow.set("49", objectMapper.readTree("""
                {
                  "inputs": { "dimensions": "1344 x 768 (1.75:1)", "invert": false, "batch_size": 1 },
                  "class_type": "EmptyLatentImage",
                  "_meta": { "title": "Empty Latent Image" }
                }
                """));

            // Node 50: KSampler (model from node 67)
            long seed = ThreadLocalRandom.current().nextLong(0, Long.MAX_VALUE);
            workflow.set("50", objectMapper.readTree("""
                {
                  "inputs": {
                    "seed": %d, "steps": 30, "cfg": 4,
                    "sampler_name": "euler", "scheduler": "normal", "denoise": 1,
                    "model": ["67", 0], "positive": ["48", 0],
                    "negative": ["47", 0], "latent_image": ["49", 0]
                  },
                  "class_type": "KSampler",
                  "_meta": { "title": "KSampler" }
                }
                """.formatted(seed)));

            // Node 51 & 52: VAE Decode + Save
            workflow.set("51", objectMapper.readTree("""
                {
                  "inputs": { "samples": ["50", 0], "vae": ["1", 2] },
                  "class_type": "VAEDecode", "_meta": { "title": "VAE 디코드" }
                }
                """));
            workflow.set("52", objectMapper.readTree("""
                {
                  "inputs": { "filename_prefix": "LucidBG", "images": ["51", 0] },
                  "class_type": "SaveImage", "_meta": { "title": "이미지 저장" }
                }
                """));

            return workflow;
        } catch (Exception e) {
            throw new RuntimeException("Failed to build location workflow", e);
        }
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  응답 DTO
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    public record QueueResponse(String requestId, String statusUrl, String responseUrl) {}
    public record PollResult(boolean completed, String status, JsonNode payload) {}
}