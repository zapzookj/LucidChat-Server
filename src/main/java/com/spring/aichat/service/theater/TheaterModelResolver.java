package com.spring.aichat.service.theater;

import com.spring.aichat.config.OpenAiProperties;
import com.spring.aichat.domain.character.Character;
import com.spring.aichat.domain.enums.BranchLevel;
import com.spring.aichat.domain.theater.TheaterState;
import com.spring.aichat.domain.user.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * [Phase 5.5-Theater · Phase III · 작업 3] Theater 2단 모델 라우팅
 *
 * Theater 모드는 배치당 LLM 1회를 호출하지만 한 세션이 500~1,000 Scene까지
 * 이어지므로 모든 호출에 무차별로 proModel(고급)을 사용하면 운영 비용 폭증.
 * 반대로 모두 model(저비용)이면 결정적 순간(분기 직후·클라이맥스·엔딩)의
 * 서사 품질이 떨어진다.
 *
 * 본 리졸버는 호출 컨텍스트별로 두 모델 중 적절한 것을 선택한다.
 *
 * ── 라우팅 정책 ──
 *
 * 1. 사용자 명시(Boost ON)              → proModel  (최우선)
 * 2. 캐릭터별 특정 모델이 박혀있는 경우    → 그 값을 그대로 (기존 동작 보존)
 * 3. 그 외:
 *
 *    배치 생성:
 *      - 분기 직후 첫 배치(justBranched)              → proModel
 *      - 마지막 Chapter (Act 클라이맥스 호흡)           → proModel
 *      - 그 외 일반 배치                              → model
 *
 *    분기 옵션 생성:
 *      - CLIMAX                                      → proModel
 *      - MINOR / MAJOR / LOCATION                    → model
 *
 *    엔딩 씬 생성:
 *      - 항상                                        → proModel
 *
 * 캐릭터별 llmModelName이 있으면 모든 정책 위에 군림한다 — 특정 캐릭터가
 * 특정 모델로 이미 튜닝되어 있을 가능성을 존중하기 위함이며, 기존 동작과의
 * 회귀를 막는다. (CharacterSeeder가 이를 채워둘 수 있음)
 *
 * 본 리졸버는 stateless하므로 어디서든 안전하게 주입 가능하다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TheaterModelResolver {

    private final OpenAiProperties props;

    /**
     * 배치 생성용 모델 선택.
     *
     * @param user           유저 (Boost 모드 상태 확인용)
     * @param speaker        이번 배치의 화자 캐릭터 (캐릭터별 모델 우선)
     * @param state          Theater 세션 상태
     * @param justBranched   직전에 분기 적용이 있었는지 (분기 직후 첫 배치)
     * @param isLastChapter  현재가 Act의 마지막 Chapter인지
     * @return 사용할 OpenRouter 모델 ID
     */
    public String resolveBatchModel(User user, Character speaker, TheaterState state,
                                    boolean justBranched, boolean isLastChapter) {
        // 1. 캐릭터에 명시된 모델은 항상 최우선 (회귀 방지)
        if (speaker != null && speaker.getLlmModelName() != null
            && !speaker.getLlmModelName().isBlank()) {
            return speaker.getLlmModelName();
        }

        // 2. 유저 명시 Boost ON
        if (user != null && Boolean.TRUE.equals(user.getBoostMode())) {
            return logChoice(props.proModel(), "user-boost", state);
        }

        // 3. 정책 기반
        if (justBranched) {
            return logChoice(props.proModel(), "post-branch", state);
        }
        if (isLastChapter) {
            return logChoice(props.proModel(), "last-chapter", state);
        }

        return logChoice(props.model(), "default", state);
    }

    /**
     * 분기 옵션 생성용 모델 선택.
     *
     *   - CLIMAX:                proModel (Act의 운명을 가르는 결정)
     *   - MINOR / MAJOR / LOCATION:  model (옵션 텍스트는 짧고, 결정의
     *                                       묘미는 적용 후 첫 배치에서 발현)
     */
    public String resolveBranchModel(User user, BranchLevel level) {
        // 유저 명시 Boost는 무조건 우선
        if (user != null && Boolean.TRUE.equals(user.getBoostMode())) {
            return props.proModel();
        }
        if (level == BranchLevel.CLIMAX) {
            return props.proModel();
        }
        return props.model();
    }

    /**
     * 엔딩 씬 생성용 모델 — 항상 proModel.
     *
     * 한 세션의 누적 가치가 응축되는 순간이며, 비용 대비 임팩트가 가장 큼.
     * 따라서 정책 분기 없이 일관되게 proModel.
     */
    public String resolveEndingModel(User user) {
        return props.proModel();
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  내부
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    private String logChoice(String model, String reason, TheaterState state) {
        if (log.isDebugEnabled()) {
            log.debug("🎭 [MODEL] roomId={} act={} ch={} → {} ({})",
                state != null ? state.getRoom().getId() : null,
                state != null ? state.getCurrentAct() : null,
                state != null ? state.getCurrentChapter() : null,
                model, reason);
        }
        return model;
    }
}