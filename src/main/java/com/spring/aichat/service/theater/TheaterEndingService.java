package com.spring.aichat.service.theater;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.spring.aichat.domain.character.Character;
import com.spring.aichat.domain.chat.ChatRoom;
import com.spring.aichat.domain.chat.ChatRoomRepository;
import com.spring.aichat.domain.enums.AvatarStat;
import com.spring.aichat.domain.enums.TheaterEndingType;
import com.spring.aichat.domain.theater.*;
import com.spring.aichat.dto.theater.TheaterResponses.*;
import com.spring.aichat.dto.chat.SendChatResponse.SceneResponse;
import com.spring.aichat.exception.BadRequestException;
import com.spring.aichat.exception.BusinessException;
import com.spring.aichat.exception.ErrorCode;
import com.spring.aichat.exception.NotFoundException;
import com.spring.aichat.external.OpenRouterClient;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * [Phase 5.5-Theater] Theater 엔딩 서비스
 *
 * Act 4 종료 시 호출되어, [호감도 + 지배 스탯] 조합으로 엔딩 타입을 결정하고
 * LLM으로 엔딩 씬을 생성한다.
 *
 * [엔딩 결정 로직]
 *   호감도 >= 70 + 지배 스탯 존재 → {지배스탯}_ENDING (해피 5종)
 *   20 <= 호감도 < 70             → FADED_ENDING (스쳐간 인연)
 *   -30 <= 호감도 < 20            → BITTER_ENDING (엇갈린 마음)
 *   호감도 < -30                  → ENEMY_ENDING (원수)
 *
 * [지배 스탯 임계치]
 *   80 이상이어야 해피 엔딩 중 "{stat}_ENDING" 확정
 *   그 이하면 CHARM_ENDING으로 폴백 (평균적인 해피 엔딩)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TheaterEndingService {

    private final ChatRoomRepository chatRoomRepository;
    private final TheaterStateRepository theaterStateRepository;
    private final TheaterHeroineAffectionRepository affectionRepository;
    private final TheaterBranchChoiceRepository branchChoiceRepository;
    private final TheaterDirectorNoteRepository directorNoteRepository;
    private final TheaterBatchCacheService batchCache;
    private final OpenRouterClient openRouterClient;
    private final ObjectMapper objectMapper;
    // [Phase III · 작업 3] 엔딩 씬은 항상 proModel
    private final TheaterModelResolver modelResolver;

    private static final int DOMINANT_STAT_THRESHOLD = 80;
    private static final int HAPPY_THRESHOLD = 70;
    private static final int FADED_THRESHOLD = 20;
    private static final int BITTER_THRESHOLD = -30;

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  1. 엔딩 트리거
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /**
     * Act 4의 마지막 Chapter 종료 시 자동 호출되어야 함.
     * 또는 CLIMAX Choice 종료 후 호출.
     */
    @Transactional
    public TheaterEnding triggerEnding(Long roomId, String username) {
        ChatRoom room = getOwnedRoom(roomId, username);
        TheaterState state = getState(roomId);

        if (state.isEndingReached()) {
            throw new BadRequestException("이미 엔딩에 도달했습니다.");
        }

        // ─── 1. 메인 히로인 & 호감도 확정 ───
        List<TheaterHeroineAffection> allAffections =
            affectionRepository.findByRoomOrderByAffectionDesc(roomId);

        if (allAffections.isEmpty()) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "히로인 데이터가 없습니다.");
        }

        TheaterHeroineAffection main = allAffections.get(0);
        int mainAffection = main.getAffection();
        Character mainHeroine = main.getCharacter();

        // ─── 2. 지배 스탯 결정 ───
        AvatarStat dominantStat = state.dominantStat();
        int dominantValue = state.getStat(dominantStat);

        // ─── 3. 엔딩 타입 결정 ───
        TheaterEndingType endingType = determineEndingType(mainAffection, dominantStat, dominantValue);

        // ─── 4. 엔딩 씬 생성 (LLM) ───
        List<SceneResponse> endingScenes;
        String closingQuote;
        try {
            EndingScenePayload payload = generateEndingScenesViaLlm(
                room, state, mainHeroine, endingType, dominantStat, dominantValue, mainAffection
            );
            endingScenes = payload.scenes();
            closingQuote = payload.closingQuote();
        } catch (Exception e) {
            log.error("🎭 [ENDING] LLM generation failed, using fallback: {}", e.getMessage());
            endingScenes = buildFallbackScenes(mainHeroine, endingType);
            closingQuote = endingType.isHappy()
                ? mainHeroine.getEffectiveEndingQuoteHappy()
                : mainHeroine.getEffectiveEndingQuoteBad();
        }

        // ─── 5. 상태 저장 ───
        state.markEndingReached(endingType, endingType.getTitleKo(), mainHeroine.getId());

        // ─── 6. 통계 수집 ───
        EndingStats stats = buildEndingStats(state, allAffections, roomId);

        // ─── 7. 캐시 정리 ───
        batchCache.purgeRoom(roomId);

        log.info("🎭 [ENDING] Reached | roomId={} | type={} | mainHeroine={} | affection={} | dominantStat={}({})",
            roomId, endingType, mainHeroine.getName(), mainAffection, dominantStat, dominantValue);

        return new TheaterEnding(
            endingType.name(),
            endingType.getMoodCategory(),
            endingType.getTitleKo(),
            mainHeroine.getId(),
            mainHeroine.getName(),
            mainAffection,
            dominantStat.name(),
            dominantValue,
            endingScenes,
            closingQuote,
            extractMemoryHighlights(roomId),
            stats
        );
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  2. 엔딩 타입 결정 로직
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    private TheaterEndingType determineEndingType(int affection, AvatarStat dominantStat, int dominantValue) {
        if (affection < BITTER_THRESHOLD) return TheaterEndingType.ENEMY_ENDING;
        if (affection < FADED_THRESHOLD) return TheaterEndingType.BITTER_ENDING;
        if (affection < HAPPY_THRESHOLD) return TheaterEndingType.FADED_ENDING;

        // 해피 엔딩 — 지배 스탯이 임계치 이상이어야 해당 엔딩으로
        if (dominantValue >= DOMINANT_STAT_THRESHOLD) {
            return switch (dominantStat) {
                case CHARM -> TheaterEndingType.CHARM_ENDING;
                case WIT -> TheaterEndingType.WIT_ENDING;
                case BOLDNESS -> TheaterEndingType.BOLDNESS_ENDING;
                case INTELLECT -> TheaterEndingType.INTELLECT_ENDING;
                case EMPATHY -> TheaterEndingType.EMPATHY_ENDING;
            };
        }
        // 지배 스탯 부족 → 기본 해피 (매력 엔딩)
        return TheaterEndingType.CHARM_ENDING;
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  3. LLM 엔딩 씬 생성
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    private record EndingScenePayload(List<SceneResponse> scenes, String closingQuote) {}

    private EndingScenePayload generateEndingScenesViaLlm(
        ChatRoom room, TheaterState state, Character heroine,
        TheaterEndingType type, AvatarStat dominantStat, int dominantValue, int affection
    ) {
        String toneDescription = switch (type.getMoodCategory()) {
            case "HAPPY" -> "희망적이고 따뜻한 결말. 서로의 삶이 하나가 되는 분위기.";
            case "NEUTRAL" -> "씁쓸하지만 아름다운 작별. 서로 다른 길로 가지만 깊은 존중이 남아있는.";
            case "BAD" -> "엇갈림의 결말. 감정의 파편만 남기고 헤어지는 차가운 밤.";
            default -> "결말.";
        };

        String systemPrompt = """
            # Theater Ending Generator
            You are writing the final cinematic scenes of a visual novel.
            Heroine: %s (%s)
            Ending type: %s (%s)
            Dominant avatar stat: %s = %d
            Heroine affection: %d
            Tone: %s

            Generate 3 scenes that form the ending. Each scene must include:
              - narration (3인칭 서술)
              - dialogue (히로인의 마지막 말, 마지막 씬에는 반드시 포함)
              - emotion
              - location

            Also provide a single closing_quote — 가장 기억에 남을 한 문장.

            # Output JSON
            {
              "scenes": [
                { "narration": "...", "dialogue": "...", "emotion": "...", "location": "..." },
                { ... },
                { ... }
              ],
              "closing_quote": "..."
            }
            """.formatted(
            heroine.getName(), heroine.getEffectiveRole(),
            type.name(), type.getTitleKo(),
            dominantStat.name(), dominantValue, affection,
            toneDescription
        );

        // [Phase III · 작업 3] 엔딩 씬은 누적 가치의 클라이맥스 — 항상 proModel
        String model = modelResolver.resolveEndingModel(room.getUser());

        log.info("🎭 [ENDING] generate scenes | roomId={} | type={} | model={}",
            room.getId(), type.name(), model);

        String responseText = openRouterClient.completeJson(
            model, systemPrompt,
            "Generate ending now.", 1800, 0.85
        );

        try {
            var node = objectMapper.readTree(cleanJson(responseText));
            List<SceneResponse> scenes = new ArrayList<>();
            var scenesNode = node.path("scenes");
            if (scenesNode.isArray()) {
                for (var s : scenesNode) {
                    scenes.add(new SceneResponse(
                        s.path("narration").asText(""),
                        s.path("dialogue").asText(""),
                        parseEmotion(s.path("emotion").asText("NEUTRAL")),
                        s.path("location").asText(""),
                        null, null, null
                    ));
                }
            }
            String closingQuote = node.path("closing_quote").asText("");
            return new EndingScenePayload(scenes, closingQuote);
        } catch (JsonProcessingException e) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "엔딩 파싱 실패: " + e.getMessage());
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

    private List<SceneResponse> buildFallbackScenes(Character heroine, TheaterEndingType type) {
        String defaultLoc = heroine.getEffectiveDefaultLocation();
        String narration = type.isHappy()
            ? "시간이 흐르고, 두 사람은 같은 풍경 앞에 서 있다. 말하지 않아도, 서로가 같은 곳을 바라보고 있음을 안다."
            : type.isBad()
            ? "그날의 마지막 말이 허공에 남았다. 둘의 길은 다시 갈라지고, 문은 조용히 닫혔다."
            : "그들은 서로 다른 방향으로 걸어갔다. 잘 지내기를 바라는 마음만은 같았다.";
        String quote = type.isHappy()
            ? heroine.getEffectiveEndingQuoteHappy()
            : heroine.getEffectiveEndingQuoteBad();

        return List.of(new SceneResponse(
            narration, quote, com.spring.aichat.domain.enums.EmotionTag.RELAX, defaultLoc, null, null, null
        ));
    }

    /** LLM의 emotion 문자열을 EmotionTag enum으로 안전 변환 */
    private com.spring.aichat.domain.enums.EmotionTag parseEmotion(String s) {
        if (s == null || s.isBlank()) return com.spring.aichat.domain.enums.EmotionTag.NEUTRAL;
        try {
            return com.spring.aichat.domain.enums.EmotionTag.valueOf(s.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return com.spring.aichat.domain.enums.EmotionTag.NEUTRAL;
        }
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  4. 통계 수집
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    private EndingStats buildEndingStats(TheaterState state, List<TheaterHeroineAffection> affections, Long roomId) {
        Map<String, Integer> finalStats = new LinkedHashMap<>();
        for (AvatarStat s : AvatarStat.values()) {
            finalStats.put(s.name(), state.getStat(s));
        }
        Map<Long, Integer> finalAffections = new LinkedHashMap<>();
        for (TheaterHeroineAffection a : affections) {
            finalAffections.put(a.getCharacter().getId(), a.getAffection());
        }

        int totalBranches = branchChoiceRepository.findByRoom_IdOrderByChosenAtAsc(roomId).size();
        int totalInterventions = (int) directorNoteRepository
            .findByRoom_IdAndNoteTypeOrderByCreatedAtAsc(roomId, "INTERVENTION")
            .stream()
            .filter(n -> n.getContent() != null && n.getContent().contains("난입 시작"))
            .count();

        // 플레이 시간 추정
        long minutes = state.getCreatedAt() != null && state.getUpdatedAt() != null
            ? java.time.Duration.between(state.getCreatedAt(), state.getUpdatedAt()).toMinutes()
            : 0;

        return new EndingStats(
            4, state.getTotalSceneCount(), totalBranches, totalInterventions,
            0, (int) minutes, finalStats, finalAffections
        );
    }

    private List<String> extractMemoryHighlights(Long roomId) {
        // 감독 노트 중 AUTO_MOMENT / CHAPTER_END 타입을 최근 5개 추출
        return directorNoteRepository.findByRoom_IdOrderByCreatedAtAsc(roomId).stream()
            .filter(n -> "AUTO_MOMENT".equals(n.getNoteType()) || "CHAPTER_END".equals(n.getNoteType()))
            .map(TheaterDirectorNote::getContent)
            .filter(Objects::nonNull)
            .limit(5)
            .toList();
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  Helpers
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

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