package com.spring.aichat.dto.admin;

import com.spring.aichat.domain.user.User;

import java.time.LocalDateTime;
import java.util.Set;

/** 관리자 유저 상세. 계정 상태 · 에너지(free/paid) · 구독 · 성인 인증 · 권한 전부 노출. */
public record AdminUserDetail(
    Long id,
    String username,
    String nickname,
    String email,
    String profileDescription,
    String provider,
    Set<String> roles,
    String status,
    String statusReason,
    LocalDateTime statusChangedAt,
    int energy,
    int freeEnergy,
    int paidEnergy,
    int freeEnergyMax,
    String subscriptionTier,
    boolean boostMode,
    boolean isAdult,
    LocalDateTime adultVerifiedAt,
    boolean isSecretMode,
    LocalDateTime createdAt
) {
    public static AdminUserDetail from(User u) {
        return new AdminUserDetail(
            u.getId(),
            u.getUsername(),
            u.getNickname(),
            u.getEmail(),
            u.getProfileDescription(),
            u.getProvider() != null ? u.getProvider().name() : null,
            u.getRoles(),
            u.getStatus() != null ? u.getStatus().name() : null,
            u.getStatusReason(),
            u.getStatusChangedAt(),
            u.getEnergy(),
            u.getFreeEnergy(),
            u.getPaidEnergy(),
            u.getFreeEnergyMax(),
            u.getSubscriptionTier() != null ? u.getSubscriptionTier().name() : null,
            Boolean.TRUE.equals(u.getBoostMode()),
            Boolean.TRUE.equals(u.getIsAdult()),
            u.getAdultVerifiedAt(),
            Boolean.TRUE.equals(u.getIsSecretMode()),
            u.getCreatedAt()
        );
    }
}
