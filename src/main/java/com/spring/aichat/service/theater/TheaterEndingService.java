package com.spring.aichat.service.theater;

import com.spring.aichat.domain.ending.EndingResultDocument;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.spring.aichat.config.OpenAiProperties;
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
    private final OpenAiProperties openAiProperties;
    private final ObjectMapper objectMapper;
    private final com.spring.aichat.domain.ending.EndingResultRepository endingResultRepository;
    private final TheaterDirectorEngine directorEngine;

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

        // [D-10 · docs/19_assets/blockd_regressions.md] 엔딩 발동은 **비관적 쓰기 잠금**으로 읽는다.
        //   락 없는 findByRoom_Id로 읽으면 isEndingReached() 검사 → LLM 20~60초 → markEnded() 사이가
        //   통째로 TOCTOU 창이다. FE가 'GET 우선 → 404면 POST'로 바뀐 뒤로는 새로고침 1회면 재현되며
        //   LLM 과금 2회 + ending_results 중복 문서(prod에 유니크 인덱스 없음 → 이후 조회 영구 500)로 이어졌다.
        //   두 번째 요청은 여기서 대기했다가 endingReached=true를 읽고 아래 저장분 반환 분기로 빠진다.
        TheaterState state = theaterStateRepository.findByRoomIdForUpdate(roomId)
            .orElseThrow(() -> new NotFoundException("Theater 세션이 없습니다."));

        // [블록 D · 극장 엔딩 부활] 이미 엔딩이면 **저장된 결과를 돌려준다.**
        //   기존에는 400을 던져서 아카이브의 "엔딩 다시 보기"가 100% 실패했다(docs/13 B-9.9).
        if (state.isEndingReached()) {
            return loadPersistedEnding(roomId)
                .orElseThrow(() -> new BadRequestException(
                    "이미 엔딩에 도달했지만 저장된 결과가 없습니다. (이 개편 이전에 종료된 세션)"));
        }

        // [블록 D · 극장 엔딩 부활] 진행도 가드.
        //   기존 가드는 위 중복 검사 하나뿐이라 URL 직타(/theater/{id}/ending)로 Act 1에서도
        //   엔딩이 확정되고 markEnded()로 **resume 불가 영구 전환**됐다. 되돌릴 API도 없다.
        if (!directorEngine.isEndingPoint(state)) {
            throw new BadRequestException("아직 이야기가 끝나지 않았습니다.");
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
        // [Phase 5.5 UX Polish · R4] 엔딩 도달 시 세션을 ENDED로 영구 전환.
        //   - 활성 슬롯에서 빠짐 → 다음 새 극 시작 시 confirm 불필요
        //   - 아카이브에서 영구 감상 가능 (resume은 불가)
        state.markEnded();

        // ─── 6. 통계 수집 ───
        EndingStats stats = buildEndingStats(state, allAffections, roomId);

        // ─── 7. 캐시 정리 ───
        batchCache.purgeRoom(roomId);

        log.info("🎭 [ENDING] Reached | roomId={} | type={} | mainHeroine={} | affection={} | dominantStat={}({})",
            roomId, endingType, mainHeroine.getName(), mainAffection, dominantStat, dominantValue);

        TheaterEnding result = new TheaterEnding(
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

        // ─── 8. 결과 영속 (블록 D · docs/13 B-9.9) ───
        //   저장 실패가 엔딩 자체를 무르게 하면 안 된다 — 상태는 이미 ENDED로 넘어갔고
        //   유저는 지금 엔딩을 보는 중이다. 실패는 '다시 보기 불가'로만 degrade시킨다.
        persistEnding(roomId, endingType.name(), result);

        return result;
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  [블록 D] 엔딩 결과 영속 / 재조회
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /**
     * [D-10 · docs/19_assets/blockd_regressions.md — "ending_results 유니크 인덱스가 prod에서
     * 생성되지 않음"] 유니크 인덱스에 의존하지 않는 <b>upsert</b>.
     *
     * <p>prod는 {@code application-prod.yml:20 auto-index-creation: false}라
     * {@code @Indexed(unique = true)}가 실제로 만들어지지 않는다(로컬만 true — 전형적
     * 'works on my machine'). 인덱스가 없는 상태에서 중복 문서가 하나라도 생기면
     * {@code Optional} 반환 파생 질의가 {@code IncorrectResultSizeDataAccessException}을 던져
     * 엔딩 조회가 <b>영구 500</b>이 됐다. 그래서
     * ① List로 받아 최신 1건에 덮어쓰고, ② 잉여 문서를 그 자리에서 정리한다.
     *
     * <p><b>운영 런북 — prod Mongo 수동 인덱스 생성</b>
     * (필드명이 {@code @Field("room_id")}이므로 키는 {@code room_id}다):
     * <pre>
     * // 1) 중복 확인 (있으면 인덱스 생성이 실패한다)
     * db.ending_results.aggregate([{$group:{_id:"$room_id",n:{$sum:1}}},{$match:{n:{$gt:1}}}])
     * // 2) 인덱스 생성
     * db.ending_results.createIndex({room_id:1},{unique:true})
     * </pre>
     * 인덱스가 생겨도 이 메서드는 그대로 안전하다 — 인덱스는 방어의 2선일 뿐이다.
     */
    private void persistEnding(Long roomId, String endingType, TheaterEnding payload) {
        try {
            String json = objectMapper.writeValueAsString(payload);
            List<EndingResultDocument> existing =
                endingResultRepository.findAllByRoomIdOrderByGeneratedAtDesc(roomId);

            if (existing.isEmpty()) {
                endingResultRepository.save(new EndingResultDocument(roomId, "THEATER", endingType, json));
            } else {
                EndingResultDocument doc = existing.get(0);
                doc.overwrite(endingType, json);
                endingResultRepository.save(doc);

                // 과거 경합으로 생긴 중복 문서 정리 — 남겨두면 인덱스 수동 생성도 실패한다.
                if (existing.size() > 1) {
                    log.warn("🎭 [ENDING] Duplicate ending_results detected — purging {} stale docs | roomId={}",
                        existing.size() - 1, roomId);
                    endingResultRepository.deleteAll(existing.subList(1, existing.size()));
                }
            }
        } catch (Exception e) {
            log.error("🎭 [ENDING] Persist failed — 다시 보기 불가 상태로 degrade | roomId={}", roomId, e);
        }
    }

    /**
     * 저장된 엔딩 재조회 — 아카이브 "엔딩 다시 보기"가 이 경로를 탄다.
     *
     * <p>[D-10] 여기도 {@code Optional} 파생 질의를 쓰면 중복 문서 1건만으로 영구 500이 된다.
     * List로 받아 최신 1건만 읽는다(정리는 재발동 시 {@code persistEnding}이 한다).
     */
    public java.util.Optional<TheaterEnding> loadPersistedEnding(Long roomId) {
        return endingResultRepository.findAllByRoomIdOrderByGeneratedAtDesc(roomId).stream()
            .findFirst()
            .map(doc -> {
                try {
                    return objectMapper.readValue(doc.getPayloadJson(), TheaterEnding.class);
                } catch (Exception e) {
                    log.error("🎭 [ENDING] Stored payload unreadable | roomId={}", roomId, e);
                    return (TheaterEnding) null;
                }
            })
            .filter(java.util.Objects::nonNull);
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

            # 이 극의 실제 내용 (반드시 반영하라)
            %s

            ⚠️ 위 내용은 유저가 500~700씬에 걸쳐 실제로 겪은 이야기다.
               일반적인 결말이 아니라 **이 극의 결말**을 써라 — 유저가 내린 선택과 남은 순간들이
               마지막 씬에서 회수되어야 한다.

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
            toneDescription,
            buildDramaContext(room, state)
        );

        String responseText = openRouterClient.completeJson(
            openAiProperties.model(), systemPrompt,
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
        // 감독 노트 중 AUTO_MOMENT / CHAPTER_END 타입을 **최근** 5개 추출.
        // [docs/13 E-4.12] ASC 정렬에 limit(5)를 걸어 '최초 5개'를 뽑고 있었다 —
        //   Act 1 초반 순간만 회상되고 정작 클라이맥스가 빠졌다. 뒤에서 5개를 잘라 시간순으로 되돌린다.
        List<String> all = directorNoteRepository.findByRoom_IdOrderByCreatedAtAsc(roomId).stream()
            .filter(n -> "AUTO_MOMENT".equals(n.getNoteType()) || "CHAPTER_END".equals(n.getNoteType()))
            .map(TheaterDirectorNote::getContent)
            .filter(Objects::nonNull)
            .toList();
        return all.size() <= 5 ? all : all.subList(all.size() - 5, all.size());
    }

    /**
     * [블록 D · 극장 엔딩 부활] 엔딩 프롬프트에 주입할 '이 극의 실제 내용'.
     *
     * <p>기존 프롬프트는 히로인 이름·엔딩 타입·스탯 숫자만 받았다 — 극의 맥락이 <b>0바이트</b>였다.
     * {@code room}/{@code state} 인자가 이미 넘어와 있는데 쓰이지 않았다.
     * 90~100 에너지를 쓴 유저에게 자기 극과 무관한 3씬을 내보내면 엔딩이 없느니만 못하다.
     */
    private String buildDramaContext(ChatRoom room, TheaterState state) {
        StringBuilder sb = new StringBuilder();

        sb.append("- 진행: Act ").append(state.getCurrentAct().getNumber())
          .append(" (").append(state.getCurrentAct().getTitle()).append("), Chapter ")
          .append(state.getCurrentChapter()).append("까지 완주\n");

        // 유저가 실제로 내린 선택 — 이 극을 이 극으로 만든 것
        List<TheaterBranchChoice> choices =
            branchChoiceRepository.findByRoom_IdOrderByChosenAtAsc(room.getId());
        if (!choices.isEmpty()) {
            sb.append("- 유저가 내린 주요 선택(시간순):\n");
            List<TheaterBranchChoice> tail = choices.size() <= 8
                ? choices : choices.subList(choices.size() - 8, choices.size());
            for (TheaterBranchChoice c : tail) {
                String label = sanitizeForPrompt(c.getChosenLabel(), BRANCH_LABEL_MAX_LEN);
                if (label.isBlank()) continue;
                sb.append("    · Act ").append(c.getActNumber())
                  .append(" — ").append(label).append('\n');
            }
        }

        // 기억에 남은 순간들
        List<String> moments = extractMemoryHighlights(room.getId());
        if (!moments.isEmpty()) {
            sb.append("- 기억에 남은 순간들:\n");
            for (String m : moments) {
                String line = sanitizeForPrompt(m, MOMENT_MAX_LEN);
                if (line.isBlank()) continue;
                sb.append("    · ").append(line).append('\n');
            }
        }

        return sb.length() == 0 ? "(기록된 맥락 없음 — 일반적인 결말로 작성하라.)" : sb.toString();
    }

    /** [D-12] 분기 label은 유저 자유 입력이다 — 80자로 자른다. 선례: TheaterAutoNoteService.java:115. */
    private static final int BRANCH_LABEL_MAX_LEN = 80;

    /**
     * [D-12] 감독 노트(AUTO_MOMENT/CHAPTER_END)는 서버·LLM 생성물이라 유저 자유 입력은 아니지만,
     * BRANCH_TAKEN 유래 문구가 섞일 수 있어 같은 정화를 거친다. 서술 품질을 위해 길이만 넉넉히 둔다.
     */
    private static final int MOMENT_MAX_LEN = 200;

    /**
     * [D-12 · docs/19_assets/blockd_regressions.md — "buildDramaContext가 클라이언트가 임의로 보낸
     * 분기 label을 엔딩 프롬프트에 무검증 주입"] <b>잔존 방어</b>.
     *
     * <p>✅ <b>근본해는 적용됐다</b>(버그픽스 B-4.a~f). {@code /branches/choose}는 더 이상 요청 본문의
     * {@code optionsSnapshot}·{@code level}을 읽지 않고, 서버가 발급한 오퍼 원본
     * ({@code BranchOffer})에서 label·비용·히로인을 재판정한다. 즉 <b>신규로 적재되는</b>
     * {@code TheaterBranchChoice.chosenLabel}은 서버 생성물(LLM 옵션 라벨 또는 결정론적 장소명)이며
     * 유저 자유 입력이 아니다.
     *
     * <p>그럼에도 이 정화를 남기는 이유는 두 가지다:
     * <ol>
     *   <li><b>배포 이전 적재분</b> — 이미 DB에 들어간 행에는 조작된 label이 섞여 있을 수 있고,
     *       엔딩은 과거 Act의 선택을 통째로 읽는다.</li>
     *   <li><b>LLM 생성물도 신뢰 입력이 아니다</b> — 바로 아래 프롬프트가
     *       "⚠️ 위 내용은 유저가 실제로 겪은 이야기다 / 반드시 반영하라"로 신뢰도를 <b>올려주므로</b>,
     *       출처가 서버여도 구분자·장문은 끊어 두는 편이 안전하다.</li>
     * </ol>
     *
     * <p>방어 3종:
     * <ol>
     *   <li>개행·제어문자 제거 — 리스트 항목을 벗어나 새 지시 블록을 여는 것을 막는다.</li>
     *   <li>구분자 이스케이프(백틱·{@code #}·{@code ⚠}) — 마크다운 헤딩/코드펜스로 섹션을 위조하는 것을 막는다.</li>
     *   <li>80자 truncate — 장문 지시문을 실을 여지를 없앤다(선례: TheaterAutoNoteService:115).</li>
     * </ol>
     *
     * <p>(구 주석은 "임시 방어 — 근본해는 배치 4 과제"라고 적혀 있었다. 근본해가 들어간 지금
     * 그 서술은 사실이 아니라서 정정한다.)
     */
    private String sanitizeForPrompt(String raw, int maxLen) {
        if (raw == null) return "";
        StringBuilder out = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char ch = raw.charAt(i);
            if (ch == '\n' || ch == '\r' || ch == '\t') {           // ① 개행/탭 → 공백
                out.append(' ');
            // ⚠ 이 파일은 도메인 Character를 import하고 있어 단순 `Character.`는 그쪽을 가리킨다 — FQN 필수.
            } else if (java.lang.Character.isISOControl(ch)) {       // ① 나머지 제어문자 제거
                continue;
            } else if (ch == '`' || ch == '#' || ch == 0x26A0) { // ② 구분자(코드펜스·헤딩·경고표식 U+26A0) 무력화
                out.append('\'');
            } else {
                out.append(ch);
            }
        }
        String s = out.toString().replaceAll(" {2,}", " ").trim();
        return s.length() > maxLen ? s.substring(0, maxLen) + "..." : s;   // ③ truncate
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

    // [D-10] 락 없는 getState(findByRoom_Id) 헬퍼는 제거했다. 이 서비스에서 state를 읽는 유일한
    //   경로가 triggerEnding이고, 그 경로는 반드시 findByRoomIdForUpdate(비관적 쓰기 잠금)를 타야 한다.
    //   헬퍼를 남겨두면 다음 수정에서 조용히 락 없는 경로로 되돌아간다(§2-6과 같은 이유).
}