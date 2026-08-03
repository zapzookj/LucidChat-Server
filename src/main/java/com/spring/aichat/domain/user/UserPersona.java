package com.spring.aichat.domain.user;

import com.spring.aichat.domain.enums.CharacterGender;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * [2026-08-04 페르소나 풀 패키지] 유저 페르소나 카드 — 재사용 자산 (V21).
 *
 * <p>Theater에서 검증된 재미의 제품화: 페르소나 본문(자기인식) + 5축 스탯(객관 현실).
 * 방 생성 시 <b>스냅샷 복사</b>(room.userPersona + personaStatsJson + personaGender) —
 * 카드 수정이 진행 중 방을 소급 변경하지 않는다(Theater avatar 관례).
 *
 * <p>스탯 체계는 Theater {@code AvatarStat}(charm/wit/boldness/intellect/empathy) 공유 —
 * 분배 포인트 게이트는 서비스 계층(구독 티어, TheaterLobbyService 정책과 동일 규칙).
 */
@Getter
@NoArgsConstructor
@Entity
@Table(name = "user_personas", indexes = {
    @Index(name = "idx_user_persona_owner", columnList = "user_id")
})
public class UserPersona {

    /** 유저당 보관 카드 상한 (v1 전 티어 공통 — BM 세분화는 종원 결정 대기). */
    public static final int MAX_CARDS_PER_USER = 10;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "persona_id")
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "name", nullable = false, length = 30)
    private String name;

    /** 씬 렌더 유저 표현 기준 — null=MALE(기존 폴백과 동일). */
    @Enumerated(EnumType.STRING)
    @Column(name = "gender", length = 10)
    private CharacterGender gender;

    @Column(name = "persona_text", nullable = false, columnDefinition = "TEXT")
    private String personaText;

    @Column(name = "stat_charm", nullable = false)
    private int statCharm;
    @Column(name = "stat_wit", nullable = false)
    private int statWit;
    @Column(name = "stat_boldness", nullable = false)
    private int statBoldness;
    @Column(name = "stat_intellect", nullable = false)
    private int statIntellect;
    @Column(name = "stat_empathy", nullable = false)
    private int statEmpathy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    void prePersist() {
        LocalDateTime now = LocalDateTime.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    void preUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    public static UserPersona create(Long userId, String name, CharacterGender gender,
                                     String personaText, int charm, int wit, int boldness,
                                     int intellect, int empathy) {
        UserPersona p = new UserPersona();
        p.userId = userId;
        p.name = name;
        p.gender = gender;
        p.personaText = personaText;
        p.statCharm = charm;
        p.statWit = wit;
        p.statBoldness = boldness;
        p.statIntellect = intellect;
        p.statEmpathy = empathy;
        return p;
    }

    public void update(String name, CharacterGender gender, String personaText,
                       int charm, int wit, int boldness, int intellect, int empathy) {
        this.name = name;
        this.gender = gender;
        this.personaText = personaText;
        this.statCharm = charm;
        this.statWit = wit;
        this.statBoldness = boldness;
        this.statIntellect = intellect;
        this.statEmpathy = empathy;
    }

    public boolean isOwnedBy(Long userId) {
        return this.userId != null && this.userId.equals(userId);
    }

    public CharacterGender getGenderOrDefault() {
        return gender != null ? gender : CharacterGender.MALE;
    }

    /** 방 스냅샷용 스탯 JSON — {"charm":n,...} (프롬프트 Hybrid 블록의 단일 소스). */
    public String statsJson() {
        return "{\"charm\":%d,\"wit\":%d,\"boldness\":%d,\"intellect\":%d,\"empathy\":%d}"
            .formatted(statCharm, statWit, statBoldness, statIntellect, statEmpathy);
    }

    public int totalPoints() {
        return statCharm + statWit + statBoldness + statIntellect + statEmpathy;
    }

    public int maxSingleStat() {
        return Math.max(statCharm, Math.max(statWit,
            Math.max(statBoldness, Math.max(statIntellect, statEmpathy))));
    }
}
