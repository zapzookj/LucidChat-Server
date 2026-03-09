package com.spring.aichat.service.user;

import com.spring.aichat.domain.user.User;
import com.spring.aichat.domain.user.UserRepository;
import com.spring.aichat.dto.user.UpdateUserRequest;
import com.spring.aichat.dto.user.UserResponse;
import com.spring.aichat.exception.BusinessException;
import com.spring.aichat.exception.ErrorCode;
import com.spring.aichat.security.PromptInjectionGuard;
import com.spring.aichat.service.cache.RedisCacheService;
import com.spring.aichat.service.payment.SecretModeService;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * [Phase 5 Fix] 시크릿 모드 우회 취약점 수정
 *
 * [기존 취약점]
 * updateMyInfo()에서 isSecretMode: true를 직접 설정 가능
 * → 성인 인증만 통과하면 결제(패스/해금) 없이 시크릿 모드 활성화
 *
 * [수정 내용]
 * 1. updateMyInfo(): isSecretMode 처리 완전 제거
 * 2. toggleSecretMode(): 전용 메서드 신설
 *    - enabled=true: SecretModeService.canAccessSecretMode() 검증 필수
 *    - enabled=false: 검증 없이 즉시 비활성화
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;
    private final RedisCacheService cacheService;
    private final SecretModeService secretModeService;
    private final PromptInjectionGuard injectionGuard;

    public UserResponse getMyInfo(String username) {
        return cacheService.getUserProfile(username, UserResponse.class)
            .orElseGet(() -> {
                User user = findUser(username);

                UserResponse response = new UserResponse(
                    user.getId(),
                    user.getUsername(),
                    user.getNickname(),
                    user.getEmail(),
                    user.getProfileDescription(),
                    user.getIsSecretMode(),
                    user.getEnergy(),
                    user.getFreeEnergy(),
                    user.getPaidEnergy(),
                    user.getFreeEnergyMax(),
                    Boolean.TRUE.equals(user.getIsAdult()),
                    user.getSubscriptionTier() != null ? user.getSubscriptionTier().name() : null,
                    Boolean.TRUE.equals(user.getBoostMode())
                );

                cacheService.cacheUserProfile(username, response);
                return response;
            });
    }

    /**
     * 프로필 업데이트 (닉네임, 페르소나)
     *
     * [Phase 5 Fix] isSecretMode 처리 완전 제거
     * 시크릿 모드 토글은 toggleSecretMode() 전용 메서드로만 가능
     */
    @Transactional
    public void updateMyInfo(UpdateUserRequest request, String username) {
        User user = findUser(username);

        if (request.nickname() != null) {
            user.updateNickName(injectionGuard.sanitizeNickname(request.nickname()));
        }
        if (request.profileDescription() != null) {
            user.updateProfileDescription(injectionGuard.sanitizePersona(request.profileDescription()));
        }

        // ⚠️ isSecretMode 처리 의도적으로 제거 — toggleSecretMode()로 분리

        userRepository.save(user);
        cacheService.evictUserProfile(username);
    }

    /**
     * [Phase 5 Fix] 시크릿 모드 전용 토글
     *
     * enabled=true:
     *   - characterId 필수
     *   - SecretModeService.canAccessSecretMode() 검증
     *   - 실패 시 BusinessException (NEED_PURCHASE)
     *
     * enabled=false:
     *   - 검증 없이 즉시 비활성화
     */
    @Transactional
    public void toggleSecretMode(String username, boolean enabled, Long characterId) {
        User user = findUser(username);

        if (enabled) {
            // 활성화 시 반드시 캐릭터별 접근 권한 검증
            if (characterId == null) {
                throw new BusinessException(ErrorCode.BAD_REQUEST,
                    "characterId is required to enable secret mode");
            }

            if (!secretModeService.canAccessSecretMode(user, characterId)) {
                // 구체적 거절 사유 판별
                if (!Boolean.TRUE.equals(user.getIsAdult())) {
                    throw new BusinessException(ErrorCode.VERIFICATION_UNDERAGE,
                        "Adult verification required for secret mode");
                }
                throw new BusinessException(ErrorCode.BAD_REQUEST,
                    "Secret mode access denied: no valid pass for this character");
            }

            log.info("[SECRET_TOGGLE] Enabled: user={}, charId={}", username, characterId);
        } else {
            log.info("[SECRET_TOGGLE] Disabled: user={}", username);
        }

        user.updateIsSecretMode(enabled);
        userRepository.save(user);
        cacheService.evictUserProfile(username);
    }

    /**
     * 부스트 모드 토글
     */
    @Transactional
    public void toggleBoostMode(String username, boolean enabled) {
        User user = findUser(username);
        user.updateBoostMode(enabled);
        userRepository.save(user);
        cacheService.evictUserProfile(username);
        log.info("[BOOST] user={}, boostMode={}", username, enabled);
    }

    private User findUser(String username) {
        return userRepository.findByUsername(username)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "User not found"));
    }
}