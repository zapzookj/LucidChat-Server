package com.spring.aichat.controller;

import com.spring.aichat.config.LegacyFeatureProperties;
import com.spring.aichat.domain.enums.ChatMode;
import com.spring.aichat.domain.chat.ChatRoomRepository;
import com.spring.aichat.domain.enums.EndingType;
import com.spring.aichat.exception.BadRequestException;
import com.spring.aichat.exception.NotFoundException;
import com.spring.aichat.dto.chat.EndingResponse;
import com.spring.aichat.service.EndingService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
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
    private final ChatRoomRepository chatRoomRepository;
    private final LegacyFeatureProperties legacy;

    /**
     * 엔딩 데이터 생성
     *
     * 프론트에서 endingTrigger를 받은 후 호출.
     * LLM으로 엔딩 씬 + 타이틀 + 추억 + 통계를 생성하여 반환.
     */
    @PostMapping("/generate")
    @PreAuthorize("@authGuard.checkRoomOwnership(#roomId, principal.subject)")
    public EndingResponse generateEnding(
        @PathVariable Long roomId,
        @RequestBody GenerateEndingRequest request
    ) {
        // [블록 D · docs/14 §C#6] 자유·스토리 엔딩 게이트. 서버에서 닫는다 —
        //   프론트 진입점만 지우면 소유권 검사만 통과하면 열리는 API가 남는다
        //   (beta-activate가 정확히 그 실수였다 — docs/14_assets §5).
        if (!legacy.getEnding().isDialogueEnabled()) {
            throw new BadRequestException("엔딩은 현재 제공되지 않는 기능입니다.");
        }
        // [docs/13 B-8.5] chatMode 가드 부재 — 극장 방 ID로 V1 엔딩 파이프라인(LLM 3콜)을
        //   그대로 돌릴 수 있었다. 극장 엔딩은 TheaterFinalityController 소관이다.
        ChatMode mode = chatRoomRepository.findById(roomId)
            .orElseThrow(() -> new NotFoundException("방을 찾을 수 없습니다."))
            .getChatMode();
        if (mode == ChatMode.THEATER) {
            throw new BadRequestException("극장 세션의 엔딩은 이 경로로 생성할 수 없습니다.");
        }
        EndingType type = EndingType.valueOf(request.endingType().toUpperCase());
        return endingService.generateEnding(roomId, type);
    }

    public record GenerateEndingRequest(String endingType) {}
}