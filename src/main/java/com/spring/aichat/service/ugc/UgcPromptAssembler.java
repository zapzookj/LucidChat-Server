package com.spring.aichat.service.ugc;

import com.spring.aichat.domain.enums.EmotionTag;
import com.spring.aichat.dto.ugc.StructuredConcept;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * [UGC v1] 이미지·시스템 프롬프트 조립 상수의 단일 소스 (스펙 §4.2~4.4).
 *
 * <p>원칙:
 * <ul>
 *   <li>유저 자유 텍스트는 절대 여기 들어오지 않는다 — Stage 0 구조화 태그만 조립.</li>
 *   <li>품질 프리픽스·네거티브는 <b>검증 Export JSON 값</b>이 서버 상수다
 *       (2026-07-17 결정 — 스펙 §4.2의 구버전 문구 아님).</li>
 *   <li>WF-2 positive에는 씬·소품 태그 절대 포함 금지 — "입력 이미지가 이미 그런 상태"를 서술.</li>
 *   <li>HEATED·FLIRTATIOUS는 SFW 라인 유지 태그로 구성 — 수위 태그 추가 금지.</li>
 * </ul>
 */
@Component
@lombok.RequiredArgsConstructor
public class UgcPromptAssembler {

    private final com.spring.aichat.config.UgcPipelineProperties props;

    /** 검증 Export 값 (wf1/wf2 node 12 프리픽스). */
    static final String QUALITY_PREFIX = "masterpiece, best quality, newest, absurdres";

    static final String WF2_POSE_TAGS = "standing, cowboy shot, looking at viewer";

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  Qwen 편집 템플릿 (§4.3 — 검증 최종본의 일반화)
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /**
     * [2026-07-21 컨셉 반영 자세] 자세 슬롯(%s)만 캐릭터별 동적 값으로 치환하는 템플릿.
     * 프레이밍·카메라 가드는 서버가 사수 — 카메라 방향 기울임이 비율을 깨는 실측(Pleading 사례) 대응.
     */
    private static final String QWEN_POSE_TEMPLATE = """
        Change the character's pose and framing: reframe to a cowboy shot (from mid-thigh up),
        standing and facing the viewer. %s
        Both hands empty — remove any held objects and any furniture she is leaning on.
        Avoid a stiff, symmetrical, ID-photo-like pose. Beyond this reframing, do not change
        the camera distance or angle — she must not lean toward or away from the camera.
        Keep the background, lighting, character identity, face, hairstyle, outfit details,
        proportions, and art style completely unchanged.""";

    /** 동적 자세 부재 시 폴백 — 기존 검증 스탠스. */
    private static final String DEFAULT_BASE_STANCE = """
        A natural, relaxed stance — weight shifted subtly onto one leg, shoulders loose,
        a very slight tilt of the head. One hand resting lightly on her hip, the other arm
        relaxed at her side. Calm, composed expression looking at the viewer.""";

    /** [2026-07-21 누끼 품질] 균일 단색·실루엣 주변 강조 — WF-3 배경 제거 경계 품질의 근간. */
    private static final String QWEN_BACKGROUND_PROMPT = """
        Replace the entire background with a completely uniform, perfectly solid %s background —
        one single flat color filling the whole frame edge to edge. Remove all background scenery,
        gradients, textures, shadows, and lighting effects. Pay special attention to the area
        around the character's hair and silhouette: it must be the same clean solid color,
        with no halos, glow, or leftover background pixels between hair strands.
        Change the lighting on the character to flat, even, neutral lighting —
        remove any colored ambient cast, dramatic shadows, and rim light from her.
        Do not change the character herself: same pose, same face, same hairstyle,
        same outfit details, same proportions, same art style.""";

    private static final String QWEN_EMOTION_PROMPT = """
        Change the character's emotion from neutral to %s.
        Facial expression: %s.
        Pose: %s.
        She must not lean or move toward or away from the camera — keep her distance from
        the camera, her scale in the frame, and her body proportions exactly the same.
        Keep everything else exactly the same: same character identity and face shape,
        same hairstyle and hair color, same eye color, same outfit with identical details,
        same body proportions, same framing and camera angle, same flat lighting,
        same plain background. The character must remain instantly recognizable
        as the same person.""";

    private static final String QWEN_NEGATIVE = "different person, changed face, changed hairstyle, "
        + "changed outfit, altered proportions, extra fingers, deformed hands, holding object, "
        + "three-quarter view, side view, cropped head, stiff symmetrical pose";

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  감정 14종 매핑 (§4.4 — UgcPromptAssembler 상수 맵)
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /** Qwen 표정/자세 지시 + WF-2 감정 태그. NEUTRAL은 베이스 자체(파생 없음). */
    record EmotionPromptSpec(String expression, String pose, String wf2Tags) {}

    private static final Map<EmotionTag, EmotionPromptSpec> EMOTIONS = new EnumMap<>(Map.ofEntries(
        Map.entry(EmotionTag.NEUTRAL, new EmotionPromptSpec(null, null,
            "neutral expression")),
        // [2026-07-21 구도 가드] 카메라 방향 기울임·후퇴 자세는 비율 붕괴 실측(Pleading)으로 전면 완화 —
        // 자세 변주는 팔·손·어깨·고개 등 상반신 제스처로 한정한다.
        Map.entry(EmotionTag.JOY, new EmotionPromptSpec(
            "bright open smile, eyes gently curved in happiness",
            "shoulders relaxed, a light lively energy in her posture",
            "smile, happy, cheerful expression")),
        Map.entry(EmotionTag.SAD, new EmotionPromptSpec(
            "downcast eyes, faint frown, brows tilted up",
            "head lowered slightly, shoulders drooping, hands loosely together in front",
            "sad, downcast eyes, frown")),
        Map.entry(EmotionTag.ANGRY, new EmotionPromptSpec(
            "furrowed brows drawn together, sharp glare, displeased frown",
            "both hands on hips with elbows out, tense squared shoulders",
            "angry, furrowed brow, glare, v-shaped eyebrows")),
        Map.entry(EmotionTag.SHY, new EmotionPromptSpec(
            "soft blush across cheeks, gaze averted downward, small bashful smile",
            "body turned slightly away, one hand near her chest",
            "shy, blush, looking away, embarrassed")),
        Map.entry(EmotionTag.SURPRISE, new EmotionPromptSpec(
            "wide open eyes, raised eyebrows, mouth slightly open",
            "shoulders raised in a startled flinch, one hand raised near her face",
            "surprised, wide eyes, open mouth")),
        Map.entry(EmotionTag.PANIC, new EmotionPromptSpec(
            "flustered wide eyes, wavering mouth, light sweat",
            "both hands raised waving slightly in fluster, body tense",
            "panicking, flustered, sweatdrop, motion lines")),
        Map.entry(EmotionTag.RELAX, new EmotionPromptSpec(
            "soft gentle smile, calm half-closed eyes",
            "fully relaxed shoulders, weight settled comfortably",
            "relaxed, gentle smile, half-closed eyes")),
        Map.entry(EmotionTag.DISGUST, new EmotionPromptSpec(
            "scornful narrowed eyes, one brow raised, corners of mouth turned down",
            "arms crossed, chin slightly raised, body angled a bit away",
            "disgusted, scornful expression, narrowed eyes, frown")),
        Map.entry(EmotionTag.FRIGHTENED, new EmotionPromptSpec(
            "fearful wide eyes, trembling lips, brows raised in worry",
            "body shrunk inward, both hands pulled defensively to her chest",
            "scared, fearful expression, trembling, wavy mouth")),
        Map.entry(EmotionTag.FLIRTATIOUS, new EmotionPromptSpec(
            "seductive half-lidded eyes, knowing smirk, slight head tilt",
            "one hand near her lips or resting on her hip, a slight playful tilt of the head and shoulders",
            "seductive smile, half-lidded eyes, smirk, head tilt")),
        Map.entry(EmotionTag.HEATED, new EmotionPromptSpec(
            "deep full-face blush, heavy-lidded dazed eyes, parted lips",
            "body slightly swaying, one hand touching her own cheek",
            "heavy blush, full-face blush, parted lips, dazed expression")),
        Map.entry(EmotionTag.DUMBFOUNDED, new EmotionPromptSpec(
            "blank wide stare, mouth half open, flat brows",
            "arms hanging limp, slight forward slump",
            "dumbfounded, blank stare, open mouth, jitome")),
        Map.entry(EmotionTag.SULKING, new EmotionPromptSpec(
            "puffed cheeks, pout, eyes turned pointedly away",
            "arms crossed, body turned sideways away from the viewer",
            "pout, puffy cheeks, looking away, sulking")),
        Map.entry(EmotionTag.PLEADING, new EmotionPromptSpec(
            "large teary begging eyes, brows tilted up desperately",
            "both hands clasped together in front of her chest, shoulders slightly raised",
            "pleading eyes, tearing up, hands clasped, begging"))
    ));

    /** 스타 토폴로지 파생 대상 — NEUTRAL 제외 14종. */
    public static List<EmotionTag> derivedEmotions() {
        return EMOTIONS.keySet().stream().filter(t -> t != EmotionTag.NEUTRAL).toList();
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  WF-1 / WF-2 positive 조립 (§4.2)
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /**
     * WF-1 = 프리픽스 + 1girl, solo + 외형 태그 + 성격·무드 태그 + 씬 연출 태그.
     * (중복 태그는 1회만 — LLM이 1girl/solo를 포함해도 안전. 검증 원본 프롬프트도 성격 태그를 포함했다.)
     */
    public String goldenShotPositive(List<String> appearanceTags, List<String> personaTags, List<String> sceneTags) {
        StringBuilder sb = new StringBuilder(QUALITY_PREFIX).append(", 1girl, solo");
        java.util.Set<String> seen = seedSeen();
        appendTags(sb, appearanceTags, seen);
        appendTags(sb, personaTags, seen);
        appendTags(sb, sceneTags, seen);
        return sb.toString();
    }

    /**
     * WF-2 = 프리픽스 + 1girl, solo + 외형 태그 + 성격·무드 태그 + 자세 고정 태그 + 감정 태그 + 배경 지시.
     * 씬·소품 태그 절대 포함 금지.
     */
    public String refinePositive(List<String> appearanceTags, List<String> personaTags,
                                 EmotionTag emotion, String bgColor) {
        StringBuilder sb = new StringBuilder(QUALITY_PREFIX).append(", 1girl, solo");
        java.util.Set<String> seen = seedSeen();
        appendTags(sb, appearanceTags, seen);
        appendTags(sb, personaTags, seen);
        sb.append(", ").append(WF2_POSE_TAGS);
        sb.append(", ").append(EMOTIONS.get(emotion).wf2Tags());
        // [2026-07-21 누끼 품질] 배경 단색화 어텐션 강화 — WF-3 경계 품질(머리카락 계단 현상)의
        // 근본 원인이 배경 보색 주입 미흡으로 실측되어 가중치 부여. 색 가중치는 튜닝 노브.
        sb.append(", (simple background:1.2), (")
            .append(bgColor).append(" background:").append(bgEmphasis())
            .append("), (flat lighting:1.1)");
        return sb.toString();
    }

    private String bgEmphasis() {
        double w = props.generation().bgEmphasis();
        return java.math.BigDecimal.valueOf(w).stripTrailingZeros().toPlainString();
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  FaceDetailer 와일드카드 (2026-07-20 — 감정 15종 간 얼굴 디테일 일관성)
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /**
     * [2026-07-21 재구성] 눈 관련 + 얼굴 식별점(mole/freckle)만 — 헤어 태그는 디테일 패스에서
     * 무의미하며 어텐션만 희석하는 것으로 실측되어 제외(플레이그라운드 PoC).
     */
    private static final List<String> FACE_TAG_KEYWORDS = List.of(
        "eye", "pupil", "iris", "lash", "brow", "heterochromia", "mole", "freckle");

    /**
     * FaceDetailer 와일드카드 — 와일드카드는 디테일 패스의 프롬프트를 <b>대체</b>하므로 구성이 곧
     * 얼굴 품질이다. [2026-07-21 PoC 확정 구성]: detailed beautiful eyes(기본 품질) + 눈 색·눈매
     * 태그 + 캐릭터 무드(persona) + <b>감정 표정 태그</b> — 표정이 빠지면 디테일 패스가 Qwen이
     * 만든 표정을 중화시킨다(감정 컷 밋밋함의 원인).
     */
    public String faceDetailWildcard(List<String> appearanceTags, List<String> personaTags, EmotionTag emotion) {
        StringBuilder sb = new StringBuilder("detailed beautiful eyes");
        java.util.Set<String> seen = new java.util.LinkedHashSet<>();
        if (appearanceTags != null) {
            for (String tag : appearanceTags) {
                if (tag == null || tag.isBlank()) continue;
                String lower = tag.toLowerCase();
                if (FACE_TAG_KEYWORDS.stream().anyMatch(lower::contains) && seen.add(lower)) {
                    sb.append(", ").append(tag.trim());
                }
            }
        }
        if (personaTags != null) {
            for (String tag : personaTags) {
                if (tag == null || tag.isBlank() || !seen.add(tag.toLowerCase())) continue;
                sb.append(", ").append(tag.trim());
            }
        }
        if (emotion != null) {
            sb.append(", ").append(EMOTIONS.get(emotion).wf2Tags());
        }
        return sb.toString();
    }

    /** 프리픽스에 이미 들어간 태그 — appendTags 중복 방지 초기 집합. */
    private static java.util.Set<String> seedSeen() {
        java.util.Set<String> seen = new java.util.LinkedHashSet<>();
        seen.add("1girl");
        seen.add("solo");
        for (String t : QUALITY_PREFIX.split(",")) {
            seen.add(t.trim().toLowerCase());
        }
        return seen;
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  Qwen 프롬프트 (§4.3~4.4)
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /** 패스 1 — 자세·구도 표준화 (배경 유지). 기본 스탠스 폴백. */
    public String qwenPosePrompt() {
        return qwenPosePrompt(null);
    }

    /**
     * [2026-07-21 컨셉 반영 자세] 패스 1 — Stage0가 산출한 캐릭터별 기본 자세를 슬롯에 주입.
     * 프레이밍·카메라 가드는 템플릿이 사수(카메라 방향 기울임 금지 — 비율 붕괴 완화).
     */
    public String qwenPosePrompt(String basePose) {
        String stance = (basePose != null && !basePose.isBlank())
            ? basePose.trim() : DEFAULT_BASE_STANCE.strip();
        if (!stance.endsWith(".")) stance = stance + ".";
        return QWEN_POSE_TEMPLATE.formatted(stance);
    }

    /** 패스 2 — 배경·조명 클린업. BG_COLOR는 WF-2 positive와 반드시 동일 값 주입. */
    public String qwenBackgroundPrompt(String bgColor) {
        return QWEN_BACKGROUND_PROMPT.formatted(bgColor);
    }

    /** 감정 파생 — 베이스에서 직접(스타 토폴로지, 체인 편집 금지). */
    public String qwenEmotionPrompt(EmotionTag emotion) {
        return qwenEmotionPrompt(emotion, null);
    }

    /**
     * [2026-07-20] 캐릭터 개성 반영 감정 파생 — persona 태그를 힌트로 부착해
     * 같은 감정이라도 캐릭터 성격에 맞는 표정·자세 변주가 나오게 한다.
     * (기존: 전 캐릭터 공통 고정 지시 → 단조로움 실측 지적)
     */
    public String qwenEmotionPrompt(EmotionTag emotion, String personaHint) {
        return qwenEmotionPrompt(emotion, personaHint, null);
    }

    /**
     * [2026-07-21 컨셉 반영 감정] 캐릭터별 동적 표정·자세(LLM 산출)가 있으면 상수 대신 주입.
     * 카메라 거리·앵글·프레이밍 가드는 템플릿이 사수 — 동적 값이 무엇이든 구도는 불변.
     */
    public String qwenEmotionPrompt(EmotionTag emotion, String personaHint,
                                    StructuredConcept.EmotionPromptOverride override) {
        EmotionPromptSpec spec = EMOTIONS.get(emotion);
        if (spec == null || spec.expression() == null) {
            throw new IllegalArgumentException("파생 불가 감정: " + emotion);
        }
        String expression = (override != null && override.expression() != null && !override.expression().isBlank())
            ? override.expression().trim() : spec.expression();
        String pose = (override != null && override.pose() != null && !override.pose().isBlank())
            ? override.pose().trim() : spec.pose();
        String base = QWEN_EMOTION_PROMPT.formatted(
            emotion.name().toLowerCase(), expression, pose);
        if (personaHint != null && !personaHint.isBlank()) {
            base += "\nHer personality: " + personaHint.trim()
                + ". Let the expression and small gestures subtly reflect this personality"
                + " while strictly keeping the emotion " + emotion.name().toLowerCase() + ".";
        }
        return base;
    }

    /** 감정별 WF-2 태그 (동적 감정 프롬프트 산출 지시·와일드카드 조립 공용). */
    static String wf2TagsFor(EmotionTag emotion) {
        return EMOTIONS.get(emotion).wf2Tags();
    }

    public String qwenNegative() {
        return QWEN_NEGATIVE;
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  UGC baseSystemPrompt 골격 (공식 캐릭터 프롬프트 골격의 템플릿화)
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /**
     * 공식 시드의 base-system-prompt(짧은 영문 정체성 블록)와 동일 형식.
     * 상세 인격은 CharacterPromptAssembler가 영혼 필드(backstory/coreValues/flaws/speechQuirks)로 주입한다.
     */
    public String buildUgcBaseSystemPrompt(StructuredConcept.CharacterProfile profile) {
        String role = profile.role() != null ? profile.role() : "an original character";
        return """
            You are %s, %s.
            Stay true to the persona defined in your identity section at all times — \
            your personality, values, flaws, and speech habits are who you are.
            You speak natural Korean that matches your defined tone.""".formatted(profile.name(), role);
    }

    private static void appendTags(StringBuilder sb, List<String> tags, java.util.Set<String> seen) {
        if (tags == null) return;
        for (String tag : tags) {
            if (tag == null || tag.isBlank()) continue;
            String trimmed = tag.trim();
            if (!seen.add(trimmed.toLowerCase())) continue; // 중복 태그 1회만
            sb.append(", ").append(trimmed);
        }
    }
}
