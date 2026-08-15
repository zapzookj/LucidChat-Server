package com.spring.aichat.domain.user;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/** [블록 B 페르소나] 활성 프로필(유저당 1행) + 저장 카드 Repository. */
public interface UserPersonaRepository extends JpaRepository<UserPersona, Long> {

    Optional<UserPersona> findByUserIdAndProfileTrue(Long userId);

    /**
     * [리뷰픽스] 프로필 부트스트랩 — 동시 첫 요청 레이스에서 파셜 유니크 위반이 트랜잭션을
     * abort시키지 않도록 ON CONFLICT DO NOTHING upsert(패자는 no-op 후 재조회). 값은
     * {@link UserPersona#createProfile}과 동기(렌즈 중립 5·나이 미설정·소개 공란).
     */
    @Modifying
    @Query(value = """
        INSERT INTO user_personas (user_id, is_profile, name, age, gender, persona_text,
            lens_allure, lens_friendliness, lens_trust, lens_charisma, lens_mystique,
            created_at, updated_at)
        VALUES (:userId, TRUE, :name, NULL, NULL, '', 5, 5, 5, 5, 5, NOW(), NOW())
        ON CONFLICT (user_id) WHERE is_profile DO NOTHING
        """, nativeQuery = true)
    int insertProfileIfAbsent(@Param("userId") Long userId, @Param("name") String name);

    /**
     * [리뷰픽스] 카드 저장 직렬화 앵커 — 프로필 행(유저당 1행 보장) 비관 락으로
     * 슬롯 상한 check-then-insert 레이스 차단.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from UserPersona p where p.userId = :userId and p.profile = true")
    Optional<UserPersona> findByUserIdAndProfileTrueForUpdate(@Param("userId") Long userId);

    List<UserPersona> findByUserIdAndProfileFalseOrderByUpdatedAtDesc(Long userId);

    Optional<UserPersona> findByIdAndUserIdAndProfileFalse(Long id, Long userId);

    long countByUserIdAndProfileFalse(Long userId);
}
