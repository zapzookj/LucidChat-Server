package com.spring.aichat.domain.chat;

import com.spring.aichat.domain.enums.ChatMode;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ChatRoomRepository extends JpaRepository<ChatRoom, Long> {

    @EntityGraph(attributePaths = {"user", "character"})
    Optional<ChatRoom> findWithMemberAndCharacterById(Long id);

    Optional<ChatRoom> findByUser_IdAndCharacter_Id(Long userId, Long characterId);

    Optional<ChatRoom> findByUser_IdAndCharacter_IdAndChatMode(Long userId, Long characterId, ChatMode chatMode);

    Optional<ChatRoom> findById(Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM ChatRoom r WHERE r.id = :id")
    Optional<ChatRoom> findByIdForUpdate(@Param("id") Long id);

    Optional<ChatRoom> findByUser_Id(Long userId);

    @EntityGraph(attributePaths = {"character"})
    List<ChatRoom> findAllByUser_IdOrderByLastActiveAtDesc(Long userId);

    long countByUser_Id(Long userId);

    boolean existsByUser_IdAndCharacter_IdAndChatMode(Long userId, Long characterId, ChatMode chatMode);

    /**
     * [Phase 5.5-Theater] 유저의 특정 모드 방들
     * Dialogue 탭(STORY/SANDBOX) vs Theater 탭 분리용
     */
    @EntityGraph(attributePaths = {"character"})
    List<ChatRoom> findAllByUser_IdAndChatModeOrderByLastActiveAtDesc(Long userId, ChatMode chatMode);

    /**
     * [Phase 5.5-Theater] 유저의 특정 모드 중 Dialogue 그룹
     * ChatMode.STORY, ChatMode.SANDBOX 두 모드 방
     */
    @EntityGraph(attributePaths = {"character"})
    @Query("SELECT r FROM ChatRoom r WHERE r.user.id = :userId " +
        "AND r.chatMode IN (com.spring.aichat.domain.enums.ChatMode.STORY, com.spring.aichat.domain.enums.ChatMode.SANDBOX) " +
        "ORDER BY r.lastActiveAt DESC")
    List<ChatRoom> findDialogueRoomsByUser(@Param("userId") Long userId);
}