package com.spring.aichat.external;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
@Slf4j
public class EmbeddingClient {

    private final RestClient openRouterRestClient;

    @Value("${spring.ai.openai.embedding-model}")
    private String embeddingModel;

    public List<Double> embed(String text) {
        try {
            Map<String, Object> request = Map.of(
                "model", embeddingModel,
                "input", text
            );

            EmbeddingResponse response = openRouterRestClient.post()
                .uri("/embeddings") // OpenRouter Embedding Endpoint
                .body(request)
                .retrieve()
                .body(EmbeddingResponse.class);

            if (response == null || response.data() == null || response.data().isEmpty()) {
                throw new RuntimeException("Embedding response is empty");
            }

            return response.data().get(0).embedding();

        } catch (Exception e) {
            log.error("Embedding Failed for text: {}", text.substring(0, Math.min(text.length(), 20)), e);
            throw new RuntimeException("Embedding API Call Failed", e);
        }
    }

    // Response DTO
    record EmbeddingResponse(List<EmbeddingData> data) {}
    record EmbeddingData(
        @JsonProperty("embedding") List<Double> embedding,
        @JsonProperty("index") int index
    ) {}
}
