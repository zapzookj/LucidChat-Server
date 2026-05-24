package com.spring.aichat.domain.memory;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * [V2 Story + Theater 결함 A 통합] 캐릭터별 누적 메모리 Repository.
 *
 * <p>주 사용처:
 * - 디렉터 prompt [8] CUMULATIVE MEMORY 빌딩 — (room, character)별 메모리 조회
 * - 메모리 압축 LLM 호출 후 신규 row 추가
 * - 스토리 초기화 시 일괄 삭제
 * - Theater 결함 A 패치: Theater 측에서 같은 메서드로 캐릭터별 메모리 조회
 *
 * <p>[Redis 캐싱 패턴]
 * 기존 {@link MemorySummary}와 동일하게, MemoryService 측에서 Redis read-through
 * 적용 권장 (room+character 키로 캐싱).
 */
public interface HeroineMemorySummaryRepository extends JpaRepository<HeroineMemorySummary, Long> {

    /**
     * 특정 방-캐릭터의 메모리 — 시간순 오름차순.
     * 디렉터 prompt [8] 섹션의 *그 캐릭터의 누적 기억* 빌딩.
     */
    List<HeroineMemorySummary> findByRoomIdAndCharacterIdOrderByCreatedAtAsc(Long roomId, Long characterId);

    /**
     * 방의 모든 메모리 — 디버그/관리자용.
     */
    List<HeroineMemorySummary> findByRoomIdOrderByCreatedAtAsc(Long roomId);

    /**
     * 카운트 — N마다 LLM 압축 트리거 판단용 (이미 저장된 누적 요약 수).
     */
    long countByRoomIdAndCharacterId(Long roomId, Long characterId);

    /** 스토리 초기화 시 일괄 삭제 */
    void deleteByRoomId(Long roomId);

    /** 특정 방-캐릭터 단위 삭제 — 향후 *특정 캐릭터만 기억 리셋* 같은 기능 대비 */
    void deleteByRoomIdAndCharacterId(Long roomId, Long characterId);
}