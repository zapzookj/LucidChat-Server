package com.spring.aichat.service.ugc;

import com.spring.aichat.dto.ugc.StructuredConcept;
import com.spring.aichat.dto.ugc.StructuredWorld;
import com.spring.aichat.exception.ContentModerationException;
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
 * (VLM 자동 프리필터는 PoC-5 대기 — v1은 플래그 자리만 마련, 미구현.)
 */
@Slf4j
@Service
public class UgcModerationService {

    /** 유저 노출용 일반 안내 (기준 미노출 원칙). */
    static final String BLOCK_MESSAGE = "이 컨셉으로는 캐릭터를 만들 수 없어요. 내용을 수정해 다시 시도해 주세요.";

    /** 캐릭터 최소 연령 — 성인인증(만 19세) 서비스 기준과 정합. */
    static final int MIN_CHARACTER_AGE = 19;

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
     * [세계관 빌더] W0 산출 검증 — LLM minor_signal 판정 (월드는 연령 축 없음).
     * 원문 게이트는 {@link #assertRawConceptAllowed} 공용.
     */
    public void assertStructuredWorldAllowed(StructuredWorld world) {
        if (world.moderation() != null && world.moderation().minorSignal()) {
            log.info("[UGC-MOD] LLM minor_signal block (world): reason={}",
                world.moderation().reason());
            throw blockedWorld("MINOR_SIGNAL");
        }
    }

    /**
     * Stage 0 산출 검증 — 연령 하한 + LLM minor_signal 판정.
     */
    public void assertStructuredConceptAllowed(StructuredConcept concept) {
        if (concept.moderation() != null && concept.moderation().minorSignal()) {
            log.info("[UGC-MOD] LLM minor_signal block: reason={}",
                concept.moderation().reason());
            throw blocked("MINOR_SIGNAL");
        }
        Integer age = concept.character() != null ? concept.character().age() : null;
        if (age != null && age < MIN_CHARACTER_AGE) {
            log.info("[UGC-MOD] age block: age={}", age);
            throw blocked("UNDERAGE_CHARACTER");
        }
    }

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
