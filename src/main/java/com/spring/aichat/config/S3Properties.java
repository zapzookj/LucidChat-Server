package com.spring.aichat.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * [Phase 5.5-Illust] AWS S3 스토리지 설정
 *
 * application.yml 예시:
 *   aws:
 *     s3:
 *       bucket-name: "lucid-chat-assets"
 *       region: "ap-northeast-2"
 *       access-key: "YOUR_AWS_ACCESS_KEY"
 *       secret-key: "YOUR_AWS_SECRET_KEY"
 */
@ConfigurationProperties(prefix = "aws.s3")
public record S3Properties(
    String bucketName,
    String region,
    String accessKey,
    String secretKey
) {
    /** S3 객체의 공개 URL 빌더 */
    public String buildPublicUrl(String key) {
        return String.format("https://%s.s3.%s.amazonaws.com/%s", bucketName, region, key);
    }
}