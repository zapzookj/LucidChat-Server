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
            "multicolored eyes, blue eyes, yellow eyes, gradient eyes, pink hair, medium hair",
            "lucid_airi_v1.safetensors"
        ));
        CHARACTER_VISUALS.put("taeri", new CharacterVisual(
            "lcn_char03",
            "brown eyes, black hair, long hair, side ponytail, hair ribbon",
            "lucid_taeri_v1.safetensors"
        ));
        CHARACTER_VISUALS.put("luna", new CharacterVisual(
            "lcn_char01",
            "purple eyes, light purple hair, short hair, messy hair, hair clips",
            "lucid_luna_v2.safetensors"
        ));
        CHARACTER_VISUALS.put("yeonhwa", new CharacterVisual(
            "lcn_char04",
            "golden eyes, white hair, very long hair, fox ears, fox tail",
            "lucid_yeonhwa_v1.safetensors"
        ));
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  복장 프롬프트 매핑
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    private static final Map<String, String> AIRI_OUTFIT_PROMPTS = new LinkedHashMap<>();
    static {
        AIRI_OUTFIT_PROMPTS.put("MAID", "maid headdress, bunny hair ornament, classic maid outfit, black dress, white frilled apron, black ribbon bowtie, short puffy sleeves, black thighhighs, mary janes");
        AIRI_OUTFIT_PROMPTS.put("DATE", "casual outfit, white blouse, denim skirt, sneakers");
        AIRI_OUTFIT_PROMPTS.put("SWIMSUIT", "elegant date outfit, off-shoulder sweater, pleated skirt, ankle boots");
    }

    private static final Map<String, String> TAERI_OUTFIT_PROMPTS = new LinkedHashMap<>();
    static {
        TAERI_OUTFIT_PROMPTS.put("DAILY", "maid headdress, bunny hair ornament, classic maid outfit, black dress, white frilled apron, black ribbon bowtie, short puffy sleeves, black thighhighs, mary janes");
        TAERI_OUTFIT_PROMPTS.put("DATE", "casual outfit, white blouse, denim skirt, sneakers");
        TAERI_OUTFIT_PROMPTS.put("SWIMSUIT", "elegant date outfit, off-shoulder sweater, pleated skirt, ankle boots");
    }

    private static final Map<String, String> LUNA_OUTFIT_PROMPTS = new LinkedHashMap<>();
    static {
        LUNA_OUTFIT_PROMPTS.put("DAILY", "maid headdress, bunny hair ornament, classic maid outfit, black dress, white frilled apron, black ribbon bowtie, short puffy sleeves, black thighhighs, mary janes");
        LUNA_OUTFIT_PROMPTS.put("DATE", "casual outfit, white blouse, denim skirt, sneakers");
        LUNA_OUTFIT_PROMPTS.put("SWIMSUIT", "elegant date outfit, off-shoulder sweater, pleated skirt, ankle boots");
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

    private static final Map<String, String> EMOTION_PROMPTS = new LinkedHashMap<>();
    static {
        EMOTION_PROMPTS.put("NEUTRAL", "neutral expression, gentle smile, looking at viewer, relaxed posture");
        EMOTION_PROMPTS.put("JOY", "happy, bright smile, sparkling eyes, joyful, cheerful");
        EMOTION_PROMPTS.put("SHY", "blushing, shy smile, looking away, fidgeting, embarrassed");
        EMOTION_PROMPTS.put("SAD", "sad expression, teary eyes, looking down, melancholy");
        EMOTION_PROMPTS.put("ANGRY", "angry expression, furrowed brows, clenched fists, intense gaze");
        EMOTION_PROMPTS.put("SURPRISE", "surprised, wide eyes, open mouth, hands up");
        EMOTION_PROMPTS.put("LOVE", "loving gaze, warm smile, heart eyes, blushing cheeks, dreamy");
        EMOTION_PROMPTS.put("FLIRTATIOUS", "flirty expression, wink, playful smile, leaning forward, alluring");
        EMOTION_PROMPTS.put("HEATED", "intense gaze, heavy breathing, flushed cheeks, passionate");
        EMOTION_PROMPTS.put("DISGUST", "disgusted face, grimace, stepping back");
        EMOTION_PROMPTS.put("FRIGHTENED", "scared, trembling, wide eyes, clutching self");
        EMOTION_PROMPTS.put("RELAX", "relaxed, peaceful, eyes closed, serene smile, content");
        EMOTION_PROMPTS.put("PANIC", "panicking, frantic, sweatdrop, waving hands");
        EMOTION_PROMPTS.put("DUMBFOUNDED", "dumbfounded, blank stare, open mouth, confused");
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
        String emotionPrompt = EMOTION_PROMPTS.getOrDefault(
            normalize(emotion), EMOTION_PROMPTS.get("NEUTRAL"));
        sb.append(emotionPrompt);

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