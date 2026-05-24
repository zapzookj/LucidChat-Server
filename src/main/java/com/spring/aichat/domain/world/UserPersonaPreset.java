package com.spring.aichat.domain.world;

import com.spring.aichat.domain.enums.WorldId;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * [V2 Story] World 종속 사전 정의 페르소나
 *
 * <p>StoryCreateFlow의 페르소나 단계에서 *무료 옵션*으로 노출되는 페르소나 풀.
 * 유저가 자유 페르소나 BM 미보유 시 이 풀 중에서만 선택 가능.
 *
 * <p>[World 종속의 이유]
 * 페르소나는 *세계관 안에서의 정체성*이다. "신학교 신입생"은 MEDIEVAL_FANTASY/
 * FANTASY_ACADEMY에서만 의미를 가지며, "20대 직장인"은 MODERN_KOREA에서만 의미를
 * 가진다. 글로벌 페르소나는 어느 세계관에서도 *어색한 위화감*을 만든다.
 *
 * <p>[자유 페르소나 BM과의 관계]
 * 자유 페르소나 BM은 *글로벌 영구* (1회 결제 → 모든 World에서 자유 작성 가능).
 * 이 사전 정의 페르소나는 BM 미보유 유저용 *기본 옵션*이며, BM 보유 유저도
 * 빠른 시작을 원하면 사용 가능.
 *
 * <p>[World당 콘텐츠 권장량]
 * 3~5개. 너무 많으면 선택 마비, 너무 적으면 다양성 결여. MVP는 World당 3개.
 */
@Getter
@NoArgsConstructor
@Entity
@Table(name = "user_persona_presets",
    uniqueConstraints = {
        @UniqueConstraint(name = "uk_persona_preset_world_key", columnNames = {"world_id", "preset_key"})
    },
    indexes = {
        @Index(name = "idx_persona_preset_world", columnList = "world_id, display_order")
    })
public class UserPersonaPreset {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "world_id", nullable = false, length = 50)
    private WorldId worldId;

    /** World 안에서 unique. 예: "NEW_LORD", "NEW_SEMINARIAN" */
    @Column(name = "preset_key", nullable = false, length = 50)
    private String presetKey;

    /** UI에 노출되는 짧은 이름. 예: "새 영주", "신학교 신입생" */
    @Column(name = "name", nullable = false, length = 50)
    private String name;

    /**
     * 페르소나 본문 — 디렉터 prompt [7] USER ACTOR PERSONA에 주입.
     * 2~4문장. 출신/배경/성격/이 세계와의 관계.
     */
    @Column(name = "description", nullable = false, columnDefinition = "TEXT")
    private String description;

    /** 디폴트 닉네임 호칭 (디렉터가 캐릭터 입을 빌려 부를 때). 예: "주인님", "수도원장님" */
    @Column(name = "default_nickname", length = 30)
    private String defaultNickname;

    /** 권장 시작 장소 (WorldLocation.locationKey 참조). nullable. */
    @Column(name = "suggested_start_location_key", length = 50)
    private String suggestedStartLocationKey;

    /** 정렬 순서 */
    @Column(name = "display_order", nullable = false)
    private int displayOrder = 0;

    /** 활성화 여부 */
    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  Factory & Update
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    public static UserPersonaPreset create(WorldId worldId, String presetKey, String name,
                                           String description, String defaultNickname,
                                           String suggestedStartLocationKey, int displayOrder) {
        UserPersonaPreset p = new UserPersonaPreset();
        p.worldId = worldId;
        p.presetKey = presetKey;
        p.name = name;
        p.description = description;
        p.defaultNickname = defaultNickname;
        p.suggestedStartLocationKey = suggestedStartLocationKey;
        p.displayOrder = displayOrder;
        return p;
    }

    public void update(String name, String description, String defaultNickname,
                       String suggestedStartLocationKey, int displayOrder) {
        this.name = name;
        this.description = description;
        this.defaultNickname = defaultNickname;
        this.suggestedStartLocationKey = suggestedStartLocationKey;
        this.displayOrder = displayOrder;
    }

    public void setActive(boolean active) {
        this.active = active;
    }
}