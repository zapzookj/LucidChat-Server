package com.spring.aichat.controller;

import com.spring.aichat.dto.chat.NarratorResponse;
import com.spring.aichat.service.NarratorService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/story")
public class StoryController {

    private final NarratorService narratorService;

    /**
     * 상황 전환 이벤트 트리거 (나레이터 개입)
     */
    @PostMapping("/rooms/{roomId}/events")
    @PreAuthorize("@authGuard.checkRoomOwnership(#roomId, principal.subject)")
    public NarratorResponse triggerEvent(@PathVariable Long roomId) {
        return narratorService.triggerEvent(roomId);
    }
}
