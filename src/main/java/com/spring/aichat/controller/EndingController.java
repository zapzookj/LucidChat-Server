package com.spring.aichat.controller;

import com.spring.aichat.domain.enums.EndingType;
import com.spring.aichat.dto.chat.EndingResponse;
import com.spring.aichat.service.EndingService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * 엔딩 이벤트 컨트롤러
 *
 * [Phase 4] 분기별 엔딩 이벤트 시스템
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/ending/rooms/{roomId}")
public class EndingController {

    private final EndingService endingService;

    /**
     * 엔딩 데이터 생성
     *
     * 프론트에서 endingTrigger를 받은 후 호출.
     * LLM으로 엔딩 씬 + 타이틀 + 추억 + 통계를 생성하여 반환.
     */
    @PostMapping("/generate")
    public EndingResponse generateEnding(
        @PathVariable Long roomId,
        @RequestBody GenerateEndingRequest request
    ) {
        EndingType type = EndingType.valueOf(request.endingType().toUpperCase());
        return endingService.generateEnding(roomId, type);
    }

    public record GenerateEndingRequest(String endingType) {}
}