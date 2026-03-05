package com.spring.aichat.service.payment;

import com.spring.aichat.config.OpenAiProperties;
import com.spring.aichat.domain.enums.ChatMode;
import com.spring.aichat.domain.user.User;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 부스트 모드 결정 헬퍼
 *
 * ChatService에서 주입받아 사용.
 * LLM 모델 선택과 에너지 비용 계산을 중앙화.
 *
 * [모델 선택 로직]
 * boostMode ON  → proModel (e.g. claude-sonnet)
 * boostMode OFF → model (e.g. gemini-flash)
 *
 * [비용 계산 로직]
 * boostMode OFF:
 *   SANDBOX=1, STORY=2 (기본 비용)
 *
 * boostMode ON + 비구독자:
 *   SANDBOX=5, STORY=10 (5배 — 체험용)
 *
 * boostMode ON + 구독자:
 *   SANDBOX=1, STORY=2 (기본 비용 — 구독 핵심 혜택)
 */
@Component
@RequiredArgsConstructor
public class BoostModeResolver {

    private final OpenAiProperties props;

    /**
     * 사용할 LLM 모델 결정
     */
    public String resolveModel(User user) {
        if (Boolean.TRUE.equals(user.getBoostMode())) {
            return props.proModel();
        }
        return props.model();
    }

    /**
     * 에너지 비용 계산
     */
    public int resolveEnergyCost(ChatMode chatMode, User user) {
        return chatMode.getEnergyCost(
            Boolean.TRUE.equals(user.getBoostMode()),
            user.isSubscriber()
        );
    }

    /**
     * 부스트 모드 상태 요약 (프론트엔드용)
     */
    public BoostModeInfo getInfo(User user, ChatMode chatMode) {
        boolean boost = Boolean.TRUE.equals(user.getBoostMode());
        boolean subscriber = user.isSubscriber();
        int cost = resolveEnergyCost(chatMode, user);
        String model = boost ? "Pro" : "Standard";

        return new BoostModeInfo(boost, subscriber, cost, model);
    }

    public record BoostModeInfo(
        boolean boostEnabled,
        boolean isSubscriber,
        int energyCost,
        String modelTier
    ) {}
}