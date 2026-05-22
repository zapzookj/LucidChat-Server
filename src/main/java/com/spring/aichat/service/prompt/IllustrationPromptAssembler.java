package com.spring.aichat.service.prompt;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * [Phase 5.5-Illust] 캐릭터 일러스트 프롬프트 조립기 (Danbooru 태그 양식, SDXL/ModelsLab 호환)
 *
 * <p>[Phase 6-Illust] 6단 구조로 확장. 동적 묘사 강화를 위한 신규 슬롯 2개 추가:
 * <ol>
 *   <li>LoRA 트리거 + 고정 품질 prefix       — 백엔드 통제 (변동성 0)</li>
 *   <li>캐릭터 정체성 (눈/머리/특수특성)     — 백엔드 통제 (변동성 0)</li>
 *   <li>복장 (outfit enum 매핑)             — 백엔드 통제 (변동성 낮음)</li>
 *   <li><b>장소</b> — 정적이면 enum 매핑, 동적이면 LLM 자연어 묘사 활용 — 하이브리드 (중간)</li>
 *   <li>감정/표정 (emotion enum 매핑)       — 백엔드 통제 (변동성 낮음)</li>
 *   <li><b>자세/액션/시츄에이션</b> — LLM의 illustration_scene_hint 직삽입 — LLM 통제 (높음)</li>
 * </ol>
 *
 * <p><b>밸런스 철학</b>:
 * 캐릭터 정체성·표정·의상은 변수 통제(시리즈 일관성)를 위해 백엔드가 enum으로 못박고,
 * 자세·액션·시츄에이션은 LLM의 매 응답 hint로 자연스러운 동적 묘사를 더한다.
 * Phase 5.5 시기 "너무 정적" 문제 해결의 핵심 변경.
 */
@Component
@Slf4j
public class IllustrationPromptAssembler {

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  고정 prefix / negative
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    private static final String FIXED_QUALITY_PREFIX =
        "(masterpiece, best quality, ultra-detailed:1.2), anime style, 1girl, solo";

    private static final String FIXED_NEGATIVE_PROMPT =
        "(worst quality, low quality:1.4), blurry, distorted, malformed hands, " +
            "extra fingers, mutated, watermark, text, signature, multiple girls, " +
            "bad anatomy, ugly, deformed";

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  캐릭터 시각 정체성 매핑
    //  ※ 더미 데이터 — 실제 값은 yml 또는 DB에서 관리
    //  ※ loraUrl 필드는 Phase 6 피벗 후 의미가 *ModelsLab LoRA Model ID*로 재해석됨.
    //      (필드명은 호환 유지, 값은 PoC 완료 후 yml로 외부 주입)
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    private static final Map<String, CharacterVisual> CHARACTER_VISUALS = new LinkedHashMap<>();
    static {
        CHARACTER_VISUALS.put("airi", new CharacterVisual(
            "lcn_char02",
            "multicolored eyes, blue eyes, yellow eyes, gradient eyes, pink hair, short hair, medium hair, bangs",
            "lucid_airi_v1"
        ));
        CHARACTER_VISUALS.put("taeri", new CharacterVisual(
            "lcn_char03",
            "purple eyes, grey hair, medium hair",
            "lucid_taeri_v1"
        ));
        CHARACTER_VISUALS.put("luna", new CharacterVisual(
            "lcn_char01",
            "blue eyes, black hair, blonde inner hair, multicolored hair, two-tone hair, bangs, short hair, colored inner hair",
            "lucid_luna_v2"
        ));
        CHARACTER_VISUALS.put("yeonhwa", new CharacterVisual(
            "lcn_char04",
            "red eyes, animal ear fluff, fox girl, white hair, long hair, animal ears, fox ears, fox tail, breasts, multiple tails, facial mark, bangs",
            "lucid_yeonhwa_v1"
        ));
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  복장 프롬프트 매핑
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    private static final Map<String, String> AIRI_OUTFIT_PROMPTS = new LinkedHashMap<>();
    static {
        AIRI_OUTFIT_PROMPTS.put("MAID", "maid headdress, bunny hair ornament, classic maid outfit, black dress, white frilled apron, black ribbon bowtie, short puffy sleeves, black thighhighs, mary janes");
        AIRI_OUTFIT_PROMPTS.put("DATE", "bag, shirt, hairband, blue cardigan, jewelry, necklace, white shirt, pleated skirt, long sleeves, black skirt, black hairband, handbag");
        AIRI_OUTFIT_PROMPTS.put("SWIMSUIT", "swimsuit, bikini, navel, cleavage, sarong, blue bikini, collarbone, frills, ribbon, frilled bikini, hair ribbon, open clothes, thighs, long sleeves, cardigan, stomach, front-tie top, blue ribbon");
    }

    private static final Map<String, String> TAERI_OUTFIT_PROMPTS = new LinkedHashMap<>();
    static {
        TAERI_OUTFIT_PROMPTS.put("DAILY", "casual outfit, blouse, skirt, simple jewelry");
        TAERI_OUTFIT_PROMPTS.put("DATE", "elegant dress, hair accessory");
        TAERI_OUTFIT_PROMPTS.put("SWIMSUIT", "swimsuit, bikini, navel");
    }

    private static final Map<String, String> LUNA_OUTFIT_PROMPTS = new LinkedHashMap<>();
    static {
        LUNA_OUTFIT_PROMPTS.put("DAILY", "school uniform, blazer, pleated skirt, ribbon tie");
        LUNA_OUTFIT_PROMPTS.put("DATE", "stylish outfit, jacket");
        LUNA_OUTFIT_PROMPTS.put("SWIMSUIT", "swimsuit, bikini");
    }

    private static final Map<String, String> YEONHWA_OUTFIT_PROMPTS = new LinkedHashMap<>();
    static {
        YEONHWA_OUTFIT_PROMPTS.put("HANBOK", "hanbok, jeogori, chima, traditional Korean clothing, embroidery, silk ribbon");
        YEONHWA_OUTFIT_PROMPTS.put("DAILY", "modern outfit");
        YEONHWA_OUTFIT_PROMPTS.put("SWIMSUIT", "swimsuit, bikini");
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  정적 장소 프롬프트 매핑 (enum 기반)
    //  ※ 동적 장소(LLM 자연어)일 때는 dynamicLocationDescription 활용
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    private static final Map<String, String> LOCATION_PROMPTS = new LinkedHashMap<>();
    static {
        LOCATION_PROMPTS.put("MAID_CAFE",  "maid cafe interior, cozy lighting, decorated tables, romantic ambiance");
        LOCATION_PROMPTS.put("HOME",       "indoor, cozy living room, warm lighting, comfortable atmosphere");
        LOCATION_PROMPTS.put("BEDROOM",    "indoor, bedroom, soft lighting, intimate atmosphere");
        LOCATION_PROMPTS.put("KITCHEN",    "indoor, kitchen, modern appliances, warm cooking lights");
        LOCATION_PROMPTS.put("OUTDOOR",    "outdoor, blurred park background, natural lighting");
        LOCATION_PROMPTS.put("PARK",       "outdoor, park, trees, grass, sunlight");
        LOCATION_PROMPTS.put("BEACH",      "outdoor, beach, ocean, sand, blue sky, sunny");
        LOCATION_PROMPTS.put("CAFE",       "indoor, modern cafe, ambient lighting, coffee aroma");
        LOCATION_PROMPTS.put("SCHOOL",     "indoor, school classroom, daytime");
        LOCATION_PROMPTS.put("ROOFTOP",    "outdoor, rooftop, evening sky, city skyline");
        LOCATION_PROMPTS.put("LIBRARY",    "indoor, library, bookshelves, quiet warm light");
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  감정 프롬프트 매핑 (캐릭터별 — 표정만 다루며, 자세는 sceneHint 책임)
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    private static final Map<String, String> AIRI_EMOTION_PROMPTS = new LinkedHashMap<>();
    static {
        AIRI_EMOTION_PROMPTS.put("NEUTRAL",    "calm face, soft expression, gentle gaze");
        AIRI_EMOTION_PROMPTS.put("JOY",        "smiling, bright eyes, happy expression");
        AIRI_EMOTION_PROMPTS.put("LAUGH",      "laughing, eyes closed, wide smile");
        AIRI_EMOTION_PROMPTS.put("SHY",        "blushing, looking away, embarrassed");
        AIRI_EMOTION_PROMPTS.put("SAD",        "sad eyes, slight frown, downcast gaze");
        AIRI_EMOTION_PROMPTS.put("LOVE",       "loving gaze, soft blush, tender smile, heart in eyes");
        AIRI_EMOTION_PROMPTS.put("SURPRISED",  "wide eyes, mouth open, surprised expression");
        AIRI_EMOTION_PROMPTS.put("ANGRY",      "frowning, sharp eyes, irritated expression");
        AIRI_EMOTION_PROMPTS.put("EMBARRASSED","heavy blush, looking down, flustered");
    }

    private static final Map<String, String> TAERI_EMOTION_PROMPTS = new LinkedHashMap<>();
    static {
        TAERI_EMOTION_PROMPTS.put("NEUTRAL",   "calm face, composed expression");
        TAERI_EMOTION_PROMPTS.put("JOY",       "soft smile, gentle eyes");
        TAERI_EMOTION_PROMPTS.put("SHY",       "slight blush, averted gaze");
        TAERI_EMOTION_PROMPTS.put("SAD",       "melancholic eyes, subtle frown");
        TAERI_EMOTION_PROMPTS.put("LOVE",      "warm gaze, gentle blush, tender smile");
        TAERI_EMOTION_PROMPTS.put("SURPRISED", "wide eyes, parted lips");
        TAERI_EMOTION_PROMPTS.put("ANGRY",     "stern eyes, pressed lips");
    }

    private static final Map<String, String> LUNA_EMOTION_PROMPTS = new LinkedHashMap<>();
    static {
        LUNA_EMOTION_PROMPTS.put("NEUTRAL",    "calm expression, neutral gaze");
        LUNA_EMOTION_PROMPTS.put("JOY",        "smile, bright eyes");
        LUNA_EMOTION_PROMPTS.put("SHY",        "blushing cheeks, shy smile");
        LUNA_EMOTION_PROMPTS.put("SAD",        "sad eyes, downcast");
        LUNA_EMOTION_PROMPTS.put("LOVE",       "loving expression, soft smile, blush");
        LUNA_EMOTION_PROMPTS.put("SURPRISED",  "surprised eyes wide");
        LUNA_EMOTION_PROMPTS.put("ANGRY",      "angry expression, frown");
    }

    private static final Map<String, String> YEONHWA_EMOTION_PROMPTS = new LinkedHashMap<>();
    static {
        YEONHWA_EMOTION_PROMPTS.put("NEUTRAL",    "calm fox-like expression, mysterious gaze");
        YEONHWA_EMOTION_PROMPTS.put("JOY",        "playful smile, mischievous eyes");
        YEONHWA_EMOTION_PROMPTS.put("SHY",        "shy blush, ears slightly drooped");
        YEONHWA_EMOTION_PROMPTS.put("SAD",        "sad expression, ears down");
        YEONHWA_EMOTION_PROMPTS.put("LOVE",       "tender gaze, soft blush, ears perked up");
        YEONHWA_EMOTION_PROMPTS.put("SURPRISED",  "ears perked, wide eyes");
        YEONHWA_EMOTION_PROMPTS.put("ANGRY",      "fierce eyes, ears flattened back");
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  공개 API
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /**
     * [Phase 6-Illust] 신규 6단 시그니처 — 캐릭터 일러스트 포지티브 프롬프트 조립.
     *
     * @param characterSlug              캐릭터 slug ("airi" / "taeri" / "luna" / "yeonhwa")
     * @param emotion                    감정 enum 문자열 (표정 슬롯)
     * @param location                   장소 enum 문자열 (정적 슬롯, 동적이면 무관)
     * @param outfit                     의상 enum 문자열
     * @param sceneHint                  [Phase 6 신규] LLM의 illustration_scene_hint
     *                                   (Danbooru 영문 콤마 키워드, nullable)
     * @param dynamicLocationDescription [Phase 6 신규] 동적 장소의 LLM 자연어 묘사 (nullable)
     *                                   존재 시 (4) 슬롯의 enum 매핑 대신 이 묘사를 사용
     */
    public String assemblePositivePrompt(
        String characterSlug, String emotion, String location, String outfit,
        String sceneHint, String dynamicLocationDescription
    ) {
        CharacterVisual visual = CHARACTER_VISUALS.getOrDefault(
            characterSlug, CHARACTER_VISUALS.get("airi"));

        StringBuilder sb = new StringBuilder();

        // (1) LoRA 트리거 + 고정 품질
        sb.append(visual.loraTrigger).append(", ").append(FIXED_QUALITY_PREFIX).append(", ");

        // (2) 캐릭터 아이덴티티
        sb.append(visual.identityPrompt).append(", ");

        // (3) 복장
        sb.append(resolveOutfit(characterSlug, outfit)).append(", ");

        // (4) 장소 — 하이브리드: dynamicLocationDescription 우선, 없으면 enum 매핑
        sb.append(resolveLocation(location, dynamicLocationDescription)).append(", ");

        // (5) 감정/표정 (캐릭터별 매핑)
        sb.append(resolveEmotion(characterSlug, emotion)).append(", ");

        // (6) [Phase 6 신규] 자세/액션/시츄에이션 hint
        if (sceneHint != null && !sceneHint.isBlank()) {
            sb.append(sceneHint.trim());
        } else {
            // hint 부재 시: 정적 기본 자세를 부여하여 prompt 일관성 유지
            sb.append("upper body, looking at viewer, gentle pose");
        }

        String prompt = sb.toString();
        log.info("[ILLUST-PROMPT] Assembled: slug={}, hasHint={}, hasDynLoc={}, len={}",
            characterSlug,
            sceneHint != null && !sceneHint.isBlank(),
            dynamicLocationDescription != null && !dynamicLocationDescription.isBlank(),
            prompt.length());
        return prompt;
    }

    /**
     * 구버전 호환 — Phase 5.5의 4-arg 시그니처를 그대로 호출하는 코드용.
     * 신규 슬롯(sceneHint, dynamicLocationDescription)은 null로 처리.
     */
    public String assemblePositivePrompt(String characterSlug, String emotion, String location, String outfit) {
        return assemblePositivePrompt(characterSlug, emotion, location, outfit, null, null);
    }

    /** ModelsLab/SDXL용 negative prompt. */
    public String getNegativePrompt() {
        return FIXED_NEGATIVE_PROMPT;
    }

    /**
     * 캐릭터 slug → ModelsLab LoRA Model ID.
     *
     * <p>[Phase 6-Illust] 의미 재해석: 이전엔 S3의 safetensors URL이었지만,
     * 신 양식에선 ModelsLab에 등록된 LoRA Model ID(또는 CivitAI airID).
     * 값 변경은 PoC 후 yml/DB로 외부 주입 가능.
     */
    public String getLoraId(String characterSlug) {
        CharacterVisual visual = CHARACTER_VISUALS.get(characterSlug);
        return visual != null ? visual.loraUrl : CHARACTER_VISUALS.get("airi").loraUrl;
    }

    /** 구버전 호환 — 동일 의미, 신규 코드는 {@link #getLoraId(String)} 사용 권장. */
    @Deprecated
    public String getLoraUrl(String characterSlug) {
        return getLoraId(characterSlug);
    }

    /** 등록된 캐릭터 slug인지 확인. */
    public boolean isSupported(String characterSlug) {
        return CHARACTER_VISUALS.containsKey(characterSlug);
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  내부 헬퍼 — 슬롯별 해석
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    private String resolveOutfit(String slug, String outfit) {
        String key = normalize(outfit);
        return switch (slug == null ? "" : slug) {
            case "taeri"   -> TAERI_OUTFIT_PROMPTS.getOrDefault(key, TAERI_OUTFIT_PROMPTS.get("DAILY"));
            case "luna"    -> LUNA_OUTFIT_PROMPTS.getOrDefault(key, LUNA_OUTFIT_PROMPTS.get("DAILY"));
            case "yeonhwa" -> YEONHWA_OUTFIT_PROMPTS.getOrDefault(key, YEONHWA_OUTFIT_PROMPTS.get("HANBOK"));
            default        -> AIRI_OUTFIT_PROMPTS.getOrDefault(key, AIRI_OUTFIT_PROMPTS.get("MAID"));
        };
    }

    /**
     * 장소 슬롯 해석.
     * <ul>
     *   <li>dynamicLocationDescription이 있으면(=동적 장소) 그것을 사용</li>
     *   <li>없으면 location enum → LOCATION_PROMPTS 매핑</li>
     *   <li>둘 다 없으면 "simple background"</li>
     * </ul>
     */
    private String resolveLocation(String location, String dynamicLocationDescription) {
        if (dynamicLocationDescription != null && !dynamicLocationDescription.isBlank()) {
            // 동적 장소: LLM 자연어를 안전하게 prompt 토큰 양식으로 다듬음 (마침표 제거)
            String desc = dynamicLocationDescription.trim().replaceAll("\\.+\\s*", ", ");
            // 캐릭터 일러스트는 1girl 위주라, 배경 디테일을 너무 자세하면 캐릭터가 가려질 수 있어 길이 제한
            if (desc.length() > 200) desc = desc.substring(0, 200);
            return desc;
        }
        return LOCATION_PROMPTS.getOrDefault(normalize(location), "simple background");
    }

    private String resolveEmotion(String slug, String emotion) {
        String key = normalize(emotion);
        return switch (slug == null ? "" : slug) {
            case "taeri"   -> TAERI_EMOTION_PROMPTS.getOrDefault(key, TAERI_EMOTION_PROMPTS.get("NEUTRAL"));
            case "luna"    -> LUNA_EMOTION_PROMPTS.getOrDefault(key, LUNA_EMOTION_PROMPTS.get("NEUTRAL"));
            case "yeonhwa" -> YEONHWA_EMOTION_PROMPTS.getOrDefault(key, YEONHWA_EMOTION_PROMPTS.get("NEUTRAL"));
            default        -> AIRI_EMOTION_PROMPTS.getOrDefault(key, AIRI_EMOTION_PROMPTS.get("NEUTRAL"));
        };
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  내부 구조
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    private record CharacterVisual(String loraTrigger, String identityPrompt, String loraUrl) {}

    private static String normalize(String s) {
        return s == null ? "" : s.trim().toUpperCase();
    }
}