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
 * [Phase 5.5-RunPod] Fal.ai → RunPod 전환에 따른 변경:
 *   - 이미지가 URL이 아닌 Base64 raw data로 반환
 *   - handleCompletion() → extractImageBase64() → uploadIllustrationFromBase64()
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

    private static final int ILLUSTRATION_ENERGY_COST = 10;

    /** 폴링 최대 시도 횟수 — ComfyUI cold start 고려하여 3분 */
    private static final int MAX_POLL_ATTEMPTS = 180;
    private static final long POLL_INTERVAL_MS = 1000;
    /** 연속 에러 허용 횟수 */
    private static final int MAX_CONSECUTIVE_ERRORS = 10;

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  1. 유저 수동 일러스트 생성 요청
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    @Transactional
    public IllustrationRequestResult requestIllustration(String username, Long roomId) {
        User user = userRepository.findByUsername(username)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "User not found"));

        ChatRoom room = chatRoomRepository.findWithMemberAndCharacterById(roomId)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "ChatRoom not found"));

        if (!room.getUser().getId().equals(user.getId())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Not your chat room");
        }

        user.consumeEnergy(ILLUSTRATION_ENERGY_COST);
        userRepository.save(user);
        cacheService.evictUserProfile(username);

        Character character = room.getCharacter();
        String emotion = room.getLastEmotion() != null ? room.getLastEmotion().name() : "NEUTRAL";
        String location = room.getCurrentLocation() != null ? room.getCurrentLocation().name() : character.getEffectiveDefaultLocation();
        String outfit = room.getCurrentOutfit() != null ? room.getCurrentOutfit().name() : character.getEffectiveDefaultOutfit();

        return submitGeneration(user, character, emotion, location, outfit, "MANUAL");
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  2. 자동 일러스트 생성 (승급/엔딩)
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    @Async
    public void generateAutoIllustration(Long userId, Long characterId, Long roomId, String triggerType) {
        try {
            User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "User not found"));
            Character character = characterRepository.findById(characterId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "Character not found"));
            ChatRoom room = chatRoomRepository.findById(roomId).orElse(null);

            String emotion = "LOVE";
            String location = room != null && room.getCurrentLocation() != null
                ? room.getCurrentLocation().name() : character.getEffectiveDefaultLocation();
            String outfit = room != null && room.getCurrentOutfit() != null
                ? room.getCurrentOutfit().name() : character.getEffectiveDefaultOutfit();

            if ("ENDING".equals(triggerType)) {
                emotion = "JOY";
            }

            IllustrationRequestResult result = submitGeneration(user, character, emotion, location, outfit, triggerType);
            processPollingInBackground(result.requestId());

            log.info("[ILLUST] Auto generation submitted: trigger={}, userId={}, charId={}", triggerType, userId, characterId);
        } catch (Exception e) {
            log.error("[ILLUST] Auto generation failed: trigger={}, userId={}, charId={}", triggerType, userId, characterId, e);
        }
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  3. 폴링으로 상태 확인 (프론트 → 백엔드 API)
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    public IllustrationStatusResult checkStatus(String requestId, String username) {
        UserIllustration illust = illustrationRepository.findByFalRequestId(requestId)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "Illustration not found"));

        if (!illust.getUser().getUsername().equals(username)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Not your illustration");
        }

        if (illust.isCompleted()) {
            return new IllustrationStatusResult("COMPLETED", illust.getImageUrl(), null);
        }

        if ("FAILED".equals(illust.getStatus())) {
            return new IllustrationStatusResult("FAILED", null, illust.getErrorMessage());
        }

        // 아직 진행 중 → RunPod에 직접 폴링
        if (illust.isPending() && illust.getStatusUrl() != null) {
            try {
                PollResult poll = falAiClient.pollStatus(illust.getStatusUrl());
                if (poll.completed()) {
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
    //  4. 웹훅 콜백 처리
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    @Transactional
    public void handleWebhookCallback(String requestId, JsonNode payload) {
        log.info("[ILLUST-WEBHOOK] Received: requestId={}", requestId);

        UserIllustration illust = illustrationRepository.findByFalRequestId(requestId).orElse(null);
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

        String positivePrompt = promptAssembler.assemblePositivePrompt(slug, emotion, location, outfit);
        String negativePrompt = promptAssembler.getNegativePrompt();
        String loraUrl = promptAssembler.getLoraUrl(slug);

        JsonNode workflow = falAiClient.buildCharacterWorkflow(loraUrl, positivePrompt, negativePrompt);
        QueueResponse queueResp = falAiClient.submitToQueue(workflow);

        UserIllustration illust = UserIllustration.createPending(
            user, character.getId(), character.getName(),
            queueResp.requestId(), queueResp.statusUrl(), queueResp.responseUrl(),
            positivePrompt, triggerType, emotion, location, outfit
        );
        illustrationRepository.save(illust);

        log.info("[ILLUST] Submitted: requestId={}, slug={}, trigger={}, userId={}",
            queueResp.requestId(), slug, triggerType, user.getId());

        return new IllustrationRequestResult(queueResp.requestId(), illust.getId(), "PENDING");
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  내부: 완료 처리 (Base64 → S3 업로드 + DB 상태 전이)
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /**
     * [Phase 5.5-RunPod] Base64 이미지 데이터를 S3에 업로드하고 DB 상태 전이
     */
    @Transactional
    protected void handleCompletion(UserIllustration illust, JsonNode payload) {
        try {
            // [RunPod] Base64 데이터 추출
            String base64Data = falAiClient.extractImageBase64(payload);
            if (base64Data == null) {
                illust.markFailed("No image data in RunPod response");
                illustrationRepository.save(illust);
                return;
            }

            // Base64 → S3 직접 업로드
            String s3Url = s3StorageService.uploadIllustrationFromBase64(
                base64Data,
                illust.getUser().getId(),
                illust.getCharacterId(),
                illust.getFalRequestId()
            );

            illust.markCompleted(s3Url, "runpod:base64"); // falTempUrl 대신 출처 마커
            illustrationRepository.save(illust);

            log.info("[ILLUST] ✅ Completed: requestId={}, s3Url={}", illust.getFalRequestId(), s3Url);
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

            String lastStatus = "";
            int consecutiveErrors = 0;
            long startTime = System.currentTimeMillis();

            for (int i = 0; i < MAX_POLL_ATTEMPTS; i++) {
                Thread.sleep(POLL_INTERVAL_MS);

                PollResult poll = falAiClient.pollStatus(illust.getStatusUrl());
                String currentStatus = poll.status();

                // 상태 변화 감지 로깅
                if (!currentStatus.equals(lastStatus)) {
                    long elapsed = (System.currentTimeMillis() - startTime) / 1000;
                    log.info("[ILLUST] Status changed: {} → {} | elapsed={}s | requestId={}",
                        lastStatus.isEmpty() ? "(start)" : lastStatus,
                        currentStatus, elapsed, requestId);
                    lastStatus = currentStatus;
                }

                // 10초마다 진행률 로깅
                if (i > 0 && i % 10 == 0) {
                    long elapsed = (System.currentTimeMillis() - startTime) / 1000;
                    log.info("[ILLUST] Still polling: status={} | attempt={}/{} | elapsed={}s | requestId={}",
                        currentStatus, i, MAX_POLL_ATTEMPTS, elapsed, requestId);
                }

                if (poll.completed()) {
                    long elapsed = (System.currentTimeMillis() - startTime) / 1000;
                    log.info("[ILLUST] ✅ Generation completed in {}s | requestId={}", elapsed, requestId);
                    handleCompletion(illust, poll.payload()); // RunPod은 페이로드에 결과 포함
                    return;
                }

                if ("FAILED".equalsIgnoreCase(currentStatus)) {
                    illust.markFailed("RunPod generation failed");
                    illustrationRepository.save(illust);
                    log.error("[ILLUST] ❌ RunPod generation FAILED: requestId={}", requestId);
                    return;
                }

                // 연속 에러 감지
                if ("ERROR".equalsIgnoreCase(currentStatus)) {
                    consecutiveErrors++;
                    if (consecutiveErrors >= MAX_CONSECUTIVE_ERRORS) {
                        illust.markFailed("Aborted after " + consecutiveErrors + " consecutive poll errors");
                        illustrationRepository.save(illust);
                        log.error("[ILLUST] ❌ {} consecutive poll errors, aborting | requestId={}",
                            consecutiveErrors, requestId);
                        return;
                    }
                } else {
                    consecutiveErrors = 0;
                }
            }

            long elapsed = (System.currentTimeMillis() - startTime) / 1000;
            illust.markFailed("Generation timed out after " + elapsed + " seconds");
            illustrationRepository.save(illust);
            log.warn("[ILLUST] ⏱ Polling timed out after {}s | lastStatus={} | requestId={}",
                elapsed, lastStatus, requestId);

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