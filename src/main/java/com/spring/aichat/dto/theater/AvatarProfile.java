package com.spring.aichat.dto.theater;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * [Phase 5.5-Theater] 아바타 프로필 DTO
 *
 * TheaterState.avatar_profile_json에 직렬화되어 저장.
 *
 * 구조화된 필드 + 자유 텍스트 페르소나를 함께 관리하여
 * LLM이 주인공 아바타를 풍부하게 묘사할 수 있게 돕는다.
 *
 * 모든 필드 nullable — 유저가 일부만 입력해도 허용.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AvatarProfile(
    /** 아바타 이름 (유저 닉네임과 별개) */
    String name,

    /** 성별: MALE / FEMALE / OTHER */
    String gender,

    /** 연령대: TEEN / TWENTIES / THIRTIES / FORTIES / MATURE */
    String ageRange,

    /** 체형: SLIM / AVERAGE / TONED / STURDY / CURVY */
    String physique,

    /** 외형 상세 (자유 텍스트, 150자 이내 권장) */
    String appearance,

    /** 신분/직업 (프리셋 또는 자유 텍스트) */
    String role,

    /** 성격 태그 리스트 (3~5개 권장) */
    List<String> personalityTags,

    /**
     * 관계 시작점
     * FIRST_MEETING / OLD_FRIEND / REUNION / CONTRACT / ARRANGED
     */
    String relationStart,

    /** 백스토리 (자유 텍스트, 300자 이내 권장) */
    String backstory
) {

    /** 빈 프로필 — 유저가 아무 필드도 입력하지 않은 경우 */
    public static AvatarProfile empty() {
        return new AvatarProfile(null, null, null, null, null, null, null, null, null);
    }

    /**
     * LLM 프롬프트에 주입할 간결한 요약문 생성.
     * null/blank 필드는 건너뛴다.
     */
    public String toPromptSummary() {
        StringBuilder sb = new StringBuilder();
        if (name != null && !name.isBlank()) sb.append("이름: ").append(name).append("\n");
        if (gender != null) sb.append("성별: ").append(gender).append("\n");
        if (ageRange != null) sb.append("연령대: ").append(ageRange).append("\n");
        if (physique != null) sb.append("체형: ").append(physique).append("\n");
        if (appearance != null && !appearance.isBlank()) sb.append("외형: ").append(appearance).append("\n");
        if (role != null && !role.isBlank()) sb.append("신분/직업: ").append(role).append("\n");
        if (personalityTags != null && !personalityTags.isEmpty()) {
            sb.append("성격 키워드: ").append(String.join(", ", personalityTags)).append("\n");
        }
        if (relationStart != null) sb.append("관계 시작점: ").append(relationStart).append("\n");
        if (backstory != null && !backstory.isBlank()) sb.append("백스토리: ").append(backstory).append("\n");
        return sb.toString();
    }

    public boolean isBlank() {
        return (name == null || name.isBlank())
            && gender == null && ageRange == null && physique == null
            && (appearance == null || appearance.isBlank())
            && (role == null || role.isBlank())
            && (personalityTags == null || personalityTags.isEmpty())
            && relationStart == null
            && (backstory == null || backstory.isBlank());
    }
}