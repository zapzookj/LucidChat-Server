package com.spring.aichat.controller;

import com.spring.aichat.config.JwtProperties;
import com.spring.aichat.domain.chat.ChatRoom;
import com.spring.aichat.domain.chat.ChatRoomRepository;
import com.spring.aichat.domain.user.User;
import com.spring.aichat.domain.user.UserRepository;
import com.spring.aichat.dto.auth.AuthResponse;
import com.spring.aichat.dto.auth.LoginRequest;
import com.spring.aichat.dto.auth.SignupRequest;
import com.spring.aichat.exception.BusinessException;
import com.spring.aichat.exception.ErrorCode;
import com.spring.aichat.service.auth.AuthService;
import com.spring.aichat.service.auth.JwtTokenService;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * 인증 API
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

    @PostMapping("/signup")
    public AuthResponse signup(@RequestBody @Valid SignupRequest req,
                               HttpServletResponse response) {

        // Service가 AuthResult(AuthResponse + RefreshToken) 반환
        AuthService.AuthResult result = authService.signup(req);

        // Refresh Token 쿠키 설정
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
        // (reissue 내부에서 검증하지만, 유저 조회를 위해 먼저 추출)
        String username = jwtTokenService.extractUsername(refreshToken);

        // 2. 토큰 재발급 (RTR)
        JwtTokenService.TokenPair tokenPair = jwtTokenService.reissue(refreshToken);

        // 3. 유저 정보 조회
        User user = userRepository.findByUsername(username)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "사용자를 찾을 수 없습니다."));

        // 4. 채팅방 정보 조회
        ChatRoom room = chatRoomRepository.findByUser_Id(user.getId())
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "활성화된 채팅방이 없습니다."));

        // 5. User Map 구성
        Map<String, Object> userMap = new HashMap<>();
        userMap.put("id", user.getId());
        userMap.put("username", user.getUsername());
        userMap.put("nickname", user.getNickname());
        userMap.put("energy", user.getEnergy());
        // 필요 시 role, provider 등 추가

        // 6. AuthResponse 생성 (기존 구조 준수)
        AuthResponse authResponse = new AuthResponse(
            tokenPair.accessToken(),
            props.accessTokenTtlSeconds(), // expiresIn
            true,                          // roomId
            userMap                                // user
        );

        // 7. 새 Refresh Token 쿠키 설정
        setRefreshTokenCookie(response, tokenPair.refreshToken());

        return ResponseEntity.ok(authResponse);
    }

    @PostMapping("/login")
    public AuthResponse login(@RequestBody @Valid LoginRequest req,
                              HttpServletResponse response) {
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
            String username = jwtTokenService.extractUsername(accessToken); // AccessToken에서 추출

            // Redis 블랙리스트 처리 및 Refresh Token 삭제
            jwtTokenService.logout(accessToken, username);
        }

        // 쿠키 삭제
        Cookie cookie = new Cookie("refresh_token", null);
        cookie.setHttpOnly(true);
        cookie.setSecure(false); // 배포 시 true
        cookie.setPath("/");
        cookie.setMaxAge(0);
        response.addCookie(cookie);

        return ResponseEntity.ok().build();
    }

    private void setRefreshTokenCookie(HttpServletResponse response, String refreshToken) {
        Cookie cookie = new Cookie("refresh_token", refreshToken);
        cookie.setHttpOnly(true);
        cookie.setSecure(false); // 배포 시 true
        cookie.setPath("/");
        cookie.setMaxAge((int) props.refreshTokenTtlSeconds());
        response.addCookie(cookie);
    }
}
