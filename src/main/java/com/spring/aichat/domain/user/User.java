package com.spring.aichat.domain.user;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
@Entity
@Table(name = "users", indexes = {
    @Index(name = "idx_user_username", columnList = "username", unique = true)
})
/**
 * 사용자 엔티티
 * - energy는 대화 가능 횟수(행동력)이며, 별도 스케줄러로 회복 처리
 */
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 50, unique = true)
    private String username;

    @Column(nullable = false, length = 50)
    private String nickname; // 캐릭터가 부를 유저의 이름

    @Column(nullable = false)
    private int energy = 100;

    public User(String username, String nickname) {
        this.username = username;
        this.nickname = nickname;
        this.energy = 100;
    }

    /**
     * 메시지 전송 시 에너지 차감
     */
    public void consumeEnergy(int amount) {
        if (this.energy < amount) {
//            throw new InsufficientEnergyException("에너지가 부족합니다. 충전 후 다시 시도해주세요.");
        }
        this.energy -= amount;
    }

    /**
     * 에너지 회복(최대 100 클램핑)
     */
    public void regenEnergy(int amount) {
        this.energy = Math.min(100, this.energy + amount);
    }
}
