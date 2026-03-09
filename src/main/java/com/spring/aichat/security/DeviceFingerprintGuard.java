package com.spring.aichat.security;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * [Phase 5] 기기/IP 기반 다중 계정 방지 (Shadow Ban) 시스템
 *
 * [설계]
 * 프론트엔드에서 브라우저 핑거프린트(FingerprintJS visitorId)를 수집하여
 * 소셜 로그인 성공 시 서버로 전송. 동일 기기/IP에서 짧은 기간 내
 * 복수 계정이 생성되면 소프트 밴을 적용한다.
 *
 * [Redis Key 설계]
 * 1. "fp:accounts:{fingerprint}" → 해당 기기에서 생성/로그인된 계정 수 (TTL: 7일)
 * 2. "fp:ban:{fingerprint}"      → 섀도우 밴 플래그 (TTL: 3일)
 * 3. "ip:accounts:{ip}"          → 해당 IP에서 생성된 계정 수 (TTL: 24시간)
 * 4. "fp:user:{userId}"          → 유저의 핑거프린트 기록 (영구)
 *
 * [정책]
 * - 동일 기기에서 3개 이상 계정 생성 → 소프트 밴 (초기 에너지 0)
 * - 동일 IP에서 5개 이상 계정 생성(24h 내) → 신규 가입 차단
 * - 섀도우 밴된 계정: 서비스 이용은 가능하지만 초기 무료 에너지 미지급
 *
 * [핑거프린트 수집 (프론트)]
 * FingerprintJS(무료 버전)의 visitorId를 사용.
 * OAuth 콜백 시 또는 로그인 직후 POST /api/v1/auth/device로 전송.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class DeviceFingerprintGuard {

    private final StringRedisTemplate redisTemplate;

    private static final String FP_ACCOUNTS_PREFIX = "fp:accounts:";
    private static final String FP_BAN_PREFIX = "fp:ban:";
    private static final String IP_ACCOUNTS_PREFIX = "ip:accounts:";
    private static final String FP_USER_PREFIX = "fp:user:";

    private static final int MAX_ACCOUNTS_PER_DEVICE = 3;
    private static final int MAX_ACCOUNTS_PER_IP_24H = 5;
    private static final long DEVICE_WINDOW_DAYS = 7;
    private static final long BAN_DURATION_DAYS = 3;

    /**
     * 신규 계정 생성 시 호출 — 기기/IP 카운트 증가 + 밴 판정
     *
     * @return DeviceCheckResult (정상 / 소프트밴 / 차단)
     */
    public DeviceCheckResult onAccountCreated(String fingerprint, String clientIp, Long userId) {
        // 핑거프린트가 없으면 (JS 미로드 등) IP만으로 판단
        if (fingerprint == null || fingerprint.isBlank()) {
            return checkIpOnly(clientIp, userId);
        }

        // 유저-핑거프린트 매핑 저장
        redisTemplate.opsForValue().set(FP_USER_PREFIX + userId, fingerprint);

        // 기기별 계정 수 증가
        String fpKey = FP_ACCOUNTS_PREFIX + fingerprint;
        Long fpCount = redisTemplate.opsForValue().increment(fpKey);
        if (fpCount != null && fpCount == 1) {
            redisTemplate.expire(fpKey, DEVICE_WINDOW_DAYS, TimeUnit.DAYS);
        }

        // IP별 계정 수 증가
        String ipKey = IP_ACCOUNTS_PREFIX + clientIp;
        Long ipCount = redisTemplate.opsForValue().increment(ipKey);
        if (ipCount != null && ipCount == 1) {
            redisTemplate.expire(ipKey, 24, TimeUnit.HOURS);
        }

        log.info("[DEVICE_GUARD] Account created: fp={}, ip={}, userId={}, fpCount={}, ipCount={}",
            fingerprint.substring(0, Math.min(8, fingerprint.length())),
            clientIp, userId, fpCount, ipCount);

        // IP 과다 생성 → 하드 블록
        if (ipCount != null && ipCount > MAX_ACCOUNTS_PER_IP_24H) {
            log.warn("[DEVICE_GUARD] IP hard block: ip={}, count={}", clientIp, ipCount);
            return DeviceCheckResult.HARD_BLOCK;
        }

        // 기기 과다 생성 → 소프트 밴
        if (fpCount != null && fpCount > MAX_ACCOUNTS_PER_DEVICE) {
            String banKey = FP_BAN_PREFIX + fingerprint;
            redisTemplate.opsForValue().set(banKey, "true", BAN_DURATION_DAYS, TimeUnit.DAYS);
            log.warn("[DEVICE_GUARD] Soft ban applied: fp={}, count={}", fingerprint, fpCount);
            return DeviceCheckResult.SOFT_BAN;
        }

        return DeviceCheckResult.OK;
    }

    /**
     * 핑거프린트 없이 IP만으로 체크
     */
    private DeviceCheckResult checkIpOnly(String clientIp, Long userId) {
        String ipKey = IP_ACCOUNTS_PREFIX + clientIp;
        Long ipCount = redisTemplate.opsForValue().increment(ipKey);
        if (ipCount != null && ipCount == 1) {
            redisTemplate.expire(ipKey, 24, TimeUnit.HOURS);
        }

        if (ipCount != null && ipCount > MAX_ACCOUNTS_PER_IP_24H) {
            log.warn("[DEVICE_GUARD] IP-only hard block: ip={}, count={}", clientIp, ipCount);
            return DeviceCheckResult.HARD_BLOCK;
        }

        return DeviceCheckResult.OK;
    }

    /**
     * 기존 계정 로그인 시 핑거프린트 업데이트
     */
    public void onLogin(String fingerprint, String clientIp, Long userId) {
        if (fingerprint != null && !fingerprint.isBlank()) {
            redisTemplate.opsForValue().set(FP_USER_PREFIX + userId, fingerprint);
        }
    }

    /**
     * 소프트 밴 여부 확인 (에너지 지급 판단용)
     */
    public boolean isSoftBanned(String fingerprint) {
        if (fingerprint == null || fingerprint.isBlank()) return false;
        return Boolean.TRUE.toString().equals(
            redisTemplate.opsForValue().get(FP_BAN_PREFIX + fingerprint));
    }

    /**
     * 특정 유저의 핑거프린트 조회
     */
    public String getFingerprint(Long userId) {
        return redisTemplate.opsForValue().get(FP_USER_PREFIX + userId);
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    public enum DeviceCheckResult {
        OK,         // 정상 — 초기 에너지 전액 지급
        SOFT_BAN,   // 소프트 밴 — 서비스 이용 가능, 초기 에너지 0
        HARD_BLOCK  // 하드 블록 — 가입 자체 차단
    }
}