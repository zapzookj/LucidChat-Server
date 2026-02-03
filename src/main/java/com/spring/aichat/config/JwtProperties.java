package com.spring.aichat.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** JWT 설정 프로퍼티 */
@ConfigurationProperties(prefix = "auth.jwt")
public record JwtProperties(
    String issuer,
    String secret,
    long accessTokenTtlSeconds,
    long refreshTokenTtlSeconds
) {}
