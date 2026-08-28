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
            // [버그픽스 B-10.1 · docs/17_assets/defect_register.md §B-10.1 · docs/19 D-31]
            //   refresh 토큰이 Bearer 자리에 오면 거부한다. 디코더(JwtConfig)는 만료·nbf만 보고
            //   SecurityConfig는 .anyRequest().authenticated() — '권한'이 아니라 '인증 여부'만
            //   보므로, 타입 구분이 없으면 RT 하나로 14일간 일반 유저 API 전체가 열린다
            //   (RT TTL 1209600s = AT의 336배).
            //   ★ 디코더에 OAuth2TokenValidator를 다는 대신 여기서 막는 이유: 그 디코더 빈은
            //     JwtTokenService.reissue·logout·isTokenRevoked가 공유하므로, typ=access를
            //     강제하면 정상적인 재발급 경로가 함께 죽는다. 반면 이 필터는 이미 같은 자리에서
            //     토큰을 디코드하고 있어(isTokenRevoked) 추가 비용이 사실상 없다.
            //   ※ /api/v1/auth/reissue는 RT를 쿠키·바디로 받으므로 이 판정에 걸리지 않는다.
            if (!token.isEmpty() && jwtTokenService.isRefreshTypeToken(token)) {
                log.warn("[JWT] Blocked refresh token used as Bearer | uri={}", request.getRequestURI());
                response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Refresh token not allowed");
                return;
            }
        }
        chain.doFilter(request, response);
    }
}
