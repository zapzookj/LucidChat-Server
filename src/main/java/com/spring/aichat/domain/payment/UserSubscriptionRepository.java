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
    /**
     * [D-4.5] 활성 구독 조회 — <b>List</b>다. 종전 {@code Optional} 반환은 활성 2행(레이스·인덱스 부재)이 생기는 순간
     * {@code IncorrectResultSizeDataAccessException} → 500으로 구독 조회 경로 전부(재결제·환불 회수·isSubscriber·
     * /users/subscription)를 만료일까지 죽였다. 호출측은 첫 행(만료일 최장)을 쓰고 2행 이상이면 error 로그로 degrade한다.
     * V33 부분 유니크가 붙은 뒤에는 실제로 1행 이하지만, 인덱스 적용 전/실패 환경에서도 조회가 죽지 않게 한다.
     */
    List<UserSubscription> findByUser_IdAndActiveTrueOrderByExpiresAtDesc(Long userId);

    /** [Phase 6] 환불 회수용 — 주문번호로 구독 조회 */
    Optional<UserSubscription> findByMerchantUid(String merchantUid);

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