package com.spring.aichat.service.theater;

import com.spring.aichat.domain.character.Character;
import com.spring.aichat.domain.chat.ChatRoom;
import com.spring.aichat.domain.chat.ChatRoomRepository;
import com.spring.aichat.domain.enums.BranchLevel;
import com.spring.aichat.domain.enums.ChatMode;
import com.spring.aichat.domain.enums.EmotionTag;
import com.spring.aichat.domain.enums.TheaterAct;
import com.spring.aichat.domain.theater.*;
import com.spring.aichat.domain.user.User;
import com.spring.aichat.domain.user.UserRepository;
import com.spring.aichat.dto.theater.TheaterResponses.*;
import com.spring.aichat.exception.BadRequestException;
import com.spring.aichat.exception.BusinessException;
import com.spring.aichat.exception.ErrorCode;
import com.spring.aichat.exception.NotFoundException;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * [Phase 5.5-Theater] Theater 메인 서비스
 *
 * Scene 배치 소비/진행의 핵심 흐름:
 * 1. requestNextBatch()     — 다음 배치 조회 (캐시 hit → 반환 / miss → 동기 생성)
 * 2. onBatchConsumed()      — 유저가 배치 감상 완료 시 호출
 * 3. prefetchNextBatchAsync() — 70% 소비 시 비동기 prefetch
 * 4. finalizeChapter()      — Chapter 종료 처리 + 리포트 생성
 *
 * [에너지 정책]
 * Theater는 배치당 1 에너지 (ChatMode.THEATER.getBaseCost()).
 * User.consumeEnergy()를 통해 직접 차감 — 기존 프로젝트의 에너지 패턴과 일치.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TheaterService {

    private final ChatRoomRepository chatRoomRepository;
    private final TheaterStateRepository theaterStateRepository;
    private final TheaterHeroineAffectionRepository affectionRepository;
    private final TheaterBranchChoiceRepository branchChoiceRepository;
    private final TheaterBatchGenerator batchGenerator;
    private final TheaterBatchCacheService batchCache;
    private final TheaterDirectorEngine directorEngine;
    private final UserRepository userRepository;

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  1. 다음 배치 조회/생성
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    @Transactional
    public SceneBatch requestNextBatch(Long roomId, String username, boolean prefetch) {
        ChatRoom room = getOwnedRoom(roomId, username);
        TheaterState state = getState(roomId);

        if (state.isEndingReached()) throw new BadRequestException("이미 엔딩에 도달한 세션입니다.");
        if (state.isInIntermission()) throw new BadRequestException("인터미션 중입니다. 인터미션을 종료해주세요.");
        if (state.isInterventionActive()) throw new BadRequestException("난입 세션이 활성 상태입니다. 먼저 복귀해주세요.");

        // [Polish · P1 #7 + LOCATION fix] LOCATION choice 선행 가드.
        //   멀티 히로인 + 새 Chapter 진입 시점 + 아직 LOCATION 미선택 → batch LLM 호출 차단.
        //   기존 버그: 프론트가 자동 진입 시 batch 0과 LOCATION 모달이 병렬로 트리거되어
        //              batch 0이 한 번 생성되고, 분기 선택 후 invalidate → 또 생성 → LLM 비용 2배.
        //   ⚠️ 분기 기록 확인이 빠지면 LOCATION 선택 후에도 가드가 풀리지 않아 "반응 없음" 버그.
        if (state.getCurrentBatchId() == 0
            && state.getScenesInCurrentChapter() == 0
            && state.getCurrentAct().getNumber() <= 3
            && affectionRepository.findByRoom_Id(roomId).size() >= 2
            && !branchChoiceRepository.existsByRoom_IdAndActNumberAndChapterNumberAndBranchLevel(
            roomId, state.getCurrentAct().getNumber(), state.getCurrentChapter(),
            BranchLevel.LOCATION)) {
            log.info("🎭 [THEATER] Batch request blocked — LOCATION choice required | roomId={}", roomId);
            throw new BadRequestException("LOCATION_CHOICE_REQUIRED");
        }

        int batchId = state.getCurrentBatchId();

        // ─── 캐시 체크 ───
        Optional<SceneBatch> cached = batchCache.getBatch(roomId, batchId);
        if (cached.isPresent()) {
            log.info("🎭 [THEATER] Batch cache HIT | roomId={} | batchId={} | prefetch={}",
                roomId, batchId, prefetch);
            if (!prefetch) chargeBatchEnergy(username);
            return cached.get();
        }

        log.info("🎭 [THEATER] Batch cache MISS | roomId={} | batchId={} | prefetch={}",
            roomId, batchId, prefetch);

        if (!prefetch) chargeBatchEnergy(username);

        // [Phase III · 작업 3] 분기 직후 컨텍스트 consume — 그동안 dead code였음.
        //   BranchService.applyBranchChoice가 "active" 토큰으로 Redis에 저장한
        //   분기 후 컨텍스트를 여기서 처음 소비한다. consume이라 1회용 — 다음
        //   배치부터는 null로 돌아가므로 정확히 "분기 직후 첫 배치"에만 영향.
        String branchContext = batchCache.consumeBranchContext(roomId, "active").orElse(null);
        boolean justBranched = branchContext != null;

        // [Phase 6 도그푸딩 #2 결함 B / Patch B-3] 분기 시 저장된 화자 히로인 hint를 consume.
        //   Chapter 전환 직후 첫 batch에서 같은 히로인이 이어서 등장하도록 한다.
        //   hint가 없으면 null — 기존 Act 기반 분배 정책으로 fallback.
        Long hintedHeroineId = batchCache.consumeHeroineHint(roomId).orElse(null);

        TheaterBatchGenerator.GenerateParams params = new TheaterBatchGenerator.GenerateParams(
            room, state, hintedHeroineId, branchContext, false, justBranched);

        SceneBatch batch = batchGenerator.generateNextBatch(params);
        room.touch(EmotionTag.NEUTRAL); // lastActiveAt 갱신
        return batch;
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  2. 비동기 prefetch
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    @Async("theaterPrefetchExecutor")
    public CompletableFuture<Void> prefetchNextBatchAsync(Long roomId) {
        try {
            TheaterState state = getState(roomId);
            if (state.isEndingReached() || state.isInIntermission() || state.isInterventionActive()) {
                return CompletableFuture.completedFuture(null);
            }

            // [Polish · P1 #7 + LOCATION fix] LOCATION choice 선행 가드 (prefetch도 동일).
            //   분기 미선택 상태에선 어떤 batch도 LLM에 던지지 않는다.
            if (state.getCurrentBatchId() == 0
                && state.getScenesInCurrentChapter() == 0
                && state.getCurrentAct().getNumber() <= 3
                && affectionRepository.findByRoom_Id(roomId).size() >= 2
                && !branchChoiceRepository.existsByRoom_IdAndActNumberAndChapterNumberAndBranchLevel(
                roomId, state.getCurrentAct().getNumber(), state.getCurrentChapter(),
                BranchLevel.LOCATION)) {
                log.debug("🎭 [PREFETCH] Skipped — LOCATION choice required | roomId={}", roomId);
                return CompletableFuture.completedFuture(null);
            }

            int nextBatchId = state.getCurrentBatchId() + 1;
            if (batchCache.existsBatch(roomId, nextBatchId)) {
                log.debug("🎭 [PREFETCH] Already cached | roomId={} | nextBatchId={}", roomId, nextBatchId);
                return CompletableFuture.completedFuture(null);
            }

            ChatRoom room = chatRoomRepository.findById(roomId)
                .orElseThrow(() -> new NotFoundException("방을 찾을 수 없습니다."));

            // [Phase III · 작업 3] Prefetch는 branchContext를 consume하지 않는다.
            //   분기 직후 첫 배치는 동기 경로(requestNextBatch)에서 정확히 한 번
            //   consume되어야 하므로, 비동기 prefetch가 미리 가져가면 안 됨.
            //   따라서 prefetch는 항상 일반 정책(model)으로 미리 만든다.
            //   만약 prefetch 시점에 active 컨텍스트가 살아있다면, 분기 적용
            //   직후 BranchService가 invalidateBatchesFrom으로 캐시를 비웠을 것이고
            //   이후 첫 동기 호출이 그것을 consume한다.
            TheaterBatchGenerator.GenerateParams params = new TheaterBatchGenerator.GenerateParams(
                room, state, null, null, false, false);

            batchGenerator.generateNextBatch(params);
            log.info("🎭 [PREFETCH] Done | roomId={} | nextBatchId={}", roomId, nextBatchId);
        } catch (Exception e) {
            log.warn("🎭 [PREFETCH] Failed | roomId={}: {}", roomId, e.getMessage());
        }
        return CompletableFuture.completedFuture(null);
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  3. 배치 소비 완료
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    @Transactional
    public boolean onBatchConsumed(Long roomId, String username, int consumedBatchId) {
        ChatRoom room = getOwnedRoom(roomId, username);
        TheaterState state = getState(roomId);

        if (consumedBatchId != state.getCurrentBatchId()) {
            // [Phase6/Tier4 / H-22] 정책 (b): 클라이언트 stale 상태 진행 시 어긋남 누적 위험 →
            //   즉시 차단하고 새로고침 유도.
            log.warn("🎭 [THEATER] Batch ID mismatch on consume | expected={} | got={}",
                state.getCurrentBatchId(), consumedBatchId);
            throw new BusinessException(ErrorCode.STALE_CLIENT_STATE,
                "클라이언트 상태가 오래되었습니다. 새로고침 후 다시 시도해주세요.");
        }

        SceneBatch batch = batchCache.getBatch(roomId, consumedBatchId)
            .orElseThrow(() -> new BusinessException(ErrorCode.INTERNAL_ERROR,
                "소비된 배치 캐시가 없습니다. batchId=" + consumedBatchId));

        // 호감도 변화 영속화
        if (batch.heroineAffectionDeltas() != null && !batch.heroineAffectionDeltas().isEmpty()) {
            for (var entry : batch.heroineAffectionDeltas().entrySet()) {
                Long heroineId = entry.getKey();
                int delta = entry.getValue();
                affectionRepository.findByRoom_IdAndCharacter_Id(roomId, heroineId)
                    .ifPresent(a -> {
                        a.applyDelta(delta);
                        a.recordAppearance(batch.scenes() == null ? 0 : batch.scenes().size());
                    });
            }
        }

        int scenesInBatch = batch.scenes() == null ? 0 : batch.scenes().size();
        state.addScenes(scenesInBatch);
        state.setCurrentHeroine(batch.speakerHeroineId());
        state.advanceBatch();

        boolean chapterEnd = batch.chapterEndAfter() || state.isChapterComplete();
        room.touch(EmotionTag.NEUTRAL);

        log.info("🎭 [THEATER] Batch consumed | roomId={} | batchId={} | scenes={} | chapterEnd={}",
            roomId, consumedBatchId, scenesInBatch, chapterEnd);
        return chapterEnd;
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  4. Chapter 종료 + 리포트 빌드
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    @Transactional
    public ChapterReport finalizeChapter(Long roomId, String username) {
        ChatRoom room = getOwnedRoom(roomId, username);
        TheaterState state = getState(roomId);

        int finishedAct = state.getCurrentAct().getNumber();
        int finishedChapter = state.getCurrentChapter();

        // [Polish · P0 #4] Chapter 동안 감상한 씬 수를 reset 이전에 캡처.
        //   기존 버그: state.completeChapter()가 scenesInCurrentChapter를 0으로 reset
        //   하기 때문에, 이후 state.getScenesInCurrentChapter()는 항상 0 →
        //   ChapterReport.scenesConsumed가 항상 0으로 표시됐다.
        int scenesConsumedThisChapter = state.getScenesInCurrentChapter();

        // 히로인별 리포트 항목 수집
        List<TheaterHeroineAffection> affections = affectionRepository.findByRoom_Id(roomId);
        TheaterHeroineAffection leader = topAffectionBeforeSeal(affections);

        List<HeroineReportItem> heroineItems = new java.util.ArrayList<>();
        for (TheaterHeroineAffection a : affections) {
            int prev = a.getAffection() - a.getRunningDelta();
            int now = a.getAffection();
            boolean isLeader = leader != null && leader.getId().equals(a.getId());
            boolean justBecameLeader = isLeader && leaderChangedThisChapter(affections, a);

            heroineItems.add(new HeroineReportItem(
                a.getCharacter().getId(),
                a.getCharacter().getName(),
                a.getCharacter().getSlug(),            // [Polish-v2] characterSlug 추가
                a.getCharacter().getThumbnailUrl(),
                prev, now, a.getRunningDelta(),
                a.getChapterHighlightQuote(),
                isLeader, justBecameLeader
            ));
            a.sealChapterDelta();
        }

        boolean isLastChapterOfAct = directorEngine.isLastChapterOfAct(state);
        boolean transitionToNewAct = isLastChapterOfAct;

        // [Polish · 인터미션 정책 변경] Act 사이 → Chapter 사이마다 인터미션.
        //   기존: Act 사이에만 인터미션 (4 Act → 3회) → 평균 ~22점 상승. 5종 max 500 못 찍음.
        //   신규: 모든 chapter 후 인터미션 (총 ~25회) → 끝까지 진행 시 종당 60+ 가능.
        //   예외: 마지막 Act의 마지막 chapter는 엔딩 직진 — 몰입 끊김 방지.
        //         (엔딩 진입 시점엔 directorEngine이 endingReached를 set할 것이고, 그 직전이라
        //          한 번 더 stamina를 쥐여줘봤자 엔딩 후엔 의미 없음.)
        boolean isLastAct = state.getCurrentAct().next() == null;
        boolean leadsToIntermission = !(isLastAct && isLastChapterOfAct);

        if (transitionToNewAct && state.getCurrentAct().next() != null) {
            Character newMain = directorEngine.confirmMainHeroineIfApplicable(room, state);

            // [Phase 6 도그푸딩 #2 결함 B / Patch B-5 (c)] Act 3 → Act 4 진입 시
            //   currentHeroineId(직전 chapter 마지막 화자)와 confirmedMain(메인 히로인)이
            //   다를 때 인터미션 후 Act 4 첫 batch에 *자연 전환 묘사*를 강제 주입한다.
            //   채널: branchContext "active" — 인터미션 종료 후 첫 requestNextBatch가 consume.
            //   ⚠️ 콘텐츠 폴리싱 영역(Phase 6 도그푸딩 #2 결함 B Patch B-5).
            //      한국어 표현/톤은 사용자 검토 후 다듬을 수 있음.
            boolean enteringAct4 = state.getCurrentAct().next() == TheaterAct.ACT_4_RESOLUTION;
            Long lastHeroineId = state.getCurrentHeroineId();
            if (enteringAct4 && newMain != null
                && lastHeroineId != null
                && !lastHeroineId.equals(newMain.getId())) {
                String lastHeroineName = affections.stream()
                    .filter(a -> a.getCharacter().getId().equals(lastHeroineId))
                    .map(a -> a.getCharacter().getName())
                    .findFirst()
                    .orElse("이전 히로인");
                String transitionContext = String.format("""
                    [Act 4 진입 — 메인 히로인 자연 전환]
                    이전 Act의 마지막 흐름은 %s(이)와 함께였다. 그 시간은 끝맺음이 필요했고, 짧은 인터미션 동안 주인공은 자기 마음을 정리했다.
                    이제 이야기는 메인 히로인 %s에게로 향한다.

                    이번 batch의 첫 씬은 다음 두 요소를 *자연스럽게* 포함하라:
                    1) %s과(와) 짧게 마주치거나 마음 속으로 작별하는 짧은 묘사 (한 두 씬, 무겁지 않게)
                    2) %s에게로 시선/발걸음/마음이 향하는 전환 — 우연한 만남, 메시지, 또는 장소의 자연스러운 이동
                    이 전환은 *분기*가 아니라 *서사적 흐름*이다. 유저의 선택지를 만들지 말고, 이야기가 자연스럽게 %s에게로 이어지도록 묘사하라.
                    """, lastHeroineName, newMain.getName(), lastHeroineName, newMain.getName(), newMain.getName());
                batchCache.putBranchContext(roomId, "active", transitionContext);
                log.info("🎭 [THEATER] Act 4 자연 전환 컨텍스트 주입 | roomId={} | last={} | main={}",
                    roomId, lastHeroineName, newMain.getName());
            }
        }

        state.completeChapter();
        int newTargetScenes = directorEngine.decideChapterTargetScenes(state, isLastChapterOfAct);
        state.assignChapterTargetScenes(newTargetScenes);

        // Act 전환은 마지막 chapter일 때만 (정책 변경 없음)
        if (transitionToNewAct) {
            state.advanceToNextAct();
        }
        // 인터미션 시작 — 이제 매 chapter 후 (엔딩 직진 케이스 제외)
        if (leadsToIntermission) {
            state.startIntermission();
        }

        batchCache.invalidateBatchesFrom(roomId, 0);
        batchCache.clearRollingSummary(roomId);

        List<ReportBadge> badges = new java.util.ArrayList<>();
        if (transitionToNewAct) {
            badges.add(new ReportBadge(
                "ACT_TRANSITION", "막이 바뀝니다",
                "Act " + state.getCurrentAct().getNumber() + " — " + state.getCurrentAct().getTitle(),
                "🎬", null));
        }
        if (leadsToIntermission) {
            badges.add(new ReportBadge(
                "INTERMISSION", "인터미션 진입",
                "성장의 기회입니다. 피로도를 사용해 스탯을 올리세요.",
                "☕", null));
        }

        return new ChapterReport(
            finishedAct, finishedChapter, "Chapter " + finishedChapter,
            scenesConsumedThisChapter,
            branchChoiceRepository.findByRoom_IdAndActNumberOrderByChosenAtAsc(roomId, finishedAct).size(),
            new java.util.LinkedHashMap<>(), // statDeltas는 인터미션/분기에서 별도 반영
            heroineItems, badges,
            transitionToNewAct,
            transitionToNewAct ? state.getCurrentAct().getTitle() : null,
            leadsToIntermission
        );
    }

    private TheaterHeroineAffection topAffectionBeforeSeal(List<TheaterHeroineAffection> list) {
        return list.stream()
            .max((a, b) -> Integer.compare(a.getAffection(), b.getAffection()))
            .orElse(null);
    }

    private boolean leaderChangedThisChapter(List<TheaterHeroineAffection> list, TheaterHeroineAffection now) {
        TheaterHeroineAffection prevTop = list.stream()
            .max((a, b) -> Integer.compare(
                a.getAffection() - a.getRunningDelta(),
                b.getAffection() - b.getRunningDelta()))
            .orElse(null);
        return prevTop != null && !prevTop.getId().equals(now.getId());
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  5. 재생 설정
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    @Transactional
    public void updatePlaySettings(Long roomId, String username, Boolean autoPlayEnabled, String playSpeed) {
        getOwnedRoom(roomId, username);
        TheaterState state = getState(roomId);
        state.updatePlaySettings(
            autoPlayEnabled != null ? autoPlayEnabled : state.isAutoPlayEnabled(),
            playSpeed
        );
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  Helpers
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    private ChatRoom getOwnedRoom(Long roomId, String username) {
        ChatRoom room = chatRoomRepository.findWithMemberAndCharacterById(roomId)
            .orElseThrow(() -> new NotFoundException("방을 찾을 수 없습니다."));
        if (!room.getUser().getUsername().equals(username)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "접근 권한이 없습니다.");
        }
        if (room.getChatMode() != ChatMode.THEATER) {
            throw new BadRequestException("Theater 모드 방이 아닙니다.");
        }
        return room;
    }

    private TheaterState getState(Long roomId) {
        return theaterStateRepository.findByRoom_Id(roomId)
            .orElseThrow(() -> new NotFoundException("Theater 세션이 없습니다."));
    }

    /**
     * 배치 생성 시 에너지 1 차감 (User 엔티티 직접).
     *
     * [Phase6/Tier4 / H-15 정책 (b)] Theater는 *부스트 모드 영향 없이* base 비용 고정.
     *   다른 모드(STORY/SANDBOX)는 BoostModeResolver.resolveEnergyCost가 부스트 시 비용을
     *   조정하지만, Theater는 배치당 1 에너지로 평탄하게 유지한다(게임 디자인 의도).
     *   향후 정책 변경 시 boostModeResolver.resolveEnergyCost(ChatMode.THEATER, user)
     *   호출로 통일 가능.
     */
    private void chargeBatchEnergy(String username) {
        User user = userRepository.findByUsername(username)
            .orElseThrow(() -> new NotFoundException("유저를 찾을 수 없습니다: " + username));
        int cost = ChatMode.THEATER.getBaseCost();
        user.consumeEnergy(cost);
    }
}