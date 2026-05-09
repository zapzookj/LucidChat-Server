package com.spring.aichat.config;

import com.spring.aichat.service.auth.OAuth2LoginSuccessHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.client.OAuth2AuthorizationSuccessHandler;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
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
                "/api/v1/payments/webhook",   // Phase 5: PortOne webhook (no JWT)
                "/api/v1/webhook/**",
                "/health"                // 헬스 체크 엔드포인트
            ).permitAll()
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

        // 예외 처리 (401 에러 시 JSON 응답)
        http.exceptionHandling(ex -> ex
            .authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED))
        );

        return http.build();
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
            "https://lucid-chat.com",       // Vercel 프론트엔드 운영 도메인
            "http://localhost:5173"         // 로컬 프론트엔드 테스트용
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
