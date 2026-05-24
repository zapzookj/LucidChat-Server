package com.spring.aichat.domain.heroine;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

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
}