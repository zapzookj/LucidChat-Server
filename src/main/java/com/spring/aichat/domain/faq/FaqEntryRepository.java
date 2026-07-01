package com.spring.aichat.domain.faq;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface FaqEntryRepository extends JpaRepository<FaqEntry, Long> {

    List<FaqEntry> findByPublishedTrueOrderByCategoryAscDisplayOrderAscIdAsc();

    List<FaqEntry> findAllByOrderByCategoryAscDisplayOrderAscIdAsc();
}
