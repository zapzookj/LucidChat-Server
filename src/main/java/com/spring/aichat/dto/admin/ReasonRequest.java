package com.spring.aichat.dto.admin;

/** 사유만 담는 공용 요청(구독 해제 / 성인 인증 해제 등). reason 은 선택. */
public record ReasonRequest(
    String reason
) {}
