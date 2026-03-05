package com.spring.aichat.service.user;

import com.spring.aichat.domain.user.User;
import com.spring.aichat.domain.user.UserRepository;
import com.spring.aichat.dto.user.UpdateUserRequest;
import com.spring.aichat.dto.user.UserResponse;
import com.spring.aichat.exception.BusinessException;
import com.spring.aichat.exception.ErrorCode;
import com.spring.aichat.service.cache.RedisCacheService;
import com.spring.aichat.service.payment.SecretModeService;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Phase 5 BM 패키지 통합
 *
 * [변경]
 * - getMyInfo(): 구독/부스트/freeEnergyMax 필드 포함
 * - updateMyInfo(): 시크릿 모드 토글 시 SecretModeService 권한 검증
 * - toggleBoostMode(): 부스트 모드 토글 API
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;
    private final RedisCacheService cacheService;
    private final SecretModeService secretModeService;

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

    @Transactional
    public void updateMyInfo(UpdateUserRequest request, String username) {
        User user = findUser(username);

        if (request.nickname() != null) {
            user.updateNickName(request.nickname());
        }
        if (request.profileDescription() != null) {
            user.updateProfileDescription(request.profileDescription());
        }

        // 시크릿 모드 토글은 별도 API로 분리 권장
        // 하위 호환: isSecretMode가 true로 요청되면 검증
        if (Boolean.TRUE.equals(request.isSecretMode())) {
            // 시크릿 모드 활성화 시 성인 인증 필수 체크 (캐릭터 무관)
            if (!Boolean.TRUE.equals(user.getIsAdult())) {
                throw new BusinessException(ErrorCode.VERIFICATION_UNDERAGE,
                    "Adult verification required for secret mode");
            }
        }
        user.updateIsSecretMode(
            request.isSecretMode() != null ? request.isSecretMode() : false
        );

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