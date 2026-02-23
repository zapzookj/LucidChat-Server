package com.spring.aichat.domain.enums;

/**
 * 채팅 모드
 *
 * [Phase 4 — Multi-Track Pipeline]
 *
 * STORY:   정해진 플롯(호감도, 승급, 엔딩, 이스터에그) 기반 비주얼 노벨
 *          - 풀 시스템 프롬프트 (씬 디렉션, 관계 가이드, 이스터에그 등)
 *          - 에너지 소모: 2 per message
 *
 * SANDBOX: 자유 대화 모드. 기본 페르소나만 유지, 모든 게임 시스템 비활성화
 *          - 경량 시스템 프롬프트 (페르소나 + 감정 태그만)
 *          - 에너지 소모: 1 per message
 */
public enum ChatMode {
    STORY,
    SANDBOX;

    /** 모드별 메시지당 에너지 소모량 */
    public int getEnergyCost() {
        return this == STORY ? 2 : 1;
    }

    /** 한국어 표시명 */
    public String getDisplayName() {
        return this == STORY ? "스토리 모드" : "자유 모드";
    }
}