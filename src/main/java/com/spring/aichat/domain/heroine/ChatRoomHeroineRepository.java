package com.spring.aichat.domain.heroine;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * [V2 Story] ChatRoom-Heroine 다대다 + 캐릭터별 상태 Repository.
 *
 * <p>주 사용처:
 * - 디렉터 prompt 빌딩 — 방의 모든 히로인 + 캐릭터 정보 EntityGraph 로드
 * - 매 턴 스탯 갱신 — 특정 (room, character) 단일 조회 + 갱신
 * - 라우팅 디폴트 결정:
 *   - 호감도 1위: orderByStatAffectionDesc
 *   - 최근 화자: orderByLastSpokenAtDesc
 * - 상태창(BiometricStatusPanel) 캐릭터 탭 — 방의 모든 히로인 정보
 */
public interface ChatRoomHeroineRepository extends JpaRepository<ChatRoomHeroine, Long> {

    /**
     * 방의 모든 히로인 — 캐릭터 정보까지 로드.
     * 디렉터 prompt 빌딩의 1차 쿼리.
     */
    @EntityGraph(attributePaths = {"character"})
    List<ChatRoomHeroine> findByChatRoom_Id(Long chatRoomId);

    /**
     * 특정 방의 특정 캐릭터 — 스탯 갱신 / 속마음 갱신 / 화자 마킹.
     */
    @EntityGraph(attributePaths = {"character"})
    Optional<ChatRoomHeroine> findByChatRoom_IdAndCharacter_Id(Long chatRoomId, Long characterId);

    /**
     * 호감도 1위 (라우팅 디폴트 - 같은 공간 여러 명 + 호명 불명확).
     */
    @EntityGraph(attributePaths = {"character"})
    List<ChatRoomHeroine> findByChatRoom_IdOrderByStatAffectionDesc(Long chatRoomId);

    /**
     * 최근 화자 (라우팅 디폴트 보조 - 호감도가 동률일 때).
     * lastSpokenAt이 null인 행은 가장 후순위로 처리(서비스 레이어가 정렬 후 처리).
     */
    @EntityGraph(attributePaths = {"character"})
    List<ChatRoomHeroine> findByChatRoom_IdOrderByLastSpokenAtDesc(Long chatRoomId);

    /** 스토리 초기화 시 일괄 삭제 */
    void deleteByChatRoom_Id(Long chatRoomId);

    long countByChatRoom_Id(Long chatRoomId);

    /**
     * [Chunk D / Lobby N+1 방지] 여러 방의 히로인 수를 단일 쿼리로 일괄 조회.
     *
     * <p>로비 기억의 끈 패널이 V2 방 카드를 그릴 때 각 방의 heroineCount를 표시한다.
     * 방 한 개당 별도 COUNT 쿼리(N+1)를 피하기 위한 GROUP BY 일괄 쿼리.
     *
     * <p>반환: {@link RoomHeroineCountProjection} 리스트 (roomId, heroineCount).
     *   roomIds가 비어 있으면 빈 리스트. 해당 roomId에 히로인 0명이면 결과에 포함되지 않음
     *   (호출자는 default 0 처리).
     */
    @Query("SELECT crh.chatRoom.id AS roomId, COUNT(crh.id) AS heroineCount " +
        "FROM ChatRoomHeroine crh " +
        "WHERE crh.chatRoom.id IN :roomIds " +
        "GROUP BY crh.chatRoom.id")
    List<RoomHeroineCountProjection> findHeroineCountsByRoomIds(@Param("roomIds") Collection<Long> roomIds);

    /** 위 GROUP BY 쿼리 결과 projection. */
    interface RoomHeroineCountProjection {
        Long getRoomId();
        Long getHeroineCount();
    }
}