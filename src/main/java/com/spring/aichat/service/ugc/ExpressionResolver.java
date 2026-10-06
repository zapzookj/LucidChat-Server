package com.spring.aichat.service.ugc;

import com.spring.aichat.domain.character.Character;
import com.spring.aichat.domain.enums.EmotionTag;

/** Resolve only a trusted character's published asset. LLM URLs are never accepted. */
public final class ExpressionResolver {
    private ExpressionResolver() {}
    public record Resolved(String id, String imageUrl) {}
    public static Resolved resolve(Character character, String requested, EmotionTag emotion) {
        if (character == null || character.getExpressionCatalogJson() == null) return new Resolved(null, null);
        String id = ExpressionCatalog.select(character.getExpressionCatalogJson(), requested, emotion);
        String neutral = character.getDefaultImageUrl();
        if (neutral == null || !neutral.endsWith("/default_neutral.png")) return new Resolved("NEUTRAL", neutral);
        return new Resolved(id, neutral.substring(0, neutral.length() - "default_neutral.png".length())
            + "default_" + id.toLowerCase(java.util.Locale.ROOT) + ".png");
    }
    public static boolean matches(Character character, String speaker) {
        return character != null && speaker != null
            && (speaker.trim().equalsIgnoreCase(character.getName()) || speaker.trim().equalsIgnoreCase(character.getSlug()));
    }
}
