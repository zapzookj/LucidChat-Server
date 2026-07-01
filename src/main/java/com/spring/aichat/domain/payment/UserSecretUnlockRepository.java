package com.spring.aichat.domain.payment;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface UserSecretUnlockRepository extends JpaRepository<UserSecretUnlock, Long> {

    boolean existsByUser_IdAndCharacter_Id(Long userId, Long characterId);

    List<UserSecretUnlock> findByUser_Id(Long userId);

    /** [Phase 6] 환불 회수용 — 주문번호로 영구 해금 조회 */
    Optional<UserSecretUnlock> findByMerchantUid(String merchantUid);
}