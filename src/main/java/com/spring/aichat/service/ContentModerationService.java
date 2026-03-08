package com.spring.aichat.service;

import com.spring.aichat.external.OpenAiModerationClient;
import com.spring.aichat.external.OpenAiModerationClient.ModerationResult;
import com.spring.aichat.security.KeywordFilter;
import com.spring.aichat.security.KeywordFilter.FilterResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * [Phase 5] 콘텐츠 모더레이션 서비스 — 2-Step 유해 콘텐츠 필터
 *
 * [파이프라인]
 *
 *   유저 메시지
 *       │
 *       ▼
 *   ┌─────────────────────────────┐
 *   │ Step 1: Aho-Corasick 키워드 │  비용: 0원  지연: <0.1ms
 *   │ - 노골적 성적 표현           │
 *   │ - 심각한 혐오 발언           │
 *   │ - 불법 콘텐츠 (아동, 약물)   │
 *   └──────────┬──────────────────┘
 *              │ 통과
 *              ▼
 *   ┌─────────────────────────────┐
 *   │ Step 2: OpenAI Moderation   │  비용: 0원  지연: ~200ms
 *   │ - 자체 임계값 기반 판정      │
 *   │ - 문맥 기반 유해성 탐지      │
 *   │ - sexual/minors 무관용       │
 *   └──────────┬──────────────────┘
 *              │ 통과
 *              ▼
 *        LLM 호출 진행
 *
 * [적용 조건]
 * - 노말 모드에서만 작동 (isSecretMode == false)
 * - 시크릿 모드에서는 완전 바이패스
 *
 * [장애 대응]
 * - Step 1 (인메모리): 장애 가능성 없음
 * - Step 2 (외부 API): 장애 시 PASS 반환 (가용성 > 필터링)
 *   → Step 1이 핵심 방어선, Step 2는 보조 방어선
 *
 * [사용처]
 * - ChatService.sendMessage() — TX-1 진입 전
 * - ChatService.generateResponseForSystemEvent() — TX-1 진입 전 (선택적)
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class ContentModerationService {

    private final KeywordFilter keywordFilter;
    private final OpenAiModerationClient moderationClient;

    /**
     * 2-Step 콘텐츠 필터링 실행
     *
     * @param message 유저 입력 메시지
     * @param isSecretMode 시크릿 모드 여부
     * @return ModerationVerdict (통과 or 차단)
     */
    public ModerationVerdict moderate(String message, boolean isSecretMode) {
        // 시크릿 모드 → 완전 바이패스
        if (isSecretMode) {
            return ModerationVerdict.PASS;
        }

        if (message == null || message.isBlank()) {
            return ModerationVerdict.PASS;
        }

        long totalStart = System.currentTimeMillis();

        // ── Step 1: Aho-Corasick 키워드 필터 (<0.1ms) ──
        FilterResult keywordResult = keywordFilter.check(message);
        if (!keywordResult.passed()) {
            log.info("[MODERATION] Blocked by Step 1 (keyword): category={}, keyword='{}'",
                keywordResult.category(), keywordResult.matchedKeyword());
            return new ModerationVerdict(
                false, 1, keywordResult.category().name(),
                keywordResult.userMessage(),
                System.currentTimeMillis() - totalStart
            );
        }

        // ── Step 2: OpenAI Moderation API (~200ms) ──
        ModerationResult apiResult = moderationClient.moderate(message);
        if (!apiResult.passed()) {
            log.info("[MODERATION] Blocked by Step 2 (OpenAI): category={}, score={:.3f}, latency={}ms",
                apiResult.flaggedCategory(), apiResult.score(), apiResult.latencyMs());
            return new ModerationVerdict(
                false, 2, apiResult.flaggedCategory(),
                apiResult.userMessage(),
                System.currentTimeMillis() - totalStart
            );
        }

        long totalElapsed = System.currentTimeMillis() - totalStart;
        if (totalElapsed > 500) {
            log.warn("[MODERATION] Slow moderation pipeline: {}ms", totalElapsed);
        }

        return ModerationVerdict.PASS;
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  Result DTO
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    public record ModerationVerdict(
        boolean passed,
        int blockedAtStep,    // 0=통과, 1=키워드, 2=OpenAI
        String category,      // 차단 카테고리
        String userMessage,   // 유저에게 보여줄 메시지
        long totalLatencyMs   // 전체 소요 시간
    ) {
        public static final ModerationVerdict PASS =
            new ModerationVerdict(true, 0, null, null, 0);
    }
}