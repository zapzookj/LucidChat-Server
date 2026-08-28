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
     * [버그픽스 B-10.1 · docs/17_assets/defect_register.md §B-10.1 · docs/19 D-31]
     * 토큰 타입 구분 클레임. 리소스 서버 디코더는 만료·nbf만 보므로 타입 구분이 없으면
     * refresh 토큰이 Bearer 액세스 토큰 자리에 그대로 통과한다.
     */
    public static final String TOKEN_TYPE_CLAIM = "typ";
    public static final String TOKEN_TYPE_ACCESS = "access";
    public static final String TOKEN_TYPE_REFRESH = "refresh";

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
            // [버그픽스 B-10.1 · docs/17_assets/defect_register.md §B-10.1 · docs/19 D-31]
            //   토큰 타입 구분자. 검증은 JwtBlacklistFilter가 한다(TOKEN_TYPE_CLAIM).
            .claim(TOKEN_TYPE_CLAIM, TOKEN_TYPE_ACCESS)
            .build();

        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        return jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }

    /**
     * Refresh Token 생성 (랜덤 UUID 대신 JWT 형식을 사용하여 유효성 검증도 가능하게 함)
     *
     * <p>[버그픽스 B-10.1/B-10.2 · docs/17_assets/defect_register.md §B-10.1·§B-10.2 · docs/19 D-31]
     * <ul>
     *   <li>{@code typ=refresh} — 이전에는 AT/RT의 차이가 jti·role·만료뿐이라 RT를
     *       {@code Authorization: Bearer}에 그대로 넣으면 통과했다. TTL이 14일(AT의 336배)이라
     *       사실상 14일짜리 액세스 토큰이었다. 거부는 JwtBlacklistFilter가 한다.</li>
     *   <li>{@code jti} — 없으면 로그아웃·블랙리스트(BL:{jti})로 무효화할 수단이 아예 없다.
     *       회전(reissue) 시 구 RT의 jti를 블랙리스트에 넣는 것도 이 클레임이 전제다.</li>
     * </ul>
     */
    private String generateRefreshToken(String username) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
            .id(UUID.randomUUID().toString())   // jti — B-10.2
            .issuer(props.issuer())
            .issuedAt(now)
            .expiresAt(now.plusSeconds(props.refreshTokenTtlSeconds()))
            .subject(username)
            .claim(TOKEN_TYPE_CLAIM, TOKEN_TYPE_REFRESH)   // B-10.1
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

        // [버그픽스 B-10.2 · docs/17_assets/defect_register.md §B-10.2 · docs/19 D-31]
        //   RTR 회전 시 구 RT의 jti를 블랙리스트에 넣는다. 기존에는 Redis 값 불일치로
        //   '재발급'만 막혔을 뿐, 구 RT를 Bearer로 쓰는 경로는 열려 있었다.
        //   (B-10.1의 typ 거부가 1차 방어선이고 이건 다층 방어 — 레거시 RT는 jti가 없어
        //    여기서 걸리지 않으므로 typ 규칙 쪽이 반드시 함께 있어야 한다.)
        blacklistRotatedRefreshToken(jwt);

        return issueTokenPair(username, role);
    }

    /** [버그픽스 B-10.2] 회전된 구 refresh 토큰을 남은 TTL만큼 BL:{jti}에 등록. */
    private void blacklistRotatedRefreshToken(Jwt oldRefreshJwt) {
        String jti = oldRefreshJwt.getId();
        if (jti == null || jti.isBlank() || oldRefreshJwt.getExpiresAt() == null) {
            return; // 픽스 이전 발급분(jti 없음) — typ 규칙이 Bearer 경로를 막는다
        }
        long ttl = Duration.between(Instant.now(), oldRefreshJwt.getExpiresAt()).getSeconds();
        if (ttl > 0) {
            redisTemplate.opsForValue().set(
                BLACKLIST_PREFIX + jti, "rotated", ttl, TimeUnit.SECONDS);
        }
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
    public void logout(String accessToken, String refreshToken, String username) {
        // [버그픽스 B-10.2 잔여 · docs/19 §F D-31] 로그아웃 시 **RT의 jti도** 블랙리스트한다.
        //   Redis의 REFRESH_PREFIX 삭제만으로는 '서버가 기억하는 최신 RT'만 지워질 뿐,
        //   유저가 이미 들고 있는 RT 문자열 자체는 남은 TTL(14일) 동안 유효하다.
        //   typ 클레임(B-10.1)이 Bearer 오용은 막지만, /auth/refresh 재발급은 그것만으로 막히지 않는다.
        //   ※ 1-arg/2-arg 구버전은 남기지 않는다(CLAUDE.md §2-6) — 호출부를 컴파일러가 드러내게 한다.
        if (refreshToken != null && !refreshToken.isBlank()) {
            try {
                blacklistRotatedRefreshToken(jwtDecoder.decode(refreshToken));
            } catch (JwtException e) {
                log.warn("[JWT] logout: refresh token decode 실패 (무시) — {}", e.getMessage());
            }
        }
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

    /**
     * [버그픽스 B-10.1 · docs/17_assets/defect_register.md §B-10.1 · docs/19 D-31]
     * Bearer 자리에 들어온 토큰이 <b>refresh 토큰</b>인지 판정한다. 호출자는 JwtBlacklistFilter.
     *
     * <p>판정 규칙 (§B-10.1 수정안 4번 — 전환기 하위 호환):
     * <ul>
     *   <li>{@code typ == "refresh"} → refresh. (이 픽스 이후 발급분)</li>
     *   <li>{@code typ} 없음 &amp;&amp; {@code jti} 없음 → refresh. 픽스 이전 RT는 정확히 이 모양이고,
     *       픽스 이전 AT는 jti를 갖는다(:63) — 이 조합만이 레거시 RT를 특정한다.</li>
     *   <li>그 외 → access로 간주해 통과. AT TTL이 1시간이라 전환은 1시간 안에 끝난다.</li>
     * </ul>
     * 유효하지 않은 토큰은 Resource Server가 401로 처리하므로 여기서는 false(통과)로 위임한다.
     */
    public boolean isRefreshTypeToken(String token) {
        try {
            Jwt jwt = jwtDecoder.decode(token);
            String typ = jwt.getClaimAsString(TOKEN_TYPE_CLAIM);
            if (typ != null) {
                return TOKEN_TYPE_REFRESH.equals(typ);
            }
            String jti = jwt.getId();
            return jti == null || jti.isBlank();
        } catch (JwtException e) {
            return false;
        }
    }

    public String extractUsername(String token) {
        return jwtDecoder.decode(token).getSubject();
    }

    public record TokenPair(String accessToken, String refreshToken) {}
}
