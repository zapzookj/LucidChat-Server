package com.spring.aichat.config;

import java.net.URI;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * [Phase 5.5-Illust] 오브젝트 스토리지 클라이언트 빈 설정 (S3 호환 API).
 * [Phase 6-Illust] ModelsLabProperties 추가 — 캐릭터 트랙 신규 플랫폼.
 * [2026-08-18 탈AWS] endpoint 지정 시(R2 등) endpointOverride + path-style —
 * R2는 버킷 서브도메인 대신 path-style 접근이 안전하다.
 */
@Configuration
@EnableConfigurationProperties({
    S3Properties.class,
    FalAiProperties.class,
    ModelsLabProperties.class
})
public class S3Config {

    @Bean
    public S3Client s3Client(S3Properties props) {
        var builder = S3Client.builder()
            .region(Region.of(props.region()))
            .credentialsProvider(
                StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(props.accessKey(), props.secretKey())
                )
            );
        if (props.hasCustomEndpoint()) {
            builder.endpointOverride(URI.create(props.endpoint()))
                .forcePathStyle(true);
        }
        return builder.build();
    }
}
