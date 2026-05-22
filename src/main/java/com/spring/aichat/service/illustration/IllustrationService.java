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
import com.spring.aichat.external.ModelsLabClient;
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

    private final ModelsLabClient modelsLabClient;
    private final S3StorageService s3StorageService;
    private final IllustrationPromptAssembler promptAssembler;
    private final UserIllustrationRepository illustrationRepository;
    private final UserRepository userRepository;
    private final CharacterRepository characterRepository;
    private final ChatRoomRepository chatRoomRepository;
    private final RedisCacheService cacheService;
    /**
     * [Phase 5.5 UX Polish · R6] AUTO 일러스트가 특정 DirectorNote와 연결됐을 때
     * 폴링 완료 시 노트의 relatedIllustrationUrl을 업데이트하기 위한 의존성.
     * (optional — 연결 노트가 없으면 사용되지 않음)
     */
    private final com.spring.aichat.domain.theater.TheaterDirectorNoteRepository directorNoteRepository;

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

        // [Phase 6-Illust] LLM이 매 응답에 갱신한 scene hint + 동적 장소 묘사 활용
        String sceneHint = room.getLastIllustrationHint();
        String dynamicLocDesc = room.getCurrentDynamicLocationName() != null
            ? buildDynamicLocationTagsFromCache(room) : null;

        return submitGeneration(user, character, emotion, location, outfit,
            "MANUAL", sceneHint, dynamicLocDesc);
    }

    /**
     * [Phase 6-Illust] 동적 장소의 캐시된 description을 prompt 태그로 변환.
     * room.currentDynamicLocationName이 있으면 BackgroundCache 또는 ChatRoom에 저장된
     * 묘사를 가져온다. 현재는 단순히 location name만 반환 — 향후 BackgroundCache에서
     * canonical_key 기반으로 조회하여 description 활용 확장 가능.
     */
    private String buildDynamicLocationTagsFromCache(ChatRoom room) {
        String name = room.getCurrentDynamicLocationName();
        if (name == null || name.isBlank()) return null;
        // 단순 변환: "심야의 무인 카페" → 캐릭터 일러스트 배경 슬롯에 활용 가능한 형태
        return name + ", anime background, soft lighting";
    }
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  2. 자동 일러스트 생성 (승급/엔딩)
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    // [Phase6/Tier4 / H-17] executor 명시 — TheaterConfig#illustrationExecutor 사용.
    @Async("illustrationExecutor")
    public void generateAutoIllustration(Long userId, Long characterId, Long roomId,
                                         String triggerType, Long noteId) {
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

            // [Phase 6-Illust] hint/dynamicLocation 추출 (room이 null이면 둘 다 null)
            String sceneHint = room != null ? room.getLastIllustrationHint() : null;
            String dynamicLocDesc = (room != null && room.getCurrentDynamicLocationName() != null)
                ? buildDynamicLocationTagsFromCache(room) : null;

            IllustrationRequestResult result = submitGeneration(
                user, character, emotion, location, outfit, triggerType,
                sceneHint, dynamicLocDesc
            );

            // [R6] 노트 연결 — 폴링 완료 시 handleCompletion이 노트 URL을 업데이트
            if (noteId != null && result != null && result.illustrationId() != null) {
                try {
                    illustrationRepository.findById(result.illustrationId()).ifPresent(illust -> {
                        illust.linkToNote(noteId);
                        illustrationRepository.save(illust);
                    });
                } catch (Exception linkErr) {
                    log.warn("[ILLUST] linkToNote failed (non-fatal): noteId={}, err={}",
                        noteId, linkErr.getMessage());
                }
            }

            // [Phase 6-Illust] 동기 완료된 경우 폴링 불필요 — submitGeneration 내부에서 이미 처리됨
            if (!"COMPLETED".equals(result.status())) {
                processPollingInBackground(result.requestId());
            }

            log.info("[ILLUST] Auto generation submitted: trigger={}, userId={}, charId={}, noteId={}, status={}",
                triggerType, userId, characterId, noteId, result.status());
        } catch (Exception e) {
            log.error("[ILLUST] Auto generation failed: trigger={}, userId={}, charId={}",
                triggerType, userId, characterId, e);
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
//        if (illust.isPending() && illust.getStatusUrl() != null) {
//            try {
//                PollResult poll = modelsLabClient.pollStatus(illust.getStatusUrl());
//                if (poll.completed()) {
//                    handleCompletion(illust, poll.payload());
//                    return new IllustrationStatusResult("COMPLETED", illust.getImageUrl(), null);
//                }
//                return new IllustrationStatusResult(poll.status(), null, null);
//            } catch (Exception e) {
//                log.warn("[ILLUST] Poll failed for {}: {}", requestId, e.getMessage());
//                return new IllustrationStatusResult("GENERATING", null, null);
//            }
//        }

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

    /**
     * [Phase 6-Illust] ModelsLab webhook 콜백 — 비동기 큐 완료 시 호출.
     *
     * IllustrationWebhookController가 trackId가 "BG_" prefix가 아닐 때(=캐릭터 일러스트일 때) 이 메서드 호출.
     * 폴링과 webhook 경쟁 — 이미 COMPLETED면 skip.
     */
    @Transactional
    public void handleModelsLabWebhookCallback(String generationId, JsonNode payload) {
        UserIllustration illust = illustrationRepository.findByFalRequestId(generationId).orElse(null);
        if (illust == null) {
            log.warn("[ILLUST-WEBHOOK] Unknown generationId: {}", generationId);
            return;
        }
        if ("COMPLETED".equals(illust.getStatus())) {
            log.info("[ILLUST-WEBHOOK] Already completed (polling won race): {}", generationId);
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

    /**
     * [Phase 6-Illust] 캐릭터 일러스트 생성 제출 — ModelsLab 트랙.
     *
     * 6단 prompt 구조: LoRA + 정체성 + 의상 + 장소(enum/동적) + 표정 + sceneHint(동적).
     * 응답이 동기 완료이면 즉시 S3 업로드까지 마치고 COMPLETED 상태로 반환,
     * 큐 진입이면 PENDING 상태로 저장 후 호출자가 폴링.
     */
    private IllustrationRequestResult submitGeneration(
        User user, Character character,
        String emotion, String location, String outfit, String triggerType,
        String sceneHint, String dynamicLocDesc
    ) {
        String slug = character.getSlug();

        // [Phase 6-Illust] 신 6단 시그니처
        String positivePrompt = promptAssembler.assemblePositivePrompt(
            slug, emotion, location, outfit, sceneHint, dynamicLocDesc);
        String negativePrompt = promptAssembler.getNegativePrompt();
        String loraId = promptAssembler.getLoraId(slug);

        // 캐릭터 정체성 LoRA + (옵션) 글로벌 Detail/Style LoRA 슬롯 조합
        java.util.List<ModelsLabClient.LoraSlot> loras = new java.util.ArrayList<>();
        loras.add(new ModelsLabClient.LoraSlot(loraId, 1.0));
        // Detail/Style은 ModelsLabProperties의 효과 활성 시 ModelsLabClient가 자동 추가하지 않으므로,
        // 여기서 명시적으로 props를 읽어 추가하는 패턴이 명확하다. 단, 현 구현은 client 외부에 둠.

        String trackId = "ILL_" + user.getId() + "_" + System.currentTimeMillis();

        ModelsLabClient.SubmitResult submitResult = modelsLabClient.submit(
            new ModelsLabClient.GenerationRequest(
                null,                // modelId — null이면 props.defaultModelId
                positivePrompt, negativePrompt,
                loras, trackId
            )
        );

        // 호환: UserIllustration 컬럼은 falRequestId/statusUrl/responseUrl을 그대로 사용 (의미는 provider 무관 id)
        String requestId = submitResult.generationId();
        String fetchUrl = submitResult.fetchUrl();  // 큐 모드 시 fetch URL, 동기 완료 시 null

        UserIllustration illust;
        try {
            illust = UserIllustration.createPending(
                user, character.getId(), character.getName(),
                requestId,
                fetchUrl,                    // statusUrl 컬럼에 fetchUrl 저장 (의미 재해석)
                fetchUrl,                    // responseUrl 컬럼에도 동일 저장
                positivePrompt, triggerType, emotion, location, outfit
            );
            illustrationRepository.save(illust);
        } catch (RuntimeException e) {
            log.error("[ILLUST] ORPHAN_QUEUE_REQUEST | external queue submitted but DB save failed " +
                    "| requestId={} | slug={} | userId={} | trigger={}",
                requestId, slug, user.getId(), triggerType, e);
            throw e;
        }

        // 동기 완료 케이스: 즉시 다운로드 + S3 업로드 후 COMPLETED로 마무리
        if (submitResult.syncCompleted() && submitResult.imageUrl() != null) {
            log.info("[ILLUST] Sync-completed at submit: requestId={}, slug={}, userId={}",
                requestId, slug, user.getId());
            try {
                String s3Url = s3StorageService.downloadAndUpload(
                    submitResult.imageUrl(),
                    "illustrations/",
                    "user_" + user.getId() + "_char_" + character.getId() + "_" + requestId
                );
                illust.markCompleted(s3Url, submitResult.imageUrl());
                illustrationRepository.save(illust);

                attachToDirectorNoteIfLinked(illust, s3Url);

                return new IllustrationRequestResult(requestId, illust.getId(), "COMPLETED");
            } catch (Exception e) {
                log.error("[ILLUST] Sync-complete S3 upload failed, fallback to polling: requestId={}", requestId, e);
                // 폴링으로 폴백
            }
        }

        log.info("[ILLUST] Submitted (queue): requestId={}, slug={}, trigger={}, userId={}",
            requestId, slug, triggerType, user.getId());

        return new IllustrationRequestResult(requestId, illust.getId(), "PENDING");
    }

    /**
     * 구버전 호환 — 4-arg(+triggerType) 시그니처. 신규 슬롯은 null로 호출.
     */
    private IllustrationRequestResult submitGeneration(
        User user, Character character,
        String emotion, String location, String outfit, String triggerType
    ) {
        return submitGeneration(user, character, emotion, location, outfit, triggerType, null, null);
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
            // [Phase 6-Illust] ModelsLab은 URL 반환
            String imageUrl = modelsLabClient.extractFirstOutputUrl(payload);
            if (imageUrl == null) {
                illust.markFailed("No image URL in ModelsLab response");
                illustrationRepository.save(illust);
                return;
            }

            String s3Url = s3StorageService.downloadAndUpload(
                imageUrl,
                "illustrations/",
                "user_" + illust.getUser().getId() + "_char_" + illust.getCharacterId() + "_" + illust.getFalRequestId()
            );

            illust.markCompleted(s3Url, imageUrl);
            illustrationRepository.save(illust);

            attachToDirectorNoteIfLinked(illust, s3Url);

            log.info("[ILLUST] ✅ Completed: requestId={}, s3Url={}", illust.getFalRequestId(), s3Url);
        } catch (Exception e) {
            log.error("[ILLUST] Completion failed: requestId={}", illust.getFalRequestId(), e);
            illust.markFailed(e.getMessage());
            illustrationRepository.save(illust);
        }
    }

    /**
     * DirectorNote 연결 시 일러스트 URL 부착 — handleCompletion 중복 코드 추출.
     */
    private void attachToDirectorNoteIfLinked(UserIllustration illust, String s3Url) {
        Long noteId = illust.getLinkedNoteId();
        if (noteId == null) return;
        try {
            directorNoteRepository.findById(noteId).ifPresent(note -> {
                note.attachIllustration(s3Url);
                directorNoteRepository.save(note);
            });
            log.info("[ILLUST] ✅ Linked to DirectorNote: noteId={}, s3Url={}", noteId, s3Url);
        } catch (Exception linkErr) {
            log.warn("[ILLUST] DirectorNote link failed (non-fatal): noteId={}, err={}",
                noteId, linkErr.getMessage());
        }
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  내부: 백그라운드 폴링 (자동 생성용)
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    @Async("illustrationExecutor")
    protected void processPollingInBackground(String requestId) {
        try {
            UserIllustration illust = illustrationRepository.findByFalRequestId(requestId).orElse(null);
            if (illust == null) return;

            String lastStatus = "";
            int consecutiveErrors = 0;
            long startTime = System.currentTimeMillis();

            for (int i = 0; i < MAX_POLL_ATTEMPTS; i++) {
                Thread.sleep(POLL_INTERVAL_MS);

                // [Phase 6-Illust] ModelsLab fetch
                ModelsLabClient.PollResult poll = modelsLabClient.fetch(illust.getStatusUrl(), requestId);
                String currentStatus = poll.status();

                if (!currentStatus.equals(lastStatus)) {
                    long elapsed = (System.currentTimeMillis() - startTime) / 1000;
                    log.info("[ILLUST] Status changed: {} → {} | elapsed={}s | requestId={}",
                        lastStatus.isEmpty() ? "(start)" : lastStatus, currentStatus, elapsed, requestId);
                    lastStatus = currentStatus;
                }

                if (i > 0 && i % 20 == 0) {
                    long elapsed = (System.currentTimeMillis() - startTime) / 1000;
                    log.info("[ILLUST] Still polling: status={} | attempt={}/{} | elapsed={}s | requestId={}",
                        currentStatus, i, MAX_POLL_ATTEMPTS, elapsed, requestId);
                }

                if (poll.completed()) {
                    long elapsed = (System.currentTimeMillis() - startTime) / 1000;
                    log.info("[ILLUST] ✅ Generation completed in {}s | requestId={}", elapsed, requestId);
                    // ModelsLab 응답 페이로드를 그대로 handleCompletion에 전달
                    handleCompletion(illust, poll.payload());
                    return;
                }

                if ("FAILED".equalsIgnoreCase(currentStatus)) {
                    illust.markFailed("ModelsLab generation failed");
                    illustrationRepository.save(illust);
                    log.error("[ILLUST] ❌ ModelsLab generation FAILED: requestId={}", requestId);
                    return;
                }

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
            log.error("[ILLUST] Polling unexpected error: requestId={}", requestId, e);
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