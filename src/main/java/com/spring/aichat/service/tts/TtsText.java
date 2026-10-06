package com.spring.aichat.service.tts;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Set;
import java.util.regex.Pattern;

public final class TtsText {
    private TtsText() {}
    public static final Set<String> TAGS = Set.of("whispers", "laughs", "sighs", "sad", "excited", "angry", "curious", "nervous", "calm", "softly");
    private static final Pattern TAG = Pattern.compile("\\[([^\\[\\]]+)\\]");
    public static String validate(String dialogue, String input) {
        if (dialogue == null || dialogue.isBlank() || input == null || input.isBlank()) return null;
        String spoken = literalDialogue(dialogue);
        if (normalize(spoken).equals(normalize(input))) return input.trim();
        var matcher = TAG.matcher(input); int tags = 0;
        while (matcher.find()) { if (!TAGS.contains(matcher.group(1)) || ++tags > 2) return null; }
        String body = TAG.matcher(input).replaceAll("");
        return normalize(body).equals(normalize(spoken)) ? input.trim() : null;
    }
    public static String literalDialogue(String dialogue) { return dialogue.replace('[', '［').replace(']', '］'); }
    private static String normalize(String text) { return text.strip().replaceAll("\\s+", " "); }
    public static String hash(String text) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }
    public static String prompt() {
        return """
            # Voice performance output (enabled for this response)
            Each scene with spoken dialogue MUST also contain a string `tts_input`.
            Copy dialogue verbatim, adding at most TWO subtle English performance tags from this list:
            [whispers], [laughs], [sighs], [sad], [excited], [angry], [curious], [nervous], [calm], [softly].
            Do not add narration, inner thoughts, speaker names, translations, new words, or sound effects.
            Keep performance natural and consistent with the character. Plain verbatim dialogue is valid when no tag helps.
            A scene without spoken dialogue has tts_input: null. Displayed dialogue never contains audio tags.
            """;
    }
}
