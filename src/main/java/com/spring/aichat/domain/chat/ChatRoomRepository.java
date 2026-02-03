package com.spring.aichat.domain.chat;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

import java.util.Optional;

public interface ChatRoomRepository extends JpaRepository<ChatRoom, Long> {

    @EntityGraph(attributePaths = {"user", "character"})
    Optional<ChatRoom> findWithMemberAndCharacterById(Long id);

    Optional<ChatRoom> findByUser_IdAndCharacter_Id(Long userId, Long characterId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<ChatRoom> findById(Long id);

    Optional<ChatRoom> findByUser_Id(Long userId);
}
