package com.spring.aichat.domain.notice;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface NoticeRepository extends JpaRepository<Notice, Long> {

    List<Notice> findByPublishedTrueOrderByPinnedDescPublishedAtDesc();

    List<Notice> findAllByOrderByPinnedDescIdDesc();
}
