package com.spring.aichat.service.storage;

import com.spring.aichat.config.S3Properties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * [Phase 5.5-Illust] S3 스토리지 서비스
 *
 * Fal.ai에서 생성된 이미지를 다운로드 → S3에 영구 적재 → 공개 URL 반환
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class S3StorageService {

    private final S3Client s3Client;
    private final S3Properties s3Props;

    private static final String ILLUSTRATION_PREFIX = "illustrations/";
    private static final String BACKGROUND_PREFIX = "backgrounds/";

    /**
     * 외부 URL의 이미지를 다운로드하여 S3에 업로드
     *
     * @param sourceUrl  Fal.ai 생성 이미지 URL (임시)
     * @param prefix     S3 키 접두사 (illustrations/ 또는 backgrounds/)
     * @param filename   저장할 파일명 (null이면 UUID 자동 생성)
     * @return S3 공개 URL
     */
    public String downloadAndUpload(String sourceUrl, String prefix, String filename) {
        try {
            log.info("[S3] Downloading from Fal.ai: {}", sourceUrl);

            // 이미지 다운로드
            HttpClient httpClient = HttpClient.newHttpClient();
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(sourceUrl))
                .GET()
                .build();

            HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());

            if (response.statusCode() != 200) {
                throw new RuntimeException("Image download failed: HTTP " + response.statusCode());
            }

            byte[] imageBytes = response.body();
            String contentType = response.headers()
                .firstValue("Content-Type")
                .orElse("image/png");

            // S3 키 생성
            String ext = contentType.contains("jpeg") ? ".jpg" : ".png";
            String key = prefix + (filename != null ? filename : UUID.randomUUID().toString()) + ext;

            // S3 업로드
            PutObjectRequest putRequest = PutObjectRequest.builder()
                .bucket(s3Props.bucketName())
                .key(key)
                .contentType(contentType)
                .cacheControl("public, max-age=31536000, immutable")
                .build();

            s3Client.putObject(putRequest, RequestBody.fromBytes(imageBytes));

            String publicUrl = s3Props.buildPublicUrl(key);
            log.info("[S3] Uploaded: {} ({} bytes)", publicUrl, imageBytes.length);

            return publicUrl;

        } catch (Exception e) {
            log.error("[S3] Upload failed: sourceUrl={}", sourceUrl, e);
            throw new RuntimeException("S3 upload failed: " + e.getMessage(), e);
        }
    }

    /**
     * 캐릭터 일러스트 업로드 (비동기)
     */
    @Async
    public CompletableFuture<String> uploadIllustrationAsync(String sourceUrl, Long userId, Long characterId) {
        String filename = "user_%d_char_%d_%s".formatted(userId, characterId, UUID.randomUUID().toString().substring(0, 8));
        String url = downloadAndUpload(sourceUrl, ILLUSTRATION_PREFIX, filename);
        return CompletableFuture.completedFuture(url);
    }

    /**
     * 장소 배경 업로드 (동기 — 캐시 저장이 선행되어야 하므로)
     */
    public String uploadBackground(String sourceUrl, String cacheKey) {
        String sanitizedKey = cacheKey.replaceAll("[^a-zA-Z0-9_-]", "_");
        return downloadAndUpload(sourceUrl, BACKGROUND_PREFIX, sanitizedKey);
    }
}