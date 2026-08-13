package com.spring.aichat.security;

import jakarta.servlet.ServletException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * [블록 A 게스트] 비인증 공개 탐색 IP 레이트리밋 필터 — 적용 범위·429 계약·우회 회귀 검증.
 */
class GuestBrowseRateLimitFilterTest {

    private final ApiRateLimiter limiter = mock(ApiRateLimiter.class);
    private final GuestBrowseRateLimitFilter filter = new GuestBrowseRateLimitFilter(limiter);

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private MockHttpServletRequest get(String uri) {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", uri);
        req.setRequestURI(uri);
        return req;
    }

    @Test
    @DisplayName("익명 GET 브라우징 경로만 필터 대상이다")
    void scopesToGuestBrowsePaths() throws Exception {
        assertFalse(filter.shouldNotFilter(get("/api/v1/lobby/feed")));
        assertFalse(filter.shouldNotFilter(get("/api/v1/lobby/characters/3/profile")));
        assertFalse(filter.shouldNotFilter(get("/api/v1/theater/lobby/worlds/ACADEMY")));
        assertFalse(filter.shouldNotFilter(get("/api/v1/ugc/characters/explore")));

        // 비대상: 회원 전용 경로·비GET
        assertTrue(filter.shouldNotFilter(get("/api/v1/lobby/rooms")));
        assertTrue(filter.shouldNotFilter(get("/api/v1/chat/rooms/1/logs")));
        MockHttpServletRequest post = new MockHttpServletRequest("POST", "/api/v1/lobby/feed");
        post.setRequestURI("/api/v1/lobby/feed");
        assertTrue(filter.shouldNotFilter(post));
    }

    @Test
    @DisplayName("[P1 회귀] 비-Bearer 임의 Authorization 헤더는 면제 사유가 아니다 — 익명이면 리밋 적용")
    void junkAuthorizationHeaderDoesNotBypass() throws Exception {
        // Spring은 'Bearer' 스킴이 아닌 헤더를 인증도 401도 하지 않고 익명 통과시킨다.
        // 헤더 존재만으로 면제하면 정적 헤더 한 줄로 레이트리밋 전면 우회 — 적대적 리뷰 P1 재현 차단.
        MockHttpServletRequest req = get("/api/v1/lobby/feed");
        req.addHeader("Authorization", "guest");
        assertFalse(filter.shouldNotFilter(req), "비-Bearer 헤더는 익명 요청 — 반드시 리밋 대상");

        MockHttpServletRequest basic = get("/api/v1/lobby/feed");
        basic.addHeader("Authorization", "Basic dXNlcjpwdw==");
        assertFalse(filter.shouldNotFilter(basic));
    }

    @Test
    @DisplayName("실제 인증 컨텍스트가 있으면 면제된다 (유효 토큰 사용자)")
    void authenticatedContextIsExempt() throws Exception {
        TestingAuthenticationToken auth = new TestingAuthenticationToken("user1", "n/a", "ROLE_USER");
        auth.setAuthenticated(true);
        SecurityContextHolder.getContext().setAuthentication(auth);
        assertTrue(filter.shouldNotFilter(get("/api/v1/lobby/feed")));
    }

    @Test
    @DisplayName("한도 초과 시 429 JSON으로 즉시 종료하고 체인을 타지 않는다")
    void returns429WhenLimited() throws ServletException, IOException {
        when(limiter.isRateLimited(eq("guest_browse"), anyString(), anyInt(), anyInt())).thenReturn(true);
        MockHttpServletRequest req = get("/api/v1/lobby/feed");
        MockHttpServletResponse res = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilterInternal(req, res, chain);

        assertEquals(429, res.getStatus());
        assertTrue(res.getContentAsString().contains("RATE_LIMITED"));
        assertNull(chain.getRequest(), "체인으로 전달되면 안 된다");
    }

    @Test
    @DisplayName("[P1 회귀] 퍼센트 인코딩 경로도 디코딩해 리밋 대상으로 판정한다 (%6Cobby→lobby)")
    void percentEncodedPathIsDecoded() {
        // raw URI가 프리픽스를 비껴가도 permitAll·핸들러는 디코딩 경로로 서빙하므로 우회 불가해야 함
        assertFalse(filter.shouldNotFilter(get("/api/v1/%6Cobby/feed")));
        assertFalse(filter.shouldNotFilter(get("/api/v1/lobby/characters/17/%70rofile")));
    }

    @Test
    @DisplayName("[P1 회귀] 인코딩된 프로필 경로도 저한도 guest_profile 버킷으로 판정")
    void percentEncodedProfileUsesProfileBucket() throws ServletException, IOException {
        when(limiter.isRateLimited(anyString(), anyString(), anyInt(), anyInt())).thenReturn(false);
        MockHttpServletRequest req = get("/api/v1/lobby/characters/17/%70rofile");
        req.setRemoteAddr("203.0.113.9");

        filter.doFilterInternal(req, new MockHttpServletResponse(), new MockFilterChain());

        verify(limiter).isRateLimited(eq("guest_profile"), eq("203.0.113.9"), eq(40), eq(60));
    }

    @Test
    @DisplayName("프로필 열거 경로는 별도 저한도 버킷(guest_profile)을 쓴다")
    void profileEnumerationUsesTighterBucket() throws ServletException, IOException {
        when(limiter.isRateLimited(anyString(), anyString(), anyInt(), anyInt())).thenReturn(false);
        MockHttpServletRequest req = get("/api/v1/lobby/characters/17/profile");
        req.setRemoteAddr("203.0.113.5");

        filter.doFilterInternal(req, new MockHttpServletResponse(), new MockFilterChain());

        verify(limiter).isRateLimited(eq("guest_profile"), eq("203.0.113.5"), eq(40), eq(60));
    }

    @Test
    @DisplayName("한도 내 요청은 체인으로 통과한다")
    void passesThroughWhenAllowed() throws ServletException, IOException {
        when(limiter.isRateLimited(anyString(), anyString(), anyInt(), anyInt())).thenReturn(false);
        MockHttpServletRequest req = get("/api/v1/lobby/feed");
        MockHttpServletResponse res = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilterInternal(req, res, chain);

        assertEquals(200, res.getStatus());
        assertNotNull(chain.getRequest());
    }
}
