package com.spring.aichat.config;

import com.spring.aichat.exception.BusinessException;
import com.spring.aichat.exception.ErrorCode;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * PortOne (구 아임포트) API 설정
 *
 * application.yml:
 * portone:
 *   api-key: ${PORTONE_API_KEY:}
 *   api-secret: ${PORTONE_API_SECRET:}
 *   api-url: https://api.iamport.kr
 *
 * <p>⚠ [C-2.l · docs/17_assets/defect_register.md] 이전 application.yml은
 * {@code YOUR_PORTONE_API_KEY} 같은 <b>리터럴 플레이스홀더</b>였다(`${ENV:default}` 형태 아님).
 * 그 상태에서는 환경변수 미주입이 기동 성공 → 결제 대사 시점 401 로 나타나
 * 운영 로그에서 '설정 누락'과 'PG 장애'가 구분되지 않았다.
 * 이제 yml은 {@code ${PORTONE_API_KEY:}} 형태이고, 여기 {@link #assertConfigured(String)}가
 * blank 와 {@code YOUR_} 접두 플레이스홀더를 <b>모두 '미설정'으로</b> 판정한다.
 *
 * <p>가드를 전역 fail-fast(부팅 차단)로 만들지 않은 이유는 C-1.3(NiceApiClient)과 같다 —
 * docs/19 §C-1의 부팅 블로커 사고 이후, 자격증명 누락은 <b>진입부 차단</b>으로 처리한다.
 */
@Slf4j
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "portone")
public class PortOneProperties {

    private String apiKey;
    private String apiSecret;
    private String apiUrl = "https://api.iamport.kr";

    /**
     * [C-2.l] 자격증명 미주입 판정. blank 뿐 아니라 {@code YOUR_} 접두 플레이스홀더도
     * '미설정'으로 본다 — 리터럴 플레이스홀더가 그대로 아임포트에 실려 나가는 것이
     * 이 결함의 본체였기 때문이다.
     */
    private static boolean isUnset(String value) {
        return value == null || value.isBlank() || value.startsWith("YOUR_");
    }

    /** PortOne 서버 자격증명이 실제로 주입되어 있는가. */
    public boolean isCredentialsConfigured() {
        return !isUnset(apiKey) && !isUnset(apiSecret);
    }

    /**
     * [C-2.l · 선례 NiceApiClient.getAccessToken] PortOne 실호출 진입부 가드.
     * 자격증명이 없으면 외부 호출 전에 명시적으로 실패시킨다.
     *
     * @param callSite 로그 식별용 호출 지점(예: {@code "prepareOrder"}).
     * @throws BusinessException 미설정 시 {@link ErrorCode#EXTERNAL_API_ERROR}(502).
     */
    public void assertConfigured(String callSite) {
        if (!isCredentialsConfigured()) {
            log.error("[PortOne] 자격증명 미주입 — PORTONE_API_KEY/PORTONE_API_SECRET 환경변수를 확인하라 "
                + "(callSite={}, apiKeyBlank={}, apiSecretBlank={})",
                callSite, isUnset(apiKey), isUnset(apiSecret));
            throw new BusinessException(ErrorCode.EXTERNAL_API_ERROR,
                "결제 서비스가 아직 설정되지 않았습니다. 잠시 후 다시 시도해 주세요.");
        }
    }
}