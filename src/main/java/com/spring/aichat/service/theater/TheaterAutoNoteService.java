package com.spring.aichat.service.theater;

import com.spring.aichat.domain.character.Character;
import com.spring.aichat.domain.chat.ChatRoom;
import com.spring.aichat.domain.theater.TheaterDirectorNote;
import com.spring.aichat.domain.theater.TheaterDirectorNoteRepository;
import com.spring.aichat.domain.theater.TheaterState;
import com.spring.aichat.service.illustration.IllustrationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * [Phase 5.5 UX Polish · R6] 자동 노트 캡처 + 일러스트 동기 트리거의 단일 진입점.
 *
 * Theater 모드에서 결정적 순간이 발생하면 (호감도 ±2, 분기 선택, Chapter 종료 등)
 * 다음을 한 곳에서 처리:
 *
 *   1. DirectorNote 자동 생성 (AUTO_MOMENT / BRANCH_TAKEN / CHAPTER_END)
 *   2. 일러스트 비동기 트리거 (해당 순간의 화자 히로인으로)
 *   3. 일러스트 폴링 완료 시 노트의 relatedIllustrationUrl 자동 채움
 *      (이건 IllustrationService의 handleCompletion이 처리)
 *
 * 결과: 다이어리 패널이 시간이 지남에 따라 일러스트 사진첩화.
 *
 * 주의 사항:
 *  - 일러스트 생성은 비용이 있으므로 트리거를 신중히 — 이 서비스가 결정적 순간만 잡는 게이트키퍼.
 *  - 모든 메서드는 다른 트랜잭션에서 fail해도 본 배치 응답엔 영향이 없도록 try-catch.
 *  - 별도 트랜잭션(REQUIRES_NEW)으로 노트 저장 — 본 배치 트랜잭션 롤백과 격리.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TheaterAutoNoteService {

    private final TheaterDirectorNoteRepository directorNoteRepository;
    private final IllustrationService illustrationService;

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  AUTO_MOMENT — 호감도 ±2 같은 결정적 순간
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /**
     * 호감도 큰 변동(±2) 시 결정적 순간 캡처.
     *
     * @param room        ChatRoom
     * @param state       Theater 상태 (Act/Chapter 정보)
     * @param heroine     영향 받은 히로인 (일러스트 화자)
     * @param affectionDelta 변동량 (±2가 일반적)
     * @param sceneRefId  씬 식별 (배치ID:scene-index)
     */
    public void captureAffectionMoment(
        ChatRoom room, TheaterState state, Character heroine,
        int affectionDelta, String sceneRefId
    ) {
        if (heroine == null || room == null || state == null) return;

        try {
            String content = formatAffectionMoment(heroine, affectionDelta);
            TheaterDirectorNote note = saveNoteInNewTx(
                TheaterDirectorNote.autoMoment(
                    room, content, sceneRefId,
                    state.getCurrentAct().getNumber(),
                    state.getCurrentChapter(),
                    heroine.getId()
                )
            );

            // 일러스트 트리거 — 이번 순간의 히로인으로
            triggerIllustration(room, heroine, "AUTO_MOMENT", note.getId());

            log.info("🎭 [AUTO-NOTE] AFFECTION_MOMENT captured | roomId={} | heroine={} | delta={} | noteId={}",
                room.getId(), heroine.getName(), affectionDelta, note.getId());
        } catch (Exception e) {
            log.warn("🎭 [AUTO-NOTE] capture AFFECTION_MOMENT failed (non-fatal): roomId={}, err={}",
                room.getId(), e.getMessage());
        }
    }

    private String formatAffectionMoment(Character heroine, int delta) {
        // 결정적 순간의 결을 톤으로 표현
        if (delta >= 2) {
            return heroine.getName() + "와의 거리가 한 걸음 가까워졌다.";
        } else if (delta <= -2) {
            return heroine.getName() + "와의 사이에 깊은 균열이 생겼다.";
        }
        return heroine.getName() + "와의 관계에 변화가 있었다.";
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  BRANCH_TAKEN — 분기 선택 직후
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /**
     * 분기 선택 직후 자동 기록.
     *
     * @param room          ChatRoom
     * @param state         Theater 상태
     * @param branchLevel   "MINOR" / "MAJOR" / "CLIMAX" / "LOCATION"
     * @param chosenLabel   유저가 선택한 옵션 라벨
     * @param speakerHeroine 선택 후 화자 히로인 (LOCATION 분기는 선택된 히로인) — 일러스트 화자
     */
    public void captureBranchTaken(
        ChatRoom room, TheaterState state, String branchLevel,
        String chosenLabel, Character speakerHeroine
    ) {
        if (room == null || state == null || chosenLabel == null) return;

        // MINOR 분기는 빈도가 높으므로 일러스트 트리거 X (비용 절감)
        boolean shouldGenerateIllustration = !"MINOR".equalsIgnoreCase(branchLevel);

        try {
            String content = "\"" + truncate(chosenLabel, 80) + "\"를 선택했다.";
            TheaterDirectorNote note = saveNoteInNewTx(
                TheaterDirectorNote.branchTaken(
                    room, content,
                    state.getCurrentAct().getNumber(),
                    state.getCurrentChapter(),
                    speakerHeroine != null ? speakerHeroine.getId() : null
                )
            );

            if (shouldGenerateIllustration && speakerHeroine != null) {
                triggerIllustration(room, speakerHeroine, "BRANCH_TAKEN", note.getId());
            }

            log.info("🎭 [AUTO-NOTE] BRANCH_TAKEN captured | roomId={} | level={} | label='{}' | noteId={}",
                room.getId(), branchLevel, truncate(chosenLabel, 30), note.getId());
        } catch (Exception e) {
            log.warn("🎭 [AUTO-NOTE] capture BRANCH_TAKEN failed (non-fatal): roomId={}, err={}",
                room.getId(), e.getMessage());
        }
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  CHAPTER_END — Chapter 종료 회고
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /**
     * Chapter 종료 시 회고 노트.
     *
     * @param chapterTitle  Chapter 제목 (LLM이 batch_meta에 제공한 값 또는 default)
     * @param leaderHeroine 이번 Chapter 호감도 1위 히로인 (일러스트 화자) — null 가능
     */
    public void captureChapterEnd(
        ChatRoom room, TheaterState state, String chapterTitle,
        Character leaderHeroine
    ) {
        if (room == null || state == null) return;

        try {
            String content = (chapterTitle != null && !chapterTitle.isBlank())
                ? "Chapter " + state.getCurrentChapter() + " — " + chapterTitle
                : "Chapter " + state.getCurrentChapter() + "의 막이 내렸다.";
            TheaterDirectorNote note = saveNoteInNewTx(
                TheaterDirectorNote.chapterEnd(
                    room, content,
                    state.getCurrentAct().getNumber(),
                    state.getCurrentChapter()
                )
            );

            if (leaderHeroine != null) {
                triggerIllustration(room, leaderHeroine, "CHAPTER_END", note.getId());
            }

            log.info("🎭 [AUTO-NOTE] CHAPTER_END captured | roomId={} | act={} | chapter={} | noteId={}",
                room.getId(), state.getCurrentAct().getNumber(), state.getCurrentChapter(), note.getId());
        } catch (Exception e) {
            log.warn("🎭 [AUTO-NOTE] capture CHAPTER_END failed (non-fatal): roomId={}, err={}",
                room.getId(), e.getMessage());
        }
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  내부 — 일러스트 트리거 + 노트 저장
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /**
     * REQUIRES_NEW 트랜잭션으로 노트 저장 — 본 배치 트랜잭션 롤백과 격리.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public TheaterDirectorNote saveNoteInNewTx(TheaterDirectorNote note) {
        return directorNoteRepository.save(note);
    }

    /**
     * 일러스트 트리거 — IllustrationService에 위임 (자체적으로 비동기).
     * 폴링 완료 시 IllustrationService가 노트의 relatedIllustrationUrl을 채움.
     */
    private void triggerIllustration(
        ChatRoom room, Character heroine,
        String triggerType, Long noteId
    ) {
        try {
            illustrationService.generateAutoIllustration(
                room.getUser().getId(),
                heroine.getId(),
                room.getId(),
                triggerType,
                noteId
            );
        } catch (Exception e) {
            log.warn("🎭 [AUTO-NOTE] illustration trigger failed (non-fatal): trigger={}, noteId={}, err={}",
                triggerType, noteId, e.getMessage());
        }
    }

    private String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() > max ? s.substring(0, max) + "..." : s;
    }
}