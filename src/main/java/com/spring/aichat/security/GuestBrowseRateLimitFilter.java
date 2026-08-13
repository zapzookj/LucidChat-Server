package com.spring.aichat.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * [블록 A 게스트 브라우징] 비인증 공개 탐색 경로 IP 레이트리밋 필터.
 *
 * <p>게스트 개방(permitAll)된 브라우징 GET 엔드포인트에 한해, Authorization 헤더가 없는
 * 요청을 IP 단위로 제한한다(스크래핑 방어 — docs/14 부록 §3 보안 3원칙 ③).
 *
 * <ul>
 *   <li>대상: {@link #GUEST_BROWSE_PREFIXES} 프리픽스의 GET 요청 중 Authorization 부재분만.
 *       위조 Bearer로 우회 불가 — permitAll 경로여도 유효하지 않은 Bearer는
 *       BearerTokenAuthenticationFilter가 401로 쳐낸다.</li>
 *   <li>한도: IP당 {@value #MAX_REQUESTS}회 / {@value #WINDOW_SECONDS}초 (fixed window).
 *       피드+프로필 탐색이 화면당 2~4콜이므로 정상 브라우징엔 넉넉하고 크롤러엔 유효한 수준.</li>
 *   <li>Redis 장애 시 fail-open — 기존 {@link ApiRateLimiter} 정책 그대로(가용성 우선).</li>
 * </ul>
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class GuestBrowseRateLimitFilter extends OncePerRequestFilter {

    private static final int MAX_REQUESTS = 120;
    private static final int WINDOW_SECONDS = 60;

    /** SecurityConfig의 게스트 permitAll 목록과 반드시 동기 유지할 것. */
    private static final List<String> GUEST_BROWSE_PREFIXES = List.of(
        "/api/v1/lobby/characters",
        "/api/v1/lobby/feed",
        "/api/v1/lobby/worlds",
        "/api/v1/theater/lobby/worlds",
        "/api/v1/ugc/characters/explore",
        "/api/v1/faq"
    );

    private final ApiRateLimiter apiRateLimiter;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (!"GET".equalsIgnoreCase(request.getMethod())) return true;
        String auth = request.getHeader("Authorization");
        if (auth != null && !auth.isBlank()) return true; // 인증 요청 — 게스트 리밋 비대상
        String uri = request.getRequestURI();
        return GUEST_BROWSE_PREFIXES.stream().noneMatch(uri::startsWith);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String clientIp = ClientIpResolver.resolve(request);
        if (apiRateLimiter.isRateLimited("guest_browse", clientIp, MAX_REQUESTS, WINDOW_SECONDS)) {
            response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding("UTF-8");
            response.getWriter().write(
                "{\"error\":\"RATE_LIMITED\",\"message\":\"요청이 너무 많습니다. 잠시 후 다시 시도해 주세요.\"}");
            return;
        }
        filterChain.doFilter(request, response);
    }
}
