package com.spring.aichat.bakeoff;

import com.fasterxml.jackson.databind.ObjectMapper;
import software.amazon.awssdk.auth.credentials.*;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;
import software.amazon.awssdk.core.sync.RequestBody;
import java.nio.file.*;
import java.net.*;
import java.net.http.*;
import java.time.Duration;

/** Explicit bucket preparation, isolated from app startup. Outputs no credentials. */
public class TtsStoragePrepare {
    public static void main(String[] args) throws Exception {
        var mapper = new ObjectMapper();
        var config = mapper.readTree(Files.readString(Path.of("tools/tts/.local/storage.json")));
        String bucket = config.path("privateBucket").asText();
        if (bucket.equals(config.path("publicBucket").asText()) || bucket.isBlank()) throw new IllegalStateException("Private bucket required");
        try (var s3 = S3Client.builder().endpointOverride(URI.create(config.path("endpoint").asText())).region(Region.of("auto"))
            .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(config.path("accessKey").asText(), config.path("secretKey").asText())))
            .forcePathStyle(true).overrideConfiguration(c -> c.apiCallTimeout(Duration.ofSeconds(30))).build()) {
            try { s3.headBucket(HeadBucketRequest.builder().bucket(bucket).build()); System.out.println("Private bucket already exists"); }
            catch (S3Exception e) {
                if (e.statusCode() != 404 && e.statusCode() != 403) { System.out.println("Bucket access rejected | HTTP=" + e.statusCode()); System.exit(2); }
                try { s3.createBucket(CreateBucketRequest.builder().bucket(bucket).build()); System.out.println("Created separate bucket: " + bucket); }
                catch (S3Exception rejected) { System.out.println("Bucket creation rejected | HTTP=" + rejected.statusCode()); System.exit(2); }
            }
            Path samples = Path.of("tools/tts/.local/greetings-v4"); int total = 0;
            try (var receipts = Files.list(samples)) {
                for (Path receipt : receipts.filter(p -> p.toString().endsWith(".json")).toList()) {
                    var json = mapper.readTree(Files.readString(receipt));
                    if (!json.path("state").asText().equals("DONE")) throw new IllegalStateException("Incomplete greeting");
                    String key = json.path("objectKey").asText();
                    byte[] bytes = Files.readAllBytes(samples.resolve(json.path("slug").asText() + ".mp3"));
                    s3.putObject(PutObjectRequest.builder().bucket(bucket).key(key).contentType("audio/mpeg").cacheControl("private, no-store").build(), RequestBody.fromBytes(bytes));
                    byte[] restored = s3.getObjectAsBytes(GetObjectRequest.builder().bucket(bucket).key(key).build()).asByteArray();
                    if (!java.util.Arrays.equals(bytes, restored)) throw new IllegalStateException("Audio storage mismatch");
                    var response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(config.path("endpoint").asText() + "/" + bucket + "/" + key)).timeout(Duration.ofSeconds(15)).GET().build(), HttpResponse.BodyHandlers.discarding());
                    if (response.statusCode() == 200) throw new IllegalStateException("Unsigned audio was accessible");
                    System.out.println(json.path("slug").asText() + " stored | exact bytes verified | unsigned HTTP=" + response.statusCode()); total++;
                }
            }
            if (total != 10) throw new IllegalStateException("Expected exactly 10 greetings");
            System.out.println("10 greetings stored and verified; no public URL configured by this operation.");
        } catch (Exception e) { System.out.println("Storage preparation failed | type=" + e.getClass().getSimpleName()); System.exit(2); }
    }
}
