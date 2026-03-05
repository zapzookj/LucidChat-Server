package com.spring.aichat.domain.payment;

import com.spring.aichat.domain.character.Character;
import com.spring.aichat.domain.user.User;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 캐릭터별 시크릿 모드 영구 해금
 *
 * [설계]
 * - 유저-캐릭터 쌍에 대한 영구 해금 레코드
 * - 14,900원 결제 시 생성
 * - 한번 해금하면 삭제 불가 (환불 시에만 soft-delete)
 */
@Getter
@NoArgsConstructor
@Entity
@Table(name = "user_secret_unlocks",
    uniqueConstraints = @UniqueConstraint(
        name = "uk_user_character_unlock",
        columnNames = {"user_id", "character_id"}
    ),
    indexes = @Index(name = "idx_unlock_user", columnList = "user_id")
)
public class UserSecretUnlock {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "character_id", nullable = false)
    private Character character;

    @Column(name = "unlocked_at", nullable = false)
    private LocalDateTime unlockedAt;

    @Column(name = "merchant_uid", length = 50)
    private String merchantUid;

    public static UserSecretUnlock create(User user, Character character, String merchantUid) {
        UserSecretUnlock unlock = new UserSecretUnlock();
        unlock.user = user;
        unlock.character = character;
        unlock.unlockedAt = LocalDateTime.now();
        unlock.merchantUid = merchantUid;
        return unlock;
    }
}