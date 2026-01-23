package com.spring.aichat.external;

import com.spring.aichat.dto.openai.OpenAiChatRequest;
import com.spring.aichat.dto.openai.OpenAiChatResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * OpenRouter(OpenAI 호환) API 호출 전용 Client
 */
@Component
@RequiredArgsConstructor
public class OpenRouterClient {

    private final RestClient openRouterRestClient;

    public String chatCompletion(OpenAiChatRequest request) {
        try {
            OpenAiChatResponse response = openRouterRestClient.post()
                .uri("/chat/completions")
                .body(request)
                .retrieve()
                .body(OpenAiChatResponse.class);

            if (response == null) {
//                throw new ExternalApiException("OpenRouter 응답이 null 입니다.");
            }
            return response.firstContentOrThrow();

        } catch (RestClientResponseException e) {
            throw new RuntimeException("OpenRouter 호출 실패: " + e.getResponseBodyAsString(), e);
        }
    }
}
