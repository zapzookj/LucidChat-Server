package com.spring.aichat.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * PortOne (구 아임포트) API 설정
 *
 * application.yml:
 * portone:
 *   api-key: ${PORTONE_API_KEY}
 *   api-secret: ${PORTONE_API_SECRET}
 *   api-url: https://api.iamport.kr
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "portone")
public class PortOneProperties {

    private String apiKey;
    private String apiSecret;
    private String apiUrl = "https://api.iamport.kr";
}