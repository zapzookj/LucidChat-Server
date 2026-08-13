package com.spring.aichat.controller;

import com.spring.aichat.domain.user.UserRepository;
import com.spring.aichat.dto.achievement.AchievementResponse.Gallery;
import com.spring.aichat.dto.achievement.AchievementResponse.UnlockNotification;
import com.spring.aichat.exception.NotFoundException;
import com.spring.aichat.security.AuthGuard;
import com.spring.aichat.service.AchievementService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/**
 * [Phase 4.4] Achievement Controller
 *
 * GET  /api/v1/achievements/gallery — [블록 A] 유저 스코프 업적 갤러리 (보관함 탭)
 * GET  /api/v1/achievements/rooms/{roomId}/gallery — 방 URL 경유 갤러리 (챗 내 열람 호환)
 * POST /api/v1/achievements/rooms/{roomId}/unlock — 클라이언트 트리거 이스터에그 해금
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/achievements")
public class AchievementController {

    private final AchievementService achievementService;
    private final AuthGuard authGuard;
    private final UserRepository userRepository;

    /**
     * [블록 A 보관함] 유저 스코프 업적 갤러리 — 방 무관 전역 수집품 뷰.
     * 데이터는 원래 유저 단위였고(getGallery(userId)) URL만 방 스코프였다(docs/13 E-4) —
     * 로비 보관함은 방 컨텍스트가 없으므로 이 경로를 쓴다. 인증 필수(게스트 비개방).
     */
    @GetMapping("/gallery")
    public Gallery getMyGallery(Authentication authentication) {
        Long userId = userRepository.findByUsername(authentication.getName())
            .orElseThrow(() -> new NotFoundException("유저를 찾을 수 없습니다."))
            .getId();
        return achievementService.getGallery(userId);
    }

    /**
     * 업적 갤러리 조회 — 해금/미해금 분리 (챗 내 방 URL 경유 호환 경로)
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
    @PreAuthorize("@authGuard.checkRoomOwnership(#roomId, principal.subject)")
    public UnlockNotification unlockClientTriggered(@PathVariable Long roomId, @RequestBody UnlockRequest request) {
        Long userId = authGuard.getCurrentUserId(roomId);
        return achievementService.unlockClientTriggered(userId, request.code());
    }

    public record UnlockRequest(String code) {}
}