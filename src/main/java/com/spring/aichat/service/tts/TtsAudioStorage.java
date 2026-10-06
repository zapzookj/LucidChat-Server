package com.spring.aichat.service.tts;

import com.spring.aichat.config.S3Properties;
import com.spring.aichat.config.TtsProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;

@Component @RequiredArgsConstructor
public class TtsAudioStorage {
    private final S3Client s3;
    private final TtsProperties props;
    private final S3Properties publicAssets;
    private S3Client privateClient;
    @jakarta.annotation.PostConstruct
    void initializePrivateClient() {
        if (props.getAudioAccessKey() == null || props.getAudioAccessKey().isBlank()) return;
        if (props.getAudioSecretKey() == null || props.getAudioSecretKey().isBlank()) throw new IllegalStateException("TTS storage secret missing");
        var builder = S3Client.builder().region(software.amazon.awssdk.regions.Region.of(publicAssets.region()))
            .credentialsProvider(software.amazon.awssdk.auth.credentials.StaticCredentialsProvider.create(
                software.amazon.awssdk.auth.credentials.AwsBasicCredentials.create(props.getAudioAccessKey(), props.getAudioSecretKey())));
        String endpoint = props.getAudioEndpoint() == null || props.getAudioEndpoint().isBlank() ? publicAssets.endpoint() : props.getAudioEndpoint();
        if (endpoint != null && !endpoint.isBlank()) builder.endpointOverride(java.net.URI.create(endpoint)).forcePathStyle(true);
        privateClient = builder.build();
    }
    private S3Client client() { return privateClient == null ? s3 : privateClient; }
    @jakarta.annotation.PreDestroy void close() { if (privateClient != null) privateClient.close(); }
    public void checkPrivateBucket() {
        if (props.getAudioBucket() == null || props.getAudioBucket().isBlank()
                || props.getAudioBucket().equals(publicAssets.bucketName()))
            throw new IllegalStateException("TTS requires a separate private bucket");
    }
    public void put(String key, byte[] audio) {
        checkPrivateBucket();
        client().putObject(PutObjectRequest.builder().bucket(props.getAudioBucket()).key(key)
            .contentType("audio/mpeg").cacheControl("private, no-store")
            .overrideConfiguration(c -> c.apiCallTimeout(java.time.Duration.ofSeconds(20)))
            .build(), RequestBody.fromBytes(audio));
    }
    public byte[] get(String key) {
        checkPrivateBucket();
        return client().getObjectAsBytes(GetObjectRequest.builder().bucket(props.getAudioBucket()).key(key).build()).asByteArray();
    }
    public boolean exists(String key) {
        checkPrivateBucket();
        try { client().headObject(HeadObjectRequest.builder().bucket(props.getAudioBucket()).key(key).build()); return true; }
        catch (S3Exception e) { if (e.statusCode() == 404) return false; throw e; }
    }
    public void delete(String key) {
        checkPrivateBucket();
        client().deleteObject(DeleteObjectRequest.builder().bucket(props.getAudioBucket()).key(key).build());
    }
}
