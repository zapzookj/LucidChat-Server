package com.spring.aichat.controller;

import com.spring.aichat.dto.verification.VerificationCallbackRequest;
import com.spring.aichat.dto.verification.VerificationTokenResponse;
import com.spring.aichat.service.verification.VerificationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 성인 인증 API
 *
 * [플로우]
 * 1. GET  /api/v1/verify/token   -> NICE 인증 토큰 발급 (프론트가 팝업 호출에 사용)
 * 2. POST /api/v1/verify/success -> 인증 결과 콜백 (프론트가 팝업 결과 전달)
 *
 * [보안]
 * - 모든 엔드포인트는 JWT 인증 필수
 * - key/iv는 서버 세션(Redis)에만 저장, 프론트에 노출 안 함
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/verify")
public class VerificationController {

    private final VerificationService verificationService;

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