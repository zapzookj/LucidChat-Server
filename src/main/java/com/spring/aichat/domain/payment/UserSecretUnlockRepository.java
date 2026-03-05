package com.spring.aichat.domain.payment;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface UserSecretUnlockRepository extends JpaRepository<UserSecretUnlock, Long> {

    boolean existsByUser_IdAndCharacter_Id(Long userId, Long characterId);

    List<UserSecretUnlock> findByUser_Id(Long userId);
}