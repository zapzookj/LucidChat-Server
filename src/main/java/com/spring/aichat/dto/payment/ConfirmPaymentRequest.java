package com.spring.aichat.dto.payment;

import jakarta.validation.constraints.NotBlank;

/**
 * 결제 확인 요청 (사후 검증)
 * - 프론트에서 결제 완료 후 imp_uid와 merchant_uid를 전달
 */
public record ConfirmPaymentRequest(
    @NotBlank(message = "imp_uid는 필수입니다.")
    String impUid,

    @NotBlank(message = "merchant_uid는 필수입니다.")
    String merchantUid
) {}