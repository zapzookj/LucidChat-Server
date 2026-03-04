package com.spring.aichat.dto.verification;

import jakarta.validation.constraints.NotBlank;

/**
 * 성인 인증 결과 콜백 요청
 * - 프론트엔드가 NICE 인증 팝업 완료 후 전달하는 암호화된 결과
 */
public record VerificationCallbackRequest(
    @NotBlank(message = "requestNo는 필수입니다.")
    String requestNo,

    @NotBlank(message = "encData는 필수입니다.")
    String encData,

    @NotBlank(message = "tokenVersionId는 필수입니다.")
    String tokenVersionId
) {}