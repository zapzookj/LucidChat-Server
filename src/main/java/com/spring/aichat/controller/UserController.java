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

    // [블록 B 선행 픽스 — docs/13 B-2·docs/14 §G-3] beta-activate 엔드포인트 제거.
    // NICE 없이 isAdult+미드나잇 구독을 부여하는 성인인증 우회로, 페르소나 나이 게이트까지
    // 무력화하는 P0 착취면이었다. 프론트 트리거(로고 5회 클릭)와 세트로 제거.
}