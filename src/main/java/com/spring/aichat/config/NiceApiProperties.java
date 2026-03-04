package com.spring.aichat.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * NICE 본인인증 API 설정
 *
 * application.yml 예시:
 * nice:
 *   client-id: ${NICE_CLIENT_ID}
 *   client-secret: ${NICE_CLIENT_SECRET}
 *   product-id: ${NICE_PRODUCT_ID}
 *   return-url: ${NICE_RETURN_URL:https://yourdomain.com/verify/callback}
 *   api-url: ${NICE_API_URL:https://svc.niceapi.co.kr:22001}
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "nice")
public class NiceApiProperties {

    private String clientId;
    private String clientSecret;
    private String productId;
    private String returnUrl;
    private String apiUrl = "https://svc.niceapi.co.kr:22001";
}