package com.spring.aichat.domain.chat;

import com.spring.aichat.domain.enums.ChatMode;
import com.spring.aichat.domain.enums.WorldId;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * ChatRoom Repository — V2 듀얼 모드 지원.
 *
 * <p>[V1 메서드 보존]
 * Sandbox 모드는 V1 호환을 유지하므로 character 기반 메서드들 모두 유지.
 *
 * <p>[V2 신규 메서드]
 * World 기반 조회/검증 메서드 추가. STORY 모드 전용.
 *
 * <p>[EntityGraph 정책]
 * V1 메서드들은 {@code "character"}만 fetch. V2 메서드들은 {@code "world"}만 fetch.
 * Continue 패널처럼 *두 모드 혼합 리스트*가 필요한 경우 별도 메서드 {@code findAllByUser_IdWithModes}로 둘 다 fetch.
 */
public interface ChatRoomRepository extends JpaRepository<ChatRoom, Long> {

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  공통 조회
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    Optional<ChatRoom> findById(Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM ChatRoom r WHERE r.id = :id")
    Optional<ChatRoom> findByIdForUpdate(@Param("id") Long id);

    Optional<ChatRoom> findByUser_Id(Long userId);

    long countByUser_Id(Long userId);

    /** [Phase 6] CS 로그 뷰어 — 유저의 방 목록. */
    List<ChatRoom> findByUser_IdOrderByIdDesc(Long userId);

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  [V1/Sandbox] character 기반 조회 (유지)
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    @EntityGraph(attributePaths = {"user", "character"})
    Optional<ChatRoom> findWithMemberAndCharacterById(Long id);

    Optional<ChatRoom> findByUser_IdAndCharacter_Id(Long userId, Long characterId);

    Optional<ChatRoom> findByUser_IdAndCharacter_IdAndChatMode(Long userId, Long characterId, ChatMode chatMode);

    boolean existsByUser_IdAndCharacter_IdAndChatMode(Long userId, Long characterId, ChatMode chatMode);

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  [V2 Story] world 기반 조회 (신규)
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /**
     * V2 Story 진입 — World당 1방 unique 보장.
     * StoryCreateFlow 진입 시 기존 방 존재 여부 체크 + 입장.
     */
    @EntityGraph(attributePaths = {"user", "world"})
    Optional<ChatRoom> findByUser_IdAndWorld_IdAndChatMode(Long userId, WorldId worldId, ChatMode chatMode);

    boolean existsByUser_IdAndWorld_IdAndChatMode(Long userId, WorldId worldId, ChatMode chatMode);

    /**
     * [V2] World 기반 fetch 단일 조회 — ChatStreamService의 진입점.
     */
    @EntityGraph(attributePaths = {"user", "world"})
    Optional<ChatRoom> findWithMemberAndWorldById(Long id);

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  로비 — 모드별 분리 리스트
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /**
     * [V2] Continue 패널용 — Sandbox + Story 혼합. character와 world 둘 다 fetch.
     * 호출처에서 chatMode로 분기하여 어느 FK가 살아있는지 판단.
     */
    @EntityGraph(attributePaths = {"character", "world"})
    @Query("SELECT r FROM ChatRoom r WHERE r.user.id = :userId ORDER BY r.lastActiveAt DESC")
    List<ChatRoom> findAllByUser_IdOrderByLastActiveAtDesc(@Param("userId") Long userId);

    /**
     * 유저의 특정 모드 방들 — Theater 분리는 chatMode=THEATER 별도 처리.
     */
    @EntityGraph(attributePaths = {"character", "world"})
    List<ChatRoom> findAllByUser_IdAndChatModeOrderByLastActiveAtDesc(Long userId, ChatMode chatMode);

    /**
     * 유저의 Dialogue 그룹 (STORY/SANDBOX) — Continue 패널에서 Theater와 분리 표시.
     */
    @EntityGraph(attributePaths = {"character", "world"})
    @Query("SELECT r FROM ChatRoom r WHERE r.user.id = :userId " +
        "AND r.chatMode IN (com.spring.aichat.domain.enums.ChatMode.STORY, com.spring.aichat.domain.enums.ChatMode.SANDBOX) " +
        "ORDER BY r.lastActiveAt DESC")
    List<ChatRoom> findDialogueRoomsByUser(@Param("userId") Long userId);
}