package com.spring.aichat.domain.enums;

/**
 * [Phase 5.5-Theater] Theater 모드의 세계관 식별자
 *
 * 세계관의 상세 메타데이터(display_name, tagline, hero_image 등)는
 * `worlds` 테이블의 마스터 데이터로 관리하지만, 코드 레벨에서 타입 안전성을
 * 확보하기 위해 enum도 함께 정의한다.
 *
 * 새 세계관 추가 시:
 *   1. 이 enum에 항목 추가
 *   2. worlds 테이블에 row 추가 (DB 마이그레이션)
 *   3. 소속 캐릭터들의 world_id 업데이트
 *
 * [초기 세계관]
 * - MEDIEVAL_FANTASY: 아이리 (저택, 고딕)
 * - ORIENTAL_FANTASY: 연화 (무협, 구미호)
 * - MODERN_KOREA:     서태리, 백루나 (캠퍼스, 도시)
 */
public enum WorldId {

    MEDIEVAL_FANTASY("중세 판타지", "고딕 저택에서 피어나는 로맨스"),
    ORIENTAL_FANTASY("동양 판타지", "달빛 아래 구미호의 유혹"),
    MODERN_KOREA("현대 한국", "도시의 낮과 밤, 엇갈리는 일상");

    private final String displayName;
    private final String defaultTagline;

    WorldId(String displayName, String defaultTagline) {
        this.displayName = displayName;
        this.defaultTagline = defaultTagline;
    }

    public String getDisplayName() { return displayName; }
    public String getDefaultTagline() { return defaultTagline; }

    /**
     * 안전 파싱 — 알 수 없는 값이면 null 반환
     */
    public static WorldId fromStringOrNull(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return WorldId.valueOf(value.toUpperCase().trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}