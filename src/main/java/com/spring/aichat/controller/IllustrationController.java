package com.spring.aichat.controller;

import com.spring.aichat.service.illustration.BackgroundGenerationService;
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
 * [Phase 7-V2 Story] V2 멀티 히로인 지원 — generate body에 characterId 수용
 *
 * Endpoints:
 *   POST /api/v1/illustrations/generate       - 일러스트 생성 요청 (V1: roomId only / V2: roomId + characterId)
 *   GET  /api/v1/illustrations/status/{id}     - 생성 상태 폴링
 *   GET  /api/v1/illustrations/gallery         - 갤러리 조회
 *   GET  /api/v1/illustrations/background      - 동적 배경 캐시 폴링
 */
@RestController
@RequestMapping("/api/v1/illustrations")
@RequiredArgsConstructor
public class IllustrationController {

    private final IllustrationService illustrationService;
    private final BackgroundGenerationService backgroundGenerationService;

    /**
     * 일러스트 생성 요청.
     *
     * <p>V1 (Sandbox) body: {@code { "roomId": 1 }} — ChatRoom의 단일 character 사용.
     * <p>V2 (Story)   body: {@code { "roomId": 1, "characterId": 5 }} — 명시한 히로인 사용.
     *
     * <p>V2 STORY 방에서 characterId가 누락되면 400 Bad Request. 에너지 차감은 검증 통과 후에만.
     * 에너지 10 소모 → 생성 큐 제출 → requestId 반환. 프론트는 requestId로 폴링하여 완료 확인.
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
        Long characterId = body.get("characterId");  // V2에서만 사용. V1 호출은 null.

        IllustrationRequestResult result = illustrationService.requestIllustration(
            authentication.getName(), roomId, characterId);

        return ResponseEntity.ok(result);
    }

    /**
     * 생성 상태 폴링.
     *
     * 프론트에서 1~2초 간격으로 호출. 완료 시 imageUrl 포함하여 반환.
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
     * 일러스트 갤러리 조회.
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

    /**
     * [Phase 6-Illust hotfix] 동적 배경 생성 완료 폴링.
     *
     * <p>장소 전환이 캐시 미스로 떨어지면 SendChatResponse.locationTransition에
     * backgroundUrl=null, cacheHash=xxx, isGenerating=true가 실려 온다.
     * 프론트(LocationTransition)는 이 엔드포인트를 cacheHash로 1초 간격 폴링하여
     * 배경 완성을 감지한다.
     *
     * <p>read-only — 생성 트리거 없음. 배경 캐시는 사용자 종속이 아닌 공용 자원이므로
     * 인증만 통과하면 접근 가능 (별도 소유권 검사 불필요).
     *
     * 응답:
     *   { "ready": true,  "url": "https://..." }   — 완성됨
     *   { "ready": false, "url": null }            — 아직 생성 중
     */
    @GetMapping("/background")
    public ResponseEntity<Map<String, Object>> getBackgroundStatus(
        @RequestParam String cacheHash,
        Authentication authentication
    ) {
        String url = backgroundGenerationService.peekByCacheHash(cacheHash);
        Map<String, Object> body = new java.util.HashMap<>();
        body.put("ready", url != null);
        body.put("url", url);
        return ResponseEntity.ok(body);
    }
}