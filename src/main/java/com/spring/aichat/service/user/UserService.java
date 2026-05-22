package com.spring.aichat.service.user;

import com.spring.aichat.domain.enums.SubscriptionType;
import com.spring.aichat.domain.user.User;
import com.spring.aichat.domain.user.UserRepository;
import com.spring.aichat.dto.user.UpdateUserRequest;
import com.spring.aichat.dto.user.UserResponse;
import com.spring.aichat.exception.BusinessException;
import com.spring.aichat.exception.ErrorCode;
import com.spring.aichat.security.PromptInjectionGuard;
import com.spring.aichat.service.cache.RedisCacheService;
import com.spring.aichat.service.payment.SecretModeService;
import com.spring.aichat.service.payment.SubscriptionService;
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
    /** [Polish · Beta Fix] 베타 활성화 시 UserSubscription 레코드 생성을 위해 위임 */
    private final SubscriptionService subscriptionService;
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

    /**
     * [Polish · Beta Fix] 베타 테스터 혜택 단일 트랜잭션 활성화.
     *
     * <h3>이전 버그</h3>
     * UserController.betaActivate가 트랜잭션 없이 다음 흐름으로 동작했음:
     * <pre>
     *   User user = findUser(...);            // detached
     *   user.completeAdultVerification(...);  // detached.isAdult = true (메모리만)
     *   subscriptionService.activateSubscription(user, ...);  // @Transactional 진입
     *     ├─ UserSubscription.create(user, ...)
     *     ├─ subscriptionRepository.save(subscription)
     *     │     └─ ⚠️ JPA가 subscription.user(detached)를 발견 → 영속 컨텍스트로 끌어들이며
     *     │        DB에서 user를 fresh fetch → 우리가 메모리에서 set한 isAdult=true 손실
     *     ├─ user.activateSubscription(type)  ← 이 시점 user는 이미 fresh persistent
     *     └─ userRepository.save(user)        ← isAdult 누락 상태로 commit
     * </pre>
     * 결과: DB에 subscriptionTier만 저장되고 isAdult는 false로 남음. 다른 구독 기능은
     * 동작하지만 시크릿 모드(isAdult 검증)만 거부됐다.
     *
     * <h3>Fix</h3>
     * 메서드 전체를 {@code @Transactional}로 감싸 단일 영속 컨텍스트에서 처리:
     * <ul>
     *   <li>{@code findUser()}로 영속 객체 획득 (detached가 아님)</li>
     *   <li>{@code completeAdultVerification()} → dirty checking으로 자동 반영</li>
     *   <li>{@code subscriptionService.activateSubscription()} 내부의 fresh fetch가
     *       발생하더라도 같은 영속 객체를 받음 (PERSISTENCE_CONTEXT 동일)</li>
     *   <li>{@code chargePaidEnergy(300)} → dirty checking</li>
     *   <li>트랜잭션 commit 시점에 모든 변경 한 번에 flush</li>
     * </ul>
     */
    @Transactional
    public void activateBetaTester(String username) {
        User user = findUser(username);

        // 1) 성인 인증 처리 (이미 인증된 경우 skip)
        if (!Boolean.TRUE.equals(user.getIsAdult())) {
            user.completeAdultVerification("BETA_TESTER_" + user.getId());
        }

        // 2) 루시드 미드나잇 패스 활성화 — 같은 영속 컨텍스트 안에서 호출되므로
        //    내부 fresh fetch가 발생해도 우리의 isAdult 변경 사항이 동일 객체에 반영되어 있음.
        //    SubscriptionService.activateSubscription은 REQUIRED로 같은 트랜잭션을 사용한다.
        subscriptionService.activateSubscription(
            user,
            SubscriptionType.LUCID_MIDNIGHT_PASS,
            "BETA_TESTER_" + user.getId() + "_" + System.currentTimeMillis()
        );

        // 3) paidEnergy 300 충전 — dirty checking
        user.chargePaidEnergy(300);

        // 트랜잭션 commit은 메서드 종료 시 자동 수행. 명시적 save 불필요.
        // SubscriptionService가 내부적으로 evictUserProfile을 호출하지만 안전을 위해 한 번 더.
        cacheService.evictUserProfile(username);
    }

    private User findUser(String username) {
        return userRepository.findByUsername(username)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "User not found"));
    }
}