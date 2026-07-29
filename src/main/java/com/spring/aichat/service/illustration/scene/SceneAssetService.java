package com.spring.aichat.service.illustration.scene;

import com.spring.aichat.config.S3Properties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;

/**
 * [2026-07-30 B-2/A-1 재피벗] 씬 렌더 산출 보관 — {@code illustrations/scenes/} 키 네임스페이스.
 * presigned URL 저장 금지 원칙(UGC 관례 동일): 수신 즉시 서비스 S3 복사, CDN 공개 URL만 노출.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SceneAssetService {

    static final int DOWNLOAD_LIMIT_BYTES = 30 * 1024 * 1024;

    private static final String SCENE_PREFIX = "illustrations/scenes/";

    private final S3Client s3Client;
    private final S3Properties s3Props;

    private final HttpClient httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build();

    /** 씬 렌더 산출을 방/턴 키로 보관 → CDN 공개 URL 반환. */
    public String storeScene(String sourceUrl, Long roomId, int turnIndex) {
        byte[] bytes = downloadBytes(sourceUrl);
        String key = SCENE_PREFIX + "r" + roomId + "/turn_" + turnIndex + "_" + shortUuid() + ".png";
        s3Client.putObject(PutObjectRequest.builder()
                .bucket(s3Props.bucketName())
                .key(key)
                .contentType("image/png")
                .cacheControl("public, max-age=31536000, immutable")
                .build(),
            RequestBody.fromBytes(bytes));
        log.info("[SCENE-ASSET] stored: key={} ({} bytes)", key, bytes.length);
        return s3Props.buildPublicUrl(key);
    }

    private byte[] downloadBytes(String sourceUrl) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(sourceUrl))
                .timeout(Duration.ofSeconds(60))
                .GET()
                .build();
            HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() != 200) {
                throw new IllegalStateException("에셋 다운로드 실패: HTTP " + response.statusCode());
            }
            byte[] body = response.body();
            if (body == null || body.length == 0) {
                throw new IllegalStateException("에셋 다운로드 실패: 빈 응답");
            }
            if (body.length > DOWNLOAD_LIMIT_BYTES) {
                throw new IllegalStateException("에셋 크기 초과: " + body.length + " bytes");
            }
            return body;
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("에셋 다운로드 실패: " + e.getMessage(), e);
        }
    }

    private static String shortUuid() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
