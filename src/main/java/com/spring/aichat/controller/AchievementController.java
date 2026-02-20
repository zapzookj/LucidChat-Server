package com.spring.aichat.controller;

import com.spring.aichat.dto.achievement.AchievementResponse.Gallery;
import com.spring.aichat.dto.achievement.AchievementResponse.UnlockNotification;
import com.spring.aichat.security.AuthGuard;
import com.spring.aichat.service.AchievementService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * [Phase 4.4] Achievement Controller
 *
 * GET  /api/v1/achievements       — 업적 갤러리 조회
 * POST /api/v1/achievements/unlock — 클라이언트 트리거 이스터에그 해금
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/achievements")
public class AchievementController {

    private final AchievementService achievementService;
    private final AuthGuard authGuard;

    /**
     * 업적 갤러리 조회 — 해금/미해금 분리
     */
    @GetMapping("/rooms/{roomId}/gallery")
    @PreAuthorize("@authGuard.checkRoomOwnership(#roomId, principal.subject)")
    public Gallery getGallery(@PathVariable Long roomId) {
        Long userId = authGuard.getCurrentUserId(roomId);
        return achievementService.getGallery(userId);
    }

    /**
     * 클라이언트 트리거 이스터에그 해금
     * (INVISIBLE_MAN 등 프론트에서 직접 감지하는 이스터에그)
     */
    @PostMapping("/rooms/{roomId}/unlock")
    public UnlockNotification unlockClientTriggered(@PathVariable Long roomId, @RequestBody UnlockRequest request) {
        Long userId = authGuard.getCurrentUserId(roomId);
        return achievementService.unlockClientTriggered(userId, request.code());
    }

    public record UnlockRequest(String code) {}
}