package com.spring.aichat.domain.moderation;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface ModerationEventRepository extends JpaRepository<ModerationEvent, Long> {

    Page<ModerationEvent> findAllByOrderByIdDesc(Pageable pageable);

    /** 유저별 차단 건수 (반복위반 집계). row = [userId, count]. */
    @Query("SELECT m.userId, COUNT(m) FROM ModerationEvent m WHERE m.userId IS NOT NULL GROUP BY m.userId")
    List<Object[]> countByUser();
}
