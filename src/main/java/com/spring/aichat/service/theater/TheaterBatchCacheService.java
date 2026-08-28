package com.spring.aichat.service.theater;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.spring.aichat.dto.theater.BranchOffer;
import com.spring.aichat.dto.theater.LlmSceneBatchOutput;
import com.spring.aichat.dto.theater.TheaterResponses.SceneBatch;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Optional;

/**
 * [Phase 5.5-Theater] Theater 배치 캐시 서비스
 *
 * Redis에 prefetch된 Scene 배치를 저장/조회/무효화한다.
 *
 * [Key 스키마]
 * - theater:batch:{roomId}:{batchId}             — 단일 배치 (직렬화된 SceneBatch)
 * - theater:batch:raw:{roomId}:{batchId}         — LLM 원본 응답 (재파싱용, 선택적)
 * - theater:chapter:rolling:{roomId}             — 현재 Chapter의 롤링 요약
 * - theater:branch:ctx:{roomId}:{token}          — 분기 후 컨텍스트 ("active" 전용, 1회용)
 * - theater:branch:offer:{roomId}:{scene|location}
 *                                                — [B-4 · P2-e] 서버 발급 분기 오퍼 원본
 *                                                  (씬/LOCATION 각각 방당 1개 — 상호 축출 방지)
 * - theater:branch:pending:{roomId}              — [B-4] 소비된 배치가 남긴 분기 신호 마커
 *
 * [TTL]
 * - 배치 캐시: 6시간 (세션 길이 대응)
 * - 롤링 요약: 6시간
 * - 분기 컨텍스트: 30분 (유저가 고민하는 시간 고려)
 * - 분기 오퍼 / pending 마커: 6시간 (배치 캐시와 동조 — 배치보다 길면 사라진 배치의
 *   분기가 적용되는 어긋남이 생긴다)
 *
 * [무효화 정책]
 * - 유저가 분기를 선택하면 해당 배치 이후의 모든 prefetch 배치 evict
 * - 난입(Intervention) 시작 시 현재 & 이후 배치 evict
 * - Chapter 종료 시 이전 Chapter의 배치들 evict (자동 TTL 의존)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TheaterBatchCacheService {

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    private static final Duration BATCH_TTL = Duration.ofHours(6);
    private static final Duration ROLLING_TTL = Duration.ofHours(6);
    private static final Duration BRANCH_CTX_TTL = Duration.ofMinutes(30);
    /**
     * [버그픽스 B-4.a~f · docs/17_assets/defect_register.md] 분기 오퍼 원본 TTL.
     * 배치 캐시(BATCH_TTL)와 **동조**시킨다 — 오퍼가 배치보다 오래 살면 이미 사라진 배치에
     * 대한 분기를 확정하게 되어 서버 상태와 어긋난다. 반대로 짧으면 정상 유저가 400을 본다.
     */
    private static final Duration BRANCH_OFFER_TTL = Duration.ofHours(6);
    /**
     * [Phase 6 도그푸딩 #2 결함 B] 분기 선택 시 다음 chapter용 화자 히로인 hint TTL.
     * 분기 직후 ~ 다음 chapter 첫 batch 진입까지 충분한 30분.
     */
    private static final Duration HEROINE_HINT_TTL = Duration.ofMinutes(30);
    /**
     * [Phase 5.5 UX Polish · R3] 활성 감독 명령어 TTL.
     * 1배치 일회성이지만 안전망으로 30분 만료 (그 동안 다음 배치가 안 오면 자동 폐기).
     */
    private static final Duration DIRECTOR_COMMAND_TTL = Duration.ofMinutes(30);

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  Key 빌더
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    private String batchKey(Long roomId, int batchId) {
        return "theater:batch:" + roomId + ":" + batchId;
    }

    private String rawBatchKey(Long roomId, int batchId) {
        return "theater:batch:raw:" + roomId + ":" + batchId;
    }

    private String rollingKey(Long roomId) {
        return "theater:chapter:rolling:" + roomId;
    }

    private String branchCtxKey(Long roomId, String token) {
        return "theater:branch:ctx:" + roomId + ":" + token;
    }

    /**
     * [적대적 리뷰 P2-e] 오퍼 종류. 씬 분기와 LOCATION 분기가 <b>서로 다른 키</b>를 쓴다.
     *
     * <p>왜 나눴나 — 방당 키 1개였을 때는 두 흐름이 서로를 축출했다. 새 Chapter 진입 시
     * LOCATION 오퍼를 받은 상태에서 (또는 그 반대로) 상대 오퍼가 발급되면 앞선 오퍼가 사라져
     * 멱등 재사용이 깨지고, 새로고침할 때마다 LLM이 다시 돌았다(비용) — 그리고 사용자는
     * "만료됐습니다"를 봤다.
     */
    public enum BranchOfferKind {
        SCENE("scene"),
        LOCATION("location");

        private final String suffix;

        BranchOfferKind(String suffix) { this.suffix = suffix; }

        public String suffix() { return suffix; }
    }

    /**
     * [버그픽스 B-4.a~f · 적대적 리뷰 P2-e] 분기 오퍼 원본 키 — **종류당 방당 1개**.
     *
     * ⚠ 네임스페이스를 branchCtxKey와 분리한 이유: 오퍼 토큰과 분기 후 컨텍스트("active")가
     *   같은 키 공간을 쓰면 클라이언트가 branchToken="active"를 보내 다음 배치용 컨텍스트를
     *   선점 소비할 수 있다.
     * ⚠ 토큰별 키가 아니라 (방, 종류)당 단일 키인 이유: purgeRoom(세이브 로드·방 삭제)이
     *   패턴 SCAN 없이 **두 키를 명시적으로** 지울 수 있어야 한다. 고아 오퍼가 남으면
     *   롤백된 시점의 분기가 재적용된다.
     */
    private String branchOfferKey(Long roomId, BranchOfferKind kind) {
        return "theater:branch:offer:" + roomId + ":" + kind.suffix();
    }

    /**
     * [버그픽스 B-4.e] 소비된 배치가 남긴 분기 신호 마커.
     *
     * ⚠ 왜 필요한가: 분기 옵션 prefetch가 실패하면 FE fallback은 notifyBatchConsumed
     *   (= state.advanceBatch()) **이후에** /branches/scene을 호출한다. 그 시점의
     *   currentBatchId는 분기를 실은 배치보다 1 크다. 게다가 FE는 70% 지점에서 다음 배치를
     *   prefetch하므로 currentBatchId 배치가 **자기만의 branchSignal을 갖고 캐시에 존재**할 수
     *   있어 단순 오프셋 추정이 엉뚱한 레벨을 집는다. 소비 시점에 서버가 직접 남긴 이 마커라야
     *   정확하다.
     */
    private String pendingBranchKey(Long roomId) {
        return "theater:branch:pending:" + roomId;
    }

    /** [R3] 활성 감독 명령어 키 — text와 noteId를 ":"로 구분해 저장 */
    private String directorCommandKey(Long roomId) {
        return "theater:director:command:" + roomId;
    }

    /** [Phase 6 결함 B] 분기 직후 다음 chapter 화자 히로인 hint 키 */
    private String heroineHintKey(Long roomId) {
        return "theater:heroine:hint:" + roomId;
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  배치 캐시
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    public void putBatch(Long roomId, int batchId, SceneBatch batch) {
        try {
            String json = objectMapper.writeValueAsString(batch);
            redisTemplate.opsForValue().set(batchKey(roomId, batchId), json, BATCH_TTL);
            log.debug("🎭 [CACHE] Batch stored | roomId={} | batchId={} | scenes={}",
                roomId, batchId, batch.scenes() == null ? 0 : batch.scenes().size());
        } catch (JsonProcessingException e) {
            log.warn("🎭 [CACHE] Failed to serialize batch | roomId={} | batchId={}: {}",
                roomId, batchId, e.getMessage());
        }
    }

    public Optional<SceneBatch> getBatch(Long roomId, int batchId) {
        String json = redisTemplate.opsForValue().get(batchKey(roomId, batchId));
        if (json == null) return Optional.empty();
        try {
            return Optional.of(objectMapper.readValue(json, SceneBatch.class));
        } catch (JsonProcessingException e) {
            log.warn("🎭 [CACHE] Failed to deserialize batch | roomId={} | batchId={}: {}",
                roomId, batchId, e.getMessage());
            return Optional.empty();
        }
    }

    /** 배치 캐시 존재 여부 (prefetch 중복 방지용) */
    public boolean existsBatch(Long roomId, int batchId) {
        return Boolean.TRUE.equals(redisTemplate.hasKey(batchKey(roomId, batchId)));
    }

    /** LLM 원본 응답 캐시 (디버깅용) */
    public void putRawBatch(Long roomId, int batchId, LlmSceneBatchOutput raw) {
        try {
            String json = objectMapper.writeValueAsString(raw);
            redisTemplate.opsForValue().set(rawBatchKey(roomId, batchId), json, BATCH_TTL);
        } catch (JsonProcessingException e) {
            log.debug("🎭 [CACHE] Raw batch serialization failed: {}", e.getMessage());
        }
    }

    /**
     * 분기 발생 또는 난입 시 호출.
     * batchId 이상의 모든 배치 캐시를 무효화.
     *
     * Redis SCAN으로 탐색하되, 규모가 작으므로 범위 기반 삭제(batchId ~ batchId+10)로 충분.
     */
    public void invalidateBatchesFrom(Long roomId, int fromBatchId) {
        int evicted = 0;
        // 최대 10개 forward 제거 (현실적 prefetch 깊이 상한)
        for (int i = 0; i < 10; i++) {
            int target = fromBatchId + i;
            Boolean removed = redisTemplate.delete(batchKey(roomId, target));
            Boolean removedRaw = redisTemplate.delete(rawBatchKey(roomId, target));
            if (Boolean.TRUE.equals(removed)) evicted++;
            if (!Boolean.TRUE.equals(removed) && i > 2) break; // 더 이상 캐시 없음
        }
        log.info("🎭 [CACHE] Invalidated batches | roomId={} | from={} | evicted={}",
            roomId, fromBatchId, evicted);
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  롤링 요약
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    public void putRollingSummary(Long roomId, String summary) {
        if (summary == null || summary.isBlank()) return;
        redisTemplate.opsForValue().set(rollingKey(roomId), summary, ROLLING_TTL);
    }

    public Optional<String> getRollingSummary(Long roomId) {
        String s = redisTemplate.opsForValue().get(rollingKey(roomId));
        return Optional.ofNullable(s);
    }

    public void clearRollingSummary(Long roomId) {
        redisTemplate.delete(rollingKey(roomId));
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  분기 컨텍스트 토큰
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    public void putBranchContext(Long roomId, String token, String context) {
        redisTemplate.opsForValue().set(branchCtxKey(roomId, token), context, BRANCH_CTX_TTL);
    }

    public Optional<String> consumeBranchContext(Long roomId, String token) {
        String key = branchCtxKey(roomId, token);
        String ctx = redisTemplate.opsForValue().get(key);
        if (ctx != null) redisTemplate.delete(key); // 1회용
        return Optional.ofNullable(ctx);
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  [버그픽스 B-4.a~f · docs/17_assets/defect_register.md] 분기 오퍼 원본
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /** 오퍼 발급/갱신 (종류당 방당 1개 — 같은 종류의 새 오퍼가 이전 오퍼를 덮어쓴다). */
    public void putBranchOffer(Long roomId, BranchOfferKind kind, BranchOffer offer) {
        try {
            String json = objectMapper.writeValueAsString(offer);
            redisTemplate.opsForValue().set(branchOfferKey(roomId, kind), json, BRANCH_OFFER_TTL);
            log.debug("🎭 [CACHE] Branch offer stored | roomId={} | kind={} | level={} | sourceBatchId={}",
                roomId, kind, offer.level(), offer.sourceBatchId());
        } catch (JsonProcessingException e) {
            log.warn("🎭 [CACHE] Failed to serialize branch offer | roomId={}: {}", roomId, e.getMessage());
        }
    }

    /**
     * 오퍼 조회 — <b>비파괴</b>.
     * 확정 성공 후에만 evictBranchOffer로 소비한다(1회용). 조회에서 지우면 새로고침 복구가 막힌다.
     */
    public Optional<BranchOffer> readBranchOffer(Long roomId, BranchOfferKind kind) {
        String json = redisTemplate.opsForValue().get(branchOfferKey(roomId, kind));
        if (json == null) return Optional.empty();
        try {
            return Optional.of(objectMapper.readValue(json, BranchOffer.class));
        } catch (JsonProcessingException e) {
            log.warn("🎭 [CACHE] Failed to deserialize branch offer | roomId={}: {}", roomId, e.getMessage());
            return Optional.empty();
        }
    }

    /** 오퍼 소멸 — ① 확정 성공 직후(1회용 → 리플레이 차단) ② purgeRoom ③ TTL. */
    public void evictBranchOffer(Long roomId, BranchOfferKind kind) {
        redisTemplate.delete(branchOfferKey(roomId, kind));
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  [버그픽스 B-4.e] 소비된 배치의 분기 신호 마커
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /**
     * 방금 소비된 배치가 분기를 실고 있었다는 사실 + 그 서버 원본 신호.
     *
     * @param batchId        분기를 실은 배치 id (소비 직전의 currentBatchId)
     * @param level          서버 확정 레벨
     * @param contextSummary 서버 확정 컨텍스트
     * @param actNumber      [적대적 리뷰 P2-c] 마커를 남긴 시점의 Act
     * @param chapterNumber  [적대적 리뷰 P2-c] 마커를 남긴 시점의 Chapter
     */
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    public record PendingBranch(int batchId, String level, String contextSummary,
                                int actNumber, int chapterNumber) {

        /**
         * [적대적 리뷰 P2-c] 마커가 <b>현재 지점</b>의 것인지.
         *
         * <p>currentBatchId는 Chapter/Act 전환 시 0으로 리셋되는데 마커는 6h를 살아남는다.
         * 좌표를 검사하지 않으면 이전 Chapter에서 확정하지 않고 넘어간 분기가 새 Chapter에서
         * 되살아나 레벨·컨텍스트·과금을 한 칸 오염시킨다.
         *
         * <p>배포 이전에 적재된 마커는 act/chapter가 없어 역직렬화 시 0이 된다 —
         * Act는 1부터라 자연히 불일치로 폐기된다(의도된 fail-safe).
         */
        public boolean matchesPosition(int act, int chapter) {
            return actNumber == act && chapterNumber == chapter;
        }
    }

    public void putPendingBranch(Long roomId, int batchId, String level, String contextSummary,
                                 int actNumber, int chapterNumber) {
        if (level == null || level.isBlank()) return;
        try {
            String json = objectMapper.writeValueAsString(
                new PendingBranch(batchId, level, contextSummary, actNumber, chapterNumber));
            redisTemplate.opsForValue().set(pendingBranchKey(roomId), json, BRANCH_OFFER_TTL);
        } catch (JsonProcessingException e) {
            log.warn("🎭 [CACHE] Failed to serialize pending branch | roomId={}: {}", roomId, e.getMessage());
        }
    }

    public Optional<PendingBranch> readPendingBranch(Long roomId) {
        String json = redisTemplate.opsForValue().get(pendingBranchKey(roomId));
        if (json == null) return Optional.empty();
        try {
            return Optional.of(objectMapper.readValue(json, PendingBranch.class));
        } catch (JsonProcessingException e) {
            log.warn("🎭 [CACHE] Failed to deserialize pending branch | roomId={}: {}", roomId, e.getMessage());
            return Optional.empty();
        }
    }

    public void clearPendingBranch(Long roomId) {
        redisTemplate.delete(pendingBranchKey(roomId));
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  [Phase 5.5 UX Polish · R3] 활성 감독 명령어
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /**
     * 활성 명령어 페이로드.
     * @param text   명령어 텍스트 (검증 통과한 sanitized 값)
     * @param noteId DB의 DirectorNote ID — consume 시 wasUsed 마킹용
     */
    public record ActiveDirectorCommand(String text, Long noteId) {}

    /** 직렬화 구분자 — 텍스트 안에 거의 등장하지 않는 토큰 */
    private static final String CMD_SEPARATOR = "\u0001";

    /**
     * 활성 명령어 등록 (max=1 — 기존 큐 덮어쓰기).
     * 새 명령어를 발동하면 직전에 발동했지만 아직 소비되지 않은 명령어는 폐기됨.
     */
    public void setActiveDirectorCommand(Long roomId, String text, Long noteId) {
        String payload = (noteId != null ? noteId : 0L) + CMD_SEPARATOR + (text != null ? text : "");
        redisTemplate.opsForValue().set(directorCommandKey(roomId), payload, DIRECTOR_COMMAND_TTL);
    }

    /**
     * 활성 명령어 조회 — 소비하지 않음 (peek).
     * BranchService가 옵션 생성 시너지 컨텍스트로 가볍게 참조하는 용도.
     */
    public Optional<ActiveDirectorCommand> peekActiveDirectorCommand(Long roomId) {
        String payload = redisTemplate.opsForValue().get(directorCommandKey(roomId));
        return parseCommandPayload(payload);
    }

    /**
     * 활성 명령어 소비 (consume) — 조회 + 즉시 삭제.
     * BatchGenerator가 배치 생성 직전 1회 호출 → 프롬프트에 흡수 후 큐 비움.
     */
    public Optional<ActiveDirectorCommand> consumeActiveDirectorCommand(Long roomId) {
        String key = directorCommandKey(roomId);
        String payload = redisTemplate.opsForValue().get(key);
        if (payload != null) redisTemplate.delete(key);
        return parseCommandPayload(payload);
    }

    /** 활성 명령어 강제 삭제 (배치 invalidation 등) */
    public void clearActiveDirectorCommand(Long roomId) {
        redisTemplate.delete(directorCommandKey(roomId));
    }

    private Optional<ActiveDirectorCommand> parseCommandPayload(String payload) {
        if (payload == null || payload.isBlank()) return Optional.empty();
        int idx = payload.indexOf(CMD_SEPARATOR);
        if (idx < 0) {
            // 구버전 — text만 저장된 경우
            return Optional.of(new ActiveDirectorCommand(payload, null));
        }
        Long noteId = null;
        try {
            long parsed = Long.parseLong(payload.substring(0, idx));
            if (parsed > 0) noteId = parsed;
        } catch (NumberFormatException ignored) {}
        String text = payload.substring(idx + 1);
        return Optional.of(new ActiveDirectorCommand(text, noteId));
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  [Phase 6 도그푸딩 #2 결함 B] 화자 히로인 hint
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /**
     * 분기 직후 다음 batch/chapter에서 같은 히로인을 화자로 유지하기 위한 hint 저장.
     * MINOR/MAJOR/CLIMAX 분기 시 currentHeroineId를 보존하여 chapter 전환 후에도
     * 맥락 단절 없이 같은 히로인이 이어서 등장하도록 한다.
     */
    public void saveHeroineHint(Long roomId, Long heroineId) {
        if (heroineId == null) return;
        redisTemplate.opsForValue().set(heroineHintKey(roomId), String.valueOf(heroineId), HEROINE_HINT_TTL);
    }

    /**
     * Hint 소비 — 1회용. requestNextBatch에서 호출되어 GenerateParams의 hintedHeroineId로 주입.
     */
    public Optional<Long> consumeHeroineHint(Long roomId) {
        String key = heroineHintKey(roomId);
        String v = redisTemplate.opsForValue().get(key);
        if (v == null) return Optional.empty();
        redisTemplate.delete(key);
        try {
            return Optional.of(Long.parseLong(v));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  전체 정리 (방 삭제 시)
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    public void purgeRoom(Long roomId) {
        invalidateBatchesFrom(roomId, 0);
        clearRollingSummary(roomId);
        clearActiveDirectorCommand(roomId);
        redisTemplate.delete(heroineHintKey(roomId));
        // [버그픽스 B-4.e] 세이브 로드(TheaterSaveLoadService)·방 삭제 시 분기 오퍼도 반드시 폐기한다.
        //   남겨두면 롤백된 시점의 오퍼가 살아남아 되돌린 분기를 그대로 재적용할 수 있다.
        // [적대적 리뷰 P2-e] 오퍼 키가 종류별로 갈라졌으므로 **두 키를 모두 명시적으로** 지운다.
        //   패턴 SCAN을 쓰지 않는다(운영 Redis에서 KEYS/SCAN 순회는 금지 관례).
        for (BranchOfferKind kind : BranchOfferKind.values()) {
            evictBranchOffer(roomId, kind);
        }
        clearPendingBranch(roomId);
        log.info("🎭 [CACHE] Purged all caches | roomId={}", roomId);
    }
}