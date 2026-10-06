package com.spring.aichat.external;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.spring.aichat.config.FalAiProperties;
import org.springframework.stereotype.Component;
import lombok.RequiredArgsConstructor;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

/** Observed fal queue contract (2026-10-06). Persist the receipt before polling; never retry an ambiguous POST. */
@Component
@RequiredArgsConstructor
public class FalGptEmotionEditClient {
    public static final String MODEL = "openai/gpt-image-2.5/sunburst/edit";
    private static final String QUEUE = "https://queue.fal.run/";
    private final FalAiProperties properties;
    private final ObjectMapper mapper;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();

    public record Receipt(String requestId, String statusUrl, String responseUrl) {}
    public record Result(String imageUrl) {}
    public static Map<String, Object> input(String prompt, String sourceUrl, String quality) {
        if (!Set.of("low", "high").contains(quality)) throw new IllegalArgumentException("Unsupported emotion quality");
        return Map.of("prompt", prompt, "image_urls", List.of(sourceUrl), "quality", quality,
            "image_size", Map.of("width", 1024, "height", 1024), "num_images", 1,
            "background", "opaque", "output_format", "png");
    }
    public Receipt submit(String prompt, String sourceUrl, String quality) throws Exception {
        JsonNode n = request(URI.create(QUEUE + MODEL), "POST", mapper.writeValueAsString(input(prompt, sourceUrl, quality)));
        Receipt r = new Receipt(n.path("request_id").asText(), n.path("status_url").asText(), n.path("response_url").asText());
        if (r.requestId().isBlank()) throw new IllegalStateException("Missing fal receipt");
        checkedQueueUrl(r.statusUrl()); checkedQueueUrl(r.responseUrl());
        return r;
    }
    public CompletableFuture<Result> await(Receipt receipt) {
        CompletableFuture<Result> result = new CompletableFuture<>();
        poll(receipt, System.nanoTime() + Duration.ofMinutes(15).toNanos(), result);
        return result;
    }
    private void poll(Receipt receipt, long deadline, CompletableFuture<Result> result) {
        if (result.isDone()) return;
        if (System.nanoTime() >= deadline) { result.completeExceptionally(new TimeoutException("Retain fal receipt for recovery")); return; }
        requestAsync(checkedQueueUrl(receipt.statusUrl())).thenCompose(status -> {
            if ("COMPLETED".equals(status.path("status").asText())) return requestAsync(checkedQueueUrl(receipt.responseUrl()));
            if ("FAILED".equals(status.path("status").asText())) return CompletableFuture.<JsonNode>failedFuture(new ProviderFailure("fal generation failed"));
            return CompletableFuture.<JsonNode>completedFuture(null);
        }).whenComplete((data, error) -> {
            Throwable cause = error;
            while (cause != null && cause.getCause() != null) cause = cause.getCause();
            if (cause instanceof ProviderFailure) { result.completeExceptionally(cause); return; }
            if (error == null && data != null) {
                try {
                    JsonNode images = data.path("images");
                    if (!images.isArray() || images.size() != 1) throw new ProviderFailure("Invalid image count");
                    String url = images.get(0).path("url").asText();
                    URI parsed = URI.create(url);
                    if (!"https".equals(parsed.getScheme()) || parsed.getHost() == null) throw new ProviderFailure("Invalid image URL");
                    result.complete(new Result(url));
                } catch (Exception e) { result.completeExceptionally(new ProviderFailure("Invalid image response")); }
                return;
            }
            // Nonblocking polling: many character jobs do not occupy the shared worker pool while queued.
            CompletableFuture.delayedExecutor(error == null ? 2500 : 5000, TimeUnit.MILLISECONDS)
                .execute(() -> poll(receipt, deadline, result));
        });
    }
    private CompletableFuture<JsonNode> requestAsync(URI uri) {
        HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(45))
            .header("Authorization", "Key " + properties.apiKey()).GET().build();
        return http.sendAsync(request, HttpResponse.BodyHandlers.ofString()).thenApply(response -> {
            if (response.statusCode() == 422 || response.statusCode() == 404) throw new ProviderFailure("fal result rejected or unavailable");
            if (response.statusCode() < 200 || response.statusCode() >= 300) throw new IllegalStateException("fal HTTP " + response.statusCode());
            try { return mapper.readTree(response.body()); }
            catch (Exception e) { throw new IllegalStateException("Invalid fal JSON"); }
        });
    }
    private JsonNode request(URI uri, String method, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(45))
            .header("Authorization", "Key " + properties.apiKey());
        if (body == null) b.GET();
        else b.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body));
        HttpResponse<String> response = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            if ("GET".equals(method) && response.statusCode() == 422) throw new ProviderFailure("fal rejected generation");
            throw new IllegalStateException("fal HTTP " + response.statusCode());
        }
        return mapper.readTree(response.body());
    }
    static URI checkedQueueUrl(String value) {
        URI uri = URI.create(value);
        if (!"https".equals(uri.getScheme()) || !"queue.fal.run".equals(uri.getHost())
            || uri.getUserInfo() != null || uri.getPort() != -1)
            throw new IllegalArgumentException("Invalid fal queue URL");
        return uri;
    }
    public static final class ProviderFailure extends RuntimeException {
        public ProviderFailure(String message) { super(message); }
    }
}
