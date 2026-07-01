package com.spring.aichat.domain.moderation;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface InjectionEventRepository extends JpaRepository<InjectionEvent, Long> {

    Page<InjectionEvent> findAllByOrderByIdDesc(Pageable pageable);

    @Query("SELECT i.userId, COUNT(i) FROM InjectionEvent i WHERE i.userId IS NOT NULL GROUP BY i.userId")
    List<Object[]> countByUser();
}
