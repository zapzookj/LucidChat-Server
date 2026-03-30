package com.spring.aichat.service.prompt;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * [Phase 5.5-Illust] 장소 배경 프롬프트 조립기
 *
 * 프롬프트 구조:
 *   고정 포지티브 + 장소 묘사(시간대 포함) + 고정 네거티브
 *
 * LLM이 new_location_name과 location_description을 제공하면,
 * 해당 정보를 기반으로 배경 생성 프롬프트를 조립한다.
 */
@Component
@Slf4j
public class BackgroundPromptAssembler {

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  고정 프롬프트
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    private static final String FIXED_POSITIVE_PREFIX =
        "(masterpiece, best quality, ultra-detailed), (scenery:1.4), (no humans:1.5)";

    private static final String FIXED_POSITIVE_SUFFIX =
        "visual novel background, anime style";

    private static final String FIXED_NEGATIVE_PROMPT =
        "(worst quality, low quality:1.4), (1girl, 1boy, character, person, human, silhouette:1.5), " +
            "text, signature, watermark, blurry, out of focus";

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  시간대 → 분위기 프롬프트 매핑
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    private static final Map<String, String> TIME_MOOD_PROMPTS = new LinkedHashMap<>();
    static {
        TIME_MOOD_PROMPTS.put("DAWN", "(early morning, golden hour, soft pink sky, first light:1.2)");
        TIME_MOOD_PROMPTS.put("MORNING", "(bright morning, warm sunlight, clear sky, fresh atmosphere:1.2)");
        TIME_MOOD_PROMPTS.put("AFTERNOON", "(warm afternoon sunlight, sunbeams, floating dust:1.2)");
        TIME_MOOD_PROMPTS.put("SUNSET", "(golden sunset, orange sky, warm glow, long shadows:1.3)");
        TIME_MOOD_PROMPTS.put("EVENING", "(twilight, blue hour, city lights beginning, calm:1.2)");
        TIME_MOOD_PROMPTS.put("NIGHT", "(night time, moonlight, starry sky, ambient city glow:1.2)");
        TIME_MOOD_PROMPTS.put("LATE_NIGHT", "(deep night, dim lighting, quiet atmosphere, neon accents:1.2)");
        TIME_MOOD_PROMPTS.put("DAY", "(daytime, clear sky, bright natural lighting:1.1)");
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  공개 API
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /**
     * 장소 배경 포지티브 프롬프트 조립
     *
     * @param locationDescription  LLM이 제공한 장소 묘사 (영문, 프롬프트 수준)
     * @param timeOfDay            시간대 enum 문자열
     * @return 완성된 포지티브 프롬프트
     */
    public String assemblePositivePrompt(String locationDescription, String timeOfDay) {
        StringBuilder sb = new StringBuilder();

        // (1) 고정 접두사
        sb.append(FIXED_POSITIVE_PREFIX).append(",\n");

        // (2) 장소 묘사 (LLM 제공)
        if (locationDescription != null && !locationDescription.isBlank()) {
            sb.append(locationDescription.trim()).append(",\n");
        }

        // (3) 시간대 분위기
        String timeMood = TIME_MOOD_PROMPTS.getOrDefault(
            normalize(timeOfDay), TIME_MOOD_PROMPTS.get("DAY"));
        sb.append(timeMood).append(",\n");

        // (4) 고정 접미사
        sb.append(FIXED_POSITIVE_SUFFIX);

        String prompt = sb.toString();
        log.info("[BG-PROMPT] Assembled: timeOfDay={}, len={}", timeOfDay, prompt.length());
        return prompt;
    }

    /**
     * 고정 네거티브 프롬프트
     */
    public String getNegativePrompt() {
        return FIXED_NEGATIVE_PROMPT;
    }

    private static String normalize(String s) {
        return s == null ? "DAY" : s.trim().toUpperCase();
    }
}