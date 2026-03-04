package com.spring.aichat.external;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.spring.aichat.config.NiceApiProperties;
import com.spring.aichat.exception.BusinessException;
import com.spring.aichat.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
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
 * [보안 핵심]
 * 1. Access Token은 짧은 TTL로 관리 (요청마다 발급 or Redis 캐싱)
 * 2. 암호화 토큰(enc_data)은 서버에서만 복호화 - 클라이언트에 키 노출 금지
 * 3. CI 값은 해시 후 저장 (원본 보관 불필요)
 *
 * [NICE API 플로우]
 * 1. /digital/niceid/oauth/oauth/token -> Access Token 발급
 * 2. /digital/niceid/api/v1.0/common/crypto/token -> 암호화 토큰 발급
 * 3. 프론트에서 팝업 인증 -> enc_data 콜백
 * 4. 서버에서 enc_data 복호화 -> 이름, 생년월일, CI 추출
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NiceApiClient {

    private final NiceApiProperties props;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    /**
     * NICE OAuth2 Access Token 발급
     */
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
                log.info("[NICE] Access Token 발급 성공");
                return tokenType + " " + accessToken;
            }

            throw new BusinessException(ErrorCode.VERIFICATION_TOKEN_FAILED,
                "NICE Access Token 발급에 실패했습니다.");

        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("[NICE] Access Token 발급 중 예외", e);
            throw new BusinessException(ErrorCode.VERIFICATION_TOKEN_FAILED,
                "NICE 인증 서버와 통신에 실패했습니다.", e);
        }
    }

    /**
     * NICE 암호화 토큰 발급 (프론트 팝업 호출용)
     */
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
                        "NICE 암호화 토큰 발급 실패 (rsp_cd=" + resultCd + ")");
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

                log.info("[NICE] 암호화 토큰 발급 성공: tokenVersionId={}", tokenVersionId);
                return new CryptoTokenResult(tokenVersionId, encData, integrityValue, key, iv);
            }

            throw new BusinessException(ErrorCode.VERIFICATION_TOKEN_FAILED,
                "NICE 암호화 토큰 응답이 올바르지 않습니다.");

        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("[NICE] 암호화 토큰 발급 중 예외", e);
            throw new BusinessException(ErrorCode.VERIFICATION_TOKEN_FAILED,
                "NICE 암호화 토큰 발급에 실패했습니다.", e);
        }
    }

    /**
     * NICE 인증 완료 후 enc_data 복호화
     */
    public JsonNode decryptResult(String encData, String key, String iv) {
        try {
            String decrypted = decryptAES128CBC(encData, key, iv);
            return objectMapper.readTree(decrypted);
        } catch (Exception e) {
            log.error("[NICE] 인증 결과 복호화 실패", e);
            throw new BusinessException(ErrorCode.VERIFICATION_DECRYPT_FAILED,
                "인증 결과 복호화에 실패했습니다.", e);
        }
    }

    // ── 내부 유틸리티 ──

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

    /** 암호화 토큰 결과 (key/iv는 서버 세션에만 저장, 프론트 노출 금지) */
    public record CryptoTokenResult(
        String tokenVersionId,
        String encData,
        String integrityValue,
        String key,
        String iv
    ) {}
}