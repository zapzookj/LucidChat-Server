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
import com.spring.aichat.service.cache.RedisCacheService;
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
    /** [Polish · P0] beta-activate 후 user profile 캐시 무효화에 사용 */
    private final RedisCacheService cacheService;

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
     * - 루시드 미드나잇 패스 구독 활성화 (UserSubscription 레코드 + User.subscriptionTier)
     * - paidEnergy 300 충전
     * - user profile 캐시 무효화 (다음 /me 호출에서 신선한 값 반환)
     *
     * 이미 적용된 유저에게는 중복 적용하지 않고 안내만 반환.
     *
     * [Polish · P0]
     *  기존 버그 fix:
     *  1) user.activateSubscription() 직접 호출 → UserSubscription 레코드 미생성.
     *     SecretModeService.hasMidnightPass()는 UserSubscription을 조회하므로,
     *     베타 활성화 후에도 시크릿 모드 접근이 거부됐다.
     *     → SubscriptionService.activateSubscription()으로 위임 (UserSub + User 동기화).
     *  2) cacheService.evictUserProfile() 누락 → /me 응답이 30분간 stale.
     *     → save 직후 evict 추가.
     *  3) 응답에 갱신된 UserResponse 전체를 포함 → 프론트가 별도 GET /me 없이도
     *     AuthContext를 즉시 동기화 가능.
     */
    @PostMapping("/beta-activate")
    public ResponseEntity<Map<String, Object>> betaActivate(Authentication authentication) {
        User user = findUser(authentication.getName());

        boolean alreadyActivated = user.isSubscriber()
            && user.getSubscriptionTier() == com.spring.aichat.domain.enums.SubscriptionType.LUCID_MIDNIGHT_PASS
            && Boolean.TRUE.equals(user.getIsAdult());

        if (alreadyActivated) {
            // 캐시는 혹시 모를 stale 상태 대비 evict (저비용 무해 작업)
            cacheService.evictUserProfile(authentication.getName());
            return ResponseEntity.ok(Map.of(
                "success", true,
                "message", "이미 베타 테스터 혜택이 적용되어 있습니다!",
                "alreadyActivated", true,
                "user", userService.getMyInfo(authentication.getName())
            ));
        }

        // 1) 성인 인증 처리 (ciHash는 베타용 더미)
        if (!Boolean.TRUE.equals(user.getIsAdult())) {
            user.completeAdultVerification("BETA_TESTER_" + user.getId());
        }

        // 2) 루시드 미드나잇 패스 활성화 — SubscriptionService 위임으로
        //    UserSubscription 레코드와 User.subscriptionTier를 함께 동기화.
        subscriptionService.activateSubscription(
            user,
            com.spring.aichat.domain.enums.SubscriptionType.LUCID_MIDNIGHT_PASS,
            "BETA_TESTER_" + user.getId() + "_" + System.currentTimeMillis()
        );

        // 3) paidEnergy 300 충전
        user.chargePaidEnergy(300);

        userRepository.save(user);

        // 4) 캐시 무효화 — 다음 /me에서 신선한 값
        cacheService.evictUserProfile(authentication.getName());

        // 5) 갱신된 UserResponse를 응답에 포함 (프론트 즉시 동기화)
        UserResponse refreshed = userService.getMyInfo(authentication.getName());

        return ResponseEntity.ok(Map.of(
            "success", true,
            "message", "베타 테스터 혜택이 적용되었습니다!",
            "alreadyActivated", false,
            "user", refreshed
        ));
    }
}