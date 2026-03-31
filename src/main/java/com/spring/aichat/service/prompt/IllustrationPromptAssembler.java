package com.spring.aichat.service.prompt;

import com.spring.aichat.domain.character.Character;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * [Phase 5.5-Illust] 캐릭터 일러스트 프롬프트 조립기
 *
 * 프롬프트 구조:
 *   (1) 고정: LoRA 트리거 키워드 + masterpiece, best quality, highres, 1girl, solo
 *   (2) 캐릭터별 아이덴티티: 눈 색, 머리 색, 길이 등
 *   (3) 장소: location enum → 배경 프롬프트
 *   (4) 복장: outfit enum → 의상 프롬프트
 *   (5) 감정: emotion tag → 표정/포즈 프롬프트
 *
 * ※ 각 매핑 값은 더미 데이터로 채워두고, 세밀한 튜닝은 수동으로 진행.
 */
@Component
@Slf4j
public class IllustrationPromptAssembler {

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  고정 프롬프트
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    private static final String FIXED_QUALITY_PREFIX =
        "masterpiece, best quality, highres, 1girl, solo, detailed beautiful eyes";

    private static final String FIXED_NEGATIVE_PROMPT =
        "lowres, bad anatomy, bad hands, text, error, missing fingers, worst quality, ugly, " +
            "deformed, extra limbs, duplicate, morbid, mutilated, poorly drawn face, " +
            "mutation, extra fingers, blurry, watermark, signature";

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  캐릭터별 LoRA 트리거 + 아이덴티티 매핑
    //  ※ 더미 데이터 — 실제 값은 yml 또는 DB에서 관리
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /** slug → { loraTrigger, identityPrompt, loraUrl } */
    private static final Map<String, CharacterVisual> CHARACTER_VISUALS = new LinkedHashMap<>();
    static {
        CHARACTER_VISUALS.put("airi", new CharacterVisual(
            "lcn_char02",
            "multicolored eyes, blue eyes, yellow eyes, gradient eyes, pink hair, short hair, medium hair, bangs",
            "lucid_airi_v1.safetensors"
        ));
        CHARACTER_VISUALS.put("taeri", new CharacterVisual(
            "lcn_char03",
            "purple eyes, grey hair, medium hair",
            "lucid_taeri_v1.safetensors"
        ));
        CHARACTER_VISUALS.put("luna", new CharacterVisual(
            "lcn_char01",
            "blue eyes, black hair, blonde inner hair, multicolored hair, two-tone hair, bangs, short hair, colored inner hair",
            "lucid_luna_v2.safetensors"
        ));
        CHARACTER_VISUALS.put("yeonhwa", new CharacterVisual(
            "lcn_char04",
            "red eyes, animal ear fluff, fox girl, white hair, long hair,  animal ears, fox ears, fox tail, breasts, multiple tails, facial mark,bangs,",
            "lucid_yeonhwa_v1.safetensors"
        ));
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  복장 프롬프트 매핑
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    private static final Map<String, String> AIRI_OUTFIT_PROMPTS = new LinkedHashMap<>();
    static {
        AIRI_OUTFIT_PROMPTS.put("MAID", "maid headdress, bunny hair ornament, classic maid outfit, black dress, white frilled apron, black ribbon bowtie, short puffy sleeves, black thighhighs, mary janes");
        AIRI_OUTFIT_PROMPTS.put("DATE", "bag, shirt, hairband, blue cardigan, jewelry, necklace, white shirt, pleated skirt, long sleeves, black skirt, black hairband, handbag");
        AIRI_OUTFIT_PROMPTS.put("SWIMSUIT", "swimsuit, bikini, navel, cleavage,  sarong, blue bikini, collarbone, frills, ribbon, frilled bikini, hair ribbon,  open clothes, thighs, long sleeves,  cardigan, stomach, front-tie top, blue ribbon");
    }

    private static final Map<String, String> TAERI_OUTFIT_PROMPTS = new LinkedHashMap<>();
    static {
        TAERI_OUTFIT_PROMPTS.put("DAILY", "crop top, shirt, purple headphones around neck, navel, shorts, oversized black leather jacket, midriff, white shirt, choker, black shorts, stomach, headphones, black choker, open jacket, short shorts, open clothes,  black jacket, belt, long sleeves, crop top overhang, thighs, leather");
        TAERI_OUTFIT_PROMPTS.put("DATE", "hair tied up, skirt, jewelry, bag, necklace, shirt, off-shoulder white blouse, white shirt, shirt tucked in, collarbone, off shoulder, bare shoulders, blue skirt,  short sleeves, shoulder bag, handbag,high-waist skirt, belt, miniskirt");
        TAERI_OUTFIT_PROMPTS.put("SWIMSUIT", "crop top overhang, crop top, choker, black choker, shirt, navel, sunglasses, swimsuit, stomach, eyewear on head, black shirt, bikini, short sleeves, thighs, black bikini, side-tie bikini bottom, string bikini");
    }

    private static final Map<String, String> LUNA_OUTFIT_PROMPTS = new LinkedHashMap<>();
    static {
        LUNA_OUTFIT_PROMPTS.put("DAILY", "animal ear headphones, black cat ear headphones with glowing blue accents, cat ear headphones, headphones, hood, hoodie, hood down, long sleeves,  oversized white hoodie, sleeves past wrists, drawstring, bare legs, bottomless");
        LUNA_OUTFIT_PROMPTS.put("DATE", "oversized fluffy cardigan, off shoulder, collarbone, ribbon, white plain blouse, pleated skirt, mini skirt, plaid skirt");
//        LUNA_OUTFIT_PROMPTS.put("SWIMSUIT", "elegant date outfit, off-shoulder sweater, pleated skirt, ankle boots");
    }

    private static final Map<String, String> YEONHWA_OUTFIT_PROMPTS = new LinkedHashMap<>();
    static {
        YEONHWA_OUTFIT_PROMPTS.put("HANBOK", "maid headdress, bunny hair ornament, classic maid outfit, black dress, white frilled apron, black ribbon bowtie, short puffy sleeves, black thighhighs, mary janes");
        AIRI_OUTFIT_PROMPTS.put("CASUAL", "casual outfit, white blouse, denim skirt, sneakers");
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  장소 프롬프트 매핑
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    private static final Map<String, String> LOCATION_PROMPTS = new LinkedHashMap<>();
    static {
        LOCATION_PROMPTS.put("ENTRANCE", "mansion entrance hall, grand staircase, warm lighting");
        LOCATION_PROMPTS.put("LIVING_ROOM", "cozy living room, fireplace, warm ambient light");
        LOCATION_PROMPTS.put("KITCHEN", "modern kitchen, warm sunlight through windows");
        LOCATION_PROMPTS.put("GARDEN", "beautiful garden, flowers, soft sunlight, trees");
        LOCATION_PROMPTS.put("BEDROOM", "elegant bedroom, soft bed, warm mood lighting");
        LOCATION_PROMPTS.put("CAMPUS", "university campus, cherry blossom trees, blue sky");
        LOCATION_PROMPTS.put("ART_ROOM", "art studio, easels, canvases, warm afternoon sunlight");
        LOCATION_PROMPTS.put("CONVENIENCE_STORE", "convenience store interior, fluorescent lights, late night");
        LOCATION_PROMPTS.put("FOREST", "mystical forest, sunbeams through canopy, ethereal atmosphere");
        LOCATION_PROMPTS.put("SHRINE", "traditional shrine, stone path, lanterns, serene");
        LOCATION_PROMPTS.put("ROOFTOP", "school rooftop, sunset, city skyline, wind");
        LOCATION_PROMPTS.put("CAFE", "cozy cafe interior, warm lights, coffee cups");
        LOCATION_PROMPTS.put("BEACH", "sandy beach, ocean waves, clear sky");
        LOCATION_PROMPTS.put("LIBRARY", "grand library, bookshelves, reading nook, warm lamplight");
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  감정 프롬프트 매핑
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    private static final Map<String, String> AIRI_EMOTION_PROMPTS = new LinkedHashMap<>();
    static {
        AIRI_EMOTION_PROMPTS.put("NEUTRAL", "neutral, gentle smile, warm vibe");
        AIRI_EMOTION_PROMPTS.put("JOY", "joy, bright smile, closed eyes smile, happy");
        AIRI_EMOTION_PROMPTS.put("SHY", "shy, deep blush, averting gaze, looking down and to the side");
        AIRI_EMOTION_PROMPTS.put("SAD", "sad, worried expression, looking down, eyebrows furrowed");
        AIRI_EMOTION_PROMPTS.put("ANGRY", "angry, slight frown, pouting, scolding playfully");
        AIRI_EMOTION_PROMPTS.put("SURPRISE", "surprise, wide eyes, slightly open mouth, :o, blinking");
        AIRI_EMOTION_PROMPTS.put("SULKING", "sulking, pouty mouth, looking away");
        AIRI_EMOTION_PROMPTS.put("FLIRTATIOUS", "flirtatious, sweet smile, head tilt, looking up through eyelashes, blush, leaning forward slightly");
        AIRI_EMOTION_PROMPTS.put("HEATED", "heated, heavy blush, half-closed eyes, looking down slightly, flustered, shy smile");
        AIRI_EMOTION_PROMPTS.put("DISGUST", "disgust, troubled expression, looking away slightly, sweatdrop");
        AIRI_EMOTION_PROMPTS.put("FRIGHTENED", "frightened, wide eyes, shrinking back, nervous, slight tears");
        AIRI_EMOTION_PROMPTS.put("RELAX", "relaxed, peaceful, eyes closed, serene smile, content");
        AIRI_EMOTION_PROMPTS.put("PANIC", "panic, wide eyes, closed mouth, multiple sweatdrops, waving hands frantically, flustered");
        AIRI_EMOTION_PROMPTS.put("DUMBFOUNDED", "dumbfounded, blank stare, blinking, tilted head, sweatdrop");
        AIRI_EMOTION_PROMPTS.put("PLEADING", "pleading, puppy-dog eyes, looking up, looking at viewer, slight blush");
    }

    private static final Map<String, String> TAERI_EMOTION_PROMPTS = new LinkedHashMap<>();
    static {
        TAERI_EMOTION_PROMPTS.put("NEUTRAL", "neutral expression, cool vibe, slightly tilted head");
        TAERI_EMOTION_PROMPTS.put("JOY", "joy, genuine smile, closed eyes smile");
        TAERI_EMOTION_PROMPTS.put("SHY", "shy, looking away, heavy blush, tsundere, averting gaze");
        TAERI_EMOTION_PROMPTS.put("SAD", "sad, tearful, gloomy");
        TAERI_EMOTION_PROMPTS.put("ANGRY", "frown, closed mouth, v-shaped eyebrows");
        TAERI_EMOTION_PROMPTS.put("SURPRISE", "surprise, slight open mouth");
        TAERI_EMOTION_PROMPTS.put("SULKING", "sulking, puffed cheeks, looking away, tsundere pout");
        TAERI_EMOTION_PROMPTS.put("FLIRTATIOUS", "flirtatious, smile, closed mouth, half-closed eyes, blush");
        TAERI_EMOTION_PROMPTS.put("HEATED", "heated, heavy blush, looking away, half-closed eyes, flustered");
        TAERI_EMOTION_PROMPTS.put("DISGUST", "disgust, looking down, contemptuous look");
        TAERI_EMOTION_PROMPTS.put("FRIGHTENED", "nervous, sweatdrop, trying to act tough, tsundere, shrinking back, defensive posture");
        TAERI_EMOTION_PROMPTS.put("RELAX", "relax, soft smile, leaning back slightly");
        TAERI_EMOTION_PROMPTS.put("PANIC", "wide eyes, closed mouth, sweatdrop, waving hands frantically, flustered");
        TAERI_EMOTION_PROMPTS.put("DUMBFOUNDED", "dumbfounded, flat expression, half-closed eyes, sweatdrop, sighing");
        TAERI_EMOTION_PROMPTS.put("PLEADING", "pleading, looking up, heavy blush, hesitant");
    }

    private static final Map<String, String> LUNA_EMOTION_PROMPTS = new LinkedHashMap<>();
    static {
        LUNA_EMOTION_PROMPTS.put("NEUTRAL", "neutral, timid, slight blush");
        LUNA_EMOTION_PROMPTS.put("JOY", " joy, bright smile, closed eyes smile, open mouth");
        LUNA_EMOTION_PROMPTS.put("SHY", "shy, averting gaze, heavy blush, fidgeting, looking sideways");
        LUNA_EMOTION_PROMPTS.put("SAD", "sad, crying, looking down, wiping tears, sniffling");
        LUNA_EMOTION_PROMPTS.put("ANGRY", "pout, blush");
        LUNA_EMOTION_PROMPTS.put("SURPRISE", "surprise, wide eyes, mouth open in an O shape");
        LUNA_EMOTION_PROMPTS.put("SULKING", "pout, blush, looking sideways, half-closed eyes");
        LUNA_EMOTION_PROMPTS.put("FLIRTATIOUS", "flirtatious, shy smile, looking up through eyelashes, heavy blush");
        LUNA_EMOTION_PROMPTS.put("HEATED", "heated, heavy blush, panting, half-closed eyes");
        LUNA_EMOTION_PROMPTS.put("DISGUST", "disgust, troubled expression, looking away slightly, sweatdrop");
        LUNA_EMOTION_PROMPTS.put("FRIGHTENED", "frightened, wide eyes, trembling, tears");
        LUNA_EMOTION_PROMPTS.put("RELAX", "relax, soft smile");
        LUNA_EMOTION_PROMPTS.put("PANIC", "panic, wide eyes, chaotic expression, multiple sweatdrops");
        LUNA_EMOTION_PROMPTS.put("DUMBFOUNDED", "dumbfounded, blank stare, half-closed eyes, sweatdrop, slack-jawed");
        LUNA_EMOTION_PROMPTS.put("PLEADING", "pleading, puppy-dog eyes, looking up, hands clasped together begging, leaning forward");
    }

    private static final Map<String, String> YEONHWA_EMOTION_PROMPTS = new LinkedHashMap<>();
    static {
        YEONHWA_EMOTION_PROMPTS.put("NEUTRAL", "neutral expression, gentle smile, looking at viewer, relaxed posture");
        YEONHWA_EMOTION_PROMPTS.put("JOY", "happy, bright smile, sparkling eyes, joyful, cheerful");
        YEONHWA_EMOTION_PROMPTS.put("SHY", "blushing, shy smile, looking away, fidgeting, embarrassed");
        YEONHWA_EMOTION_PROMPTS.put("SAD", "sad expression, teary eyes, looking down, melancholy");
        YEONHWA_EMOTION_PROMPTS.put("ANGRY", "angry expression, furrowed brows, clenched fists, intense gaze");
        YEONHWA_EMOTION_PROMPTS.put("SURPRISE", "surprised, wide eyes, open mouth, hands up");
        YEONHWA_EMOTION_PROMPTS.put("LOVE", "loving gaze, warm smile, heart eyes, blushing cheeks, dreamy");
        YEONHWA_EMOTION_PROMPTS.put("FLIRTATIOUS", "flirty expression, wink, playful smile, leaning forward, alluring");
        YEONHWA_EMOTION_PROMPTS.put("HEATED", "intense gaze, heavy breathing, flushed cheeks, passionate");
        YEONHWA_EMOTION_PROMPTS.put("DISGUST", "disgusted face, grimace, stepping back");
        YEONHWA_EMOTION_PROMPTS.put("FRIGHTENED", "scared, trembling, wide eyes, clutching self");
        YEONHWA_EMOTION_PROMPTS.put("RELAX", "relaxed, peaceful, eyes closed, serene smile, content");
        YEONHWA_EMOTION_PROMPTS.put("PANIC", "panicking, frantic, sweatdrop, waving hands");
        YEONHWA_EMOTION_PROMPTS.put("DUMBFOUNDED", "dumbfounded, blank stare, open mouth, confused");
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  공개 API
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /**
     * 캐릭터 일러스트 포지티브 프롬프트 조립
     */
    public String assemblePositivePrompt(String characterSlug, String emotion, String location, String outfit) {
        CharacterVisual visual = CHARACTER_VISUALS.getOrDefault(
            characterSlug, CHARACTER_VISUALS.get("airi"));

        StringBuilder sb = new StringBuilder();

        // (1) LoRA 트리거 + 고정 품질
        sb.append(visual.loraTrigger).append(", ").append(FIXED_QUALITY_PREFIX).append(", ");

        // (2) 캐릭터 아이덴티티
        sb.append(visual.identityPrompt).append(", ");

        // (3) 복장
        switch (characterSlug) {
            case "taeri" -> sb.append(TAERI_OUTFIT_PROMPTS.getOrDefault(
                normalize(outfit), TAERI_OUTFIT_PROMPTS.get("DAILY"))).append(", ");
            case "luna" -> sb.append(LUNA_OUTFIT_PROMPTS.getOrDefault(
                normalize(outfit), LUNA_OUTFIT_PROMPTS.get("DAILY"))).append(", ");
            case "yeonhwa" -> sb.append(YEONHWA_OUTFIT_PROMPTS.getOrDefault(
                normalize(outfit), YEONHWA_OUTFIT_PROMPTS.get("HANBOK"))).append(", ");
            default -> sb.append(AIRI_OUTFIT_PROMPTS.getOrDefault(
                normalize(outfit), AIRI_OUTFIT_PROMPTS.get("MAID"))).append(", ");
        }

        // (4) 장소 배경
        String locationPrompt = LOCATION_PROMPTS.getOrDefault(
            normalize(location), "simple background");
        sb.append(locationPrompt).append(", ");

        // (5) 감정/표정
        switch (characterSlug) {
            case "taeri" -> sb.append(TAERI_EMOTION_PROMPTS.getOrDefault(
                normalize(emotion), TAERI_EMOTION_PROMPTS.get("NEUTRAL"))).append(", ");
            case "luna" -> sb.append(LUNA_EMOTION_PROMPTS.getOrDefault(
                normalize(emotion), LUNA_EMOTION_PROMPTS.get("NEUTRAL"))).append(", ");
            case "airi" -> sb.append(AIRI_EMOTION_PROMPTS.getOrDefault(
                normalize(emotion), AIRI_EMOTION_PROMPTS.get("NEUTRAL"))).append(", ");
            default -> sb.append(YEONHWA_EMOTION_PROMPTS.getOrDefault(
                normalize(emotion), YEONHWA_EMOTION_PROMPTS.get("NEUTRAL"))).append(", ");
        }

        String prompt = sb.toString();
        log.info("[ILLUST-PROMPT] Assembled positive: slug={}, len={}", characterSlug, prompt.length());
        return prompt;
    }

    /**
     * 고정 네거티브 프롬프트 반환
     */
    public String getNegativePrompt() {
        return FIXED_NEGATIVE_PROMPT;
    }

    /**
     * 캐릭터 slug → LoRA URL
     */
    public String getLoraUrl(String characterSlug) {
        CharacterVisual visual = CHARACTER_VISUALS.get(characterSlug);
        return visual != null ? visual.loraUrl : CHARACTER_VISUALS.get("airi").loraUrl;
    }

    /**
     * 등록된 캐릭터 slug인지 확인
     */
    public boolean isSupported(String characterSlug) {
        return CHARACTER_VISUALS.containsKey(characterSlug);
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  내부 구조
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    private record CharacterVisual(String loraTrigger, String identityPrompt, String loraUrl) {}

    private static String normalize(String s) {
        return s == null ? "" : s.trim().toUpperCase();
    }
}