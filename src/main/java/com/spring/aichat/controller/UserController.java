package com.spring.aichat.controller;

import com.spring.aichat.domain.user.User;
import com.spring.aichat.domain.user.UserRepository;
import com.spring.aichat.dto.user.UpdateUserRequest;
import com.spring.aichat.dto.user.UserResponse;
import com.spring.aichat.exception.BusinessException;
import com.spring.aichat.exception.ErrorCode;
import com.spring.aichat.service.payment.SecretModeService;
import com.spring.aichat.service.payment.SubscriptionService;
import com.spring.aichat.service.user.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Phase 5 BM 패키지: 유저 API
 *
 * [추가 엔드포인트]
 * PATCH /api/v1/users/boost            -> 부스트 모드 토글
 * GET   /api/v1/users/secret-status    -> 시크릿 모드 접근 상태 (캐릭터별)
 * GET   /api/v1/users/subscription     -> 구독 상태
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/users")
public class UserController {

    private final UserService userService;
    private final SecretModeService secretModeService;
    private final SubscriptionService subscriptionService;
    private final UserRepository userRepository;

    @GetMapping("/me")
    public UserResponse getMyInfo(Authentication authentication) {
        return userService.getMyInfo(authentication.getName());
    }

    @PatchMapping("/update")
    public void updateMyInfo(@RequestBody UpdateUserRequest request,
                             Authentication authentication) {
        userService.updateMyInfo(request, authentication.getName());
    }

    /**
     * 부스트 모드 토글
     *
     * Body: { "enabled": true/false }
     */
    @PatchMapping("/boost")
    public ResponseEntity<Map<String, Object>> toggleBoostMode(
        @RequestBody Map<String, Boolean> body,
        Authentication authentication
    ) {
        boolean enabled = Boolean.TRUE.equals(body.get("enabled"));
        userService.toggleBoostMode(authentication.getName(), enabled);
        return ResponseEntity.ok(Map.of("boostMode", enabled));
    }

    /**
     * 특정 캐릭터에 대한 시크릿 모드 접근 상태 조회
     *
     * 응답 예시:
     * {
     *   "isAdult": true,
     *   "hasMidnightPass": false,
     *   "hasPermanentUnlock": true,
     *   "has24hPass": false,
     *   "accessReason": "GRANTED",
     *   "canAccess": true
     * }
     */
    @GetMapping("/secret-status")
    public ResponseEntity<SecretModeService.SecretModeStatus> getSecretStatus(
        @RequestParam Long characterId,
        Authentication authentication
    ) {
        User user = findUser(authentication.getName());
        SecretModeService.SecretModeStatus status = secretModeService.getStatus(user, characterId);
        return ResponseEntity.ok(status);
    }

    /**
     * 구독 상태 조회
     *
     * 응답 예시:
     * {
     *   "active": true,
     *   "tier": "LUCID_PASS",
     *   "expiresAt": "2026-04-03T12:00:00"
     * }
     */
    @GetMapping("/subscription")
    public ResponseEntity<Map<String, Object>> getSubscriptionStatus(
        Authentication authentication
    ) {
        User user = findUser(authentication.getName());
        return subscriptionService.getActiveSubscription(user.getId())
            .map(sub -> ResponseEntity.ok(Map.<String, Object>of(
                "active", true,
                "tier", sub.getType().name(),
                "displayName", sub.getType().getDisplayName(),
                "expiresAt", sub.getExpiresAt().toString()
            )))
            .orElse(ResponseEntity.ok(Map.of(
                "active", false,
                "tier", "",
                "displayName", "",
                "expiresAt", ""
            )));
    }

    private User findUser(String username) {
        return userRepository.findByUsername(username)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "User not found"));
    }
}