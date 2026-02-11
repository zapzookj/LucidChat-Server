//package com.spring.aichat.config;
//
//import org.springframework.beans.factory.annotation.Value;
//import org.springframework.context.annotation.Bean;
//import org.springframework.context.annotation.Configuration;
//import org.springframework.http.HttpHeaders;
//import org.springframework.http.MediaType;
//import org.springframework.web.reactive.function.client.WebClient;
//
//@Configuration
//public class WebClientConfig {
//
//    @Value("${openai.api-key}")
//    private String openAiApiKey;
//
//    @Value("${openai.base-url}")
//    private String openAiBaseUrl;
//
//    @Bean
//    public WebClient openRouterWebClient() {
//        return WebClient.builder()
//            .baseUrl(openAiBaseUrl)
//            .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + openAiApiKey)
//            .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
//            .build();
//    }
//}
