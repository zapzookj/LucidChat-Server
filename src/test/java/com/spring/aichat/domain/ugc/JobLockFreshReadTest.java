package com.spring.aichat.domain.ugc;

import com.spring.aichat.domain.user.EnergySplit;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.LockModeType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.orm.jpa.EntityManagerHolder;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [2026-11-09 핫픽스 · 잡 51] open-in-view 요청 스레드 재현 — 잡을 먼저 적재한 EntityManager가 스레드에 묶인 채로,
 * 다른 스레드가 커밋한 변경을 락 조회가 보는가.
 */
@DataJpaTest(showSql = false, properties = {"spring.flyway.enabled=false", "spring.jpa.hibernate.ddl-auto=create-drop",
    "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect"})
@ContextConfiguration(classes = JobLockFreshReadTest.Config.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class JobLockFreshReadTest {
    @Configuration @EntityScan("com.spring.aichat.domain")
    @EnableJpaRepositories(basePackageClasses = CharacterCreationJobRepository.class)
    static class Config {}

    @Autowired CharacterCreationJobRepository jobs;
    @Autowired EntityManagerFactory emf;
    @Autowired PlatformTransactionManager txm;

    @Test void lockReadSeesWhatAnotherThreadCommittedAfterTheEntityWasLoaded() throws Exception {
        TransactionTemplate tx = new TransactionTemplate(txm);
        Long id = tx.execute(s -> jobs.save(CharacterCreationJob.start(1L, null, "성격 이야기", EnergySplit.of(0, 0))).getId());

        EntityManager osiv = emf.createEntityManager();
        TransactionSynchronizationManager.bindResource(emf, new EntityManagerHolder(osiv));
        var other = Executors.newSingleThreadExecutor();
        try {
            // 웹훅 요청 스레드: TX 밖에서 잡을 먼저 읽는다(onCutoutResult 첫 줄의 findById)
            assertThat(jobs.findById(id).orElseThrow().getExternalJobsJson()).isNull();

            // 그 사이 제출 루프(다른 스레드)가 누끼 요청 번호를 기록하고 커밋
            other.submit(() -> new TransactionTemplate(txm).execute(s -> {
                jobs.findByIdForUpdate(id).orElseThrow().updateExternalJobs("{\"CUTOUT:EX_05\":\"rp-5\"}");
                return null;
            })).get();

            // 종전 방식(@Lock JPQL)은 락만 잡고 낡은 상태를 돌려줬다 — 원인 고정
            String viaOldQuery = tx.execute(s -> osiv.createQuery("select j from CharacterCreationJob j where j.id = :id",
                    CharacterCreationJob.class).setParameter("id", id).setLockMode(LockModeType.PESSIMISTIC_WRITE)
                .getSingleResult().getExternalJobsJson());
            assertThat(viaOldQuery).isNull();

            // 수정된 락 조회는 다시 읽는다 — 이 상태로 써도 다른 스레드의 기록이 지워지지 않는다
            String viaLock = tx.execute(s -> {
                CharacterCreationJob j = jobs.findByIdForUpdate(id).orElseThrow();
                j.updateEmotionAssets("{}");   // 웹훅 TX처럼 다른 필드를 고쳐 행 전체를 다시 쓴다
                return j.getExternalJobsJson();
            });
            assertThat(viaLock).contains("rp-5");
        } finally {
            TransactionSynchronizationManager.unbindResource(emf);
            osiv.close();
            other.shutdownNow();
        }
        String committed = new TransactionTemplate(txm).execute(s -> jobs.findById(id).orElseThrow().getExternalJobsJson());
        assertThat(committed).contains("rp-5");
    }
}
