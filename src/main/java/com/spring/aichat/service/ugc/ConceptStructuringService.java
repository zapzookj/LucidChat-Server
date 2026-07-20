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
          · first_greeting: 유저를 처음 만났을 때 캐릭터가 입으로 말하는 **첫인사 대사만**.
            순수 발화 텍스트 1~4문장. 마크다운(*), 괄호 지문, 감싸는 따옴표, '이름:' 접두, 줄바꿈 구획 금지.
          · intro_narration: 유저가 캐릭터를 처음 만나는 장면 묘사 2~3문장 (관찰자 시점, "당신은…" 톤).
            상황·행동 묘사는 전부 여기에 — first_greeting에 넣지 않는다.
          · 주의: character의 모든 값은 JSON 배열이 아니라 **단일 문자열**이다.
            bullet은 문자열 안에서 "- 항목" 형태로 개행 구분한다.
        - moderation: 명백한 미성년 신체·설정 시그널이 있을 때만 minor_signal=true (모호하면 false).
        출력 스키마:
        {"appearance_tags":[...], "persona_tags":[...], "scene_tags":[...], "bg_color":"...",
         "character":{"name":"...","tagline":"...","age":23,"role":"...","personality":"...",
          "tone":"...","appearance":"...","clothing":"...","backstory":"...",
          "core_values":"...","flaws":"...","speech_quirks":"...","first_greeting":"...",
          "intro_narration":"..."},
         "moderation":{"minor_signal":false,"reason":""}}
        출력은 JSON 외 어떤 텍스트도 금지.
        """;

    private final OpenRouterClient openRouterClient;
    private final OpenAiProperties openAiProps;
    private final ObjectMapper objectMapper;

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
                openAiProps.model(), SYSTEM_PROMPT, userMessage, 8192, 0.7);
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

        StructuredConcept.CharacterProfile fixed = new StructuredConcept.CharacterProfile(
            effectiveName, p.tagline(), p.age(), p.role(), p.personality(), p.tone(),
            p.appearance(), p.clothing(), p.backstory(), p.coreValues(), p.flaws(),
            p.speechQuirks(), dialogue, intro);

        List<String> personaTags = c.personaTags() == null ? List.of() : c.personaTags();
        List<String> sceneTags = c.sceneTags() == null ? List.of() : c.sceneTags();
        return new StructuredConcept(c.appearanceTags(), personaTags, sceneTags, effectiveBg, fixed, c.moderation());
    }

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
}
