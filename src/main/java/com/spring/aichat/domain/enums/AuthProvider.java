package com.spring.aichat.domain.enums;

/**
 * 인증 제공자 구분
 *
 * [Phase 5] KAKAO, NAVER 추가
 * LOCAL은 deprecated — 신규 가입 불가, 기존 계정만 로그인 허용
 */
public enum AuthProvider {
    /** @deprecated 신규 가입 차단됨. 기존 유저 하위 호환용으로만 유지. */
    @Deprecated
    LOCAL,

    GOOGLE,
    KAKAO,
    NAVER
}