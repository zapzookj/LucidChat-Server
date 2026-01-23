package com.spring.aichat.domain.enums;

/**
 * 관계 상태 레벨(호감도 기반)
 */
public enum RelationStatus {
    STRANGER, // 타인, 호감도 0~20
    ACQUAINTANCE, // 지인, 호감도 21~40
    FRIEND, // 친구, 호감도 41~70
    LOVER, // 사랑, 호감도 71~100
    ENEMY // 적, 호감도 -100~-1
}
