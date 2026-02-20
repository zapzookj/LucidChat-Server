package com.spring.aichat.domain.achievement;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * [Phase 4.4] Achievement Repository
 */
public interface AchievementRepository extends JpaRepository<Achievement, Long> {

    List<Achievement> findByUserIdOrderByUnlockedAtDesc(Long userId);

    Optional<Achievement> findByUserIdAndCode(Long userId, String code);

    boolean existsByUserIdAndCode(Long userId, String code);

    long countByUserId(Long userId);

    long countByUserIdAndType(Long userId, String type);
}