package com.spring.aichat.config;

import com.spring.aichat.service.auth.OAuth2LoginSuccessHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
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
public class SecurityConfig {

    private final OAuth2LoginSuccessHandler oAuth2LoginSuccessHandler;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {

        http.csrf(csrf -> csrf.disable());

        http.cors(cors -> cors.configurationSource(corsConfigurationSource()));

        http.authorizeHttpRequests(auth -> auth
            .requestMatchers(
                "/api/v1/auth/**",
                "/oauth2/**", "/login/**",
                "/swagger-ui/**", "/v3/api-docs/**"
            ).permitAll()
            .anyRequest().authenticated()
        );

        // Google OAuth2 로그인 (성공 시 JWT 발급)
        http.oauth2Login(oauth -> oauth
            .successHandler(oAuth2LoginSuccessHandler)
        );

        // Bearer JWT 인증
        http.oauth2ResourceServer(oauth2 -> oauth2
            .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter()))
        );

        // 인증 실패 시 JSON 친화 응답(간단 버전)
        http.exceptionHandling(ex -> ex
            .authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED))
        );

        return http.build();
    }

    @Bean
    public JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtGrantedAuthoritiesConverter converter = new JwtGrantedAuthoritiesConverter();
        converter.setAuthoritiesClaimName("roles");
        converter.setAuthorityPrefix(""); // roles에 이미 ROLE_ 접두어가 있음

        JwtAuthenticationConverter jac = new JwtAuthenticationConverter();
        jac.setJwtGrantedAuthoritiesConverter(converter);
        return jac;
    }

    // ★ CORS 허용 정책 빈 등록
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(List.of("http://localhost:5173", "http://localhost:3000")); // 프론트 주소
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("*"));
        configuration.setAllowCredentials(true); // 인증 정보(쿠키/헤더) 포함 허용

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
}
