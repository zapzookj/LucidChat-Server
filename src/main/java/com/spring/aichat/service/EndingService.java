package com.spring.aichat.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.spring.aichat.config.OpenAiProperties;
import com.spring.aichat.domain.chat.ChatLog;
import com.spring.aichat.domain.chat.ChatLogRepository;
import com.spring.aichat.domain.chat.ChatRoom;
import com.spring.aichat.domain.chat.ChatRoomRepository;
import com.spring.aichat.domain.enums.EmotionTag;
import com.spring.aichat.domain.enums.EndingType;
import com.spring.aichat.dto.chat.AiJsonOutput;
import com.spring.aichat.dto.chat.EndingResponse;
import com.spring.aichat.dto.chat.EndingResponse.EndingScene;
import com.spring.aichat.dto.chat.EndingResponse.EndingStats;
import com.spring.aichat.dto.openai.OpenAiChatRequest;
import com.spring.aichat.dto.openai.OpenAiMessage;
import com.spring.aichat.exception.NotFoundException;
import com.spring.aichat.external.OpenRouterClient;
import com.spring.aichat.service.prompt.EndingPromptAssembler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 엔딩 이벤트 서비스
 *
 * [Phase 4]   분기별 엔딩 이벤트 시스템
 * [Fix  #9]   RAG 메모리 시적 변환 레이어 추가 (빨간약 제거)
 * [Fix  #12]  buildEndingContext에서 ASSISTANT 로그 reasoning 제거
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class EndingService {

    private final ChatRoomRepository chatRoomRepository;
    private final ChatLogRepository chatLogRepository;
    private final EndingPromptAssembler endingPromptAssembler;
    private final OpenRouterClient openRouterClient;
    private final OpenAiProperties props;
    private final ObjectMapper objectMapper;
    private final MemoryService memoryService;
    private final TransactionTemplate txTemplate;
    private final AchievementService achievementService;

    /**
     * 엔딩 데이터 생성 — 씬 + 타이틀 + 추억 + 통계를 한 번에 반환
     */
    public EndingResponse generateEnding(Long roomId, EndingType endingType) {
        long totalStart = System.currentTimeMillis();
        log.info("🎬 [ENDING] ====== generateEnding START ====== roomId={} type={}", roomId, endingType);

        // ── 1. 데이터 로드 ──
        ChatRoom room = chatRoomRepository.findWithMemberAndCharacterById(roomId)
            .orElseThrow(() -> new NotFoundException("채팅방이 존재하지 않습니다. roomId=" + roomId));

        String characterName = room.getCharacter().getName();
        String userNickname = room.getUser().getNickname();
        int affection = room.getAffectionScore();
        String relationStatus = room.getStatusLevel().name();
        boolean isSecretMode = room.getUser().getIsSecretMode();
        Long userId = room.getUser().getId();

        // ── 2. RAG — 장기 기억 전체 검색 (추억 회고용) ──
        long ragStart = System.currentTimeMillis();
        String longTermMemory = "";
        List<String> rawMemoryList = new ArrayList<>();
        try {
            String[] searchQueries = {
                "가장 기억에 남는 순간",
                "함께 했던 특별한 이벤트",
                "감동적인 대화",
                "처음 만났을 때"
            };
            for (String query : searchQueries) {
                String result = memoryService.retrieveContext(userId, query);
                if (!result.isEmpty()) {
                    for (String line : result.split("\n")) {
                        String cleaned = line.startsWith("- ") ? line.substring(2).trim() : line.trim();
                        if (!cleaned.isEmpty() && !rawMemoryList.contains(cleaned)) {
                            rawMemoryList.add(cleaned);
                        }
                    }
                }
            }
            longTermMemory = rawMemoryList.stream()
                .map(m -> "- " + m)
                .collect(Collectors.joining("\n"));
        } catch (Exception e) {
            log.warn("🎬 [ENDING] RAG retrieval failed (non-blocking): {}", e.getMessage());
        }
        log.info("🎬 [ENDING] RAG: {}ms | memories found: {}", System.currentTimeMillis() - ragStart, rawMemoryList.size());

        // ── 2.5 [Fix #9] RAG 메모리 → 시적 1인칭 변환 (빨간약 제거) ──
        List<String> transformedMemoryList = transformMemoriesToPoetic(rawMemoryList, characterName, userNickname, endingType);

        // ── 3. 최근 대화 요약 (타이틀 생성용) ──
        List<ChatLog> recentLogs = chatLogRepository.findTop20ByRoom_IdOrderByCreatedAtDesc(roomId);
        recentLogs.sort(Comparator.comparing(ChatLog::getCreatedAt));
        String recentSummary = recentLogs.stream()
            .map(l -> l.getRole().name() + ": " + l.getCleanContent())
            .collect(Collectors.joining("\n"));

        // ── 4. 엔딩 씬 생성 (LLM Call 1) ──
        long sceneStart = System.currentTimeMillis();
        String scenePrompt = endingPromptAssembler.assembleEndingScenePrompt(
            endingType, characterName, userNickname,
            affection, relationStatus, longTermMemory, isSecretMode
        );

        List<OpenAiMessage> sceneMessages = buildEndingContext(roomId, scenePrompt);
        String sceneRaw = openRouterClient.chatCompletion(
            new OpenAiChatRequest(props.model(), sceneMessages, 0.85)
        );
        log.info("🎬 [ENDING] Scene LLM: {}ms", System.currentTimeMillis() - sceneStart);

        EndingScenesWrapper scenesWrapper = parseEndingScenes(sceneRaw);

        // ── 5. 엔딩 타이틀 생성 (LLM Call 2 — penalty 미적용, 창의성 극대화) ──
        long titleStart = System.currentTimeMillis();
        String titlePrompt = endingPromptAssembler.assembleEndingTitlePrompt(
            endingType, longTermMemory, recentSummary, userNickname, characterName
        );
        String endingTitle = openRouterClient.chatCompletion(
            OpenAiChatRequest.withoutPenalty(props.sentimentModel(), List.of(OpenAiMessage.system(titlePrompt)), 0.9)
        ).trim().replaceAll("[\"']", "");
        log.info("🎬 [ENDING] Title LLM: {}ms | title={}", System.currentTimeMillis() - titleStart, endingTitle);

        // ── 6. 플레이 통계 집계 ──
        EndingStats stats = collectStats(roomId, room);

        // ── 7. 엔딩 로그 저장 (히스토리용) ──
        txTemplate.execute(status -> {
            ChatRoom freshRoom = chatRoomRepository.findById(roomId)
                .orElseThrow(() -> new NotFoundException("Room not found"));

            String endingNarration = "[ENDING:" + endingType.name() + "] " + endingTitle;
            chatLogRepository.save(ChatLog.system(freshRoom, endingNarration));

            freshRoom.markEndingReached(endingType);
            return null;
        });

        // ── 8. 응답 조립 ──
        List<EndingScene> endingScenes = scenesWrapper.scenes().stream()
            .map(s -> new EndingScene(
                s.narration(),
                s.dialogue(),
                parseEmotion(s.emotion()),
                safeUpperCase(s.location()),
                safeUpperCase(s.time()),
                safeUpperCase(s.outfit()),
                safeUpperCase(s.bgmMode())
            ))
            .collect(Collectors.toList());

        String characterQuote = scenesWrapper.characterQuote() != null
            ? scenesWrapper.characterQuote()
            : (endingType == EndingType.HAPPY
            ? "주인님과의 모든 순간이, 아이리에겐 기적이었어요."
            : "그 분이 처음 문을 열었을 때의 온기가... 아직도 손끝에 남아 있습니다.");

        log.info("🎬 [ENDING] ====== generateEnding DONE: {}ms ======", System.currentTimeMillis() - totalStart);

        try {
            achievementService.unlockEnding(room.getUser().getId(), endingType.name());
        } catch (Exception e) {
            log.warn("🏆 [ACHIEVEMENT] Failed to unlock ending achievement: {}", e.getMessage());
        }

        return new EndingResponse(
            endingType.name(),
            endingTitle,
            endingScenes,
            transformedMemoryList,  // [Fix #9] 변환된 시적 메모리 사용
            characterQuote,
            stats
        );
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  [Fix #9] RAG 메모리 → 시적 1인칭 변환
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /**
     * RAG에서 추출한 메타적 메모리를 캐릭터의 1인칭 시적 회상으로 변환.
     *
     * 입력: "유저가 AI 캐릭터와 정원에서 산책하며 별을 보았다"
     * 출력: "주인님과 정원에서 올려다본 그 밤하늘의 별빛..."
     *
     * sentimentModel(경량 모델) 사용, 비용 미미.
     * 실패 시 원본 메모리를 그대로 반환 (폴백).
     */
    private List<String> transformMemoriesToPoetic(
        List<String> rawMemories, String characterName, String userNickname, EndingType endingType
    ) {
        if (rawMemories.isEmpty()) return rawMemories;

        String moodGuide = endingType == EndingType.HAPPY
            ? "따뜻하고 사랑스러운 톤으로"
            : "아련하고 쓸쓸한 톤으로";

        String memoriesText = rawMemories.stream()
            .map(m -> "- " + m)
            .collect(Collectors.joining("\n"));

        String transformPrompt = """
            당신은 '%s'라는 이름의 메이드 캐릭터입니다.
            아래의 기억들을 당신(%s)이 '%s'(주인님)을 회상하는 1인칭 시점으로 변환하세요.
            
            ## 규칙:
            - 각 기억을 **시적이고 감성적인 한 줄**(15~30자)로 변환
            - %s 회상하세요
            - 'AI', '유저', '캐릭터', '시스템', '호감도' 같은 메타 용어는 **절대 사용 금지**
            - '주인님', '나(아이리)' 같은 인칭을 사용
            - 기억의 개수를 유지하세요 (입력 N개 → 출력 N개)
            - 각 줄을 "- "로 시작하세요
            
            ## 원본 기억:
            %s
            
            ## 출력:
            변환된 기억만 출력하세요. 설명이나 부연은 금지.
            """.formatted(
            characterName, characterName, userNickname,
            moodGuide,
            memoriesText
        );

        try {
            long transformStart = System.currentTimeMillis();
            String transformed = openRouterClient.chatCompletion(
                OpenAiChatRequest.withoutPenalty(props.sentimentModel(), List.of(OpenAiMessage.system(transformPrompt)), 0.7)
            ).trim();
            log.info("🎬 [ENDING] Memory transform: {}ms", System.currentTimeMillis() - transformStart);

            // 변환 결과 파싱
            List<String> result = new ArrayList<>();
            for (String line : transformed.split("\n")) {
                String cleaned = line.startsWith("- ") ? line.substring(2).trim() : line.trim();
                if (!cleaned.isEmpty()) {
                    result.add(cleaned);
                }
            }

            // 변환 결과가 비어있으면 원본 반환
            return result.isEmpty() ? rawMemories : result;

        } catch (Exception e) {
            log.warn("🎬 [ENDING] Memory transformation failed (using raw): {}", e.getMessage());
            return rawMemories; // 폴백: 원본 메모리
        }
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  내부 헬퍼
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /**
     * 엔딩 컨텍스트 구성 — 최근 대화 + 엔딩 시스템 프롬프트
     * [Fix #12] ASSISTANT 로그에서 reasoning 제거
     */
    private List<OpenAiMessage> buildEndingContext(Long roomId, String systemPrompt) {
        List<ChatLog> recent = chatLogRepository.findTop20ByRoom_IdOrderByCreatedAtDesc(roomId);
        recent.sort(Comparator.comparing(ChatLog::getCreatedAt));

        List<OpenAiMessage> messages = new ArrayList<>();
        messages.add(OpenAiMessage.system(systemPrompt));

        for (ChatLog chatLog : recent) {
            switch (chatLog.getRole()) {
                case USER -> messages.add(OpenAiMessage.user(chatLog.getRawContent()));
                case ASSISTANT -> messages.add(
                    OpenAiMessage.assistant(sanitizeAssistantLog(chatLog))
                );
                case SYSTEM -> messages.add(OpenAiMessage.user("[NARRATION]\n" + chatLog.getRawContent()));
            }
        }

        return messages;
    }

    /**
     * [Fix #12] ASSISTANT 로그 정제 — reasoning 제거, scenes만 추출
     * ChatService.sanitizeAssistantLog()와 동일한 로직
     */
    private String sanitizeAssistantLog(ChatLog chatLog) {
        String raw = chatLog.getRawContent();
        if (raw == null || raw.isBlank()) {
            return chatLog.getCleanContent() != null ? chatLog.getCleanContent() : "";
        }

        try {
            String cleanJson = stripMarkdown(raw);
            AiJsonOutput parsed = objectMapper.readValue(cleanJson, AiJsonOutput.class);

            if (parsed.scenes() == null || parsed.scenes().isEmpty()) {
                return chatLog.getCleanContent() != null ? chatLog.getCleanContent() : "";
            }

            StringBuilder sb = new StringBuilder();
            for (AiJsonOutput.Scene scene : parsed.scenes()) {
                if (scene.narration() != null && !scene.narration().isBlank()) {
                    sb.append("(").append(scene.narration()).append(") ");
                }
                if (scene.dialogue() != null && !scene.dialogue().isBlank()) {
                    sb.append("\"").append(scene.dialogue()).append("\"");
                }
                if (scene.emotion() != null) {
                    sb.append(" [").append(scene.emotion()).append("]");
                }
                sb.append("\n");
            }

            return sb.toString().trim();

        } catch (Exception e) {
            return chatLog.getCleanContent() != null ? chatLog.getCleanContent() : raw;
        }
    }

    /**
     * 플레이 통계 집계
     */
    private EndingStats collectStats(Long roomId, ChatRoom room) {
        long totalMessages = chatLogRepository.countByRoomId(roomId);

        ChatLog firstLog = chatLogRepository.findTop1ByRoom_IdOrderByCreatedAtAsc(roomId).orElse(null);
        String firstDate = "알 수 없음";
        long totalDays = 0;

        if (firstLog != null) {
            LocalDateTime firstAt = firstLog.getCreatedAt();
            firstDate = firstAt.toLocalDate().toString();
            totalDays = ChronoUnit.DAYS.between(firstAt.toLocalDate(), LocalDateTime.now().toLocalDate()) + 1;
        }

        return new EndingStats(
            totalMessages,
            totalDays,
            room.getAffectionScore(),
            room.getStatusLevel().name(),
            firstDate
        );
    }

    private EndingScenesWrapper parseEndingScenes(String raw) {
        try {
            String clean = stripMarkdown(raw);
            return objectMapper.readValue(clean, EndingScenesWrapper.class);
        } catch (JsonProcessingException e) {
            log.error("🎬 [ENDING] Scene JSON parsing failed: {}", raw, e);
            return new EndingScenesWrapper(
                List.of(new RawEndingScene(
                    "아이리가 조용히 당신을 바라본다.",
                    "...감사했습니다, 주인님.",
                    "SAD", null, null, null, null
                )),
                "당신과의 모든 날들이, 저에겐 전부였습니다."
            );
        }
    }

    private record EndingScenesWrapper(
        List<RawEndingScene> scenes,
        String characterQuote
    ) {}

    private record RawEndingScene(
        String narration,
        String dialogue,
        String emotion,
        String location,
        String time,
        String outfit,
        String bgmMode
    ) {}

    private EmotionTag parseEmotion(String emotionStr) {
        try {
            return EmotionTag.valueOf(emotionStr.toUpperCase());
        } catch (Exception e) {
            return EmotionTag.NEUTRAL;
        }
    }

    private String safeUpperCase(String value) {
        if (value == null || value.isBlank() || "null".equalsIgnoreCase(value)) return null;
        return value.toUpperCase().trim();
    }

    private String stripMarkdown(String text) {
        if (text.startsWith("```json")) text = text.substring(7);
        else if (text.startsWith("```")) text = text.substring(3);
        if (text.endsWith("```")) text = text.substring(0, text.length() - 3);
        return text.trim();
    }
}