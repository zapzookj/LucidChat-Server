package com.spring.aichat.controller;

import com.spring.aichat.service.illustration.IllustrationService;
import com.spring.aichat.service.illustration.IllustrationService.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * [Phase 5.5-Illust] 캐릭터 일러스트 API
 *
 * Endpoints:
 *   POST /api/v1/illustrations/generate       - 일러스트 생성 요청
 *   GET  /api/v1/illustrations/status/{id}     - 생성 상태 폴링
 *   GET  /api/v1/illustrations/gallery         - 갤러리 조회
 */
@RestController
@RequestMapping("/api/v1/illustrations")
@RequiredArgsConstructor
public class IllustrationController {

    private final IllustrationService illustrationService;

    /**
     * 일러스트 생성 요청
     *
     * Body: { "roomId": 1 }
     * 에너지 10 소모 → Fal.ai 큐 제출 → requestId 반환
     * 프론트에서 requestId로 폴링하여 완료 확인
     */
    @PostMapping("/generate")
    public ResponseEntity<IllustrationRequestResult> generateIllustration(
        @RequestBody Map<String, Long> body,
        Authentication authentication
    ) {
        Long roomId = body.get("roomId");
        if (roomId == null) {
            return ResponseEntity.badRequest().build();
        }

        IllustrationRequestResult result = illustrationService.requestIllustration(
            authentication.getName(), roomId);

        return ResponseEntity.ok(result);
    }

    /**
     * 생성 상태 폴링
     *
     * 프론트에서 1~2초 간격으로 호출.
     * 완료 시 imageUrl 포함하여 반환.
     */
    @GetMapping("/status/{requestId}")
    public ResponseEntity<IllustrationStatusResult> checkStatus(
        @PathVariable String requestId,
        Authentication authentication
    ) {
        IllustrationStatusResult result = illustrationService.checkStatus(
            requestId, authentication.getName());
        return ResponseEntity.ok(result);
    }

    /**
     * 일러스트 갤러리 조회
     *
     * @param characterId  특정 캐릭터 필터 (optional)
     */
    @GetMapping("/gallery")
    public ResponseEntity<List<IllustrationGalleryItem>> getGallery(
        @RequestParam(required = false) Long characterId,
        Authentication authentication
    ) {
        List<IllustrationGalleryItem> gallery = illustrationService.getGallery(
            authentication.getName(), characterId);
        return ResponseEntity.ok(gallery);
    }
}