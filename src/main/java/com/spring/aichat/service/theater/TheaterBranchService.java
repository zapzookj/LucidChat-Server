package com.spring.aichat.service.theater;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.spring.aichat.domain.character.Character;
import com.spring.aichat.domain.chat.ChatRoom;
import com.spring.aichat.domain.chat.ChatRoomRepository;
import com.spring.aichat.domain.enums.AvatarStat;
import com.spring.aichat.domain.enums.BranchLevel;
import com.spring.aichat.domain.theater.*;
import com.spring.aichat.domain.user.User;
import com.spring.aichat.domain.user.UserRepository;
import com.spring.aichat.dto.theater.BranchOffer;
import com.spring.aichat.dto.theater.TheaterResponses.BranchOption;
import com.spring.aichat.dto.theater.TheaterResponses.BranchOptions;
import com.spring.aichat.dto.theater.TheaterResponses.BranchSignal;
import com.spring.aichat.dto.theater.TheaterResponses.SceneBatch;
import com.spring.aichat.dto.theater.TheaterResponses.StatGate;
import com.spring.aichat.exception.BadRequestException;
import com.spring.aichat.exception.BusinessException;
import com.spring.aichat.exception.ErrorCode;
import com.spring.aichat.exception.NotFoundException;
import com.spring.aichat.external.OpenRouterClient;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.*;
import java.util.stream.Collectors;

/**
 * [Phase 5.5-Theater] 분기 서비스
 *
 * 4가지 분기 레벨 처리:
 *  - MINOR/MAJOR/CLIMAX: 씬 분기 (LLM 생성)
 *  - LOCATION: 장소 선택 분기 (결정론적 생성)
 *
 * Stat-gated Branch: 선택지에 스탯 최소치 조건. 미충족 시 unlocked=false로 UI 잠금.
 *
 * ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 * [버그픽스 B-4.a/b/c/d/e/f · docs/17_assets/defect_register.md · docs/19 §A #13·#14]
 * ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 * <b>왜 재설계했나</b> — 확정 경로가 클라이언트가 보낸 옵션 스냅샷을 그대로 믿었다.
 * 서버는 옵션을 만들 때만 정답(unlocked·energyCost·heroineId·label)을 계산하고
 * 어디에도 저장하지 않았기 때문에, 확정 시점에 대조할 원본 자체가 없었다.
 * 그 결과 스탯 잠금 우회(a)·MAJOR/CLIMAX 무과금(b)·임의 캐릭터 화자 탈취와 무단 일러
 * 트리거(c)·프롬프트 주입(d)·제시된 적 없는 분기 확정(e)·NPE 500(f)이 전부 열려 있었다.
 *
 * <b>새 계약</b> — 오퍼(제시)와 소비(확정)를 분리한다.
 *  1. 오퍼는 100% 서버 확정이다. 씬 분기의 level·contextSummary는 요청 본문이 아니라
 *     **캐시된 배치의 branchSignal**에서 가져온다. LOCATION은 원래부터 요청 본문이 없다.
 *  2. 발급한 옵션 원본 전체를 {@link BranchOffer}로 Redis에 보관한다(방당 1개).
 *  3. 확정은 branchToken을 필수로 요구하고, 옵션·레벨·비용·히로인을 전부 오퍼 원본에서
 *     재판정한다. 요청의 level·optionsSnapshot은 읽지 않는다.
 *  4. 확정 성공 시 오퍼를 evict한다(1회용 → 리플레이 차단).
 *  5. 같은 배치(chapter)에 대한 오퍼 요청은 멱등이다 — LLM 재호출 없이 같은 오퍼를 돌려주므로
 *     새로고침이 곧 복구 경로가 되고 "만료"가 유저에게 드러나지 않는다.
 *
 * ⚠ <b>동작 변화</b>: stat_gate가 이제 <b>처음으로 실제 작동</b>한다. 지금까지 클라이언트가
 *   unlocked=true로 밀어 통과시키던 잠긴 선택지가 이제 400으로 거부된다(의도된 변화).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TheaterBranchService {

    private final ChatRoomRepository chatRoomRepository;
    private final TheaterStateRepository theaterStateRepository;
    private final TheaterHeroineAffectionRepository affectionRepository;
    private final TheaterBranchChoiceRepository branchChoiceRepository;
    private final TheaterBatchCacheService batchCache;
    private final OpenRouterClient openRouterClient;
    private final ObjectMapper objectMapper;
    private final UserRepository userRepository;
    // [Phase III · 작업 3] CLIMAX 분기는 proModel
    private final TheaterModelResolver modelResolver;
    /** [Phase 5.5 UX Polish · R6] 분기 선택 직후 BRANCH_TAKEN 노트 + 일러스트 트리거 */
    private final TheaterAutoNoteService autoNoteService;
    /** [R6] 화자 히로인 조회용 (LOCATION은 chosen.heroineId, 그 외는 state.currentHeroineId) */
    private final com.spring.aichat.domain.character.CharacterRepository characterRepository;
    /**
     * [적대적 리뷰 P1-1] LOCATION 선행 술어의 단일 소유자.
     * 로비(requiresLocationChoice)·배치 요청 가드·오퍼 발급이 <b>같은 것</b>을 보게 한다 —
     * 복붙으로 두면 다음 수정 때 또 어긋난다.
     */
    private final TheaterProgressGateService gateService;

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  1. 장소 선택 분기 (LOCATION)
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /**
     * LOCATION 오퍼의 sourceBatchId 자리표시자.
     * LOCATION은 배치가 아니라 Chapter 진입에 묶이므로 (act, chapter)가 멱등키다.
     */
    private static final int LOCATION_SOURCE_BATCH_ID =
        TheaterProgressGateService.LOCATION_SOURCE_BATCH_ID;

    @Transactional
    public BranchOptions generateLocationBranch(Long roomId, String username) {
        getOwnedRoom(roomId, username);
        TheaterState state = getState(roomId);

        // ─── [적대적 리뷰 P1-1] 시점 가드 (B-4.e의 LOCATION 잔여) ───
        //  기존 가드는 소유권·세션·히로인 수 셋뿐이었다. 그래서 히로인 2명 이상인 방이면
        //  Chapter 중반 아무 시점에나 POST /branches/location으로 오퍼를 받아 확정까지 할 수 있었고,
        //  그 확정 기록이 TheaterService.requestNextBatch의 LOCATION 선행 가드와
        //  로비의 requiresLocationChoice를 함께 풀어 줬다(기록이 두 게이트의 단일 진실 원천이다).
        //  로비가 requiresLocationChoice를 계산할 때 쓰는 술어와 **같은 것**을 여기서 재확인한다.
        //  (히로인 ≥2 조건도 이 술어 안에 포함돼 있다 — 중복 검사를 지웠다.)
        gateService.requireLocationChoiceWindow(roomId, state);

        List<TheaterHeroineAffection> affections = affectionRepository
            .findByRoomOrderByAffectionDesc(roomId);

        int actNumber = state.getCurrentAct().getNumber();
        int chapterNumber = state.getCurrentChapter();

        // [버그픽스 B-4.e] 멱등 재발급 — 같은 Chapter의 LOCATION 오퍼는 그대로 재사용한다.
        //   ① 새로고침·모달 재진입이 곧 복구 경로가 되어 "만료" 개념이 유저에게 드러나지 않는다.
        //   ② 부수 UX 개선: 지금까지는 random.nextInt 때문에 새로고침할 때마다 제시 장소가 바뀌었다.
        Optional<BranchOffer> reusable = batchCache.readBranchOffer(
            roomId, TheaterBatchCacheService.BranchOfferKind.LOCATION);
        if (reusable.isPresent() && reusable.get().matches(
            BranchLevel.LOCATION.name(), LOCATION_SOURCE_BATCH_ID, actNumber, chapterNumber)) {
            log.info("🎭 [BRANCH] LOCATION offer reuse | roomId={} | act={} | chapter={}",
                roomId, actNumber, chapterNumber);
            return reusable.get().toResponse();
        }

        List<BranchOption> options = new ArrayList<>();
        int idx = 0;
        Random random = new Random();

        for (TheaterHeroineAffection aff : affections) {
            Character c = aff.getCharacter();
            List<String> locations = c.getHomeLocationList();
            if (locations.isEmpty()) continue;

            String todayLocation = locations.get(random.nextInt(locations.size()));
            options.add(new BranchOption(
                idx++, todayLocation,
                c.getName() + "이(가) 있을지도...",
                "affection", 0,
                c.getId(), c.getName(),
                todayLocation, null,
                true, false
            ));
        }

        options.add(new BranchOption(
            idx, "발 닿는 대로 걸어본다",
            "누구를 만날지 모른다",
            "introspective", 0,
            null, null, null, null,
            true, false
        ));

        // [버그픽스 B-4.a/c/e] 옵션 원본을 오퍼로 보관한다 — 확정은 오직 여기서만 값을 읽는다.
        //  ⚠ [B-4.d] contextSummary는 null이다. 기존에는 영문 리터럴 "LOCATION_BRANCH_OFFERED"를
        //    분기 컨텍스트로 저장했는데, 토큰이 실제로 왕복하게 된 지금 그 값을 그대로 두면
        //    확정 시 다음 배치 시스템 프롬프트에 이 영문 리터럴이 실려 나간다.
        BranchOffer offer = new BranchOffer(
            generateBranchToken(),
            BranchLevel.LOCATION.name(),
            null,
            "새로운 Chapter가 시작된다. 오늘, 어디로 향할까?",
            LOCATION_SOURCE_BATCH_ID,
            actNumber, chapterNumber, state.getTotalSceneCount(),
            options,
            System.currentTimeMillis()
        );
        batchCache.putBranchOffer(roomId, TheaterBatchCacheService.BranchOfferKind.LOCATION, offer);

        return offer.toResponse();
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  2. 씬 분기 (MINOR/MAJOR/CLIMAX) — LLM 생성
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /**
     * 서버가 확정한 분기 신호 (요청 본문에서 오지 않는다).
     *
     * @param batchId        신호를 실은 배치 id — 오퍼 멱등키
     * @param level          레벨 문자열
     * @param contextSummary 분기 컨텍스트 (LLM이 만든 배치 원본)
     */
    private record ResolvedSignal(int batchId, String level, String contextSummary) {}

    /**
     * [버그픽스 B-4.b/d/e] 씬 분기의 level·contextSummary를 <b>서버가 확정</b>한다.
     *
     * <p>기존에는 컨트롤러가 요청 본문의 level을 valueOf하고 실패하면 MINOR로 폴백했고
     * contextSummary는 클라이언트 문자열을 그대로 썼다. 그래서 {@code level:"MINOR"} 한 줄로
     * CLIMAX(2E)를 무과금 확정할 수 있었고(b), 임의 문자열이 다음 배치 시스템 프롬프트로
     * 흘러 들어갔다(d — 지금까지는 토큰이 왕복하지 않아 죽어 있던 경로라 드러나지 않았을 뿐,
     * 토큰만 배선하고 이 함수를 넣지 않으면 오히려 되살아난다).
     *
     * <p>해석 순서가 중요하다:
     * <ol>
     *   <li><b>pending 마커</b> — prefetch 실패 시 FE fallback은 {@code advanceBatch()} 이후에
     *       호출된다. 이때 currentBatchId는 분기를 실은 배치보다 1 크다. 단순히 -1 오프셋으로
     *       추정하면 FE가 70% 지점에서 미리 만들어 둔 다음 배치(자기만의 branchSignal 보유 가능)와
     *       구분이 안 되므로, 소비 시점에 서버가 남긴 마커를 정본으로 삼는다.</li>
     *   <li><b>현재 배치</b> — 정상 prefetch 경로. 아직 소비 전이라 currentBatchId 배치가 신호를 갖는다.</li>
     *   <li><b>직전 배치</b> — 이 픽스 배포 시점에 이미 배치를 소비해 마커가 없는 세션용 backstop.
     *       이게 없으면 배포 순간 진행 중이던 유저의 MAJOR/CLIMAX가 400으로 죽는다.</li>
     * </ol>
     */
    private ResolvedSignal resolveServerBranchSignal(Long roomId, TheaterState state) {
        int currentBatchId = state.getCurrentBatchId();
        int actNumber = state.getCurrentAct().getNumber();
        int chapterNumber = state.getCurrentChapter();

        Optional<TheaterBatchCacheService.PendingBranch> pending = batchCache.readPendingBranch(roomId);
        // [적대적 리뷰 P2-c] 좌표 검사 추가. currentBatchId는 Chapter/Act 전환 시 0으로 리셋되는데
        //   마커는 6h를 살아남는다. batchId 관계식만 보면 **이전 Chapter의 미확정 분기가
        //   새 Chapter에서 부활**해 레벨·컨텍스트·과금을 한 칸 오염시킨다.
        if (pending.isPresent()
            && pending.get().matchesPosition(actNumber, chapterNumber)
            && pending.get().batchId() == currentBatchId - 1
            && !alreadyResolved(roomId, actNumber, chapterNumber, pending.get().batchId())) {
            return new ResolvedSignal(
                pending.get().batchId(), pending.get().level(), pending.get().contextSummary());
        }

        Optional<ResolvedSignal> fromCurrent =
            signalOfBatch(roomId, currentBatchId, actNumber, chapterNumber);
        if (fromCurrent.isPresent()) return fromCurrent.get();

        if (currentBatchId > 0) {
            Optional<ResolvedSignal> fromPrev =
                signalOfBatch(roomId, currentBatchId - 1, actNumber, chapterNumber);
            if (fromPrev.isPresent()) return fromPrev.get();
        }

        throw new BadRequestException("제시된 분기가 없습니다. 새로고침 후 다시 시도해주세요.");
    }

    /**
     * [적대적 리뷰 P1-2] <b>확정 이력을 소비 게이트로</b> 쓴다.
     *
     * <p>확정 성공 시 {@code invalidateBatchesFrom(roomId, currentBatchId)}를 부르지만,
     * FE fallback 경로(소비 후 currentBatchId=N+1, 신호는 배치 N)에서는 <b>배치 N이 캐시에 남는다.</b>
     * 그래서 아래 무한 루프가 성립했다:
     * <pre>
     *   consume-batch → /branches/scene(오퍼 T1) → /choose(확정, evict)
     *                 → /branches/scene(backstop이 배치 N을 다시 집어 오퍼 T2) → /choose … 반복
     * </pre>
     * 오퍼는 1회용이지만 <b>재발급</b>이 막히지 않아 재확정이 무한히 가능했다(CLIMAX면 2E씩 반복 과금).
     * 캐시 상태가 아니라 <b>DB의 확정 기록</b>을 정본으로 삼아야 이 루프가 닫힌다.
     */
    private boolean alreadyResolved(Long roomId, int actNumber, int chapterNumber, int batchId) {
        return branchChoiceRepository.existsByRoom_IdAndActNumberAndChapterNumberAndSourceBatchId(
            roomId, actNumber, chapterNumber, batchId);
    }

    private Optional<ResolvedSignal> signalOfBatch(Long roomId, int batchId,
                                                   int actNumber, int chapterNumber) {
        // [P1-2] 이미 확정된 배치는 다시 오퍼의 근거가 되지 못한다.
        if (alreadyResolved(roomId, actNumber, chapterNumber, batchId)) return Optional.empty();
        Optional<SceneBatch> batch = batchCache.getBatch(roomId, batchId);
        if (batch.isEmpty()) return Optional.empty();
        BranchSignal signal = batch.get().branchSignal();
        if (signal == null || signal.level() == null || signal.level().isBlank()) return Optional.empty();
        return Optional.of(new ResolvedSignal(batchId, signal.level(), signal.context()));
    }

    /**
     * [적대적 리뷰 P2-d ②] 씬 분기 레벨 정규화 — <b>미지·LOCATION은 400이 아니라 MINOR로 강등</b>한다.
     *
     * <p>왜 400을 걷어냈나 — 레벨 문자열은 서버 내부 산출물(디렉터 결정 또는 LLM 응답)이다.
     * 그게 깨졌을 때 유저 진행을 막는 것은 책임 소재가 뒤바뀐 처리다. 구 코드에는 MINOR 폴백이
     * 있어 이런 케이스를 조용히 흡수했는데, 서버 확정으로 옮기면서 하드 400이 되어
     * <b>배치 하나가 세션 전체를 멈추는</b> 회귀가 생겼다.
     *
     * <p>왜 하필 MINOR인가 — 레벨이 결정하는 것은 옵션 수와 <b>에너지 비용</b>뿐이고
     * MINOR가 최저 비용(0E)이다. 즉 강등은 과금상 항상 안전한 방향이다.
     * (반대로 아무 레벨이나 신뢰하면 CLIMAX 2E가 임의로 청구될 수 있다.)
     *
     * <p>LOCATION이 여기로 오는 것은 클라이언트 상태 어긋남이 아니라 <b>배치 신호 오염</b>이다 —
     * LOCATION은 /branches/location 전용 경로이므로 씬 오퍼에서는 성립할 수 없다.
     */
    private BranchLevel normalizeSceneLevel(Long roomId, String rawLevel) {
        BranchLevel parsed = null;
        try {
            if (rawLevel != null && !rawLevel.isBlank()) {
                parsed = BranchLevel.valueOf(rawLevel.toUpperCase(Locale.ROOT));
            }
        } catch (IllegalArgumentException ignored) {
            // 아래 warn 경로로 흡수
        }
        if (parsed == null || parsed == BranchLevel.LOCATION) {
            log.warn("🎭 [BRANCH] Unusable scene branch level — demoted to MINOR | roomId={} | level={}",
                roomId, rawLevel);
            return BranchLevel.MINOR;
        }
        return parsed;
    }

    /**
     * 씬 분기 오퍼 발급.
     *
     * ⚠ 요청 본문의 {@code level}·{@code contextSummary}는 <b>받지 않는다</b>(B-4.b/d).
     *   전부 캐시된 배치의 branchSignal에서 서버가 확정한다.
     */
    @Transactional
    public BranchOptions generateSceneBranch(Long roomId, String username) {
        ChatRoom room = getOwnedRoom(roomId, username);
        TheaterState state = getState(roomId);

        ResolvedSignal signal = resolveServerBranchSignal(roomId, state);

        // [적대적 리뷰 P2-d ②] 미지·LOCATION 레벨은 400이 아니라 MINOR 강등 + warn.
        BranchLevel level = normalizeSceneLevel(roomId, signal.level());

        int actNumber = state.getCurrentAct().getNumber();
        int chapterNumber = state.getCurrentChapter();

        // [버그픽스 B-4.e] 멱등 재발급 — 같은 배치에 대한 재요청은 LLM 재호출 없이 같은 오퍼를 반환한다.
        //   prefetch + fallback 중복 호출, 모달 재진입, 새로고침이 전부 무료가 되고
        //   오퍼 만료가 유저에게 드러나지 않는다(docs/19 안건 14 (c)의 실현 방식).
        Optional<BranchOffer> reusable = batchCache.readBranchOffer(
            roomId, TheaterBatchCacheService.BranchOfferKind.SCENE);
        if (reusable.isPresent() && reusable.get().matches(
            level.name(), signal.batchId(), actNumber, chapterNumber)) {
            log.info("🎭 [BRANCH] offer reuse | roomId={} | level={} | sourceBatchId={}",
                roomId, level, signal.batchId());
            return reusable.get().toResponse();
        }

        String systemPrompt = buildBranchPrompt(state, level, signal.contextSummary());

        // [Phase III · 작업 3] 2단 모델 라우팅 — CLIMAX는 proModel, 그 외는 model
        String model = modelResolver.resolveBranchModel(room.getUser(), level);

        log.info("🎭 [BRANCH] generate | roomId={} | level={} | model={}",
            roomId, level, model);

        String llmResponse = openRouterClient.completeJson(
            model, systemPrompt,
            "Generate branch options now.", 1500, 0.85
        );

        List<BranchOption> options = parseBranchOptions(llmResponse, level, state);

        // [버그픽스 B-4.e] 빈 옵션 오퍼를 캐시에 남기면 멱등 재사용 때문에 6시간 동안
        //   선택 불가 상태가 고정된다. 저장하지 않고 거부해 재시도가 LLM을 다시 태우게 한다.
        if (options.isEmpty()) {
            log.warn("🎭 [BRANCH] Empty options from LLM | roomId={} | level={}", roomId, level);
            throw new BadRequestException("분기 옵션 생성에 실패했습니다. 새로고침 후 다시 시도해주세요.");
        }

        BranchOffer offer = new BranchOffer(
            generateBranchToken(),
            level.name(),
            signal.contextSummary(),
            extractContextNarration(llmResponse),
            signal.batchId(),
            actNumber, chapterNumber, state.getTotalSceneCount(),
            options,
            System.currentTimeMillis()
        );
        batchCache.putBranchOffer(roomId, TheaterBatchCacheService.BranchOfferKind.SCENE, offer);

        return offer.toResponse();
    }

    private String buildBranchPrompt(TheaterState state, BranchLevel level, String contextSummary) {
        int optionCount = level.getTypicalOptionCount();
        String levelDesc = switch (level) {
            case MINOR -> "톤 조정 수준의 가벼운 선택 (2지선다)";
            case MAJOR -> "Chapter 방향을 바꿀 중대 선택 (3지선다)";
            case CLIMAX -> "Act의 운명을 가를 결정적 선택 (3지선다)";
            default -> "선택";
        };

        // [Phase 5.5 UX Polish · R2] MINOR 빈도가 높아지므로 (Chapter당 3~4회)
        // 톤 다양화 강제로 단조로움 회피.
        String minorToneGuidance = level == BranchLevel.MINOR
            ? """
              # MINOR Tone Diversification (CRITICAL — Chapter당 빈발)
              The 2 options MUST use DIFFERENT tones from this set:
                AFFECTION    — emotional warmth, soft connection
                BOLD         — taking a step forward, daring
                WITTY        — humor, light deflection
                INTROSPECTIVE — turning inward, hesitating
              Pick 2 distinct tones. Never both AFFECTION or both BOLD.
              Each option's `detail` should be SHORT (under 30자) and lyrical, not heavy.
              Avoid stat_gates on MINOR branches — keep them tonal, not stat-locked.
              """
            : "";

        // [Phase 5.5 UX Polish · R3] 활성 감독 명령어가 있으면 옵션 생성 컨텍스트에 주입.
        // 이게 메모-신호와 분기 시스템의 시너지 (정책 E).
        // peek: 큐를 비우지 않음 — 분기 옵션 생성 후 다음 배치 생성 시 BatchGenerator가 consume.
        String activeCommand = batchCache.peekActiveDirectorCommand(state.getRoom().getId())
            .map(TheaterBatchCacheService.ActiveDirectorCommand::text)
            .orElse(null);
        String commandContext = (activeCommand != null && !activeCommand.isBlank())
            ? "\n# Active Director Command (current scene atmosphere)\n  \"" + activeCommand
            + "\"\n  Consider this when crafting option tones — they should feel coherent with this atmosphere.\n"
            : "";

        return """
            # Branch Generator — Theater Mode
            You generate %d narrative branch options for the current story moment.

            # Current Context
            Act %d — Chapter %d
            Protagonist stats: CHARM=%d WIT=%d BOLDNESS=%d INTELLECT=%d EMPATHY=%d
            Branch level: %s — %s

            # Context Summary
            %s
            %s
            %s
            # Output Format
            Return a single JSON object:
            {
              "context_narration": "1~2문장으로 분기 순간의 상황을 묘사",
              "options": [
                {
                  "label": "선택지 제목 (10~20자)",
                  "detail": "선택지의 뉘앙스/결과 암시 (MINOR=under 30자 / MAJOR/CLIMAX=20~40자)",
                  "tone": "normal | affection | bold | witty | introspective",
                  "stat_gate": { "stat": "CHARM | WIT | BOLDNESS | INTELLECT | EMPATHY", "min_value": 30 } | null,
                  "is_secret": false
                }
              ]
            }

            # Rules
            - %d개 옵션 중 1개는 stat_gate 조건이 있어도 좋다 (MINOR 제외)
            - stat_gate의 min_value는 30~70 사이
            - tone은 해당 선택의 감정 기류를 나타낸다
            - 각 옵션은 서로 명확히 다른 방향성을 가져야 한다
            - 한국어로 작성
            """.formatted(
            optionCount,
            state.getCurrentAct().getNumber(), state.getCurrentChapter(),
            state.getStatCharm(), state.getStatWit(), state.getStatBoldness(),
            state.getStatIntellect(), state.getStatEmpathy(),
            level.name(), levelDesc,
            contextSummary != null ? contextSummary : "(no summary)",
            commandContext,
            minorToneGuidance,
            optionCount
        );
    }

    private String extractContextNarration(String llmResponse) {
        try {
            var node = objectMapper.readTree(cleanJson(llmResponse));
            return node.path("context_narration").asText("");
        } catch (JsonProcessingException e) {
            return "";
        }
    }

    private List<BranchOption> parseBranchOptions(String llmResponse, BranchLevel level, TheaterState state) {
        try {
            var node = objectMapper.readTree(cleanJson(llmResponse));
            var optsNode = node.path("options");
            if (!optsNode.isArray()) return List.of();

            List<BranchOption> result = new ArrayList<>();
            int idx = 0;
            for (var opt : optsNode) {
                String label = opt.path("label").asText("");
                String detail = opt.path("detail").asText("");
                String tone = opt.path("tone").asText("normal");
                boolean isSecret = opt.path("is_secret").asBoolean(false);

                StatGate gate = null;
                boolean unlocked = true;
                var gateNode = opt.path("stat_gate");
                if (!gateNode.isMissingNode() && !gateNode.isNull()) {
                    String statName = gateNode.path("stat").asText("");
                    int minValue = gateNode.path("min_value").asInt(0);
                    try {
                        AvatarStat stat = AvatarStat.valueOf(statName.toUpperCase());
                        gate = new StatGate(stat.name(), minValue);
                        unlocked = state.getStat(stat) >= minValue;
                    } catch (IllegalArgumentException ignored) {}
                }

                result.add(new BranchOption(
                    idx++, label, detail, tone,
                    level.getEnergyCost(),
                    null, null, null,
                    gate, unlocked, isSecret
                ));
            }
            return result;
        } catch (JsonProcessingException e) {
            log.warn("🎭 [BRANCH] Parse failed: {}", e.getMessage());
            return List.of();
        }
    }

    private String cleanJson(String text) {
        if (text == null) return "{}";
        String s = text.trim();
        if (s.startsWith("```")) {
            int n = s.indexOf('\n');
            if (n > 0) s = s.substring(n + 1);
            int end = s.lastIndexOf("```");
            if (end > 0) s = s.substring(0, end);
        }
        int start = s.indexOf('{');
        int end = s.lastIndexOf('}');
        if (start >= 0 && end > start) return s.substring(start, end + 1);
        return s.trim();
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  3. 선택 적용
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /**
     * 분기 확정.
     *
     * ⚠ 시그니처가 축소됐다 — {@code level}·{@code optionsSnapshot}을 더 이상 받지 않는다.
     *   하위호환 오버로드를 남기지 않는 이유는 CLAUDE.md §2-6: 오버로드가 있으면 호출부가
     *   조용히 낡은(= 클라 값을 믿는) 경로로 컴파일된다. 컴파일러가 호출부를 전수로 드러내게 한다.
     *
     * @param chosenIndex  선택한 옵션 인덱스 — <b>서버가 매긴</b> index를 그대로 쓴다.
     * @param branchToken  서버가 발급한 오퍼 토큰. <b>필수</b>(B-4.e).
     */
    @Transactional
    public void applyBranchChoice(Long roomId, String username,
                                  int chosenIndex, String branchToken) {
        ChatRoom room = getOwnedRoom(roomId, username);
        TheaterState state = getState(roomId);

        // ─── [버그픽스 B-4.e] 제시 증거 검증 (fail-closed) ───
        //  토큰 부재/불일치는 400. 유저 복구 경로는 "새로고침" — 오퍼 발급이 멱등이므로
        //  새로고침하면 LLM 재호출 없이 같은 오퍼(토큰 포함)를 다시 받아 그대로 진행된다.
        if (branchToken == null || branchToken.isBlank()) {
            throw new BadRequestException("분기 토큰이 없습니다. 새로고침 후 다시 시도해주세요.");
        }
        // [적대적 리뷰 P2-e] 오퍼 키가 씬/LOCATION으로 갈라졌다. 요청의 level은 여전히 읽지 않으므로
        //   토큰으로 어느 쪽 오퍼인지 식별한다(두 키를 명시 조회 — 패턴 SCAN 없음).
        MatchedOffer matched = findOfferByToken(roomId, branchToken)
            .orElseThrow(() -> {
                log.warn("🎭 [BRANCH] Branch offer missing or token mismatch | roomId={} | user={}",
                    roomId, username);
                return new BadRequestException("분기 정보가 만료되었습니다. 새로고침 후 다시 시도해주세요.");
            });
        BranchOffer offer = matched.offer();

        // ─── [버그픽스 B-4.b] 레벨은 오퍼 원본에서만 온다 (요청 본문 무시) ───
        //  기존엔 컨트롤러가 클라 level을 valueOf하고 실패 시 MINOR로 폴백해서
        //  level:"MINOR" 한 줄로 CLIMAX(2E)를 0E에 확정할 수 있었다.
        BranchLevel level;
        try {
            level = BranchLevel.valueOf(offer.level());
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new BadRequestException("분기 정보가 올바르지 않습니다. 새로고침 후 다시 시도해주세요.");
        }

        // ─── [적대적 리뷰 P2-c] 오퍼 좌표 검증 ───
        //  오퍼 TTL은 6h다. Chapter/Act가 넘어간 뒤에도 옛 오퍼가 살아 있으면 그 분기가
        //  새 좌표에 기록되어 진행·과금이 한 칸 어긋난다. 아래 확정 기록 게이트와 유니크 키가
        //  전부 (act, chapter)를 축으로 삼으므로 여기서 좌표를 못 박아야 정합이 유지된다.
        int actNumber = state.getCurrentAct().getNumber();
        int chapterNumber = state.getCurrentChapter();
        if (offer.actNumber() != actNumber || offer.chapterNumber() != chapterNumber) {
            log.warn("🎭 [BRANCH] Stale offer position | roomId={} | offer=({},{}) | state=({},{})",
                roomId, offer.actNumber(), offer.chapterNumber(), actNumber, chapterNumber);
            throw new BadRequestException("분기 정보가 만료되었습니다. 새로고침 후 다시 시도해주세요.");
        }

        // ─── [적대적 리뷰 P1-2] 확정 이력을 소비 게이트로 ───
        //  오퍼 1회용 evict만으로는 **재발급**을 막지 못해서, 같은 배치에 대해
        //  /branches/scene → /choose 를 반복하면 무한 재확정(CLIMAX면 2E씩 반복 과금)이 됐다.
        //  캐시가 아니라 DB의 확정 기록이 정본이다.
        if (alreadyResolved(roomId, actNumber, chapterNumber, offer.sourceBatchId())) {
            log.warn("🎭 [BRANCH] Duplicate confirm rejected | roomId={} | act={} | chapter={} | sourceBatchId={}",
                roomId, actNumber, chapterNumber, offer.sourceBatchId());
            throw new BadRequestException("이미 확정한 분기입니다.");
        }

        // ─── [버그픽스 B-4.a/f] 옵션은 오퍼 원본에서 재판정 ───
        //  요청 본문의 optionsSnapshot은 읽지 않는다 → 스탯 잠금 우회(a)와
        //  optionsSnapshot 누락 NPE 500(f)이 함께 소멸한다.
        List<BranchOption> options = offer.options();
        if (options == null || chosenIndex < 0 || chosenIndex >= options.size()) {
            throw new BadRequestException("잘못된 선택 인덱스입니다.");
        }
        BranchOption chosen = options.get(chosenIndex);

        if (!chosen.unlocked()) {
            throw new BadRequestException("이 선택지는 아직 해금되지 않았습니다.");
        }

        // ─── [버그픽스 B-4.c] 방 소속 히로인 집합 — 화자 지정·일러 트리거의 심층 방어 ───
        //  characterRepository.findById는 전역 조회라 방 밖·타인 UGC 캐릭터도 잡힌다.
        //  오퍼 원본 재판정으로 위조 경로는 이미 닫히지만, state.currentHeroineId가
        //  다른 경로로 오염된 경우의 2차 방어로 소속 필터를 함께 건다.
        Set<Long> roomHeroineIds = affectionRepository.findByRoom_Id(roomId).stream()
            .map(a -> a.getCharacter().getId())
            .collect(Collectors.toSet());

        if (level == BranchLevel.LOCATION) {
            // 오퍼 발급 단계(:generateLocationBranch)의 멀티 히로인 조건을 확정 단계에서도 재확인.
            if (roomHeroineIds.size() < 2) {
                throw new BadRequestException("장소 선택은 멀티 히로인 세션에서만 가능합니다.");
            }
            if (chosen.heroineId() != null && !roomHeroineIds.contains(chosen.heroineId())) {
                throw new BadRequestException("이 방의 히로인이 아닙니다.");
            }
        }

        // ─── [버그픽스 B-4.b] 에너지는 enum 확정값으로만 과금 ───
        int energyCost = level.getEnergyCost();
        if (energyCost > 0) {
            User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new NotFoundException("유저를 찾을 수 없습니다."));
            user.consumeEnergy(energyCost);
        }

        // [버그픽스 B-4.d] 컨텍스트도 서버 원본(오퍼 발급 시 배치 branchSignal에서 확정)만 쓴다.
        String contextSummary = offer.contextSummary();

        String optionsJson;
        try {
            // [버그픽스 B-4.a/d] DB에는 서버 진실만 영속한다 (조작된 스냅샷 전문이 아니라 오퍼 원본).
            optionsJson = objectMapper.writeValueAsString(options);
        } catch (JsonProcessingException e) {
            optionsJson = "[]";
        }

        TheaterBranchChoice choice = TheaterBranchChoice.record(
            room, level, state.getCurrentAct(), state.getCurrentChapter(),
            state.getTotalSceneCount(), optionsJson,
            chosenIndex, chosen.label(),
            chosen.heroineId(), energyCost,
            // [적대적 리뷰 P2-b] 유니크 키의 마지막 축 — "그 분기를 실은 배치".
            offer.sourceBatchId()
        );
        try {
            // ─── [적대적 리뷰 P2-b] 동시 2중 과금의 최후 방어선 ───
            //  위 확정 기록 게이트는 check-then-act라 원자적이지 않다. 거의 동시에 도착한 두
            //  /choose가 둘 다 조회 단계를 통과할 수 있고, 씬 분기는 TheaterState를 수정하지 않아
            //  @Version 낙관적 락도 걸리지 않는다 → CLIMAX 2E가 4E가 된다.
            //  saveAndFlush로 **이 트랜잭션 안에서** V29 유니크 인덱스에 부딪히게 해서,
            //  진 쪽이 롤백(에너지 차감 포함)되고 400으로 끝나게 한다.
            //  (save만 쓰면 위반이 커밋 시점에 터져 500 + 아래 afterCommit 미실행이 된다.)
            branchChoiceRepository.saveAndFlush(choice);
        } catch (DataIntegrityViolationException e) {
            log.warn("🎭 [BRANCH] Concurrent duplicate confirm rejected by DB | roomId={} | sourceBatchId={}",
                roomId, offer.sourceBatchId());
            throw new BadRequestException("이미 확정한 분기입니다.");
        }

        if (level == BranchLevel.LOCATION && chosen.heroineId() != null) {
            state.setCurrentHeroine(chosen.heroineId());
        }

        String newBranchContext = String.format(
            "유저가 '%s' 선택함 (%s, %s). %s",
            chosen.label(), level.name(), chosen.tone(),
            contextSummary != null ? contextSummary : ""
        );
        Long heroineHintId = (level != BranchLevel.LOCATION) ? state.getCurrentHeroineId() : null;
        int invalidateFrom = state.getCurrentBatchId();
        TheaterBatchCacheService.BranchOfferKind consumedKind = matched.kind();

        // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
        //  [적대적 리뷰 P2-a] Redis 부수효과는 **커밋 이후에** 실행한다
        // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
        //  기존엔 @Transactional 본문 마지막에서 바로 돌아 **커밋 전에** Redis를 건드렸다.
        //  커밋이 실패하면(낙관적 락 충돌·DB 순단·플러시 예외) DB는 롤백되는데 오퍼만 사라져
        //  정상 재시도가 400을 맞는다 — 유저는 에너지도 안 나가고 분기도 못 고르는 상태로 멈춘다.
        //  반대로 컨텍스트("active")·hint를 미리 넣어 두면 확정되지도 않은 분기가 다음 배치
        //  프롬프트에 실린다. Redis는 트랜잭션에 참여하지 않으므로 커밋 성공을 확인하고 쓴다.
        afterCommit(() -> {
            // [Phase 6 도그푸딩 #2 결함 B / Patch B-2] LOCATION 외 분기에서도 다음 chapter용
            //   heroine hint를 보존. Act 3 마지막 chapter에서 1번 캐릭터와 깊은 분기를 진행했는데
            //   다음 Act/Chapter 진입 시 heroine이 갑자기 바뀌어 맥락 단절되던 결함을 차단한다.
            //   LOCATION은 이미 state.setCurrentHeroine으로 즉시 반영되므로 hint 불필요.
            if (heroineHintId != null) {
                batchCache.saveHeroineHint(roomId, heroineHintId);
            }
            batchCache.invalidateBatchesFrom(roomId, invalidateFrom);
            batchCache.putBranchContext(roomId, "active", newBranchContext);

            // ─── [버그픽스 B-4.e] 오퍼 1회용 소비 — 리플레이(같은 토큰 재사용) 차단 ───
            //  ⚠ 조회 시점이 아니라 확정 성공 후에 evict한다. 조회에서 지우면 새로고침 복구가 막힌다.
            //  ⚠ [P2-e] 확정한 종류의 오퍼만 지운다 — 씬 확정이 LOCATION 오퍼를 축출하면 안 된다.
            //  ⚠ MAJOR 중복 확정 방지는 ① 배치 생성 시 마킹(TheaterBatchGenerator: markMajorBranchDoneInChapter)
            //    ② 오퍼 batchId 멱등 ③ 이 1회용 evict ④ [P1-2] 확정 기록 게이트 + V29 유니크의 4중이다.
            //    여기서 플래그를 또 세우면 같은 chapter의 재발동 판정을 이중으로 흔드므로 세우지 않는다.
            batchCache.evictBranchOffer(roomId, consumedKind);
            // [적대적 리뷰 P1-3] 마커도 함께 소멸 — 분기 시점이 지났다는 뜻이다.
            batchCache.clearPendingBranch(roomId);
        });

        log.info("🎭 [BRANCH] Applied | roomId={} | level={} | chosen={} | cost={}",
            roomId, level, chosen.label(), energyCost);

        // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
        //  [Phase 5.5 UX Polish · R6] BRANCH_TAKEN 자동 캡처
        // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
        //  분기 종류에 따라 일러스트 화자 히로인 결정:
        //   - LOCATION: chosen.heroineId() (선택된 장소에 묶인 히로인)
        //   - 그 외:    state.currentHeroineId (현재 화자)
        //  MINOR는 빈도가 높으므로 노트만 생성하고 일러스트는 X (autoNoteService에서 처리).
        try {
            Long speakerHeroineId = (level == BranchLevel.LOCATION && chosen.heroineId() != null)
                ? chosen.heroineId()
                : state.getCurrentHeroineId();
            // [버그픽스 B-4.c] 전역 findById 전에 방 소속 필터를 건다.
            //   이 값이 오염되면 triggerIllustration(외부 유료 호출)이 방 밖·타인 UGC 캐릭터로 발사된다.
            if (speakerHeroineId != null && !roomHeroineIds.contains(speakerHeroineId)) {
                log.warn("🎭 [BRANCH] speakerHeroineId not in room roster — dropped | roomId={} | heroineId={}",
                    roomId, speakerHeroineId);
                speakerHeroineId = null;
            }
            Character speakerHeroine = speakerHeroineId != null
                ? characterRepository.findById(speakerHeroineId).orElse(null)
                : null;
            autoNoteService.captureBranchTaken(
                room, state, level.name(), chosen.label(), speakerHeroine);
        } catch (Exception e) {
            log.warn("🎭 [BRANCH] BRANCH_TAKEN auto-note failed (non-fatal): {}", e.getMessage());
        }

        // (오퍼 evict·마커 clear·캐시 무효화는 위 afterCommit 블록에서 커밋 이후에 실행된다 — P2-a)
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  Helpers
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /** 토큰으로 식별된 오퍼 + 그 오퍼가 담겨 있던 키 종류(확정 후 그 종류만 evict한다). */
    private record MatchedOffer(TheaterBatchCacheService.BranchOfferKind kind, BranchOffer offer) {}

    /**
     * [적대적 리뷰 P2-e] 씬·LOCATION 두 키를 명시적으로 조회해 토큰이 맞는 오퍼를 찾는다.
     *
     * <p>요청 본문의 level은 여전히 신뢰하지 않으므로(B-4.b) 어느 키를 봐야 하는지 알 수 없다.
     * 키가 두 개뿐이라 명시 조회로 충분하다 — 운영 Redis에서 패턴 SCAN은 쓰지 않는다.
     */
    private Optional<MatchedOffer> findOfferByToken(Long roomId, String branchToken) {
        for (TheaterBatchCacheService.BranchOfferKind kind
            : TheaterBatchCacheService.BranchOfferKind.values()) {
            Optional<BranchOffer> candidate = batchCache.readBranchOffer(roomId, kind);
            if (candidate.isPresent() && branchToken.equals(candidate.get().token())) {
                return Optional.of(new MatchedOffer(kind, candidate.get()));
            }
        }
        return Optional.empty();
    }

    /**
     * [적대적 리뷰 P2-a] 트랜잭션 커밋 이후에 실행 — Redis는 트랜잭션에 참여하지 않는다.
     *
     * <p>트랜잭션 동기화가 활성이 아니면(테스트·비트랜잭션 호출) 즉시 실행한다 —
     * 조용히 건너뛰면 캐시 무효화가 통째로 사라져 더 나쁜 상태가 된다.
     */
    private void afterCommit(Runnable action) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            action.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                action.run();
            }
        });
    }

    /**
     * [버그픽스 B-4.e] 오퍼 토큰 생성.
     *
     * ⚠ 기존 구현은 {@code level + "-" + roomId + "-" + System.currentTimeMillis()}로
     *   <b>추측 가능</b>했다. 레벨·방ID는 유저가 알고 ms만 브루트포스하면 되므로,
     *   토큰을 필수화해도 위조로 그대로 뚫린다. UUID여야 "서버가 제시했다는 증거" 역할을 한다.
     */
    private String generateBranchToken() {
        return UUID.randomUUID().toString();
    }

    private ChatRoom getOwnedRoom(Long roomId, String username) {
        ChatRoom room = chatRoomRepository.findById(roomId)
            .orElseThrow(() -> new NotFoundException("방을 찾을 수 없습니다."));
        if (!room.getUser().getUsername().equals(username)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "접근 권한이 없습니다.");
        }
        return room;
    }

    private TheaterState getState(Long roomId) {
        return theaterStateRepository.findByRoom_Id(roomId)
            .orElseThrow(() -> new NotFoundException("Theater 세션이 없습니다."));
    }
}