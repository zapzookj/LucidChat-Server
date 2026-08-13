package com.spring.aichat.security;

import jakarta.servlet.ServletException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * [블록 A 게스트] 비인증 공개 탐색 IP 레이트리밋 필터 — 적용 범위·429 계약 검증.
 */
class GuestBrowseRateLimitFilterTest {

    private final ApiRateLimiter limiter = mock(ApiRateLimiter.class);
    private final GuestBrowseRateLimitFilter filter = new GuestBrowseRateLimitFilter(limiter);

    private MockHttpServletRequest get(String uri) {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", uri);
        req.setRequestURI(uri);
        return req;
    }

    @Test
    @DisplayName("게스트 GET 브라우징 경로만 필터 대상이다")
    void scopesToGuestBrowsePaths() throws Exception {
        assertFalse(filter.shouldNotFilter(get("/api/v1/lobby/feed")));
        assertFalse(filter.shouldNotFilter(get("/api/v1/lobby/characters/3/profile")));
        assertFalse(filter.shouldNotFilter(get("/api/v1/theater/lobby/worlds/ACADEMY")));
        assertFalse(filter.shouldNotFilter(get("/api/v1/ugc/characters/explore")));

        // 비대상: 회원 전용 경로·비GET·인증 요청
        assertTrue(filter.shouldNotFilter(get("/api/v1/lobby/rooms")));
        assertTrue(filter.shouldNotFilter(get("/api/v1/chat/rooms/1/logs")));
        MockHttpServletRequest post = new MockHttpServletRequest("POST", "/api/v1/lobby/feed");
        post.setRequestURI("/api/v1/lobby/feed");
        assertTrue(filter.shouldNotFilter(post));
        MockHttpServletRequest authed = get("/api/v1/lobby/feed");
        authed.addHeader("Authorization", "Bearer some-token");
        assertTrue(filter.shouldNotFilter(authed));
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
    @DisplayName("한도 내 요청은 체인으로 통과한다")
    void passesThroughWhenAllowed() throws ServletException, IOException {
        when(limiter.isRateLimited(eq("guest_browse"), anyString(), anyInt(), anyInt())).thenReturn(false);
        MockHttpServletRequest req = get("/api/v1/lobby/feed");
        MockHttpServletResponse res = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilterInternal(req, res, chain);

        assertEquals(200, res.getStatus());
        assertNotNull(chain.getRequest());
    }
}
