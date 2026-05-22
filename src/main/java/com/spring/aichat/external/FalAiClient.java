package com.spring.aichat.external;

import ai.fal.client.AsyncFalClient;
import ai.fal.client.ClientConfig;
import ai.fal.client.CredentialsResolver;
import ai.fal.client.Output;
import ai.fal.client.SubscribeOptions;
import com.google.gson.JsonObject;
import com.spring.aichat.config.FalAiProperties;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * [Phase 6-Illust v3] Fal.ai 클라이언트 — 공식 Java SDK({@code ai.fal.client:fal-client-async}) wrapper.
 *
 * <p>배경 일러스트 트랙 ({@code fal-ai/flux-2/stream}).
 *
 * <p><b>SDK 채택 근거</b>: Fal.ai는 공식 Java/Kotlin SDK(fal-java)를 제공한다.
 * {@code subscribe()}는 큐 제출 → 폴링 → 결과 조회 → 자동 재시도를 모두 내부 처리하므로,
 * 수동 REST 호출/폴링 루프/webhook 콜백 인프라가 전부 불필요해진다.
 *
 * <p><b>API 매핑</b> (fal-java 0.7.1):
 * <ul>
 *   <li>클라이언트: {@code AsyncFalClient.withConfig(ClientConfig.withCredentials(...))}</li>
 *   <li>호출: {@code subscribe(endpointId, SubscribeOptions)} → {@code CompletableFuture<Output<JsonObject>>}</li>
 *   <li>결과: {@code output.getData()} → {@code JsonObject { images:[{url}], ... }}</li>
 * </ul>
 *
 * <p><b>입력 스키마</b> (flux-2/stream): prompt / image_size / num_inference_steps /
 * guidance_scale(기본 2.5) / acceleration(none|regular|high) / enable_safety_checker /
 * output_format. <b>negative_prompt 없음</b> (Flux 2 positive-only).
 *
 * <p>build.gradle: {@code implementation 'ai.fal.client:fal-client-async:0.7.1'}
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class FalAiClient {

    private final FalAiProperties props;

    private AsyncFalClient fal;

    @PostConstruct
    void init() {
        // yml로 주입한 키를 ClientConfig로 명시 (FAL_KEY 환경변수에 의존하지 않음)
        ClientConfig config = ClientConfig.withCredentials(
            CredentialsResolver.fromApiKey(props.apiKey())
        );
        this.fal = AsyncFalClient.withConfig(config);
        log.info("[FAL] AsyncFalClient initialized (SDK fal-client-async, model={})",
            props.effectiveModel());
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  생성 (subscribe — 큐+폴링+결과 SDK 자동 처리)
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /**
     * 배경 이미지 생성. SDK subscribe가 완료까지 내부 폴링 후 결과 반환.
     *
     * @return 생성된 이미지 URL의 CompletableFuture (실패 시 예외 완성)
     */
    public CompletableFuture<GenerationResult> generate(GenerationRequest req) {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("prompt", req.prompt());
        input.put("image_size", req.imageSize() != null ? req.imageSize() : "landscape_16_9");
        input.put("num_inference_steps", req.numInferenceSteps() != null ? req.numInferenceSteps() : 28);
        input.put("guidance_scale", req.guidanceScale() != null ? req.guidanceScale() : 2.5);
        input.put("acceleration", req.acceleration() != null ? req.acceleration() : "regular");
        input.put("enable_safety_checker", false);
        input.put("output_format", "png");
        // negative_prompt 없음 — Flux 2 positive-only (금지사항은 prompt 후미 자연어 통합)

        String endpoint = props.effectiveModel();
        log.info("[FAL] subscribe: model={}, promptLen={}", endpoint, req.prompt().length());

        CompletableFuture<Output<JsonObject>> future = fal.subscribe(
            endpoint,
            SubscribeOptions.<JsonObject>builder()
                .input(input)
                .resultType(JsonObject.class)
                .onQueueUpdate(update -> {
                    if (update != null && update.getStatus() != null) {
                        log.debug("[FAL] queue update: {}", update.getStatus());
                    }
                })
                .build()
        );

        return future.thenApply(output -> {
            String requestId = output.getRequestId();
            String imageUrl = extractImageUrl(output.getData());
            if (imageUrl == null) {
                log.error("[FAL] No image url in result: requestId={}", requestId);
                throw new IllegalStateException("Fal.ai returned no image url (requestId=" + requestId + ")");
            }
            log.info("[FAL] ✅ Completed: requestId={}, url={}", requestId, imageUrl);
            return new GenerationResult(requestId, imageUrl);
        });
    }

    /**
     * 동기 편의 메서드 — 호출 스레드에서 완료까지 블로킹.
     * (BackgroundGenerationService는 @Async 스레드풀 위에서 실행되므로 블로킹 허용.)
     *
     * @return 이미지 URL, 실패 시 null
     */
    public GenerationResult generateBlocking(GenerationRequest req) {
        try {
            return generate(req).join();
        } catch (Exception e) {
            log.error("[FAL] generateBlocking failed: {}", e.getMessage(), e);
            return null;
        }
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  결과 파싱
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /**
     * flux-2/stream 결과 JsonObject에서 첫 이미지 URL 추출.
     * 구조: {@code { "images": [ { "url": "...", "width":..., "height":... } ], "seed":... }}
     */
    public String extractImageUrl(JsonObject data) {
        if (data == null) return null;
        try {
            if (data.has("images") && data.get("images").isJsonArray()) {
                var images = data.getAsJsonArray("images");
                if (!images.isEmpty()) {
                    var first = images.get(0);
                    if (first.isJsonObject()) {
                        var obj = first.getAsJsonObject();
                        if (obj.has("url") && !obj.get("url").isJsonNull()) {
                            String url = obj.get("url").getAsString();
                            if (url != null && url.startsWith("http")) return url;
                        }
                    } else if (first.isJsonPrimitive()) {
                        String url = first.getAsString();
                        if (url.startsWith("http")) return url;
                    }
                }
            }
        } catch (Exception e) {
            log.warn("[FAL] extractImageUrl parse error: {}", e.getMessage());
        }
        return null;
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  DTO
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /**
     * 생성 요청. negative_prompt 없음 (Flux 2 positive-only).
     * acceleration: none | regular | high (기본 regular).
     */
    public record GenerationRequest(
        String prompt,
        String imageSize,
        Integer numInferenceSteps,
        Double guidanceScale,
        String acceleration
    ) {
        /** 배경용 표준 (16:9, 28 step, guidance 2.5, regular). */
        public static GenerationRequest background(String prompt) {
            return new GenerationRequest(prompt, "landscape_16_9", 28, 2.5, "regular");
        }
    }

    public record GenerationResult(String requestId, String imageUrl) {}
}