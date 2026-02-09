package com.spring.aichat.controller;

import com.spring.aichat.dto.chat.NarratorResponse;
import com.spring.aichat.dto.chat.SendChatResponse;
import com.spring.aichat.service.NarratorService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/story/rooms/{roomId}")
public class StoryController {

    private final NarratorService narratorService;

    // 1. 이벤트 생성 (옵션 3개 반환)
    @PostMapping("/events")
    public NarratorResponse triggerEvent(@PathVariable Long roomId) {
        return narratorService.triggerEvent(roomId);
    }

    // 2. 이벤트 선택 (실행 및 캐릭터 반응 반환)
    // RequestBody로 선택한 detail과 cost를 받습니다.
    @PostMapping("/events/select")
    public SendChatResponse selectEvent(
        @PathVariable Long roomId,
        @RequestBody SelectEventRequest request
    ) {
        return narratorService.selectEvent(roomId, request.detail(), request.energyCost());
    }

    // DTO (Inner Record)
    public record SelectEventRequest(String detail, int energyCost) {}
}
