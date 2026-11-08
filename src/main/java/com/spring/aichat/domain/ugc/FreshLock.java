package com.spring.aichat.domain.ugc;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;

import java.util.Optional;

/**
 * [2026-11-09 핫픽스] 잡 행 비관적 락 + <b>DB 최신 상태 재적재</b>.
 *
 * <p>종전 {@code @Lock(PESSIMISTIC_WRITE) @Query} 조회는 같은 영속성 컨텍스트에 이미 그 잡이 올라와 있으면
 * {@code SELECT ... FOR UPDATE}로 락만 잡고 <b>엔티티 상태는 갱신하지 않았다</b>(Hibernate는 관리 중인 인스턴스를
 * 쿼리 결과로 덮어쓰지 않는다). open-in-view(기본 on) 아래 웹훅 요청 스레드는 TX 밖 {@code findById}로 잡을 먼저
 * 적재하므로, 락 대기 동안 다른 스레드가 커밋한 내용(누끼 요청 번호 기록 등)을 모른 채 낡은 JSON을 통째로 다시 써
 * <b>lost update</b>가 났다 — 2026-11-09 잡 51: 누끼 웹훅이 제출 루프와 겹쳐 EX_05·06의 요청 번호가 지워지고,
 * 30분 뒤 스윕이 "접수 미확인"으로 잡 전체를 실패·환불했다.
 *
 * <p>여기서는 엔티티를 찾은 뒤 {@link EntityManager#refresh(Object, LockModeType)}로 락과 함께 다시 읽는다.
 * 이 TX 안에서 이미 바꾼 내용은 refresh가 버리지 않도록 먼저 flush한다(TX 밖 호출은 락 자체가 불가해 예외).
 */
final class FreshLock {
    private FreshLock() {}

    static <T> Optional<T> findForUpdate(EntityManager em, Class<T> type, Long id) {
        if (id == null) return Optional.empty();
        T entity = em.find(type, id);
        if (entity == null) return Optional.empty();
        if (em.isJoinedToTransaction()) em.flush();
        em.refresh(entity, LockModeType.PESSIMISTIC_WRITE);
        return Optional.of(entity);
    }
}
