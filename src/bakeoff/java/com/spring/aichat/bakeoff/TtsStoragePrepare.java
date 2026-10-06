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
import java.util.*;
import java.security.MessageDigest;

/** Explicit bucket preparation, isolated from app startup. Outputs no credentials. */
public class TtsStoragePrepare {
    private static final Set<String> SLUGS = Set.of("airi", "yeonhwa", "taeri", "luna", "claire", "rosetta", "chaerin", "sierra", "edel", "seolah");
    private record PreparedAudio(String slug, String key, byte[] bytes) {}
    private static List<PreparedAudio> readSamples(Path samples, ObjectMapper mapper) throws Exception {
        List<PreparedAudio> result = new ArrayList<>(); Set<String> seen = new HashSet<>();
        try (var paths = Files.list(samples)) {
            for (Path path : paths.filter(p -> p.toString().endsWith(".json")).sorted().toList()) {
                var receipt = mapper.readTree(Files.readString(path));
                String slug = receipt.path("slug").asText();
                String hash = receipt.path("hash").asText();
                String key = receipt.path("objectKey").asText();
                if (!SLUGS.contains(slug) || !seen.add(slug) || !path.getFileName().toString().equals(slug + ".json")
                        || !receipt.path("state").asText().equals("DONE") || !hash.matches("[0-9a-f]{64}")
                        || !key.equals("greetings/" + slug + "/" + hash + ".mp3"))
                    throw new IllegalStateException("Invalid greeting receipt");
                byte[] bytes = Files.readAllBytes(samples.resolve(slug + ".mp3"));
                String audioHash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
                boolean legacy = samples.toAbsolutePath().normalize().equals(Path.of("tools/tts/.local/greetings-v4").toAbsolutePath().normalize())
                    && !receipt.has("source");
                boolean selected = !legacy;
                String expectedAudioHash = receipt.path("audioSha256").asText("");
                if (selected && !expectedAudioHash.matches("[0-9a-f]{64}"))
                    throw new IllegalStateException("Selected audio requires a SHA-256 receipt");
                if (bytes.length < 1000 || receipt.path("bytes").asInt() != bytes.length
                        || (receipt.has("audioSha256") && !expectedAudioHash.equals(audioHash)))
                    throw new IllegalStateException("Greeting audio does not match receipt");
                result.add(new PreparedAudio(slug, key, bytes));
            }
        }
        if (!seen.equals(SLUGS)) throw new IllegalStateException("Expected exactly 10 distinct official greetings");
        return result;
    }
    public static void main(String[] args) throws Exception {
        var mapper = new ObjectMapper();
        Path samples = args.length > 0 ? Path.of(args[0]) : Files.isDirectory(Path.of("tools/tts/.local/greetings-selected"))
            ? Path.of("tools/tts/.local/greetings-selected") : Path.of("tools/tts/.local/greetings-v4");
        // Reconcile the entire batch before the first network mutation, not after partial writes.
        var prepared = readSamples(samples, mapper);
        if (args.length > 1 && args[1].equals("--check-only")) { System.out.println("10 receipts and exact local audio hashes verified; no network operations."); return; }
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
                for (var sample : prepared) {
                    String key = sample.key();
                    byte[] bytes = sample.bytes();
                    s3.putObject(PutObjectRequest.builder().bucket(bucket).key(key).contentType("audio/mpeg").cacheControl("private, no-store").build(), RequestBody.fromBytes(bytes));
                    byte[] restored = s3.getObjectAsBytes(GetObjectRequest.builder().bucket(bucket).key(key).build()).asByteArray();
                    if (!java.util.Arrays.equals(bytes, restored)) throw new IllegalStateException("Audio storage mismatch");
                    var response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(config.path("endpoint").asText() + "/" + bucket + "/" + key)).timeout(Duration.ofSeconds(15)).GET().build(), HttpResponse.BodyHandlers.discarding());
                    if (response.statusCode() == 200) throw new IllegalStateException("Unsigned audio was accessible");
                    System.out.println(sample.slug() + " stored | exact bytes verified | unsigned HTTP=" + response.statusCode());
                }
            System.out.println("10 greetings stored and verified; no public URL configured by this operation.");
        } catch (Exception e) { System.out.println("Storage preparation failed | type=" + e.getClass().getSimpleName()); System.exit(2); }
    }
}
