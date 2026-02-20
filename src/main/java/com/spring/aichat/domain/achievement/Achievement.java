package com.spring.aichat.domain.achievement;

import com.spring.aichat.domain.user.User;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 업적(훈장) 엔티티
 *
 * [Phase 4.4] Easter Egg & Achievement System
 *
 * type:
 *   ENDING  — 엔딩 도달 (HAPPY_ENDING, BAD_ENDING)
 *   SPECIAL — 이스터에그 발견 (STOCKHOLM, DRUNK, FOURTH_WALL, MACHINE_REBELLION, INVISIBLE_MAN)
 *
 * (user_id, code) 유니크 → 동일 업적 중복 획득 방지
 */
@Entity
@Table(
    name = "achievements",
    uniqueConstraints = @UniqueConstraint(
        name = "uk_achievement_user_code",
        columnNames = {"user_id", "code"}
    ),
    indexes = {
        @Index(name = "idx_achievement_user", columnList = "user_id"),
        @Index(name = "idx_achievement_type", columnList = "type")
    }
)
@Getter
@NoArgsConstructor
public class Achievement {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    /** 업적 유형: ENDING | SPECIAL */
    @Column(nullable = false, length = 20)
    private String type;

    /** 고유 코드: HAPPY_ENDING, BAD_ENDING, STOCKHOLM, DRUNK 등 */
    @Column(nullable = false, length = 50)
    private String code;

    /** 영문 타이틀 */
    @Column(nullable = false, length = 100)
    private String title;

    /** 한글 타이틀 */
    @Column(nullable = false, length = 100)
    private String titleKo;

    /** 업적 설명 */
    @Column(length = 300)
    private String description;

    /** 아이콘 (emoji) */
    @Column(length = 10)
    private String icon;

    @Column(name = "unlocked_at", nullable = false, updatable = false)
    private LocalDateTime unlockedAt;

    @PrePersist
    void prePersist() {
        this.unlockedAt = LocalDateTime.now();
    }

    // ── Factory Methods ──

    public static Achievement ending(User user, String endingType) {
        Achievement a = new Achievement();
        a.user = user;
        a.type = "ENDING";
        a.code = endingType + "_ENDING";  // HAPPY_ENDING or BAD_ENDING
        a.title = endingType.equals("HAPPY") ? "Happily Ever After" : "Bittersweet Farewell";
        a.titleKo = endingType.equals("HAPPY") ? "해피엔딩" : "배드엔딩";
        a.description = endingType.equals("HAPPY")
            ? "아이리와 행복한 결말을 맞이했다."
            : "아이리와 이별의 결말을 맞이했다.";
        a.icon = endingType.equals("HAPPY") ? "💕" : "💔";
        return a;
    }

    public static Achievement easterEgg(User user, com.spring.aichat.domain.enums.EasterEggType eggType) {
        Achievement a = new Achievement();
        a.user = user;
        a.type = "SPECIAL";
        a.code = eggType.name();
        a.title = eggType.getTitle();
        a.titleKo = eggType.getTitleKo();
        a.description = eggType.getDescription();
        a.icon = eggType.getIcon();
        return a;
    }
}