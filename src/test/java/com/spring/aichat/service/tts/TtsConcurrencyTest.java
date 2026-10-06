package com.spring.aichat.service.tts;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.spring.aichat.config.TtsProperties;
import com.spring.aichat.domain.chat.*;
import com.spring.aichat.domain.enums.*;
import com.spring.aichat.domain.tts.*;
import com.spring.aichat.domain.user.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.mock.mockito.*;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.*;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.*;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@DataJpaTest(showSql = false, properties = {"spring.flyway.enabled=false", "spring.jpa.hibernate.ddl-auto=create-drop", "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect"})
@ContextConfiguration(classes = TtsConcurrencyTest.Config.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class TtsConcurrencyTest {
    @Configuration @EntityScan("com.spring.aichat.domain")
    @EnableJpaRepositories(basePackageClasses = {UserRepository.class, ChatRoomRepository.class, TtsResponseAudioRepository.class})
    @Import(TtsService.class)
    static class Config {
        @Bean ObjectMapper mapper() { return new ObjectMapper().findAndRegisterModules(); }
        @Bean TransactionTemplate template(PlatformTransactionManager manager) { return new TransactionTemplate(manager); }
    }
    @Autowired UserRepository users;
    @Autowired ChatRoomRepository rooms;
    @Autowired TtsResponseAudioRepository jobs;
    @Autowired TransactionTemplate tx;
    @SpyBean TtsService service;
    @MockBean TtsProperties props;
    @MockBean TtsSourceResolver sources;
    @MockBean TtsAudioStorage storage;
    @MockBean TtsGreetingService greetings;
    @MockBean TtsInputFormatter formatter;
    @MockBean ElevenLabsDialogueClient provider;
    @MockBean ChatLogMongoRepository logs;
    Long userId, roomId;
    ChatLogDocument log;
    int initialEnergy;

    @BeforeEach void setup() {
        doNothing().when(service).dispatchQueued();
        when(props.configured()).thenReturn(true); when(props.getEnergyCost()).thenReturn(1);
        when(props.getModel()).thenReturn("eleven_v4");
        tx.executeWithoutResult(s -> {
            User user = users.saveAndFlush(User.local("tts_" + UUID.randomUUID(), "hash", "name", UUID.randomUUID() + "@test.invalid"));
            userId = user.getId(); initialEnergy = user.getEnergy();
            roomId = rooms.saveAndFlush(new ChatRoom(user, null, ChatMode.SANDBOX)).getId();
        });
        log = ChatLogDocument.builder().id("log").roomId(roomId).role(ChatRole.ASSISTANT).cleanContent("안녕").scenesJson("[]").build();
        when(logs.findById("log")).thenReturn(Optional.of(log));
        when(sources.resolve(any(), eq("log"))).thenReturn(new TtsSourceResolver.Source(TtsSourceResolver.hash(log),
            List.of(new TtsSourceResolver.Clip(0, "안녕", "testVoice", "[calm] 안녕", null)), null));
    }
    private String username() { return users.findById(userId).orElseThrow().getUsername(); }
    private TtsService.View unlock(String key, String retry) { return service.unlock(username(), roomId, "log", new TtsService.Unlock(1, key, retry)); }
    private TtsResponseAudio job() { return jobs.findByUserIdAndRoomIdAndLogId(userId, roomId, "log").orElseThrow(); }
    private void concurrently(Runnable task) throws Exception {
        var executor = Executors.newFixedThreadPool(2); var gate = new CountDownLatch(1);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < 2; i++) futures.add(executor.submit(() -> { try { gate.await(); task.run(); } catch (Exception e) { throw new RuntimeException(e); } }));
            gate.countDown(); for (var f : futures) f.get(20, TimeUnit.SECONDS);
        } finally { executor.shutdownNow(); }
    }
    @Test void concurrentInitialRequestsChargeOnce() throws Exception {
        concurrently(() -> unlock(UUID.randomUUID().toString(), null));
        assertThat(jobs.findByRoomId(roomId)).hasSize(1);
        assertThat(users.findById(userId).orElseThrow().getEnergy()).isEqualTo(initialEnergy - 1);
    }
    @Test void concurrentFailureRefundsOnceAndHttpReplayDoesNotRecharge() throws Exception {
        var first = unlock("first_key_123", null); Long id = job().getId();
        concurrently(() -> ReflectionTestUtils.invokeMethod(service, "fail", id, first.attemptId(), false, "TEST_FAILURE"));
        assertThat(users.findById(userId).orElseThrow().getEnergy()).isEqualTo(initialEnergy);
        assertThat(unlock("lost_response_123", null).status()).isEqualTo("FAILED");
        assertThat(users.findById(userId).orElseThrow().getEnergy()).isEqualTo(initialEnergy);
        concurrently(() -> unlock(UUID.randomUUID().toString(), first.attemptId()));
        assertThat(users.findById(userId).orElseThrow().getEnergy()).isEqualTo(initialEnergy - 1);
    }
    @Test void paidBucketRefundAndStaleAttemptCannotRefundNewAttempt() {
        tx.executeWithoutResult(s -> {
            var u = users.findByIdForUpdate(userId).orElseThrow(); u.consumeEnergy(u.getEnergy()); u.chargePaidEnergy(3);
        });
        var first = unlock("paid_key_123", null); Long id = job().getId();
        ReflectionTestUtils.invokeMethod(service, "fail", id, first.attemptId(), false, "TEST_FAILURE");
        assertThat(users.findById(userId).orElseThrow().getPaidEnergy()).isEqualTo(3);
        var retry = unlock("paid_retry_123", first.attemptId());
        ReflectionTestUtils.invokeMethod(service, "fail", id, first.attemptId(), false, "LATE_FAILURE");
        assertThat(job().getAttemptId()).isEqualTo(retry.attemptId());
        assertThat(users.findById(userId).orElseThrow().getPaidEnergy()).isEqualTo(2);
    }
    @Test void deleteFailedJobClearsPrivateSnapshotWithoutSecondRefund() {
        var first = unlock("delete_key_123", null); Long id = job().getId();
        ReflectionTestUtils.invokeMethod(service, "fail", id, first.attemptId(), false, "TEST_FAILURE");
        service.cancelRoom(roomId);
        assertThat(job().getStatus()).isEqualTo(TtsResponseAudio.Status.CANCELLED);
        assertThat(job().getClipsJson()).isEqualTo("[]");
        assertThat(users.findById(userId).orElseThrow().getEnergy()).isEqualTo(initialEnergy);
    }
    @Test void latePutAfterCancellationRetainsCleanupLedgerWhenDeleteFails() throws Exception {
        unlock("upload_key_123", null); Long id = job().getId();
        when(formatter.format(anyList())).thenAnswer(inv -> inv.getArgument(0));
        when(provider.synthesize(anyString(), anyString(), anyString())).thenReturn(new byte[]{1,2,3});
        CountDownLatch putStarted = new CountDownLatch(1), putRelease = new CountDownLatch(1);
        doAnswer(inv -> { putStarted.countDown(); if (!putRelease.await(10, TimeUnit.SECONDS)) throw new IllegalStateException(); return null; }).when(storage).put(anyString(), any());
        var executor = Executors.newSingleThreadExecutor();
        try {
            var task = executor.submit(() -> ReflectionTestUtils.invokeMethod(service, "generate", id));
            assertThat(putStarted.await(10, TimeUnit.SECONDS)).isTrue();
            service.cancelRoom(roomId);
            // Mimics cleanup successfully deleting a not-yet-visible PUT before the upload finishes.
            service.sweep();
            doThrow(new IllegalStateException("storage offline")).when(storage).delete(anyString());
            putRelease.countDown(); task.get(15, TimeUnit.SECONDS);
            assertThat(job().getStatus()).isEqualTo(TtsResponseAudio.Status.CANCELLED);
            assertThat(job().getCleanupJson()).contains("responses/");
            assertThat(users.findById(userId).orElseThrow().getEnergy()).isEqualTo(initialEnergy);
        } finally { putRelease.countDown(); executor.shutdownNow(); }
    }
    @Test void autoOffCommittedWhileWaitingForUserLockDoesNotCharge() throws Exception {
        when(sources.officialVoice(any())).thenReturn("voice");
        tx.executeWithoutResult(s -> rooms.findById(roomId).orElseThrow().setTtsMode(true, 1));
        CountDownLatch locked = new CountDownLatch(1), release = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var blocker = executor.submit(() -> tx.executeWithoutResult(s -> {
                users.findByIdForUpdate(userId).orElseThrow(); locked.countDown();
                try { release.await(10, TimeUnit.SECONDS); } catch (InterruptedException e) { throw new RuntimeException(e); }
                rooms.findById(roomId).orElseThrow().setTtsMode(false, 0);
            }));
            assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
            var automatic = executor.submit(() -> service.onResponse(roomId, "log"));
            // Both futures are submitted while the OFF transaction owns the same billing lock.
            release.countDown(); blocker.get(10, TimeUnit.SECONDS); automatic.get(10, TimeUnit.SECONDS);
            assertThat(users.findById(userId).orElseThrow().getEnergy()).isEqualTo(initialEnergy);
            assertThat(jobs.findByRoomId(roomId)).isEmpty();
        } finally { release.countDown(); executor.shutdownNow(); }
    }
    @Test void cancellationAfterFirstChunkPreventsSecondProviderCall() throws Exception {
        String longText = "가".repeat(2500);
        when(sources.resolve(any(), eq("log"))).thenReturn(new TtsSourceResolver.Source(TtsSourceResolver.hash(log),
            List.of(new TtsSourceResolver.Clip(0, longText, "testVoice", longText, null)), null));
        unlock("chunks_key_123", null); Long id = job().getId();
        when(formatter.format(anyList())).thenAnswer(inv -> inv.getArgument(0));
        when(provider.synthesize(anyString(), anyString(), anyString())).thenAnswer(inv -> {
            service.cancelRoom(roomId); return new byte[]{1,2,3};
        });
        ReflectionTestUtils.invokeMethod(service, "generate", id);
        verify(provider, times(1)).synthesize(anyString(), anyString(), anyString());
        verify(storage, never()).put(anyString(), any());
        assertThat(users.findById(userId).orElseThrow().getEnergy()).isEqualTo(initialEnergy);
    }
    @Test void wrongOwnerAndNotReadyAudioCannotReadPrivateStorage() {
        unlock("ownership_key_123", null);
        assertThatThrownBy(() -> service.status("anotherUser", roomId, "log")).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        assertThatThrownBy(() -> service.audio(username(), roomId, "log", 0)).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        verify(storage, never()).get(anyString());
    }
    @Test void resetCapturesOnlyExistingAttemptsAndCancelsAfterCommit() {
        var first = unlock("reset_old_123", null);
        tx.executeWithoutResult(s -> {
            service.cancelRoomAfterCommit(roomId);
            assertThat(job().getStatus()).isEqualTo(TtsResponseAudio.Status.QUEUED);
            // A new attempt created between target capture and commit belongs to the new state.
            ReflectionTestUtils.invokeMethod(service, "fail", job().getId(), first.attemptId(), false, "TEST_FAILURE");
            unlock("reset_new_123", first.attemptId());
        });
        assertThat(job().getStatus()).isEqualTo(TtsResponseAudio.Status.QUEUED);
        assertThat(job().getAttemptId()).isNotEqualTo(first.attemptId());
        assertThat(users.findById(userId).orElseThrow().getEnergy()).isEqualTo(initialEnergy - 1);
        tx.executeWithoutResult(s -> service.cancelRoomAfterCommit(roomId));
        assertThat(job().getStatus()).isEqualTo(TtsResponseAudio.Status.CANCELLED);
        assertThat(users.findById(userId).orElseThrow().getEnergy()).isEqualTo(initialEnergy);
    }
    @Test void repeatedCancellationDoesNotResetDailyCleanupSchedule() {
        unlock("cleanup_due_123", null); Long id = job().getId();
        tx.executeWithoutResult(s -> jobs.findById(id).orElseThrow().setCleanupJson("[\"responses/tombstone\"]"));
        service.cancelRoom(roomId);
        service.sweep();
        var due = job().getCleanupAfter();
        assertThat(due).isAfter(java.time.LocalDateTime.now().plusHours(23));
        clearInvocations(storage);
        service.sweep(); service.cancelRoom(roomId);
        assertThat(job().getCleanupAfter()).isEqualTo(due);
        verify(storage, never()).exists(anyString());
    }
    @Test void resetClearsDialogueEvenWhenCallingPersistenceContextCachedQueuedJob() {
        unlock("reset_cached_123", null);
        tx.executeWithoutResult(s -> {
            assertThat(job().getStatus()).isEqualTo(TtsResponseAudio.Status.QUEUED);
            service.cancelRoomAfterCommit(roomId);
        });
        assertThat(job().getStatus()).isEqualTo(TtsResponseAudio.Status.CANCELLED);
        assertThat(job().getClipsJson()).isEqualTo("[]");
        assertThat(users.findById(userId).orElseThrow().getEnergy()).isEqualTo(initialEnergy);
    }
    @Test void changedPriceRejectsOldQuoteWithoutChargeOrEnablingAutomaticMode() {
        when(sources.officialVoice(any())).thenReturn("voice");
        assertThatThrownBy(() -> service.unlock(username(), roomId, "log", new TtsService.Unlock(2, "old_quote_123", null)))
            .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        assertThatThrownBy(() -> service.setMode(username(), roomId, true, 2))
            .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        assertThat(jobs.findByRoomId(roomId)).isEmpty();
        assertThat(rooms.findById(roomId).orElseThrow().isTtsEnabled()).isFalse();
        assertThat(users.findById(userId).orElseThrow().getEnergy()).isEqualTo(initialEnergy);
    }
}
