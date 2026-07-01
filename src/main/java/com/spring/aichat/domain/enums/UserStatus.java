package com.spring.aichat.domain.enums;

/**
 * 계정 상태 (Phase 6: 관리자 수동 정지/차단).
 *
 * 가입 시점 디바이스 휴리스틱(DeviceFingerprintGuard의 자동 섀도우밴) 외에
 * 런타임에 문제 유저를 막을 수단이 없던 공백을 메운다.
 */
public enum UserStatus {
    /** 정상 이용 */
    ACTIVE,
    /** 일시 정지 — 운영 판단, 해제 가능 */
    SUSPENDED,
    /** 영구 차단 */
    BANNED;

    /** 접근을 차단해야 하는 상태인가. */
    public boolean blocksAccess() {
        return this != ACTIVE;
    }
}
