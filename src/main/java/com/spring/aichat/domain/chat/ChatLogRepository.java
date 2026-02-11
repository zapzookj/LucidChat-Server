package com.spring.aichat.domain.chat;

import com.spring.aichat.domain.enums.ChatRole;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ChatLogRepository extends JpaRepository<ChatLog, Long> {

    List<ChatLog> findTop20ByRoom_IdOrderByCreatedAtDesc(Long roomId);

    Page<ChatLog> findByRoom_Id(Long roomId, Pageable pageable);

    void deleteByRoom_Id(Long roomId);

    int countByRoomId(Long roomId);

    Optional<ChatLog> findTop1ByRoom_IdAndRoleOrderByCreatedAtDesc(Long id, ChatRole chatRole);

    /** [Phase 3] 특정 역할의 로그 수 조회 (메모리 트리거 판단용) */
    long countByRoom_IdAndRole(Long roomId, ChatRole role);
}