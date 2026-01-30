package com.spring.aichat.service.auth;

import com.spring.aichat.domain.enums.AuthProvider;
import com.spring.aichat.domain.user.User;
import com.spring.aichat.domain.user.UserRepository;
import com.spring.aichat.dto.auth.LoginRequest;
import com.spring.aichat.dto.auth.SignupRequest;
import com.spring.aichat.exception.BadRequestException;
import com.spring.aichat.exception.NotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 로컬 회원가입/로그인 비즈니스 로직
 */
@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final JwtTokenService jwtTokenService;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    @Transactional
    public Long signup(SignupRequest req) {
        if (userRepository.existsByUsername(req.username())) {
            throw new BadRequestException("이미 사용 중인 아이디입니다.");
        }
        if (req.email() != null && !req.email().isBlank() && userRepository.existsByEmail(req.email())) {
            throw new BadRequestException("이미 사용 중인 이메일입니다.");
        }

        String hash = passwordEncoder.encode(req.password());
        User user = User.local(req.username(), hash, req.nickname(), req.email());

        return userRepository.save(user).getId();
    }

    @Transactional(readOnly = true)
    public JwtTokenService.TokenResponse login(LoginRequest req) {
        User user = userRepository.findByUsername(req.username())
            .orElseThrow(() -> new NotFoundException("아이디 또는 비밀번호가 올바르지 않습니다."));

        if (user.getProvider() != AuthProvider.LOCAL) {
            throw new BadRequestException("해당 계정은 소셜 로그인으로 가입되었습니다. 구글 로그인을 이용하세요.");
        }

        if (user.getPassword() == null || !passwordEncoder.matches(req.password(), user.getPassword())) {
            throw new NotFoundException("아이디 또는 비밀번호가 올바르지 않습니다.");
        }

        return jwtTokenService.issue(user);
    }
}
