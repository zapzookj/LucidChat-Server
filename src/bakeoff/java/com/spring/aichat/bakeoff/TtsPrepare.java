package com.spring.aichat.bakeoff;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.spring.aichat.config.TtsProperties;
import com.spring.aichat.service.tts.ElevenLabsDialogueClient;
import com.spring.aichat.service.tts.TtsText;
import org.yaml.snakeyaml.Yaml;
import java.nio.file.*;
import java.util.*;

/** Explicit offline preparation only; not packaged in the application. No synthesis retries. */
public class TtsPrepare {
    @SuppressWarnings("unchecked")
    public static void main(String[] args) throws Exception {
        if (args.length != 3) throw new IllegalArgumentException("secretFile outputDirectory slug-or-all");
        var mapper = new ObjectMapper();
        var secret = mapper.readTree(Files.readString(Path.of(args[0])).replaceFirst("^\uFEFF", ""));
        var props = new TtsProperties(); props.setApiKey(secret.path("apiKey").asText());
        var client = new ElevenLabsDialogueClient(props, mapper);
        Map<String, Object> yaml = new Yaml().load(Files.readString(Path.of("src/main/resources/application-characters.yml")));
        var characters = (List<Map<String, Object>>) ((Map<String, Object>) yaml.get("app")).get("characters");
        Path directory = Path.of(args[1]); Files.createDirectories(directory);
        for (var c : characters) {
            String slug = (String) c.get("slug");
            if (!args[2].equals("all") && !args[2].equals(slug)) continue;
            String text = (String) c.get("first-greeting");
            String voice = secret.path("voiceIds").path(slug).asText();
            if (voice.isBlank() || text == null) throw new IllegalStateException("Missing official greeting voice");
            String hash = TtsText.hash(text + "|" + voice);
            Path receipt = directory.resolve(slug + ".json");
            if (Files.exists(receipt)) { System.out.println(slug + " receipt exists; skipped"); continue; }
            // A crash or ambiguous provider outcome requires inspection, never a repeated paid request.
            Files.writeString(receipt, mapper.writeValueAsString(Map.of("slug", slug, "state", "REQUESTING", "hash", hash, "model", "eleven_v4")));
            long start = System.nanoTime();
            try {
                byte[] audio = client.synthesize("eleven_v4", voice, text);
                Path path = directory.resolve(slug + ".mp3"); Files.write(path, audio);
                Files.writeString(receipt, mapper.writeValueAsString(Map.of("slug", slug, "state", "DONE", "hash", hash, "model", "eleven_v4",
                    "bytes", audio.length, "seconds", (System.nanoTime()-start)/1e9, "objectKey", "greetings/" + slug + "/" + hash + ".mp3")));
                System.out.println(slug + " prepared | bytes=" + audio.length + " | seconds=" + (System.nanoTime()-start)/1e9);
            } catch (Exception e) {
                Files.writeString(receipt, mapper.writeValueAsString(Map.of("slug", slug, "state", "FAILED_OR_UNKNOWN", "hash", hash, "model", "eleven_v4", "errorType", e.getClass().getSimpleName())));
                System.out.println(slug + " failed | type=" + e.getClass().getSimpleName());
                // Do not leak the WebSocket handshake, key, voice ID or payload via exception messages.
                throw new IllegalStateException("TTS preparation stopped; inspect sanitized receipt");
            }
        }
    }
}
