package com.spring.aichat.domain.user;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/** [2026-08-04 페르소나 풀 패키지] 유저 페르소나 카드 Repository. */
public interface UserPersonaRepository extends JpaRepository<UserPersona, Long> {

    List<UserPersona> findByUserIdOrderByUpdatedAtDesc(Long userId);

    Optional<UserPersona> findByIdAndUserId(Long id, Long userId);

    long countByUserId(Long userId);
}
