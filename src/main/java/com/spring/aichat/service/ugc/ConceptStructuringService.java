package com.spring.aichat.service.ugc;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.spring.aichat.config.OpenAiProperties;
import com.spring.aichat.dto.ugc.StructuredConcept;
import com.spring.aichat.exception.ExternalApiException;
import com.spring.aichat.external.OpenRouterClient;
import com.spring.aichat.service.util.LlmOutputParser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * [UGC v1] Stage 0 — 유저 자유 서술을 이미지·페르소나 구조화 데이터로 변환.
 *
 * <p>불변 원칙: 유저 텍스트는 여기서 끝난다 — 이후 모든 이미지 프롬프트는
 * 이 산출의 태그만으로 서버 상수와 조립된다(콘텐츠 통제의 근간).
 *
 * <p>비스트리밍 단건 completion — {@link OpenRouterClient#completeJson} 재사용
 * (response_format=json_object 강제).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConceptStructuringService {

    /** BG_COLOR 허용 팔레트 (FIELD_SPEC §4) — 벗어나면 기본값 폴백. */
    static final Set<String> BG_COLOR_PALETTE = Set.of(
        "light gray", "pale blue gray", "muted teal", "soft beige", "dusty lavender");
    static final String BG_COLOR_FALLBACK = "light gray";

    private static final String SYSTEM_PROMPT = """
        너는 캐릭터 컨셉 구조화 엔진이다. 유저의 자유 서술을 아래 JSON 스키마로만 응답한다.
        규칙:
        - appearance_tags: Danbooru 태그 관례(영문 소문자, 개별 태그 문자열 배열), 외형만 40~60개.
          머리(색/길이/스타일), 눈, 체형, 의상, 액세서리를 빠짐없이. 씬·조명·구도 태그 금지.
          입력에 [외형 지정] 블록이 있으면 그 특징을 appearance_tags에 빠짐없이 최우선 반영한다.
        - persona_tags: 캐릭터의 성격·무드 태그 5~8개 (영문 소문자 — 예: kuudere, cold beauty,
          mysterious, playful energy). 외형 태그와 중복 금지. 표정 연출과 무드 표현에 쓰인다.
        - scene_tags: 캐릭터의 직업·분위기에 어울리는 황금샷 연출 10~20개 (배경, 소품, 조명, 구도).
        - bg_color: 머리색(우선)·의상색과 명도 대비가 큰 저채도 1개 —
          ["light gray","pale blue gray","muted teal","soft beige","dusty lavender"] 중 선택.
          밝은 머리(은발·금발·백발)→중간 명도 색, 어두운 머리→light gray.
        - character: 서비스 톤에 맞는 한국어로 작성.
          · name: 한국어 이름 — 유저가 이름을 지정했다면 반드시 그대로 사용
          · tagline: 한 줄 캐치프레이즈
          · age: 캐릭터 나이(정수) — 반드시 19 이상의 성인
          · role: 한 줄 역할 설명 (예: "저택의 메이드")
          · personality: 성격 (3~5문장)
          · tone: 말투 (2~3문장)
          · appearance: 외형 한국어 서술
          · clothing: 복장 한국어 서술
          · backstory: 과거사 3~5문단 — 어떤 사건이 지금의 가치관을 형성했는가
          · core_values: 가치관/철학 bullet 5~7개 — 무엇을 옳다/그르다 여기는가
          · flaws: 약점·두려움·모순 bullet 3~5개 — 살아있는 사람의 결
          · speech_quirks: 어휘 습관·말버릇 (구체 예시 포함)
          · height: 키 — "164cm" 형식 (성인 체형에 자연스러운 값)
          · likes: 좋아하는 것 — 짧은 구 1~2개, 콤마 구분 (컨셉·성격과 정합)
          · dislikes: 싫어하는 것 — 짧은 구 1~2개, 콤마 구분
          · hobby: 취미 — 짧은 구 1~2개
          · profile_quote: 캐릭터를 한 줄로 압축한 자기소개 또는 명대사 1문장 (프로필 카드 노출용 — 성격·말투가 드러나게, 따옴표·마크다운·줄바꿈 금지)
          · first_greeting: 유저를 처음 만났을 때 캐릭터가 입으로 말하는 **첫인사 대사만**.
            순수 발화 텍스트 1~4문장. 마크다운(*), 괄호 지문, 감싸는 따옴표, '이름:' 접두, 줄바꿈 구획 금지.
          · intro_narration: 유저가 캐릭터를 처음 만나는 장면 묘사 2~3문장 (관찰자 시점, "당신은…" 톤).
            상황·행동 묘사는 전부 여기에 — first_greeting에 넣지 않는다.
          · 주의: character의 모든 값은 JSON 배열이 아니라 **단일 문자열**이다.
            bullet은 문자열 안에서 "- 항목" 형태로 개행 구분한다.
        - base_pose: 캐릭터 컨셉·성격에 어울리는 기본 스탠딩 자세 묘사 (영문 1~2문장).
          카우보이샷 프레이밍 안에서 팔·손·어깨·고개 각도 중심으로. 소품 금지.
          카메라 쪽으로 기울이거나 멀어지는 자세, 앵글·구도를 바꾸는 묘사 절대 금지.
        - moderation: 명백한 미성년 신체·설정 시그널이 있을 때만 minor_signal=true (모호하면 false).
        출력 스키마:
        {"appearance_tags":[...], "persona_tags":[...], "scene_tags":[...], "bg_color":"...",
         "character":{"name":"...","tagline":"...","age":23,"role":"...","personality":"...",
          "tone":"...","appearance":"...","clothing":"...","backstory":"...",
          "core_values":"...","flaws":"...","speech_quirks":"...","first_greeting":"...",
          "intro_narration":"...","height":"164cm","likes":"...","dislikes":"...","hobby":"...","profile_quote":"..."},
         "base_pose":"...",
         "moderation":{"minor_signal":false,"reason":""}}
        출력은 JSON 외 어떤 텍스트도 금지.
        """;

    private final OpenRouterClient openRouterClient;
    private final OpenAiProperties openAiProps;
    private final com.spring.aichat.config.UgcPipelineProperties ugcProps;
    private final ObjectMapper objectMapper;

    /** [2026-07-21] Stage0 전용 모델 — ugc.stage0-model 지정 시 우선(외형 태그 품질), 미지정 시 전역 모델. */
    private String effectiveModel() {
        String override = ugcProps.stage0ModelOrNull();
        return override != null ? override : openAiProps.model();
    }

    /**
     * 컨셉 구조화 실행 (블로킹 — @Async 오케스트레이터 스레드에서 호출).
     *
     * @param rawInput      유저 자유 서술 (하드 키워드 게이트 통과본)
     * @param requestedName 유저 지정 이름 (null이면 LLM 작명)
     */
    public StructuredConcept structure(String rawInput, String requestedName) {
        String userMessage = buildUserMessage(rawInput, requestedName);

        String raw;
        try {
            raw = openRouterClient.completeJson(
                effectiveModel(), SYSTEM_PROMPT, userMessage, 8192, 0.7);
        } catch (Exception e) {
            log.error("[UGC-STAGE0] LLM 호출 실패: {}", e.getMessage());
            throw new ExternalApiException("컨셉 구조화 실패 — 잠시 후 다시 시도해 주세요.");
        }

        StructuredConcept concept = parse(raw);
        return sanitize(concept, requestedName);
    }

    private String buildUserMessage(String rawInput, String requestedName) {
        if (requestedName != null && !requestedName.isBlank()) {
            return "[캐릭터 이름 (반드시 이 이름 사용)]: " + requestedName.trim() + "\n\n[컨셉 서술]:\n" + rawInput;
        }
        return "[컨셉 서술]:\n" + rawInput;
    }

    private StructuredConcept parse(String raw) {
        try {
            String json = LlmOutputParser.extractJson(raw);
            return objectMapper.readValue(json, StructuredConcept.class);
        } catch (Exception e) {
            log.error("[UGC-STAGE0] 산출 파싱 실패: {} — raw 앞부분: {}", e.getMessage(),
                raw == null ? "null" : raw.substring(0, Math.min(200, raw.length())));
            throw new ExternalApiException("컨셉 구조화 실패 — 잠시 후 다시 시도해 주세요.");
        }
    }

    /** 필수 필드 검증 + bg_color 팔레트 강제 + 유저 지정 이름 우선. */
    private StructuredConcept sanitize(StructuredConcept c, String requestedName) {
        if (c.appearanceTags() == null || c.appearanceTags().isEmpty()
            || c.character() == null
            || c.character().name() == null || c.character().name().isBlank()) {
            log.error("[UGC-STAGE0] 필수 필드 누락: tags={}, character={}",
                c.appearanceTags() == null ? "null" : c.appearanceTags().size(), c.character());
            throw new ExternalApiException("컨셉 구조화 실패 — 잠시 후 다시 시도해 주세요.");
        }

        String bg = c.bgColor() == null ? null : c.bgColor().toLowerCase(Locale.ROOT).trim();
        String effectiveBg = (bg != null && BG_COLOR_PALETTE.contains(bg)) ? bg : BG_COLOR_FALLBACK;

        // 유저 지정 이름은 LLM 판단보다 우선한다
        String effectiveName = (requestedName != null && !requestedName.isBlank())
            ? requestedName.trim() : c.character().name().trim();

        StructuredConcept.CharacterProfile p = c.character();

        // [2026-07-20 폴리싱] 첫인사 정규화 — 서비스 렌더 계약: first_greeting=순수 대사,
        // 장면 묘사는 intro_narration(SYSTEM 나레이션 채널). 프롬프트 미준수 대비 이중 방어.
        GreetingParts greeting = normalizeGreeting(p.firstGreeting());
        String dialogue = greeting.dialogue() != null ? greeting.dialogue()
            : "……안녕하세요. 만나서 반가워요.";
        String intro = firstNonBlank(p.introNarration(), greeting.extractedNarration());

        // [리뷰 픽스] 신상 4종은 VARCHAR(30/200) 컬럼에 저장된다 — LLM 과다 산출·배열 bullet 펼침이
        // 최종 바인딩(전 스테이지 완주 후)에서 varchar 초과로 잡 전체를 죽이지 않도록 정규화·절삭.
        StructuredConcept.CharacterProfile fixed = new StructuredConcept.CharacterProfile(
            effectiveName, p.tagline(), p.age(), p.role(), p.personality(), p.tone(),
            p.appearance(), p.clothing(), p.backstory(), p.coreValues(), p.flaws(),
            p.speechQuirks(), dialogue, intro,
            normalizeShort(p.height(), 30), normalizeShort(p.likes(), 200),
            normalizeShort(p.dislikes(), 200), normalizeShort(p.hobby(), 200),
            normalizeShort(p.profileQuote(), 200));

        List<String> personaTags = c.personaTags() == null ? List.of() : c.personaTags();
        List<String> sceneTags = c.sceneTags() == null ? List.of() : c.sceneTags();
        return new StructuredConcept(c.appearanceTags(), personaTags, sceneTags, effectiveBg, fixed,
            c.moderation(), c.basePose(), c.emotionPrompts());
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  [2026-07-21] 감정 14종 동적 프롬프트 파생 (감정 스테이지 진입 시 1콜)
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    private static final String EMOTION_PROMPT_SYSTEM = """
        너는 캐릭터 감정 연출 엔진이다. 캐릭터 프로필을 받아, 감정 14종 각각에 대해 이 캐릭터답게
        연출된 표정(expression)과 자세(pose)를 영문으로 산출한다. 아래 JSON 스키마로만 응답한다.
        규칙:
        - expression: 얼굴 표정 묘사 (영문, 20단어 이내) — 눈·눈썹·입 중심. 감정이 명확히 드러나야 한다.
        - pose: 상반신 제스처만 (영문, 20단어 이내) — 팔·손·어깨·고개 각도로 한정.
          절대 금지: 카메라 쪽으로 기울이기/멀어지기, 카메라 거리·앵글·프레이밍 변경, 전신 자세 전환,
          앉기/눕기/돌아서기, 소품 사용. (구도 변화는 캐릭터 비율을 깨뜨린다)
        - HEATED·FLIRTATIOUS 는 SFW 라인 유지 — 노출·성적 행위 묘사 금지, 분위기 표현만.
        - 캐릭터의 성격·말투·역할이 표정과 제스처에 배어나게 하되, 감정 자체는 명확히 유지한다.
        - 14종 키를 전부 포함: JOY, SAD, ANGRY, SHY, SURPRISE, PANIC, RELAX, DISGUST,
          FRIGHTENED, FLIRTATIOUS, HEATED, DUMBFOUNDED, SULKING, PLEADING
        출력 스키마:
        {"emotions":{"JOY":{"expression":"...","pose":"..."}, ...}}
        출력은 JSON 외 어떤 텍스트도 금지.
        """;

    /**
     * 캐릭터별 감정 연출 프롬프트 일괄 산출 — 감정 스테이지 진입 시 워커가 1회 호출해 잡에 저장한다
     * (리롤 재현성). 실패는 호출측이 상수 폴백으로 흡수하므로 예외를 그대로 던진다.
     */
    public Map<String, StructuredConcept.EmotionPromptOverride> deriveEmotionPrompts(StructuredConcept concept) {
        StructuredConcept.CharacterProfile p = concept.character();
        StringBuilder sb = new StringBuilder("[캐릭터 프로필]\n");
        sb.append("- 이름: ").append(p.name()).append('\n');
        if (p.role() != null) sb.append("- 역할: ").append(p.role()).append('\n');
        if (p.personality() != null) sb.append("- 성격: ").append(p.personality()).append('\n');
        if (p.tone() != null) sb.append("- 말투: ").append(p.tone()).append('\n');
        if (concept.personaTags() != null && !concept.personaTags().isEmpty()) {
            sb.append("- 무드 태그: ").append(String.join(", ", concept.personaTags())).append('\n');
        }

        String raw;
        try {
            raw = openRouterClient.completeJson(
                effectiveModel(), EMOTION_PROMPT_SYSTEM, sb.toString(), 4096, 0.7);
        } catch (Exception e) {
            log.warn("[UGC-EMOPROMPT] LLM 호출 실패: {}", e.getMessage());
            throw new ExternalApiException("감정 연출 산출 실패");
        }
        try {
            String json = LlmOutputParser.extractJson(raw);
            EmotionPromptsEnvelope parsed = objectMapper.readValue(json, EmotionPromptsEnvelope.class);
            Map<String, StructuredConcept.EmotionPromptOverride> result = new java.util.LinkedHashMap<>();
            if (parsed.emotions() != null) {
                parsed.emotions().forEach((k, v) -> {
                    // 유효 감정 키 + 두 슬롯 모두 있는 항목만 채택 — 나머지는 서버 상수 폴백
                    if (v == null || v.expression() == null || v.expression().isBlank()
                        || v.pose() == null || v.pose().isBlank()) return;
                    try {
                        com.spring.aichat.domain.enums.EmotionTag.valueOf(k.trim().toUpperCase(Locale.ROOT));
                        result.put(k.trim().toUpperCase(Locale.ROOT), v);
                    } catch (IllegalArgumentException ignored) {
                        // 알 수 없는 키 스킵
                    }
                });
            }
            if (result.isEmpty()) {
                throw new ExternalApiException("감정 연출 산출 실패 — 유효 항목 없음");
            }
            return result;
        } catch (ExternalApiException e) {
            throw e;
        } catch (Exception e) {
            log.warn("[UGC-EMOPROMPT] 산출 파싱 실패: {}", e.getMessage());
            throw new ExternalApiException("감정 연출 산출 실패");
        }
    }

    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    record EmotionPromptsEnvelope(Map<String, StructuredConcept.EmotionPromptOverride> emotions) {}

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  [2026-07-21] 외형 전용 경량 재구조화 (황금샷 리롤 외형 수정 — GACHA_WAIT 전용)
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    private static final String APPEARANCE_SYSTEM_PROMPT = """
        너는 캐릭터 외형 재구조화 엔진이다. 기존 캐릭터의 컨셉과 새 [외형 지정]을 받아,
        외형 관련 산출만 다시 만든다. 캐릭터의 성격·서사는 절대 건드리지 않는다.
        규칙:
        - appearance_tags: Danbooru 태그 관례(영문 소문자, 개별 태그 문자열 배열), 외형만 40~60개.
          [외형 지정]의 특징을 빠짐없이 최우선 반영. 지정되지 않은 부분은 기존 외형 태그를 유지·계승한다.
        - scene_tags: 새 외형·분위기에 어울리는 황금샷 연출 10~20개.
        - bg_color: 새 머리색(우선)·의상색과 명도 대비가 큰 저채도 1개 —
          ["light gray","pale blue gray","muted teal","soft beige","dusty lavender"] 중 선택.
        - appearance: 새 외형 한국어 서술 / clothing: 새 복장 한국어 서술.
        - moderation: 명백한 미성년 신체 시그널이 있을 때만 minor_signal=true.
        출력 스키마:
        {"appearance_tags":[...], "scene_tags":[...], "bg_color":"...",
         "appearance":"...", "clothing":"...", "moderation":{"minor_signal":false,"reason":""}}
        출력은 JSON 외 어떤 텍스트도 금지.
        """;

    /**
     * 황금샷 리롤 외형 수정 — 새 외형 힌트로 외형 태그·씬·배경색·외형 한국어 서술만 재산출해
     * 기존 컨셉에 병합한다 (페르소나·서사·유저 편집분은 그대로 보존).
     */
    public StructuredConcept restructureAppearance(String rawConcept, StructuredConcept current,
                                                   String appearanceHintsBlock) {
        String userMessage = "[원래 컨셉 서술]:\n" + rawConcept
            + "\n\n[기존 외형 태그]: " + String.join(", ", current.appearanceTags())
            + "\n\n[외형 지정 — 변경 요청, 최우선 반영]\n" + appearanceHintsBlock;

        String raw;
        try {
            raw = openRouterClient.completeJson(
                effectiveModel(), APPEARANCE_SYSTEM_PROMPT, userMessage, 8192, 0.7);
        } catch (Exception e) {
            log.error("[UGC-APPEARANCE] LLM 호출 실패: {}", e.getMessage());
            throw new ExternalApiException("외형 재구조화 실패 — 잠시 후 다시 시도해 주세요.");
        }

        AppearanceRestructure parsed;
        try {
            parsed = objectMapper.readValue(LlmOutputParser.extractJson(raw), AppearanceRestructure.class);
        } catch (Exception e) {
            log.error("[UGC-APPEARANCE] 산출 파싱 실패: {}", e.getMessage());
            throw new ExternalApiException("외형 재구조화 실패 — 잠시 후 다시 시도해 주세요.");
        }
        if (parsed.appearanceTags() == null || parsed.appearanceTags().isEmpty()) {
            throw new ExternalApiException("외형 재구조화 실패 — 잠시 후 다시 시도해 주세요.");
        }

        String bg = parsed.bgColor() == null ? null : parsed.bgColor().toLowerCase(Locale.ROOT).trim();
        String effectiveBg = (bg != null && BG_COLOR_PALETTE.contains(bg)) ? bg : BG_COLOR_FALLBACK;
        List<String> sceneTags = parsed.sceneTags() != null && !parsed.sceneTags().isEmpty()
            ? parsed.sceneTags() : current.sceneTags();

        StructuredConcept.CharacterProfile p = current.character();
        StructuredConcept.CharacterProfile merged = new StructuredConcept.CharacterProfile(
            p.name(), p.tagline(), p.age(), p.role(), p.personality(), p.tone(),
            firstNonBlank(parsed.appearance(), p.appearance()),
            firstNonBlank(parsed.clothing(), p.clothing()),
            p.backstory(), p.coreValues(), p.flaws(), p.speechQuirks(),
            p.firstGreeting(), p.introNarration(),
            p.height(), p.likes(), p.dislikes(), p.hobby(), p.profileQuote());

        return new StructuredConcept(parsed.appearanceTags(), current.personaTags(), sceneTags,
            effectiveBg, merged, parsed.moderation(), current.basePose(), current.emotionPrompts());
    }

    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    @com.fasterxml.jackson.databind.annotation.JsonNaming(
        com.fasterxml.jackson.databind.PropertyNamingStrategies.SnakeCaseStrategy.class)
    record AppearanceRestructure(
        List<String> appearanceTags,
        List<String> sceneTags,
        String bgColor,
        @com.fasterxml.jackson.databind.annotation.JsonDeserialize(
            using = com.spring.aichat.dto.ugc.FlexibleStringDeserializer.class)
        String appearance,
        @com.fasterxml.jackson.databind.annotation.JsonDeserialize(
            using = com.spring.aichat.dto.ugc.FlexibleStringDeserializer.class)
        String clothing,
        StructuredConcept.Moderation moderation
    ) {}

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  첫인사 정규화 (패키지 가시성 — 테스트용)
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    record GreetingParts(String dialogue, String extractedNarration) {}

    /**
     * 혼합 포맷 첫인사를 [순수 대사]와 [괄호 지문 나레이션]으로 분리한다.
     * 규칙: {@code *...*} 마크다운 지문 제거 · 괄호로만 이루어진 줄 → 나레이션 ·
     * 줄 단위 감싸는 따옴표 제거 · 잔여 줄은 공백 결합.
     */
    static GreetingParts normalizeGreeting(String raw) {
        if (raw == null || raw.isBlank()) return new GreetingParts(null, null);

        String s = raw.replaceAll("\\*[^*\\n]*\\*", " "); // *지문* 제거
        StringBuilder dialogue = new StringBuilder();
        StringBuilder narration = new StringBuilder();
        for (String line : s.split("\\R")) {
            String t = line.trim();
            if (t.isEmpty()) continue;
            if (t.startsWith("(") && t.endsWith(")")) {
                if (!narration.isEmpty()) narration.append(" ");
                narration.append(t, 1, t.length() - 1);
                continue;
            }
            t = stripWrappingQuotes(t);
            if (t.isEmpty()) continue;
            if (!dialogue.isEmpty()) dialogue.append(" ");
            dialogue.append(t);
        }
        String d = dialogue.toString().replaceAll("\\s{2,}", " ").trim();
        String n = narration.toString().replaceAll("\\s{2,}", " ").trim();
        return new GreetingParts(d.isBlank() ? null : d, n.isBlank() ? null : n);
    }

    private static String stripWrappingQuotes(String t) {
        if (t.length() >= 2) {
            char a = t.charAt(0);
            char b = t.charAt(t.length() - 1);
            if ((a == '"' && b == '"') || (a == '“' && b == '”') || (a == '\'' && b == '\'')) {
                return t.substring(1, t.length() - 1).trim();
            }
        }
        return t;
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) return a;
        return (b != null && !b.isBlank()) ? b : null;
    }

    /**
     * [리뷰 픽스] 신상 필드 정규화 — FlexibleStringDeserializer의 "- 항목" 개행 bullet을
     * 콤마 구분으로 되돌리고(프로필 노출 계약), 컬럼 길이에 맞춰 절삭한다.
     */
    static String normalizeShort(String s, int max) {
        if (s == null || s.isBlank()) return null;
        String flat = s.replaceAll("\\R+\\s*-\\s*", ", ")   // 개행 bullet → 콤마
            .replaceAll("^\\s*-\\s*", "")                    // 선두 bullet 제거
            .replaceAll("\\R+", ", ")                        // 잔여 개행 → 콤마
            .replaceAll("\\s{2,}", " ")
            .strip();
        if (flat.length() > max) {
            int cut = max;
            if (Character.isHighSurrogate(flat.charAt(cut - 1))) cut--;
            flat = flat.substring(0, cut).strip();
        }
        return flat.isBlank() ? null : flat;
    }
}
