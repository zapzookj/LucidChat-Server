package com.spring.aichat.domain.user;

import com.spring.aichat.domain.enums.AuthProvider;
import com.spring.aichat.exception.InsufficientEnergyException;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;

@Getter
@NoArgsConstructor
@Entity
@Table(name = "users", indexes = {
    @Index(name = "idx_user_username", columnList = "username", unique = true),
    @Index(name = "idx_user_email", columnList = "email", unique = true)
})
/**
 * 사용자 엔티티
 * - energy는 대화 가능 횟수(행동력)이며, 별도 스케줄러로 회복 처리
 * - LOCAL: username + password 사용
 * - GOOGLE: email + providerId 기반으로 가입/로그인 처리 (password는 null 가능)
 */
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 로그인 ID(로컬) / 구글 로그인 시엔 내부 식별자로도 사용 */
    @Column(nullable = false, length = 50, unique = true)
    private String username;

    /** 로컬 로그인용 비밀번호 해시(BCrypt). OAuth 유저는 null 가능 */
    @Column(length = 100)
    private String password;

    @Column(nullable = false, length = 50)
    private String nickname; // 캐릭터가 부를 유저의 이름

    /** 구글 로그인 식별에 사용 */
    @Column(length = 120, unique = true)
    private String email;

    @Column(name = "profile_description", columnDefinition = "TEXT")
    private String profileDescription;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AuthProvider provider = AuthProvider.LOCAL;

    /** OAuth provider의 sub(google subject) */
    @Column(length = 120)
    private String providerId;

    /** 권한(확장 대비). 기본 ROLE_USER */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "user_roles", joinColumns = @JoinColumn(name = "user_id"))
    @Column(name = "role", length = 30)
    private Set<String> roles = new HashSet<>();

    @Column(nullable = false)
    private int energy = 100;

    @Column(name = "is_secret_mode", nullable = false)
    private Boolean isSecretMode = false;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void prePersist() {
        this.createdAt = LocalDateTime.now();
        if (this.roles.isEmpty()) this.roles.add("ROLE_USER");
    }

    public static User local(String username, String passwordHash, String nickname, String email) {
        User u = new User();
        u.username = username;
        u.password = passwordHash;
        u.nickname = nickname;
        u.email = email;
        u.provider = AuthProvider.LOCAL;
        return u;
    }

    public static User google(String username, String nickname, String email, String providerId) {
        User u = new User();
        u.username = username;
        u.nickname = nickname;
        u.email = email;
        u.provider = AuthProvider.GOOGLE;
        u.providerId = providerId;
        return u;
    }

    /**
     * 메시지 전송 시 에너지 차감
     */
    public void consumeEnergy(int amount) {
        if (this.energy < amount) {
            throw new InsufficientEnergyException("에너지가 부족합니다. 충전 후 다시 시도해주세요.");
        }
        this.energy -= amount;
    }

    /**
     * 에너지 회복(최대 100 클램핑)
     */
    public void regenEnergy(int amount) {
        this.energy = Math.min(100, this.energy + amount);
    }

    public void updateNickName(String nickname) {
        this.nickname = nickname;
    }

    public void updateProfileDescription(String s) {
        this.profileDescription = s;
    }

    public void updateIsSecretMode(boolean b) {
        this.isSecretMode = b;
    }
}
