package com.spring.aichat.security;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.ahocorasick.trie.Emit;
import org.ahocorasick.trie.Trie;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * [Phase 5] Aho-Corasick 기반 인메모리 키워드 필터
 *
 * [설계 철학]
 * "법적으로 문제되는 노골적 표현만 차단, 나머지는 LLM의 시스템 프롬프트가 방어"
 *
 * 이 필터는 노말 모드 전용이며, 시크릿 모드에서는 바이패스된다.
 * 수위 설정 기준: 청소년보호법 유해매체물 기준 + 방통심의위 심의 가이드라인
 *   → 직접적인 성행위 묘사, 아동 대상 성적 표현, 심각한 혐오 발언만 차단
 *   → 약한 욕설, 가벼운 로맨스 표현, 신체 부위 단순 언급은 허용
 *
 * [성능]
 * - Aho-Corasick: O(N) where N = 입력 텍스트 길이 (키워드 수와 무관)
 * - 초기화: ~5ms (키워드 수백 개 기준)
 * - 조회: < 0.1ms per message
 * - 메모리: ~50KB
 *
 * [의존성]
 * implementation 'org.ahocorasick:ahocorasick:0.6.3'
 */
@Component
@Slf4j
public class KeywordFilter {

    private Trie sexualTrie;
    private Trie hateTrie;
    private Trie illegalTrie;

    /**
     * 정규화 전처리 패턴
     * 공격자가 "씹ㅂ알" 처럼 초성 분리하거나 "시.발" 처럼 구두점 삽입하는 우회 방어
     */
    private static final Pattern NORMALIZE_PATTERN = Pattern.compile(
        "[\\s.·\\-_~!@#$%^&*()\\[\\]{}|;:'\",<>?/\\\\`ㅤ]+"
    );

    @PostConstruct
    public void init() {
        long start = System.currentTimeMillis();

        sexualTrie = buildTrie(SEXUAL_KEYWORDS);
        hateTrie = buildTrie(HATE_KEYWORDS);
        illegalTrie = buildTrie(ILLEGAL_KEYWORDS);

        log.info("[KEYWORD_FILTER] Initialized in {}ms | sexual={}, hate={}, illegal={}",
            System.currentTimeMillis() - start,
            SEXUAL_KEYWORDS.size(), HATE_KEYWORDS.size(), ILLEGAL_KEYWORDS.size());
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  Public API
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /**
     * 메시지 필터링 검사
     *
     * @return FilterResult (통과 or 차단 사유)
     */
    public FilterResult check(String message) {
        if (message == null || message.isBlank()) return FilterResult.PASS;

        String normalized = normalize(message);

        // 1. 불법 콘텐츠 (최우선 — 무조건 차단)
        Collection<Emit> illegalHits = illegalTrie.parseText(normalized);
        if (!illegalHits.isEmpty()) {
            String matched = illegalHits.iterator().next().getKeyword();
            log.warn("[KEYWORD_FILTER] ILLEGAL hit: '{}'", matched);
            return new FilterResult(false, FilterCategory.ILLEGAL,
                "부적절한 내용이 포함되어 있습니다.", matched);
        }

        // 2. 노골적 성적 표현
        Collection<Emit> sexualHits = sexualTrie.parseText(normalized);
        if (!sexualHits.isEmpty()) {
            String matched = sexualHits.iterator().next().getKeyword();
            log.info("[KEYWORD_FILTER] SEXUAL hit: '{}'", matched);
            return new FilterResult(false, FilterCategory.SEXUAL,
                "해당 표현은 시크릿 모드에서만 사용할 수 있습니다.", matched);
        }

        // 3. 심각한 혐오 표현
        Collection<Emit> hateHits = hateTrie.parseText(normalized);
        if (!hateHits.isEmpty()) {
            String matched = hateHits.iterator().next().getKeyword();
            log.info("[KEYWORD_FILTER] HATE hit: '{}'", matched);
            return new FilterResult(false, FilterCategory.HATE,
                "부적절한 표현이 포함되어 있습니다.", matched);
        }

        return FilterResult.PASS;
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  내부 유틸
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    private Trie buildTrie(List<String> keywords) {
        Trie.TrieBuilder builder = Trie.builder()
            .ignoreCase()
            .ignoreOverlaps();

        for (String kw : keywords) {
            builder.addKeyword(kw);
        }

        return builder.build();
    }

    /**
     * 입력 정규화: 우회 시도 방어
     * "시 발" → "시발", "씹.발" → "씹발", "ㅅㅂ" → "ㅅㅂ" (초성은 그대로 유지)
     */
    private String normalize(String input) {
        return NORMALIZE_PATTERN.matcher(input).replaceAll("").toLowerCase();
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  Result DTO
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    public enum FilterCategory { NONE, SEXUAL, HATE, ILLEGAL }

    public record FilterResult(
        boolean passed,
        FilterCategory category,
        String userMessage,   // 유저에게 보여줄 메시지
        String matchedKeyword // 내부 로깅용
    ) {
        public static final FilterResult PASS =
            new FilterResult(true, FilterCategory.NONE, null, null);
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  금지어 사전
    //
    //  [수위 기준]
    //  ✅ 차단: 직접적 성행위 묘사, 성기/체액 직접 지칭,
    //           아동 성적 표현, 살인/자살 교사, 심각한 인종/장애 혐오
    //  ❌ 비차단: 약한 욕설(씨발, 개새끼 등), 신체 부위 단순 언급,
    //            가벼운 로맨스(키스, 포옹), 감정적 분노 표현
    //
    //  씨발/개새끼 등 일반 욕설을 차단하지 않는 이유:
    //  1. 한국 게임/서비스의 일반적 기준에서 "15세 이용가" 수준
    //  2. 이를 차단하면 게임적 자유도가 심각하게 저하 → 유저 이탈
    //  3. 이런 표현은 LLM의 시스템 프롬프트 레벨에서 캐릭터가 반응 조절
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /** 노골적 성적 표현 (성행위/성기/체액 직접 묘사) */
    private static final List<String> SEXUAL_KEYWORDS = List.of(
        // 성행위 직접 묘사
        "삽입해", "삽입하", "박아줘", "박아줄", "박아달", "따먹", "따묵",
        "성교", "성관계해", "관계하자", "섹스하자", "섹스해줘",
        "자위해", "자위하", "딸쳐", "딸치",
        "정액", "사정해", "사정하", "싸줘", "쌀게", "안에싸", "안에쏴",
        "조교해", "조교하", "능욕", "윤간", "강간",
        "페니스", "자지",
        "보지", "음부",
        "야동", "포르노", "에로동영상",
        "펠라", "blowjob", "handjob", "creampie",
        "fuck me", "fuck you", "sex with",

        // 성적 도구/행위 구체적 묘사
        "딜도", "바이브레이터", "오나홀",
        "속박플레이", "sm플레이",
        "페티시즘",

        // 노출 직접 요구
        "벗어봐", "벗어줘", "옷벗어", "알몸", "전라",
        "가슴보여", "가슴만져", "젖꼭지",
        "엉덩이만져", "엉덩이보여"
    );

    /** 심각한 혐오/차별 발언 (법적 리스크) */
    private static final List<String> HATE_KEYWORDS = List.of(
        // 장애 혐오
        "장애인죽", "장애인처",
        "병신새끼죽", "지체장애놈",

        // 인종/민족 혐오 (법적 리스크)
        "조선족죽", "쪽바리죽", "깜둥이죽",
        "니그로", "nigger",

        // 특정 집단 살인/폭력 교사
        "다죽여", "다죽이", "몰살",
        "학살하", "테러하"
    );

    /** 불법 콘텐츠 (무조건 차단 — 아동 성착취, 자살 교사 등) */
    private static final List<String> ILLEGAL_KEYWORDS = List.of(
        // 아동 성착취 (아동청소년성보호법)
        "초등학생섹", "중학생섹", "미성년섹",
        "어린이섹스", "아동포르노", "아동야동",
        "아청법", "로리야동", "쇼타야동",
        "lolicon", "shotacon", "child porn",
        "어린이알몸", "아동알몸",
        "어린이나체", "아동나체",

        // 자살/자해 교사
        "자살방법", "자살하는법", "목매는법",
        "약물자살", "투신자살", "동반자살",
        "손목긋는법", "청산가리구",
        "번개자살", "동반자살모집",

        // 마약 거래
        "대마구매", "필로폰구매", "마약판매",
        "대마판매", "엑스터시구매"
    );
}