package com.spring.aichat.domain.character;

import com.spring.aichat.domain.enums.DayPart;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * [V2 Story] 캐릭터별 시간대-장소 확률 매핑
 *
 * <p>{@code (character_id, time_of_day, location_key)} 조합으로 캐릭터의 일과를
 * *확률적*으로 정의한다. 결정론적 루틴(Theater 패턴)이 아닌 *디렉터 자유의
 * 가이드라인*. 자유도와 전략적 유저 행동의 절충안.
 *
 * <p>[작동 흐름]
 * 1. V2 라우팅 시 백엔드가 {@code chars_at(characterId, currentTimeOfDay)}로
 *    *확률 기반 위치 추정*. 동일 시간대 여러 행이 있을 수 있고, probability 가중치로
 *    선택.
 * 2. 추정 결과는 {@link com.spring.aichat.domain.chat.CharacterPresence}에 반영.
 * 3. 디렉터 prompt에는 *루틴 데이터 직접 주입하지 않음* — 결과만 [3] PRESENT SCENE
 *    /[6] OFFSCREEN CHARACTERS에 반영. 디렉터가 *상황상 어색*하다 판단하면
 *    자율 묘사로 재배치 가능.
 *
 * <p>[캐릭터 마스터 단위]
 * room 무관 — 모든 유저 세션에서 동일한 루틴 적용. CharacterSeeder 또는
 * 별도 시드 properties로 콘텐츠 주입.
 *
 * <p>[빈 행 의미]
 * 한 캐릭터의 한 시간대에 매핑된 행이 없으면 *특정 장소 미정* = 디렉터에 위임
 * (자유 묘사). 시드 시 의도적으로 비워둘 수 있다.
 *
 * <p>예시 (Claire):
 * <pre>
 *   (Claire, MORNING,   CATHEDRAL,    90)
 *   (Claire, NOON,      CATHEDRAL,    60)
 *   (Claire, NOON,      DINING_HALL,  30)
 *   (Claire, AFTERNOON, GARDEN,       40)
 *   (Claire, EVENING,   BALCONY,      30)
 *   (Claire, NIGHT,     BEDROOM,      80)
 * </pre>
 */
@Getter
@NoArgsConstructor
@Entity
@Table(name = "character_routines",
    uniqueConstraints = {
        @UniqueConstraint(name = "uk_routine_char_time_loc",
            columnNames = {"character_id", "time_of_day", "location_key"})
    },
    indexes = {
        @Index(name = "idx_routine_char_time", columnList = "character_id, time_of_day")
    })
public class CharacterRoutine {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "character_id", nullable = false)
    private Long characterId;

    @Enumerated(EnumType.STRING)
    @Column(name = "time_of_day", nullable = false, length = 20)
    private DayPart timeOfDay;

    /**
     * {@code WorldLocation.locationKey} 참조. FK 미설정 — 캐릭터/장소 시드 순서
     * 의존성 회피. 정합성은 시드/검증 단계에서.
     */
    @Column(name = "location_key", nullable = false, length = 50)
    private String locationKey;

    /** 0 ~ 100. 다른 행과 *상대 가중치*로도 사용 가능 (정규화 X). */
    @Column(name = "probability", nullable = false)
    private int probability;

    /** 자유 메모 (시드 작성자용). 디렉터 prompt에는 미주입. */
    @Column(name = "notes", length = 200)
    private String notes;

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  Factory
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    public static CharacterRoutine create(Long characterId, DayPart timeOfDay,
                                          String locationKey, int probability, String notes) {
        CharacterRoutine r = new CharacterRoutine();
        r.characterId = characterId;
        r.timeOfDay = timeOfDay;
        r.locationKey = locationKey;
        r.probability = Math.max(0, Math.min(100, probability));
        r.notes = notes;
        return r;
    }

    public void updateProbability(int probability) {
        this.probability = Math.max(0, Math.min(100, probability));
    }
}