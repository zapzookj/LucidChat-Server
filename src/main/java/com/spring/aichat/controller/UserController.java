package com.spring.aichat.controller;

import com.spring.aichat.domain.user.User;
import com.spring.aichat.domain.user.UserRepository;
import com.spring.aichat.dto.user.ToggleSecretModeRequest;
import com.spring.aichat.dto.user.UpdateUserRequest;
import com.spring.aichat.dto.user.UserResponse;
import com.spring.aichat.exception.BusinessException;
import com.spring.aichat.exception.ErrorCode;
import com.spring.aichat.exception.RateLimitException;
import com.spring.aichat.security.ApiRateLimiter;
import com.spring.aichat.service.payment.SecretModeService;
import com.spring.aichat.service.payment.SubscriptionService;
import com.spring.aichat.service.user.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * [Phase 5 Fix] 시크릿 모드 토글 전용 엔드포인트 추가
 *
 * 기존: PATCH /users/update에서 isSecretMode 직접 변경 가능 (취약점)
 * 수정: PATCH /users/secret-mode 전용 엔드포인트로 분리
 *       → SecretModeService.canAccessSecretMode() 검증 필수
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/users")
public class UserController {

    private final UserService userService;
    private final SecretModeService secretModeService;
    private final SubscriptionService subscriptionService;
    private final UserRepository userRepository;
    private final ApiRateLimiter rateLimiter;

    @GetMapping("/me")
    public UserResponse getMyInfo(Authentication authentication) {
        return userService.getMyInfo(authentication.getName());
    }

    @PatchMapping("/update")
    public void updateMyInfo(@RequestBody UpdateUserRequest request,
                             Authentication authentication) {
        if (rateLimiter.checkProfileUpdate(authentication.getName())) {
            throw new RateLimitException("프로필 업데이트가 너무 빠릅니다.", 5);
        }
        userService.updateMyInfo(request, authentication.getName());
    }

    /**
     * [Phase 5 Fix] 시크릿 모드 전용 토글 엔드포인트
     *
     * Body: { "enabled": true, "characterId": 1 }
     *
     * enabled=true:
     *   - characterId 필수
     *   - SecretModeService로 패스/해금/구독 검증
     *   - 검증 실패 시 400 에러
     *
     * enabled=false:
     *   - characterId 불필요
     *   - 즉시 비활성화
     */
    @PatchMapping("/secret-mode")
    public ResponseEntity<Map<String, Object>> toggleSecretMode(
        @RequestBody @Valid ToggleSecretModeRequest request,
        Authentication authentication
    ) {
        userService.toggleSecretMode(
            authentication.getName(),
            Boolean.TRUE.equals(request.enabled()),
            request.characterId()
        );
        return ResponseEntity.ok(Map.of(
            "isSecretMode", Boolean.TRUE.equals(request.enabled())
        ));
    }

    /**
     * 부스트 모드 토글
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

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  [BETA] 베타 테스터 치트 — 프로덕션 전 삭제 예정
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /**
     * 로비 로고 5회 클릭 시 호출.
     * - 성인 인증 완료 처리
     * - 루시드 미드나잇 패스 구독 활성화
     * - paidEnergy 300 충전
     *
     * 이미 적용된 유저에게는 중복 적용하지 않고 안내만 반환.
     */
    @PostMapping("/beta-activate")
    public ResponseEntity<Map<String, Object>> betaActivate(Authentication authentication) {
        User user = findUser(authentication.getName());

        boolean alreadyActivated = user.isSubscriber()
            && user.getSubscriptionTier() == com.spring.aichat.domain.enums.SubscriptionType.LUCID_MIDNIGHT_PASS
            && Boolean.TRUE.equals(user.getIsAdult());

        if (alreadyActivated) {
            return ResponseEntity.ok(Map.of(
                "success", true,
                "message", "이미 베타 테스터 혜택이 적용되어 있습니다!",
                "alreadyActivated", true
            ));
        }

        // 1) 성인 인증 처리 (ciHash는 베타용 더미)
        if (!Boolean.TRUE.equals(user.getIsAdult())) {
            user.completeAdultVerification("BETA_TESTER_" + user.getId());
        }

        // 2) 루시드 미드나잇 패스 활성화
        user.activateSubscription(com.spring.aichat.domain.enums.SubscriptionType.LUCID_MIDNIGHT_PASS);

        // 3) paidEnergy 300 충전
        user.chargePaidEnergy(300);

        userRepository.save(user);

        return ResponseEntity.ok(Map.of(
            "success", true,
            "message", "베타 테스터 혜택이 적용되었습니다!",
            "alreadyActivated", false,
            "energy", user.getEnergy(),
            "paidEnergy", user.getPaidEnergy(),
            "subscriptionTier", user.getSubscriptionTier().name()
        ));
    }
}