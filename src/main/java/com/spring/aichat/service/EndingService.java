package com.spring.aichat.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.spring.aichat.config.OpenAiProperties;
import com.spring.aichat.domain.chat.*;
import com.spring.aichat.domain.enums.ChatRole;
import com.spring.aichat.domain.enums.EmotionTag;
import com.spring.aichat.domain.enums.EndingType;
import com.spring.aichat.dto.chat.EndingResponse;
import com.spring.aichat.dto.chat.EndingResponse.EndingScene;
import com.spring.aichat.dto.chat.EndingResponse.EndingStats;
import com.spring.aichat.dto.openai.OpenAiChatRequest;
import com.spring.aichat.dto.openai.OpenAiMessage;
import com.spring.aichat.exception.BusinessException;
import com.spring.aichat.exception.ErrorCode;
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
 * [Phase 4] 분기별 엔딩 이벤트 시스템
 *
 * 역할:
 *   1. 엔딩 씬 생성 (LLM) — 캐릭터의 마지막 감정 폭발 연출
 *   2. 엔딩 타이틀 생성 (LLM) — 유저만의 고유 엔딩 제목
 *   3. 추억 검색 (RAG) — "우리가 함께한 시간" 회고
 *   4. 플레이 통계 집계 — 총 메시지, 함께한 일수 등
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

    /**
     * 엔딩 데이터 생성 — 씬 + 타이틀 + 추억 + 통계를 한 번에 반환
     *
     * @param roomId     채팅방 ID
     * @param endingType HAPPY / BAD
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
        List<String> memoryList = new ArrayList<>();
        try {
            // 엔딩에서는 여러 쿼리로 폭넓게 검색
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
                        if (!cleaned.isEmpty() && !memoryList.contains(cleaned)) {
                            memoryList.add(cleaned);
                        }
                    }
                }
            }
            longTermMemory = memoryList.stream()
                .map(m -> "- " + m)
                .collect(Collectors.joining("\n"));
        } catch (Exception e) {
            log.warn("🎬 [ENDING] RAG retrieval failed (non-blocking): {}", e.getMessage());
        }
        log.info("🎬 [ENDING] RAG: {}ms | memories found: {}", System.currentTimeMillis() - ragStart, memoryList.size());

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

        // ── 5. 엔딩 타이틀 생성 (LLM Call 2) ──
        long titleStart = System.currentTimeMillis();
        String titlePrompt = endingPromptAssembler.assembleEndingTitlePrompt(
            endingType, longTermMemory, recentSummary, userNickname, characterName
        );
        String endingTitle = openRouterClient.chatCompletion(
            new OpenAiChatRequest(props.sentimentModel(), List.of(OpenAiMessage.system(titlePrompt)), 0.9)
        ).trim().replaceAll("[\"']", ""); // 따옴표 제거
        log.info("🎬 [ENDING] Title LLM: {}ms | title={}", System.currentTimeMillis() - titleStart, endingTitle);

        // ── 6. 플레이 통계 집계 ──
        EndingStats stats = collectStats(roomId, room);

        // ── 7. 엔딩 로그 저장 (히스토리용) ──
        txTemplate.execute(status -> {
            ChatRoom freshRoom = chatRoomRepository.findById(roomId)
                .orElseThrow(() -> new NotFoundException("Room not found"));

            String endingNarration = "[ENDING:" + endingType.name() + "] " + endingTitle;
            chatLogRepository.save(ChatLog.system(freshRoom, endingNarration));

            // 엔딩 상태 마킹
            freshRoom.markEndingReached(endingType);
            freshRoom.saveEndingTitle(endingTitle);
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

        return new EndingResponse(
            endingType.name(),
            endingTitle,
            endingScenes,
            memoryList,
            characterQuote,
            stats
        );
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  내부 헬퍼
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /**
     * 엔딩 컨텍스트 구성 — 최근 대화 + 엔딩 시스템 프롬프트
     */
    private List<OpenAiMessage> buildEndingContext(Long roomId, String systemPrompt) {
        List<ChatLog> recent = chatLogRepository.findTop20ByRoom_IdOrderByCreatedAtDesc(roomId);
        recent.sort(Comparator.comparing(ChatLog::getCreatedAt));

        List<OpenAiMessage> messages = new ArrayList<>();
        messages.add(OpenAiMessage.system(systemPrompt));

        for (ChatLog chatLog : recent) {
            switch (chatLog.getRole()) {
                case USER -> messages.add(OpenAiMessage.user(chatLog.getRawContent()));
                case ASSISTANT -> messages.add(OpenAiMessage.assistant(chatLog.getRawContent()));
                case SYSTEM -> messages.add(OpenAiMessage.user("[NARRATION]\n" + chatLog.getRawContent()));
            }
        }

        return messages;
    }

    /**
     * 플레이 통계 집계
     */
    private EndingStats collectStats(Long roomId, ChatRoom room) {
        long totalMessages = chatLogRepository.countByRoomId(roomId);

        // 첫 대화 날짜
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

    /**
     * 엔딩 씬 JSON 파싱
     */
    private EndingScenesWrapper parseEndingScenes(String raw) {
        try {
            String clean = stripMarkdown(raw);
            return objectMapper.readValue(clean, EndingScenesWrapper.class);
        } catch (JsonProcessingException e) {
            log.error("🎬 [ENDING] Scene JSON parsing failed: {}", raw, e);
            // 폴백 — 기본 씬 반환
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

    // 내부 파싱 DTO
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