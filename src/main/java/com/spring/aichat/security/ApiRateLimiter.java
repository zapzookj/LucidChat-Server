package com.spring.aichat.security;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * [Phase 5] Redis 기반 API Rate Limiter (슬라이딩 윈도우 카운터)
 *
 * [설계 근거]
 * Bucket4j 라이브러리 대신 Redis Lua Script 기반의 슬라이딩 윈도우 카운터를 직접 구현.
 * 이유: 외부 의존성 최소화 + Redis를 이미 사용 중이므로 추가 인프라 비용 제로.
 *
 * [알고리즘: Fixed Window Counter with Redis INCR + EXPIRE]
 * - Key: "rl:{endpoint}:{username}:{windowKey}"
 * - windowKey: currentTimeMillis / windowMs (윈도우 단위 시간 식별자)
 * - Lua Script로 INCR + EXPIRE를 원자적 실행 → 레이스 컨디션 완전 방지
 *
 * [적용 대상 엔드포인트 및 한도]
 * ┌────────────────────────────┬──────────┬─────────────┬─────────────────────────────┐
 * │ Endpoint                   │ Window   │ Max Requests│ 위협                         │
 * ├────────────────────────────┼──────────┼─────────────┼─────────────────────────────┤
 * │ POST /chat/rooms/{}/messages│ 3초      │ 1           │ LLM 과금 폭탄 (핵심 방어선)   │
 * │ POST /chat/rooms/{}/init   │ 5초      │ 1           │ Init 스팸                    │
 * │ POST /story/rooms/{}/events│ 3초      │ 1           │ 나레이터 LLM 남용             │
 * │ POST /payments/ready       │ 5초      │ 2           │ 주문 생성 남용                │
 * │ POST /payments/confirm     │ 5초      │ 2           │ 결제 검증 남용                │
 * │ PATCH /users/update        │ 5초      │ 3           │ 프로필 업데이트 스팸           │
 * │ POST /auth/login           │ 60초     │ 5           │ 브루트포스 로그인              │
 * │ POST /auth/signup          │ 60초     │ 3           │ 계정 생성 남용                │
 * └────────────────────────────┴──────────┴─────────────┴─────────────────────────────┘
 *
 * [Redis 메모리 영향]
 * - Key 하나당 ~100 bytes
 * - 1,000명 동시 접속 × 8개 엔드포인트 = ~800KB (무시 가능)
 * - TTL 자동 만료 → 메모리 누수 없음
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class ApiRateLimiter {

    private final StringRedisTemplate redisTemplate;

    private static final String KEY_PREFIX = "rl:";

    /**
     * Lua Script: 원자적 INCR + EXPIRE
     *
     * KEYS[1] = rate limit key
     * ARGV[1] = max allowed count
     * ARGV[2] = window TTL in seconds
     *
     * Returns: 0 = 허용, 1 = 차단
     */
    private static final String RATE_LIMIT_LUA = """
        local current = redis.call('INCR', KEYS[1])
        if current == 1 then
            redis.call('EXPIRE', KEYS[1], ARGV[2])
        end
        if current > tonumber(ARGV[1]) then
            return 1
        end
        return 0
        """;

    private static final DefaultRedisScript<Long> RATE_LIMIT_SCRIPT =
        new DefaultRedisScript<>(RATE_LIMIT_LUA, Long.class);

    /**
     * Rate limit 체크
     *
     * @param endpoint 엔드포인트 식별자 (예: "chat_send")
     * @param identifier 유저 식별자 (username 또는 IP)
     * @param maxRequests 윈도우 내 최대 허용 횟수
     * @param windowSeconds 윈도우 크기 (초)
     * @return true = 차단 (한도 초과), false = 허용
     */
    public boolean isRateLimited(String endpoint, String identifier, int maxRequests, int windowSeconds) {
        String key = KEY_PREFIX + endpoint + ":" + identifier;

        try {
            Long result = redisTemplate.execute(
                RATE_LIMIT_SCRIPT,
                List.of(key),
                String.valueOf(maxRequests),
                String.valueOf(windowSeconds)
            );
            boolean blocked = result != null && result == 1L;

            if (blocked) {
                log.warn("[RATE_LIMIT] BLOCKED: endpoint={}, user={}, limit={}/{}s",
                    endpoint, identifier, maxRequests, windowSeconds);
            }

            return blocked;
        } catch (Exception e) {
            // Redis 장애 시 → 요청 허용 (서비스 가용성 우선)
            log.error("[RATE_LIMIT] Redis error — allowing request: endpoint={}, user={}",
                endpoint, identifier, e);
            return false;
        }
    }

    /**
     * 편의 메서드: 채팅 전송 (가장 엄격 — 3초에 1회)
     */
    public boolean checkChatSend(String username) {
        return isRateLimited("chat_send", username, 1, 3);
    }

    /**
     * 편의 메서드: 채팅방 초기화
     */
    public boolean checkChatInit(String username) {
        return isRateLimited("chat_init", username, 1, 5);
    }

    /**
     * 편의 메서드: 이벤트 트리거
     */
    public boolean checkEventTrigger(String username) {
        return isRateLimited("event_trigger", username, 1, 3);
    }

    /**
     * 편의 메서드: 결제 관련
     */
    public boolean checkPayment(String username) {
        return isRateLimited("payment", username, 2, 5);
    }

    /**
     * 편의 메서드: 프로필 업데이트
     */
    public boolean checkProfileUpdate(String username) {
        return isRateLimited("profile_update", username, 3, 5);
    }

    /**
     * 편의 메서드: 로그인 (IP 기반 권장)
     */
    public boolean checkLogin(String ipOrUsername) {
        return isRateLimited("login", ipOrUsername, 5, 60);
    }

    /**
     * 편의 메서드: 회원가입 (IP 기반 권장)
     */
    public boolean checkSignup(String ipOrUsername) {
        return isRateLimited("signup", ipOrUsername, 3, 60);
    }
}