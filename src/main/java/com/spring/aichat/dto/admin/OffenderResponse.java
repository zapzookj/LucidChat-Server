package com.spring.aichat.dto.admin;

/** 반복 위반자 집계 행 (모더레이션 + 인젝션 합산). 관리자 정지 판단용. */
public record OffenderResponse(
    Long userId,
    String username,
    String nickname,
    long moderationCount,
    long injectionCount,
    long total
) {}
