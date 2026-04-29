package com.spring.aichat.service.theater;

import com.spring.aichat.domain.chat.ChatRoom;
import com.spring.aichat.domain.chat.ChatRoomRepository;
import com.spring.aichat.domain.theater.TheaterSceneLog;
import com.spring.aichat.domain.theater.TheaterSceneLogRepository;
import com.spring.aichat.dto.theater.TheaterResponses.SceneHistoryItem;
import com.spring.aichat.dto.theater.TheaterResponses.SceneHistoryPage;
import com.spring.aichat.exception.BusinessException;
import com.spring.aichat.exception.ErrorCode;
import com.spring.aichat.exception.NotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * [Phase 5.5-Theater-Polish] 대화 기록 조회 서비스
 *
 * 이슈 #4 (이전 버튼 / 대화 기록 부재) 해결의 백엔드 측.
 * TheaterSceneLog(MongoDB)을 조회해 시간순 씬 목록을 반환.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TheaterHistoryService {

    private final ChatRoomRepository chatRoomRepository;
    private final TheaterSceneLogRepository sceneLogRepository;

    /**
     * 특정 Chapter의 모든 씬 조회 (대화 기록 패널의 Chapter 탭용)
     */
    public List<SceneHistoryItem> getChapterHistory(
        Long roomId, String username, int actNumber, int chapterNumber
    ) {
        verifyOwnership(roomId, username);
        List<TheaterSceneLog> logs = sceneLogRepository
            .findByRoomIdAndActNumberAndChapterNumberOrderBySceneSeqInChapterAsc(
                roomId, actNumber, chapterNumber);

        return logs.stream().map(this::toHistoryItem).toList();
    }

    /**
     * 페이지네이션 전체 조회 (가장 오래된 것부터)
     */
    public SceneHistoryPage getPaginatedHistory(
        Long roomId, String username, int page, int size
    ) {
        verifyOwnership(roomId, username);
        int safeSize = Math.min(Math.max(size, 10), 100);
        Page<TheaterSceneLog> pageData = sceneLogRepository
            .findByRoomIdOrderByGlobalSceneSeqAsc(roomId, PageRequest.of(page, safeSize));

        List<SceneHistoryItem> items = pageData.getContent().stream()
            .map(this::toHistoryItem).toList();

        return new SceneHistoryPage(
            items,
            page,
            safeSize,
            pageData.getTotalPages(),
            pageData.getTotalElements()
        );
    }

    /**
     * 최근 N개 씬 조회 (플레이어의 '이전' 버튼이 현재 배치 밖으로 나갈 때 사용)
     */
    public List<SceneHistoryItem> getRecentScenes(Long roomId, String username, int count) {
        verifyOwnership(roomId, username);
        int safeCount = Math.min(Math.max(count, 1), 50);

        List<TheaterSceneLog> logs = sceneLogRepository
            .findTop30ByRoomIdOrderByGlobalSceneSeqDesc(roomId);

        if (logs.size() > safeCount) logs = logs.subList(0, safeCount);

        // 시간순으로 반환 (최근 → 역순 → 시간순)
        return logs.stream()
            .sorted((a, b) -> Long.compare(a.getGlobalSceneSeq(), b.getGlobalSceneSeq()))
            .map(this::toHistoryItem)
            .toList();
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  Helpers
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    private SceneHistoryItem toHistoryItem(TheaterSceneLog log) {
        // [Phase 5.5 UX Polish · R1] innerNarration은 의미상 protagonistInner와 동일.
        // 신/구 필드 모두에 같은 값을 채워 응답 (구버전 클라이언트 호환).
        String protagonistInner = log.getInnerNarration();
        return new SceneHistoryItem(
            log.getId(),
            log.getActNumber(),
            log.getChapterNumber(),
            log.getBatchId(),
            log.getSceneIndexInBatch(),
            log.getSceneSeqInChapter(),
            log.getGlobalSceneSeq(),
            log.getNarration(),
            protagonistInner,             // 신규: protagonistInner
            log.getHeroineInner(),        // 신규: heroineInner (UI 미노출이지만 응답엔 포함)
            protagonistInner,             // alias: innerNarration (구버전 호환)
            log.getDialogue(),
            log.getSpeakerType(),
            log.getSpeakerName(),
            log.getHeroineId(),
            log.getSceneType(),           // 신규: sceneType
            log.getEmotion() == null ? null : log.getEmotion().name(),
            log.getLocation(),
            log.getTimeOfDay(),
            log.getOutfit(),
            log.getBgmMode(),
            log.getIllustrationUrl(),
            log.getCreatedAt()
        );
    }

    private void verifyOwnership(Long roomId, String username) {
        ChatRoom room = chatRoomRepository.findById(roomId)
            .orElseThrow(() -> new NotFoundException("방을 찾을 수 없습니다."));
        if (!room.getUser().getUsername().equals(username)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "접근 권한이 없습니다.");
        }
    }
}