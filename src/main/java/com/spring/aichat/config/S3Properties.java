package com.spring.aichat.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * [Phase 5.5-Illust] 오브젝트 스토리지 설정 — S3 호환 API.
 * [2026-08-18 탈AWS] endpoint 추가: 비어 있으면 AWS S3(현행), 지정하면 해당
 * S3 호환 스토리지(Cloudflare R2 등)로 붙는다. R2는 region=auto 권장.
 *
 * application.yml 예시:
 *   aws:
 *     s3:
 *       bucket-name: "lucid-chat-assets-v2"
 *       region: "auto"
 *       access-key: "${AWS_ACCESS_KEY}"      # R2에서는 R2 API 토큰의 Access Key
 *       secret-key: "${AWS_SECRET_KEY}"
 *       assets-url: "${CLOUDFRONT_ASSETS_URL}"  # 공개 CDN 도메인 (R2 커스텀 도메인)
 *       endpoint: "https://<accountid>.r2.cloudflarestorage.com"
 */
@ConfigurationProperties(prefix = "aws.s3")
public record S3Properties(
    String bucketName,
    String region,
    String accessKey,
    String secretKey,
    String assetsUrl,
    String endpoint
) {
    /** S3 객체의 공개 URL 빌더 */
    public String buildPublicUrl(String key) {
        return assetsUrl + "/" + key;
    }

    /** 커스텀 엔드포인트(R2 등) 사용 여부 */
    public boolean hasCustomEndpoint() {
        return endpoint != null && !endpoint.isBlank();
    }
}
