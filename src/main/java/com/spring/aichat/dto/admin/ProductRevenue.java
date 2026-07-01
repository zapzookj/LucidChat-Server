package com.spring.aichat.dto.admin;

/** 상품별 매출 집계 행. */
public record ProductRevenue(
    String productType,
    long count,
    long amount
) {}
