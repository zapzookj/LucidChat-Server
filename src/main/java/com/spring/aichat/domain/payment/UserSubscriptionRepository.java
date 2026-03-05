package com.spring.aichat.domain.payment;

import com.spring.aichat.domain.enums.SubscriptionType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface UserSubscriptionRepository extends JpaRepository<UserSubscription, Long> {

    /** 유저의 활성 구독 조회 (최대 1개) */
    Optional<UserSubscription> findByUser_IdAndActiveTrue(Long userId);

    /** 유저의 활성 구독 존재 여부 */
    boolean existsByUser_IdAndActiveTrue(Long userId);

    /** 만료된 활성 구독 일괄 비활성화 (스케줄러용) */
    @Modifying
    @Query("UPDATE UserSubscription s SET s.active = false WHERE s.active = true AND s.expiresAt < :now")
    int deactivateExpiredSubscriptions(@Param("now") LocalDateTime now);

    /** 유저의 전체 구독 이력 */
    List<UserSubscription> findByUser_IdOrderByCreatedAtDesc(Long userId);

    /** 활성 구독자 User ID 목록 (에너지 리젠 스케줄러용) */
    @Query("SELECT s.user.id FROM UserSubscription s WHERE s.active = true AND s.expiresAt > :now")
    List<Long> findActiveSubscriberUserIds(@Param("now") LocalDateTime now);
}