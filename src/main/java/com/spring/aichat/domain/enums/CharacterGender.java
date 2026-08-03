package com.spring.aichat.domain.enums;

/**
 * [2026-08-04 남캐 파이프라인] 캐릭터 성별 — 위저드 명시 선택(종원 확정, LLM 추론 배제).
 *
 * <p>파이프라인 분기의 단일 기준: 이미지 앵커 태그(1girl/1boy), Male_Type LoRA 조건부 주입,
 * Stage0 태그·연출 가이드, 씬 렌더 캐스트 성별. null=FEMALE(레거시 전 캐릭터).
 */
public enum CharacterGender {

    FEMALE, MALE;

    public boolean isMale() {
        return this == MALE;
    }

    /** 이미지 프롬프트 앵커 태그 — wai_illustrious danbooru 규약. */
    public String anchorTag() {
        return isMale() ? "1boy" : "1girl";
    }

    public static CharacterGender fromStringOrNull(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return valueOf(raw.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
