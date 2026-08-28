package com.spring.aichat.service.theater;

import com.spring.aichat.domain.enums.BranchLevel;
import com.spring.aichat.domain.theater.TheaterBranchChoiceRepository;
import com.spring.aichat.domain.theater.TheaterHeroineAffectionRepository;
import com.spring.aichat.domain.theater.TheaterState;
import com.spring.aichat.exception.BadRequestException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Locale;
import java.util.Optional;

/**
 * [적대적 리뷰 P1-1 / P1-4] 극장 진행 게이트의 <b>단일 소유자</b>.
 *
 * <p><b>왜 서비스로 뽑았나</b> — LOCATION 선행 술어가 세 곳에 흩어져 있었고 그중 한 곳
 * (분기 오퍼 발급)에는 <b>아예 없었다</b>. 로비({@code TheaterLobbyService.buildRoomInfo})가
 * {@code requiresLocationChoice}를 계산할 때 쓰는 술어와 배치 요청 가드
 * ({@code TheaterService.requestNextBatch})의 술어는 복붙으로 유지돼 있었고,
 * {@code TheaterBranchService.generateLocationBranch}는 소유권·세션·히로인 수 셋만 보고
 * 오퍼를 내줬다. 그래서 히로인 2명 이상인 방이면 <b>Chapter 중반 아무 시점에도</b>
 * {@code POST /branches/location}으로 오퍼를 받아 확정까지 할 수 있었다(B-4.e의 LOCATION 잔여).
 * 위조된 LOCATION 확정 기록은 배치 요청 가드와 로비 플래그가 신뢰하는 단일 진실 원천이라,
 * 그것 하나로 두 게이트가 함께 풀린다.
 *
 * <p>같은 술어를 세 곳이 각자 들고 있으면 다음 수정 때 또 어긋난다 — 그래서 여기 한 곳에 둔다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TheaterProgressGateService {

    private final TheaterHeroineAffectionRepository affectionRepository;
    private final TheaterBranchChoiceRepository branchChoiceRepository;
    private final TheaterBatchCacheService batchCache;

    /** LOCATION 분기가 열리는 Act 상한 (Act 4는 메인 히로인 확정 후라 장소 선택이 없다). */
    private static final int LOCATION_MAX_ACT = 3;

    /**
     * LOCATION 오퍼의 sourceBatchId 자리표시자.
     * LOCATION은 배치가 아니라 Chapter 진입에 묶이므로 (act, chapter)가 멱등키다.
     */
    public static final int LOCATION_SOURCE_BATCH_ID = -1;

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  1. LOCATION 선행 술어 (P1-1)
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /**
     * 지금 이 방이 <b>LOCATION 선택을 해야 하는 시점</b>인가.
     *
     * <p>조건(전부 AND):
     * <ul>
     *   <li>멀티 히로인(호감도 행 ≥ 2) — 단일 히로인 세션엔 고를 장소가 없다</li>
     *   <li>Act ≤ 3</li>
     *   <li>새 Chapter 진입 시점: {@code currentBatchId == 0 && scenesInCurrentChapter == 0}</li>
     *   <li>인터미션·난입·엔딩 도달 상태가 아님</li>
     *   <li>이번 (Act, Chapter)에 LOCATION 확정 기록이 아직 없음</li>
     * </ul>
     *
     * <p>마지막 조건이 진실의 단일 원천인 이유: LOCATION을 선택해도 {@link TheaterState}의
     * 어떤 필드도 변하지 않는다. 기록을 보지 않으면 선택 후에도 게이트가 안 풀려
     * "반응 없음"이 된다(Polish · LOCATION fix의 원 결함).
     */
    public boolean isLocationChoiceRequired(Long roomId, TheaterState state) {
        if (state.isInIntermission() || state.isInterventionActive() || state.isEndingReached()) {
            return false;
        }
        if (state.getCurrentAct().getNumber() > LOCATION_MAX_ACT) return false;
        if (state.getCurrentBatchId() != 0) return false;
        if (state.getScenesInCurrentChapter() != 0) return false;
        if (affectionRepository.findByRoom_Id(roomId).size() < 2) return false;

        return !branchChoiceRepository.existsByRoom_IdAndActNumberAndChapterNumberAndBranchLevel(
            roomId, state.getCurrentAct().getNumber(), state.getCurrentChapter(),
            BranchLevel.LOCATION);
    }

    /**
     * LOCATION 오퍼 발급·확정 진입 가드.
     *
     * <p>오퍼 발급이 로비와 <b>같은 술어</b>를 재확인하지 않으면, 히로인 2명 이상인 방에서
     * Chapter 중반 아무 시점에나 {@code POST /branches/location}을 불러 오퍼를 받고 확정까지
     * 할 수 있다. 그 확정 기록이 다시 배치 요청 가드를 풀어 준다.
     */
    public void requireLocationChoiceWindow(Long roomId, TheaterState state) {
        if (!isLocationChoiceRequired(roomId, state)) {
            log.warn("🎭 [GATE] LOCATION offer outside window | roomId={} | act={} | chapter={} "
                    + "| batchId={} | scenesInChapter={}",
                roomId, state.getCurrentAct().getNumber(), state.getCurrentChapter(),
                state.getCurrentBatchId(), state.getScenesInCurrentChapter());
            throw new BadRequestException("지금은 장소를 선택할 시점이 아닙니다.");
        }
    }


    /** 알 수 없는 레벨 문자열은 null (호출부가 "판단 근거 없음"으로 취급한다). */
    private BranchLevel parseLevel(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return BranchLevel.valueOf(raw.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
