package com.spring.aichat.domain.user;

import com.spring.aichat.domain.enums.AuthProvider;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {

    boolean existsByUsername(String username);
    boolean existsByEmail(String email);
    boolean existsByCiHash(String ciHash);

    Optional<User> findByUsername(String username);
    Optional<User> findByEmail(String email);
    Optional<User> findByProviderAndProviderId(AuthProvider provider, String providerId);

    /**
     * 비구독자 에너지 회복: +1 per 10min, max 30
     * (subscriptionTier IS NULL인 유저만 대상)
     */
    @Modifying
    @Query("UPDATE User u SET u.freeEnergy = CASE WHEN u.freeEnergy < 30 THEN u.freeEnergy + 1 ELSE 30 END " +
        "WHERE u.subscriptionTier IS NULL")
    int regenFreeUserEnergy();

    /**
     * 구독자 에너지 회복: +1 per 5min, max 100
     * (subscriptionTier IS NOT NULL인 유저만 대상)
     */
    @Modifying
    @Query("UPDATE User u SET u.freeEnergy = CASE WHEN u.freeEnergy < 100 THEN u.freeEnergy + 1 ELSE 100 END " +
        "WHERE u.subscriptionTier IS NOT NULL")
    int regenSubscriberEnergy();

    /**
     * 만료된 구독 유저의 subscriptionTier 초기화
     * (SubscriptionService.deactivateExpired()에서 호출)
     *
     * UserSubscription이 비활성화된 유저 중
     * 더 이상 활성 구독이 없는 유저의 tier를 null로.
     * freeEnergy가 30 초과하면 30으로 클램핑.
     */
    @Modifying
    @Query("UPDATE User u SET u.subscriptionTier = NULL, " +
        "u.freeEnergy = CASE WHEN u.freeEnergy > 30 THEN 30 ELSE u.freeEnergy END " +
        "WHERE u.subscriptionTier IS NOT NULL " +
        "AND u.id NOT IN (SELECT s.user.id FROM UserSubscription s WHERE s.active = true)")
    int clearExpiredSubscriptionTiers();
}