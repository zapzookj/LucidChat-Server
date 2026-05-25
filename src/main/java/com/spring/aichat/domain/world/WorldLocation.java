package com.spring.aichat.domain.world;

import com.spring.aichat.domain.enums.WorldId;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * [V2 Story] World 안의 장소 풀
 *
 * <p>V1의 {@code Location} enum (ENTRANCE/LIVINGROOM/...)은 *전 캐릭터 공유* 디자인이라
 * 세계관별 장소 분리 표현이 불가능했다. V2 Story는 World당 고유 장소 풀을 갖는다.
 *
 * <p>{@code Location} enum 자체는 Theater 호환을 위해 *유지*되며, V2 Story만
 * 이 테이블 기반의 String key를 사용한다.
 *
 * <p>[locationKey 명명 규칙]
 * - World 내 unique한 SCREAMING_SNAKE_CASE
 * - 예: MEDIEVAL_FANTASY/{ENTRANCE, GARDEN, CATHEDRAL, STUDY, BALCONY ...}
 * - 동적 배경 캐싱({@code BackgroundCache}) 시 {@code canonical_key}와 별개로
 *   *정적 장소 식별자*로도 사용 가능.
 *
 * <p>[사용처]
 * - 로비 → StoryCreateFlow 시작 장소 선택 UI (장소 풀 전체 노출)
 * - 디렉터 prompt [2] WORLD 섹션의 Key Locations 주입
 * - V2 ChatRoom.currentUserLocationKey 매핑
 * - 유저 명시 액션(장소 전환) UI의 선택지
 */
@Getter
@NoArgsConstructor
@Entity
@Table(name = "world_locations",
    uniqueConstraints = {
        @UniqueConstraint(name = "uk_world_location_key", columnNames = {"world_id", "location_key"})
    },
    indexes = {
        @Index(name = "idx_world_location_world", columnList = "world_id, display_order")
    })
public class WorldLocation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "world_id", nullable = false, length = 50)
    private WorldId worldId;

    /** World 안에서 unique한 식별자. 예: "CATHEDRAL", "GARDEN" */
    @Column(name = "location_key", nullable = false, length = 50)
    private String locationKey;

    /** UI/디렉터 prompt에 노출되는 한국어 표시명. 예: "루멘 성당" */
    @Column(name = "display_name", nullable = false, length = 100)
    private String displayName;

    /** 장소 분위기 설명 (1~2문장). 디렉터 prompt [2] Key Locations 주입용. */
    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    /**
     * 이 장소의 *기본 BGM 분위기*. World.defaultBgm 위에 장소별 오버라이드.
     * nullable — null이면 World 기본 BGM 사용.
     */
    @Column(name = "default_bgm", length = 30)
    private String defaultBgm;

    /**
     * 이 장소의 *시작 가능 여부*. true면 StoryCreateFlow의 시작 장소 후보에 포함.
     * 예: 신학교 *지하실* 같은 곳은 시작 장소로 부적합 → false.
     */
    @Column(name = "selectable_as_start", nullable = false)
    private boolean selectableAsStart = true;

    /** 정렬 순서 */
    @Column(name = "display_order", nullable = false)
    private int displayOrder = 0;

    /** 활성화 여부 — 비활성 시 UI와 prompt에서 모두 제외. */
    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  Factory & Update
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    public static WorldLocation create(WorldId worldId, String locationKey, String displayName,
                                       String description, String defaultBgm,
                                       boolean selectableAsStart, int displayOrder) {
        WorldLocation loc = new WorldLocation();
        loc.worldId = worldId;
        loc.locationKey = locationKey;
        loc.displayName = displayName;
        loc.description = description;
        loc.defaultBgm = defaultBgm;
        loc.selectableAsStart = selectableAsStart;
        loc.displayOrder = displayOrder;
        return loc;
    }

    public void update(String displayName, String description, String defaultBgm,
                       boolean selectableAsStart, int displayOrder) {
        this.displayName = displayName;
        this.description = description;
        this.defaultBgm = defaultBgm;
        this.selectableAsStart = selectableAsStart;
        this.displayOrder = displayOrder;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    public boolean getSelectableAsStart() {
        return selectableAsStart;
    }
}