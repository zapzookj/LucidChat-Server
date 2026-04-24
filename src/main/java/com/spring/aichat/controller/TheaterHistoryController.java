package com.spring.aichat.controller;

import com.spring.aichat.dto.theater.TheaterResponses.SceneHistoryItem;
import com.spring.aichat.dto.theater.TheaterResponses.SceneHistoryPage;
import com.spring.aichat.service.theater.TheaterHistoryService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * [Phase 5.5-Theater-Polish] 대화 기록 엔드포인트
 *
 * 이슈 #4 해결: 이전 버튼 / 대화 기록 패널에 데이터 공급.
 *
 * [Endpoints]
 * GET /api/v1/theater/rooms/{roomId}/scene-history/chapter/{actNumber}/{chapterNumber}
 * GET /api/v1/theater/rooms/{roomId}/scene-history/paginated?page=0&size=30
 * GET /api/v1/theater/rooms/{roomId}/scene-history/recent?count=20
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/theater/rooms/{roomId}/scene-history")
public class TheaterHistoryController {

    private final TheaterHistoryService historyService;

    @GetMapping("/chapter/{actNumber}/{chapterNumber}")
    @PreAuthorize("@authGuard.checkRoomOwnership(#roomId, principal.subject)")
    public List<SceneHistoryItem> getChapterHistory(
        @PathVariable Long roomId,
        @PathVariable int actNumber,
        @PathVariable int chapterNumber,
        Authentication authentication
    ) {
        return historyService.getChapterHistory(
            roomId, authentication.getName(), actNumber, chapterNumber);
    }

    @GetMapping("/paginated")
    @PreAuthorize("@authGuard.checkRoomOwnership(#roomId, principal.subject)")
    public SceneHistoryPage getPaginatedHistory(
        @PathVariable Long roomId,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "30") int size,
        Authentication authentication
    ) {
        return historyService.getPaginatedHistory(
            roomId, authentication.getName(), page, size);
    }

    @GetMapping("/recent")
    @PreAuthorize("@authGuard.checkRoomOwnership(#roomId, principal.subject)")
    public List<SceneHistoryItem> getRecentScenes(
        @PathVariable Long roomId,
        @RequestParam(defaultValue = "20") int count,
        Authentication authentication
    ) {
        return historyService.getRecentScenes(
            roomId, authentication.getName(), count);
    }
}