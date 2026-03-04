package com.spring.aichat.domain.user;

import com.spring.aichat.domain.enums.AuthProvider;
import com.spring.aichat.exception.InsufficientEnergyException;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;

@Getter @NoArgsConstructor
@Entity
@Table(name = "users", indexes = {
    @Index(name = "idx_user_username", columnList = "username", unique = true),
    @Index(name = "idx_user_email", columnList = "email", unique = true),
    @Index(name = "idx_user_ci_hash", columnList = "ci_hash", unique = true)
})
public class User {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 50, unique = true)
    private String username;

    @Column(length = 100)
    private String password;

    @Column(nullable = false, length = 50)
    private String nickname;

    @Column(length = 120, unique = true)
    private String email;

    @Column(name = "profile_description", columnDefinition = "TEXT")
    private String profileDescription;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AuthProvider provider = AuthProvider.LOCAL;

    @Column(length = 120)
    private String providerId;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "user_roles", joinColumns = @JoinColumn(name = "user_id"))
    @Column(name = "role", length = 30)
    private Set<String> roles = new HashSet<>();

    /** free energy: auto-regen, max 30, +1 per 10min */
    @Column(name = "free_energy", nullable = false)
    private int freeEnergy = 30;

    /** paid energy: charged via payment, no cap */
    @Column(name = "paid_energy", nullable = false)
    private int paidEnergy = 0;

    @Column(name = "is_adult", nullable = false)
    private Boolean isAdult = false;

    @Column(name = "ci_hash", length = 64, unique = true)
    private String ciHash;

    @Column(name = "adult_verified_at")
    private LocalDateTime adultVerifiedAt;

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
        u.username = username; u.password = passwordHash;
        u.nickname = nickname; u.email = email;
        u.provider = AuthProvider.LOCAL;
        return u;
    }

    public static User google(String username, String nickname, String email, String providerId) {
        User u = new User();
        u.username = username; u.nickname = nickname;
        u.email = email; u.provider = AuthProvider.GOOGLE; u.providerId = providerId;
        return u;
    }

    public int getEnergy() { return this.freeEnergy + this.paidEnergy; }

    public void consumeEnergy(int amount) {
        int total = this.freeEnergy + this.paidEnergy;
        if (total < amount) {
            throw new InsufficientEnergyException(
                "Energy insufficient. (have: " + total + ", need: " + amount + ")");
        }
        if (this.freeEnergy >= amount) {
            this.freeEnergy -= amount;
        } else {
            int remaining = amount - this.freeEnergy;
            this.freeEnergy = 0;
            this.paidEnergy -= remaining;
        }
    }

    public void regenEnergy(int amount) {
        this.freeEnergy = Math.min(30, this.freeEnergy + amount);
    }

    public void chargePaidEnergy(int amount) {
        if (amount <= 0) throw new IllegalArgumentException("amount must be > 0");
        this.paidEnergy += amount;
    }

    public void completeAdultVerification(String ciHash) {
        this.isAdult = true;
        this.ciHash = ciHash;
        this.adultVerifiedAt = LocalDateTime.now();
    }

    public void updateNickName(String nickname) { this.nickname = nickname; }
    public void updateProfileDescription(String s) { this.profileDescription = s; }
    public void updateIsSecretMode(boolean b) { this.isSecretMode = b; }
}