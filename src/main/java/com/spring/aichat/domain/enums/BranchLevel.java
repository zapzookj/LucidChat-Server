package com.spring.aichat.domain.enums;

/**
 * [Phase 5.5-Theater] Theater 분기 레벨
 *
 * 3단 분기 시스템:
 * - MINOR:   소분기 (2~3씬마다, 톤 조정 수준)
 * - MAJOR:   중분기 (Chapter 마지막, 다음 Chapter 방향 결정)
 * - CLIMAX:  대분기 (Act 끝, 승급/엔딩 직전, 시네마틱 연출)
 * - LOCATION: 장소 선택 분기 (Act 초입에서 어느 히로인 씬으로 갈지 결정)
 */
public enum BranchLevel {

    /** 소분기 — 톤 조정 (인라인 2지선다), 에너지 무료 */
    MINOR(0, 2, "톤 조정"),

    /** 중분기 — Chapter 방향 (풀스크린 3지선다), 에너지 1 */
    MAJOR(1, 3, "큰 선택"),

    /** 대분기 — Act/엔딩 결정 (시네마틱 3지선다), 에너지 2 */
    CLIMAX(2, 3, "결정적 선택"),

    /** 장소 선택 — Act 초입에서 씬 방향 결정, 에너지 무료 */
    LOCATION(0, 3, "장소 선택");

    private final int energyCost;
    private final int typicalOptionCount;
    private final String label;

    BranchLevel(int energyCost, int typicalOptionCount, String label) {
        this.energyCost = energyCost;
        this.typicalOptionCount = typicalOptionCount;
        this.label = label;
    }

    public int getEnergyCost() { return energyCost; }
    public int getTypicalOptionCount() { return typicalOptionCount; }
    public String getLabel() { return label; }
}