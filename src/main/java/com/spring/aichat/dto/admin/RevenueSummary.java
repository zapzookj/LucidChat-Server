package com.spring.aichat.dto.admin;

import java.time.LocalDateTime;
import java.util.List;

/** 기간 매출 요약. net = totalPaid - totalRefunded. */
public record RevenueSummary(
    LocalDateTime from,
    LocalDateTime to,
    long totalPaid,
    long totalRefunded,
    long net,
    List<ProductRevenue> byProduct
) {}
