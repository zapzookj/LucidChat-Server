package com.spring.aichat.domain.illustration;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface SceneIllustrationRepository extends JpaRepository<SceneIllustration, Long> {

    /** 방의 마지막 완료 씬 — scene_hash 디덥 비교 + 스킵 시 재사용 원본. */
    Optional<SceneIllustration> findTopByChatRoomIdAndStatusOrderByIdDesc(Long chatRoomId, String status);

    /** 방의 최신 행(상태 무관) — 인플라이트 디덥(동일 해시 진행 중이면 중복 제출 방지). */
    Optional<SceneIllustration> findTopByChatRoomIdOrderByIdDesc(Long chatRoomId);

    /** 씬 네비게이션(A-2) — 방의 턴별 씬 목록. */
    List<SceneIllustration> findByChatRoomIdOrderByIdAsc(Long chatRoomId);

    /**
     * [안건 16 (b) · E-4.7] '씬당 1회' 게이트의 <b>좌표계 비의존</b> 판정.
     *
     * <p>기존 판정은 {@code turnIndex}(= 방의 로그 개수)를 키로 삼았는데, V2 리셋
     * ({@code StoryV2Service.cascadeResetRoom})이 로그를 전부 지우면 턴 좌표가 0부터 다시
     * 세어진다. 씬 일러는 갤러리 보존 정책상 남으므로, 새 회차가 이전 회차의 행에 걸려
     * "이 장면은 이미 그렸어요" 409로 <b>오차단</b>됐다(5E 구매가 막힌다).
     *
     * <p>대신 시각으로 묻는다 — "이 방의 <b>최신 대화 로그보다 나중에</b> 만들어진 수동 렌더가
     * 있는가". 좌표계에 의존하지 않으므로 리셋·hidden 로그·마이그레이션 어디에도 걸리지 않는다.
     * 이전 회차의 씬은 새 회차의 어떤 로그보다도 오래됐으므로 영구히 판정에서 빠진다.
     *
     * <p>FAILED(자동 환불 완료)만 제외해 실패 재시도는 허용하고, AUTO/SKIPPED 행이 유료 요청을
     * 막지 않도록 triggerSource=MANUAL로 한정하는 규칙은 종전 그대로다.
     */
    boolean existsByChatRoomIdAndTriggerSourceAndStatusNotAndCreatedAtAfter(
        Long chatRoomId, String triggerSource, String status, LocalDateTime after);
}
