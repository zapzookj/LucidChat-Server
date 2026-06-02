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
 *
 * [Phase 7-V2 Story / BM 피벗] 시크릿 모드 user-global 전환:
 *   - GET /secret-status?characterId=X → characterId required=false 완화 + user-global 응답
 *   - 시크릿 해금/패스/구독은 *유저 단위*로 작동 (캐릭터별 X)
 *   - V1 frontend의 기존 호출 (`?characterId=N`)도 그대로 작동, 단 응답은 user-global
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
     *   - characterId 필수 (V1 호환 — V2에서도 방의 임의 히로인 id로 충분)
     *   - SecretModeService로 패스/해금/구독 검증 (user-global)
     *   - 검증 실패 시 400 에러
     *
     * enabled=false:
     *   - characterId 불필요
     *   - 즉시 비활성화
     *
     * <p>[Phase 7-V2 BM 피벗] characterId 파라미터는 V1 시그니처 호환을 위해 유지하나
     * SecretModeService 내부에서 무시됨 (user-global 검증). 향후 cleanup 작업에서
     * characterId 폐기 가능.
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
     * 시크릿 모드 접근 상태 조회.
     *
     * <p>[Phase 7-V2 BM 피벗] characterId 파라미터는 V1 호환을 위해 유지하나 *무시됨*.
     * 시크릿 BM이 user-global로 전환되어 *모든* 캐릭터에 대해 동일 상태 반환.
     *
     * <p>V1 호출 (`?characterId=5`) — 그대로 작동, user-global 응답
     * <p>V2 호출 (파라미터 없음) — characterId required=false 덕에 정상 작동
     */
    @GetMapping("/secret-status")
    public ResponseEntity<SecretModeService.SecretModeStatus> getSecretStatus(
        @RequestParam(required = false) Long characterId,
        Authentication authentication
    ) {
        User user = findUser(authentication.getName());
        // [BM 피벗] 1-arg user-global 메서드 호출. characterId는 무시.
        SecretModeService.SecretModeStatus status = secretModeService.getStatus(user);
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
     * <h3>Polish · Beta Fix (시크릿 모드 디버깅)</h3>
     * <p>이전 버전은 컨트롤러에서 직접 detached User 객체를 조작하고 SubscriptionService를
     * 호출했는데, SubscriptionService가 새 트랜잭션을 열어 detached User를 통해 fresh fetch를
     * 일으키는 바람에 메모리상 isAdult=true 변경이 손실됐다.
     *
     * <p>다른 구독 기능은 잘 동작했지만 시크릿 모드(isAdult 검증)만 거부되던 증상의 원인.
     *
     * <p>Fix: 모든 변경을 {@link UserService#activateBetaTester(String)}에 위임하여
     * 단일 {@code @Transactional} 안에서 처리한다. 트랜잭션 commit 후에 getMyInfo()를
     * 호출해야 신선한 값을 반환할 수 있으므로 컨트롤러 단에서 그 순서를 보장한다.
     */
    @PostMapping("/beta-activate")
    public ResponseEntity<Map<String, Object>> betaActivate(Authentication authentication) {
        String username = authentication.getName();
        User user = findUser(username);

        boolean alreadyActivated = user.isSubscriber()
            && user.getSubscriptionTier() == com.spring.aichat.domain.enums.SubscriptionType.LUCID_MIDNIGHT_PASS
            && Boolean.TRUE.equals(user.getIsAdult());

        if (alreadyActivated) {
            // 캐시는 혹시 모를 stale 상태 대비 evict (저비용 무해 작업)
            cacheService.evictUserProfile(username);
            return ResponseEntity.ok(Map.of(
                "success", true,
                "message", "이미 베타 테스터 혜택이 적용되어 있습니다!",
                "alreadyActivated", true,
                "user", userService.getMyInfo(username)
            ));
        }

        // 활성화는 단일 트랜잭션으로 위임. 메서드 반환 시점에 트랜잭션 commit 완료.
        userService.activateBetaTester(username);

        // 트랜잭션 commit 직후 fresh UserResponse fetch (캐시 evict는 위에서 끝남).
        UserResponse refreshed = userService.getMyInfo(username);

        return ResponseEntity.ok(Map.of(
            "success", true,
            "message", "베타 테스터 혜택이 적용되었습니다!",
            "alreadyActivated", false,
            "user", refreshed
        ));
    }
}