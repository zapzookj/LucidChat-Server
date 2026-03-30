package com.spring.aichat.service.illustration;

import com.fasterxml.jackson.databind.JsonNode;
import com.spring.aichat.domain.character.Character;
import com.spring.aichat.domain.character.CharacterRepository;
import com.spring.aichat.domain.chat.ChatRoom;
import com.spring.aichat.domain.chat.ChatRoomRepository;
import com.spring.aichat.domain.illustration.UserIllustration;
import com.spring.aichat.domain.illustration.UserIllustrationRepository;
import com.spring.aichat.domain.user.User;
import com.spring.aichat.domain.user.UserRepository;
import com.spring.aichat.exception.BusinessException;
import com.spring.aichat.exception.ErrorCode;
import com.spring.aichat.external.FalAiClient;
import com.spring.aichat.external.FalAiClient.QueueResponse;
import com.spring.aichat.external.FalAiClient.PollResult;
import com.spring.aichat.service.cache.RedisCacheService;
import com.spring.aichat.service.prompt.IllustrationPromptAssembler;
import com.spring.aichat.service.storage.S3StorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * [Phase 5.5-Illust] 캐릭터 일러스트 생성 서비스
 *
 * 전체 흐름:
 *   1. 유저 에너지 차감 (10)
 *   2. 프롬프트 조립 (캐릭터 slug + 현재 감정/장소/복장)
 *   3. ComfyUI 워크플로우 빌드 → Fal.ai 비동기 큐 제출
 *   4. UserIllustration PENDING 레코드 생성 (requestId 기록)
 *   5. 비동기 폴링 루프 또는 웹훅으로 완료 대기
 *   6. 완료 시: Fal.ai 임시 URL → S3 영구 적재 → DB 상태 COMPLETED
 *   7. 프론트에서 폴링 API로 상태 확인 → 완료 시 이미지 URL 반환
 *
 * 트리거 유형:
 *   - MANUAL: 유저가 직접 "일러스트 생성" 버튼 클릭
 *   - PROMOTION: 관계 승급 이벤트 성공 시 자동
 *   - ENDING: 엔딩 크레딧 도달 시 자동
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class IllustrationService {

    private final FalAiClient falAiClient;
    private final S3StorageService s3StorageService;
    private final IllustrationPromptAssembler promptAssembler;
    private final UserIllustrationRepository illustrationRepository;
    private final UserRepository userRepository;
    private final CharacterRepository characterRepository;
    private final ChatRoomRepository chatRoomRepository;
    private final RedisCacheService cacheService;

    /** 일러스트 생성 에너지 비용 */
    private static final int ILLUSTRATION_ENERGY_COST = 10;

    /** 폴링 최대 시도 횟수 (1초 간격 × 60 = 최대 60초) */
    private static final int MAX_POLL_ATTEMPTS = 60;

    /** 폴링 간격 (ms) */
    private static final long POLL_INTERVAL_MS = 1000;

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  1. 유저 수동 일러스트 생성 요청
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /**
     * 유저가 직접 일러스트 생성 버튼을 클릭한 경우
     *
     * @param username  인증된 유저명
     * @param roomId    현재 채팅방 ID (씬 상태 조회용)
     * @return 생성 요청 결과 (requestId, 폴링 URL)
     */
    @Transactional
    public IllustrationRequestResult requestIllustration(String username, Long roomId) {
        User user = userRepository.findByUsername(username)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "User not found"));

        ChatRoom room = chatRoomRepository.findWithMemberAndCharacterById(roomId)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "ChatRoom not found"));

        // 권한 확인
        if (!room.getUser().getId().equals(user.getId())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Not your chat room");
        }

        // 에너지 차감
        user.consumeEnergy(ILLUSTRATION_ENERGY_COST);
        userRepository.save(user);
        cacheService.evictUserProfile(username);

        // 현재 씬 상태에서 프롬프트 조립
        Character character = room.getCharacter();
        String emotion = room.getLastEmotion() != null ? room.getLastEmotion().name() : "NEUTRAL";
        String location = room.getCurrentLocation() != null ? room.getCurrentLocation().name() : character.getEffectiveDefaultLocation();
        String outfit = room.getCurrentOutfit() != null ? room.getCurrentOutfit().name() : character.getEffectiveDefaultOutfit();

        return submitGeneration(user, character, emotion, location, outfit, "MANUAL");
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  2. 자동 일러스트 생성 (승급/엔딩)
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /**
     * 관계 승급 성공 또는 엔딩 도달 시 자동 생성 (에너지 미차감)
     */
    @Async
    public void generateAutoIllustration(Long userId, Long characterId, Long roomId, String triggerType) {
        try {
            User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "User not found"));
            Character character = characterRepository.findById(characterId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "Character not found"));
            ChatRoom room = chatRoomRepository.findById(roomId).orElse(null);

            String emotion = "LOVE"; // 승급/엔딩 기본 감정
            String location = room != null && room.getCurrentLocation() != null
                ? room.getCurrentLocation().name() : character.getEffectiveDefaultLocation();
            String outfit = room != null && room.getCurrentOutfit() != null
                ? room.getCurrentOutfit().name() : character.getEffectiveDefaultOutfit();

            if ("ENDING".equals(triggerType)) {
                emotion = "JOY"; // 해피엔딩
            }

            IllustrationRequestResult result = submitGeneration(user, character, emotion, location, outfit, triggerType);

            // 자동 생성은 폴링까지 백그라운드에서 완료
            processPollingInBackground(result.requestId());

            log.info("[ILLUST] Auto generation completed: trigger={}, userId={}, charId={}", triggerType, userId, characterId);
        } catch (Exception e) {
            log.error("[ILLUST] Auto generation failed: trigger={}, userId={}, charId={}", triggerType, userId, characterId, e);
        }
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  3. 폴링으로 상태 확인 (프론트 → 백엔드 API)
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /**
     * 프론트엔드가 주기적으로 호출하여 생성 상태를 확인
     */
    public IllustrationStatusResult checkStatus(String requestId, String username) {
        UserIllustration illust = illustrationRepository.findByFalRequestId(requestId)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "Illustration not found"));

        // 권한 확인
        if (!illust.getUser().getUsername().equals(username)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Not your illustration");
        }

        if (illust.isCompleted()) {
            return new IllustrationStatusResult("COMPLETED", illust.getImageUrl(), null);
        }

        if ("FAILED".equals(illust.getStatus())) {
            return new IllustrationStatusResult("FAILED", null, illust.getErrorMessage());
        }

        // 아직 진행 중 → Fal.ai에 직접 폴링
        if (illust.isPending() && illust.getStatusUrl() != null) {
            try {
                PollResult poll = falAiClient.pollStatus(illust.getStatusUrl());
                if (poll.completed()) {
                    // 완료 → 결과 처리
                    handleCompletion(illust, poll.payload());
                    return new IllustrationStatusResult("COMPLETED", illust.getImageUrl(), null);
                }
                return new IllustrationStatusResult(poll.status(), null, null);
            } catch (Exception e) {
                log.warn("[ILLUST] Poll failed for {}: {}", requestId, e.getMessage());
                return new IllustrationStatusResult("GENERATING", null, null);
            }
        }

        return new IllustrationStatusResult(illust.getStatus(), null, null);
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  4. 웹훅 콜백 처리 (Fal.ai → 백엔드)
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /**
     * Fal.ai 웹훅으로 완료 통보 수신
     */
    @Transactional
    public void handleWebhookCallback(String requestId, JsonNode payload) {
        log.info("[ILLUST-WEBHOOK] Received: requestId={}", requestId);

        UserIllustration illust = illustrationRepository.findByFalRequestId(requestId)
            .orElse(null);

        if (illust == null) {
            log.warn("[ILLUST-WEBHOOK] Illustration not found for requestId={}", requestId);
            return;
        }

        if (illust.isCompleted()) {
            log.info("[ILLUST-WEBHOOK] Already completed (idempotent): {}", requestId);
            return;
        }

        handleCompletion(illust, payload);
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  5. 갤러리 조회
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /**
     * 특정 유저의 완료된 일러스트 갤러리
     */
    public List<IllustrationGalleryItem> getGallery(String username, Long characterId) {
        User user = userRepository.findByUsername(username)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "User not found"));

        List<UserIllustration> illustrations;
        if (characterId != null) {
            illustrations = illustrationRepository
                .findByUserIdAndCharacterIdAndStatusOrderByCreatedAtDesc(user.getId(), characterId, "COMPLETED");
        } else {
            illustrations = illustrationRepository
                .findByUserIdAndStatusOrderByCreatedAtDesc(user.getId(), "COMPLETED");
        }

        return illustrations.stream()
            .map(i -> new IllustrationGalleryItem(
                i.getId(), i.getCharacterId(), i.getCharacterName(),
                i.getImageUrl(), i.getEmotion(), i.getLocation(), i.getOutfit(),
                i.getTriggerType(), i.getCreatedAt().toString()))
            .toList();
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  내부: 생성 제출 공통 로직
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    private IllustrationRequestResult submitGeneration(
        User user, Character character,
        String emotion, String location, String outfit, String triggerType
    ) {
        String slug = character.getSlug();

        // 프롬프트 조립
        String positivePrompt = promptAssembler.assemblePositivePrompt(slug, emotion, location, outfit);
        String negativePrompt = promptAssembler.getNegativePrompt();
        String loraUrl = promptAssembler.getLoraUrl(slug);

        // ComfyUI 워크플로우 빌드
        JsonNode workflow = falAiClient.buildCharacterWorkflow(loraUrl, positivePrompt, negativePrompt);

        // Fal.ai 비동기 큐 제출
        QueueResponse queueResp = falAiClient.submitToQueue(workflow);

        // DB에 PENDING 레코드 생성
        UserIllustration illust = UserIllustration.createPending(
            user, character.getId(), character.getName(),
            queueResp.requestId(), queueResp.statusUrl(), queueResp.responseUrl(),
            positivePrompt, triggerType, emotion, location, outfit
        );
        illustrationRepository.save(illust);

        log.info("[ILLUST] Submitted: requestId={}, slug={}, trigger={}, userId={}",
            queueResp.requestId(), slug, triggerType, user.getId());

        return new IllustrationRequestResult(
            queueResp.requestId(), illust.getId(), "PENDING");
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  내부: 완료 처리 (S3 업로드 + DB 상태 전이)
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    @Transactional
    protected void handleCompletion(UserIllustration illust, JsonNode payload) {
        try {
            String falImageUrl = falAiClient.extractImageUrl(payload);
            if (falImageUrl == null) {
                illust.markFailed("No image in Fal.ai response");
                illustrationRepository.save(illust);
                return;
            }

            // S3 영구 적재
            String s3Url = s3StorageService.downloadAndUpload(
                falImageUrl, "illustrations/",
                "user_%d_char_%d_%s".formatted(
                    illust.getUser().getId(), illust.getCharacterId(),
                    illust.getFalRequestId().substring(0, 8)));

            illust.markCompleted(s3Url, falImageUrl);
            illustrationRepository.save(illust);

            log.info("[ILLUST] Completed: requestId={}, s3Url={}", illust.getFalRequestId(), s3Url);
        } catch (Exception e) {
            log.error("[ILLUST] Completion failed: requestId={}", illust.getFalRequestId(), e);
            illust.markFailed(e.getMessage());
            illustrationRepository.save(illust);
        }
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  내부: 백그라운드 폴링 (자동 생성용)
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    @Async
    protected void processPollingInBackground(String requestId) {
        try {
            UserIllustration illust = illustrationRepository.findByFalRequestId(requestId).orElse(null);
            if (illust == null) return;

            for (int i = 0; i < MAX_POLL_ATTEMPTS; i++) {
                Thread.sleep(POLL_INTERVAL_MS);

                PollResult poll = falAiClient.pollStatus(illust.getStatusUrl());
                if (poll.completed()) {
                    // response_url로 전체 결과 조회
                    JsonNode fullResult = illust.getResponseUrl() != null
                        ? falAiClient.fetchResult(illust.getResponseUrl())
                        : poll.payload();
                    handleCompletion(illust, fullResult);
                    return;
                }

                if ("FAILED".equalsIgnoreCase(poll.status())) {
                    illust.markFailed("Fal.ai generation failed");
                    illustrationRepository.save(illust);
                    return;
                }
            }

            // 타임아웃
            illust.markFailed("Generation timed out after " + MAX_POLL_ATTEMPTS + " seconds");
            illustrationRepository.save(illust);
            log.warn("[ILLUST] Polling timed out: requestId={}", requestId);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("[ILLUST] Polling interrupted: requestId={}", requestId);
        } catch (Exception e) {
            log.error("[ILLUST] Polling error: requestId={}", requestId, e);
        }
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  DTO
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    public record IllustrationRequestResult(String requestId, Long illustrationId, String status) {}
    public record IllustrationStatusResult(String status, String imageUrl, String errorMessage) {}
    public record IllustrationGalleryItem(
        Long id, Long characterId, String characterName,
        String imageUrl, String emotion, String location, String outfit,
        String triggerType, String createdAt
    ) {}
}