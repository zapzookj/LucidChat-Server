package com.spring.aichat.service.ugc;

import com.spring.aichat.config.S3Properties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CopyObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;

/**
 * [UGC v1] 파이프라인 에셋 이동/보관 서비스.
 *
 * <p><b>원칙: 외부 presigned URL은 절대 저장하지 않는다</b> — 수신 즉시 서비스 자산 S3로
 * 복사하고 우리 키만 보관한다. 유저에게 노출되는 URL은 전부 CloudFront 공개 경로.
 *
 * <p>키 레이아웃:
 * <ul>
 *   <li>중간 산출물: {@code ugc/jobs/{jobId}/{label}_{uuid8}.png} — 리롤마다 새 키
 *       (CloudFront immutable 캐시와 충돌하지 않도록 덮어쓰기 금지)</li>
 *   <li>확정본: {@code characters/{slug}/{outfit}_{emotion}.png} — 프런트
 *       CharacterDisplay의 공식 규약({outfit}_{emotion}.png, 소문자)과 동일. UGC 복장은 "default".</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UgcAssetService {

    /** 외부 산출물 다운로드 상한 (presigned 1장 기준 — SDXL PNG는 1~3MB대). */
    static final int DOWNLOAD_LIMIT_BYTES = 30 * 1024 * 1024;

    private static final String JOB_PREFIX = "ugc/jobs/";
    private static final String CHARACTER_PREFIX = "characters/";

    private final S3Client s3Client;
    private final S3Presigner s3Presigner;
    private final S3Properties s3Props;

    private final HttpClient httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build();

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  외부 URL → 서비스 S3 (즉시 복사 원칙)
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /**
     * RunPod/fal presigned URL의 산출물을 잡 중간 에셋으로 복사.
     *
     * @param label 용도 라벨 (golden_0, base, emo_joy, cut_joy ...)
     * @return 서비스 S3 키
     */
    public String storeFromUrl(String sourceUrl, Long jobId, String label) {
        byte[] bytes = downloadBytes(sourceUrl);
        String key = JOB_PREFIX + jobId + "/" + label + "_" + shortUuid() + ".png";
        putPng(key, bytes);
        log.info("[UGC-ASSET] stored: key={} ({} bytes)", key, bytes.length);
        return key;
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  확정본 승격 (Stage 4 바인딩)
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /**
     * 잡 중간 에셋을 캐릭터 확정 경로로 서버사이드 복사.
     *
     * @param filename 확정 파일명 (예: default_neutral.png, thumbnail.png)
     * @return 확정 키 (characters/{slug}/{filename})
     */
    public String promoteToCharacterAsset(String srcKey, String slug, String filename) {
        String destKey = CHARACTER_PREFIX + slug + "/" + filename;
        s3Client.copyObject(CopyObjectRequest.builder()
            .sourceBucket(s3Props.bucketName())
            .sourceKey(srcKey)
            .destinationBucket(s3Props.bucketName())
            .destinationKey(destKey)
            .cacheControl("public, max-age=31536000, immutable")
            .metadataDirective("REPLACE")
            .contentType("image/png")
            .build());
        log.info("[UGC-ASSET] promoted: {} → {}", srcKey, destKey);
        return destKey;
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  조회/전달
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /** 서비스 S3 객체 바이트 (RunPod input.images base64 주입용). */
    public byte[] download(String key) {
        ResponseBytes<GetObjectResponse> bytes = s3Client.getObjectAsBytes(
            GetObjectRequest.builder().bucket(s3Props.bucketName()).key(key).build());
        return bytes.asByteArray();
    }

    /**
     * fal.ai 입력용 presigned GET — 큐 대기를 커버할 만료 시간 필수(권장 1시간+).
     */
    public String presignGet(String key, Duration ttl) {
        return s3Presigner.presignGetObject(GetObjectPresignRequest.builder()
                .signatureDuration(ttl)
                .getObjectRequest(GetObjectRequest.builder()
                    .bucket(s3Props.bucketName()).key(key).build())
                .build())
            .url().toString();
    }

    /** CloudFront 공개 URL (프런트 노출용 — presigned 금지 원칙). */
    public String publicUrl(String key) {
        if (key == null) return null;
        return s3Props.buildPublicUrl(key);
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

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

    private void putPng(String key, byte[] bytes) {
        s3Client.putObject(PutObjectRequest.builder()
                .bucket(s3Props.bucketName())
                .key(key)
                .contentType("image/png")
                .cacheControl("public, max-age=31536000, immutable")
                .build(),
            RequestBody.fromBytes(bytes));
    }

    private static String shortUuid() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
