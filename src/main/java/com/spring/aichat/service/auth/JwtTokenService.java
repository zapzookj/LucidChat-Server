package com.spring.aichat.service.auth;

import com.spring.aichat.config.JwtProperties;
import com.spring.aichat.domain.user.User;
import com.spring.aichat.domain.user.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class JwtTokenService {

    private final JwtEncoder jwtEncoder;
    private final JwtDecoder jwtDecoder;
    private final JwtProperties props;
    private final StringRedisTemplate redisTemplate;
    // [Phase6/Tier3 / H-1] reissue 시 DB에서 최신 role 조회용
    private final UserRepository userRepository;

    private static final String REFRESH_PREFIX = "RT:";
    /** [Phase6/Tier3 / M-4] 블랙리스트 키: BL:{jti}. 토큰 전체를 키로 쓰던 비효율 제거. */
    private static final String BLACKLIST_PREFIX = "BL:";
    /** [Phase 6] 계정 정지 마커: SUSP:USER:{username}. 활성 access 토큰 즉시 차단용. */
    private static final String SUSPENDED_PREFIX = "SUSP:USER:";
    private static final String DEFAULT_ROLE = "ROLE_USER";

    /**
     * Access Token & Refresh Token 발급
     */
    public TokenPair issueTokenPair(String username, String role) {
        String accessToken = generateAccessToken(username, role);
        String refreshToken = generateRefreshToken(username);

        // Redis에 Refresh Token 저장 (Key: RT:{username}, Value: {refreshToken})
        redisTemplate.opsForValue().set(
            REFRESH_PREFIX + username,
            refreshToken,
            props.refreshTokenTtlSeconds(),
            TimeUnit.SECONDS
        );

        return new TokenPair(accessToken, refreshToken);
    }

    /**
     * Access Token 생성 — [Phase6/Tier3 / M-4] jti(JWT ID) 클레임 추가.
     *  · 블랙리스트 키를 BL:{jti}로 짧게 유지 — 토큰 전체를 키로 쓰던 비효율 제거.
     *  · jti는 UUID로 충돌 가능성 무시 가능.
     */
    private String generateAccessToken(String username, String role) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
            .id(UUID.randomUUID().toString())   // jti
            .issuer(props.issuer())
            .issuedAt(now)
            .expiresAt(now.plusSeconds(props.accessTokenTtlSeconds()))
            .subject(username)
            .claim("role", role)
            .build();

        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        return jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }

    /**
     * Refresh Token 생성 (랜덤 UUID 대신 JWT 형식을 사용하여 유효성 검증도 가능하게 함)
     */
    private String generateRefreshToken(String username) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
            .issuer(props.issuer())
            .issuedAt(now)
            .expiresAt(now.plusSeconds(props.refreshTokenTtlSeconds()))
            .subject(username)
            .build();

        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        return jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }

    /**
     * 토큰 재발급 (Rotate Refresh Token)
     *
     * [Phase6/Tier3 / H-1]
     *  - role 하드코딩 제거 → DB에서 최신 role 조회. ADMIN 강등 방지.
     *  - 탈취 감지(RT mismatch) 시 해당 username의 RT 키 *삭제* → 모든 활성 세션 강제 로그아웃.
     */
    public TokenPair reissue(String refreshToken) {
        // 1. 토큰 자체 유효성 검사
        Jwt jwt = jwtDecoder.decode(refreshToken);
        String username = jwt.getSubject();

        // 2. Redis에 저장된 토큰과 일치하는지 확인 (탈취 감지)
        String storedToken = redisTemplate.opsForValue().get(REFRESH_PREFIX + username);
        if (storedToken == null || !storedToken.equals(refreshToken)) {
            // [H-1] 탈취 의심 → 강제 전체 무효화
            redisTemplate.delete(REFRESH_PREFIX + username);
            log.warn("[JWT] RT mismatch — possible theft. All sessions revoked: user={}", username);
            throw new IllegalArgumentException("유효하지 않거나 만료된 Refresh Token입니다.");
        }

        // 3. [H-1] DB에서 최신 role 조회 + [Phase 6] 정지 계정 재발급 차단.
        User user = userRepository.findByUsername(username).orElse(null);
        if (user != null && user.isAccessBlocked()) {
            redisTemplate.delete(REFRESH_PREFIX + username);
            log.warn("[JWT] Reissue blocked — account not active: user={}, status={}", username, user.getStatus());
            throw new IllegalArgumentException("정지되었거나 이용이 제한된 계정입니다.");
        }
        String role = (user != null) ? extractPrimaryRole(user) : DEFAULT_ROLE;

        return issueTokenPair(username, role);
    }

    /**
     * [Phase6/Tier3 / H-1, H-2] User.roles에서 대표 role 1개 추출.
     * 다중 role을 갖는 유저(ADMIN+USER 등)는 ADMIN을 우선시한다.
     * 비어있으면 ROLE_USER 폴백.
     */
    public String extractPrimaryRole(User user) {
        if (user.getRoles() == null || user.getRoles().isEmpty()) {
            return DEFAULT_ROLE;
        }
        // ADMIN > MODERATOR > USER 우선순위
        if (user.getRoles().contains("ROLE_ADMIN")) return "ROLE_ADMIN";
        if (user.getRoles().contains("ROLE_MODERATOR")) return "ROLE_MODERATOR";
        return user.getRoles().iterator().next();
    }

    /**
     * 로그아웃 (Access Token 블랙리스트 처리)
     *
     * [Phase6/Tier3 / M-4] 블랙리스트 키를 BL:{jti}로 변경. 토큰 전체를 키로 저장하던
     * 비효율(긴 키, Redis 메모리 낭비)을 제거.
     */
    public void logout(String accessToken, String username) {
        try {
            Jwt jwt = jwtDecoder.decode(accessToken);
            long ttl = Duration.between(Instant.now(), jwt.getExpiresAt()).getSeconds();

            if (ttl > 0) {
                String jti = jwt.getId();
                if (jti != null && !jti.isBlank()) {
                    redisTemplate.opsForValue().set(
                        BLACKLIST_PREFIX + jti,
                        "logout",
                        ttl,
                        TimeUnit.SECONDS
                    );
                } else {
                    // [호환] 기존 발급된 토큰(jti 없음) — 토큰 전체로 폴백
                    log.warn("[JWT] Token without jti — legacy fallback to full-token key");
                    redisTemplate.opsForValue().set(
                        BLACKLIST_PREFIX + accessToken,
                        "logout",
                        ttl,
                        TimeUnit.SECONDS
                    );
                }
            }
            // Refresh Token 삭제
            redisTemplate.delete(REFRESH_PREFIX + username);
        } catch (JwtException e) {
            log.warn("로그아웃 시 유효하지 않은 토큰 무시: {}", e.getMessage());
        }
    }

    /**
     * 블랙리스트 여부 확인.
     *
     * [Phase6/Tier3 / C-2 + M-4] 호출자(JwtBlacklistFilter)는 token 문자열만 알고 있으므로
     * 내부에서 jwtDecoder로 jti 추출 후 BL:{jti} 체크. 유효하지 않은 토큰은 어차피
     * Resource Server가 401 처리하므로 여기서는 false 반환.
     */
    public boolean isBlacklisted(String accessToken) {
        try {
            Jwt jwt = jwtDecoder.decode(accessToken);
            String jti = jwt.getId();
            if (jti != null && !jti.isBlank()) {
                if (Boolean.TRUE.equals(redisTemplate.hasKey(BLACKLIST_PREFIX + jti))) {
                    return true;
                }
            }
            // [호환] jti 없는 레거시 토큰: 토큰 전체 키도 같이 확인
            return Boolean.TRUE.equals(redisTemplate.hasKey(BLACKLIST_PREFIX + accessToken));
        } catch (JwtException e) {
            // 유효성 검증 실패는 Resource Server에 위임. 여기서는 통과.
            return false;
        }
    }

    /**
     * [Phase 6] 계정 정지 — 활성 access 토큰 즉시 차단 + refresh 토큰 삭제(재발급 불가).
     * 마커 TTL 은 access TTL 과 동일 — 그 시간이 지나면 기존 access 토큰은 어차피 만료되고,
     * 신규 발급은 로그인/OAuth/reissue 의 status 체크로 막힌다.
     */
    public void revokeUserSessions(String username) {
        redisTemplate.opsForValue().set(
            SUSPENDED_PREFIX + username, "1",
            props.accessTokenTtlSeconds(), TimeUnit.SECONDS);
        redisTemplate.delete(REFRESH_PREFIX + username);
    }

    /** [Phase 6] 정지 해제 — access 차단 마커 제거. */
    public void clearUserSessionRevocation(String username) {
        redisTemplate.delete(SUSPENDED_PREFIX + username);
    }

    /**
     * [Phase 6] 필터용 통합 판정 — 토큰이 블랙리스트(jti)이거나 계정 정지(subject) 상태인지.
     * 토큰을 1회만 디코드한다.
     */
    public boolean isTokenRevoked(String accessToken) {
        try {
            Jwt jwt = jwtDecoder.decode(accessToken);
            String jti = jwt.getId();
            if (jti != null && !jti.isBlank()
                && Boolean.TRUE.equals(redisTemplate.hasKey(BLACKLIST_PREFIX + jti))) {
                return true;
            }
            if (Boolean.TRUE.equals(redisTemplate.hasKey(BLACKLIST_PREFIX + accessToken))) {
                return true; // 레거시(jti 없는 토큰)
            }
            String sub = jwt.getSubject();
            return sub != null && Boolean.TRUE.equals(redisTemplate.hasKey(SUSPENDED_PREFIX + sub));
        } catch (JwtException e) {
            return false;
        }
    }

    public String extractUsername(String token) {
        return jwtDecoder.decode(token).getSubject();
    }

    public record TokenPair(String accessToken, String refreshToken) {}
}
