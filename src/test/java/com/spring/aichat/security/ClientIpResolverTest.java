package com.spring.aichat.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * [블록 A 게스트] 클라이언트 IP 해석 — XFF 최우측(신뢰 프록시가 append한 값) 원칙 검증.
 * 최좌측 사용은 docs/13 B-11의 위조 우회 벡터라 금지.
 */
class ClientIpResolverTest {

    @Test
    @DisplayName("XFF가 있으면 최우측 값을 취한다 — 최좌측은 클라이언트가 위조 가능")
    void takesRightmostXff() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("X-Forwarded-For", "6.6.6.6, 203.0.113.7");
        req.setRemoteAddr("10.0.0.1");
        assertEquals("203.0.113.7", ClientIpResolver.resolve(req));
    }

    @Test
    @DisplayName("XFF 부재 시 remoteAddr 폴백")
    void fallsBackToRemoteAddr() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setRemoteAddr("192.168.0.5");
        assertEquals("192.168.0.5", ClientIpResolver.resolve(req));
    }

    @Test
    @DisplayName("단일 값 XFF는 그대로 사용")
    void singleXffValue() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("X-Forwarded-For", "203.0.113.9");
        req.setRemoteAddr("10.0.0.1");
        assertEquals("203.0.113.9", ClientIpResolver.resolve(req));
    }

    @Test
    @DisplayName("공백뿐인 XFF는 remoteAddr 폴백 — 빈 키 레이트리밋 버킷 공유 방지")
    void blankXffFallsBack() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("X-Forwarded-For", "   ");
        req.setRemoteAddr("192.168.0.9");
        assertEquals("192.168.0.9", ClientIpResolver.resolve(req));
    }
}
