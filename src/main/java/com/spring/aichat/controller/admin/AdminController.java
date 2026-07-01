package com.spring.aichat.controller.admin;

import com.spring.aichat.domain.enums.SubscriptionType;
import com.spring.aichat.domain.user.User;
import com.spring.aichat.domain.user.UserRepository;
import com.spring.aichat.exception.BusinessException;
import com.spring.aichat.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * 백오피스 공통 — 관리자 자기 정보.
 *
 * /api/v1/admin/** 은 SecurityConfig 에서 hasRole('ADMIN') 게이트 하에 있으므로,
 * 이 엔드포인트에 도달했다는 것 자체가 호출자가 ROLE_ADMIN 임을 의미한다.
 * admin SPA는 로그인 직후 이 엔드포인트로 관리자 권한을 확인한다(UserResponse에는 roles가 없음).
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/admin")
public class AdminController {

    private final UserRepository userRepository;

    @GetMapping("/me")
    public Map<String, Object> me(Authentication authentication) {
        User user = userRepository.findByUsername(authentication.getName())
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "관리자 계정을 찾을 수 없습니다."));
        return Map.of(
            "username", user.getUsername(),
            "nickname", user.getNickname() != null ? user.getNickname() : user.getUsername(),
            "roles", user.getRoles()
        );
    }

    /** 구독 티어 메타(관리자 구독 부여 UI 드롭다운용). deprecated 티어는 제외. */
    @GetMapping("/meta/subscription-types")
    public List<Map<String, Object>> subscriptionTypes() {
        return Arrays.stream(SubscriptionType.values())
            .filter(t -> t != SubscriptionType.LUCID_PASS_PREMIUM)
            .map(t -> Map.<String, Object>of(
                "name", t.name(),
                "displayName", t.getDisplayName(),
                "monthlyPriceKrw", t.getMonthlyPriceKrw(),
                "adultOnly", t.isAdultOnly()))
            .toList();
    }
}

