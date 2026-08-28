package com.spring.aichat.service.ugc;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.spring.aichat.config.OpenAiProperties;
import com.spring.aichat.config.UgcPipelineProperties;
import com.spring.aichat.dto.ugc.StructuredConcept;
import com.spring.aichat.dto.ugc.StructuredWorld;
import com.spring.aichat.exception.ContentModerationException;
import com.spring.aichat.external.OpenRouterClient;
import com.spring.aichat.service.moderation.ModerationEventService;
import com.spring.aichat.service.util.LlmOutputParser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * [UGC v1] 좁은 생성 게이트 (모더레이션 3층 중 ①).
 *
 * <p>정책: <b>명백한 미성년 시그널만</b> 하드 차단. 과차단보다 좁게 —
 * 정상 유저 99%는 이 게이트의 존재를 모르게 한다. 나머지 통제는
 * ② 승인 큐(공개/Secret 검수), ③ 신고 연계가 담당.
 *
 * <p>차단 시 유저 노출 메시지는 일반 안내뿐 — 판정 기준·상세 사유는 정책상 미노출.
 *
 * <p>[2026-07-30 오탐 방어] 구조화 LLM의 minor_signal 스냅 판정은 즉시 차단하지 않는다 —
 * 엄격 기준 전용 프롬프트의 <b>2차 확정 판정</b>을 통과한 경우에만 차단한다(장문 성장담
 * 세계관이 '소년·어린 나이 언급'만으로 반려되던 실측 사고의 구조적 방어). 1차 플래그가
 * 뒤집힌 케이스는 moderation_events(MINOR_SIGNAL_OVERTURNED)로 적재해 오탐률을 측정한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UgcModerationService {

    private final OpenRouterClient openRouterClient;
    private final ObjectMapper objectMapper;
    private final ModerationEventService moderationEventService;
    private final UgcPipelineProperties ugcProps;
    private final OpenAiProperties openAiProps;

    /** 유저 노출용 일반 안내 (기준 미노출 원칙). */
    static final String BLOCK_MESSAGE = "이 컨셉으로는 캐릭터를 만들 수 없어요. 내용을 수정해 다시 시도해 주세요.";

    /**
     * 캐릭터 최소 연령 — 성인인증(만 19세) 서비스 기준과 정합.
     *
     * <p>[안건 9 · docs/19_assets/decisions_confirmed.md §C] 시크릿 자격 게이트 3곳
     * ({@code SecretModeService.isCharacterSecretEligible} · {@code AdminUgcReviewService.review} ·
     * {@code UgcCharacterService.requestSecretReview})이 같은 기준을 봐야 하므로 public으로 승격.
     * 상수를 각 패키지에 복제하면 한쪽만 바뀌는 순간 게이트가 갈린다.
     */
    public static final int MIN_CHARACTER_AGE = 19;

    /**
     * 하드 키워드 — 의도적으로 소수만 유지 (좁은 게이트 원칙).
     * 영문은 단어 경계 매칭(예: 'childhood friend'는 통과), 한국어는 포함 매칭.
     */
    private static final List<Pattern> HARD_BLOCK_EN = List.of(
        Pattern.compile("\\bloli(ta)?\\b"),
        Pattern.compile("\\bshota\\b"),
        Pattern.compile("\\bchild\\b"),
        Pattern.compile("\\bpreteen\\b"),
        Pattern.compile("\\btoddler\\b")
    );

    private static final List<String> HARD_BLOCK_KO = List.of(
        "초등학생", "유치원생", "중학생", "미성년"
    );

    /**
     * Stage 0 진입 전 — 원문 하드 키워드 게이트. 에너지 차감 전에 호출한다.
     */
    public void assertRawConceptAllowed(String rawInput) {
        if (rawInput == null) return;
        String lower = rawInput.toLowerCase(Locale.ROOT);

        for (Pattern p : HARD_BLOCK_EN) {
            if (p.matcher(lower).find()) {
                log.info("[UGC-MOD] hard keyword block (en): pattern={}", p.pattern());
                throw blocked("HARD_KEYWORD");
            }
        }
        for (String kw : HARD_BLOCK_KO) {
            if (lower.contains(kw)) {
                log.info("[UGC-MOD] hard keyword block (ko): keyword={}", kw);
                throw blocked("HARD_KEYWORD");
            }
        }
    }

    /**
     * [세계관 빌더] W0 산출 검증 — LLM minor_signal 1차 플래그 + 2차 확정 판정.
     * 원문 게이트는 {@link #assertRawConceptAllowed} 공용.
     *
     * @param rawConcept 유저 원문(2차 판정 대상 — 구조화 산출이 아니라 원문을 재검증)
     * @param userId     이벤트 적재용
     */
    public void assertStructuredWorldAllowed(StructuredWorld world, String rawConcept, Long userId) {
        if (world.moderation() != null && world.moderation().minorSignal()) {
            String reason = world.moderation().reason();
            if (!confirmMinorSignal(rawConcept, reason, userId, "UGC_WORLD")) {
                log.info("[UGC-MOD] minor_signal 1차 플래그 → 2차 미확정 통과 (world): 1차 사유={}", reason);
                return;
            }
            log.info("[UGC-MOD] LLM minor_signal 확정 차단 (world): reason={}", reason);
            throw blockedWorld("MINOR_SIGNAL");
        }
    }

    /**
     * Stage 0 산출 검증 — 연령 하한 + LLM minor_signal 1차 플래그 + 2차 확정 판정.
     */
    public void assertStructuredConceptAllowed(StructuredConcept concept, String rawConcept, Long userId) {
        if (concept.moderation() != null && concept.moderation().minorSignal()) {
            String reason = concept.moderation().reason();
            if (confirmMinorSignal(rawConcept, reason, userId, "UGC_CHARACTER")) {
                log.info("[UGC-MOD] LLM minor_signal 확정 차단: reason={}", reason);
                throw blocked("MINOR_SIGNAL");
            }
            log.info("[UGC-MOD] minor_signal 1차 플래그 → 2차 미확정 통과: 1차 사유={}", reason);
        }
        Integer age = concept.character() != null ? concept.character().age() : null;
        if (age != null && age < MIN_CHARACTER_AGE) {
            log.info("[UGC-MOD] age block: age={}", age);
            throw blocked("UNDERAGE_CHARACTER");
        }
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  [2026-07-30] 2차 확정 판정 — 오탐 방어
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    private static final String CONFIRM_SYSTEM_PROMPT = """
        너는 콘텐츠 안전 확정 판정 엔진이다. 1차 자동 검수가 플래그한 텍스트를 재검증한다.
        확정 기준 — 오직 이것만 판정한다: 텍스트가 **미성년자를 성적·연애적 대상으로 삼는가?**
        - 미성년 인물이 서사에 등장하는 것 자체(성장담·수련기·과거사·가족사·전쟁·비극)는 위반이 아니다.
        - 성인 캐릭터/인물의 어린 시절 서술은 위반이 아니다.
        - 위반은 미성년 상태의 인물에 대한 성적 묘사, 성적 관계, 연애적 욕망의 대상화가
          명시적으로 존재할 때만이다. 모호하면 confirmed=false.
        출력 스키마: {"confirmed":false,"reason":"판정 근거 1~2문장 (한국어)"}
        출력은 JSON 외 어떤 텍스트도 금지.
        """;

    private static final int CONFIRM_TEXT_MAX = 8000;

    /**
     * 2차 확정 판정 — 유저 원문을 엄격 기준으로 재검증.
     * true=확정(차단) / false=미확정(통과 — 공개 노출은 어차피 ② 승인 큐가 심사).
     * LLM 호출·파싱 실패 시 <b>보수적으로 true</b>(1차 판정 유지 = 기존 동작).
     * 확정/뒤집힘 모두 moderation_events에 적재 — 오탐률 측정 기반(어드민 모더레이션 큐 가시화).
     */
    private boolean confirmMinorSignal(String rawText, String firstPassReason, Long userId, String source) {
        long start = System.currentTimeMillis();
        boolean confirmed;
        String confirmReason;
        try {
            String text = rawText == null ? "" : rawText;
            if (text.length() > CONFIRM_TEXT_MAX) text = text.substring(0, CONFIRM_TEXT_MAX);
            String model = ugcProps.stage0ModelOrNull() != null ? ugcProps.stage0ModelOrNull() : openAiProps.model();
            String raw = openRouterClient.completeJson(model, CONFIRM_SYSTEM_PROMPT,
                "[1차 플래그 사유]: " + (firstPassReason == null ? "(없음)" : firstPassReason)
                    + "\n\n[검증 대상 텍스트]:\n" + text, 1024, 0.0);
            ConfirmVerdict verdict = objectMapper.readValue(LlmOutputParser.extractJson(raw), ConfirmVerdict.class);
            confirmed = Boolean.TRUE.equals(verdict.confirmed());
            confirmReason = verdict.reason();
        } catch (Exception e) {
            log.warn("[UGC-MOD] 2차 확정 판정 실패 — 보수적으로 1차 판정 유지: {}", e.getMessage());
            confirmed = true;
            confirmReason = "2차 판정 실패(" + e.getMessage() + ") — 1차 유지";
        }
        recordEvent(userId, source, confirmed ? "MINOR_SIGNAL_CONFIRMED" : "MINOR_SIGNAL_OVERTURNED",
            System.currentTimeMillis() - start,
            "[1차] " + firstPassReason + " / [2차] " + confirmReason);
        return confirmed;
    }

    /** 이벤트 적재 — best-effort (측정 실패가 게이트를 깨지 않게). */
    private void recordEvent(Long userId, String source, String category, long latencyMs, String detail) {
        try {
            moderationEventService.recordModeration(userId, null, source, 4, category, latencyMs, detail);
        } catch (Exception e) {
            log.warn("[UGC-MOD] 이벤트 적재 실패 (non-blocking): {}", e.getMessage());
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ConfirmVerdict(Boolean confirmed, String reason) {}

    private ContentModerationException blocked(String category) {
        // blockedAtStep=0 — Stage 0 게이트 (에너지 차감 전이므로 유저 손실 없음)
        return new ContentModerationException(BLOCK_MESSAGE, category, 0);
    }

    /** 유저 노출용 일반 안내 — 세계관 문구 (기준 미노출 원칙 동일). */
    static final String WORLD_BLOCK_MESSAGE = "이 컨셉으로는 세계관을 만들 수 없어요. 내용을 수정해 다시 시도해 주세요.";

    private ContentModerationException blockedWorld(String category) {
        return new ContentModerationException(WORLD_BLOCK_MESSAGE, category, 0);
    }
}
