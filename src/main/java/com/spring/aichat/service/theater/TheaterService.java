package com.spring.aichat.service.theater;

import com.spring.aichat.domain.character.Character;
import com.spring.aichat.domain.chat.ChatRoom;
import com.spring.aichat.domain.chat.ChatRoomRepository;
import com.spring.aichat.domain.enums.ChatMode;
import com.spring.aichat.domain.enums.EmotionTag;
import com.spring.aichat.domain.enums.TheaterAct;
import com.spring.aichat.domain.theater.*;
import com.spring.aichat.domain.user.User;
import com.spring.aichat.domain.user.UserRepository;
import com.spring.aichat.dto.theater.TheaterResponses.*;
import com.spring.aichat.exception.BadRequestException;
import com.spring.aichat.exception.BusinessException;
import com.spring.aichat.exception.ErrorCode;
import com.spring.aichat.exception.NotFoundException;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * [Phase 5.5-Theater] Theater 메인 서비스
 *
 * Scene 배치 소비/진행의 핵심 흐름:
 * 1. requestNextBatch()     — 다음 배치 조회 (캐시 hit → 반환 / miss → 동기 생성)
 * 2. onBatchConsumed()      — 유저가 배치 감상 완료 시 호출
 * 3. prefetchNextBatchAsync() — 70% 소비 시 비동기 prefetch
 * 4. finalizeChapter()      — Chapter 종료 처리 + 리포트 생성
 *
 * [에너지 정책]
 * Theater는 배치당 1 에너지 (ChatMode.THEATER.getBaseCost()).
 * User.consumeEnergy()를 통해 직접 차감 — 기존 프로젝트의 에너지 패턴과 일치.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TheaterService {

    private final ChatRoomRepository chatRoomRepository;
    private final TheaterStateRepository theaterStateRepository;
    private final TheaterHeroineAffectionRepository affectionRepository;
    private final TheaterBranchChoiceRepository branchChoiceRepository;
    private final TheaterBatchGenerator batchGenerator;
    private final TheaterBatchCacheService batchCache;
    private final TheaterDirectorEngine directorEngine;
    private final UserRepository userRepository;
    // [INT-3] 에너지 차감 후 /users/me 프로필 캐시 무효화 — 극장 축만 이 관례에서 빠져 있었다.
    private final com.spring.aichat.service.cache.RedisCacheService cacheService;
    /**
     * [적대적 리뷰 P1-1 / P1-4] LOCATION 선행 술어 + 미확정 분기 가드의 단일 소유자.
     * 이 술어를 여기와 로비와 분기 오퍼가 각자 들고 있던 것이 P1-1의 원인이었다.
     */
    private final TheaterProgressGateService gateService;

    /**
     * [B-5.2 · 적대적 리뷰 P1] 과금 워터마크 게이트를 **실제로 거부**할지.
     *
     * <p>기본 {@code false} = 관측 모드(WARN만). 켜기 전 확인할 것:
     * ① 로그 {@code "Unpaid batch consume detected"} 건수가 0에 수렴하는가
     * ② 배포가 혼재 창을 만들지 않는가(drain-then-switch) — 롤링 창에서는 정상 결제 유저가
     *    거부당할 수 있다(구 태스크가 markBatchPaid를 안 한다).
     */
    @org.springframework.beans.factory.annotation.Value("${theater.paid-batch-gate-enforced:false}")
    private boolean paidBatchGateEnforced;

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  1. 다음 배치 조회/생성
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /**
     * [버그픽스 B-5.1] {@code prefetch} 파라미터를 <b>제거했다</b>(하위호환 오버로드 없음 — CLAUDE.md §2-6).
     *
     * <p>클라이언트가 보낸 플래그가 과금 2지점을 모두 건너뛰면서도 <b>같은 SceneBatch 전문</b>을
     * 돌려줘서, 자기 방 소유자면 누구나 에너지 0으로 Act 1~4를 완주할 수 있었다.
     * 선행 생성은 전용 엔드포인트 {@code POST /{roomId}/prefetch}가 담당한다(본문 미반환).
     */
    @Transactional
    public SceneBatch requestNextBatch(Long roomId, String username) {
        ChatRoom room = getOwnedRoom(roomId, username);
        TheaterState state = getState(roomId);

        if (state.isEndingReached()) throw new BadRequestException("이미 엔딩에 도달한 세션입니다.");
        // [D-13 ② · docs/19_assets/blockd_regressions.md — "엔딩 지점을 넘긴 뒤에도 서버가 계속
        //   플레이를 허용"] 종료 가드가 isEndingReached() 하나뿐이라, 챕터 리포트에서 엔딩 CTA를
        //   누르지 않고 이탈하면 ACT_4 Chapter 5·6·7…이 무한히 진행됐다. leadsToIntermission이
        //   마지막 Act·마지막 챕터에서 false이므로 **인터미션도 영구히 열리지 않는다**(스탯 성장 소멸).
        //   isEndingPoint()는 '마지막 챕터를 끝낸 뒤'에만 참이라 정상 플레이를 막지 않는다([D-13 ①]).
        if (directorEngine.isEndingPoint(state)) {
            throw new BadRequestException("ENDING_READY");
        }
        if (state.isInIntermission()) throw new BadRequestException("인터미션 중입니다. 인터미션을 종료해주세요.");
        if (state.isInterventionActive()) throw new BadRequestException("난입 세션이 활성 상태입니다. 먼저 복귀해주세요.");

        // [Polish · P1 #7 + LOCATION fix] LOCATION choice 선행 가드.
        //   멀티 히로인 + 새 Chapter 진입 시점 + 아직 LOCATION 미선택 → batch LLM 호출 차단.
        //   기존 버그: 프론트가 자동 진입 시 batch 0과 LOCATION 모달이 병렬로 트리거되어
        //              batch 0이 한 번 생성되고, 분기 선택 후 invalidate → 또 생성 → LLM 비용 2배.
        //   ⚠️ 분기 기록 확인이 빠지면 LOCATION 선택 후에도 가드가 풀리지 않아 "반응 없음" 버그.
        //   [적대적 리뷰 P1-1] 술어를 인라인으로 복붙해 두던 것을 gateService로 옮겼다 —
        //   로비(requiresLocationChoice)·오퍼 발급과 **같은 것**을 보게 하기 위해서다.
        if (gateService.isLocationChoiceRequired(roomId, state)) {
            log.info("🎭 [THEATER] Batch request blocked — LOCATION choice required | roomId={}", roomId);
            throw new BadRequestException("LOCATION_CHOICE_REQUIRED");
        }

        // ─── [적대적 리뷰 P1-4 — 도입했다가 철회] 미확정 분기 가드를 두지 않는다 ───
        //  한때 '분기를 확정하지 않고 /next-batch만 부르면 MAJOR 1E·CLIMAX 2E가 opt-in이 된다'는
        //  이유로 400(BRANCH_CHOICE_REQUIRED)을 던졌으나, **철회했다.**
        //
        //  ① 성격 판정이 틀렸다 — 분기를 건너뛰는 것은 *착취*가 아니라 *포기*다.
        //     B-4.b가 막는 것은 "분기를 취하면서 0원을 내는 것"이고, 건너뛰기는 그 분기의
        //     서사·컨텍스트·보상을 통째로 버린다. 상품을 안 사는 것을 도둑질이라 하지 않는다.
        //  ② 대가가 명백히 컸다 — 확정하지 않고 새로고침/이탈하거나, 옵션 LLM이 실패하거나,
        //     에너지가 모자라 확정이 튕기거나, 세이브를 로드하면 pending 마커(TTL 6h)가 남아
        //     /next-batch가 영구 400이 됐다. 마커를 지우는 경로가 전부 도달 불가라 **6시간 잠금**이다.
        //     완주에 90~100E가 드는 트랙에서 1~2E를 지키려고 세션을 세우는 것은 역전된 거래다.
        //  CLAUDE.md의 기준 그대로 — 정상 유저를 막는 가드는 착취를 남기는 것보다 나쁘다.

        // ─── [적대적 리뷰 P1-3] pending 마커를 '한 배치 수명'으로 강제 ───
        //  마커를 지우는 곳이 확정 성공과 purgeRoom뿐이라, 분기를 제시받고 확정하지 않은 채
        //  다음 배치로 넘어간 세션이 **정확히 한 칸 오염**됐다(다음 배치의 분기를 옛 마커가
        //  가로채 레벨·컨텍스트·과금이 한 칸 밀린다). **동기 배치 요청이 왔다 = 분기 시점이
        //  지났다**는 뜻이므로 여기서 마커의 수명을 끊는다.
        //  ⚠ 캐시 HIT 조기 반환보다 앞에 둔다 — 뒤에 두면 캐시 HIT 경로에서 마커가 살아남는다.
        //  ⚠ prefetchNextBatchAsync에는 넣지 않는다 — 70% 지점의 비동기 prefetch가
        //    FE fallback 경로(소비 후 /branches/scene)가 의존하는 마커를 지워 버린다.
        batchCache.clearPendingBranch(roomId);

        int batchId = state.getCurrentBatchId();

        // ─── 캐시 체크 ───
        Optional<SceneBatch> cached = batchCache.getBatch(roomId, batchId);
        if (cached.isPresent()) {
            log.info("🎭 [THEATER] Batch cache HIT | roomId={} | batchId={}", roomId, batchId);
            // [B-5.1] 캐시 HIT도 과금한다 — 전용 prefetch가 미리 데워 둔 배치를
            //   유저가 실제로 '받아 가는' 지점이 여기이기 때문이다.
            // [INT-1] ★ 단 **이미 지불한 배치**는 제외한다. /next-batch는 소비(/batch-consumed)
            //   전까지 같은 batchId를 계속 반환하므로, 배치를 받은 뒤 새로고침·재진입 한 번이면
            //   B-5.1의 무조건 과금이 같은 배치에 1E를 또 물린다 — 착취를 막다 정상 유저를 친
            //   회귀다(docs/17 §D · 전역 하네스). 워터마크가 이미 판별 정보를 들고 있으므로 새 상태가 필요 없다.
            //   **미지불** 배치는 HIT든 MISS든 여전히 과금된다.
            //   면제를 HIT에 한정하는 이유 — MISS는 실제 LLM 재생성이라 면제하면
            //   난입(invalidateBatchesFrom)으로 캐시를 비우고 무한 무료 리롤을 돌릴 수 있다.
            //   ★ 이 면제는 prefetch의 워터마크 가드(:214)와 **짝**이다 — 그 가드가 없으면
            //     prefetch가 지불된 배치 N을 새 LLM 롤로 덮어쓰고(D-5.1/5.2의 키 어긋남),
            //     여기서 0E로 내주게 되어 무료 리롤이 열린다. 한쪽만 지우지 마라.
            //   잔여(극장 사용량 0이라 보류): 지불한 배치를 소비하기 전에 난입하거나 BATCH_TTL(6h)이
            //   지나면 MISS로 떨어져 한 번 더 과금된다. 그 경우는 실제 LLM 비용이 발생한다.
            Integer paidWatermark = state.getLastPaidBatchId();
            if (paidWatermark == null || batchId > paidWatermark) {
                chargeBatchEnergy(username);
                // [B-5.2] 과금 워터마크 전진. 차감 **직후**에 둔다 — 같은 트랜잭션이므로
                //   에너지 부족(InsufficientEnergyException)이면 여기까지 오지 못하고,
                //   이후 어떤 실패로 롤백되면 차감과 워터마크가 함께 되돌아간다.
                state.markBatchPaid(batchId);
            } else {
                log.info("🎭 [THEATER] Batch already paid — 재과금 생략 | roomId={} | batchId={} | watermark={}",
                    roomId, batchId, paidWatermark);
            }
            return cached.get();
        }

        log.info("🎭 [THEATER] Batch cache MISS | roomId={} | batchId={}", roomId, batchId);

        chargeBatchEnergy(username);
        // [B-5.2] 캐시 MISS 경로의 워터마크 전진 (위 HIT 경로와 같은 규칙).
        state.markBatchPaid(batchId);

        // [Phase III · 작업 3] 분기 직후 컨텍스트 consume — 그동안 dead code였음.
        //   BranchService.applyBranchChoice가 "active" 토큰으로 Redis에 저장한
        //   분기 후 컨텍스트를 여기서 처음 소비한다. consume이라 1회용 — 다음
        //   배치부터는 null로 돌아가므로 정확히 "분기 직후 첫 배치"에만 영향.
        String branchContext = batchCache.consumeBranchContext(roomId, "active").orElse(null);
        boolean justBranched = branchContext != null;

        // [Phase 6 도그푸딩 #2 결함 B / Patch B-3] 분기 시 저장된 화자 히로인 hint를 consume.
        //   Chapter 전환 직후 첫 batch에서 같은 히로인이 이어서 등장하도록 한다.
        //   hint가 없으면 null — 기존 Act 기반 분배 정책으로 fallback.
        Long hintedHeroineId = batchCache.consumeHeroineHint(roomId).orElse(null);

        TheaterBatchGenerator.GenerateParams params = new TheaterBatchGenerator.GenerateParams(
            room, state, hintedHeroineId, branchContext, false, justBranched);

        SceneBatch batch = batchGenerator.generateNextBatch(params);
        room.touch(EmotionTag.NEUTRAL); // lastActiveAt 갱신
        return batch;
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  2. 비동기 prefetch
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    @Async("theaterPrefetchExecutor")
    public CompletableFuture<Void> prefetchNextBatchAsync(Long roomId) {
        try {
            TheaterState state = getState(roomId);
            if (state.isEndingReached() || state.isInIntermission() || state.isInterventionActive()) {
                return CompletableFuture.completedFuture(null);
            }

            // [Polish · P1 #7 + LOCATION fix] LOCATION choice 선행 가드 (prefetch도 동일).
            //   분기 미선택 상태에선 어떤 batch도 LLM에 던지지 않는다.
            //   [적대적 리뷰 P1-1] 동기 경로와 같은 술어를 쓴다.
            //   ⚠ P1-3의 clearPendingBranch는 여기 넣지 않는다 — 비동기 prefetch가 FE fallback
            //     경로의 마커를 지워 버려 분기 레벨을 잃는다.
            if (gateService.isLocationChoiceRequired(roomId, state)) {
                log.debug("🎭 [PREFETCH] Skipped — LOCATION choice required | roomId={}", roomId);
                return CompletableFuture.completedFuture(null);
            }

            // ★ [INT-1 짝 가드] 이미 지불한 배치 위에는 절대 생성하지 않는다.
            //   TheaterBatchGenerator는 putBatch(roomId, state.getCurrentBatchId()) = **N**에 쓰는데
            //   (:318), 바로 아래 중복 가드는 existsBatch(roomId, N+1)을 본다 — 키가 어긋나 있다(D-5.1/5.2).
            //   putBatch는 NX가 아니라 무조건 SET이므로(TheaterBatchCacheService:152) 이 메서드가
            //   성공하면 **이미 1E를 지불한 배치 N을 새 LLM 롤로 덮어쓴다**. 거기에 INT-1의
            //   '지불한 배치는 재과금 생략'이 붙으면 유저는 그 새 배치를 0E로 받아 간다 =
            //   무료 리롤. FE가 재생 70%에서 triggerPrefetch를 자동 발사하므로(useTheaterStream.js:136)
            //   '70%까지 보고 새로고침' 반복만으로 돌아간다.
            //   현재는 D-5.6(이 메서드가 @Async인데 @Transactional이 없어 detached LAZY 역참조로
            //   100% 실패)이 우연히 막고 있을 뿐이다 — 그 우연에 과금 정합을 걸어 두지 않는다.
            //   ⚠ 이 가드가 들어가면 prefetch는 사실상 상시 no-op이 되지만 **잃는 것이 없다**:
            //     N+1 키는 어떤 경로로도 생기지 않아 지연 단축 효과가 원래 0이었다.
            //   근본 수정(저장 키를 N+1로)은 requestNextBatch·onBatchConsumed의 조회 키까지
            //   전수로 흔들므로 별도 커밋으로 미룬다(D-5.1/5.2).
            Integer paidWatermark = state.getLastPaidBatchId();
            if (paidWatermark != null && state.getCurrentBatchId() <= paidWatermark) {
                log.debug("🎭 [PREFETCH] Skipped — 지불된 배치 덮어쓰기 방지 | roomId={} | current={} | watermark={}",
                    roomId, state.getCurrentBatchId(), paidWatermark);
                return CompletableFuture.completedFuture(null);
            }

            int nextBatchId = state.getCurrentBatchId() + 1;
            // ⚠ 이 키는 어떤 경로로도 생성되지 않는다 — 생성기는 N에 쓴다(D-5.1/5.2). 위 워터마크
            //   가드가 실질 방어이고, 이 줄은 근본 수정 후에 의미를 갖는다.
            if (batchCache.existsBatch(roomId, nextBatchId)) {
                log.debug("🎭 [PREFETCH] Already cached | roomId={} | nextBatchId={}", roomId, nextBatchId);
                return CompletableFuture.completedFuture(null);
            }

            ChatRoom room = chatRoomRepository.findById(roomId)
                .orElseThrow(() -> new NotFoundException("방을 찾을 수 없습니다."));

            // [Phase III · 작업 3] Prefetch는 branchContext를 consume하지 않는다.
            //   분기 직후 첫 배치는 동기 경로(requestNextBatch)에서 정확히 한 번
            //   consume되어야 하므로, 비동기 prefetch가 미리 가져가면 안 됨.
            //   따라서 prefetch는 항상 일반 정책(model)으로 미리 만든다.
            //   만약 prefetch 시점에 active 컨텍스트가 살아있다면, 분기 적용
            //   직후 BranchService가 invalidateBatchesFrom으로 캐시를 비웠을 것이고
            //   이후 첫 동기 호출이 그것을 consume한다.
            TheaterBatchGenerator.GenerateParams params = new TheaterBatchGenerator.GenerateParams(
                room, state, null, null, false, false);

            batchGenerator.generateNextBatch(params);
            log.info("🎭 [PREFETCH] Done | roomId={} | nextBatchId={}", roomId, nextBatchId);
        } catch (Exception e) {
            log.warn("🎭 [PREFETCH] Failed | roomId={}: {}", roomId, e.getMessage());
        }
        return CompletableFuture.completedFuture(null);
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  3. 배치 소비 완료
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    @Transactional
    public boolean onBatchConsumed(Long roomId, String username, int consumedBatchId) {
        ChatRoom room = getOwnedRoom(roomId, username);
        TheaterState state = getState(roomId);

        if (consumedBatchId != state.getCurrentBatchId()) {
            // [Phase6/Tier4 / H-22] 정책 (b): 클라이언트 stale 상태 진행 시 어긋남 누적 위험 →
            //   즉시 차단하고 새로고침 유도.
            log.warn("🎭 [THEATER] Batch ID mismatch on consume | expected={} | got={}",
                state.getCurrentBatchId(), consumedBatchId);
            throw new BusinessException(ErrorCode.STALE_CLIENT_STATE,
                "클라이언트 상태가 오래되었습니다. 새로고침 후 다시 시도해주세요.");
        }

        // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
        //  [버그픽스 B-5.2] 과금 워터마크 검사 — 무과금 배치로 진행·보상이 확정되던 구멍
        // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
        //  B-5.1(클라이언트 prefetch 플래그 제거)로도 sibling 경로가 남아 있었다:
        //    ① POST /{roomId}/prefetch 는 **과금 없이** 배치를 만든다(202·본문 없음).
        //    ② 그 안의 중복 가드는 existsBatch(roomId, currentBatchId + 1)를 보는데
        //       TheaterBatchGenerator는 putBatch(roomId, state.getCurrentBatchId())로
        //       **N 키**에 저장한다(기존 결함 D-5.1/5.2) → 가드가 항상 통과하고 배치 N이 생긴다.
        //    ③ 여기(onBatchConsumed)는 batchId 일치 + 캐시 존재만 봤다.
        //  → /prefetch → /batch-consumed 를 반복하면 /next-batch를 한 번도 부르지 않고
        //    에너지 0으로 Act 1~4를 완주해 엔딩(90~100E 가치)에 도달할 수 있었다.
        //  씬 본문을 못 볼 뿐 호감도·씬수·화자·분기 마커·advanceBatch()가 전부 정상 진행됐다.
        //
        //  ⚠ 정상 유저를 막지 않는가 — 유저에게 SceneBatch 전문을 넘겨주는 엔드포인트는
        //    /next-batch **하나뿐**이고(전 코드베이스 grep: SceneBatch 반환 지점 1곳),
        //    그 경로는 캐시 HIT/MISS 양쪽 모두 과금 후 markBatchPaid한다. 즉 정상 플레이에서
        //    consumedBatchId는 항상 워터마크 이하다. Chapter/Act 전환·세이브 로드의 리셋은
        //    TheaterState 쪽에 근거와 함께 박아 두었다.
        Integer paidWatermark = state.getLastPaidBatchId();
        if (paidWatermark == null) {
            // [grandfather] 배포 이전부터 진행 중이던 세션 — 컬럼이 NULL이다(V30은 기존 행을
            //   채우지 않는다). 이들에게 게이트를 걸면 **전 유저 장애**이므로 통과시키고,
            //   지금 소비하는 배치까지는 지불된 것으로 보아 워터마크를 세운다.
            //   다음 배치부터는 정상 게이트가 적용된다. 신규 세션의 초기값은 -1이라 여기 오지 않는다.
            log.warn("🎭 [THEATER] Paid-watermark absent (pre-B5.2 session) — grandfathered "
                + "| roomId={} | batchId={}", roomId, consumedBatchId);
            state.adoptPaidWatermark(consumedBatchId);
        } else if (consumedBatchId > paidWatermark) {
            // ★ [적대적 리뷰 P1] 기본값은 **거부하지 않는다**(fail-open + WARN).
            //   이 세션에서 이미 같은 유형의 사고를 한 번 냈다 — '미확정 분기 가드'가 정상 유저를
            //   잠가 철회했다. 여기서 반복하지 않는다.
            //
            //   거부를 기본으로 켤 수 없는 이유(실측): ECS 롤링 배포는 신·구 태스크가 동시에
            //   트래픽을 받는 창을 만든다. 그 창에서 신 태스크가 만든 세션(워터마크 -1 — non-null이라
            //   grandfather 대상이 아니다)이 /next-batch를 **구** 태스크로 태우면 markBatchPaid가
            //   실행되지 않고, 이어지는 /batch-consumed가 **신** 태스크로 가면 정상 결제 유저가
            //   거부당한다. 게다가 FE는 이 실패를 console.error로만 삼켜 **무증상 정지**가 된다
            //   (철회했던 분기 가드가 6시간 잠금이었다면 이쪽은 무기한이다).
            //
            //   그래서 이번 릴리즈는 **관측만** 한다. 로그로 실제 거부 대상이 0에 수렴하는 것을
            //   확인한 뒤 THEATER_PAID_BATCH_GATE=true로 강제한다. 강제 시점에는 FE가
            //   ErrorCode.UNPAID_BATCH를 받아 loadNextBatch()로 자기 치유하도록 이미 배선돼 있다.
            log.warn("🎭 [THEATER] Unpaid batch consume {} | roomId={} | batchId={} | paidWatermark={}",
                paidBatchGateEnforced ? "REJECTED" : "detected (fail-open — 관측 모드)",
                roomId, consumedBatchId, paidWatermark);
            if (paidBatchGateEnforced) {
                throw new BusinessException(ErrorCode.UNPAID_BATCH,
                    "아직 열람하지 않은 배치입니다. 다음 장면을 먼저 불러와 주세요.");
            }
        }

        SceneBatch batch = batchCache.getBatch(roomId, consumedBatchId)
            .orElseThrow(() -> new BusinessException(ErrorCode.INTERNAL_ERROR,
                "소비된 배치 캐시가 없습니다. batchId=" + consumedBatchId));

        // 호감도 변화 영속화
        if (batch.heroineAffectionDeltas() != null && !batch.heroineAffectionDeltas().isEmpty()) {
            for (var entry : batch.heroineAffectionDeltas().entrySet()) {
                Long heroineId = entry.getKey();
                int delta = entry.getValue();
                affectionRepository.findByRoom_IdAndCharacter_Id(roomId, heroineId)
                    .ifPresent(a -> {
                        a.applyDelta(delta);
                        a.recordAppearance(batch.scenes() == null ? 0 : batch.scenes().size());
                    });
            }
        }

        int scenesInBatch = batch.scenes() == null ? 0 : batch.scenes().size();
        state.addScenes(scenesInBatch);
        state.setCurrentHeroine(batch.speakerHeroineId());
        state.advanceBatch();

        // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
        //  [버그픽스 B-4.e · docs/17_assets/defect_register.md] 분기 신호 마커
        // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
        //  분기 오퍼는 이제 클라이언트가 보낸 level·contextSummary가 아니라 **서버가 캐시한
        //  배치의 branchSignal**로만 발급된다(B-4.b/d). 그런데 FE의 분기 옵션 prefetch가
        //  실패하면 fallback 호출이 바로 위 advanceBatch() **이후에** 들어온다 — 그때
        //  currentBatchId는 분기를 실은 배치보다 1 크고, FE가 70% 지점에서 미리 만든
        //  다음 배치가 자기만의 branchSignal을 갖고 캐시에 있을 수 있어 오프셋 추정이
        //  엉뚱한 레벨을 집는다. 소비 시점의 서버 원본을 여기서 못 박아 둔다.
        //  [적대적 리뷰 P2-c] 좌표(act·chapter)를 함께 남긴다. currentBatchId는 Chapter/Act 전환 시
        //  0으로 리셋되는데 마커는 6h를 살아남으므로, 좌표가 없으면 이전 Chapter의 미확정 분기가
        //  새 Chapter에서 부활한다.
        if (batch.branchSignal() != null && batch.branchSignal().level() != null) {
            batchCache.putPendingBranch(roomId, consumedBatchId,
                batch.branchSignal().level(), batch.branchSignal().context(),
                state.getCurrentAct().getNumber(), state.getCurrentChapter());
        }

        boolean chapterEnd = batch.chapterEndAfter() || state.isChapterComplete();
        room.touch(EmotionTag.NEUTRAL);

        log.info("🎭 [THEATER] Batch consumed | roomId={} | batchId={} | scenes={} | chapterEnd={}",
            roomId, consumedBatchId, scenesInBatch, chapterEnd);
        return chapterEnd;
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  4. Chapter 종료 + 리포트 빌드
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    @Transactional
    public ChapterReport finalizeChapter(Long roomId, String username) {
        ChatRoom room = getOwnedRoom(roomId, username);
        TheaterState state = getState(roomId);

        int finishedAct = state.getCurrentAct().getNumber();
        int finishedChapter = state.getCurrentChapter();

        // [Polish · P0 #4] Chapter 동안 감상한 씬 수를 reset 이전에 캡처.
        //   기존 버그: state.completeChapter()가 scenesInCurrentChapter를 0으로 reset
        //   하기 때문에, 이후 state.getScenesInCurrentChapter()는 항상 0 →
        //   ChapterReport.scenesConsumed가 항상 0으로 표시됐다.
        int scenesConsumedThisChapter = state.getScenesInCurrentChapter();

        // 히로인별 리포트 항목 수집
        List<TheaterHeroineAffection> affections = affectionRepository.findByRoom_Id(roomId);
        TheaterHeroineAffection leader = topAffectionBeforeSeal(affections);

        List<HeroineReportItem> heroineItems = new java.util.ArrayList<>();
        for (TheaterHeroineAffection a : affections) {
            int prev = a.getAffection() - a.getRunningDelta();
            int now = a.getAffection();
            boolean isLeader = leader != null && leader.getId().equals(a.getId());
            boolean justBecameLeader = isLeader && leaderChangedThisChapter(affections, a);

            heroineItems.add(new HeroineReportItem(
                a.getCharacter().getId(),
                a.getCharacter().getName(),
                a.getCharacter().getSlug(),            // [Polish-v2] characterSlug 추가
                a.getCharacter().getThumbnailUrl(),
                prev, now, a.getRunningDelta(),
                a.getChapterHighlightQuote(),
                isLeader, justBecameLeader
            ));
            a.sealChapterDelta();
        }

        boolean isLastChapterOfAct = directorEngine.isLastChapterOfAct(state);
        boolean isLastAct = state.getCurrentAct().next() == null;
        // [D-13 ③ · docs/19_assets/blockd_regressions.md — "엔딩 시점 챕터 리포트가 '막이 바뀝니다 —
        //   (현재 Act 제목)' 배지를 함께 띄운다"] 기존 transitionToNewAct = isLastChapterOfAct는
        //   **마지막 Act에서도 참**이었다. state.advanceToNextAct()는 next()==null이면 no-op이라
        //   상태는 멀쩡했지만, 아래 ACT_TRANSITION 배지와 nextActTitle이 '막이 바뀝니다 — 방금 끝낸
        //   Act 제목'이라는 거짓 신호를 냈다(엔딩 직전인데 다음 막을 예고). 마지막 Act는 제외한다.
        boolean transitionToNewAct = isLastChapterOfAct && !isLastAct;

        // [Polish · 인터미션 정책 변경] Act 사이 → Chapter 사이마다 인터미션.
        //   기존: Act 사이에만 인터미션 (4 Act → 3회) → 평균 ~22점 상승. 5종 max 500 못 찍음.
        //   신규: 모든 chapter 후 인터미션 (총 ~25회) → 끝까지 진행 시 종당 60+ 가능.
        //   예외: 마지막 Act의 마지막 chapter는 엔딩 직진 — 몰입 끊김 방지.
        //         (엔딩 진입 시점엔 directorEngine이 endingReached를 set할 것이고, 그 직전이라
        //          한 번 더 stamina를 쥐여줘봤자 엔딩 후엔 의미 없음.)
        boolean leadsToIntermission = !(isLastAct && isLastChapterOfAct);

        if (transitionToNewAct) {
            Character newMain = directorEngine.confirmMainHeroineIfApplicable(room, state);

            // [Phase 6 도그푸딩 #2 결함 B / Patch B-5 (c)] Act 3 → Act 4 진입 시
            //   currentHeroineId(직전 chapter 마지막 화자)와 confirmedMain(메인 히로인)이
            //   다를 때 인터미션 후 Act 4 첫 batch에 *자연 전환 묘사*를 강제 주입한다.
            //   채널: branchContext "active" — 인터미션 종료 후 첫 requestNextBatch가 consume.
            //   ⚠️ 콘텐츠 폴리싱 영역(Phase 6 도그푸딩 #2 결함 B Patch B-5).
            //      한국어 표현/톤은 사용자 검토 후 다듬을 수 있음.
            boolean enteringAct4 = state.getCurrentAct().next() == TheaterAct.ACT_4_RESOLUTION;
            Long lastHeroineId = state.getCurrentHeroineId();
            if (enteringAct4 && newMain != null
                && lastHeroineId != null
                && !lastHeroineId.equals(newMain.getId())) {
                String lastHeroineName = affections.stream()
                    .filter(a -> a.getCharacter().getId().equals(lastHeroineId))
                    .map(a -> a.getCharacter().getName())
                    .findFirst()
                    .orElse("이전 히로인");
                String transitionContext = String.format("""
                    [Act 4 진입 — 메인 히로인 자연 전환]
                    이전 Act의 마지막 흐름은 %s(이)와 함께였다. 그 시간은 끝맺음이 필요했고, 짧은 인터미션 동안 주인공은 자기 마음을 정리했다.
                    이제 이야기는 메인 히로인 %s에게로 향한다.

                    이번 batch의 첫 씬은 다음 두 요소를 *자연스럽게* 포함하라:
                    1) %s과(와) 짧게 마주치거나 마음 속으로 작별하는 짧은 묘사 (한 두 씬, 무겁지 않게)
                    2) %s에게로 시선/발걸음/마음이 향하는 전환 — 우연한 만남, 메시지, 또는 장소의 자연스러운 이동
                    이 전환은 *분기*가 아니라 *서사적 흐름*이다. 유저의 선택지를 만들지 말고, 이야기가 자연스럽게 %s에게로 이어지도록 묘사하라.
                    """, lastHeroineName, newMain.getName(), lastHeroineName, newMain.getName(), newMain.getName());
                batchCache.putBranchContext(roomId, "active", transitionContext);
                log.info("🎭 [THEATER] Act 4 자연 전환 컨텍스트 주입 | roomId={} | last={} | main={}",
                    roomId, lastHeroineName, newMain.getName());
            }
        }

        state.completeChapter();
        int newTargetScenes = directorEngine.decideChapterTargetScenes(state, isLastChapterOfAct);
        state.assignChapterTargetScenes(newTargetScenes);

        // Act 전환은 마지막 chapter일 때만 (정책 변경 없음)
        if (transitionToNewAct) {
            state.advanceToNextAct();
        }
        // 인터미션 시작 — 이제 매 chapter 후 (엔딩 직진 케이스 제외)
        if (leadsToIntermission) {
            state.startIntermission();
        }

        batchCache.invalidateBatchesFrom(roomId, 0);
        batchCache.clearRollingSummary(roomId);
        // [적대적 리뷰 P2-c] Chapter/Act 전환 지점에서 pending 마커를 끊는다.
        //   currentBatchId는 여기서 0으로 리셋되는데(state.completeChapter) 마커 TTL은 6h다.
        //   좌표 검사(PendingBranch.matchesPosition)가 1차 방어지만, 전환 지점에서 실제로
        //   지워 두는 편이 "옛 분기가 부활한다"는 면 자체를 없앤다.
        batchCache.clearPendingBranch(roomId);

        List<ReportBadge> badges = new java.util.ArrayList<>();
        if (transitionToNewAct) {
            badges.add(new ReportBadge(
                "ACT_TRANSITION", "막이 바뀝니다",
                "Act " + state.getCurrentAct().getNumber() + " — " + state.getCurrentAct().getTitle(),
                "🎬", null));
        }
        if (leadsToIntermission) {
            badges.add(new ReportBadge(
                "INTERMISSION", "인터미션 진입",
                "성장의 기회입니다. 피로도를 사용해 스탯을 올리세요.",
                "☕", null));
        }

        return new ChapterReport(
            finishedAct, finishedChapter, "Chapter " + finishedChapter,
            scenesConsumedThisChapter,
            branchChoiceRepository.findByRoom_IdAndActNumberOrderByChosenAtAsc(roomId, finishedAct).size(),
            new java.util.LinkedHashMap<>(), // statDeltas는 인터미션/분기에서 별도 반영
            heroineItems, badges,
            transitionToNewAct,
            transitionToNewAct ? state.getCurrentAct().getTitle() : null,
            leadsToIntermission,
            // [블록 D · 극장 엔딩 부활] 위 leadsToIntermission 주석이 "엔딩 진입 시점엔 directorEngine이
            //   endingReached를 set할 것"이라 가정했지만 그런 코드는 존재한 적이 없다. 여기서 신호를 낸다.
            isLastAct && isLastChapterOfAct
        );
    }

    private TheaterHeroineAffection topAffectionBeforeSeal(List<TheaterHeroineAffection> list) {
        return list.stream()
            .max((a, b) -> Integer.compare(a.getAffection(), b.getAffection()))
            .orElse(null);
    }

    private boolean leaderChangedThisChapter(List<TheaterHeroineAffection> list, TheaterHeroineAffection now) {
        TheaterHeroineAffection prevTop = list.stream()
            .max((a, b) -> Integer.compare(
                a.getAffection() - a.getRunningDelta(),
                b.getAffection() - b.getRunningDelta()))
            .orElse(null);
        return prevTop != null && !prevTop.getId().equals(now.getId());
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  5. 재생 설정
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    @Transactional
    public void updatePlaySettings(Long roomId, String username, Boolean autoPlayEnabled, String playSpeed) {
        getOwnedRoom(roomId, username);
        TheaterState state = getState(roomId);
        state.updatePlaySettings(
            autoPlayEnabled != null ? autoPlayEnabled : state.isAutoPlayEnabled(),
            playSpeed
        );
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  Helpers
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    private ChatRoom getOwnedRoom(Long roomId, String username) {
        ChatRoom room = chatRoomRepository.findWithMemberAndCharacterById(roomId)
            .orElseThrow(() -> new NotFoundException("방을 찾을 수 없습니다."));
        if (!room.getUser().getUsername().equals(username)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "접근 권한이 없습니다.");
        }
        if (room.getChatMode() != ChatMode.THEATER) {
            throw new BadRequestException("Theater 모드 방이 아닙니다.");
        }
        return room;
    }

    private TheaterState getState(Long roomId) {
        return theaterStateRepository.findByRoom_Id(roomId)
            .orElseThrow(() -> new NotFoundException("Theater 세션이 없습니다."));
    }

    /**
     * 배치 생성 시 에너지 1 차감 (User 엔티티 직접).
     *
     * [Phase6/Tier4 / H-15 정책 (b)] Theater는 *부스트 모드 영향 없이* base 비용 고정.
     *   다른 모드(STORY/SANDBOX)는 BoostModeResolver.resolveEnergyCost가 부스트 시 비용을
     *   조정하지만, Theater는 배치당 1 에너지로 평탄하게 유지한다(게임 디자인 의도).
     *   향후 정책 변경 시 boostModeResolver.resolveEnergyCost(ChatMode.THEATER, user)
     *   호출로 통일 가능.
     */
    private void chargeBatchEnergy(String username) {
        User user = userRepository.findByUsername(username)
            .orElseThrow(() -> new NotFoundException("유저를 찾을 수 없습니다: " + username));
        int cost = ChatMode.THEATER.getBaseCost();
        user.consumeEnergy(cost);
        // [INT-3] 차감 직후 프로필 캐시 evict — 다른 20개 소비 지점과 관례를 맞춘다.
        //   ⚠ 현재는 이게 없어도 관측 증상이 없다: UserService:53의 overlayFreshEnergy(D-21)가
        //   캐시 HIT에도 에너지 4필드를 매 호출 DB 실값으로 덮기 때문이다. 그 오버레이를
        //   걷어내는 날에 필요해지므로 넣어 둔다 — '없으면 지금 당장 깨진다'는 뜻이 아니다.
        cacheService.evictUserProfile(username);
    }
}