package com.spring.aichat.service.theater;

import com.spring.aichat.domain.chat.ChatRoom;
import com.spring.aichat.domain.chat.ChatRoomRepository;
import com.spring.aichat.domain.enums.ChatMode;
import com.spring.aichat.domain.enums.EmotionTag;
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

        TheaterBatchGenerator.GenerateParams params = new TheaterBatchGenerator.GenerateParams(
            room, state, null, null, false);

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

            int nextBatchId = state.getCurrentBatchId() + 1;
            if (batchCache.existsBatch(roomId, nextBatchId)) {
                log.debug("🎭 [PREFETCH] Already cached | roomId={} | nextBatchId={}", roomId, nextBatchId);
                return CompletableFuture.completedFuture(null);
            }

            ChatRoom room = chatRoomRepository.findById(roomId)
                .orElseThrow(() -> new NotFoundException("방을 찾을 수 없습니다."));

            TheaterBatchGenerator.GenerateParams params = new TheaterBatchGenerator.GenerateParams(
                room, state, null, null, false);

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
            log.warn("🎭 [THEATER] Batch ID mismatch on consume | expected={} | got={}",
                state.getCurrentBatchId(), consumedBatchId);
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
                a.getCharacter().getThumbnailUrl(),
                prev, now, a.getRunningDelta(),
                a.getChapterHighlightQuote(),
                isLeader, justBecameLeader
            ));
            a.sealChapterDelta();
        }

        boolean isLastChapterOfAct = directorEngine.isLastChapterOfAct(state);
        boolean transitionToNewAct = isLastChapterOfAct;
        boolean leadsToIntermission = isLastChapterOfAct;

        if (transitionToNewAct && state.getCurrentAct().next() != null) {
            directorEngine.confirmMainHeroineIfApplicable(room, state);
        }

        state.completeChapter();
        int newTargetScenes = directorEngine.decideChapterTargetScenes(state, isLastChapterOfAct);
        state.assignChapterTargetScenes(newTargetScenes);

        if (transitionToNewAct) {
            state.advanceToNextAct();
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
            state.getScenesInCurrentChapter(),
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

    /** 배치 생성 시 에너지 1 차감 (User 엔티티 직접) */
    private void chargeBatchEnergy(String username) {
        User user = userRepository.findByUsername(username)
            .orElseThrow(() -> new NotFoundException("유저를 찾을 수 없습니다: " + username));
        int cost = ChatMode.THEATER.getBaseCost();
        user.consumeEnergy(cost);
    }
}