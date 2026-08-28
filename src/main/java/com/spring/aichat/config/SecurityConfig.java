package com.spring.aichat.config;

import com.spring.aichat.security.GuestBrowseRateLimitFilter;
import com.spring.aichat.security.JwtBlacklistFilter;
import com.spring.aichat.service.auth.OAuth2LoginSuccessHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.client.OAuth2AuthorizationSuccessHandler;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

/**
 * 보안 설정
 * - API: Bearer JWT 인증(Resource Server)
 * - Google: oauth2Login으로 인증 후, 성공 시 우리 JWT 발급
 */
@Configuration
@RequiredArgsConstructor
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    private final OAuth2LoginSuccessHandler oAuth2LoginSuccessHandler;
    private final JwtBlacklistFilter jwtBlacklistFilter;
    private final GuestBrowseRateLimitFilter guestBrowseRateLimitFilter;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {

        // CSRF 비활성화 (JWT 사용 시 불필요)
        http.csrf(csrf -> csrf.disable());

        // CORS 설정 적용
        http.cors(cors -> cors.configurationSource(corsConfigurationSource()));

        // 세션 관리: STATELESS (JWT 사용)
        http.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS));

        // URL 권한 설정
        http.authorizeHttpRequests(auth -> auth
            .requestMatchers(
                "/api/v1/auth/**",      // 로그인, 회원가입, 리프레시
                "/oauth2/**",           // OAuth2 엔드포인트
                "/login/**",            // 로그인 페이지 등
                "/swagger-ui/**", "/v3/api-docs/**", // Swagger
                "/actuator/**",          // (선택) 헬스 체크 등
                // Phase 5: PortOne webhook (no JWT — PortOne 서버가 직접 호출하므로 JWT를 가질 수 없다)
                // [버그픽스 B-1.3 · docs/17 §B-1.3 · docs/19 D-17] permitAll은 유지하되 무인증이
                //   아니다: PaymentController.verifyWebhookSecret이 공유 시크릿을 검증하고
                //   prod에서 시크릿 미설정이면 fail-closed로 전량 거부한다.
                //   이 줄을 지우면(=authenticated) PortOne 재시도가 401로 전부 실패해 결제 누락
                //   보완 경로 자체가 죽는다 — 게이트는 컨트롤러 진입부에 두는 것이 맞다.
                "/api/v1/payments/webhook",
                // [D-30 · C-1.5] NICE 본인확인 팝업 콜백 수신. 팝업 컨텍스트에는 JWT가 없으므로
                //   인증을 걸 수 없다. 이 엔드포인트는 GET/POST 양쪽을 받아 **SPA로 302만** 하며,
                //   실제 검증은 인증된 /verify/success가 수행한다(권한 상승 표면 없음).
                //   ⚠ 메서드 무관 블록에 두어야 POST 폼 콜백도 통과한다 — GET 전용 블록으로 옮기지 말 것.
                "/api/v1/verify/callback",
                "/api/v1/webhook/**",
                "/health"                // 헬스 체크 엔드포인트
            ).permitAll()
            // [블록 A 게스트 브라우징] 탐색 공개 — *개별 검수 완료분만* 나열 (일괄 개방 금지,
            //   docs/14 부록 §3). 각 항목은 (1) 응답 DTO에 시크릿 메타·프롬프트성 필드 부재,
            //   (2) 컨트롤러의 익명(null Authentication) 분기, (3) GuestBrowseRateLimitFilter
            //   IP 리밋 커버리지를 확인한 뒤에만 추가한다. 필터의 프리픽스 목록과 동기 유지.
            //   주의: /api/v1/notices는 published 미검사 결함(docs/13 B-12)이 남아 있어
            //   버그 픽스 세션 전까지 게스트 개방 보류.
            .requestMatchers(HttpMethod.GET,
                "/api/v1/lobby/characters",              // 캐릭터 목록 (hidden 필터 검수됨)
                "/api/v1/lobby/characters/*/profile",    // 프로필 (게스트 분기 — PUBLIC만)
                "/api/v1/lobby/feed",                    // 홈 피드 (게스트 안전 DTO)
                "/api/v1/lobby/worlds",                  // 스토리 월드 (게스트 카드 — secretAllowed 제외)
                "/api/v1/lobby/worlds/ugc",              // 승인 UGC 월드 (게스트 카드)
                "/api/v1/theater/lobby/worlds",          // 극장 월드 (게스트 카드 — secretAllowed 제외)
                "/api/v1/theater/lobby/worlds/*",
                "/api/v1/ugc/characters/explore",        // UGC 탐색 (Authentication 미사용·DTO 검수됨)
                "/api/v1/faq"                            // 게시된 FAQ만 반환 (publicList)
            ).permitAll()
            // [Phase 6] 백오피스 — 별도 admin SPA에서 호출. ROLE_ADMIN 만 접근.
            //   authorityPrefix="" + role 클레임이 "ROLE_ADMIN" 문자열이라 hasRole("ADMIN")이 정확히 매칭됨.
            .requestMatchers("/api/v1/admin/**").hasRole("ADMIN")
            .anyRequest().authenticated()
        );

        // Google OAuth2 로그인
        http.oauth2Login(oauth -> oauth
            .successHandler(oAuth2LoginSuccessHandler)
        );

        // JWT 리소스 서버 설정
        http.oauth2ResourceServer(oauth2 -> oauth2
            .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter()))
        );

        // [Phase6/Tier3 / C-2] 토큰 블랙리스트 필터 — Bearer 인증 *이전*에 적용.
        //   로그아웃된 토큰(BL:{jti}) 차단. 인증을 거치기 전이라 무효 토큰 부담 최소화.
        http.addFilterBefore(jwtBlacklistFilter, BearerTokenAuthenticationFilter.class);

        // [블록 A 게스트] 비인증 공개 탐색 IP 레이트리밋 — Bearer 인증 *뒤*에 배치.
        //   판정을 헤더 문자열이 아닌 SecurityContext 인증 여부로 하기 위함이다:
        //   유효 토큰=인증 컨텍스트 존재→면제, 무효 Bearer=401로 도달 불가,
        //   비-Bearer 임의 헤더(Authorization: guest 등)=익명→리밋 적용.
        //   (before 배치+헤더 존재 판정이던 초기 구현은 비-Bearer 헤더 한 줄로 전면 우회됐다 — 적대적 리뷰 P1.)
        http.addFilterAfter(guestBrowseRateLimitFilter, BearerTokenAuthenticationFilter.class);

        // 예외 처리 (401 에러 시 JSON 응답)
        http.exceptionHandling(ex -> ex
            .authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED))
        );

        return http.build();
    }

    /**
     * [Phase6/Tier3 / H-3] PasswordEncoder Bean.
     * AuthService에서 직접 new BCryptPasswordEncoder() 인스턴스화하던 코드를 DI로 전환.
     * strength 12로 명시 (기본 10보다 강함).
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    @Bean
    public JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtGrantedAuthoritiesConverter converter = new JwtGrantedAuthoritiesConverter();
        converter.setAuthoritiesClaimName("role"); // Claims에서 권한 정보 키 (role vs roles 확인 필요)
        converter.setAuthorityPrefix(""); // 이미 ROLE_ 가 붙어있다면 빈 문자열

        JwtAuthenticationConverter jac = new JwtAuthenticationConverter();
        jac.setJwtGrantedAuthoritiesConverter(converter);
        return jac;
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();

        // [변경] 와일드카드(*) 대신 실제 운영되는 프론트엔드 도메인만 엄격하게 허용
        // 필요에 따라 로컬 테스트용(localhost:5173)을 남겨두셔도 됩니다.
        configuration.setAllowedOrigins(List.of(
            "https://lucid-chat.com",         // Vercel 프론트엔드 운영 도메인
            "https://admin.lucid-chat.com",   // [Phase 6] 백오피스 admin SPA (운영)
            "http://localhost:5173",          // 로컬 유저 프론트엔드 테스트용
            "http://localhost:5174"           // [Phase 6] 로컬 admin SPA 테스트용
        ));

        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS", "PATCH"));
        // [Phase6/Tier1A] M-2: 와일드카드 대신 실제 사용하는 헤더만 명시
        configuration.setAllowedHeaders(List.of(
            "Authorization",
            "Content-Type",
            "X-Requested-With",
            "Accept",
            "Cache-Control",
            "ngrok-skip-browser-warning"
        ));
        configuration.setAllowCredentials(true); // 쿠키 및 Authorization 헤더 주고받기 허용
        configuration.setMaxAge(3600L); // Preflight 요청 캐싱

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
}
