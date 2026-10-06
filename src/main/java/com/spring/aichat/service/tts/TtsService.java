package com.spring.aichat.service.tts;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.spring.aichat.config.TtsProperties;
import com.spring.aichat.domain.chat.*;
import com.spring.aichat.domain.tts.*;
import com.spring.aichat.domain.user.*;
import com.spring.aichat.service.tts.TtsSourceResolver.Clip;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import java.io.ByteArrayOutputStream;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;

@Service @RequiredArgsConstructor @Slf4j
public class TtsService {
    private final TtsProperties props;
    private final TtsAudioStorage storage;
    private final TtsGreetingService greetings;
    private final TtsSourceResolver sources;
    private final TtsInputFormatter formatter;
    private final ElevenLabsDialogueClient provider;
    private final ChatRoomRepository rooms;
    private final ChatLogMongoRepository logs;
    private final UserRepository users;
    private final TtsResponseAudioRepository jobs;
    private final TransactionTemplate tx;
    private final ObjectMapper mapper;
    private final ExecutorService worker = new ThreadPoolExecutor(3, 3, 0, TimeUnit.SECONDS,
        new ArrayBlockingQueue<>(30), runnable -> { var t = new Thread(runnable, "tts-worker"); t.setDaemon(true); return t; });
    private final Set<Long> dispatched = ConcurrentHashMap.newKeySet();
    private final Map<TtsResponseAudio.Status, Long> sweepCursor = new ConcurrentHashMap<>();
    public record Mode(boolean available, boolean enabled, int energyCost) {}
    public record View(String status, String attemptId, int energyCost, boolean refunded, List<Integer> sceneIndices,
                       String greetingSlug, Integer remainingEnergy, String failureCode) {}
    public record Unlock(int expectedEnergyCost, String idempotencyKey, String retryOfAttemptId) {}
    private record Ticket(Long id, Long userId, Long roomId, String logId, String hash, String attempt, String model, List<Clip> clips) {}

    public Mode mode(String username, Long roomId) {
        return isolatedTx().execute(s -> { var room = owned(username, roomId); return new Mode(available(room), modeAccepted(room), props.getEnergyCost()); });
    }
    public Mode setMode(String username, Long roomId, boolean enabled, int expectedCost) {
        return isolatedTx().execute(s -> {
            users.findByIdForUpdate(users.findIdByUsername(username).orElseThrow(TtsSourceResolver::notFound)).orElseThrow(TtsSourceResolver::notFound);
            var room = owned(username, roomId);
            if (enabled) { requireConfigured(); if (!available(room)) throw unsupported(); checkCost(expectedCost); }
            room.setTtsMode(enabled, props.getEnergyCost());
            return new Mode(available(room), enabled, props.getEnergyCost());
        });
    }
    public View status(String username, Long roomId, String logId) {
        return isolatedTx().execute(s -> {
            var room = owned(username, roomId); var source = sources.resolve(room, logId);
            if (source.greetingSlug() != null) return greeting(source.greetingSlug());
            var job = jobs.findByUserIdAndRoomIdAndLogId(room.getUser().getId(), roomId, logId).orElse(null);
            if (job != null && !job.getSourceHash().equals(source.hash())) throw TtsSourceResolver.notFound();
            return job == null ? new View(source.clips().isEmpty() || !props.configured() ? "UNSUPPORTED" : "AVAILABLE", null,
                props.getEnergyCost(), false, source.clips().stream().map(Clip::sceneIndex).toList(), null, null, null) : view(job, null);
        });
    }
    public View unlock(String username, Long roomId, String logId, Unlock request) {
        return ensure(username, roomId, logId, request, false);
    }
    private View ensure(String username, Long roomId, String logId, Unlock request, boolean automatic) {
        requireConfigured();
        View result = isolatedTx().execute(s -> {
            // Every charge, refund and attempt transition locks User before the response row.
            var userId = users.findIdByUsername(username).orElseThrow(TtsSourceResolver::notFound);
            var user = users.findByIdForUpdate(userId).orElseThrow(TtsSourceResolver::notFound);
            var initial = owned(username, roomId);
            if (automatic && !modeAccepted(initial)) return new View("SKIPPED", null, props.getEnergyCost(), false, List.of(), null, user.getEnergy(), null);
            var source = sources.resolve(initial, logId);
            if (source.greetingSlug() != null) return greeting(source.greetingSlug());
            if (source.clips().isEmpty()) throw unsupported();
            var job = jobs.lockResponse(user.getId(), roomId, logId).orElse(null);
            if (job != null) {
                if (!job.getSourceHash().equals(source.hash()) || job.getStatus() == TtsResponseAudio.Status.CANCELLED) throw TtsSourceResolver.notFound();
                // Normal ensure requests (including lost HTTP responses) NEVER restart refunded jobs.
                if (Objects.equals(job.getRequestKey(), request.idempotencyKey()) || request.retryOfAttemptId() == null
                    || job.getStatus() != TtsResponseAudio.Status.FAILED) return view(job, user.getEnergy());
                if (!job.getAttemptId().equals(request.retryOfAttemptId())) throw conflict("재시도 상태가 변경됐습니다.");
            } else if (request.retryOfAttemptId() != null) throw conflict("재시도할 음성이 없습니다.");
            checkCost(request.expectedEnergyCost());
            if (request.idempotencyKey() == null || !request.idempotencyKey().matches("[A-Za-z0-9_-]{8,100}"))
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "음성 요청 식별자가 필요합니다.");
            if (job == null) job = TtsResponseAudio.create(user.getId(), roomId, logId, source.hash());
            var charge = user.consumeEnergy(props.getEnergyCost());
            job.start(request.idempotencyKey(), props.getModel(), json(source.clips()), charge);
            jobs.save(job);
            return view(job, user.getEnergy());
        });
        dispatchQueued(); return result;
    }
    /** The text response remains successful regardless of a voice opt-in or billing failure. */
    public void onResponse(Long roomId, String logId) {
        if (logId == null || !props.configured()) return;
        try {
            String username = isolatedTx().execute(s -> {
                var room = rooms.findById(roomId).orElse(null);
                return room != null && modeAccepted(room) ? room.getUser().getUsername() : null;
            });
            if (username != null) ensure(username, roomId, logId, new Unlock(props.getEnergyCost(), "auto_" + logId, null), true);
        } catch (Exception e) { log.warn("[TTS] automatic voice unavailable | roomId={} | type={}", roomId, e.getClass().getSimpleName()); }
    }
    public byte[] audio(String username, Long roomId, String logId, int sceneIndex) {
        String key = isolatedTx().execute(s -> {
            var room = owned(username, roomId); var source = sources.resolve(room, logId);
            var job = jobs.findByUserIdAndRoomIdAndLogId(room.getUser().getId(), roomId, logId).orElseThrow(TtsSourceResolver::notFound);
            if (job.getStatus() != TtsResponseAudio.Status.READY || !job.getSourceHash().equals(source.hash())) throw TtsSourceResolver.notFound();
            return clips(job).stream().filter(c -> c.sceneIndex() == sceneIndex).map(Clip::objectKey).filter(Objects::nonNull)
                .findFirst().orElseThrow(TtsSourceResolver::notFound);
        });
        return storage.get(key);
    }
    public void cancelRoom(Long roomId) { jobs.cancelTargets(roomId, null).forEach(j -> cancel(j.getId(), j.getAttemptId())); }
    public void cancelRoomAfterCommit(Long roomId) {
        if (!org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive()) { cancelRoom(roomId); return; }
        var targets = jobs.cancelTargets(roomId, null);
        // Reset may already hold Room locks. Never acquire the billing User lock until those are released.
        org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
            new org.springframework.transaction.support.TransactionSynchronization() {
                @Override public void afterCommit() {
                    try { targets.forEach(target -> cancel(target.getId(), target.getAttemptId())); }
                    catch (Exception e) { log.warn("[TTS] reset cleanup deferred | roomId={}", roomId); }
                }
            });
    }
    public void cancelLog(Long roomId, String logId) { jobs.cancelTargets(roomId, logId).forEach(j -> cancel(j.getId(), j.getAttemptId())); }

    @Scheduled(fixedDelay = 3000)
    public void dispatchQueued() {
        if (!props.configured()) return;
        for (var job : jobs.findTop20ByStatusOrderByCreatedAtAsc(TtsResponseAudio.Status.QUEUED)) {
            if (!dispatched.add(job.getId())) continue;
            try { worker.execute(() -> { try { generate(job.getId()); } finally { dispatched.remove(job.getId()); } }); }
            catch (RejectedExecutionException e) { dispatched.remove(job.getId()); }
        }
    }
    @Scheduled(fixedDelay = 60000)
    public void sweep() {
        jobs.findTop100ByCleanupJsonNotAndCleanupAfterBeforeOrderByCleanupAfterAsc("[]", LocalDateTime.now()).forEach(this::cleanup);
        jobs.findByStatusAndUpdatedAtBefore(TtsResponseAudio.Status.GENERATING, LocalDateTime.now().minusMinutes(10))
            .forEach(j -> fail(j.getId(), j.getAttemptId(), false, "TIMED_OUT"));
        for (var status : List.of(TtsResponseAudio.Status.QUEUED, TtsResponseAudio.Status.READY, TtsResponseAudio.Status.FAILED, TtsResponseAudio.Status.CANCELLED)) {
            var page = jobs.findTop100ByStatusAndIdGreaterThanOrderByIdAsc(status, sweepCursor.getOrDefault(status, 0L));
            sweepCursor.put(status, page.size() < 100 ? 0L : page.get(page.size() - 1).getId());
            for (var job : page) {
                if (status == TtsResponseAudio.Status.CANCELLED || !sourceExists(job)) cancel(job.getId(), job.getAttemptId());
            }
        }
    }
    private void generate(Long id) {
        Ticket ticket = isolatedTx().execute(s -> {
            var ownerId = jobs.ownerId(id).orElse(null); if (ownerId == null) return null;
            users.findByIdForUpdate(ownerId).orElseThrow(TtsSourceResolver::notFound);
            var job = jobs.lockById(id).orElseThrow();
            if (!job.claim(job.getAttemptId())) return null;
            return new Ticket(id, job.getUserId(), job.getRoomId(), job.getLogId(), job.getSourceHash(), job.getAttemptId(), job.getModel(), clips(job));
        });
        if (ticket == null) return;
        List<Clip> completed = new ArrayList<>();
        try {
            if (!sourceExists(ticket.roomId(), ticket.logId(), ticket.hash())) throw new IllegalStateException("Deleted source");
            var inputs = formatter.format(ticket.clips());
            for (var clip : inputs) {
                if (!sourceExists(ticket.roomId(), ticket.logId(), ticket.hash())) throw new IllegalStateException("Deleted source");
                // Long dialogue is split without truncation; each connection stays within the provider's recommendation.
                var audio = new ByteArrayOutputStream();
                for (String part : splitInput(clip.input())) {
                    if (!live(ticket)) throw new IllegalStateException("Cancelled attempt");
                    audio.write(provider.synthesize(ticket.model(), clip.voiceId(), part));
                }
                String key = "responses/" + ticket.userId() + "/" + ticket.id() + "/" + ticket.attempt() + "/" + clip.sceneIndex() + ".mp3";
                // Record the key before PUT so even an ambiguous upload can be cleaned.
                completed.add(clip.withInput(null).withKey(key));
                boolean live = Boolean.TRUE.equals(isolatedTx().execute(s -> {
                    users.findByIdForUpdate(ticket.userId()).orElseThrow(TtsSourceResolver::notFound);
                    var job = jobs.lockById(id).orElseThrow();
                    if (!job.matches(ticket.attempt(), TtsResponseAudio.Status.GENERATING)) return false;
                    var keys = cleanupKeys(job); keys.add(key); job.setCleanupJson(json(keys)); return true;
                }));
                if (!live) throw new IllegalStateException("Cancelled attempt");
                storage.put(key, audio.toByteArray());
            }
            boolean accepted = Boolean.TRUE.equals(isolatedTx().execute(s -> {
                users.findByIdForUpdate(ticket.userId()).orElseThrow(TtsSourceResolver::notFound);
                var job = jobs.lockById(id).orElseThrow();
                if (!sourceExists(ticket.roomId(), ticket.logId(), ticket.hash())) return false;
                if (!completed.stream().map(Clip::sceneIndex).toList().equals(ticket.clips().stream().map(Clip::sceneIndex).toList())) return false;
                boolean ready = job.complete(ticket.attempt(), json(completed));
                if (ready) {
                    var keys = cleanupKeys(job); keys.removeAll(completed.stream().map(Clip::objectKey).toList());
                    job.setCleanupJson(json(keys));
                }
                return ready;
            }));
            if (!accepted) { rememberLateCleanup(id, completed); removeObjects(completed); fail(id, ticket.attempt(), true, "CANCELLED"); }
        } catch (Exception e) {
            // Never echo provider payloads, dialogue, API keys or voice IDs into logs.
            log.warn("[TTS] generation failed | id={} | type={}", id, e.getClass().getSimpleName());
            rememberLateCleanup(id, completed); removeObjects(completed); fail(id, ticket.attempt(), false, "GENERATION_FAILED");
        }
    }
    static List<String> splitInput(String input) {
        List<String> parts = new ArrayList<>(); int start = 0;
        while (start < input.length()) {
            int end = Math.min(input.length(), start + 1800);
            if (end < input.length() && Character.isHighSurrogate(input.charAt(end - 1))) end--;
            int tagStart = input.lastIndexOf('[', end - 1);
            int tagEnd = tagStart < 0 ? -1 : input.indexOf(']', tagStart);
            if (tagStart >= start && tagStart < end && tagEnd >= end && tagEnd - tagStart < 30) end = tagStart;
            parts.add(input.substring(start, end)); start = end;
        }
        return parts;
    }
    private void fail(Long id, String attempt, boolean cancel, String code) {
        isolatedTx().executeWithoutResult(s -> {
            var ownerId = jobs.ownerId(id).orElse(null); if (ownerId == null) return;
            var user = users.findByIdForUpdate(ownerId).orElse(null);
            var job = jobs.lockById(id).orElseThrow();
            if (cancel && job.getAttemptId().equals(attempt)) {
                var keys = cleanupKeys(job);
                clips(job).stream().map(Clip::objectKey).filter(Objects::nonNull).forEach(k -> { if (!keys.contains(k)) keys.add(k); });
                job.setCleanupJson(json(keys));
            }
            var refund = job.fail(attempt, cancel, code); if (user != null) user.refundEnergy(refund);
        });
    }
    private void cancel(Long id, String attempt) {
        fail(id, attempt, true, "CANCELLED");
        var cancelledClips = isolatedTx().execute(s -> {
            var ownerId = jobs.ownerId(id).orElse(null);
            if (ownerId == null) return List.<Clip>of();
            users.findByIdForUpdate(ownerId);
            var job = jobs.lockById(id).orElse(null);
            if (job == null || !job.matches(attempt, TtsResponseAudio.Status.CANCELLED)) return List.<Clip>of();
            var snapshot = clips(job);
            job.clearClips();
            return snapshot;
        });
        removeObjects(cancelledClips);
    }
    private boolean removeObjects(List<Clip> clips) {
        boolean done = true;
        for (var c : clips) if (c.objectKey() != null) try { storage.delete(c.objectKey()); }
            catch (Exception e) { done = false; log.warn("[TTS] object cleanup pending"); }
        return done;
    }
    private List<String> cleanupKeys(TtsResponseAudio job) {
        try { return new ArrayList<>(mapper.readValue(job.getCleanupJson(), new TypeReference<List<String>>() {})); }
        catch (Exception e) { throw new IllegalStateException("Invalid cleanup snapshot"); }
    }
    private void rememberLateCleanup(Long id, List<Clip> completed) {
        if (completed.isEmpty()) return;
        isolatedTx().executeWithoutResult(s -> {
            var ownerId = jobs.ownerId(id).orElse(null); if (ownerId == null) return;
            users.findByIdForUpdate(ownerId);
            jobs.lockById(id).ifPresent(job -> {
                var keys = cleanupKeys(job);
                completed.stream().map(Clip::objectKey).filter(Objects::nonNull).forEach(k -> { if (!keys.contains(k)) keys.add(k); });
                job.setCleanupJson(json(keys));
            });
        });
    }
    private void cleanup(TtsResponseAudio snapshot) {
        var keys = cleanupKeys(snapshot);
        // Current uploads may still be running. Old attempts can be cleaned independently.
        if (snapshot.getStatus() == TtsResponseAudio.Status.GENERATING)
            keys.removeIf(key -> key.contains("/" + snapshot.getAttemptId() + "/"));
        for (String key : keys) try { if (storage.exists(key)) storage.delete(key); } catch (Exception e) { log.warn("[TTS] object cleanup pending"); }
        if (!keys.isEmpty()) isolatedTx().executeWithoutResult(s -> {
            users.findByIdForUpdate(snapshot.getUserId());
            jobs.lockById(snapshot.getId()).ifPresent(job -> {
                // DELETE before an ambiguous PUT can report success while the upload is still pending.
                // Keep opaque tombstones and reconcile them again, even after the process dies.
                job.deferCleanup();
            });
        });
    }
    private boolean live(Ticket ticket) {
        return sourceExists(ticket.roomId(), ticket.logId(), ticket.hash()) && jobs.findById(ticket.id())
            .map(j -> j.matches(ticket.attempt(), TtsResponseAudio.Status.GENERATING)).orElse(false);
    }
    private boolean sourceExists(TtsResponseAudio job) { return sourceExists(job.getRoomId(), job.getLogId(), job.getSourceHash()); }
    private boolean sourceExists(Long roomId, String logId, String hash) {
        return rooms.existsById(roomId) && logs.findById(logId).filter(l -> l.getRole() == com.spring.aichat.domain.enums.ChatRole.ASSISTANT && !l.isHidden() && Objects.equals(l.getRoomId(), roomId)
            && TtsSourceResolver.hash(l).equals(hash)).isPresent();
    }
    private ChatRoom owned(String username, Long roomId) {
        var room = rooms.findById(roomId).orElseThrow(TtsSourceResolver::notFound);
        if (!room.getUser().getUsername().equals(username)) throw TtsSourceResolver.notFound();
        return room;
    }
    private boolean available(ChatRoom room) {
        if (!props.configured() || (!room.isSandboxMode() && !room.isStoryMode())) return false;
        if (sources.officialVoice(room.getCharacter()) != null) return true;
        return room.isStoryMode() && sourcesCastAvailable(room);
    }
    private boolean sourcesCastAvailable(ChatRoom room) {
        // Eligibility query is kept in the resolver so NPC names cannot select a voice.
        return sources.hasOfficialCast(room);
    }
    private boolean modeAccepted(ChatRoom room) { return available(room) && room.isTtsEnabled() && Objects.equals(room.getTtsEnergyCostAccepted(), props.getEnergyCost()); }
    private void requireConfigured() { if (!props.configured()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "보이스를 사용할 수 없습니다."); storage.checkPrivateBucket(); }
    private void checkCost(int expected) { if (expected != props.getEnergyCost()) throw conflict("음성 요금이 변경됐습니다. 다시 확인해 주세요."); }
    private static ResponseStatusException conflict(String message) { return new ResponseStatusException(HttpStatus.CONFLICT, message); }
    private static ResponseStatusException unsupported() { return new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "이 대사에는 지원되는 보이스가 없습니다."); }
    private View greeting(String slug) { return new View(greetings.available(slug) ? "GREETING" : "UNSUPPORTED", null, 0, false, List.of(0), slug, null, null); }
    private View view(TtsResponseAudio job, Integer remaining) {
        return new View(job.getStatus().name(), job.getAttemptId(), job.getFromFree() + job.getFromPaid(), job.isRefunded(),
            clips(job).stream().map(Clip::sceneIndex).toList(), null, remaining, job.getFailureCode());
    }
    private List<Clip> clips(TtsResponseAudio job) {
        try { return mapper.readValue(job.getClipsJson(), new TypeReference<List<Clip>>() {}); }
        catch (Exception e) { throw new IllegalStateException("Invalid TTS snapshot"); }
    }
    private String json(Object value) {
        try { return mapper.writeValueAsString(value); } catch (Exception e) { throw new IllegalStateException(e); }
    }
    /** Deletes/reset callers may already have managed User/job snapshots in their own transaction. */
    private TransactionTemplate isolatedTx() {
        var template = new TransactionTemplate(Objects.requireNonNull(tx.getTransactionManager()));
        template.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return template;
    }
    @PreDestroy public void stop() { worker.shutdownNow(); }
}
