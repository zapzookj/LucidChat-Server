package com.spring.aichat.service.theater;

import com.spring.aichat.domain.character.Character;
import com.spring.aichat.domain.character.CharacterRepository;
import com.spring.aichat.domain.chat.ChatRoom;
import com.spring.aichat.domain.enums.BranchLevel;
import com.spring.aichat.domain.enums.ChatModePolicy;
import com.spring.aichat.domain.enums.TheaterAct;
import com.spring.aichat.domain.theater.TheaterHeroineAffection;
import com.spring.aichat.domain.theater.TheaterHeroineAffectionRepository;
import com.spring.aichat.domain.theater.TheaterState;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * [Phase 5.5-Theater] Theater 디렉터 엔진
 *
 * Theater 서사 흐름의 "메타 레이어" — LLM 호출 전후로 흐름을 결정한다.
 *
 * [주요 책임]
 * 1. Chapter 목표 씬 수 결정 (Act 전환 직전 Chapter는 길게 등)
 * 2. Act 기반 히로인 분배 (다음 배치의 speaker 결정)
 * 3. Act/Chapter 전환 조건 판정
 * 4. 메인 히로인 수렴 판정 (Act 3 말)
 * 5. Chapter 방향 힌트 생성 (LLM에 주입할 chapterPlanHint)
 *
 * 이 엔진은 LLM 호출과 별개로 동작한다 — 순수 결정론적 로직 + 약간의 랜덤성.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TheaterDirectorEngine {

    private final TheaterHeroineAffectionRepository heroineAffectionRepository;
    private final CharacterRepository characterRepository;
    private final Random random = new Random();

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  1. Chapter 목표 씬 수 결정
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /**
     * 새 Chapter 시작 시 목표 씬 수 결정.
     *
     * 정책:
     * - 일반 Chapter: 25~35 씬
     * - Act 전환 직전 Chapter: 35~40 씬 (더 진한 서사)
     * - 초반 Chapter (Act 1의 1~2): 20~28 씬 (짧게 시작해 몰입 유도)
     */
    public int decideChapterTargetScenes(TheaterState state, boolean isLastChapterOfAct) {
        int min = ChatModePolicy.THEATER_SCENES_PER_CHAPTER_MIN;
        int max = ChatModePolicy.THEATER_SCENES_PER_CHAPTER_MAX;

        if (state.getCurrentAct() == TheaterAct.ACT_1_MEETING && state.getCurrentChapter() <= 2) {
            return randomInRange(20, 28);
        }
        if (isLastChapterOfAct) {
            return randomInRange(Math.max(min, 35), max);
        }
        return randomInRange(min, max - 5);
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  2. 다음 배치의 speaker 히로인 결정 (Act 기반 분배)
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /**
     * 다음 배치를 이끌 히로인을 결정.
     *
     * [결정 우선순위]
     * 1. state.currentHeroineId가 있으면 그대로 유지 (Chapter 내 일관성)
     * 2. 장소 선택 분기가 직전에 있었으면 선택된 히로인 우선
     * 3. Act 기반 분배 정책 (아래 참조)
     *
     * [Act 기반 분배 정책]
     * - Act 1: 라운드 로빈 + 각 히로인 최소 3씬 보장 로직
     * - Act 2: 호감도 비례 확률
     * - Act 3: 최고 호감도 히로인 80% / 나머지 20%
     * - Act 4: 확정된 메인 히로인 100%
     */
    public Character decideNextSpeakerHeroine(ChatRoom room, TheaterState state, Long hintedHeroineId) {
        List<TheaterHeroineAffection> affections = heroineAffectionRepository
            .findByRoom_Id(room.getId());

        if (affections.isEmpty()) {
            // 폴백: room의 character (대표 히로인)
            return room.getCharacter();
        }
        if (affections.size() == 1) {
            return affections.get(0).getCharacter();
        }

        // 힌트된 히로인 우선 (장소 선택 등)
        if (hintedHeroineId != null) {
            Optional<TheaterHeroineAffection> hinted = affections.stream()
                .filter(a -> a.getCharacter().getId().equals(hintedHeroineId))
                .findFirst();
            if (hinted.isPresent()) return hinted.get().getCharacter();
        }

        // [Phase 6 도그푸딩 #2 결함 B / Patch B-1] Chapter 진행 중 일관성 — 주석 #1 구현
        //   같은 Chapter 안에서는 화자 히로인 변경 금지. Chapter 첫 batch(scenes=0)에서는
        //   분기 결정/메인 히로인 확정에 따라 새 화자 결정 가능 → 가드에서 제외.
        if (state.getCurrentHeroineId() != null && state.getScenesInCurrentChapter() > 0) {
            Optional<TheaterHeroineAffection> current = affections.stream()
                .filter(a -> a.getCharacter().getId().equals(state.getCurrentHeroineId()))
                .findFirst();
            if (current.isPresent()) return current.get().getCharacter();
        }

        TheaterAct act = state.getCurrentAct();
        return switch (act) {
            case ACT_1_MEETING -> pickForAct1(affections);
            case ACT_2_BONDING -> pickByAffectionProb(affections, 0.5);
            case ACT_3_TURNING -> pickByAffectionProb(affections, 0.8);
            case ACT_4_RESOLUTION -> pickMainHeroine(affections);
        };
    }

    /** Act 1: 등장 씬 적은 히로인 우선 + 약간의 랜덤 */
    private Character pickForAct1(List<TheaterHeroineAffection> affections) {
        // 가장 등장 횟수가 적은 히로인 찾기
        int minScenes = affections.stream()
            .mapToInt(TheaterHeroineAffection::getTotalScenes)
            .min().orElse(0);

        List<TheaterHeroineAffection> candidates = affections.stream()
            .filter(a -> a.getTotalScenes() <= minScenes + 2) // 약간 여유
            .toList();

        TheaterHeroineAffection pick = candidates.get(random.nextInt(candidates.size()));
        return pick.getCharacter();
    }

    /**
     * 호감도 비례 확률 분배.
     *
     * @param leadBias 최고 호감도 히로인에게 얼마나 더 자주 갈지 (0.0 = 균등, 1.0 = 항상 1위)
     */
    private Character pickByAffectionProb(List<TheaterHeroineAffection> affections, double leadBias) {
        // 정규화된 호감도 (음수 → 0)
        double total = 0;
        double[] weights = new double[affections.size()];
        for (int i = 0; i < affections.size(); i++) {
            double aff = Math.max(0, affections.get(i).getAffection());
            double base = Math.pow(aff + 10, 1 + leadBias); // leadBias 높을수록 격차 확대
            weights[i] = base;
            total += base;
        }

        if (total <= 0) {
            return affections.get(random.nextInt(affections.size())).getCharacter();
        }

        double pick = random.nextDouble() * total;
        double cum = 0;
        for (int i = 0; i < weights.length; i++) {
            cum += weights[i];
            if (pick <= cum) return affections.get(i).getCharacter();
        }
        return affections.get(0).getCharacter();
    }

    /** Act 4: 확정된 메인 히로인 우선, 없으면 최고 호감도 */
    private Character pickMainHeroine(List<TheaterHeroineAffection> affections) {
        return affections.stream()
            .filter(TheaterHeroineAffection::isConfirmedMain)
            .findFirst()
            .map(TheaterHeroineAffection::getCharacter)
            .orElseGet(() -> affections.stream()
                .max(Comparator.comparingInt(TheaterHeroineAffection::getAffection))
                .map(TheaterHeroineAffection::getCharacter)
                .orElse(affections.get(0).getCharacter()));
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  3. Chapter 방향 힌트 생성
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /**
     * LLM에 주입할 chapterPlanHint 생성.
     * Chapter의 시작/중간/끝 단계에 따라 서사적 긴장감을 다르게 지시한다.
     */
    public String generateChapterPlanHint(TheaterState state, Character speakerHeroine) {
        double progress = state.getChapterTargetScenes() <= 0 ? 0 :
            (double) state.getScenesInCurrentChapter() / state.getChapterTargetScenes();

        String phase;
        if (progress < 0.3) phase = "OPENING";
        else if (progress < 0.7) phase = "MIDDLE";
        else phase = "CLIMAX";

        TheaterAct act = state.getCurrentAct();
        String actHint = switch (act) {
            case ACT_1_MEETING -> "첫 만남의 설렘과 어색함. 호감도는 아직 낮다. 상대를 알아가는 것이 목표.";
            case ACT_2_BONDING -> "관계가 깊어지는 시기. 작은 갈등과 이해가 교차한다. 유저의 선택이 관계 방향을 가른다.";
            case ACT_3_TURNING -> "결정적 전환점이 다가온다. 이 Chapter에서는 히로인의 비밀이나 과거가 드러나기 좋다.";
            case ACT_4_RESOLUTION -> "이야기의 결말. 메인 히로인과의 감정적 절정. 대단원을 준비하라.";
        };

        String phaseHint = switch (phase) {
            case "OPENING" -> "Chapter 초반 — 상황 설정, 오늘의 분위기를 조성하라.";
            case "MIDDLE" -> "Chapter 중반 — 관계의 변화, 소소한 이벤트, 감정의 결이 드러나라.";
            default -> "Chapter 종반 — 작은 절정을 향해 긴장감을 끌어올려라. 분기 시그널이 자연스러우면 branch_signal을 포함하라.";
        };

        return String.format("""
            [Act] %s
            [Phase] %s
            [Focus] %s
            [Phase Instruction] %s
            [Lead Heroine in this batch] %s — 이 배치는 %s의 시점에서 전개된다.
            """,
            act.getTitle(), phase, actHint, phaseHint,
            speakerHeroine.getName(), speakerHeroine.getName()
        );
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  4. 메인 히로인 수렴 판정
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /**
     * Act 3 진입 시 / 중에 호출.
     * 최고 호감도 히로인을 confirmedMain으로 마킹하고, 기존 main은 해제.
     *
     * @return 확정된 메인 히로인 (없으면 null)
     */
    public Character confirmMainHeroineIfApplicable(ChatRoom room, TheaterState state) {
        if (state.getCurrentAct() != TheaterAct.ACT_3_TURNING
            && state.getCurrentAct() != TheaterAct.ACT_4_RESOLUTION) {
            return null;
        }

        List<TheaterHeroineAffection> affections = heroineAffectionRepository
            .findByRoomOrderByAffectionDesc(room.getId());

        if (affections.isEmpty()) return null;

        TheaterHeroineAffection top = affections.get(0);

        // 이미 해당 히로인이 confirmedMain이면 변경 없음
        if (top.isConfirmedMain()) return top.getCharacter();

        // 기존 main 해제
        for (TheaterHeroineAffection a : affections) {
            if (a.isConfirmedMain() && !a.getId().equals(top.getId())) {
                a.resetMainFlag();
            }
        }
        top.confirmAsMain();

        log.info("🎭 [DIRECTOR] Main heroine confirmed | roomId={} | heroine={} | affection={}",
            room.getId(), top.getCharacter().getName(), top.getAffection());

        return top.getCharacter();
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  5. Act 전환 판정
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /**
     * 현재 Chapter가 "Act의 마지막 Chapter인가"를 판정.
     *
     * 정책: 각 Act는 약 5~8 Chapter로 구성. 여기서는 6 Chapter를 기준으로
     * 유저 플레이 스타일(Chapter당 씬 수)에 따라 자동 조절된다.
     */
    public boolean isLastChapterOfAct(TheaterState state) {
        // Chapter 번호가 6에 도달했거나, 호감도 최고치가 임계치 넘었을 때
        int chapterThreshold = switch (state.getCurrentAct()) {
            case ACT_1_MEETING -> 5;
            case ACT_2_BONDING -> 6;
            case ACT_3_TURNING -> 5;
            case ACT_4_RESOLUTION -> 4;
        };
        return state.getCurrentChapter() >= chapterThreshold;
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  6. 장소 선택 분기 트리거 여부
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /**
     * 새 Chapter 초입에서 장소 선택 분기를 제시할지 판정.
     *
     * 정책:
     * - 멀티 히로인 세션에서만 활성화
     * - Act 1, 2에서는 매 Chapter 시작
     * - Act 3에서는 격 Chapter
     * - Act 4에서는 없음 (메인 히로인과 직행)
     */
    public boolean shouldOfferLocationChoice(TheaterState state, int heroineCount) {
        if (heroineCount < 2) return false;
        if (state.getScenesInCurrentChapter() > 0) return false; // Chapter 초입만

        return switch (state.getCurrentAct()) {
            case ACT_1_MEETING, ACT_2_BONDING -> true;
            case ACT_3_TURNING -> state.getCurrentChapter() % 2 == 1;
            case ACT_4_RESOLUTION -> false;
        };
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  [Phase 5.5 UX Polish · R2] 결정론적 배치 끝 분기
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /**
     * 다가오는 배치의 끝에서 발생시킬 분기 레벨 결정.
     *
     * 기존: LLM이 "naturally ends at a pivotal choice"를 자율 판단 → 빈도 거의 0회
     * 신규: 백엔드가 결정론적으로 배치마다 강제. LLM은 옵션 내용만 생성.
     *
     * 정책 우선순위:
     *  1. CLIMAX  — Act의 마지막 Chapter의 마지막 배치
     *  2. 마지막 배치(분기 없음) — Chapter 종료가 분기보다 우선
     *  3. MAJOR — 한 Chapter 안에서 1회만, Chapter 중반 지점
     *  4. MINOR — 그 외 모든 배치 끝
     *
     * 결과적으로 Chapter당 분기 발생량:
     *  - LOCATION 0~1회 (Chapter 첫 배치 직전, 별도 trigger)
     *  - MINOR 3~4회
     *  - MAJOR 1회
     *  - CLIMAX Act당 1회 (마지막 Chapter 마지막 배치에서만)
     *
     * @param state              현재 세션 상태
     * @param batchSize          이번 배치의 씬 수 (5~8)
     * @param chapterTargetTotal 이번 Chapter 목표 씬 총수 (DirectorEngine.decideChapterTargetScenes)
     * @return BranchLevel.MINOR/MAJOR/CLIMAX or null (분기 없음)
     */
    public BranchLevel decideBranchAfterBatch(TheaterState state, int batchSize, int chapterTargetTotal) {
        int scenesAfterThisBatch = state.getScenesInCurrentChapter() + batchSize;
        boolean isLastBatchOfChapter = scenesAfterThisBatch >= chapterTargetTotal;
        boolean isLastChapter = isLastChapterOfAct(state);

        // 1. CLIMAX — Act의 마지막 Chapter + 그 Chapter의 마지막 배치
        if (isLastChapter && isLastBatchOfChapter) {
            return BranchLevel.CLIMAX;
        }

        // 2. 마지막 배치는 분기 없음 — Chapter end 처리가 우선
        //    (Chapter 종료 리포트와 분기 모달이 동시에 뜨면 UX 혼란)
        if (isLastBatchOfChapter) {
            return null;
        }

        // 3. MAJOR — Chapter 중반(약 50% 지점)에 1회만
        //    state에 majorBranchDoneInChapter 플래그가 false이고, 다음 배치 종료 시점이
        //    chapter 50% 지점을 처음 넘어서면 발동.
        if (!Boolean.TRUE.equals(state.getMajorBranchDoneInChapter())) {
            int midpoint = chapterTargetTotal / 2;
            int prevTotal = state.getScenesInCurrentChapter();
            // 이번 배치가 50%를 처음 가로지르는 경우
            if (prevTotal < midpoint && scenesAfterThisBatch >= midpoint) {
                return BranchLevel.MAJOR;
            }
        }

        // 4. 그 외 모든 배치 끝은 MINOR
        return BranchLevel.MINOR;
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  Helpers
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    private int randomInRange(int min, int max) {
        if (max <= min) return min;
        return min + random.nextInt(max - min + 1);
    }
}