package com.spring.aichat.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * [Phase 5.5-Illust] AWS S3 클라이언트 빈 설정.
 * [Phase 6-Illust] ModelsLabProperties 추가 — 캐릭터 트랙 신규 플랫폼.
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
        return S3Client.builder()
            .region(Region.of(props.region()))
            .credentialsProvider(
                StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(props.accessKey(), props.secretKey())
                )
            )
            .build();
    }
}