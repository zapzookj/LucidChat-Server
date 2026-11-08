package com.spring.aichat.controller;

import com.spring.aichat.dto.ugc.UgcDtos;
import com.spring.aichat.exception.RateLimitException;
import com.spring.aichat.security.ApiRateLimiter;
import com.spring.aichat.service.ugc.ReferenceImageService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * [docs/56 §10 그림 방식] 레퍼런스 그림 업로드·검사.
 *
 * <pre>
 * POST /api/v1/ugc/references   multipart "file" (PNG·JPG·WebP ≤10MB) → ReferenceView (에너지 무과금)
 * </pre>
 * 판정이 OK가 아니어도 200으로 verdict·message를 돌려준다(위저드가 원인별 안내). 형식·용량 위반만 400.
 */
@RestController
@RequestMapping("/api/v1/ugc/references")
@RequiredArgsConstructor
public class ReferenceImageController {

    private final ReferenceImageService referenceImageService;
    private final ApiRateLimiter rateLimiter;

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<UgcDtos.ReferenceView> upload(
        @RequestPart("file") MultipartFile file,
        Authentication authentication
    ) {
        if (rateLimiter.checkUgcMutation(authentication.getName())) {
            throw new RateLimitException("요청이 너무 빠릅니다.", 5);
        }
        return ResponseEntity.ok(referenceImageService.upload(authentication.getName(), file));
    }
}
