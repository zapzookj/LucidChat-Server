package com.spring.aichat.service.story;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.spring.aichat.config.OpenAiProperties;
import com.spring.aichat.domain.character.Character;
import com.spring.aichat.domain.chat.ChatLogDocument;
import com.spring.aichat.domain.chat.ChatLogMongoRepository;
import com.spring.aichat.domain.chat.ChatRoom;
import com.spring.aichat.domain.chat.ChatRoomRepository;
import com.spring.aichat.domain.heroine.ChatRoomHeroine;
import com.spring.aichat.domain.heroine.ChatRoomHeroineRepository;
import com.spring.aichat.domain.memory.MemorySummary;
import com.spring.aichat.domain.memory.MemorySummaryRepository;
import com.spring.aichat.domain.user.User;
import com.spring.aichat.dto.openai.OpenAiChatRequest;
import com.spring.aichat.dto.openai.OpenAiMessage;
import com.spring.aichat.dto.story.StoryV2Requests.CarryOverRequest;
import com.spring.aichat.exception.BadRequestException;
import com.spring.aichat.exception.BusinessException;
import com.spring.aichat.exception.ErrorCode;
import com.spring.aichat.exception.NotFoundException;
import com.spring.aichat.external.OpenRouterClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * [docs/55 Carry-Over] 자유 모드(SANDBOX) 관계 → 스토리(V2) 방 이식.
 *
 * <p>종원 결정(2026-10-28): ① 세계관 소속 제약은 방 생성 검증이 담당 ② 생성 시점 1회 임포트(덮어쓰기 포함)
 * ③ 스탯 100% 승계 + 엔딩 자격 유예({@link EndingEligibilityService}) ④ 로그 원문 전량(≤{@value #MAX_LOG_COPY}, 가시 로그)
 * 복사 + 자유 모드 {@code MemorySummary}를 히로인 시점으로 재서술해 {@code HeroineMemorySummary(SANDBOX_IMPORT)}로
 * + 경계 브리지 SYSTEM 로그(히로인당 1건, 모든 이식 로그 뒤) ⑤ 복사이므로 자유 모드 방은 그대로
 * ⑥ 시크릿 콘텐츠는 게이팅 없이 그대로 이관(추후 재검토).
 *
 * <p>왜 원문 전량이어도 되는가: LLM 히스토리 창은 최근 20 로그로 고정({@code findTop20ByRoomIdOrderByCreatedAtDesc})이라
 * 길이가 프롬프트 비용을 늘리지 않는다. 20건 밖의 맥락은 요약 레이어로만 LLM에 닿는다.
 *
 * <p>트랜잭션: {@link StoryV2Service#createOrReuseRoom} TX 안에서 {@link #validate}→(히로인 생성)→{@link #apply} 순.
 * JPA 쓰기(스탯·기억 요약·carryOverAt)는 TX로 보호된다. <b>Mongo 쓰기(로그 복사·브리지)는 커밋 이후</b>
 * ({@code TransactionSynchronization.afterCommit})로 미룬다 — 덮어쓰기 경로에서 TX가 롤백되면 roomId가 살아 있는 채
 * "이식 로그는 있고 이 방 로그는 0건"인 반쪽 상태가 남아 다음 진입에 엉뚱한 오프닝이 뜨기 때문이다(독립 검토 #2).
 * afterCommit은 HTTP 응답 전에 같은 스레드에서 돌므로 프론트의 첫 {@code /logs} 조회와 경합하지 않는다.
 * 재서술 LLM 1회는 실패해도 원문 요약으로 폴백해 방 생성을 막지 않는다.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class StoryCarryOverService {

    /** 원문 로그 복사 상한(최신순, 가시 로그 기준). 자유 방 1개가 이보다 길면 오래된 쪽은 요약 레이어로만 남는다. */
    static final int MAX_LOG_COPY = 200;
    /** 요약이 아직 없을 때(유저 10턴 미만) 재서술 입력으로 쓰는 최근 로그 수. */
    static final int FALLBACK_LOG_WINDOW = 20;
    static final int MAX_MEMORY_ROWS = 20;
    static final int MAX_MEMORY_CHARS = 400;
    static final int MAX_BRIDGE_CHARS = 200;

    private final ChatRoomRepository chatRoomRepository;
    private final ChatRoomHeroineRepository heroineRepository;
    private final ChatLogMongoRepository chatLogRepository;
    private final MemorySummaryRepository memorySummaryRepository;
    private final HeroineMemoryService heroineMemoryService;
    private final OpenRouterClient openRouterClient;
    private final OpenAiProperties props;
    private final ObjectMapper objectMapper;
    /** 공식/UGC 공용 월드 뷰 — 재서술 프롬프트의 세계관 이름(UGC 방은 {@code room.getWorld()}가 null). */
    private final WorldViewService worldViewService;

    /** 검증을 통과한 이식 1건 — 방 생성 이후 {@link #apply}에 그대로 넘긴다. */
    public record Plan(ChatRoom sandboxRoom, Character character, boolean includeLogs) {}

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  [1] 검증 — 방 생성 전 (위반 시 400/403/404 → 방 생성 자체가 롤백)
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /**
     * @param heroineIds 이번 스토리의 캐스트 — carryOver의 characterId는 여기 포함돼야 한다
     * @return 적용할 계획 목록(요청이 없으면 빈 리스트)
     */
    public List<Plan> validate(User user, List<Long> heroineIds, List<CarryOverRequest> requests) {
        if (requests == null || requests.isEmpty()) return List.of();

        Set<Long> seen = new HashSet<>();
        List<Plan> plans = new ArrayList<>();
        for (CarryOverRequest req : requests) {
            if (req == null || req.characterId() == null || req.sandboxRoomId() == null) {
                throw new BadRequestException("carryOvers: characterId와 sandboxRoomId는 필수입니다.");
            }
            if (!seen.add(req.characterId())) {
                throw new BadRequestException("carryOvers: 같은 캐릭터가 두 번 지정됐습니다: " + req.characterId());
            }
            if (heroineIds == null || !heroineIds.contains(req.characterId())) {
                throw new BadRequestException("carryOvers: 이번 스토리에 선택한 히로인이 아닙니다: " + req.characterId());
            }
            ChatRoom sandbox = chatRoomRepository.findById(req.sandboxRoomId())
                .orElseThrow(() -> new NotFoundException("이어갈 자유 모드 방을 찾을 수 없습니다: " + req.sandboxRoomId()));
            if (sandbox.getUser() == null || !sandbox.getUser().getId().equals(user.getId())) {
                throw new BusinessException(ErrorCode.FORBIDDEN, "내 자유 모드 방이 아닙니다: " + req.sandboxRoomId());
            }
            if (!sandbox.isSandboxMode() || sandbox.getCharacter() == null) {
                throw new BadRequestException("자유 모드 방만 이어갈 수 있습니다: " + req.sandboxRoomId());
            }
            if (!sandbox.getCharacter().getId().equals(req.characterId())) {
                throw new BadRequestException("자유 모드 방의 캐릭터와 선택한 히로인이 다릅니다: " + req.characterId());
            }
            plans.add(new Plan(sandbox, sandbox.getCharacter(), !Boolean.FALSE.equals(req.includeLogs())));
        }
        return plans;
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  [2] 적용 — ChatRoomHeroine 생성 직후, 같은 TX
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /** 계획 1건의 산출 — Mongo 쓰기는 커밋 후로 미루므로 메모리에 모아 둔다. */
    private record Prepared(Long sandboxRoomId, List<ChatLogDocument> copies, String bridgeText) {}

    /**
     * 계획별로 ① 스탯 스냅샷(JPA) ② 로그 복사본 준비 ③ 기억 재서술·저장(JPA) 을 하고,
     * 모든 계획이 끝난 뒤 ④ 브리지를 <b>전체 이식 로그의 가장 뒤</b>에 놓고 ⑤ 방에 이식 시각을 찍은 다음
     * ⑥ 로그 복사본·브리지를 커밋 후 Mongo에 쓴다. 계획이 비어 있으면 아무것도 하지 않는다.
     *
     * <p>브리지를 계획별 '그 방의 마지막 로그 + 1초'에 두면, 복수 히로인 이식에서 두 방의 로그가 시간순으로 섞일 때
     * 먼저 끝난 방의 브리지가 다른 방 로그 한가운데 박힌다(독립 검토 #1) — 그래서 앵커는 전체 최댓값이다.
     */
    public void apply(ChatRoom storyRoom, List<Plan> plans) {
        if (plans == null || plans.isEmpty()) return;
        if (!storyRoom.isStoryMode()) {
            throw new IllegalStateException("carry-over target must be a STORY room: id=" + storyRoom.getId());
        }
        User user = storyRoom.getUser();
        String nickname = storyRoom.getEffectiveNickname(user);
        String worldName = resolveWorldName(storyRoom);

        List<Prepared> prepared = new ArrayList<>(plans.size());
        LocalDateTime anchor = null;   // 모든 이식 로그 중 가장 늦은 시각 — 브리지 앵커

        for (Plan plan : plans) {
            ChatRoom sandbox = plan.sandboxRoom();
            Character character = plan.character();

            // ① 스탯·관계 스냅샷 (결정 ③: 100% 승계)
            ChatRoomHeroine heroine = heroineRepository
                .findByChatRoom_IdAndCharacter_Id(storyRoom.getId(), character.getId())
                .orElseThrow(() -> new IllegalStateException(
                    "carry-over target heroine missing: room=" + storyRoom.getId() + ", char=" + character.getId()));
            heroine.seedFromSandbox(sandbox);
            heroineRepository.save(heroine);

            // ② 로그 원문 복사본 (결정 ④ — 가시 로그 최신 200건, 원본 createdAt 보존) — 쓰기는 커밋 후
            List<ChatLogDocument> copies = plan.includeLogs()
                ? buildCopies(sandbox.getId(), storyRoom.getId())
                : List.of();
            for (ChatLogDocument c : copies) {
                if (anchor == null || c.getCreatedAt().isAfter(anchor)) anchor = c.getCreatedAt();
            }

            // ③ 기억 재서술 → HeroineMemorySummary(SANDBOX_IMPORT) — JPA, TX 안
            Narration narration = narrate(sandbox, character, nickname, worldName);
            heroineMemoryService.importSummaries(storyRoom.getId(), character.getId(), user.getId(), narration.memories());

            String bridgeText = narration.bridge() != null ? narration.bridge() : defaultBridge(character.getName());
            prepared.add(new Prepared(sandbox.getId(), copies, bridgeText));

            log.info("🔗 [CARRY-OVER] room={} ← sandbox={} (char={}): status={}, affection={}, logs={}, memories={}, llm={}",
                storyRoom.getId(), sandbox.getId(), character.getId(),
                heroine.getStatusLevel(), heroine.getStatAffection(),
                copies.size(), narration.memories().size(), narration.fromLlm() ? "ok" : "fallback");
        }

        // ④ 브리지 — 전체 이식 로그 뒤에, 계획 순서대로 1초 간격(정렬 결정성)
        LocalDateTime bridgeAt = anchor != null ? anchor.plusSeconds(1) : LocalDateTime.now();
        List<ChatLogDocument> copies = new ArrayList<>();
        List<ChatLogDocument> bridges = new ArrayList<>(prepared.size());
        for (Prepared p : prepared) {
            copies.addAll(p.copies());
            bridges.add(ChatLogDocument.carryOverBridge(storyRoom.getId(), p.bridgeText(), p.sandboxRoomId(), bridgeAt));
            bridgeAt = bridgeAt.plusSeconds(1);
        }

        // ⑤ 이식 시각 — 엔딩 유예 판정 기준
        storyRoom.markCarriedOver();

        // ⑥ Mongo 쓰기 — 커밋 후
        scheduleMongoWrites(storyRoom.getId(), copies, bridges);
    }

    /** 세계관 표시명 — 공식은 World, UGC는 WorldView(UgcWorld.name)로. 둘 다 없으면 "이 세계". */
    private String resolveWorldName(ChatRoom storyRoom) {
        try {
            if (worldViewService != null) {
                WorldView view = worldViewService.resolveForRoom(storyRoom);
                if (view != null && view.displayName() != null && !view.displayName().isBlank()) {
                    return view.displayName();
                }
            }
        } catch (Exception e) {
            log.debug("[CARRY-OVER] world name resolve failed (fallback): {}", e.getMessage());
        }
        return storyRoom.getWorld() != null ? storyRoom.getWorld().getDisplayName() : "이 세계";
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  로그 복사
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    private List<ChatLogDocument> buildCopies(Long sandboxRoomId, Long storyRoomId) {
        // 가시 로그만 최신 200건 — hidden(디렉터 주입·액션 마커)은 원본 방 귀속이라 상한 계산에서도 뺀다(독립 검토 #4)
        List<ChatLogDocument> newestFirst = chatLogRepository
            .findTop200ByRoomIdAndHiddenNotOrderByCreatedAtDesc(sandboxRoomId, true);
        if (newestFirst == null || newestFirst.isEmpty()) return List.of();

        List<ChatLogDocument> copies = new ArrayList<>(newestFirst.size());
        for (int i = newestFirst.size() - 1; i >= 0; i--) {   // 오래된 순
            ChatLogDocument src = newestFirst.get(i);
            if (src.isHidden()) continue;                       // 방어 — 질의가 걸러도 한 번 더
            if (src.getCreatedAt() == null) continue;           // 시각 결손 로그는 정렬을 보장할 수 없어 제외
            copies.add(ChatLogDocument.copyForCarryOver(src, storyRoomId));
        }
        return copies;
    }

    /**
     * 로그 복사본·브리지를 Mongo에 쓴다 — TX 동기화가 살아 있으면 커밋 후, 아니면(테스트·비TX 호출) 즉시.
     * 커밋 후 실패는 방 생성 결과를 뒤집을 수 없으므로 로그만 남긴다(스탯·기억은 이미 영속, 오프닝은 로그 0건으로 정상 발사).
     */
    private void scheduleMongoWrites(Long roomId, List<ChatLogDocument> copies, List<ChatLogDocument> bridges) {
        Runnable write = () -> {
            try {
                if (!copies.isEmpty()) chatLogRepository.saveAll(copies);
                for (ChatLogDocument b : bridges) chatLogRepository.save(b);
                log.info("🔗 [CARRY-OVER] mongo writes done: room={}, copies={}, bridges={}", roomId, copies.size(), bridges.size());
            } catch (Exception e) {
                log.error("❌ [CARRY-OVER] mongo writes failed after commit: room={} | {}", roomId, e.getMessage(), e);
            }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { write.run(); }
            });
        } else {
            write.run();
        }
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  기억 재서술 — 자유 모드 요약(분석형 3인칭) → 히로인 시점 1인칭, 장소·시대 디테일 추상화
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    record Narration(List<String> memories, String bridge, boolean fromLlm) {}

    private Narration narrate(ChatRoom sandbox, Character character, String nickname, String worldName) {
        List<String> sourceSummaries = memorySummaryRepository.findByRoomIdOrderByCreatedAtAsc(sandbox.getId())
            .stream()
            .map(MemorySummary::getSummary)
            .filter(s -> s != null && !s.isBlank())
            .toList();

        boolean fromSummaries = !sourceSummaries.isEmpty();
        String source;
        if (fromSummaries) {
            source = sourceSummaries.stream().map(s -> "- " + s.trim()).collect(Collectors.joining("\n"));
        } else {
            // 요약이 아직 없으면(유저 10턴 미만) 최근 로그 원문으로 1회 요약
            List<ChatLogDocument> recent = new ArrayList<>(
                chatLogRepository.findTop20ByRoomIdOrderByCreatedAtDesc(sandbox.getId()));
            Collections.reverse(recent);
            source = recent.stream()
                .filter(l -> !l.isHidden() && l.getCleanContent() != null && !l.getCleanContent().isBlank())
                .limit(FALLBACK_LOG_WINDOW)
                .map(l -> l.getRole() + ": " + l.getCleanContent().trim())
                .collect(Collectors.joining("\n"));
            if (source.isBlank()) return new Narration(List.of(), null, false);   // 대화 자체가 없음 → 브리지만
        }

        try {
            String prompt = buildNarrationPrompt(character.getName(), nickname, worldName, source, fromSummaries);
            String raw = openRouterClient.chatCompletion(OpenAiChatRequest.withoutPenalty(
                props.sentimentModel(),
                List.of(OpenAiMessage.system(prompt)),
                0.4));
            Narration parsed = parseNarration(raw);
            if (parsed != null && !parsed.memories().isEmpty()) return parsed;
            log.warn("🔗 [CARRY-OVER] narration unparsable — fallback to raw summaries: sandbox={}", sandbox.getId());
        } catch (Exception e) {
            log.warn("🔗 [CARRY-OVER] narration LLM failed — fallback to raw summaries: sandbox={} | {}",
                sandbox.getId(), e.getMessage());
        }
        // 폴백: 원문 요약 그대로(요약이 없던 경우엔 기억 없이 브리지만)
        return new Narration(sourceSummaries, null, false);
    }

    private String buildNarrationPrompt(String characterName, String nickname, String worldName,
                                        String source, boolean itemized) {
        String unit = itemized
            ? "입력 항목 하나당 기억 1~2문장으로, 항목 수와 순서를 그대로 유지하라."
            : "기록 전체를 시간 순서대로 1~3개의 기억으로 압축하라.";
        return """
            당신은 캐릭터의 기억을 정리하는 작가다.
            아래는 유저 "%s"와(과) 캐릭터 "%s"가 자유 모드(1:1 대화)에서 쌓아 온 기록이다.
            이 기록을 "%s"의 1인칭 시점 기억으로 다시 써라. 이 기억은 이제 세계관 "%s"의 이야기에서 쓰인다.

            원칙:
            - %s
            - 감정·사건·관계의 변화, 유저에 대해 알게 된 사실은 보존한다. 새로운 사건을 지어내지 마라.
            - 구체적 장소·시대·현대 문물 디테일은 세계관과 충돌할 수 있으니 추상화한다(예: "카페" → "자주 가던 곳").
            - 수위와 표현은 원문을 따른다. 완곡하게 바꾸거나 검열하지 마라.
            - bridge: 이 관계가 "%s"로 이어지는 순간을 알리는 3인칭 나레이션 1문장. 유저의 행동을 가정하지 마라.
            - 한국어로 쓴다.

            출력은 아래 JSON 하나만. 설명·코드펜스 금지.
            {"memories": ["...", "..."], "bridge": "..."}

            [기록]
            %s
            """.formatted(nickname, characterName, characterName, worldName, unit, worldName, source);
    }

    /** 코드펜스·앞뒤 잡문을 걷어내고 JSON만 읽는다. 실패 시 null. */
    Narration parseNarration(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String body = raw.trim();
        int start = body.indexOf('{');
        int end = body.lastIndexOf('}');
        if (start < 0 || end <= start) return null;
        try {
            JsonNode root = objectMapper.readTree(body.substring(start, end + 1));
            List<String> memories = new ArrayList<>();
            JsonNode arr = root.get("memories");
            if (arr != null && arr.isArray()) {
                for (JsonNode n : arr) {
                    if (memories.size() >= MAX_MEMORY_ROWS) break;
                    if (!n.isTextual()) continue;
                    String t = n.asText().trim();
                    if (t.isEmpty()) continue;
                    memories.add(t.length() > MAX_MEMORY_CHARS ? t.substring(0, MAX_MEMORY_CHARS) : t);
                }
            }
            String bridge = null;
            JsonNode b = root.get("bridge");
            if (b != null && b.isTextual() && !b.asText().isBlank()) {
                String t = b.asText().trim();
                bridge = t.length() > MAX_BRIDGE_CHARS ? t.substring(0, MAX_BRIDGE_CHARS) : t;
            }
            return new Narration(memories, bridge, true);
        } catch (Exception e) {
            return null;
        }
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  브리지 폴백 문구
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    static String defaultBridge(String characterName) {
        String name = characterName == null ? "그녀" : characterName;
        return "자유 모드에서 " + name + josaWaGwa(name) + " 쌓아 온 기억을 안고, 이야기는 이 세계에서 이어진다.";
    }

    /** 받침 유무로 와/과 선택 — 한글이 아니면 '와'. */
    static String josaWaGwa(String word) {
        if (word == null || word.isEmpty()) return "와";
        char c = word.charAt(word.length() - 1);
        if (c < 0xAC00 || c > 0xD7A3) return "와";
        return ((c - 0xAC00) % 28 == 0) ? "와" : "과";
    }
}
