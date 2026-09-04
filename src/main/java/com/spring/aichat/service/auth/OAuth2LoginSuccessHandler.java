package com.spring.aichat.service.auth;

import com.spring.aichat.config.JwtProperties;
import com.spring.aichat.domain.chat.ChatRoomRepository;
import com.spring.aichat.domain.enums.AuthProvider;
import com.spring.aichat.domain.user.User;
import com.spring.aichat.domain.user.UserRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;
import java.util.Arrays;
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

    /** [E-7.1.a] upsert를 별도 빈으로 분리 — 자기호출이라 @Transactional이 안 먹던 문제를 푼다. */
    private final SocialUserUpsertService upsertService;
    private final ChatRoomRepository chatRoomRepository;
    private final JwtTokenService jwtTokenService;
    private final JwtProperties props;
    private final Environment env;

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
        // [E-7.1.b] 종전에는 이 구간에 예외 처리가 전무해, upsert가 던지면 로그인 화면 대신
        //   원시 500 페이지가 떴다(유저는 무엇이 잘못됐는지도 모른다). 전체를 감싼다.
        SocialUserUpsertService.UpsertResult result;
        try {
            result = switch (registrationId) {
                case "google" -> upsertGoogleUser(authentication);
                case "kakao" -> upsertKakaoUser(authentication);
                case "naver" -> upsertNaverUser(authentication);
                default -> {
                    log.error("[OAUTH] Unknown provider: {}", registrationId);
                    redirectToLoginWithError(response, "unknown_provider", null);
                    yield null;
                }
            };
        } catch (Exception e) {
            log.error("[OAUTH] upsert 실패 | provider={}", registrationId, e);
            redirectToLoginWithError(response, "login_failed", null);
            return;
        }

        if (result == null) return;

        // [E-7.1.a] 이메일 충돌 — decisions_confirmed §B #19 (B) provider 안내 리다이렉트.
        //   종전에는 여기서 email UNIQUE에 무방비로 INSERT해 500이 났고, 재시도해도 같은 지점에서
        //   죽어 그 유저는 그 provider로 영구히 로그인할 수 없었다.
        //   ⚠ 이메일 자체는 쿼리스트링에 싣지 않는다 — 개인정보가 URL·리퍼러·로그에 남는다.
        if (result.isEmailConflict()) {
            log.warn("[OAUTH] 이메일 충돌로 로그인 중단 | 시도={} | 기존={}",
                registrationId, result.conflictWith());
            redirectToLoginWithError(response, "email_in_use",
                result.conflictWith() != null ? result.conflictWith().name().toLowerCase() : null);
            return;
        }

        User user = result.user();

        // [Phase 6] 정지/차단 계정은 소셜 로그인으로도 토큰 발급 차단.
        if (user.isAccessBlocked()) {
            log.warn("[OAUTH] Blocked account login attempt: username={}, status={}",
                user.getUsername(), user.getStatus());
            if (successRedirect != null && !successRedirect.isBlank()) {
                String url = UriComponentsBuilder.fromUriString(successRedirect)
                    .replacePath("/login").replaceQuery("error=account_suspended")
                    .build(true).toUriString();
                response.sendRedirect(url);
            } else {
                response.sendError(HttpServletResponse.SC_FORBIDDEN, "Account suspended");
            }
            return;
        }

        // JWT 발급
        // [Phase6/Tier3 / H-2] role 하드코딩 제거 → DB의 user.getRoles()에서 추출.
        String role = jwtTokenService.extractPrimaryRole(user);
        JwtTokenService.TokenPair tokenPair = jwtTokenService.issueTokenPair(
            user.getUsername(), role);

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

    /**
     * [E-7.1.a] 로그인 실패를 화면으로 안내한다.
     *
     * <p>기존 {@code account_suspended} 경로와 같은 형태({@code .replacePath("/login")}).
     * ⚠ 이메일·닉네임 등 개인정보는 절대 쿼리스트링에 싣지 않는다 — URL은 리퍼러·서버 로그·
     * 브라우저 히스토리에 남는다. provider 이름(google/kakao/naver)만 전달한다.
     */
    private void redirectToLoginWithError(HttpServletResponse response, String errorCode, String provider)
        throws IOException {
        String query = "error=" + errorCode + (provider != null ? "&provider=" + provider : "");
        if (successRedirect != null && !successRedirect.isBlank()) {
            String url = UriComponentsBuilder.fromUriString(successRedirect)
                .replacePath("/login").replaceQuery(query)
                .build(true).toUriString();
            response.sendRedirect(url);
        } else {
            // successRedirect 미설정(로컬 등) — SPA 오리진을 모르므로 상대경로로 폴백한다.
            response.sendRedirect("/login?" + query);
        }
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  Google (OpenID Connect)
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    private SocialUserUpsertService.UpsertResult upsertGoogleUser(Authentication authentication) {
        OidcUser oidcUser = (OidcUser) authentication.getPrincipal();
        String email = oidcUser.getEmail();
        String sub = oidcUser.getSubject();
        String name = oidcUser.getFullName() != null ? oidcUser.getFullName() : "유저";
        return doUpsert(AuthProvider.GOOGLE, sub, email, name, "google_");
    }

    /**
     * [E-7.1.a] upsert 위임 + 레이스 폴백.
     *
     * <p>동시 요청이 같은 계정을 두 번 INSERT하면 {@code DataIntegrityViolationException}이 난다.
     * ⚠ 그 재조회를 upsert의 트랜잭션 <b>안에서</b> 하면 안 된다 — 제약 위반으로 rollback-only가
     * 마킹돼 이후 쿼리가 그대로 죽는다. 그래서 예외를 밖으로 받아 {@code REQUIRES_NEW} 조회로 푼다.
     */
    private SocialUserUpsertService.UpsertResult doUpsert(AuthProvider provider, String providerId,
                                                          String email, String nickname, String prefix) {
        try {
            return upsertService.upsert(provider, providerId, email, nickname, prefix);
        } catch (DataIntegrityViolationException race) {
            log.warn("[OAUTH] upsert 레이스 — 새 트랜잭션에서 재조회 | provider={}", provider);
            return upsertService.findExisting(provider, providerId, email);
        }
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  Kakao
    //  응답 구조: { "id": 12345, "kakao_account": { "email": "...", "profile": { "nickname": "..." } } }
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    private SocialUserUpsertService.UpsertResult upsertKakaoUser(Authentication authentication) {
        OAuth2User oAuth2User = (OAuth2User) authentication.getPrincipal();
        Map<String, Object> attributes = oAuth2User.getAttributes();

        String providerId = String.valueOf(attributes.get("id"));

        @SuppressWarnings("unchecked")
        Map<String, Object> kakaoAccount = (Map<String, Object>) attributes.getOrDefault("kakao_account", Map.of());
        @SuppressWarnings("unchecked")
        Map<String, Object> profile = (Map<String, Object>) kakaoAccount.getOrDefault("profile", Map.of());

        String email = (String) kakaoAccount.get("email");
        String nickname = (String) profile.getOrDefault("nickname", "유저");

        return doUpsert(AuthProvider.KAKAO, providerId, email, nickname, "kakao_");
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  Naver
    //  응답 구조: { "response": { "id": "...", "email": "...", "nickname": "..." } }
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    private SocialUserUpsertService.UpsertResult upsertNaverUser(Authentication authentication) {
        OAuth2User oAuth2User = (OAuth2User) authentication.getPrincipal();
        Map<String, Object> attributes = oAuth2User.getAttributes();

        @SuppressWarnings("unchecked")
        Map<String, Object> naverResponse = (Map<String, Object>) attributes.getOrDefault("response", attributes);

        String providerId = (String) naverResponse.get("id");
        String email = (String) naverResponse.get("email");
        String nickname = (String) naverResponse.getOrDefault("nickname",
            naverResponse.getOrDefault("name", "유저"));

        return doUpsert(AuthProvider.NAVER, providerId, email, (String) nickname, "naver_");
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    // [E-7.1.a] setUserFields는 SocialUserUpsertService로 옮겼다 — 여기 남기면 두 벌이 갈린다(§2-6 취지).

    // [Phase6/Tier1A] C-3: Refresh Token 쿠키에 Secure(prod) + SameSite=Strict 적용
    private void setRefreshTokenCookie(HttpServletResponse response, String refreshToken) {
        ResponseCookie cookie = ResponseCookie.from("refresh_token", refreshToken)
            .httpOnly(true)
            .secure(isProdProfile())
            .sameSite("Strict")
            .path("/")
            .maxAge(props.refreshTokenTtlSeconds())
            .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }

    private boolean isProdProfile() {
        return Arrays.asList(env.getActiveProfiles()).contains("prod");
    }
}