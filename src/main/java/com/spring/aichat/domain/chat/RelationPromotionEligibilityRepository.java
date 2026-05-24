package com.spring.aichat.domain.chat;

import com.spring.aichat.domain.enums.RelationStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * [V2 Story] 관계 승급 자격 게이트 Repository.
 *
 * <p>주 사용처:
 * - 디렉터 prompt 빌딩 시 활성(미발동) 자격 조회 → 자격 활성 신호 인젝션
 * - 호감도 임계 도달 감지 시 신규 자격 생성 (중복 방지: unique constraint)
 * - LLM 자율 발동 시 triggered 마킹 → ChatRoomHeroine.statusLevel 갱신과 같은 TX
 * - 매 턴 미발동 자격에 대해 deferred_turn_count 증가 → 페일세이프 임계 비교
 */
public interface RelationPromotionEligibilityRepository
    extends JpaRepository<RelationPromotionEligibility, Long> {

    /**
     * 특정 방-캐릭터-목표단계 자격 단일 조회.
     * 호감도 임계 도달 시 *이미 활성된 자격이 있는지* 확인 후 신규 생성에 사용.
     */
    Optional<RelationPromotionEligibility>
    findByChatRoomIdAndCharacterIdAndNextLevel(Long chatRoomId, Long characterId, RelationStatus nextLevel);

    /**
     * 특정 방-캐릭터의 *활성(미발동)* 자격 단일 조회.
     * 한 캐릭터에 동시에 여러 단계 자격 활성은 자연 발생 어려움(임계값이 단계적).
     * 디렉터 prompt에 인젝션 시 단일 활성 자격 가정.
     */
    Optional<RelationPromotionEligibility>
    findByChatRoomIdAndCharacterIdAndTriggeredFalse(Long chatRoomId, Long characterId);

    /**
     * 방 안의 모든 활성(미발동) 자격 — 매 턴 deferred count 증가용.
     */
    List<RelationPromotionEligibility> findByChatRoomIdAndTriggeredFalse(Long chatRoomId);

    /**
     * 방-캐릭터의 발동 이력 — UI 표시 / Achievement 트리거 등.
     */
    List<RelationPromotionEligibility>
    findByChatRoomIdAndCharacterIdOrderByEligibleSinceAsc(Long chatRoomId, Long characterId);

    /** 스토리 초기화 시 일괄 삭제 */
    void deleteByChatRoomId(Long chatRoomId);
}