package com.spring.aichat.service.theater;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.spring.aichat.config.OpenAiProperties;
import com.spring.aichat.domain.character.Character;
import com.spring.aichat.domain.chat.ChatRoom;
import com.spring.aichat.domain.chat.ChatRoomRepository;
import com.spring.aichat.domain.enums.AvatarStat;
import com.spring.aichat.domain.enums.BranchLevel;
import com.spring.aichat.domain.theater.*;
import com.spring.aichat.domain.user.User;
import com.spring.aichat.domain.user.UserRepository;
import com.spring.aichat.dto.theater.TheaterResponses.BranchOption;
import com.spring.aichat.dto.theater.TheaterResponses.BranchOptions;
import com.spring.aichat.dto.theater.TheaterResponses.StatGate;
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
 * [Phase 5.5-Theater] 분기 서비스
 *
 * 4가지 분기 레벨 처리:
 *  - MINOR/MAJOR/CLIMAX: 씬 분기 (LLM 생성)
 *  - LOCATION: 장소 선택 분기 (결정론적 생성)
 *
 * Stat-gated Branch: 선택지에 스탯 최소치 조건. 미충족 시 unlocked=false로 UI 잠금.
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
    private final OpenAiProperties openAiProperties;
    private final ObjectMapper objectMapper;
    private final UserRepository userRepository;

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  1. 장소 선택 분기 (LOCATION)
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    @Transactional
    public BranchOptions generateLocationBranch(Long roomId, String username) {
        getOwnedRoom(roomId, username);
        TheaterState state = getState(roomId);

        List<TheaterHeroineAffection> affections = affectionRepository
            .findByRoomOrderByAffectionDesc(roomId);

        if (affections.size() < 2) {
            throw new BadRequestException("장소 선택은 멀티 히로인 세션에서만 가능합니다.");
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

        String branchToken = generateBranchToken(roomId, "LOCATION");
        batchCache.putBranchContext(roomId, branchToken, "LOCATION_BRANCH_OFFERED");

        return new BranchOptions(
            BranchLevel.LOCATION.name(),
            "새로운 Chapter가 시작된다. 오늘, 어디로 향할까?",
            options,
            state.getCurrentAct().getNumber(),
            state.getCurrentChapter(),
            state.getTotalSceneCount()
        );
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  2. 씬 분기 (MINOR/MAJOR/CLIMAX) — LLM 생성
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    @Transactional
    public BranchOptions generateSceneBranch(Long roomId, String username,
                                             BranchLevel level, String contextSummary) {
        getOwnedRoom(roomId, username);
        TheaterState state = getState(roomId);

        String systemPrompt = buildBranchPrompt(state, level, contextSummary);
        String llmResponse = openRouterClient.completeJson(
            openAiProperties.model(), systemPrompt,
            "Generate branch options now.", 1500, 0.85
        );

        List<BranchOption> options = parseBranchOptions(llmResponse, level, state);

        String branchToken = generateBranchToken(roomId, level.name());
        batchCache.putBranchContext(roomId, branchToken, contextSummary);

        return new BranchOptions(
            level.name(),
            extractContextNarration(llmResponse),
            options,
            state.getCurrentAct().getNumber(),
            state.getCurrentChapter(),
            state.getTotalSceneCount()
        );
    }

    private String buildBranchPrompt(TheaterState state, BranchLevel level, String contextSummary) {
        int optionCount = level.getTypicalOptionCount();
        String levelDesc = switch (level) {
            case MINOR -> "톤 조정 수준의 가벼운 선택 (2지선다)";
            case MAJOR -> "Chapter 방향을 바꿀 중대 선택 (3지선다)";
            case CLIMAX -> "Act의 운명을 가를 결정적 선택 (3지선다)";
            default -> "선택";
        };

        return """
            # Branch Generator — Theater Mode
            You generate %d narrative branch options for the current story moment.

            # Current Context
            Act %d — Chapter %d
            Protagonist stats: CHARM=%d WIT=%d BOLDNESS=%d INTELLECT=%d EMPATHY=%d
            Branch level: %s — %s

            # Context Summary
            %s

            # Output Format
            Return a single JSON object:
            {
              "context_narration": "1~2문장으로 분기 순간의 상황을 묘사",
              "options": [
                {
                  "label": "선택지 제목 (10~20자)",
                  "detail": "선택지의 뉘앙스/결과 암시 (20~40자)",
                  "tone": "normal | affection | bold | witty | introspective",
                  "stat_gate": { "stat": "CHARM | WIT | BOLDNESS | INTELLECT | EMPATHY", "min_value": 30 } | null,
                  "is_secret": false
                }
              ]
            }

            # Rules
            - 최소 %d개 중 1개는 stat_gate 조건이 있어도 좋다
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

    @Transactional
    public void applyBranchChoice(Long roomId, String username, BranchLevel level,
                                  int chosenIndex, String branchToken,
                                  List<BranchOption> optionsSnapshot) {
        ChatRoom room = getOwnedRoom(roomId, username);
        TheaterState state = getState(roomId);

        if (chosenIndex < 0 || chosenIndex >= optionsSnapshot.size()) {
            throw new BadRequestException("잘못된 선택 인덱스입니다.");
        }
        BranchOption chosen = optionsSnapshot.get(chosenIndex);

        if (!chosen.unlocked()) {
            throw new BadRequestException("이 선택지는 아직 해금되지 않았습니다.");
        }

        if (chosen.energyCost() > 0) {
            User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new NotFoundException("유저를 찾을 수 없습니다."));
            user.consumeEnergy(chosen.energyCost());
        }

        String contextSummary = null;
        if (branchToken != null) {
            contextSummary = batchCache.consumeBranchContext(roomId, branchToken).orElse(null);
        }

        String optionsJson;
        try {
            optionsJson = objectMapper.writeValueAsString(optionsSnapshot);
        } catch (JsonProcessingException e) {
            optionsJson = "[]";
        }

        TheaterBranchChoice choice = TheaterBranchChoice.record(
            room, level, state.getCurrentAct(), state.getCurrentChapter(),
            state.getTotalSceneCount(), optionsJson,
            chosenIndex, chosen.label(),
            chosen.heroineId(), chosen.energyCost()
        );
        branchChoiceRepository.save(choice);

        if (level == BranchLevel.LOCATION && chosen.heroineId() != null) {
            state.setCurrentHeroine(chosen.heroineId());
        }

        batchCache.invalidateBatchesFrom(roomId, state.getCurrentBatchId());

        String newBranchContext = String.format(
            "유저가 '%s' 선택함 (%s, %s). %s",
            chosen.label(), level.name(), chosen.tone(),
            contextSummary != null ? contextSummary : ""
        );
        batchCache.putBranchContext(roomId, "active", newBranchContext);

        log.info("🎭 [BRANCH] Applied | roomId={} | level={} | chosen={} | cost={}",
            roomId, level, chosen.label(), chosen.energyCost());
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  Helpers
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    private String generateBranchToken(Long roomId, String level) {
        return level + "-" + roomId + "-" + System.currentTimeMillis();
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