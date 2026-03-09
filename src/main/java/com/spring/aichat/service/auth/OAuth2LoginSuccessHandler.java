package com.spring.aichat.service.auth;

import com.spring.aichat.config.JwtProperties;
import com.spring.aichat.domain.chat.ChatRoomRepository;
import com.spring.aichat.domain.enums.AuthProvider;
import com.spring.aichat.domain.user.User;
import com.spring.aichat.domain.user.UserRepository;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;
import java.util.Map;

/**
 * [Phase 5] 소셜 로그인 통합 성공 핸들러
 *
 * [지원 프로바이더]
 * - Google: OidcUser (OpenID Connect)
 * - Kakao: OAuth2User (일반 OAuth2)
 * - Naver: OAuth2User (일반 OAuth2)
 *
 * [변경 사항]
 * - OnboardingService 의존 제거 (Phase 4.5에서 로비 기반으로 전환됨)
 * - 멀티 프로바이더 지원: registrationId로 분기
 * - /oauth2/success로 리다이렉트 시 access_token만 전달
 *   → OAuthSuccessPage에서 /users/me API로 유저 정보 조회 (버그 수정)
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class OAuth2LoginSuccessHandler implements AuthenticationSuccessHandler {

    private final UserRepository userRepository;
    private final ChatRoomRepository chatRoomRepository;
    private final JwtTokenService jwtTokenService;
    private final JwtProperties props;

    @Value("${auth.oauth2.success-redirect:}")
    private String successRedirect;

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
                                        Authentication authentication) throws IOException {

        // 프로바이더 식별
        String registrationId = "google"; // 기본값
        if (authentication instanceof OAuth2AuthenticationToken oauthToken) {
            registrationId = oauthToken.getAuthorizedClientRegistrationId();
        }

        // 프로바이더별 유저 Upsert
        User user = switch (registrationId) {
            case "google" -> upsertGoogleUser(authentication);
            case "kakao" -> upsertKakaoUser(authentication);
            case "naver" -> upsertNaverUser(authentication);
            default -> {
                log.error("[OAUTH] Unknown provider: {}", registrationId);
                response.sendRedirect("/login?error=unknown_provider");
                yield null;
            }
        };

        if (user == null) return;

        // JWT 발급
        JwtTokenService.TokenPair tokenPair = jwtTokenService.issueTokenPair(
            user.getUsername(), "ROLE_USER");

        setRefreshTokenCookie(response, tokenPair.refreshToken());

        log.info("[OAUTH] Login success: provider={}, username={}, userId={}",
            registrationId, user.getUsername(), user.getId());

        // 프론트엔드로 리다이렉트 (access_token만 전달)
        if (successRedirect != null && !successRedirect.isBlank()) {
            String url = UriComponentsBuilder.fromUriString(successRedirect)
                .queryParam("access_token", tokenPair.accessToken())
                .build(true)
                .toUriString();
            response.sendRedirect(url);
        } else {
            response.setStatus(HttpServletResponse.SC_OK);
            response.setContentType("application/json");
            response.getWriter().write("{\"accessToken\":\"" + tokenPair.accessToken() + "\"}");
        }
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  Google (OpenID Connect)
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    @Transactional
    protected User upsertGoogleUser(Authentication authentication) {
        OidcUser oidcUser = (OidcUser) authentication.getPrincipal();
        String email = oidcUser.getEmail();
        String sub = oidcUser.getSubject();
        String name = oidcUser.getFullName() != null ? oidcUser.getFullName() : "유저";

        return userRepository.findByProviderAndProviderId(AuthProvider.GOOGLE, sub)
            .orElseGet(() -> {
                String username = email != null ? email : ("google_" + sub.substring(0, 8));
                if (userRepository.existsByUsername(username)) {
                    username = "google_" + sub.substring(0, 12);
                }
                User created = User.google(username, name, email, sub);
                return userRepository.save(created);
            });
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  Kakao
    //  응답 구조: { "id": 12345, "kakao_account": { "email": "...", "profile": { "nickname": "..." } } }
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    @Transactional
    protected User upsertKakaoUser(Authentication authentication) {
        OAuth2User oAuth2User = (OAuth2User) authentication.getPrincipal();
        Map<String, Object> attributes = oAuth2User.getAttributes();

        String providerId = String.valueOf(attributes.get("id"));

        @SuppressWarnings("unchecked")
        Map<String, Object> kakaoAccount = (Map<String, Object>) attributes.getOrDefault("kakao_account", Map.of());
        @SuppressWarnings("unchecked")
        Map<String, Object> profile = (Map<String, Object>) kakaoAccount.getOrDefault("profile", Map.of());

        String email = (String) kakaoAccount.get("email");
        String nickname = (String) profile.getOrDefault("nickname", "유저");

        return userRepository.findByProviderAndProviderId(AuthProvider.KAKAO, providerId)
            .orElseGet(() -> {
                String username = email != null ? email : ("kakao_" + providerId.substring(0, 8));
                if (userRepository.existsByUsername(username)) {
                    username = "kakao_" + providerId;
                }
                User created = new User();
                setUserFields(created, username, nickname, email, AuthProvider.KAKAO, providerId);
                return userRepository.save(created);
            });
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  Naver
    //  응답 구조: { "response": { "id": "...", "email": "...", "nickname": "..." } }
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    @Transactional
    protected User upsertNaverUser(Authentication authentication) {
        OAuth2User oAuth2User = (OAuth2User) authentication.getPrincipal();
        Map<String, Object> attributes = oAuth2User.getAttributes();

        @SuppressWarnings("unchecked")
        Map<String, Object> naverResponse = (Map<String, Object>) attributes.getOrDefault("response", attributes);

        String providerId = (String) naverResponse.get("id");
        String email = (String) naverResponse.get("email");
        String nickname = (String) naverResponse.getOrDefault("nickname",
            naverResponse.getOrDefault("name", "유저"));

        return userRepository.findByProviderAndProviderId(AuthProvider.NAVER, providerId)
            .orElseGet(() -> {
                String username = email != null ? email : ("naver_" + providerId.substring(0, 8));
                if (userRepository.existsByUsername(username)) {
                    username = "naver_" + providerId;
                }
                User created = new User();
                setUserFields(created, username, (String) nickname, email, AuthProvider.NAVER, providerId);
                return userRepository.save(created);
            });
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    private void setUserFields(User user, String username, String nickname, String email,
                               AuthProvider provider, String providerId) {
        try {
            java.lang.reflect.Field f;
            f = User.class.getDeclaredField("username"); f.setAccessible(true); f.set(user, username);
            f = User.class.getDeclaredField("nickname"); f.setAccessible(true); f.set(user, nickname);
            f = User.class.getDeclaredField("email"); f.setAccessible(true); f.set(user, email);
            f = User.class.getDeclaredField("provider"); f.setAccessible(true); f.set(user, provider);
            f = User.class.getDeclaredField("providerId"); f.setAccessible(true); f.set(user, providerId);
        } catch (Exception e) {
            throw new IllegalStateException("소셜 유저 생성 실패", e);
        }
    }

    private void setRefreshTokenCookie(HttpServletResponse response, String refreshToken) {
        Cookie cookie = new Cookie("refresh_token", refreshToken);
        cookie.setHttpOnly(true);
        cookie.setSecure(false); // 프로덕션에서 true
        cookie.setPath("/");
        cookie.setMaxAge((int) props.refreshTokenTtlSeconds());
        response.addCookie(cookie);
    }
}