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

    List<ChatLogDocument> findTop200ByRoomIdOrderByCreatedAtDesc(Long roomId);

    /**
     * [Phase 5.5-Fix] hidden이 아닌 로그만 페이지네이션 조회 (프론트 무한스크롤)
     * hidden 필드가 없는 기존 문서도 포함 ({$ne: true} 조건)
     * Covered by: idx_room_created
     */
    Page<ChatLogDocument> findByRoomIdAndHiddenNot(Long roomId, boolean hidden, Pageable pageable);

    /**
     * [Phase 5.5-Fix] 편의 메서드: hidden=true가 아닌 로그만 조회
     */
    default Page<ChatLogDocument> findByRoomIdAndHiddenFalse(Long roomId, Pageable pageable) {
        return findByRoomIdAndHiddenNot(roomId, true, pageable);
    }

    /**
     * [안건 16 (b) · E-1.8a/8b] hidden 제외 로그 수 — 씬 일러 {@code turnIndex}의 좌표계.
     *
     * <p>{@link #countByRoomId}(hidden <b>포함</b>)로 turnIndex를 매기고 있었는데, 프론트가
     * 그것과 맞붙이는 {@code ChatLogResponse.ordinal}은 {@link #findByRoomIdAndHiddenFalse}
     * 기준(hidden <b>제외</b>)이었다. 축이 다르니 디렉터·시간넘기기·이벤트를 쓴 방일수록
     * turnIndex가 부풀어 ① K-윈도우 복원 판정({@code lastTurnIndex >= logTotal - K})이
     * 항상 참이 되어 오래된 씬이 재입장마다 풀블리드로 부활하고, ② 히스토리→씬 점프(goToTurn)가
     * 엉뚱한 씬으로 간다. 저장 축을 ordinal과 같게 맞추면 프론트는 코드 변경 없이 정상화된다.
     *
     * <p>{@code HiddenNot(true)} = {@code {$ne: true}} — hidden 필드가 없는 구 문서도 포함해
     * 위 페이지네이션 질의와 정확히 같은 모집단을 센다.
     * Covered by: idx_room_created
     */
    long countByRoomIdAndHiddenNot(Long roomId, boolean hidden);

    /**
     * [안건 16 (b) · E-4.7] 방의 최신 <b>가시</b> 로그 — '씬당 1회' 게이트의 시각 기준점.
     * hidden 로그를 기준으로 삼으면 유저가 못 본 SYSTEM_DIRECTOR 주입만으로 게이트가 풀린다.
     * Covered by: idx_room_created
     */
    Optional<ChatLogDocument> findTop1ByRoomIdAndHiddenNotOrderByCreatedAtDesc(Long roomId, boolean hidden);

    // [Phase 6] 품질 대시보드 — 평가/사유 집계 (idx_rating_created 활용)
    Page<ChatLogDocument> findByRatingOrderByCreatedAtDesc(String rating, Pageable pageable);

    long countByRating(String rating);

    long countByDislikeReason(String dislikeReason);
}