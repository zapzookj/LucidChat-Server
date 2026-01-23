package com.spring.aichat.domain.chat;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

import java.util.Optional;

public interface ChatRoomRepository extends JpaRepository<ChatRoom, Long> {

    @EntityGraph(attributePaths = {"member", "character"})
    Optional<ChatRoom> findWithMemberAndCharacterById(Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<ChatRoom> findById(Long id);
}
