package com.spring.aichat.service.tts;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.spring.aichat.config.TtsProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

/** One voice per connection works with both v4 and v4 Turbo. Never retries synthesis. */
@Component @RequiredArgsConstructor
public class ElevenLabsDialogueClient {
    private final TtsProperties props;
    private final ObjectMapper mapper;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
    public byte[] synthesize(String model, String voiceId, String input) throws Exception {
        if (!model.equals("eleven_v4") && !model.equals("eleven_v4_turbo")) throw new IllegalArgumentException("Unsupported TTS model");
        var listener = new AudioListener(mapper);
        var socket = http.newWebSocketBuilder().header("xi-api-key", props.getApiKey())
            .connectTimeout(Duration.ofSeconds(15)).buildAsync(URI.create("wss://api.elevenlabs.io/v1/text-to-dialogue/stream-input?model_id="
                + model + "&output_format=mp3_44100_128"), listener).get(20, TimeUnit.SECONDS);
        try {
            socket.sendText(mapper.writeValueAsString(Map.of("voices", List.of(voiceId))), true).get(10, TimeUnit.SECONDS);
            socket.sendText(mapper.writeValueAsString(Map.of("inputs", List.of(Map.of("text", input, "voice_id", voiceId, "new_turn", false)))), true).get(10, TimeUnit.SECONDS);
            socket.sendText("{\"close_socket\":true}", true).get(10, TimeUnit.SECONDS);
            byte[] bytes = listener.done.get(90, TimeUnit.SECONDS);
            if (bytes.length == 0) throw new IllegalStateException("Empty TTS output");
            return bytes;
        } finally { socket.abort(); }
    }
    static final class AudioListener implements WebSocket.Listener {
        final ObjectMapper mapper;
        final CompletableFuture<byte[]> done = new CompletableFuture<>();
        final ByteArrayOutputStream audio = new ByteArrayOutputStream();
        final StringBuilder frame = new StringBuilder();
        AudioListener(ObjectMapper mapper) { this.mapper = mapper; }
        @Override public void onOpen(WebSocket socket) { socket.request(1); }
        @Override public CompletionStage<?> onText(WebSocket socket, CharSequence data, boolean last) {
            try {
                frame.append(data);
                if (frame.length() > 16_000_000) throw new IllegalStateException("TTS frame too large");
                if (last) {
                    var json = mapper.readTree(frame.toString()); frame.setLength(0);
                    if (json.hasNonNull("error")) throw new IllegalStateException("TTS provider rejected input");
                    if (json.path("audio").isTextual()) {
                        audio.write(Base64.getDecoder().decode(json.path("audio").asText()));
                        if (audio.size() > 12_000_000) throw new IllegalStateException("TTS output too large");
                    }
                    if (json.path("is_final").asBoolean(false)) done.complete(audio.toByteArray());
                }
            } catch (Exception e) { done.completeExceptionally(e); }
            socket.request(1); return CompletableFuture.completedFuture(null);
        }
        @Override public CompletionStage<?> onClose(WebSocket socket, int status, String reason) {
            if (!done.isDone()) done.completeExceptionally(new IllegalStateException("TTS stream closed before final"));
            return CompletableFuture.completedFuture(null);
        }
        @Override public void onError(WebSocket socket, Throwable error) { done.completeExceptionally(new IllegalStateException("TTS connection failed")); }
    }
}
