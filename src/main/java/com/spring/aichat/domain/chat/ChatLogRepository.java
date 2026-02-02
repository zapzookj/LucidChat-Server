package com.spring.aichat.domain.chat;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ChatLogRepository extends JpaRepository<ChatLog, Long> {

    List<ChatLog> findTop20ByRoom_IdOrderByCreatedAtDesc(Long roomId);

    Page<ChatLog> findByRoom_Id(Long roomId, Pageable pageable);

    void deleteByRoom_Id(Long roomId);
}
