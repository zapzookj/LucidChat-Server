package com.spring.aichat.domain.character;

import com.spring.aichat.domain.enums.DayPart;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * [V2 Story] 캐릭터 시간대-장소 루틴 Repository.
 *
 * <p>주 사용처:
 * - WorldRoutingService — 매 턴 시간대 전환 시 캐릭터들의 위치 재추정
 * - StoryCreateFlow 진입 시 — 시작 시간대에 맞는 히로인 초기 위치 결정
 * - 디버그/시드 검증 — 캐릭터 전체 루틴 조회
 */
public interface CharacterRoutineRepository extends JpaRepository<CharacterRoutine, Long> {

    /**
     * 특정 캐릭터의 특정 시간대 루틴 후보들.
     * 같은 (character, time)에 여러 행이 있을 수 있어 List 반환.
     * 호출처가 probability 가중치로 단일 선택.
     */
    List<CharacterRoutine> findByCharacterIdAndTimeOfDay(Long characterId, DayPart timeOfDay);

    /**
     * 특정 캐릭터의 모든 루틴 — 시간대 + 확률 정렬.
     * 시드 검증, 디버그용.
     */
    List<CharacterRoutine> findByCharacterIdOrderByTimeOfDayAscProbabilityDesc(Long characterId);

    /**
     * 여러 캐릭터의 특정 시간대 루틴 일괄 조회.
     * 매 턴 모든 히로인의 위치 재추정 시 단일 쿼리로 최적화.
     */
    List<CharacterRoutine> findByCharacterIdInAndTimeOfDay(List<Long> characterIds, DayPart timeOfDay);

    /**
     * 특정 캐릭터의 모든 루틴 삭제. 시드 재실행 시 stale 데이터 정리용.
     * CharacterRoutine은 (character, timeOfDay, locationKey) 조합으로 unique가 *불가능*하므로
     * (한 시간대에 여러 후보 장소 가능), 시드 갱신 시 *해당 캐릭터 전체 deletion + 재삽입* 패턴.
     */
    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.data.jpa.repository.Query("delete from CharacterRoutine cr where cr.characterId = :characterId")
    void deleteByCharacterId(@org.springframework.data.repository.query.Param("characterId") Long characterId);
}