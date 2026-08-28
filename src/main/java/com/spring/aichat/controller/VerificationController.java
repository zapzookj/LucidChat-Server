package com.spring.aichat.controller;

import com.spring.aichat.config.NiceApiProperties;
import com.spring.aichat.dto.verification.VerificationCallbackRequest;
import com.spring.aichat.dto.verification.VerificationTokenResponse;
import com.spring.aichat.service.verification.VerificationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * 성인 인증 API
 *
 * [플로우]
 * 1. GET  /api/v1/verify/token    -> NICE 인증 토큰 발급 (프론트가 팝업 호출에 사용)
 * 2. GET|POST /api/v1/verify/callback -> NICE 팝업이 돌아오는 지점 (인증 불요) -> SPA로 302
 * 3. POST /api/v1/verify/success  -> 인증 결과 검증 (SPA 오프너가 호출, JWT 필수)
 *
 * [D-30 · docs/19_assets/decision_agenda.md D-30 · 종원 확정]
 *   (2)를 백엔드에 둔 이유: NICE CheckPlus 콜백이 GET 쿼리인지 POST 폼인지에 대한
 *   계약 답변(docs/18 §1-E)을 기다리지 않기 위해서다. 백엔드가 **양쪽을 다 받아**
 *   SPA 라우트로 302 시키면 그 답이 무엇이든 플로우가 성립한다.
 *   이전에는 return-url이 SPA를 직접 가리켰고 그 라우트조차 없어(C-1.5),
 *   AdultVerificationModal의 postMessage 수신부가 통째로 도달 불가 코드였다.
 *
 * [보안]
 * - /token·/success는 JWT 인증 필수. /callback만 permitAll (팝업 컨텍스트에 토큰이 없다)
 * - key/iv는 서버 세션(Redis)에만 저장, 프론트에 노출 안 함
 * - 실제 복호화·나이 판정·CI 중복 검사는 (3)에서 인증된 유저 컨텍스트로만 수행한다.
 *   (2)는 파라미터를 그대로 릴레이할 뿐이며, enc_data는 서버 보관 key/iv 없이는 무의미하다.
 */
@RestController
@RequiredArgsConstructor
@Slf4j
@RequestMapping("/api/v1/verify")
public class VerificationController {

    private final VerificationService verificationService;
    private final NiceApiProperties niceApiProperties;

    /**
     * NICE 인증 토큰 발급
     * - 시크릿 모드 진입 시도 시 isAdult=false인 경우 프론트에서 호출
     */
    @GetMapping("/token")
    public ResponseEntity<VerificationTokenResponse> requestToken(Authentication authentication) {
        VerificationTokenResponse response = verificationService.requestToken(authentication.getName());
        return ResponseEntity.ok(response);
    }

    /**
     * [D-30 / C-1.5] NICE 팝업 콜백 수신 — GET 쿼리·POST 폼 양수신 후 SPA로 302
     *
     * nice.return-url이 이 엔드포인트를 가리킨다. @RequestParam은 쿼리스트링과
     * application/x-www-form-urlencoded 본문을 **동일하게** 바인딩하므로,
     * NICE가 어느 메서드로 보내든 같은 코드가 처리한다.
     *
     * ⚠ [확인필요] 파라미터 이름은 NICE CheckPlus 표준창 규격(enc_data / token_version_id /
     *   integrity_value)을 따랐다. 자격증명 미발급으로 **실호출을 관측하지 못했다**
     *   (docs/18 §1-E 미착수). 그래서 (a) 구형 규격 이름 EncodeData를 별칭으로 함께 받고,
     *   (b) 미인식 시 수신 파라미터의 **키 목록만** 로그로 남긴다 — 첫 실연동 로그 한 줄로
     *   실제 규격이 드러나게 하기 위함이다. 값은 개인정보 원문을 품은 암호문이라 로깅하지 않는다.
     *
     * 응답은 항상 302다. 실패해도 302 + error 파라미터로 보내야 팝업이 SPA 브리지에
     * 도달해 오프너에게 결과를 알리고 스스로 닫을 수 있다(에러 페이지를 띄우면 데드엔드).
     */
    @RequestMapping(value = "/callback", method = {RequestMethod.GET, RequestMethod.POST})
    public ResponseEntity<Void> niceCallback(@RequestParam Map<String, String> params) {
        String encData = firstNonBlank(
            params.get("enc_data"), params.get("encData"), params.get("EncodeData"));
        String tokenVersionId = firstNonBlank(
            params.get("token_version_id"), params.get("tokenVersionId"));
        String integrityValue = firstNonBlank(
            params.get("integrity_value"), params.get("integrityValue"));

        // ⚠ UriComponentsBuilder.encode()를 쓰지 않는 이유: enc_data는 표준 Base64라 '+'를
        //   포함할 수 있는데, '+'는 쿼리에서 legal이라 encode()가 건드리지 않는다. 그러면
        //   브라우저의 URLSearchParams가 '+'를 **공백으로** 되돌려 암호문이 깨진다.
        //   URLEncoder는 '+'를 %2B로 이스케이프하므로 왕복이 정확히 맞는다.
        StringBuilder url = new StringBuilder(niceApiProperties.getFrontendCallbackUrl());
        url.append(url.indexOf("?") >= 0 ? '&' : '?');

        if (encData == null) {
            // 키 목록만 (값 금지 — enc_data는 개인정보 암호문)
            log.warn("[VERIFY] NICE 콜백에 enc_data 없음. 수신 파라미터 키={}", params.keySet());
            url.append("error=NO_ENC_DATA");
        } else {
            url.append("encData=").append(urlEncode(encData));
            if (tokenVersionId != null) url.append("&tokenVersionId=").append(urlEncode(tokenVersionId));
            if (integrityValue != null) url.append("&integrityValue=").append(urlEncode(integrityValue));
            log.info("[VERIFY] NICE 콜백 수신 — SPA 브리지로 302 (tokenVersionId={})", tokenVersionId);
        }

        return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(url.toString())).build();
    }

    private static String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) return v;
        }
        return null;
    }

    /**
     * 인증 결과 콜백
     * - NICE 팝업 인증 완료 후 프론트에서 암호화된 결과를 전달
     * - 서버에서 복호화 -> 나이 검증 -> CI 중복 체크 -> 성인 인증 완료
     */
    @PostMapping("/success")
    public ResponseEntity<Map<String, Object>> verifyCallback(
        @RequestBody @Valid VerificationCallbackRequest request,
        Authentication authentication
    ) {
        verificationService.verifyCallback(authentication.getName(), request);

        return ResponseEntity.ok(Map.of(
            "success", true,
            "message", "성인 인증이 완료되었습니다."
        ));
    }
}