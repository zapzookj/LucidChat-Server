package com.spring.aichat.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
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
 *   <li>대상: {@link #GUEST_BROWSE_PREFIXES} 프리픽스의 GET 요청 중 <b>실제 인증 컨텍스트가
 *       없는(익명)</b> 요청만. 판정은 헤더 문자열이 아니라 {@link SecurityContextHolder}로 한다 —
 *       이 필터는 {@code BearerTokenAuthenticationFilter} <b>뒤</b>에 배치되므로, 유효 토큰은
 *       이미 인증 컨텍스트가 채워져 있고 무효 Bearer는 401로 이 필터에 도달조차 못 한다.
 *       (구현 초기 결함: "Authorization 헤더 존재=면제"로 판정 → {@code Authorization: guest} 같은
 *       비-Bearer 스킴은 Spring이 인증도 401도 하지 않고 익명 통과시키므로 정적 헤더 한 줄로
 *       레이트리밋이 통째로 우회됐다. 적대적 리뷰 P1.)</li>
 *   <li>한도: 일반 탐색 IP당 {@value #BROWSE_MAX}회 / {@value #WINDOW_SECONDS}초, 프로필 열거
 *       경로는 별도 버킷 {@value #PROFILE_MAX}회 / {@value #WINDOW_SECONDS}초(순차 id 카탈로그
 *       수집 방어). fixed-window 경계 더블링은 수용된 트레이드오프(sliding window는 공용
 *       {@link ApiRateLimiter} 개편 필요 — 별도 과제).</li>
 *   <li>Redis 장애 시 fail-open — 기존 {@link ApiRateLimiter} 정책 그대로(가용성 우선).</li>
 * </ul>
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class GuestBrowseRateLimitFilter extends OncePerRequestFilter {

    private static final int BROWSE_MAX = 120;
    private static final int PROFILE_MAX = 40;   // 순차 id 프로필 열거 방어 — 일반 탐색보다 타이트
    private static final int WINDOW_SECONDS = 60;

    /** 프로필 열거 경로 — /lobby/characters/{id}/profile. 별도 버킷·저한도. */
    private static final String PROFILE_PATH_MARK = "/profile";
    private static final String CHARACTERS_PREFIX = "/api/v1/lobby/characters/";

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
        if (isAuthenticated()) return true; // 실제 인증 사용자 — 게스트 리밋 비대상(username-keyed 별도 리밋)
        String path = decodedPath(request);
        return GUEST_BROWSE_PREFIXES.stream().noneMatch(path::startsWith);
    }

    /** SecurityContext에 익명이 아닌 인증 주체가 있는가(= 유효 토큰 통과). */
    private static boolean isAuthenticated() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null
            && auth.isAuthenticated()
            && !(auth instanceof AnonymousAuthenticationToken);
    }

    /**
     * [적대적 리뷰 P1] 프리픽스 판정을 <b>디코딩</b> 경로로 한다.
     * getRequestURI()는 raw(미디코딩)라 {@code /api/v1/%6Cobby/feed}(=%6C→'l')가 프리픽스 매칭을
     * 비껴가 필터가 통째로 스킵됐다 — 그러나 permitAll 매처와 MVC 핸들러는 디코딩 경로로 매칭하므로
     * 정상 서빙됐다(레이트리밋 무제한 우회). StrictHttpFirewall이 %2F·%25를 이미 차단하므로
     * 단일 디코딩은 경로 구분자 주입 위험 없이 라우터와 동일한 경로 표현을 얻는다.
     */
    private static String decodedPath(HttpServletRequest request) {
        String uri = request.getRequestURI();
        if (uri.indexOf('%') < 0) return uri; // 인코딩 없음 — 디코딩 불필요
        try {
            return java.net.URLDecoder.decode(uri, java.nio.charset.StandardCharsets.UTF_8);
        } catch (RuntimeException e) {
            return uri; // 디코딩 실패 시 raw로 폴백(보수적으로 리밋 대상 판정)
        }
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String clientIp = ClientIpResolver.resolve(request);
        String uri = decodedPath(request);
        boolean profileEnum = uri.startsWith(CHARACTERS_PREFIX) && uri.endsWith(PROFILE_PATH_MARK);
        String bucket = profileEnum ? "guest_profile" : "guest_browse";
        int max = profileEnum ? PROFILE_MAX : BROWSE_MAX;

        if (apiRateLimiter.isRateLimited(bucket, clientIp, max, WINDOW_SECONDS)) {
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
