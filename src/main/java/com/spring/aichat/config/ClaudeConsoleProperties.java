package com.spring.aichat.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Claude Console(Anthropic 직결) 채팅 경로 설정.
 *
 * <p>⚠ 프리픽스를 {@code anthropic}으로 두지 않는다 — OS 환경변수 {@code ANTHROPIC_API_KEY}가
 * relaxed binding으로 {@code anthropic.api-key}를 덮어쓴다(OPENAI_API_KEY 납치 사고와 같은 함정).
 * 키는 {@code CLAUDE_API_KEY} 환경변수로만 받는다.
 *
 * <p>apiKey가 비어 있으면 경로 전체가 꺼지고 기존 OpenRouter 경로만 탄다(배포 환경에 키를 안 넣으면 무변경).
 *
 * @param apiKey             Claude Console API 키 (공란 = 비활성)
 * @param model              일반 모드 모델
 * @param boostModel         부스트 모드 모델
 * @param effort             일반 모드 effort (low|medium|high)
 * @param boostEffort        부스트 모드 effort (low|medium|high)
 * @param ttftDeadlineMs     첫 토큰 데드라인 — 초과 시 OpenRouter로 폴백
 * @param creditCooldownSec  크레딧 소진·인증 실패 감지 후 Claude 경로를 건너뛰는 시간
 * @param errorCooldownSec   과부하·레이트리밋·타임아웃 후 건너뛰는 시간
 */
@ConfigurationProperties(prefix = "claude-console")
public record ClaudeConsoleProperties(
    String apiKey,
    String model,
    String boostModel,
    String effort,
    String boostEffort,
    Long ttftDeadlineMs,
    Long creditCooldownSec,
    Long errorCooldownSec
) {
    public ClaudeConsoleProperties {
        if (model == null || model.isBlank()) model = "claude-haiku-5-5";
        if (boostModel == null || boostModel.isBlank()) boostModel = "claude-sonnet-5-5";
        if (effort == null || effort.isBlank()) effort = "medium";
        if (boostEffort == null || boostEffort.isBlank()) boostEffort = "low";
        if (ttftDeadlineMs == null || ttftDeadlineMs <= 0) ttftDeadlineMs = 8000L;
        if (creditCooldownSec == null || creditCooldownSec <= 0) creditCooldownSec = 600L;
        if (errorCooldownSec == null || errorCooldownSec <= 0) errorCooldownSec = 30L;
    }

    public boolean enabled() {
        return apiKey != null && !apiKey.isBlank();
    }
}
