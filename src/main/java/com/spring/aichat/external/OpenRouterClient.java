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
//    private final WebClient openRouterWebClient;

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

    /**
     * [Phase 3] SSE 스트리밍 요청
     * stream=true 옵션으로 요청하고, 응답을 Flux<String>으로 실시간 반환
     */
//    public Flux<String> streamChatCompletion(OpenAiChatRequest request) {
//        // stream 필드를 true로 설정한 새 요청 객체 생성
//        OpenAiChatRequest streamRequest = new OpenAiChatRequest(
//            request.model(),
//            request.messages(),
//            request.temperature(),
//            true
//        );
//
//        return openRouterWebClient.post()
//            .uri("/chat/completions")
//            .bodyValue(streamRequest)
//            .accept(MediaType.TEXT_EVENT_STREAM) // 중요: SSE 타입 수신
//            .retrieve()
//            .bodyToFlux(String.class); // 들어오는 청크를 문자열 흐름으로 반환
//    }
}
