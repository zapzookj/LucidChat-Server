package com.spring.aichat.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

@Component
@Getter @Setter
@ConfigurationProperties(prefix = "tts")
public class TtsProperties {
    private boolean enabled;
    private String apiKey = "";
    private String model = "eleven_v4";
    private String formatterModel = "google/gemini-3-flash-preview";
    private int energyCost = 1;
    private String audioBucket = "";
    private String secretFile = "";
    private String storageSecretFile = "";
    private String audioAccessKey = "";
    private String audioSecretKey = "";
    private String audioEndpoint = "";
    private Map<String, String> voices = new HashMap<>();

    @PostConstruct
    void loadLocalSecret() throws Exception {
        if (secretFile != null && !secretFile.isBlank()) {
            String content = Files.readString(Path.of(secretFile)).replaceFirst("^\uFEFF", "");
            var json = new ObjectMapper().readTree(content);
            apiKey = json.path("apiKey").asText("");
            json.path("voiceIds").fields().forEachRemaining(e -> voices.put(e.getKey(), e.getValue().asText("")));
        }
        if (energyCost < 0) throw new IllegalArgumentException("TTS energy cost must be nonnegative");
        if (storageSecretFile != null && !storageSecretFile.isBlank()) {
            var json = new ObjectMapper().readTree(Files.readString(Path.of(storageSecretFile)).replaceFirst("^\uFEFF", ""));
            audioAccessKey = json.path("accessKey").asText("");
            audioSecretKey = json.path("secretKey").asText("");
            audioEndpoint = json.path("endpoint").asText("");
            audioBucket = json.path("privateBucket").asText(audioBucket);
        }
    }

    public boolean configured() {
        return enabled && apiKey != null && !apiKey.isBlank() && audioBucket != null && !audioBucket.isBlank();
    }
    public String voice(String slug) {
        String value = voices.get(slug);
        return value == null || value.isBlank() ? null : value;
    }
}
