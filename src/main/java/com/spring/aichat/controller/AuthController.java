package com.spring.aichat.controller;

import com.spring.aichat.config.JwtProperties;
import com.spring.aichat.domain.chat.ChatRoomRepository;
import com.spring.aichat.domain.user.User;
import com.spring.aichat.domain.user.UserRepository;
import com.spring.aichat.dto.auth.AuthResponse;
import com.spring.aichat.dto.auth.LoginRequest;
import com.spring.aichat.dto.auth.SignupRequest;
import com.spring.aichat.exception.BusinessException;
import com.spring.aichat.exception.ErrorCode;
import com.spring.aichat.exception.RateLimitException;
import com.spring.aichat.security.ApiRateLimiter;
import com.spring.aichat.service.auth.AuthService;
import com.spring.aichat.service.auth.JwtTokenService;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * 인증 API
 *
 * [Phase 5.5-Fix] refresh 엔드포인트 NonUniqueResultException 수정
 *   - findByUser_Id (단건) → countByUser_Id (개수) 전환
 *   - Phase 4.5 로비 시스템 이후 유저당 방이 N개이므로 단건 조회 불가
 *   - AuthResponse.hasExistingRooms는 boolean이므로 존재 여부만 확인하면 충분
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthService authService;
    private final JwtTokenService jwtTokenService;
    private final UserRepository userRepository;
    private final ChatRoomRepository chatRoomRepository;
    private final JwtProperties props;
    private final ApiRateLimiter rateLimiter;

    @PostMapping("/signup")
    public AuthResponse signup(@RequestBody @Valid SignupRequest req,
                               HttpServletRequest httpReq,
                               HttpServletResponse response) {
        String clientIp = extractClientIp(httpReq);
        if (rateLimiter.checkSignup(clientIp)) {
            throw new RateLimitException("회원가입 시도가 너무 빈번합니다.", 60);
        }
        AuthService.AuthResult result = authService.signup(req);
        setRefreshTokenCookie(response, result.refreshToken());
        return result.response();
    }

    @PostMapping("/refresh")
    public ResponseEntity<AuthResponse> refresh(
        @CookieValue(value = "refresh_token", required = false) String refreshToken,
        HttpServletResponse response
    ) {
        if (refreshToken == null) {
            throw new IllegalArgumentException("Refresh Token이 없습니다.");
        }

        // 1. 토큰 검증 및 username 추출
        String username = jwtTokenService.extractUsername(refreshToken);

        // 2. 토큰 재발급 (RTR)
        JwtTokenService.TokenPair tokenPair = jwtTokenService.reissue(refreshToken);

        // 3. 유저 정보 조회
        User user = userRepository.findByUsername(username)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "사용자를 찾을 수 없습니다."));

        // 4. [Fix] 채팅방 존재 여부만 확인 (단건 조회 → 개수 조회)
        //    Phase 4.5 이후 유저당 채팅방이 N개이므로 findByUser_Id (Optional) 사용 불가
        boolean hasExistingRooms = chatRoomRepository.countByUser_Id(user.getId()) > 0;

        // 5. User Map 구성
        Map<String, Object> userMap = new HashMap<>();
        userMap.put("id", user.getId());
        userMap.put("username", user.getUsername());
        userMap.put("nickname", user.getNickname());
        userMap.put("energy", user.getEnergy());

        // 6. AuthResponse 생성
        AuthResponse authResponse = new AuthResponse(
            tokenPair.accessToken(),
            props.accessTokenTtlSeconds(),
            hasExistingRooms,   // [Fix] 기존 방 존재 여부 (boolean)
            userMap
        );

        // 7. 새 Refresh Token 쿠키 설정
        setRefreshTokenCookie(response, tokenPair.refreshToken());

        return ResponseEntity.ok(authResponse);
    }

    @PostMapping("/login")
    public AuthResponse login(@RequestBody @Valid LoginRequest req,
                              HttpServletRequest httpReq,
                              HttpServletResponse response) {
        String clientIp = extractClientIp(httpReq);
        if (rateLimiter.checkLogin(clientIp)) {
            throw new RateLimitException("로그인 시도가 너무 빈번합니다. 1분 후 다시 시도해주세요.", 60);
        }

        AuthService.AuthResult result = authService.login(req);
        setRefreshTokenCookie(response, result.refreshToken());
        return result.response();
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(
        @RequestHeader("Authorization") String bearerToken,
        @CookieValue(value = "refresh_token", required = false) String refreshToken,
        HttpServletResponse response
    ) {
        if (bearerToken != null && bearerToken.startsWith("Bearer ")) {
            String accessToken = bearerToken.substring(7);
            String username = jwtTokenService.extractUsername(accessToken);
            jwtTokenService.logout(accessToken, username);
        }

        Cookie cookie = new Cookie("refresh_token", null);
        cookie.setHttpOnly(true);
        cookie.setSecure(false);
        cookie.setPath("/");
        cookie.setMaxAge(0);
        response.addCookie(cookie);

        return ResponseEntity.ok().build();
    }

    private void setRefreshTokenCookie(HttpServletResponse response, String refreshToken) {
        Cookie cookie = new Cookie("refresh_token", refreshToken);
        cookie.setHttpOnly(true);
        cookie.setSecure(false);
        cookie.setPath("/");
        cookie.setMaxAge((int) props.refreshTokenTtlSeconds());
        response.addCookie(cookie);
    }

    private String extractClientIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            return xff.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}