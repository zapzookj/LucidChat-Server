package com.spring.aichat.domain.chat;

import com.spring.aichat.domain.enums.RelationStatus;

/**
 * 호감도 점수에 따른 관계 레벨 정책
 */
public final class RelationStatusPolicy {

    private RelationStatusPolicy() {}

    public static RelationStatus fromScore(int score) {
        if (score < 0) return RelationStatus.ENEMY;
        if (score <= 20) return RelationStatus.STRANGER;
        if (score < 40) return RelationStatus.ACQUAINTANCE;
        if (score < 80) return RelationStatus.FRIEND;
        return RelationStatus.LOVER;
    }
}
