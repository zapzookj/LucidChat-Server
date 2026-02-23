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

    /**
     * [Phase 4.5] 유저 + 캐릭터 + 모드 조합으로 방 조회
     */
    Optional<ChatRoom> findByUser_IdAndCharacter_IdAndChatMode(Long userId, Long characterId, ChatMode chatMode);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<ChatRoom> findById(Long id);

    Optional<ChatRoom> findByUser_Id(Long userId);

    /**
     * [Phase 4.5] 유저의 모든 채팅방 목록 — 로비 '기억의 끈' (Continue) 용
     * Character를 Eager Fetch하여 N+1 방지
     */
    @EntityGraph(attributePaths = {"character"})
    List<ChatRoom> findAllByUser_IdOrderByLastActiveAtDesc(Long userId);

    /**
     * [Phase 4.5] 유저의 채팅방 개수
     */
    long countByUser_Id(Long userId);

    /**
     * [Phase 4.5] 유저 + 캐릭터 + 모드 존재 여부 확인
     */
    boolean existsByUser_IdAndCharacter_IdAndChatMode(Long userId, Long characterId, ChatMode chatMode);
}