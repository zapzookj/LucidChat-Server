package com.spring.aichat.service.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.spring.aichat.config.JwtProperties;
import com.spring.aichat.domain.chat.ChatRoom;
import com.spring.aichat.domain.enums.AuthProvider;
import com.spring.aichat.domain.user.User;
import com.spring.aichat.domain.user.UserRepository;
import com.spring.aichat.dto.auth.AuthResponse;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;
import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;

/**
 * 구글 로그인 성공 처리
 * - OidcUser(email, sub) 기반으로 회원 upsert
 * - 우리 서비스 JWT 발급 후
 *   1) success-redirect가 있으면 리다이렉트 (?access_token=...)
 *   2) 없으면 JSON으로 반환
 */
@Component
@RequiredArgsConstructor
public class OAuth2LoginSuccessHandler implements AuthenticationSuccessHandler {

    private final UserRepository userRepository;
    private final OnboardingService onboardingService;
    private final JwtTokenService jwtTokenService;
    private final JwtProperties props;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${auth.oauth2.success-redirect:}")
    private String successRedirect;

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
                                        Authentication authentication) throws IOException, ServletException {

        OidcUser oidcUser = (OidcUser) authentication.getPrincipal();
        User user = upsertGoogleMember(oidcUser);

        ChatRoom room = onboardingService.getOrCreateDefaultRoom(user);
        JwtTokenService.TokenPair tokenPair = jwtTokenService.issueTokenPair(user.getUsername(), "ROLE_USER");

        Map<String, Object> userMap = new HashMap<>();
        userMap.put("id", user.getId());
        userMap.put("username", user.getUsername());
        userMap.put("nickname", user.getNickname());
        userMap.put("energy", user.getEnergy());

        AuthResponse payload = new AuthResponse(
            tokenPair.accessToken(),
            props.accessTokenTtlSeconds(),
            false,
            userMap
        );

        setRefreshTokenCookie(response, tokenPair.refreshToken());

        if (successRedirect != null && !successRedirect.isBlank()) {
            String url = UriComponentsBuilder.fromUriString(successRedirect)
                .queryParam("access_token", tokenPair.accessToken())
                .queryParam("room_id", room.getId()) // 편의상 추가
                .build(true)
                .toUriString();

            response.sendRedirect(url);
            return;
        }

        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        objectMapper.writeValue(response.getWriter(), payload);
    }

    private void setRefreshTokenCookie(HttpServletResponse response, String refreshToken) {
        Cookie cookie = new Cookie("refresh_token", refreshToken);
        cookie.setHttpOnly(true);
        cookie.setSecure(false); // 배포(HTTPS) 환경에서는 true로 변경 필요
        cookie.setPath("/");
        cookie.setMaxAge((int) props.refreshTokenTtlSeconds());
        response.addCookie(cookie);
    }

    @Transactional
    protected User upsertGoogleMember(OidcUser oidcUser) {
        String email = oidcUser.getEmail();
        String sub = oidcUser.getSubject();   // providerId
        String name = oidcUser.getFullName() != null ? oidcUser.getFullName() : "google-user";

        // 1) provider+providerId 우선
        return userRepository.findByProviderAndProviderId(AuthProvider.GOOGLE, sub)
            .orElseGet(() -> {
                // 2) email이 이미 있으면 그 계정에 provider 정보 연결
                if (email != null && userRepository.existsByEmail(email)) {
                    User existing = userRepository.findByEmail(email).orElseThrow();
                    // 로컬 계정과 이메일이 겹치는 경우 정책은 서비스마다 다름. 여기선 "연결"로 처리
                    // (원하면 여기서 예외 던져서 충돌 방지해도 됨)
                    return userRepository.save(linkGoogle(existing, sub));
                }

                // 3) 신규 생성
                String username = (email != null) ? email : ("google_" + sub.substring(0, 8));
                // username 중복 방지
                if (userRepository.existsByUsername(username)) {
                    username = "google_" + sub.substring(0, 12);
                }

                User created = User.google(username, name, email, sub);
                return userRepository.save(created);
            });
    }

    private User linkGoogle(User user, String providerId) {
        // 단순 연결 (setter를 안 쓴다면 별도 메서드로 변경 권장)
        try {
            Field provider = User.class.getDeclaredField("provider");
            Field pid = User.class.getDeclaredField("providerId");
            provider.setAccessible(true);
            pid.setAccessible(true);
            provider.set(user, AuthProvider.GOOGLE);
            pid.set(user, providerId);
            return user;
        } catch (Exception e) {
            throw new IllegalStateException("구글 계정 연결 실패", e);
        }
    }
}
