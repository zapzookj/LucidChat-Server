package com.spring.aichat.service.admin;

import com.spring.aichat.domain.enums.SubscriptionType;
import com.spring.aichat.domain.enums.UserStatus;
import com.spring.aichat.domain.user.User;
import com.spring.aichat.domain.user.UserRepository;
import com.spring.aichat.dto.admin.AdminUserDetail;
import com.spring.aichat.dto.admin.AdminUserSummary;
import com.spring.aichat.exception.BusinessException;
import com.spring.aichat.exception.ErrorCode;
import com.spring.aichat.service.audit.AuditLogService;
import com.spring.aichat.service.auth.JwtTokenService;
import com.spring.aichat.service.cache.RedisCacheService;
import com.spring.aichat.service.payment.SubscriptionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 관리자 유저 관리 오케스트레이션.
 *
 * 기존 도메인 메서드(chargePaidEnergy/deductPaidEnergy, SubscriptionService, User.suspend 등)를
 * 재사용하고, 모든 뮤테이션은 AuditLog 로 남긴다. 상태 변경 시 JwtTokenService 로 활성 세션을
 * 즉시 무효화(정지) 또는 차단 해제(재활성)한다.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AdminUserService {

    private final UserRepository userRepository;
    private final SubscriptionService subscriptionService;
    private final JwtTokenService jwtTokenService;
    private final RedisCacheService cacheService;
    private final AuditLogService auditLogService;

    @Transactional(readOnly = true)
    public Page<AdminUserSummary> search(String query, Pageable pageable) {
        Page<User> users = (query == null || query.isBlank())
            ? userRepository.findAll(pageable)
            : userRepository.searchByKeyword(query.trim(), pageable);
        return users.map(AdminUserSummary::from);
    }

    @Transactional(readOnly = true)
    public AdminUserDetail getDetail(Long userId) {
        return AdminUserDetail.from(loadUser(userId));
    }

    @Transactional
    public AdminUserDetail adjustEnergy(String actor, Long userId, int amount, String reason) {
        if (amount == 0) throw new BusinessException(ErrorCode.BAD_REQUEST, "조정량이 0입니다.");
        User user = loadUser(userId);
        if (amount > 0) user.chargePaidEnergy(amount);
        else user.deductPaidEnergy(-amount);
        cacheService.evictUserProfile(user.getUsername());
        auditLogService.record(actor, "ENERGY_ADJUST", "USER", String.valueOf(userId),
            String.format("유료 에너지 %+d (사유: %s) → paid 잔액=%d", amount, reason, user.getPaidEnergy()));
        return AdminUserDetail.from(user);
    }

    @Transactional
    public AdminUserDetail grantSubscription(String actor, Long userId, String tierName, String reason) {
        User user = loadUser(userId);
        SubscriptionType tier = parseTier(tierName);
        // SubscriptionService 를 통해 UserSubscription(active) 을 함께 생성 —
        // 그래야 구독 만료 스케줄러(clearExpiredSubscriptionTiers)가 되돌리지 않는다.
        subscriptionService.activateSubscription(user, tier, "ADMIN_GRANT_" + System.currentTimeMillis());
        auditLogService.record(actor, "SUBSCRIPTION_GRANT", "USER", String.valueOf(userId),
            String.format("구독 %s 부여 (사유: %s)", tier.name(), reason));
        return AdminUserDetail.from(loadUser(userId));
    }

    @Transactional
    public AdminUserDetail clearSubscription(String actor, Long userId, String reason) {
        User user = loadUser(userId);
        subscriptionService.deactivateForUser(user);
        auditLogService.record(actor, "SUBSCRIPTION_CLEAR", "USER", String.valueOf(userId),
            "구독 해제 (사유: " + reason + ")");
        return AdminUserDetail.from(loadUser(userId));
    }

    @Transactional
    public AdminUserDetail changeStatus(String actor, Long userId, UserStatus status, String reason) {
        User user = loadUser(userId);
        switch (status) {
            case SUSPENDED -> {
                user.suspend(reason);
                jwtTokenService.revokeUserSessions(user.getUsername());
            }
            case BANNED -> {
                user.ban(reason);
                jwtTokenService.revokeUserSessions(user.getUsername());
            }
            case ACTIVE -> {
                user.reactivate();
                jwtTokenService.clearUserSessionRevocation(user.getUsername());
            }
        }
        cacheService.evictUserProfile(user.getUsername());
        auditLogService.record(actor, "USER_STATUS_" + status.name(), "USER", String.valueOf(userId),
            String.format("상태 변경 → %s (사유: %s)", status.name(), reason));
        return AdminUserDetail.from(user);
    }

    @Transactional
    public AdminUserDetail releaseAdult(String actor, Long userId, String reason) {
        User user = loadUser(userId);
        user.releaseAdultVerification();
        cacheService.evictUserProfile(user.getUsername());
        auditLogService.record(actor, "ADULT_RELEASE", "USER", String.valueOf(userId),
            "성인 인증 강제 해제 / CI 반납 (사유: " + reason + ")");
        return AdminUserDetail.from(user);
    }

    private User loadUser(Long userId) {
        return userRepository.findById(userId)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "유저를 찾을 수 없습니다: " + userId));
    }

    private SubscriptionType parseTier(String tierName) {
        SubscriptionType tier;
        try {
            tier = SubscriptionType.valueOf(tierName);
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "알 수 없는 구독 티어: " + tierName);
        }
        if (tier == SubscriptionType.LUCID_PASS_PREMIUM) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "사용 불가(deprecated) 구독 티어입니다.");
        }
        return tier;
    }
}
