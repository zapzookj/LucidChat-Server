package com.spring.aichat.service.ugc;

import com.spring.aichat.dto.ugc.StructuredConcept;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** Neutral restoration receives exactly quality + eye color + representative expression. */
public final class NeutralFacePrompt {
    private NeutralFacePrompt() {}
    private static final Pattern UNRELATED = Pattern.compile("\\b(makeup|eyeliner|eyeshadow|lashes|eyelashes|hair|lips|lipstick|breasts|outfit|lighting|background|pose|hands?)\\b");
    private static final Pattern DIRECTIVE = Pattern.compile("\\b(break|and|or|with|without|remove|add|change|clothes|then)\\b");
    private static final String COLORS = "amber|gold|golden|honey|hazel|brown|blue|green|red|crimson|scarlet|ruby|pink|rose|purple|violet|lavender|gray|grey|silver|black|white|orange|yellow|teal|turquoise|cyan|aqua|emerald|sapphire|copper|maroon|indigo";
    private static final Pattern EYE_TAG = Pattern.compile("(?:(?:sharp|soft|bright|clear|deep|dark|light|pale|warm|cool|vivid|glowing|piercing|gentle|narrow|large|round|almond|slanted|intense|beautiful|detailed|sparkling|cat|upturned|downturned|dull|" + COLORS + ") )*(?:" + COLORS + ") (?:eyes|iris|irises|pupils)");
    private static final String EXPRESSIONS = "smug|smirk|smirking|smile|smiling|grin|grinning|frown|frowning|pout|pouting|serious|stoic|expressionless|neutral|angry|sad|shy|bored|confident|stern|cheerful|playful|gentle|calm|worried|surprised|sleepy|relaxed|arrogant|deadpan|aloof|amused|annoyed|embarrassed|nervous|seductive";
    private static final Pattern EXPRESSION_TAG = Pattern.compile("(?:(?:warm|soft|slight|subtle|faint|wide|bright|kind|cold|knowing|teasing|wry|crooked|toothy|open|closed|mouth|small|evil|cocky|forced|apologetic|condescending|mischievous|bashful|timid|coy|sultry|determined|melancholic|sulking|adorable|" + EXPRESSIONS + ") )*(?:" + EXPRESSIONS + "|expression|gaze)");

    public static String assemble(StructuredConcept concept) {
        var hint = concept.faceDetailTags();
        String eye = hint == null ? null : eye(hint.eyeColor());
        String expression = hint == null ? null : expression(hint.expression());
        List<String> appearance = flatten(concept.appearanceTags());
        List<String> persona = flatten(concept.personaTags());
        // Original positives support old jobs whose extracted arrays omit a facial trait.
        List<String> original = concept.illustrationPrompt() == null ? List.of()
            : flatten(List.of(concept.illustrationPrompt().positivePrompt()));
        if (eye == null) eye = firstEye(appearance);
        if (eye == null) eye = firstEye(original);
        if (expression == null) expression = firstExpression(persona);
        if (expression == null) expression = firstExpression(original);
        return "detailed beautiful eyes, " + (eye == null ? "same eye color" : eye)
            + ", " + (expression == null ? "neutral expression" : expression);
    }

    private static List<String> flatten(List<String> values) {
        if (values == null) return List.of();
        List<String> tags = new ArrayList<>();
        for (String value : values) if (value != null)
            for (String tag : value.split(",")) tags.add(tag);
        return tags;
    }
    private static String single(String value) {
        if (value == null) return null;
        String tag = value.strip().toLowerCase(Locale.ROOT).replace('_', ' ').replace('-', ' ').replaceAll(" +", " ");
        // Keep a single short tag; disallow wildcard directives, weights, alternatives and lists.
        if (!tag.matches("[a-z]+(?:[ -][a-z]+){0,5}") || tag.length() > 64
            || UNRELATED.matcher(tag).find() || DIRECTIVE.matcher(tag).find()) return null;
        return tag;
    }
    private static String eye(String value) {
        String tag = single(value);
        return tag != null && (tag.equals("heterochromia") || EYE_TAG.matcher(tag).matches()) ? tag : null;
    }
    private static String expression(String value) {
        String tag = single(value);
        if (tag == null || !EXPRESSION_TAG.matcher(tag).matches()) return null;
        return tag.contains("smug") ? "smug" : tag;
    }
    private static String firstEye(List<String> tags) {
        for (String tag : tags) { String selected = eye(tag); if (selected != null) return selected; }
        return null;
    }
    private static String firstExpression(List<String> tags) {
        for (String tag : tags) { String selected = expression(tag); if (selected != null) return selected; }
        return null;
    }
}
