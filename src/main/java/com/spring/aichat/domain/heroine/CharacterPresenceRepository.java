package com.spring.aichat.domain.heroine;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * [V2 Story] 캐릭터 현재 위치 추적 Repository.
 *
 * <p>주 사용처:
 * - WorldRoutingService — 매 턴 라우팅의 첫 단계 ({@code chars_at(currentUserLocationKey)})
 * - 디렉터 prompt [3] PRESENT SCENE / [6] OFFSCREEN 빌딩
 * - 디렉터 응답의 {@code character_movements} 반영 → moveTo() 호출
 * - 시간대 전환 시 모든 히로인 위치 일괄 재추정 (CharacterRoutine 기반)
 */
public interface CharacterPresenceRepository extends JpaRepository<CharacterPresence, Long> {

    /**
     * 같은 공간 캐릭터 조회 — 위치 기반 라우팅 핵심 쿼리.
     * V2의 *현재 화자 결정*과 직결.
     */
    List<CharacterPresence> findByChatRoom_IdAndCurrentLocationKey(Long chatRoomId, String locationKey);

    /**
     * 방의 모든 캐릭터 위치 — 디렉터 prompt [3]/[6] 빌딩.
     */
    List<CharacterPresence> findByChatRoom_Id(Long chatRoomId);

    /**
     * 특정 캐릭터 위치 단일 조회 — 디렉터의 character_movements 반영 시.
     */
    Optional<CharacterPresence> findByChatRoom_IdAndCharacterId(Long chatRoomId, Long characterId);

    /**
     * 다른 위치의 캐릭터들 — 디렉터 prompt [6] OFFSCREEN 빌딩.
     * "유저 위치가 아닌 모든 곳"의 캐릭터들.
     */
    List<CharacterPresence> findByChatRoom_IdAndCurrentLocationKeyNot(Long chatRoomId, String locationKey);

    /** 스토리 초기화 시 일괄 삭제 */
    void deleteByChatRoom_Id(Long chatRoomId);
}