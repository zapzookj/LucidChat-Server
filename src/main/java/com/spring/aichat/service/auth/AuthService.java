package com.spring.aichat.service.auth;

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

/**
 * 로컬 회원가입/로그인 비즈니스 로직
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final OnboardingService onboardingService;
    private final JwtTokenService jwtTokenService;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    @Transactional
    public AuthResponse signup(SignupRequest req) {
        if (userRepository.existsByUsername(req.username())) {
            throw new BadRequestException("이미 사용 중인 아이디입니다.");
        }
        if (req.email() != null && !req.email().isBlank() && userRepository.existsByEmail(req.email())) {
            throw new BadRequestException("이미 사용 중인 이메일입니다.");
        }

        String hash = passwordEncoder.encode(req.password());
        User user = User.local(req.username(), hash, req.nickname(), req.email());

        User saved = userRepository.save(user);

        ChatRoom room = onboardingService.getOrCreateDefaultRoom(saved);

        var token = jwtTokenService.issue(saved);
        return new AuthResponse(token.accessToken(), token.expiresIn(), room.getId(), token.user());
    }

    @Transactional
    public AuthResponse login(LoginRequest req) {
        User user = userRepository.findByUsername(req.username())
            .orElseThrow(() -> new NotFoundException("아이디 또는 비밀번호가 올바르지 않습니다."));

        if (user.getProvider() != AuthProvider.LOCAL) {
            throw new BadRequestException("해당 계정은 소셜 로그인으로 가입되었습니다. 구글 로그인을 이용하세요.");
        }

        if (user.getPassword() == null || !passwordEncoder.matches(req.password(), user.getPassword())) {
            throw new NotFoundException("아이디 또는 비밀번호가 올바르지 않습니다.");
        }

        // 안전망: 혹시 방이 없으면 만들어줌
        var token = jwtTokenService.issue(user);
        return new AuthResponse(token.accessToken(), token.expiresIn(), user.getId(), token.user());
    }
}
