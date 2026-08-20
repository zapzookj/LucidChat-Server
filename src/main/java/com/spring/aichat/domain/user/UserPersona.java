package com.spring.aichat.domain.user;

import com.spring.aichat.domain.enums.CharacterGender;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * [2026-08-15 블록 B 페르소나 개편] 유저 페르소나 — 활성 프로필 + 저장 카드 (V21→V25).
 *
 * <p>개념 역전(docs/14 #4): 유저당 1행의 <b>항상 존재하는 활성 프로필</b>({@code profile=true},
 * 최초 접근 시 lazy 생성)과 그 스냅샷인 <b>저장 카드</b>({@code profile=false}, 세이브 슬롯 문법 —
 * 저장=프로필→카드 복사, 로드=카드→프로필 복사)로 구성된다. 슬롯은 무료 3/구독 10.
 *
 * <p>스탯은 인식 렌즈 5축(매력/친근함/듬직함/카리스마/신비) — 유저의 능력치가 아니라
 * <b>"캐릭터가 유저를 어떻게 인식하는가"</b>다. 축당 0~10·총점 25(전 유저 공통, 서비스 검증),
 * 중립 베이스라인은 전 축 5(총점 정확 소진 — 재배분은 제로섬). 극장 아바타 5축(TheaterState)과는
 * 이름·체계 모두 별개다.
 *
 * <p>방 생성 시 <b>스냅샷 복사</b>(room.userPersona + personaStatsJson + personaGender) —
 * 프로필 수정이 진행 중 방을 소급 변경하지 않는다(중도 교체 불가 현행 유지).
 *
 * <p>나이는 자유 입력(미성년 SFW 정당) — 시크릿 활성만 19+ 하드 게이트(SecretModeService).
 */
@Getter
@NoArgsConstructor
@Entity
@Table(name = "user_personas", indexes = {
    @Index(name = "idx_user_persona_owner", columnList = "user_id")
})
public class UserPersona {

    /** 카드 슬롯 상한 — 미구독 (프로필 행은 카운트 제외). */
    public static final int FREE_SLOT_LIMIT = 3;
    /** 카드 슬롯 상한 — 구독(전 티어 공통). */
    public static final int SUBSCRIBER_SLOT_LIMIT = 10;

    /** 렌즈 축당 상한. */
    public static final int LENS_AXIS_MAX = 10;
    /** 렌즈 총점 상한 — "다 높게"가 불가능해야 빌드 선택이 서사가 된다(긴장쌍 설계). */
    public static final int LENS_TOTAL_MAX = 25;
    /** 중립 인식 베이스라인 — 부트스트랩 프로필·V25 리셋 카드 공용. */
    public static final int LENS_DEFAULT = 5;

    /** 시크릿 활성 하드 게이트 기준 나이. */
    public static final int ADULT_AGE = 19;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "persona_id")
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** true=활성 프로필(유저당 1행, 파셜 유니크), false=저장 카드. */
    @Column(name = "is_profile", nullable = false)
    private boolean profile;

    @Column(name = "name", nullable = false, length = 30)
    private String name;

    /** 자유 입력 나이 — null=미설정. 시크릿 활성 게이트만 19+ 요구. */
    @Column(name = "age")
    private Integer age;

    /** 씬 렌더 유저 표현 기준 — null=MALE(기존 폴백과 동일). */
    @Enumerated(EnumType.STRING)
    @Column(name = "gender", length = 10)
    private CharacterGender gender;

    /** 소개(자유 서술) — 프로필은 빈 문자열 허용(부트스트랩 직후). */
    @Column(name = "persona_text", nullable = false, columnDefinition = "TEXT")
    private String personaText;

    @Column(name = "lens_allure", nullable = false)
    private int lensAllure;
    @Column(name = "lens_friendliness", nullable = false)
    private int lensFriendliness;
    @Column(name = "lens_trust", nullable = false)
    private int lensTrust;
    @Column(name = "lens_charisma", nullable = false)
    private int lensCharisma;
    @Column(name = "lens_mystique", nullable = false)
    private int lensMystique;

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

    /** 활성 프로필 부트스트랩 — 중립 렌즈(전 축 5), 나이 미설정, 소개 공란. */
    public static UserPersona createProfile(Long userId, String name) {
        UserPersona p = new UserPersona();
        p.userId = userId;
        p.profile = true;
        p.name = name;
        p.personaText = "";
        p.lensAllure = LENS_DEFAULT;
        p.lensFriendliness = LENS_DEFAULT;
        p.lensTrust = LENS_DEFAULT;
        p.lensCharisma = LENS_DEFAULT;
        p.lensMystique = LENS_DEFAULT;
        return p;
    }

    /** 카드 저장 — 프로필의 스냅샷 사본(세이브 슬롯). */
    public static UserPersona snapshotOf(UserPersona source) {
        UserPersona card = new UserPersona();
        card.userId = source.userId;
        card.profile = false;
        card.copyContentFrom(source);
        return card;
    }

    /** 프로필 편집(모달 저장). */
    public void applyProfileUpdate(String name, Integer age, CharacterGender gender, String personaText,
                                   int allure, int friendliness, int trust, int charisma, int mystique) {
        this.name = name;
        this.age = age;
        this.gender = gender;
        this.personaText = personaText;
        this.lensAllure = allure;
        this.lensFriendliness = friendliness;
        this.lensTrust = trust;
        this.lensCharisma = charisma;
        this.lensMystique = mystique;
    }

    /** 카드 로드 — 카드 내용을 프로필로 복사(카드는 보존). */
    public void copyContentFrom(UserPersona source) {
        this.name = source.name;
        this.age = source.age;
        this.gender = source.gender;
        this.personaText = source.personaText;
        this.lensAllure = source.lensAllure;
        this.lensFriendliness = source.lensFriendliness;
        this.lensTrust = source.lensTrust;
        this.lensCharisma = source.lensCharisma;
        this.lensMystique = source.lensMystique;
    }

    public boolean isOwnedBy(Long userId) {
        return this.userId != null && this.userId.equals(userId);
    }

    /** 시크릿 활성 게이트 — 나이 미설정(null)도 차단(하드 게이트, 우회 불가). */
    public boolean isAdultPersona() {
        return age != null && age >= ADULT_AGE;
    }

    public CharacterGender getGenderOrDefault() {
        return gender != null ? gender : CharacterGender.MALE;
    }

    /** 방 스냅샷용 소개 — 공란이면 null(getEffectivePersona의 profileDescription 폴백 유지). */
    public String personaTextOrNull() {
        return personaText != null && !personaText.isBlank() ? personaText : null;
    }

    /** 방 스냅샷용 렌즈 JSON — {"allure":n,...} (렌즈 프롬프트 블록의 단일 소스, V25 키). */
    public String statsJson() {
        return "{\"allure\":%d,\"friendliness\":%d,\"trust\":%d,\"charisma\":%d,\"mystique\":%d}"
            .formatted(lensAllure, lensFriendliness, lensTrust, lensCharisma, lensMystique);
    }

    public int totalLensPoints() {
        return lensAllure + lensFriendliness + lensTrust + lensCharisma + lensMystique;
    }

    public int maxSingleLens() {
        return Math.max(lensAllure, Math.max(lensFriendliness,
            Math.max(lensTrust, Math.max(lensCharisma, lensMystique))));
    }
}
