package com.spring.aichat.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.spring.aichat.config.OpenAiProperties;
import com.spring.aichat.domain.character.Character;
import com.spring.aichat.domain.chat.*;
import com.spring.aichat.domain.enums.*;
import com.spring.aichat.domain.user.User;
import com.spring.aichat.domain.user.UserRepository;
import com.spring.aichat.dto.chat.ChatRoomInfoResponse;
import com.spring.aichat.dto.chat.SendChatResponse.StatsSnapshot;
import com.spring.aichat.dto.openai.OpenAiChatRequest;
import com.spring.aichat.dto.openai.OpenAiMessage;
import com.spring.aichat.exception.BusinessException;
import com.spring.aichat.exception.ErrorCode;
import com.spring.aichat.exception.NotFoundException;
import com.spring.aichat.external.OpenRouterClient;
import com.spring.aichat.security.PromptInjectionGuard;
import com.spring.aichat.service.cache.RedisCacheService;
import com.spring.aichat.service.payment.SecretModeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 채팅 관리 서비스
 * <p>
 * [Bug #4 Fix] 레거시 REST 코드 경로 제거
 *   - sendMessage(), generateResponseForSystemEvent() 및 관련 헬퍼 제거
 *   - 모든 채팅 처리는 ChatStreamService(SSE)로 통합
 *
 * 현재 담당:
 *   - 채팅방 정보 조회/삭제/초기화
 *   - RLHF 평가, 단건 삭제, 속마음 해금
 *   - 캐릭터 생각 비동기 생성 (ChatStreamService에서 호출)
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class ChatService {

    private final ChatRoomRepository chatRoomRepository;
    private final ChatLogMongoRepository chatLogRepository;
    private final OpenRouterClient openRouterClient;
    private final OpenAiProperties props;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate txTemplate;
    private final RedisCacheService cacheService;
    private final UserRepository userRepository;
    private final SecretModeService secretModeService;
    private final PromptInjectionGuard injectionGuard;
    private final com.spring.aichat.domain.illustration.BackgroundCacheRepository backgroundCacheRepository;
    private final MemoryService memoryService;


    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  [Bug #4 Fix] Dead Code 제거 완료
    //  sendMessage(), generateResponseForSystemEvent() 및 관련 레거시 헬퍼 제거.
    //  모든 채팅 처리는 ChatStreamService(SSE)로 통합.
    //  제거된 코드: JpaPreResult, RollbackContext, LlmResult 레코드,
    //  applyStatChanges, triggerCharacterThoughtIfNeeded, compensateEnergy,
    //  compensateFullRollback, resolveAffectionAndPromotion, resolvePromotionResult,
    //  callLlmAndParse, fetchLastUserMessage, triggerMemorySummarizationIfNeeded,
    //  buildMessageHistory, applyAffectionChange, buildSanitizedAssistantContent(duplicate)
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    private StatsSnapshot buildStatsSnapshot(ChatRoom room, boolean isSecretMode) {
        return new StatsSnapshot(
            room.getStatIntimacy(),
            room.getStatAffection(),
            room.getStatDependency(),
            room.getStatPlayfulness(),
            room.getStatTrust(),
            isSecretMode ? room.getStatLust() : null,
            isSecretMode ? room.getStatCorruption() : null,
            isSecretMode ? room.getStatObsession() : null
        );
    }
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  [Phase 5.5] 캐릭터의 생각 생성 (비동기)
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    /**
     * 캐릭터의 생각을 비동기로 생성하여 ChatRoom에 저장.
     * <p>
     * sentiment 모델(경량)을 사용하여 비용과 레이턴시를 최소화.
     * 실패해도 유저 경험에 영향 없음 (다음 주기에 재시도).
     */
    @Async
    public void generateCharacterThoughtAsync(Long roomId, Long userId, int currentTurnCount, boolean isSecretMode) {
        long start = System.currentTimeMillis();
        try {
            ChatRoom room = chatRoomRepository.findWithMemberAndCharacterById(roomId)
                .orElseThrow(() -> new NotFoundException("Room not found for thought generation"));

            Character character = room.getCharacter();
            String nickname = room.getUser().getNickname();

            // 최근 대화 5턴 로드 (생각의 맥락)
            List<ChatLogDocument> recentLogs = chatLogRepository.findTop20ByRoomIdOrderByCreatedAtDesc(roomId);
            recentLogs.sort(Comparator.comparing(ChatLogDocument::getCreatedAt));

            // 최근 10개만 사용
            List<ChatLogDocument> context = recentLogs.size() > 10
                ? recentLogs.subList(recentLogs.size() - 10, recentLogs.size())
                : recentLogs;

            String conversationContext = context.stream()
                .map(log -> log.getRole().name() + ": " + log.getCleanContent())
                .collect(Collectors.joining("\n"));

            String modeContext = isSecretMode ? "시크릿 모드 (친밀한 관계)" : "노말 모드";

            String thoughtPrompt = """
                당신은 '%s'이라는 이름의 캐릭터입니다.
                                
                ## 캐릭터 정보
                - 이름: %s
                - 성격: %s
                - 현재 관계: %s
                - 모드: %s
                                
                ## 현재 스탯 (0~100)
                친밀도: %d | 호감도: %d | 의존도: %d | 장난기: %d | 신뢰도: %d
                                
                ## 최근 대화
                %s
                                
                ## 지시사항
                위 대화를 바탕으로, '%s'가 '%s'에 대해 지금 마음속으로 생각하고 있을 법한 **내면의 독백**을 한 문장으로 작성하세요.
                                
                규칙:
                - 캐릭터의 말투와 성격을 반영하세요
                - 15~40자 이내의 짧은 독백
                - 스탯 수치가 높은 감정을 반영하세요 (예: 호감도가 높으면 설렘, 의존도가 높으면 의지)
                - 메타적 표현(AI, 시스템, 스탯 등) 절대 금지
                - 독백만 출력하세요. 따옴표나 부연설명 없이.
                """.formatted(
                character.getName(),
                character.getName(),
                character.getEffectivePersonality(isSecretMode),
                room.getDynamicRelationTag() != null ? room.getDynamicRelationTag() : room.getStatusLevel().name(),
                modeContext,
                room.getStatIntimacy(), room.getStatAffection(),
                room.getStatDependency(), room.getStatPlayfulness(), room.getStatTrust(),
                conversationContext,
                character.getName(),
                nickname
            );

            String thought = openRouterClient.chatCompletion(
                OpenAiChatRequest.withoutPenalty(
                    props.sentimentModel(),
                    List.of(OpenAiMessage.system(thoughtPrompt)),
                    0.8
                )
            ).trim().replaceAll("[\"']", "");

            // DB 저장
            txTemplate.execute(status -> {
                ChatRoom freshRoom = chatRoomRepository.findById(roomId)
                    .orElseThrow(() -> new NotFoundException("Room not found"));
                freshRoom.updateCharacterThought(thought, currentTurnCount);
                return null;
            });

            cacheService.evictRoomInfo(roomId);

            log.info("💭 [THOUGHT] Generated: '{}' | roomId={} | {}ms",
                thought, roomId, System.currentTimeMillis() - start);

        } catch (Exception e) {
            log.warn("💭 [THOUGHT] Generation failed (non-blocking): roomId={} | {}",
                roomId, e.getMessage());
        }
    }
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  [Bug #3 Fix] 채팅방 단위 설정 (시크릿 모드 / 페르소나)
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /**
     * 채팅방 시크릿 모드 토글
     *
     * enabled=true: SecretModeService로 패스/해금/구독 검증 후 활성화
     * enabled=false: 즉시 비활성화
     */
    @Transactional
    public void toggleRoomSecretMode(Long roomId, boolean enabled, String username) {
        ChatRoom room = chatRoomRepository.findWithMemberAndCharacterById(roomId)
            .orElseThrow(() -> new NotFoundException("채팅방이 존재하지 않습니다."));

        if (enabled) {
            User user = room.getUser();
            Long characterId = room.getCharacter().getId();

            if (!secretModeService.canAccessSecretMode(user, characterId)) {
                if (!Boolean.TRUE.equals(user.getIsAdult())) {
                    throw new BusinessException(ErrorCode.VERIFICATION_UNDERAGE,
                        "성인 인증이 필요합니다.");
                }
                throw new BusinessException(ErrorCode.BAD_REQUEST,
                    "이 캐릭터의 시크릿 모드 접근 권한이 없습니다.");
            }

            room.activateSecretMode();
            log.info("[SECRET_TOGGLE] Room-level enabled: roomId={}, user={}", roomId, username);
        } else {
            room.deactivateSecretMode();
            log.info("[SECRET_TOGGLE] Room-level disabled: roomId={}, user={}", roomId, username);
        }

        chatRoomRepository.save(room);
        cacheService.evictRoomInfo(roomId);
    }

    /**
     * 채팅방 전용 유저 페르소나 설정
     *
     * persona가 null/blank이면 방 전용 페르소나 해제 (User.profileDescription 폴백)
     */
    @Transactional
    public void updateRoomPersona(Long roomId, String persona) {
        ChatRoom room = chatRoomRepository.findById(roomId)
            .orElseThrow(() -> new NotFoundException("채팅방이 존재하지 않습니다."));

        String sanitized = (persona != null && !persona.isBlank())
            ? injectionGuard.sanitizePersona(persona)
            : null;

        room.updateUserPersona(sanitized);
        chatRoomRepository.save(room);
        cacheService.evictRoomInfo(roomId);

        log.info("[ROOM_PERSONA] Updated: roomId={}, hasPersona={}", roomId, sanitized != null);
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  채팅방 관리 영역
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    public ChatRoomInfoResponse getChatRoomInfo(Long roomId) {
        return cacheService.getRoomInfo(roomId, ChatRoomInfoResponse.class)
            .orElseGet(() -> {
                ChatRoom room = chatRoomRepository.findWithMemberAndCharacterById(roomId)
                    .orElseThrow(() -> new NotFoundException("채팅방이 존재하지 않습니다. roomId=" + roomId));

                var character = room.getCharacter();
                // [Bug #3 Fix] Room-level 시크릿 모드
                boolean isSecret = room.isSecretModeActive()
                    && secretModeService.canAccessSecretMode(
                    room.getUser(), room.getCharacter().getId());

                StatsSnapshot statsSnapshot = buildStatsSnapshot(room, isSecret);

                // [Phase 5.5-Fix] 동적 배경 URL 해상도:
                // 1) ChatRoom에 URL이 이미 저장되어 있으면 그대로 사용
                // 2) locationName만 있고 URL이 null이면 BackgroundCache에서 조회 (비동기 생성 완료 후)
                String dynamicBgUrl = room.getCurrentDynamicBgUrl();
                String dynamicLocationName = room.getCurrentDynamicLocationName();
                if (dynamicLocationName != null && !dynamicLocationName.isBlank() && dynamicBgUrl == null) {
                    String timeOfDay = room.getCurrentTimeOfDay() != null ? room.getCurrentTimeOfDay().name() : "DAY";
                    String cacheHash = com.spring.aichat.domain.illustration.BackgroundCache.computeHash(dynamicLocationName, timeOfDay);
                    dynamicBgUrl = backgroundCacheRepository.findByCacheHash(cacheHash)
                        .map(cache -> {
                            // ChatRoom에도 캐싱하여 다음 조회 시 DB 히트 방지
                            room.updateDynamicBackground(dynamicLocationName, cache.getImageUrl());
                            chatRoomRepository.save(room);
                            return cache.getImageUrl();
                        })
                        .orElse(null);
                }

                ChatRoomInfoResponse response = new ChatRoomInfoResponse(
                    room.getId(),
                    character.getName(),
                    character.getSlug(),
                    character.getId(),
                    character.getDefaultImageUrl(),
                    "background_default.png",
                    room.getAffectionScore(),
                    room.getStatusLevel().name(),
                    room.getChatMode().name(),
                    room.getCurrentBgmMode() != null ? room.getCurrentBgmMode().name() : "DAILY",
                    room.getCurrentLocation() != null ? room.getCurrentLocation().name() : character.getEffectiveDefaultLocation(),
                    room.getCurrentOutfit() != null ? room.getCurrentOutfit().name() : character.getEffectiveDefaultOutfit(),
                    room.getCurrentTimeOfDay() != null ? room.getCurrentTimeOfDay().name() : "NIGHT",
                    character.getEffectiveDefaultOutfit(),
                    character.getEffectiveDefaultLocation(),
                    room.isEndingReached(),
                    room.getEndingType() != null ? room.getEndingType().name() : null,
                    room.getEndingTitle(),
                    new java.util.ArrayList<>(character.getAllowedOutfits(room.getStatusLevel(), isSecret)),
                    new java.util.ArrayList<>(character.getAllowedLocations(room.getStatusLevel(), isSecret)),
                    // [Phase 5.5] 입체적 상태창
                    statsSnapshot,
                    room.getCurrentBpm(),
                    room.getDynamicRelationTag(),
                    room.getCharacterThought(),
                    // [Phase 5.5-EV] 이벤트 시스템 강화
                    room.isTopicConcluded(),
                    room.isEventActive(),
                    room.getEventStatus(),
                    // [Phase 5.5-Fix] 동적 배경 영속화
                    dynamicLocationName,
                    dynamicBgUrl,
                    // [Bug #3 Fix] 도메인 분리
                    room.isSecretModeActive(),
                    room.getUserPersona()
                );

                cacheService.cacheRoomInfo(roomId, response);
                return response;
            });
    }

    @Transactional
    public void deleteChatRoom(Long roomId) {
        chatLogRepository.deleteByRoomId(roomId);
        ChatRoom room = chatRoomRepository.findById(roomId).orElseThrow(
            () -> new NotFoundException("채팅방이 존재하지 않습니다. roomId=" + roomId)
        );
        room.resetAll();
        memoryService.clearMemories(roomId);

        cacheService.evictRoomInfo(roomId);
        cacheService.evictRoomOwner(roomId);
    }

    public void initializeChatRoom(Long roomId) {
        if (chatLogRepository.countByRoomId(roomId) > 0) return;

        Character character = txTemplate.execute(status -> {
            ChatRoom room = chatRoomRepository.findWithMemberAndCharacterById(roomId)
                .orElseThrow(() -> new NotFoundException("Room not found"));
            room.updateLastActive(EmotionTag.NEUTRAL);
            room.resetSceneState();
            return room.getCharacter();
        });

        String introNarration = character.getIntroNarration();
        chatLogRepository.save(ChatLogDocument.system(roomId, introNarration));

        String firstGreeting = character.getFirstGreeting();
        chatLogRepository.save(ChatLogDocument.of(
            roomId, ChatRole.ASSISTANT, firstGreeting, firstGreeting, EmotionTag.NEUTRAL, null));
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  유저 평가 시스템 (RLHF)
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    public String rateChatLog(String logId, Long roomId, String rating, String dislikeReason) {
        ChatLogDocument doc = chatLogRepository.findById(logId)
            .orElseThrow(() -> new NotFoundException("채팅 로그를 찾을 수 없습니다."));

        if (!doc.getRoomId().equals(roomId)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "해당 채팅방의 로그가 아닙니다.");
        }

        if (doc.getRole() != ChatRole.ASSISTANT) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "캐릭터 응답에만 평가할 수 있습니다.");
        }

        doc.updateRating(rating);

        if ("DISLIKE".equals(doc.getRating()) && dislikeReason != null && !dislikeReason.isBlank()) {
            doc.updateDislikeReason(dislikeReason);
        } else {
            doc.updateDislikeReason(null);
        }

        chatLogRepository.save(doc);

        log.info("⭐ [RATING] logId={}, roomId={}, rating={} → {}, reason={}",
            logId, roomId, rating, doc.getRating(), doc.getDislikeReason());

        return doc.getRating();
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  개별 대화 삭제
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    public void deleteSingleChatLog(String logId, Long roomId) {
        ChatLogDocument doc = chatLogRepository.findById(logId)
            .orElseThrow(() -> new NotFoundException("채팅 로그를 찾을 수 없습니다."));

        if (!doc.getRoomId().equals(roomId)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "해당 채팅방의 로그가 아닙니다.");
        }

        if (doc.getRole() == ChatRole.SYSTEM) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "시스템 메시지는 삭제할 수 없습니다.");
        }

        chatLogRepository.deleteById(logId);
        log.info("🗑️ [DELETE] Single log deleted: logId={}, roomId={}, role={}",
            logId, roomId, doc.getRole());
    }
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  [Phase 5.5-IT] 속마음 해금
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    private static final int INNER_THOUGHT_UNLOCK_COST = 1;

    /**
     * 속마음(Inner Thought) 해금
     * <p>
     * 1. 로그 유효성 검증 (존재 여부, 방 소유권, 속마음 존재 여부)
     * 2. 이미 해금된 경우 → 중복 과금 방지, 바로 텍스트 반환
     * 3. 에너지 차감 (1 에너지)
     * 4. thoughtUnlocked = true로 업데이트
     * 5. 실제 속마음 텍스트 반환
     *
     * @param logId    ASSISTANT 로그 ID
     * @param roomId   채팅방 ID (소유권 검증용)
     * @param username 유저명 (캐시 무효화용)
     * @return 속마음 텍스트
     */
    @Transactional
    public String unlockInnerThought(String logId, Long roomId, String username) {
        // 1. 로그 조회 및 검증
        ChatLogDocument doc = chatLogRepository.findById(logId)
            .orElseThrow(() -> new NotFoundException("채팅 로그를 찾을 수 없습니다."));

        if (!doc.getRoomId().equals(roomId)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "해당 채팅방의 로그가 아닙니다.");
        }

        if (doc.getRole() != ChatRole.ASSISTANT) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "캐릭터 응답에만 속마음이 존재합니다.");
        }

        if (!doc.hasInnerThought()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "이 응답에는 속마음이 없습니다.");
        }

        // 2. 이미 해금된 경우 — 에너지 차감 없이 바로 반환
        if (doc.isThoughtUnlocked()) {
            log.info("💭 [INNER_THOUGHT] Already unlocked: logId={}", logId);
            return doc.getInnerThought();
        }

        // 3. 에너지 차감
        User user = userRepository.findByUsername(username)
            .orElseThrow(() -> new NotFoundException("유저를 찾을 수 없습니다."));
        user.consumeEnergy(INNER_THOUGHT_UNLOCK_COST);
        userRepository.save(user);

        // 4. 해금 처리
        doc.unlockThought();
        chatLogRepository.save(doc);

        // 5. 캐시 무효화
        cacheService.evictUserProfile(username);

        log.info("💭 [INNER_THOUGHT] Unlocked: logId={} | user={} | cost={}",
            logId, username, INNER_THOUGHT_UNLOCK_COST);

        return doc.getInnerThought();
    }
}