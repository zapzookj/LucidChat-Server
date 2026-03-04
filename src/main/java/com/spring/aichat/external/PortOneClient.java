package com.spring.aichat.external;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.spring.aichat.config.PortOneProperties;
import com.spring.aichat.exception.BusinessException;
import com.spring.aichat.exception.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

/**
 * PortOne (iamport) API Client
 *
 * [Phase 5 개선]
 * - @Qualifier("externalApiRestTemplate"): 3s connect / 5s read 타임아웃 적용
 * - ResourceAccessException 전용 catch: 타임아웃 시 명확한 로깅 + EXTERNAL_API_ERROR
 */
@Slf4j
@Component
public class PortOneClient {

    private final PortOneProperties props;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    public PortOneClient(
        PortOneProperties props,
        @Qualifier("externalApiRestTemplate") RestTemplate restTemplate,
        ObjectMapper objectMapper
    ) {
        this.props = props;
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
    }

    public String getAccessToken() {
        try {
            Map<String, String> body = Map.of(
                "imp_key", props.getApiKey(),
                "imp_secret", props.getApiSecret()
            );
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            ResponseEntity<JsonNode> response = restTemplate.exchange(
                props.getApiUrl() + "/users/getToken",
                HttpMethod.POST, new HttpEntity<>(body, headers), JsonNode.class);

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                String token = response.getBody().path("response").path("access_token").asText();
                if (token != null && !token.isEmpty()) {
                    log.info("[PortOne] Access Token obtained");
                    return token;
                }
            }
            throw new BusinessException(ErrorCode.PAYMENT_VERIFICATION_FAILED, "PortOne token failed");

        } catch (ResourceAccessException e) {
            log.error("[PortOne] Token request TIMEOUT (server may be down)", e);
            throw new BusinessException(ErrorCode.EXTERNAL_API_ERROR,
                "PortOne server not responding. Please try again later.", e);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("[PortOne] Token unexpected error", e);
            throw new BusinessException(ErrorCode.PAYMENT_VERIFICATION_FAILED, "PortOne comm failed", e);
        }
    }

    public JsonNode getPaymentInfo(String impUid) {
        try {
            String accessToken = getAccessToken();
            HttpHeaders headers = new HttpHeaders();
            headers.set("Authorization", "Bearer " + accessToken);
            ResponseEntity<JsonNode> response = restTemplate.exchange(
                props.getApiUrl() + "/payments/" + impUid,
                HttpMethod.GET, new HttpEntity<>(headers), JsonNode.class);

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                JsonNode data = response.getBody().path("response");
                if (!data.isMissingNode()) {
                    log.info("[PortOne] Payment info: impUid={}", impUid);
                    return data;
                }
            }
            throw new BusinessException(ErrorCode.PAYMENT_VERIFICATION_FAILED, "Payment not found: " + impUid);

        } catch (ResourceAccessException e) {
            log.error("[PortOne] Payment query TIMEOUT: impUid={}", impUid, e);
            throw new BusinessException(ErrorCode.EXTERNAL_API_ERROR,
                "PortOne server not responding. Please try again later.", e);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("[PortOne] Query error: impUid={}", impUid, e);
            throw new BusinessException(ErrorCode.PAYMENT_VERIFICATION_FAILED, "Query failed", e);
        }
    }

    public JsonNode cancelPayment(String impUid, int amount, String reason) {
        try {
            String accessToken = getAccessToken();
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("Authorization", "Bearer " + accessToken);
            Map<String, Object> body = Map.of("imp_uid", impUid, "amount", amount, "reason", reason);
            ResponseEntity<JsonNode> response = restTemplate.exchange(
                props.getApiUrl() + "/payments/cancel",
                HttpMethod.POST, new HttpEntity<>(body, headers), JsonNode.class);

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                log.info("[PortOne] Cancelled: impUid={}, amount={}", impUid, amount);
                return response.getBody().path("response");
            }
            throw new BusinessException(ErrorCode.PAYMENT_VERIFICATION_FAILED, "Cancel failed: " + impUid);

        } catch (ResourceAccessException e) {
            log.error("[PortOne] Cancel TIMEOUT: impUid={}", impUid, e);
            throw new BusinessException(ErrorCode.EXTERNAL_API_ERROR,
                "PortOne server not responding during cancel. Manual check needed.", e);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("[PortOne] Cancel error: impUid={}", impUid, e);
            throw new BusinessException(ErrorCode.PAYMENT_VERIFICATION_FAILED, "Cancel failed", e);
        }
    }
}