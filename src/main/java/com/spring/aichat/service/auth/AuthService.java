package com.spring.aichat.service.auth;

import com.spring.aichat.config.JwtProperties;
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
 *
 * [Phase 4.5] 단일 채팅방(roomId) 가정 제거
 * - 로그인 시 더 이상 기본 방을 자동 생성하지 않음
 * - 유저는 로비에서 직접 캐릭터 + 모드를 선택하여 방 생성
 * - hasExistingRooms 플래그로 로비 UI가 Continue 메뉴 활성화 여부를 판단
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final ChatRoomRepository chatRoomRepository;
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
        String email = (req.email() == null || req.email().isBlank()) ? null : req.email().trim();
        User user = User.local(req.username(), hash, req.nickname(), email);
        User saved = userRepository.save(user);

        // [Phase 4.5] 더 이상 기본 방을 자동 생성하지 않음 — 로비에서 직접 선택
        JwtTokenService.TokenPair tokenPair = jwtTokenService.issueTokenPair(saved.getUsername(), "ROLE_USER");

        AuthResponse response = new AuthResponse(
            tokenPair.accessToken(),
            props.accessTokenTtlSeconds(),
            false,  // 신규 가입이므로 기존 방 없음
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

        // [Phase 4.5] 기존 방 존재 여부 확인 (Continue 메뉴 활성화 판단용)
        boolean hasRooms = chatRoomRepository.countByUser_Id(user.getId()) > 0;

        JwtTokenService.TokenPair tokenPair = jwtTokenService.issueTokenPair(user.getUsername(), "ROLE_USER");

        AuthResponse response = new AuthResponse(
            tokenPair.accessToken(),
            props.accessTokenTtlSeconds(),
            hasRooms,
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