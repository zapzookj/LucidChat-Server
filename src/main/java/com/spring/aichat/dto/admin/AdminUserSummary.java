package com.spring.aichat.dto.admin;

import com.spring.aichat.domain.user.User;

import java.time.LocalDateTime;

/** 관리자 유저 목록 행. */
public record AdminUserSummary(
    Long id,
    String username,
    String nickname,
    String email,
    String status,
    String subscriptionTier,
    boolean isAdult,
    LocalDateTime createdAt
) {
    public static AdminUserSummary from(User u) {
        return new AdminUserSummary(
            u.getId(),
            u.getUsername(),
            u.getNickname(),
            u.getEmail(),
            u.getStatus() != null ? u.getStatus().name() : null,
            u.getSubscriptionTier() != null ? u.getSubscriptionTier().name() : null,
            Boolean.TRUE.equals(u.getIsAdult()),
            u.getCreatedAt()
        );
    }
}
