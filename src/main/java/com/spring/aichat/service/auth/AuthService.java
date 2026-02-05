package com.spring.aichat.service.auth;

import com.spring.aichat.config.JwtProperties;
import com.spring.aichat.domain.chat.ChatRoom;
import com.spring.aichat.domain.chat.ChatRoomRepository;
import com.spring.aichat.domain.enums.AuthProvider;
import com.spring.aichat.domain.user.User;
import com.spring.aichat.domain.user.UserRepository;
import com.spring.aichat.dto.auth.AuthResponse;
import com.spring.aichat.dto.auth.LoginRequest;
import com.spring.aichat.dto.auth.SignupRequest;
import com.spring.aichat.exception.BadRequestException;
import com.spring.aichat.exception.NotFoundException;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

/**
 * 로컬 회원가입/로그인 비즈니스 로직
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final ChatRoomRepository chatRoomRepository;
    private final OnboardingService onboardingService;
    private final JwtTokenService jwtTokenService;
    private final JwtProperties props;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    /**
     * 서비스 결과 반환용 DTO (Controller에서 쿠키 설정을 위해 RefreshToken이 필요함)
     */
    public record AuthResult(AuthResponse response, String refreshToken) {}

    @Transactional
    public AuthResult signup(SignupRequest req) {
        if (userRepository.existsByUsername(req.username())) {
            throw new BadRequestException("이미 사용 중인 아이디입니다.");
        }
        if (req.email() != null && !req.email().isBlank() && userRepository.existsByEmail(req.email())) {
            throw new BadRequestException("이미 사용 중인 이메일입니다.");
        }

        String hash = passwordEncoder.encode(req.password());

        // 빈 문자열 "" 은 null 처리. DB 버그 방지용
        String email = (req.email() == null || req.email().isBlank()) ? null : req.email().trim();
        User user = User.local(req.username(), hash, req.nickname(), email);
        User saved = userRepository.save(user);

        ChatRoom room = onboardingService.getOrCreateDefaultRoom(saved);

        JwtTokenService.TokenPair tokenPair = jwtTokenService.issueTokenPair(saved.getUsername(), "ROLE_USER");
        AuthResponse response = new AuthResponse(
            tokenPair.accessToken(),
            props.accessTokenTtlSeconds(),
            room.getId(),
            createUserMap(saved)
        );

        return new AuthResult(response, tokenPair.refreshToken());
    }

    @Transactional
    public AuthResult login(LoginRequest req) {
        User user = userRepository.findByUsername(req.username())
            .orElseThrow(() -> new NotFoundException("아이디 또는 비밀번호가 올바르지 않습니다."));

        if (user.getProvider() != AuthProvider.LOCAL) {
            throw new BadRequestException("해당 계정은 소셜 로그인으로 가입되었습니다. 구글 로그인을 이용하세요.");
        }

        if (user.getPassword() == null || !passwordEncoder.matches(req.password(), user.getPassword())) {
            throw new NotFoundException("아이디 또는 비밀번호가 올바르지 않습니다.");
        }

        // [FIX] 기존 코드 버그 수정 (user.getId() -> room.getId())
        ChatRoom room = chatRoomRepository.findByUser_Id(user.getId())
            .orElseGet(() -> onboardingService.getOrCreateDefaultRoom(user)); // 안전망

        JwtTokenService.TokenPair tokenPair = jwtTokenService.issueTokenPair(user.getUsername(), "ROLE_USER");

        AuthResponse response = new AuthResponse(
            tokenPair.accessToken(),
            props.accessTokenTtlSeconds(),
            room.getId(),
            createUserMap(user)
        );

        return new AuthResult(response, tokenPair.refreshToken());
    }

    private Map<String, Object> createUserMap(User user) {
        Map<String, Object> map = new HashMap<>();
        map.put("id", user.getId());
        map.put("username", user.getUsername());
        map.put("nickname", user.getNickname());
        map.put("energy", user.getEnergy());
        return map;
    }
}
