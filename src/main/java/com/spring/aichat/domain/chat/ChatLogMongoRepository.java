package com.spring.aichat.domain.chat;

import com.spring.aichat.domain.enums.ChatRole;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;
import java.util.Optional;

/**
 * [Phase 5] ChatLog MongoDB Repository
 *
 * 기존 JPA ChatLogRepository를 완전히 대체.
 * Spring Data MongoDB의 쿼리 메서드 네이밍 컨벤션 활용.
 *
 * [주요 쿼리 패턴 및 인덱스 매핑]
 *
 * 1. findTop20ByRoomIdOrderByCreatedAtDesc
 *    → idx_room_created {roomId:1, createdAt:-1} 커버
 *    → 사용처: ChatService(히스토리 구성), EndingService(최근 대화), NarratorService(컨텍스트), MemoryService(요약)
 *
 * 2. findByRoomId(roomId, pageable)
 *    → idx_room_created {roomId:1, createdAt:-1} 커버
 *    → 사용처: ChatController(프론트 무한스크롤 페이지네이션)
 *
 * 3. countByRoomId
 *    → idx_room_created 부분 커버 (roomId equality)
 *    → 사용처: ChatService(RAG 스킵 판단, 초기화 체크)
 *
 * 4. countByRoomIdAndRole
 *    → idx_room_role_created {roomId:1, role:1, createdAt:-1} 커버
 *    → 사용처: ChatService(메모리 요약 트리거 — USER 턴 카운트)
 *
 * 5. findTop1ByRoomIdAndRoleOrderByCreatedAtDesc
 *    → idx_room_role_created 커버
 *    → 사용처: ChatService(마지막 유저 메시지 조회)
 *
 * 6. findTop1ByRoomIdOrderByCreatedAtAsc
 *    → idx_room_created 활용 (역방향 스캔)
 *    → 사용처: EndingService(첫 로그 = 대화 시작일 통계)
 *
 * 7. deleteByRoomId
 *    → 사용처: ChatService.deleteChatRoom (방 초기화 시 전체 삭제)
 */
public interface ChatLogMongoRepository extends MongoRepository<ChatLogDocument, String> {

    /**
     * 최근 20건 조회 (히스토리 구성용)
     * Covered by: idx_room_created
     */
    List<ChatLogDocument> findTop20ByRoomIdOrderByCreatedAtDesc(Long roomId);

    /**
     * 페이지네이션 조회 (프론트 무한스크롤)
     * Covered by: idx_room_created
     */
    Page<ChatLogDocument> findByRoomId(Long roomId, Pageable pageable);

    /**
     * 방 삭제 시 전체 로그 삭제
     */
    void deleteByRoomId(Long roomId);

    /**
     * 방의 전체 로그 수
     * Covered by: idx_room_created (roomId equality scan)
     */
    long countByRoomId(Long roomId);

    /**
     * 특정 역할의 마지막 로그 조회
     * Covered by: idx_room_role_created
     */
    Optional<ChatLogDocument> findTop1ByRoomIdAndRoleOrderByCreatedAtDesc(Long roomId, ChatRole role);

    /**
     * 특정 역할의 로그 수 (메모리 요약 트리거 판단)
     * Covered by: idx_room_role_created
     */
    long countByRoomIdAndRole(Long roomId, ChatRole role);

    /**
     * 첫 번째 로그 조회 (엔딩 통계 — 대화 시작일)
     * Covered by: idx_room_created (역방향)
     */
    Optional<ChatLogDocument> findTop1ByRoomIdOrderByCreatedAtAsc(Long roomId);
}