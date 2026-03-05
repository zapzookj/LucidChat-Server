package com.spring.aichat.dto.user;

/**
 * Phase 5 BM: 구독 + 부스트 모드 필드 추가
 *
 * [추가 필드]
 * - subscriptionTier: 활성 구독 타입 (null = 미구독)
 * - boostMode: 부스트 모드 활성 여부
 * - freeEnergyMax: freeEnergy 상한 (비구독: 30, 구독: 100)
 */
public record UserResponse(
    long id,
    String username,
    String nickname,
    String email,
    String profileDescription,
    Boolean isSecretMode,
    int energy,
    int freeEnergy,
    int paidEnergy,
    int freeEnergyMax,
    boolean isAdultVerified,
    String subscriptionTier,
    boolean boostMode
) {
    /** Phase 4 이하 호환 생성자 (제거 예정) */
    public UserResponse(long id, String username, String nickname, String email,
                        String profileDescription, Boolean isSecretMode, int energy) {
        this(id, username, nickname, email, profileDescription, isSecretMode,
            energy, energy, 0, 30, false, null, false);
    }
}