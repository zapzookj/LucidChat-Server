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

    /**
     * [C-1.3 · docs/17_assets/defect_register.md 수정안 ③ · D-30]
     * 자격증명 미주입 판정. application.yml의 nice 블록은 `YOUR_NICE_CLIENT_ID` 같은
     * **리터럴 플레이스홀더**라, 환경변수를 안 꽂으면 그 문자열이 그대로 Basic 인증에 실려
     * NICE가 401을 준다 — 운영 로그에서 '설정 누락'과 'NICE 장애'가 구분되지 않는다.
     * 그래서 blank와 `YOUR_` 접두 플레이스홀더를 모두 '미설정'으로 본다.
     */
    private static boolean isUnset(String value) {
        return value == null || value.isBlank() || value.startsWith("YOUR_");
    }

    public String getAccessToken() {
        // [C-1.3] 진입부 가드 — 자격증명이 없으면 외부 호출 전에 명시적으로 실패시킨다.
        //   ※ 전역 fail-fast(부팅 차단)가 아니라 진입부 차단이다(docs/19 D-31과 같은 원칙):
        //     설정 누락이 애플리케이션 부팅 블로커가 되면 안 된다.
        if (isUnset(props.getClientId()) || isUnset(props.getClientSecret()) || isUnset(props.getProductId())) {
            log.error("[NICE] 자격증명 미주입 — NICE_CLIENT_ID/NICE_CLIENT_SECRET/NICE_PRODUCT_ID 환경변수를 확인하라");
            throw new BusinessException(ErrorCode.VERIFICATION_TOKEN_FAILED,
                "본인확인 서비스가 아직 설정되지 않았습니다. 잠시 후 다시 시도해 주세요.");
        }

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
        // [D-30] methodtype은 NICE가 returnurl로 결과를 돌려줄 때의 HTTP 메서드다.
        //   returnurl(=백엔드 /api/v1/verify/callback)이 GET·POST를 **양쪽 다** 받으므로
        //   이 값이 무엇이든 콜백은 성립한다 — 실연동에서 다른 값이 요구되면 여기만 바꾸면 된다.
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