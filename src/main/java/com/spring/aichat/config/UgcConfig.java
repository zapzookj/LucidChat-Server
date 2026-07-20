package com.spring.aichat.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

/**
 * [UGC v1] 캐릭터 생성 파이프라인 설정 활성화.
 *
 * <p>S3Presigner: fal.ai Qwen 편집의 입력 이미지(image_urls)에 서비스 S3 객체를
 * 임시 노출하기 위한 presigned GET 발급용 — 업로드는 기존 {@code S3Client} 경로.
 */
@Configuration
@EnableConfigurationProperties(UgcPipelineProperties.class)
public class UgcConfig {

    @Bean
    public S3Presigner s3Presigner(S3Properties props) {
        return S3Presigner.builder()
            .region(Region.of(props.region()))
            .credentialsProvider(
                StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(props.accessKey(), props.secretKey())
                )
            )
            .build();
    }
}
