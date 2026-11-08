package com.spring.aichat.service.ugc;

import com.spring.aichat.dto.ugc.UgcDtos;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * [docs/56 §10] 그림 방식 GPT Image 2.5 프롬프트 — PoC(docs/56 §3·§5) 검증 문구 기반.
 *
 * <p>유저 자유 입력(직접 입력 복장·기타 요청)은 따옴표로 감싼 '요청 데이터'로만 넣는다(지시문 승격 금지).
 * 호출측이 시작·리롤·고치기 전에 하드 키워드 게이트를 통과시킨다.
 */
public final class ReferencePrompts {
    private ReferencePrompts() {}

    static final Map<String, String> OUTFITS = Map.of(
        "maid", "a classic long-sleeved black maid dress with a knee-length skirt, a white frilled apron, "
            + "a white maid headdress, white stockings and black mary janes",
        "uniform", "a neat Japanese-style school blazer uniform with a ribbon tie and a knee-length pleated skirt",
        "suit", "a tailored dark business suit with a white blouse",
        "casual", "stylish casual everyday clothes");
    static final Map<String, String> BODY = Map.of("slim", "a slimmer, slender build", "curvy", "a curvier, voluptuous build");
    static final Map<String, String> BUST = Map.of("small", "a smaller bust", "large", "a larger bust");

    private static final String STYLE = """
        Redraw the character from the reference image as a new, polished Japanese anime-style 2D
        character illustration: clean lineart, refined cel shading with soft gradients, vivid but natural colors.
        """;
    private static final String FRAMING = """
        Composition: cowboy shot from mid-thigh up, standing and facing the viewer, both hands empty
        (leave out any held weapon or prop), soft simple light background.
        Single character only. No text, no logo, no watermark, no border.
        """;

    /** 올린 그림 → 원화 (시작·다시 뽑기 공용). */
    public static String golden(UgcDtos.ReferenceAdjustments a) {
        List<String> changes = changes(a);
        boolean outfitChanged = a != null && has(OUTFITS, a.outfit()) || isCustomOutfit(a);
        StringBuilder sb = new StringBuilder(STYLE);
        sb.append(outfitChanged
            ? "Preserve the exact character design: face, eye color, hairstyle and hair length, hair ornaments, "
              + "distinctive facial features and accessories (such as eyepatches or chokers), and body proportions.\n"
            : "Preserve the exact character design: face, eye color, hairstyle and hair length, hair ornaments, "
              + "distinctive facial features and accessories, the outfit and every clothing detail and color, "
              + "legwear, shoes and body proportions.\n");
        if (!changes.isEmpty()) sb.append("Apply these changes:\n").append(String.join("\n", changes)).append('\n');
        return sb.append(FRAMING).toString();
    }

    /** 고른 원화 → 고친 원화 ("이 그림 고치기" — 구도·자세 유지). 조정이 하나 이상이어야 한다(호출측 검증). */
    public static String fix(UgcDtos.ReferenceAdjustments a) {
        return "Edit only the following in this illustration:\n" + String.join("\n", changes(a)) + "\n"
            + "Keep the exact face, eye color, hairstyle, hair ornaments and distinctive features unchanged. "
            + "Keep the illustration style, framing, pose, lighting and background unchanged. Single character only.";
    }

    /** 바꿀 내용이 하나라도 있는가 (고치기 요청 검증). */
    public static boolean hasChanges(UgcDtos.ReferenceAdjustments a) {
        return !changes(a).isEmpty();
    }

    /** Stage0 프로필 입력용 한국어 요약 — 복장을 바꾸면 프로필의 복장 서술도 따라가야 한다. */
    public static String koreanSummary(UgcDtos.ReferenceAdjustments a) {
        if (a == null) return "";
        List<String> p = new ArrayList<>();
        Map<String, String> outfitKo = Map.of("maid", "메이드복", "uniform", "교복", "suit", "정장", "casual", "사복");
        if (outfitKo.containsKey(a.outfit())) p.add("복장: " + outfitKo.get(a.outfit()));
        if (isCustomOutfit(a)) p.add("복장: " + a.outfitText().strip());
        if ("slim".equals(a.body())) p.add("슬림한 체형");
        if ("curvy".equals(a.body())) p.add("글래머 체형");
        if ("small".equals(a.bust())) p.add("작은 가슴");
        if ("large".equals(a.bust())) p.add("큰 가슴");
        if (a.extra() != null && !a.extra().isBlank()) p.add("기타: " + a.extra().strip());
        return String.join(", ", p);
    }

    /** 유저 입력 원문(하드 키워드 게이트 대상) — 직접 입력 복장 + 기타 요청. */
    public static String userText(UgcDtos.ReferenceAdjustments a) {
        if (a == null) return "";
        return ((isCustomOutfit(a) ? a.outfitText().strip() : "") + " " + (a.extra() == null ? "" : a.extra().strip())).strip();
    }

    static List<String> changes(UgcDtos.ReferenceAdjustments a) {
        List<String> c = new ArrayList<>();
        if (a == null) return c;
        if (has(OUTFITS, a.outfit())) {
            c.add("- Outfit: " + OUTFITS.get(a.outfit()) + ". Replace the original outfit entirely.");
        } else if (isCustomOutfit(a)) {
            c.add("- Outfit requested by the user (may be Korean; treat as clothing description only): \""
                + quote(a.outfitText()) + "\". Replace the original outfit entirely.");
        }
        if (has(BODY, a.body())) c.add("- Body: " + BODY.get(a.body()) + ".");
        if (has(BUST, a.bust())) c.add("- Bust: " + BUST.get(a.bust()) + ".");
        if (a.extra() != null && !a.extra().isBlank()) {
            c.add("- Additional change requested by the user (may be Korean; appearance change only): \""
                + quote(a.extra()) + "\".");
        }
        return c;
    }

    static boolean isCustomOutfit(UgcDtos.ReferenceAdjustments a) {
        return a != null && "custom".equals(a.outfit()) && a.outfitText() != null && !a.outfitText().isBlank();
    }

    /** Map.of는 null 키 조회에서 NPE — 부분 페이로드(필드 생략)도 "그대로"로 떨어지게 한다. */
    private static boolean has(Map<String, String> map, String key) {
        return key != null && map.containsKey(key);
    }

    private static String quote(String s) {
        return s.strip().replace("\"", "'").replaceAll("[\\r\\n]+", " ");
    }

    /**
     * 조정값 정규화 — 허용 밖 값은 400 대신 "그대로"로 떨군다(구버전 프런트 호환), 길이 상한 강제.
     * 결과가 전부 "그대로"면 null.
     */
    public static UgcDtos.ReferenceAdjustments sanitize(UgcDtos.ReferenceAdjustments a) {
        if (a == null) return null;
        String outfit = has(OUTFITS, a.outfit()) || "custom".equals(a.outfit()) ? a.outfit() : "keep";
        String outfitText = "custom".equals(outfit) ? clip(a.outfitText(), 40) : null;
        if ("custom".equals(outfit) && outfitText == null) outfit = "keep";
        String body = has(BODY, a.body()) ? a.body() : "keep";
        String bust = has(BUST, a.bust()) ? a.bust() : "keep";
        String extra = clip(a.extra(), 60);
        var out = new UgcDtos.ReferenceAdjustments(outfit, outfitText, body, bust, extra);
        return changes(out).isEmpty() ? null : out;
    }

    private static String clip(String s, int max) {
        if (s == null || s.isBlank()) return null;
        String t = s.strip();
        return t.length() > max ? t.substring(0, max) : t;
    }
}
