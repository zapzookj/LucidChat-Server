package com.spring.aichat.external;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.spring.aichat.config.NiceApiProperties;
import com.spring.aichat.exception.BusinessException;
import com.spring.aichat.exception.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * NICE 본인인증 API 클라이언트
 *
 * [Phase 5 개선]
 * - @Qualifier("externalApiRestTemplate"): 3s connect / 5s read 타임아웃 적용
 * - ResourceAccessException 전용 catch: 타임아웃 시 명확한 에러 메시지 + 빠른 실패
 */
@Slf4j
@Component
public class NiceApiClient {

    private final NiceApiProperties props;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    public NiceApiClient(
        NiceApiProperties props,
        @Qualifier("externalApiRestTemplate") RestTemplate restTemplate,
        ObjectMapper objectMapper
    ) {
        this.props = props;
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
    }

    public String getAccessToken() {
        try {
            String credentials = props.getClientId() + ":" + props.getClientSecret();
            String basicAuth = Base64.getEncoder().encodeToString(
                credentials.getBytes(StandardCharsets.UTF_8));

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
            headers.set("Authorization", "Basic " + basicAuth);

            HttpEntity<String> entity = new HttpEntity<>(
                "grant_type=client_credentials&scope=default", headers);

            ResponseEntity<JsonNode> response = restTemplate.exchange(
                props.getApiUrl() + "/digital/niceid/oauth/oauth/token",
                HttpMethod.POST, entity, JsonNode.class);

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                JsonNode body = response.getBody();
                String tokenType = body.path("dataBody").path("token_type").asText();
                String accessToken = body.path("dataBody").path("access_token").asText();
                log.info("[NICE] Access Token issued");
                return tokenType + " " + accessToken;
            }

            throw new BusinessException(ErrorCode.VERIFICATION_TOKEN_FAILED,
                "NICE Access Token issue failed");

        } catch (ResourceAccessException e) {
            log.error("[NICE] Access Token TIMEOUT (server may be down)", e);
            throw new BusinessException(ErrorCode.EXTERNAL_API_ERROR,
                "NICE server not responding. Please try again later.", e);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("[NICE] Access Token unexpected error", e);
            throw new BusinessException(ErrorCode.VERIFICATION_TOKEN_FAILED,
                "NICE communication failed.", e);
        }
    }

    public CryptoTokenResult requestCryptoToken(String accessToken, String requestNo) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("Authorization", accessToken);
            headers.set("ProductID", props.getProductId());

            Map<String, Object> dataBody = new HashMap<>();
            dataBody.put("req_dtim", formatNow());
            dataBody.put("req_no", requestNo);
            dataBody.put("enc_mode", "1");

            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("dataHeader", Map.of("CNTY_CD", "ko"));
            requestBody.put("dataBody", dataBody);

            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestBody, headers);

            ResponseEntity<JsonNode> response = restTemplate.exchange(
                props.getApiUrl() + "/digital/niceid/api/v1.0/common/crypto/token",
                HttpMethod.POST, entity, JsonNode.class);

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                JsonNode body = response.getBody().path("dataBody");
                String resultCd = body.path("rsp_cd").asText();

                if (!"P000".equals(resultCd)) {
                    throw new BusinessException(ErrorCode.VERIFICATION_TOKEN_FAILED,
                        "NICE crypto token failed (rsp_cd=" + resultCd + ")");
                }

                String tokenVersionId = body.path("token_version_id").asText();
                String siteCode = body.path("site_code").asText();
                String tokenVal = body.path("token_val").asText();

                String key = tokenVal.substring(0, 16);
                String iv = tokenVal.substring(tokenVal.length() - 16);
                String hmacKey = tokenVal.substring(0, 32);

                String plainText = buildPlainText(requestNo, siteCode);
                String encData = encryptAES128CBC(plainText, key, iv);
                String integrityValue = hmacSHA256(hmacKey, encData);

                log.info("[NICE] Crypto token issued: tokenVersionId={}", tokenVersionId);
                return new CryptoTokenResult(tokenVersionId, encData, integrityValue, key, iv);
            }

            throw new BusinessException(ErrorCode.VERIFICATION_TOKEN_FAILED,
                "NICE crypto token invalid response");

        } catch (ResourceAccessException e) {
            log.error("[NICE] Crypto token TIMEOUT", e);
            throw new BusinessException(ErrorCode.EXTERNAL_API_ERROR,
                "NICE server not responding. Please try again later.", e);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("[NICE] Crypto token unexpected error", e);
            throw new BusinessException(ErrorCode.VERIFICATION_TOKEN_FAILED,
                "NICE crypto token failed.", e);
        }
    }

    public JsonNode decryptResult(String encData, String key, String iv) {
        try {
            String decrypted = decryptAES128CBC(encData, key, iv);
            return objectMapper.readTree(decrypted);
        } catch (Exception e) {
            log.error("[NICE] Decryption failed", e);
            throw new BusinessException(ErrorCode.VERIFICATION_DECRYPT_FAILED,
                "Decryption failed.", e);
        }
    }

    // ── internal utilities ──

    private String buildPlainText(String requestNo, String siteCode) {
        Map<String, String> data = new LinkedHashMap<>();
        data.put("requestno", requestNo);
        data.put("returnurl", props.getReturnUrl());
        data.put("sitecode", siteCode);
        data.put("authtype", "M");
        data.put("methodtype", "get");
        data.put("popupyn", "Y");

        StringBuilder sb = new StringBuilder();
        data.forEach((k, v) -> {
            if (!sb.isEmpty()) sb.append("&");
            sb.append(k).append("=").append(v);
        });
        return sb.toString();
    }

    private String encryptAES128CBC(String plainText, String key, String iv) throws Exception {
        SecretKeySpec keySpec = new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "AES");
        IvParameterSpec ivSpec = new IvParameterSpec(iv.getBytes(StandardCharsets.UTF_8));
        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, ivSpec);
        byte[] encrypted = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(encrypted);
    }

    private String decryptAES128CBC(String encData, String key, String iv) throws Exception {
        SecretKeySpec keySpec = new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "AES");
        IvParameterSpec ivSpec = new IvParameterSpec(iv.getBytes(StandardCharsets.UTF_8));
        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(Cipher.DECRYPT_MODE, keySpec, ivSpec);
        byte[] decoded = Base64.getDecoder().decode(encData);
        byte[] decrypted = cipher.doFinal(decoded);
        return new String(decrypted, StandardCharsets.UTF_8);
    }

    private String hmacSHA256(String key, String data) throws Exception {
        javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] hash = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(hash);
    }

    private String formatNow() {
        return LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
    }

    public record CryptoTokenResult(
        String tokenVersionId, String encData, String integrityValue,
        String key, String iv
    ) {}
}