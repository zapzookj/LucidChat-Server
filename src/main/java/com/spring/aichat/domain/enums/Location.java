package com.spring.aichat.domain.enums;

/**
 * 씬의 장소 (배경 이미지 매핑)
 *
 * [Phase 4] 시각 엔진 — 동적 배경 전환
 */
public enum Location {
    LIVINGROOM,   // 저택 내부 거실
    BALCONY,      // 저택 내부 발코니
    STUDY,        // 저택 내부 서재
    BATHROOM,     // 저택 내부 욕실
    GARDEN,       // 저택 내부 정원
    KITCHEN,      // 저택 내부 주방
    BEDROOM,      // 저택 내부 침실
    ENTRANCE,     // 저택 내부 현관
    FOREST,       // 숲 (연화 기본)
    BEACH,        // 저택 외부 해변
    DOWNTOWN,     // 저택 외부 번화가
    BAR,           // 저택 외부 바(술집)
    CLUB_ROOM,     // 동아리실 (서태리 기본)
    CONVENIENCE_STORE, // 편의점 (백루나 기본)

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  [안건 11 (a) · docs/19_assets/decisions_confirmed.md §A #11 · 결함 E-3.①.1~①.12]
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  application-characters.yml의 default-location/baseLocations가 참조하던 '유령 키' 5종.
    //  enum에 없어 ChatRoom.parseLocationOrDefault가 전부 ENTRANCE(저택 현관)로 삼켜, 클레어·
    //  로제타·강채린·에델·류설아 5명의 V1 SANDBOX/THEATER 방이 세계관과 무관한 곳에서 시작했다.
    //  종원 확정: (a) enum 확장 + V2 어휘 흡수. '옛 사당'은 V2(application-v2.yml:114)가 이미
    //  쓰는 ANCIENT_SHRINE으로 어휘 통일한다(V1/V2 장소 어휘 통합의 첫 케이스).
    //  ⚠ current_location은 varchar(20)이고 CHECK 제약이 없다(Flyway 미참조 = Hibernate 생성)
    //     → 최장 신규값 ANCIENT_SHRINE(14자)이 들어가므로 마이그레이션 불요.
    CATHEDRAL,     // 대성당 (클레어 기본)
    TERRACE,       // 테라스 (로제타 기본 — 마법학원)
    STREET,        // 골목길·동네 거리 (강채린 기본)
    LIBRARY,       // 도서관 (에델 기본 — IllustrationPromptAssembler.LOCATION_PROMPTS와 정합)
    ANCIENT_SHRINE, // 옛 사당 (류설아 기본 — V2 ORIENTAL_FANTASY와 같은 어휘)
}