package com.spring.aichat.external;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * [Phase 5.5-Stability] TTFT 기반 LLM Provider 서킷 브레이커
 *
 * Google AI Studio(고속/불안정) ↔ Vertex AI(저속/안정) 간 동적 라우팅.
 *
 * ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 *  상태 전이 다이어그램
 * ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 *
 *  [CLOSED] ─── 연속 3회 TTFT 초과 ──→ [OPEN]
 *      ↑                                  │
 *      │                              5분 경과
 *   프로브 성공                           │
 *      │                                  ↓
 *      └──────────────────────── [HALF_OPEN]
 *                                         │
 *                                    프로브 실패
 *                                         │
 *                                         ↓
 *                                      [OPEN] (5분 재시작)
 *
 * ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 *  TTFT 임계값 (상태별 차등)
 * ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 *
 *  CLOSED:    2초 (정상 운영 중 이상 감지)
 *  HALF_OPEN: 3초 (복구 확인은 좀 더 관대하게)
 *
 * ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 *  Per-Request Fallback
 * ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 *
 *  CLOSED 상태에서 개별 요청의 TTFT가 2초를 초과하면:
 *    1) 해당 요청은 즉시 스트림 중단 → Vertex로 재시도
 *    2) 실패 카운트 1 증가
 *    → 연속 3회 도달 시 서킷이 OPEN으로 전환
 */
@Component
@Slf4j
public class LlmCircuitBreaker {

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  상태 열거형
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    public enum State {
        /** 정상 — AI Studio 사용 */
        CLOSED,
        /** 차단 — Vertex 직행 */
        OPEN,
        /** 복구 테스트 — 프로브 1건만 AI Studio 시도 */
        HALF_OPEN
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  설정값
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /** 서킷 OPEN 전환까지 필요한 연속 실패 횟수 */
    private static final int FAILURE_THRESHOLD = 3;

    /** OPEN 상태 유지 시간 (5분) */
    private static final long OPEN_DURATION_MS = 5L * 60 * 1000;

    /** CLOSED 상태 TTFT 데드라인 (2초) */
    public static final long TTFT_DEADLINE_CLOSED_MS = 2_000;

    /** HALF_OPEN 상태 TTFT 데드라인 (3초 — 복구 확인은 관대하게) */
    public static final long TTFT_DEADLINE_HALF_OPEN_MS = 3_000;

    /** HALF_OPEN 프로브 교착 방지 타임아웃 (30초) */
    private static final long HALF_OPEN_PROBE_TIMEOUT_MS = 30_000;

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  Provider 식별자
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    public static final String PROVIDER_AI_STUDIO = "google-ai-studio";
    public static final String PROVIDER_VERTEX = "google-vertex";

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  Thread-safe 상태 변수
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    private final AtomicReference<State> state = new AtomicReference<>(State.CLOSED);
    private final AtomicInteger consecutiveFailures = new AtomicInteger(0);
    private final AtomicLong circuitOpenedAt = new AtomicLong(0);

    /** HALF_OPEN 프로브 진행 중 여부 (동시 요청 중 1건만 프로브 허용) */
    private final AtomicBoolean halfOpenProbeActive = new AtomicBoolean(false);
    /** 프로브 시작 시각 — 교착 방지용 */
    private final AtomicLong probeStartedAt = new AtomicLong(0);

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  핵심 API: Provider 결정
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /**
     * 현재 서킷 상태에 따라 사용할 Provider와 TTFT 데드라인을 결정한다.
     *
     * @return ProviderDecision — provider, ttftDeadlineMs, isPrimary
     */
    public ProviderDecision decide() {
        State current = state.get();

        switch (current) {
            case CLOSED:
                return ProviderDecision.primary(PROVIDER_AI_STUDIO, TTFT_DEADLINE_CLOSED_MS);

            case OPEN:
                long elapsed = System.currentTimeMillis() - circuitOpenedAt.get();
                if (elapsed >= OPEN_DURATION_MS) {
                    // 5분 경과 → HALF_OPEN 전이 시도
                    if (state.compareAndSet(State.OPEN, State.HALF_OPEN)) {
                        halfOpenProbeActive.set(false);
                        probeStartedAt.set(0);
                        log.info("🔄 [CIRCUIT] OPEN → HALF_OPEN ({}s elapsed, 복구 프로브 준비)",
                            elapsed / 1000);
                    }
                    return decide(); // HALF_OPEN 분기 재진입
                }
                // 아직 냉각 기간 — Vertex 직행
                return ProviderDecision.fallback(PROVIDER_VERTEX);

            case HALF_OPEN:
                // 프로브 교착 방지: 30초 이상 프로브가 완료되지 않으면 강제 리셋
                if (halfOpenProbeActive.get()) {
                    long probeElapsed = System.currentTimeMillis() - probeStartedAt.get();
                    if (probeElapsed > HALF_OPEN_PROBE_TIMEOUT_MS) {
                        log.warn("⚠️ [CIRCUIT] HALF_OPEN 프로브 교착 감지 ({}s) — 강제 리셋",
                            probeElapsed / 1000);
                        halfOpenProbeActive.set(false);
                        probeStartedAt.set(0);
                    }
                }

                // 프로브 슬롯 확보 (CAS — 동시 요청 중 1건만)
                if (halfOpenProbeActive.compareAndSet(false, true)) {
                    probeStartedAt.set(System.currentTimeMillis());
                    log.info("🔍 [CIRCUIT] HALF_OPEN 프로브 요청 — AI Studio 테스트 (deadline={}ms)",
                        TTFT_DEADLINE_HALF_OPEN_MS);
                    return ProviderDecision.primary(PROVIDER_AI_STUDIO, TTFT_DEADLINE_HALF_OPEN_MS);
                }
                // 다른 요청이 프로브 중 — Vertex로 우회
                return ProviderDecision.fallback(PROVIDER_VERTEX);

            default:
                return ProviderDecision.fallback(PROVIDER_VERTEX);
        }
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  결과 기록
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /**
     * AI Studio 요청 성공 기록.
     *
     * CLOSED → 연속 실패 카운터 리셋.
     * HALF_OPEN → CLOSED 전이 (AI Studio 복구 확인).
     */
    public void recordSuccess(long ttftMs) {
        State current = state.get();
        consecutiveFailures.set(0);

        if (current == State.HALF_OPEN) {
            state.set(State.CLOSED);
            halfOpenProbeActive.set(false);
            probeStartedAt.set(0);
            log.info("✅ [CIRCUIT] HALF_OPEN → CLOSED (TTFT={}ms, AI Studio 정상화 확인)", ttftMs);
        } else if (current == State.CLOSED) {
            log.debug("✅ [CIRCUIT] AI Studio 정상 (TTFT={}ms)", ttftMs);
        }
    }

    /**
     * AI Studio 요청 실패 기록 (TTFT 데드라인 초과).
     *
     * CLOSED → 연속 실패 카운트 증가, FAILURE_THRESHOLD 도달 시 OPEN 전이.
     * HALF_OPEN → 즉시 OPEN 전이 (5분 재냉각).
     */
    public void recordFailure(long ttftMs) {
        State current = state.get();

        if (current == State.HALF_OPEN) {
            // 프로브 실패 → 즉시 OPEN 복귀
            state.set(State.OPEN);
            circuitOpenedAt.set(System.currentTimeMillis());
            consecutiveFailures.set(0);
            halfOpenProbeActive.set(false);
            probeStartedAt.set(0);
            log.warn("❌ [CIRCUIT] HALF_OPEN → OPEN (프로브 실패, TTFT={}ms, 5분 재냉각)", ttftMs);
            return;
        }

        if (current == State.CLOSED) {
            int failures = consecutiveFailures.incrementAndGet();

            if (failures >= FAILURE_THRESHOLD) {
                // CAS로 단 한 스레드만 전이 수행
                if (state.compareAndSet(State.CLOSED, State.OPEN)) {
                    circuitOpenedAt.set(System.currentTimeMillis());
                    consecutiveFailures.set(0);
                    log.warn("🔴 [CIRCUIT] CLOSED → OPEN | 연속 {}회 TTFT 초과 → 5분간 Vertex 전환 | lastTTFT={}ms",
                        failures, ttftMs);
                }
            } else {
                log.warn("⚠️ [CIRCUIT] TTFT 초과 {}/{} (CLOSED 상태, TTFT={}ms)",
                    failures, FAILURE_THRESHOLD, ttftMs);
            }
        }
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  상태 조회 (모니터링/로깅용)
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    public State getState() {
        return state.get();
    }

    public int getConsecutiveFailures() {
        return consecutiveFailures.get();
    }

    public String getStatusSummary() {
        State s = state.get();
        return switch (s) {
            case CLOSED -> "CLOSED (AI Studio, failures=" + consecutiveFailures.get() + "/" + FAILURE_THRESHOLD + ")";
            case OPEN -> {
                long remaining = OPEN_DURATION_MS - (System.currentTimeMillis() - circuitOpenedAt.get());
                yield "OPEN (Vertex, " + Math.max(0, remaining / 1000) + "s remaining)";
            }
            case HALF_OPEN -> "HALF_OPEN (probing=" + halfOpenProbeActive.get() + ")";
        };
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  DTO
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /**
     * Provider 라우팅 결정 결과.
     *
     * @param provider        OpenRouter provider 식별자 ("google-ai-studio" | "vertex-ai")
     * @param ttftDeadlineMs  TTFT 데드라인 (ms). 0이면 데드라인 없음 (Vertex 직행 시).
     * @param isPrimary       true면 AI Studio 시도 (실패 시 Vertex 폴백 필요)
     */
    public record ProviderDecision(String provider, long ttftDeadlineMs, boolean isPrimary) {

        /** AI Studio 시도 (TTFT 데드라인 적용, 실패 시 Vertex 폴백) */
        public static ProviderDecision primary(String provider, long ttftDeadlineMs) {
            return new ProviderDecision(provider, ttftDeadlineMs, true);
        }

        /** Vertex 직행 (데드라인 없음) */
        public static ProviderDecision fallback(String provider) {
            return new ProviderDecision(provider, 0, false);
        }

        public boolean hasTtftDeadline() {
            return ttftDeadlineMs > 0;
        }
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  TTFT 타임아웃 예외
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /**
     * AI Studio TTFT 데드라인 초과 시 throw되는 예외.
     * ChatStreamService에서 catch 후 Vertex 폴백 + 서킷 실패 기록에 사용.
     */
    public static class TtftTimeoutException extends RuntimeException {
        private final long deadlineMs;

        public TtftTimeoutException(long deadlineMs) {
            super("AI Studio TTFT exceeded " + deadlineMs + "ms deadline");
            this.deadlineMs = deadlineMs;
        }

        public long getDeadlineMs() {
            return deadlineMs;
        }
    }
}