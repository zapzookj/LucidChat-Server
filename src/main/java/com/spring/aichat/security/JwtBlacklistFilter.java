package com.spring.aichat.security;

import com.spring.aichat.service.auth.JwtTokenService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * [Phase6/Tier3 / C-2] JWT 블랙리스트 필터.
 *
 * /auth/logout 호출 후에도 access token으로 모든 API 호출 가능했던 결함을 해결한다.
 * 로그아웃된 토큰(BL:{jti} Redis 키 존재)은 SecurityFilterChain 진입 단계에서 401 차단.
 *
 * 등록 위치: SecurityConfig에서 BearerTokenAuthenticationFilter *앞*.
 * 토큰 미존재/유효하지 않은 토큰은 그대로 통과시켜 Resource Server 기본 흐름에 위임.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtBlacklistFilter extends OncePerRequestFilter {

    private static final String AUTH_HEADER = "Authorization";
    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtTokenService jwtTokenService;

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain chain) throws ServletException, IOException {
        String authHeader = request.getHeader(AUTH_HEADER);
        if (authHeader != null && authHeader.startsWith(BEARER_PREFIX)) {
            String token = authHeader.substring(BEARER_PREFIX.length()).trim();
            if (!token.isEmpty() && jwtTokenService.isTokenRevoked(token)) {
                log.info("[JWT] Blocked revoked/suspended token | uri={}", request.getRequestURI());
                response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Token revoked");
                return;
            }
        }
        chain.doFilter(request, response);
    }
}
