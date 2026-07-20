package com.spring.aichat.domain.enums;

/**
 * [UGC v1] 캐릭터 공개 범위.
 *
 * <p>접근 규칙: {@code PUBLIC}은 전체, 그 외는 소유자만.
 * {@code PENDING_PUBLIC}(공개 심사 중)의 동작은 PRIVATE와 동일하다.
 * 공식(OFFICIAL) 캐릭터는 항상 PUBLIC — 목록 노출 제어는 별도 {@code hidden} 플래그.
 */
public enum CharacterVisibility {
    PUBLIC,
    PRIVATE,
    PENDING_PUBLIC;

    /** 소유자 외 유저에게 보이는가. */
    public boolean isPubliclyVisible() {
        return this == PUBLIC;
    }
}
