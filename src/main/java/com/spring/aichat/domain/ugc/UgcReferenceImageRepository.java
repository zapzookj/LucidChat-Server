package com.spring.aichat.domain.ugc;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface UgcReferenceImageRepository extends JpaRepository<UgcReferenceImage, Long> {

    Optional<UgcReferenceImage> findByIdAndUserId(Long id, Long userId);

    /** 업로드 남용 방지 — 최근 구간 업로드 수. */
    long countByUserIdAndCreatedAtAfter(Long userId, LocalDateTime since);

    /**
     * 보관 정책 정리 대상 — 생성 시각이 cutoff 이전이고, 아직 지워지지 않았고, 어떤 캐릭터에도 연결되지 않았으며,
     * 진행 중인 잡도 쓰고 있지 않은 그림. (완성 캐릭터의 그림은 캐릭터 삭제 때 함께 정리된다.)
     */
    @Query("""
        select r from UgcReferenceImage r
        where r.deletedAt is null and r.createdAt < :cutoff
          and not exists (select 1 from Character c where c.referenceImageId = r.id)
          and not exists (select 1 from CharacterCreationJob j where j.referenceImageId = r.id
                          and j.status not in :terminal)
        """)
    List<UgcReferenceImage> findOrphansBefore(@Param("cutoff") LocalDateTime cutoff,
                                              @Param("terminal") List<CreationJobStatus> terminal);
}
