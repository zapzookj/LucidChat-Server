package com.spring.aichat.external;

import ai.fal.client.AsyncFalClient;
import ai.fal.client.ClientConfig;
import ai.fal.client.CredentialsResolver;
import ai.fal.client.Output;
import ai.fal.client.SubscribeOptions;
import com.google.gson.JsonObject;
import com.spring.aichat.config.FalAiProperties;
import com.spring.aichat.config.UgcPipelineProperties;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * [UGC v1] fal.ai Qwen 이미지 편집 클라이언트 — {@code fal-ai/qwen-image-edit-2511}.
 *
 * <p>UGC 파이프라인의 자세 변환(2패스)·감정 파생(스타 토폴로지)에 사용.
 * {@link FalAiClient}(배경 트랙, flux-2)와 동일한 SDK subscribe 패턴 — 큐 제출·폴링·결과를
 * SDK가 내부 처리하므로 웹훅 인프라 불필요. API 키는 기존 {@code fal.api-key} 재사용.
 *
 * <p><b>API 스키마</b> (2026-07-17 공식 문서 확정):
 * <ul>
 *   <li>입력: {@code prompt}(필수) · <b>{@code image_urls}: 배열</b>(단수 image_url 아님 — 주의) ·
 *       {@code negative_prompt} 지원 · {@code num_inference_steps}(기본 28) ·
 *       {@code guidance_scale}(기본 4.5) · {@code seed}(동일 seed+prompt 재현성 보장) ·
 *       {@code image_size} <b>생략 시 입력 해상도 유지</b>(파생에 필요한 동작이므로 항상 생략)</li>
 *   <li>출력: {@code images[{url,width,height}]} + {@code seed}(실사용 값 반환)</li>
 *   <li>과금: $0.03/MP · 계정 동시성 기본 2(초과분은 거절 없이 큐 대기) — 출시 전 한도 상향 필요</li>
 * </ul>
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class FalQwenEditClient implements PoseEditClient {

    private final FalAiProperties falProps;
    private final UgcPipelineProperties ugcProps;

    private AsyncFalClient fal;

    @PostConstruct
    void init() {
        ClientConfig config = ClientConfig.withCredentials(
            CredentialsResolver.fromApiKey(falProps.apiKey())
        );
        this.fal = AsyncFalClient.withConfig(config);
        log.info("[UGC-QWEN] AsyncFalClient initialized (model={})", ugcProps.qwen().effectiveModel());
    }

    @Override
    public CompletableFuture<EditResult> edit(EditRequest req) {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("prompt", req.prompt());
        input.put("image_urls", List.of(req.imageUrl())); // 배열 계약
        // [2026-07-21 튜닝 노브] use-negative-prompt=false면 네거티브 전면 생략 —
        // 플레이그라운드 실측상 빈 쪽 퀄리티가 나은 관찰 (identity 가드는 positive Keep-지시 담당)
        if (ugcProps.qwen().useNegative()
            && req.negativePrompt() != null && !req.negativePrompt().isBlank()) {
            input.put("negative_prompt", req.negativePrompt());
        }
        input.put("num_inference_steps", ugcProps.qwen().steps());
        input.put("guidance_scale", ugcProps.qwen().guidance());
        if (req.seed() != null) {
            input.put("seed", req.seed());
        }
        input.put("num_images", 1);
        input.put("enable_safety_checker", false); // 오탐 블러 방지 — 콘텐츠 통제는 Stage 0 게이트가 담당
        input.put("output_format", "png");
        input.put("acceleration", "regular");
        // image_size 의도적 생략 — 입력 해상도 유지(공식 문서 명시)

        String endpoint = ugcProps.qwen().effectiveModel();
        log.info("[UGC-QWEN] subscribe: model={}, seed={}, promptLen={}", endpoint, req.seed(), req.prompt().length());

        CompletableFuture<Output<JsonObject>> future = fal.subscribe(
            endpoint,
            SubscribeOptions.<JsonObject>builder()
                .input(input)
                .resultType(JsonObject.class)
                .onQueueUpdate(update -> {
                    if (update != null && update.getStatus() != null) {
                        log.debug("[UGC-QWEN] queue update: {}", update.getStatus());
                    }
                })
                .build()
        );

        return future.thenApply(output -> {
            String requestId = output.getRequestId();
            JsonObject data = output.getData();
            String imageUrl = extractImageUrl(data);
            if (imageUrl == null) {
                log.error("[UGC-QWEN] No image url in result: requestId={}", requestId);
                throw new IllegalStateException("Qwen edit returned no image url (requestId=" + requestId + ")");
            }
            Long usedSeed = extractSeed(data);
            log.info("[UGC-QWEN] ✅ Completed: requestId={}, seed={}", requestId, usedSeed);
            return new EditResult(requestId, imageUrl, usedSeed);
        });
    }

    /** 결과 JsonObject에서 첫 이미지 URL 추출. 구조: {@code {images:[{url,...}], seed:...}} */
    private String extractImageUrl(JsonObject data) {
        if (data == null) return null;
        try {
            if (data.has("images") && data.get("images").isJsonArray()) {
                var images = data.getAsJsonArray("images");
                if (!images.isEmpty() && images.get(0).isJsonObject()) {
                    var obj = images.get(0).getAsJsonObject();
                    if (obj.has("url") && !obj.get("url").isJsonNull()) {
                        String url = obj.get("url").getAsString();
                        if (url != null && url.startsWith("http")) return url;
                    }
                }
            }
        } catch (Exception e) {
            log.warn("[UGC-QWEN] extractImageUrl parse error: {}", e.getMessage());
        }
        return null;
    }

    private Long extractSeed(JsonObject data) {
        try {
            if (data != null && data.has("seed") && !data.get("seed").isJsonNull()) {
                return data.get("seed").getAsLong();
            }
        } catch (Exception e) {
            log.debug("[UGC-QWEN] seed parse skip: {}", e.getMessage());
        }
        return null;
    }
}
