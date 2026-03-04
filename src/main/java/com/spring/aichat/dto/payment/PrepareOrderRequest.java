package com.spring.aichat.dto.payment;

import com.spring.aichat.domain.enums.ProductType;
import jakarta.validation.constraints.NotNull;

/**
 * 사전 주문 생성 요청
 * - 결제창 열기 전 서버에 주문 등록
 */
public record PrepareOrderRequest(
    @NotNull(message = "상품 타입은 필수입니다.")
    ProductType productType,

    /** 캐릭터 시크릿 해금 상품일 때만 필수 */
    Long targetCharacterId
) {}