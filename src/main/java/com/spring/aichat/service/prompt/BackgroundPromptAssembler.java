package com.spring.aichat.service.prompt;

import com.spring.aichat.domain.world.World;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * [Phase 5.5-Illust → Phase 6-Illust 재설계 v2] 장소 배경 프롬프트 조립기.
 *
 * <p><b>Flux 2 Dev/Stream PoC 결과 반영 (v2):</b>
 * <ol>
 *   <li><b>Prefix 최소화</b> — 가장 앞단에 화풍 명시 한 문장
 *       ({@code "High-quality 2D anime visual novel background art"})만으로도
 *       일관된 고품질 출력이 확인됨. 군더더기 품질 수식어/조명 키워드 제거.</li>
 *   <li><b>시간대 분위기 매핑 제거</b> — 어셈블러에서 시간대를 enum→문장 변환해 덧붙이는 대신,
 *       LLM이 location_description을 생성할 때 시간대 분위기를 포함하도록 prompt 지시로 위임
 *       (CharacterPromptAssembler / DirectorPromptAssembler 패치 참조).</li>
 *   <li><b>Negative prompt 폐기</b> — Flux 2는 positive/negative를 구분하지 않는다.
 *       금지 사항(사람/텍스트/워터마크)은 전체 프롬프트 후미에 *자연어 문장*으로 통합.</li>
 * </ol>
 *
 * <p>최종 prompt 구조:
 * <pre>
 *   High-quality 2D anime visual novel background art. {World mood, 있으면}.
 *   {LLM 자연어 장소 묘사 — 시간대/조명/분위기 포함}.
 *   The scene is completely empty with no people ... no text, signs, watermarks, or logos.
 * </pre>
 */
@Component
@Slf4j
public class BackgroundPromptAssembler {

    /** 화풍 명시 — PoC 상 이 한 문장만으로 일관된 고품질 출력 확보. */
    private static final String STYLE_PREFIX =
        "High-quality 2D anime visual novel background art";

    /**
     * 금지 사항을 자연어로 후미에 통합 (Flux 2는 negative prompt 미지원).
     */
    private static final String NEGATIVE_AS_NATURAL =
        "The scene is completely empty with no people, characters, or silhouettes, " +
            "and contains no text, signs, watermarks, or logos";

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  공개 API
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /**
     * Flux 2용 자연어 프롬프트 조립.
     *
     * @param locationDescription LLM이 자연어로 작성한 장소 묘사 — 시간대/조명/분위기 포함된 영문 1~3문장
     * @param world               세계관 (mood prefix용, nullable — null이면 mood 생략)
     */
    public String assemblePositivePrompt(String locationDescription, World world) {
        StringBuilder sb = new StringBuilder();

        // (1) 화풍 prefix — 단일 문장
        sb.append(STYLE_PREFIX).append(". ");

        // (2) World mood (있을 때만, 시대 정합성 이중 안전망)
//        String worldMood = extractWorldMood(world);
//        if (worldMood != null && !worldMood.isBlank()) {
//            sb.append("Setting: ").append(worldMood).append(". ");
//        }

        // (3) LLM 자연어 묘사 (시간대/조명/분위기는 LLM이 직접 포함)
        if (locationDescription != null && !locationDescription.isBlank()) {
            String desc = locationDescription.trim();
            if (!desc.endsWith(".")) desc = desc + ".";
            sb.append(desc).append(" ");
        }

        // (4) 금지 사항 자연어 통합 (negative prompt 대체)
        sb.append(NEGATIVE_AS_NATURAL).append(".");

        String prompt = sb.toString();
        log.info("[BG-PROMPT] Assembled (Flux v2): worldId={}, len={}",
            world != null ? world.getId() : null, prompt.length());
        return prompt;
    }

    /**
     * [Deprecated 시그니처 호환] 이전 (description, timeOfDay, world) 호출 경로 보호용.
     * timeOfDay 인자는 *무시*된다 — 시간대 분위기는 이제 LLM이 locationDescription에 포함.
     */
    @Deprecated
    public String assemblePositivePrompt(String locationDescription, String timeOfDay, World world) {
        return assemblePositivePrompt(locationDescription, world);
    }

    /** [Deprecated] World 없는 구버전 경로. */
    @Deprecated
    public String assemblePositivePrompt(String locationDescription, String timeOfDay) {
        return assemblePositivePrompt(locationDescription, (World) null);
    }

    /**
     * Flux 2는 negative prompt를 지원하지 않는다. 빈 문자열 반환.
     * 호출처는 이 값을 Fal.ai 요청에 포함하지 않는다.
     *
     * @deprecated Flux 2 positive-only. 금지 사항은 positive prompt 후미에 자연어로 통합됨.
     */
    @Deprecated
    public String getNegativePrompt() {
        return "";
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  내부 헬퍼
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /**
     * World 엔티티에서 시대/화풍 정합성 mood 추출.
     * 우선순위: moodKeywords > tagline > displayName.
     */
    private String extractWorldMood(World world) {
        if (world == null) return null;
        if (world.getMoodKeywords() != null && !world.getMoodKeywords().isBlank()) {
            return world.getMoodKeywords().trim();
        }
        if (world.getTagline() != null && !world.getTagline().isBlank()) {
            return world.getTagline().trim();
        }
        if (world.getDisplayName() != null) {
            return world.getDisplayName();
        }
        return null;
    }
}