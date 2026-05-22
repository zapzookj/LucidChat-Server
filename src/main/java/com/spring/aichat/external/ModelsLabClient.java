package com.spring.aichat.external;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.spring.aichat.config.ModelsLabProperties;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * [Phase 6-Illust] ModelsLab API 클라이언트 — 캐릭터 일러스트 트랙 전용.
 *
 * <p>응답 모델:
 * <ul>
 *   <li>동기 성공: {@code { "status": "success", "id": "...", "output": ["url"] } }</li>
 *   <li>큐 진입:   {@code { "status": "processing", "id": "...", "fetch_result": "...", "eta": 8.0 } }</li>
 *   <li>실패:     {@code { "status": "error", "message": "..." } }</li>
 * </ul>
 *
 * <p>인증: body의 {@code "key"} 필드에 API key 포함 (ModelsLab 표준).<br>
 * 다중 LoRA: {@code lora_model}/{@code lora_strength}를 콤마 구분 문자열로 전달.<br>
 * NSFW: {@code safety_checker: "no"} 클라이언트 옵트인.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class ModelsLabClient {

    private final ModelsLabProperties props;
    private final ObjectMapper objectMapper;

    private RestClient restClient;

    @PostConstruct
    void init() {
        SimpleClientHttpRequestFactory rf = new SimpleClientHttpRequestFactory();
        rf.setConnectTimeout(10_000);
        rf.setReadTimeout(60_000);
        this.restClient = RestClient.builder().requestFactory(rf).build();
        log.info("[MODELSLAB] RestClient initialized");
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  생성 제출
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    public SubmitResult submit(GenerationRequest req) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("key", props.apiKey());
        body.put("model_id", req.modelId() != null ? req.modelId() : props.defaultModelId());
        body.put("prompt", req.positivePrompt());
        if (req.negativePrompt() != null && !req.negativePrompt().isBlank()) {
            body.put("negative_prompt", req.negativePrompt());
        }

        body.put("width", String.valueOf(props.effectiveWidth()));
        body.put("height", String.valueOf(props.effectiveHeight()));
        body.put("samples", "1");
        body.put("num_inference_steps", String.valueOf(props.effectiveSteps()));
        body.put("guidance_scale", props.effectiveGuidance());
        body.put("scheduler", props.effectiveSampler());

        // NSFW 통제 — 클라이언트 옵트인 (우리는 off)
        body.put("safety_checker", "no");
        body.put("enhance_prompt", "no");

        body.put("seed", ThreadLocalRandom.current().nextLong(0, Integer.MAX_VALUE));

        // 다중 LoRA 콤마 구분
        if (req.loras() != null && !req.loras().isEmpty()) {
            StringBuilder ids = new StringBuilder();
            StringBuilder strengths = new StringBuilder();
            for (LoraSlot s : req.loras()) {
                if (s == null || s.id() == null || s.id().isBlank()) continue;
                if (ids.length() > 0) { ids.append(","); strengths.append(","); }
                ids.append(s.id());
                strengths.append(s.weight() != null ? s.weight() : 1.0);
            }
            if (ids.length() > 0) {
                body.put("lora_model", ids.toString());
                body.put("lora_strength", strengths.toString());
            }
        }

        // Webhook (있으면) — 비동기 콜백 등록
        if (props.webhookBaseUrl() != null && !props.webhookBaseUrl().isBlank()) {
            body.put("webhook", props.webhookUrl());
            if (req.trackId() != null) body.put("track_id", req.trackId());
        }

        log.info("[MODELSLAB] Submitting: modelId={}, promptLen={}, loras={}",
            body.path("model_id").asText(), req.positivePrompt().length(),
            req.loras() != null ? req.loras().size() : 0);

        try {
            String responseStr = restClient.post()
                .uri(props.text2imgUrl())
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(String.class);

            JsonNode resp = objectMapper.readTree(responseStr);
            return parseSubmitResponse(resp);

        } catch (Exception e) {
            log.error("[MODELSLAB] Submit failed", e);
            throw new RuntimeException("ModelsLab submit failed: " + e.getMessage(), e);
        }
    }

    private SubmitResult parseSubmitResponse(JsonNode resp) {
        String status = resp.path("status").asText("error");
        String id = resp.path("id").asText(null);

        if ("success".equalsIgnoreCase(status)) {
            String url = extractFirstOutputUrl(resp);
            log.info("[MODELSLAB] ✅ Sync success: id={}, url={}", id, url);
            return SubmitResult.completed(id, url);
        }

        if ("processing".equalsIgnoreCase(status)) {
            String fetchUrl = resp.path("fetch_result").asText(null);
            if (fetchUrl == null || fetchUrl.isBlank()) {
                fetchUrl = props.fetchUrl(id);
            }
            double eta = resp.path("eta").asDouble(0);
            log.info("[MODELSLAB] Queued: id={}, eta={}s", id, eta);
            return SubmitResult.queued(id, fetchUrl);
        }

        String message = resp.path("message").asText("Unknown error");
        log.error("[MODELSLAB] ❌ Error: status={}, msg={}", status, message);
        throw new RuntimeException("ModelsLab error: " + message);
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  큐 폴링 / 결과 조회
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /**
     * 큐 폴링 — fetch_result URL 또는 fetch/{id}에 POST(key 포함).
     */
    public PollResult fetch(String fetchUrl, String generationId) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("key", props.apiKey());

        String effectiveUrl = (fetchUrl != null && !fetchUrl.isBlank())
            ? fetchUrl
            : props.fetchUrl(generationId);

        try {
            String responseStr = restClient.post()
                .uri(effectiveUrl)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(String.class);

            JsonNode resp = objectMapper.readTree(responseStr);
            String status = resp.path("status").asText("processing");

            if ("success".equalsIgnoreCase(status)) {
                String url = extractFirstOutputUrl(resp);
                return new PollResult(true, "COMPLETED", url, resp);
            }
            if ("processing".equalsIgnoreCase(status)) {
                return new PollResult(false, "PROCESSING", null, resp);
            }
            if ("error".equalsIgnoreCase(status) || "failed".equalsIgnoreCase(status)) {
                return new PollResult(false, "FAILED", null, resp);
            }
            return new PollResult(false, status.toUpperCase(), null, resp);

        } catch (Exception e) {
            log.warn("[MODELSLAB] Fetch failed: {} — {}", e.getClass().getSimpleName(), e.getMessage());
            return new PollResult(false, "ERROR", null, null);
        }
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  공통 추출
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /**
     * 응답 페이로드에서 첫 번째 이미지 URL 추출.
     * output 배열은 string 또는 {url} object 형태일 수 있음.
     */
    public String extractFirstOutputUrl(JsonNode resp) {
        JsonNode output = resp.path("output");
        if (output.isArray() && !output.isEmpty()) {
            JsonNode first = output.get(0);
            if (first.isTextual()) {
                String url = first.asText();
                return (url != null && url.startsWith("http")) ? url : null;
            }
            if (first.isObject()) {
                String url = first.path("url").asText(null);
                if (url == null) url = first.path("image_url").asText(null);
                if (url != null && url.startsWith("http")) return url;
            }
        }
        return null;
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  DTO
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /** 다중 LoRA 슬롯 (id + weight). */
    public record LoraSlot(String id, Double weight) {}

    /** 생성 요청. modelId가 null이면 props.defaultModelId 사용. */
    public record GenerationRequest(
        String modelId,
        String positivePrompt,
        String negativePrompt,
        List<LoraSlot> loras,
        String trackId
    ) {}

    /** 제출 결과 — syncCompleted=true면 imageUrl 즉시 사용 가능, false면 fetchUrl로 폴링. */
    public record SubmitResult(
        boolean syncCompleted,
        String generationId,
        String fetchUrl,
        String imageUrl
    ) {
        public static SubmitResult completed(String id, String imageUrl) {
            return new SubmitResult(true, id, null, imageUrl);
        }
        public static SubmitResult queued(String id, String fetchUrl) {
            return new SubmitResult(false, id, fetchUrl, null);
        }
    }

    public record PollResult(boolean completed, String status, String imageUrl, JsonNode payload) {}
}