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
import com.spring.aichat.exception.BadRequestException;
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
    private final com.spring.aichat.config.LegacyFeatureProperties legacy;
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
    /** [2026-09-11] 자유 방 페르소나 재적용 — 초기화 시 자동 + 설정창 수동 액션 공용 */
    private final com.spring.aichat.service.persona.UserPersonaService userPersonaService;


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
            // [2026-09-11] 대사·디렉터와 같은 기준 — 속마음만 계정 닉네임을 쓰면
            //   같은 방에서 캐릭터가 유저를 두 이름으로 부른다.
            String nickname = room.getEffectiveNickname(room.getUser());

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

            // [리뷰픽스] 방 정책 차단(캐릭터 비대상·UGC/비허용 월드)은 유저가 어떤 조치로도 풀 수
            // 없다 — 나이 수정·구매 안내(아래 사유 분기)보다 먼저 명확한 400으로 끊는다.
            // [docs/13 확정 픽스] V2 STORY 방은 단일 character=null — 기존 무가드
            // getCharacter().getId()가 NPE 500이었다. V2는 월드 시크릿 정책으로 판정.
            boolean roomEligible = room.getCharacter() != null
                ? secretModeService.isCharacterSecretEligible(room.getCharacter().getId())
                : (room.getWorld() != null && room.getWorld().isSecretAllowed());
            if (!roomEligible) {
                throw new BusinessException(ErrorCode.BAD_REQUEST,
                    "이 이야기에서는 시크릿 모드를 사용할 수 없어요.");
            }

            if (!secretModeService.canAccessSecretMode(user)) {
                if (!Boolean.TRUE.equals(user.getIsAdult())) {
                    throw new BusinessException(ErrorCode.VERIFICATION_UNDERAGE,
                        "성인 인증이 필요합니다.");
                }
                // [블록 B] 페르소나 나이 하드 게이트 — FE는 프로필 나이 수정 제안 모달로 연결
                if (!secretModeService.isPersonaAdult(user.getId())) {
                    throw new BusinessException(ErrorCode.PERSONA_UNDERAGE,
                        "시크릿 모드는 성인 페르소나로만 이용할 수 있어요.");
                }
                throw new BusinessException(ErrorCode.BAD_REQUEST,
                    "시크릿 모드 접근 권한이 없습니다.");
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

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  채팅방 관리 영역
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    public ChatRoomInfoResponse getChatRoomInfo(Long roomId) {
        return cacheService.getRoomInfo(roomId, ChatRoomInfoResponse.class)
            .orElseGet(() -> {
                ChatRoom room = chatRoomRepository.findWithMemberAndCharacterById(roomId)
                    .orElseThrow(() -> new NotFoundException("채팅방이 존재하지 않습니다. roomId=" + roomId));

                // [Phase 7-V2 Pivot Fix] V2 STORY 방은 단일 character가 null (N:1 — 히로인은 ChatRoomHeroine).
                //   이 V1 엔드포인트(GET /chat/rooms/{id})로는 유효한 응답을 만들 수 없으므로,
                //   NPE(getCharacter()/getStatIntimacy() null) 대신 명확한 400을 반환.
                //   정상 경로에서는 V2 방이 /api/v1/story/v2/... 로 라우팅되어 여기 도달하지 않음.
                if (room.getCharacter() == null) {
                    throw new BadRequestException(
                        "V2 스토리 방은 이 엔드포인트로 조회할 수 없습니다. /api/v1/story/v2 를 사용하세요. roomId=" + roomId);
                }

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
                    // [E-4.16] 캐시 행은 BackgroundCache.create가 canonicalKey로 해시한다(:154).
                    //   여기서 구 2-인자 폼(canonicalKey=null → locationName 직해싱)을 쓰면 키가 어긋나
                    //   조회가 항상 빗나가고 → 새로고침 후 동적 배경이 영구 미표시가 된다.
                    //   방에 canonicalKey가 없는 구 방은 3-인자 폼의 폴백이 종전과 같은 해시를 만든다.
                    String cacheHash = com.spring.aichat.domain.illustration.BackgroundCache.computeHash(
                        room.getCurrentDynamicCanonicalKey(), timeOfDay, dynamicLocationName);
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
                    new java.util.ArrayList<>(character.getAllowedOutfits(room.getStatusLevel(), isSecret, legacy.getUnlock().isRelationGated())),
                    new java.util.ArrayList<>(character.getAllowedLocations(room.getStatusLevel(), isSecret, legacy.getUnlock().isRelationGated())),
                    // [Phase 5.5] 입체적 상태창
                    statsSnapshot,
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
                    room.getUserPersona(),
                    // [2026-09-11] 이 방의 호칭 — 프롬프트가 쓰는 값과 같은 기준으로 노출
                    room.getEffectiveNickname(room.getUser()),
                    // [세계관 빌더] UGC 월드 연동 — 프론트 동적 배경 클리어 가드 스킵용
                    character.getUgcWorldId()
                );

                cacheService.cacheRoomInfo(roomId, response);
                return response;
            });
    }

    /**
     * [V1 자유] 대화 기록 초기화. 이름과 달리 <b>방 행은 지우지 않는다</b> — 로그·기억만 비우고
     * 방 상태를 처음으로 되돌린다(그래서 방 재생성으로 페르소나가 갱신될 여지가 없었다).
     *
     * <p>[2026-09-11 종원 확정] 자유 모드는 페르소나를 풀어놓는다 — 초기화는 '처음부터 다시'이므로
     * <b>현재 프로필을 다시 스냅샷</b>한다. 종전에는 {@code resetAll()}이 페르소나를 보존해
     * (블록 B 이전 문법의 잔재: 그때는 유저가 방마다 직접 써넣은 텍스트라 보존이 맞았다)
     * 첫 방 생성 시점의 스냅샷에 영구 동결됐다. 스토리(V2)는 고정 유지 — 그쪽은
     * {@code resetStory(includePersona=true)}가 이미 같은 일을 한다.
     */
    @Transactional
    public void deleteChatRoom(Long roomId) {
        chatLogRepository.deleteByRoomId(roomId);
        ChatRoom room = chatRoomRepository.findById(roomId).orElseThrow(
            () -> new NotFoundException("채팅방이 존재하지 않습니다. roomId=" + roomId)
        );
        room.resetAll();   // 페르소나는 건드리지 않는다(V1 호환) — 재적용은 아래에서 명시적으로
        // ⚠ 모드 가드는 여기서 직접 건다. resetAll()의 requireSandbox()는 throw가 주석 처리된
        //   no-op(ChatRoom.java:479-483)이라 스토리 방을 막아주지 않는다 — 그걸 믿으면
        //   '스토리는 시작 시점 고정'이 초기화 한 번으로 뚫린다.
        if (room.isSandboxMode()) {
            userPersonaService.applyProfileSnapshot(room, room.getUser());
        }
        memoryService.clearMemories(roomId);

        cacheService.evictRoomInfo(roomId);
        cacheService.evictRoomOwner(roomId);
    }

    /**
     * [V1 자유] 진행 중인 방에 현재 프로필을 다시 적용한다(대화 기록은 보존).
     *
     * <p>스토리는 '시작 시점 고정'이 확정 정책이라 자유 모드에서만 허용한다 —
     * {@code resetAll()}의 {@code requireSandbox()}와 같은 경계다.
     */
    @Transactional
    public void refreshRoomPersona(Long roomId) {
        ChatRoom room = chatRoomRepository.findWithMemberAndCharacterById(roomId).orElseThrow(
            () -> new NotFoundException("채팅방이 존재하지 않습니다. roomId=" + roomId)
        );
        if (!room.isSandboxMode()) {
            throw new BadRequestException("스토리 모드는 시작할 때의 페르소나로 고정돼요. 스토리 초기화에서 바꿀 수 있어요.");
        }
        userPersonaService.applyProfileSnapshot(room, room.getUser());
        cacheService.evictRoomInfo(roomId);
        log.info("[ROOM_PERSONA] 현재 프로필 재적용: roomId={}", roomId);
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

        // [UGC 폴리싱 2026-07-20] 나레이션이 없는 캐릭터(과거 생성 UGC 등)는 SYSTEM 로그 자체를 생략
        //   — null 콘텐츠 로그가 저장되던 문제 방지.
        String introNarration = character.getIntroNarration();
        if (introNarration != null && !introNarration.isBlank()) {
            chatLogRepository.save(ChatLogDocument.system(roomId, introNarration));
        }

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

    /**
     * 개별 대화 삭제.
     *
     * <p>[안건 13 재확정 2026-09-04 · 종원] SYSTEM 로그 삭제 금지를 <b>visible 여부로 좁힌다.</b>
     *
     * <p>종전에는 role이 SYSTEM이면 무조건 막았다. 그런데 BRANCH 이벤트 나레이션은
     * {@code ChatLogDocument.system(...)} = <b>visible</b>로 저장돼 유저 화면에 그대로 보인다.
     * 즉 <b>보이는데 지울 수 없는</b> 비대칭이었다 — 유저·캐릭터 메시지는 지워지는데 그것만 안 됐다.
     *
     * <p>안건 13은 원래 이 오염을 {@code hiddenSystem} 복귀로 풀려 했으나
     * ({@code decisions_confirmed.md} §B #13 (나)) <b>철회했다</b>. 나레이션이 히스토리에서 사라지면
     * "캐릭터 대사1 → 캐릭터 대사2"가 부자연스럽게 이어져 맥락이 끊기기 때문이다
     * (그것이 애초 '[Bug Fix A] visible 저장'의 이유였다).
     * 화면에 보이는 것은 <b>보이는 채로 두되 지울 수 있게</b> 하는 것이 옳은 해법이다.
     *
     * <p>hidden SYSTEM은 계속 막는다 — {@code [SYSTEM_DIRECTOR]}·{@code TIME_SKIP}·{@code [ACTION:]}처럼
     * 유저에게 보이지도 않는 LLM 배관이고, 지울 수단을 열면 화면에 없는 것을 지우는 셈이 된다.
     */
    public void deleteSingleChatLog(String logId, Long roomId) {
        ChatLogDocument doc = chatLogRepository.findById(logId)
            .orElseThrow(() -> new NotFoundException("채팅 로그를 찾을 수 없습니다."));

        if (!doc.getRoomId().equals(roomId)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "해당 채팅방의 로그가 아닙니다.");
        }

        if (doc.getRole() == ChatRole.SYSTEM && doc.isHidden()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "내부 시스템 메시지는 삭제할 수 없습니다.");
        }

        chatLogRepository.deleteById(logId);
        log.info("🗑️ [DELETE] Single log deleted: logId={}, roomId={}, role={}, hidden={}",
            logId, roomId, doc.getRole(), doc.isHidden());
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