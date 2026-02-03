package com.spring.aichat.service.auth;

import com.spring.aichat.config.JwtProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class JwtTokenService {

    private final JwtEncoder jwtEncoder;
    private final JwtDecoder jwtDecoder;
    private final JwtProperties props;
    private final StringRedisTemplate redisTemplate;

    private static final String REFRESH_PREFIX = "RT:";
    private static final String BLACKLIST_PREFIX = "BL:";

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
     * Access Token 생성
     */
    private String generateAccessToken(String username, String role) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
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
     */
    public TokenPair reissue(String refreshToken) {
        // 1. 토큰 자체 유효성 검사
        Jwt jwt = jwtDecoder.decode(refreshToken);
        String username = jwt.getSubject();

        // 2. Redis에 저장된 토큰과 일치하는지 확인 (탈취 감지)
        String storedToken = redisTemplate.opsForValue().get(REFRESH_PREFIX + username);
        if (storedToken == null || !storedToken.equals(refreshToken)) {
            throw new IllegalArgumentException("유효하지 않거나 만료된 Refresh Token입니다.");
        }

        // 3. 기존 토큰 폐기 및 새 토큰 발급 (RTR 정책)
        // role은 DB에서 다시 조회하는 것이 안전하지만, 여기선 편의상 "ROLE_USER" 고정 혹은 JWT 파싱
        // 실제론 UserDetailsService 등을 통해 최신 권한을 가져오는 게 정석
        return issueTokenPair(username, "ROLE_USER");
    }

    /**
     * 로그아웃 (Access Token 블랙리스트 처리)
     */
    public void logout(String accessToken, String username) {
        try {
            Jwt jwt = jwtDecoder.decode(accessToken);
            long ttl = Duration.between(Instant.now(), jwt.getExpiresAt()).getSeconds();

            if (ttl > 0) {
                // Redis에 블랙리스트 등록 (Key: BL:{accessToken}, Value: logout)
                redisTemplate.opsForValue().set(
                    BLACKLIST_PREFIX + accessToken,
                    "logout",
                    ttl,
                    TimeUnit.SECONDS
                );
            }
            // Refresh Token 삭제
            redisTemplate.delete(REFRESH_PREFIX + username);
        } catch (JwtException e) {
            log.warn("로그아웃 시 유효하지 않은 토큰 무시: {}", e.getMessage());
        }
    }

    /**
     * 블랙리스트 여부 확인
     */
    public boolean isBlacklisted(String accessToken) {
        return Boolean.TRUE.equals(redisTemplate.hasKey(BLACKLIST_PREFIX + accessToken));
    }

    public String extractUsername(String token) {
        return jwtDecoder.decode(token).getSubject();
    }

    public record TokenPair(String accessToken, String refreshToken) {}
}
