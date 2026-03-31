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
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * [Phase 5.5-Illust] S3 스토리지 서비스
 *
 * 이미지를 S3에 영구 적재 → 공개 URL 반환
 *
 * [Phase 5.5-RunPod] Base64 업로드 지원 추가
 *   - RunPod ComfyUI는 이미지를 URL이 아닌 Base64 raw data로 반환
 *   - uploadFromBase64(): Base64 디코딩 → S3 직접 업로드 (HTTP 다운로드 불필요)
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class S3StorageService {

    private final S3Client s3Client;
    private final S3Properties s3Props;

    private static final String ILLUSTRATION_PREFIX = "illustrations/";
    private static final String BACKGROUND_PREFIX = "backgrounds/";

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  [RunPod] Base64 → S3 직접 업로드
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /**
     * Base64 인코딩된 이미지 데이터를 S3에 업로드
     *
     * @param base64Data  Base64 인코딩된 PNG 이미지 (data:image/png;base64, 접두사 허용)
     * @param prefix      S3 키 접두사 (illustrations/ 또는 backgrounds/)
     * @param filename    저장할 파일명 (null이면 UUID 자동 생성)
     * @return S3 공개 URL
     */
    public String uploadFromBase64(String base64Data, String prefix, String filename) {
        try {
            // data:image/png;base64, 접두사 제거 (있을 경우)
            String cleanBase64 = base64Data;
            if (cleanBase64.contains(",")) {
                cleanBase64 = cleanBase64.substring(cleanBase64.indexOf(",") + 1);
            }
            // 줄바꿈/공백 제거 (간혹 포함됨)
            cleanBase64 = cleanBase64.replaceAll("\\s+", "");

            byte[] imageBytes = Base64.getDecoder().decode(cleanBase64);

            // 이미지 포맷 감지 (PNG 매직넘버: 0x89504E47)
            String contentType = "image/png";
            String ext = ".png";
            if (imageBytes.length >= 3 && imageBytes[0] == (byte) 0xFF
                && imageBytes[1] == (byte) 0xD8 && imageBytes[2] == (byte) 0xFF) {
                contentType = "image/jpeg";
                ext = ".jpg";
            }

            String key = prefix + (filename != null ? filename : UUID.randomUUID().toString()) + ext;

            PutObjectRequest putRequest = PutObjectRequest.builder()
                .bucket(s3Props.bucketName())
                .key(key)
                .contentType(contentType)
                .cacheControl("public, max-age=31536000, immutable")
                .build();

            s3Client.putObject(putRequest, RequestBody.fromBytes(imageBytes));

            String publicUrl = s3Props.buildPublicUrl(key);
            log.info("[S3] Base64 uploaded: {} ({} bytes, {})", publicUrl, imageBytes.length, contentType);

            return publicUrl;

        } catch (IllegalArgumentException e) {
            log.error("[S3] Base64 decode failed: {}", e.getMessage());
            throw new RuntimeException("S3 base64 upload failed: invalid base64 data", e);
        } catch (Exception e) {
            log.error("[S3] Base64 upload failed", e);
            throw new RuntimeException("S3 base64 upload failed: " + e.getMessage(), e);
        }
    }

    /**
     * [RunPod] 캐릭터 일러스트 Base64 업로드
     */
    public String uploadIllustrationFromBase64(String base64Data, Long userId, Long characterId, String requestId) {
        String filename = "user_%d_char_%d_%s".formatted(userId, characterId, requestId.substring(0, 8));
        return uploadFromBase64(base64Data, ILLUSTRATION_PREFIX, filename);
    }

    /**
     * [RunPod] 장소 배경 Base64 업로드
     */
    public String uploadBackgroundFromBase64(String base64Data, String cacheKey) {
        String sanitizedKey = cacheKey.replaceAll("[^a-zA-Z0-9_-]", "_");
        return uploadFromBase64(base64Data, BACKGROUND_PREFIX, sanitizedKey);
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  [레거시] URL → 다운로드 → S3 업로드
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /**
     * 외부 URL의 이미지를 다운로드하여 S3에 업로드
     * @deprecated RunPod 환경에서는 uploadFromBase64() 사용
     */
    @Deprecated
    public String downloadAndUpload(String sourceUrl, String prefix, String filename) {
        try {
            log.info("[S3] Downloading from URL: {}", sourceUrl);

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

            String ext = contentType.contains("jpeg") ? ".jpg" : ".png";
            String key = prefix + (filename != null ? filename : UUID.randomUUID().toString()) + ext;

            PutObjectRequest putRequest = PutObjectRequest.builder()
                .bucket(s3Props.bucketName())
                .key(key)
                .contentType(contentType)
                .cacheControl("public, max-age=31536000, immutable")
                .build();

            s3Client.putObject(putRequest, RequestBody.fromBytes(imageBytes));

            String publicUrl = s3Props.buildPublicUrl(key);
            log.info("[S3] URL uploaded: {} ({} bytes)", publicUrl, imageBytes.length);

            return publicUrl;

        } catch (Exception e) {
            log.error("[S3] URL upload failed: sourceUrl={}", sourceUrl, e);
            throw new RuntimeException("S3 upload failed: " + e.getMessage(), e);
        }
    }

    /** @deprecated RunPod 환경에서는 uploadIllustrationFromBase64() 사용 */
    @Deprecated
    @Async
    public CompletableFuture<String> uploadIllustrationAsync(String sourceUrl, Long userId, Long characterId) {
        String filename = "user_%d_char_%d_%s".formatted(userId, characterId, UUID.randomUUID().toString().substring(0, 8));
        String url = downloadAndUpload(sourceUrl, ILLUSTRATION_PREFIX, filename);
        return CompletableFuture.completedFuture(url);
    }

    /** @deprecated RunPod 환경에서는 uploadBackgroundFromBase64() 사용 */
    @Deprecated
    public String uploadBackground(String sourceUrl, String cacheKey) {
        String sanitizedKey = cacheKey.replaceAll("[^a-zA-Z0-9_-]", "_");
        return downloadAndUpload(sourceUrl, BACKGROUND_PREFIX, sanitizedKey);
    }
}