package com.spring.aichat.config;

import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;

/**
 * Phase 5: 외부 API 통신용 RestTemplate 설정
 *
 * [Timeout 방어 설계]
 * - Connection Timeout (3초): TCP 핸드셰이크 제한. 외부 서버가 아예 응답 불가 시 빠른 실패.
 * - Read Timeout (5초): 응답 수신 대기 제한. 서버가 연결은 됐으나 처리 지연 시 스레드 해방.
 *
 * [설정 근거]
 * - NICE/PortOne API의 정상 응답 시간: 보통 200ms~1s 이내
 * - 3초/5초면 정상 케이스는 충분히 커버하면서도 장애 시 스레드 풀 고갈 방지
 * - 타임아웃 발생 시 RestClientException -> BusinessException으로 변환되어
 *   GlobalExceptionHandler에서 502 응답 (EXTERNAL_API_ERROR)
 *
 * [스레드 풀 보호]
 * 타임아웃 없이 외부 서버 장애 발생 시:
 *   요청 A → NICE API (무한 대기) → 스레드 #1 점유
 *   요청 B → NICE API (무한 대기) → 스레드 #2 점유
 *   ...
 *   → Tomcat 스레드 풀(기본 200) 고갈 → 서비스 전체 다운
 *
 * 타임아웃 적용 시:
 *   요청 A → NICE API (5초 후 타임아웃) → 스레드 #1 해방 → 502 응답
 *   → 다른 API(채팅, 로비 등)는 정상 운영 유지
 */
@Configuration
public class ExternalApiConfig {

    @Bean
    public RestTemplate externalApiRestTemplate(RestTemplateBuilder builder) {
        return builder
            .connectTimeout(Duration.ofSeconds(3))
            .readTimeout(Duration.ofSeconds(5))
            .build();
    }
}