package com.spring.aichat.controller;

import com.spring.aichat.dto.auth.LoginRequest;
import com.spring.aichat.dto.auth.SignupRequest;
import com.spring.aichat.service.auth.AuthService;
import com.spring.aichat.service.auth.JwtTokenService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 인증 API
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthService authService;

    @PostMapping("/signup")
    public Long signup(@RequestBody @Valid SignupRequest req) {
        return authService.signup(req);
    }

    @PostMapping("/login")
    public JwtTokenService.TokenResponse login(@RequestBody @Valid LoginRequest req) {
        return authService.login(req);
    }
}
