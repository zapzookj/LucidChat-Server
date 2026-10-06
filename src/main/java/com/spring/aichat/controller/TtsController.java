package com.spring.aichat.controller;

import com.spring.aichat.service.tts.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController @RequiredArgsConstructor @RequestMapping("/api/v1/tts")
public class TtsController {
    private final TtsService tts;
    private final TtsGreetingService greetings;
    public record ModeRequest(boolean enabled, int expectedEnergyCost) {}
    @GetMapping("/rooms/{roomId}")
    public TtsService.Mode mode(@PathVariable Long roomId, Authentication auth) { return tts.mode(auth.getName(), roomId); }
    @PatchMapping("/rooms/{roomId}")
    public TtsService.Mode mode(@PathVariable Long roomId, @RequestBody ModeRequest request, Authentication auth) {
        return tts.setMode(auth.getName(), roomId, request.enabled(), request.expectedEnergyCost());
    }
    @GetMapping("/rooms/{roomId}/responses/{logId}")
    public TtsService.View status(@PathVariable Long roomId, @PathVariable String logId, Authentication auth) {
        return tts.status(auth.getName(), roomId, logId);
    }
    @PostMapping("/rooms/{roomId}/responses/{logId}")
    public TtsService.View unlock(@PathVariable Long roomId, @PathVariable String logId, @RequestBody TtsService.Unlock request, Authentication auth) {
        return tts.unlock(auth.getName(), roomId, logId, request);
    }
    @GetMapping("/rooms/{roomId}/responses/{logId}/clips/{sceneIndex}")
    public ResponseEntity<byte[]> audio(@PathVariable Long roomId, @PathVariable String logId, @PathVariable int sceneIndex, Authentication auth) {
        return audioResponse(tts.audio(auth.getName(), roomId, logId, sceneIndex));
    }
    @GetMapping("/greetings/{slug}")
    public ResponseEntity<byte[]> greeting(@PathVariable String slug) { return audioResponse(greetings.audio(slug)); }
    private ResponseEntity<byte[]> audioResponse(byte[] bytes) {
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("audio/mpeg"))
            .cacheControl(CacheControl.noStore()).header("X-Content-Type-Options", "nosniff").body(bytes);
    }
    @ExceptionHandler(org.springframework.web.server.ResponseStatusException.class)
    public ResponseEntity<?> handleStatus(org.springframework.web.server.ResponseStatusException error) {
        return ResponseEntity.status(error.getStatusCode()).body(java.util.Map.of("status", error.getStatusCode().value(),
            "message", java.util.Objects.toString(error.getReason(), "보이스 요청을 처리할 수 없습니다.")));
    }
}
