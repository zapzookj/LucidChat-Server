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
 *   return-url: ${NICE_RETURN_URL:https://api.lucid-chat.com/api/v1/verify/callback}
 *   frontend-callback-url: ${NICE_FRONTEND_CALLBACK_URL:https://lucid-chat.com/verify/callback}
 *   api-url: ${NICE_API_URL:https://svc.niceapi.co.kr:22001}
 *
 * ⚠ [C-1.3/C-1.4 · docs/17_assets/defect_register.md] 현재 application.yml의 nice 블록은
 *   `YOUR_NICE_CLIENT_ID` 같은 **리터럴 플레이스홀더**다(`${ENV:default}` 형태 아님).
 *   @ConfigurationProperties + 환경변수는 relaxed binding으로 yml보다 우선하므로
 *   NICE_CLIENT_ID / NICE_CLIENT_SECRET / NICE_PRODUCT_ID / NICE_RETURN_URL /
 *   NICE_FRONTEND_CALLBACK_URL 만 주입하면 **코드 변경 없이 개통된다**.
 *   미주입 상태에서 조용히 401이 나지 않도록 NiceApiClient.getAccessToken 진입부에
 *   설정 누락 가드를 뒀다(플레이스홀더도 '미설정'으로 취급).
 *
 * [D-30 · 콜백 구조] NICE 팝업은 return-url(=백엔드 수신 엔드포인트)로 돌아오고,
 *   백엔드가 GET 쿼리/POST 폼 양쪽을 받아 frontend-callback-url로 302 시킨다.
 *   → NICE 콜백이 GET인지 POST인지에 대한 계약 답변을 기다릴 필요가 사라진다
 *     (docs/18 §1-E의 '반쪽만 만들어 두라' 지침을 이 방식으로 정정, docs/19 D-30).
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "nice")
public class NiceApiProperties {

    private String clientId;
    private String clientSecret;
    private String productId;
    /** NICE 팝업이 인증 후 돌아올 주소. **백엔드 수신 엔드포인트**를 가리켜야 한다(D-30). */
    private String returnUrl;

    /**
     * [D-30] 백엔드 콜백 수신 후 302로 보낼 SPA 라우트.
     * 기본값은 운영 도메인 — 로컬 개발은 NICE_FRONTEND_CALLBACK_URL 또는
     * application-local.yml의 nice.frontend-callback-url로 http://localhost:5173/verify/callback 지정.
     */
    private String frontendCallbackUrl = "https://lucid-chat.com/verify/callback";

    private String apiUrl = "https://svc.niceapi.co.kr:22001";
}