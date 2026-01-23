package com.spring.aichat.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.web.client.RestClient;

/**
 * OpenRouter API 통신용 RestClient 설정
 */
@Configuration
@EnableConfigurationProperties(OpenAiProperties.class)
public class RestClientConfig {

    @Bean
    public RestClient openRouterRestClient(OpenAiProperties props) {
        // OpenRouter는 Authorization: Bearer <KEY> 사용 :contentReference[oaicite:6]{index=6}
        return RestClient.builder()
            .baseUrl(props.baseUrl())
            .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + props.apiKey())
            // 아래 헤더들은 Optional (앱 식별용) :contentReference[oaicite:7]{index=7}
            .defaultHeader("HTTP-Referer", props.appReferer())
            .defaultHeader("X-Title", props.appTitle())
            .defaultHeader(HttpHeaders.CONTENT_TYPE, "application/json")
            .build();
    }
}
