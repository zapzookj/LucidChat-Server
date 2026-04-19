package com.spring.aichat.service.theater;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.spring.aichat.domain.chat.ChatRoom;
import com.spring.aichat.domain.chat.ChatRoomRepository;
import com.spring.aichat.domain.enums.ChatModePolicy;
import com.spring.aichat.domain.enums.TheaterAct;
import com.spring.aichat.domain.theater.*;
import com.spring.aichat.dto.theater.TheaterResponses.LoadResult;
import com.spring.aichat.dto.theater.TheaterResponses.SaveResult;
import com.spring.aichat.dto.theater.TheaterResponses.SaveSlotView;
import com.spring.aichat.exception.BadRequestException;
import com.spring.aichat.exception.BusinessException;
import com.spring.aichat.exception.ErrorCode;
import com.spring.aichat.exception.NotFoundException;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;

/**
 * [Phase 5.5-Theater] 세이브/로드 서비스
 *
 * 5개 수동 슬롯 (1~5) + 1개 Quick Save (0).
 *
 * [복원 범위]
 *  - TheaterState 진행 필드 (Act/Chapter/Scene/batchId/스탯/인터미션)
 *  - TheaterHeroineAffection (호감도, totalScenes)
 *  - 로그는 유지 (MongoDB append-only)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TheaterSaveLoadService {

    private final ChatRoomRepository chatRoomRepository;
    private final TheaterStateRepository theaterStateRepository;
    private final TheaterHeroineAffectionRepository affectionRepository;
    private final TheaterSaveSlotRepository saveSlotRepository;
    private final TheaterBatchCacheService batchCache;
    private final ObjectMapper objectMapper;

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  1. 슬롯 목록
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    public List<SaveSlotView> listSlots(Long roomId, String username) {
        getOwnedRoom(roomId, username);
        List<TheaterSaveSlot> slots = saveSlotRepository.findByRoom_IdOrderBySlotNumberAsc(roomId);

        List<SaveSlotView> views = new ArrayList<>();
        views.add(toView(slots, 0)); // Quick Save
        for (int i = 1; i <= ChatModePolicy.THEATER_MAX_SAVE_SLOTS; i++) {
            views.add(toView(slots, i));
        }
        return views;
    }

    private SaveSlotView toView(List<TheaterSaveSlot> slots, int slotNumber) {
        Optional<TheaterSaveSlot> match = slots.stream()
            .filter(s -> s.getSlotNumber() == slotNumber).findFirst();

        if (match.isEmpty()) {
            return new SaveSlotView(slotNumber, null, null, 0, 0, null, null,
                slotNumber == 0, null, true);
        }

        TheaterSaveSlot s = match.get();
        return new SaveSlotView(
            s.getSlotNumber(), s.getLabel(), s.getPreviewText(),
            s.getActNumber(), s.getChapterNumber(),
            s.getLeadHeroineId(), null,
            s.isQuickSave(), s.getSavedAt(), false
        );
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  2. 세이브
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    @Transactional
    public SaveResult save(Long roomId, String username, int slotNumber, String label) {
        ChatRoom room = getOwnedRoom(roomId, username);
        TheaterState state = getState(roomId);

        if (slotNumber < 0 || slotNumber > ChatModePolicy.THEATER_MAX_SAVE_SLOTS) {
            throw new BadRequestException("잘못된 슬롯 번호입니다.");
        }
        if (slotNumber == 0) {
            throw new BadRequestException("Quick Save 슬롯(0)은 직접 저장할 수 없습니다.");
        }
        if (state.isInIntermission() || state.isInterventionActive()) {
            throw new BadRequestException("인터미션/난입 중에는 세이브할 수 없습니다.");
        }

        String snapshotJson = buildSnapshot(state, roomId);
        String previewText = String.format("Act %d - Chapter %d (Scene %d)",
            state.getCurrentAct().getNumber(),
            state.getCurrentChapter(),
            state.getScenesInCurrentChapter());
        String autoLabel = label != null && !label.isBlank() ? label
            : state.getCurrentAct().getTitle() + " - Chapter " + state.getCurrentChapter();

        TheaterHeroineAffection lead = affectionRepository
            .findByRoomOrderByAffectionDesc(roomId).stream().findFirst().orElse(null);
        Long leadId = lead != null ? lead.getCharacter().getId() : null;

        Optional<TheaterSaveSlot> existing = saveSlotRepository
            .findByRoom_IdAndSlotNumber(roomId, slotNumber);

        TheaterSaveSlot slot;
        if (existing.isPresent()) {
            slot = existing.get();
            slot.overwrite(autoLabel, previewText,
                state.getCurrentAct().getNumber(), state.getCurrentChapter(),
                leadId, snapshotJson);
        } else {
            slot = TheaterSaveSlot.create(room, slotNumber, autoLabel, previewText,
                state.getCurrentAct().getNumber(), state.getCurrentChapter(),
                leadId, snapshotJson, false);
            saveSlotRepository.save(slot);
        }

        log.info("🎭 [SAVE] slot={} | roomId={} | act={}, ch={}",
            slotNumber, roomId, state.getCurrentAct().getNumber(), state.getCurrentChapter());

        return new SaveResult(slotNumber, slot.getSavedAt(), previewText);
    }

    /** Quick Save — 분기 직전 자동 저장용 (서비스 내부 호출) */
    @Transactional
    public void quickSave(Long roomId) {
        try {
            ChatRoom room = chatRoomRepository.findById(roomId)
                .orElseThrow(() -> new NotFoundException("방을 찾을 수 없습니다."));
            TheaterState state = getState(roomId);

            if (state.isEndingReached()) return;

            String snapshotJson = buildSnapshot(state, roomId);
            String previewText = "분기 직전 자동 저장";
            String autoLabel = "⚡ Quick Save";

            TheaterHeroineAffection lead = affectionRepository
                .findByRoomOrderByAffectionDesc(roomId).stream().findFirst().orElse(null);
            Long leadId = lead != null ? lead.getCharacter().getId() : null;

            Optional<TheaterSaveSlot> existing = saveSlotRepository.findByRoom_IdAndSlotNumber(roomId, 0);
            if (existing.isPresent()) {
                existing.get().overwrite(autoLabel, previewText,
                    state.getCurrentAct().getNumber(), state.getCurrentChapter(),
                    leadId, snapshotJson);
            } else {
                saveSlotRepository.save(TheaterSaveSlot.create(
                    room, 0, autoLabel, previewText,
                    state.getCurrentAct().getNumber(), state.getCurrentChapter(),
                    leadId, snapshotJson, true
                ));
            }
            log.debug("🎭 [QUICK-SAVE] roomId={}", roomId);
        } catch (Exception e) {
            log.warn("🎭 [QUICK-SAVE] failed: {}", e.getMessage());
        }
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  3. 로드
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    @Transactional
    public LoadResult load(Long roomId, String username, int slotNumber) {
        getOwnedRoom(roomId, username);

        TheaterSaveSlot slot = saveSlotRepository.findByRoom_IdAndSlotNumber(roomId, slotNumber)
            .orElseThrow(() -> new NotFoundException("세이브 슬롯이 비어있습니다."));

        TheaterState state = getState(roomId);

        try {
            JsonNode root = objectMapper.readTree(slot.getSnapshotJson());
            JsonNode stateNode = root.path("stateSnapshot");

            // 스냅샷 값 추출
            TheaterAct act = parseAct(stateNode.path("currentAct").asText("ACT_1_MEETING"));
            int chapter = stateNode.path("currentChapter").asInt(1);
            int scenesInChapter = stateNode.path("scenesInCurrentChapter").asInt(0);
            int chapterTarget = stateNode.path("chapterTargetScenes").asInt(30);
            long totalScenes = stateNode.path("totalSceneCount").asLong(0);
            Long currentHeroineId = stateNode.path("currentHeroineId").isNull() ? null
                : stateNode.path("currentHeroineId").asLong();
            int batchId = stateNode.path("currentBatchId").asInt(0);
            int charm = stateNode.path("statCharm").asInt(0);
            int wit = stateNode.path("statWit").asInt(0);
            int bold = stateNode.path("statBoldness").asInt(0);
            int intel = stateNode.path("statIntellect").asInt(0);
            int emp = stateNode.path("statEmpathy").asInt(0);
            int stamina = stateNode.path("intermissionStamina").asInt(5);

            // 실제 복원
            state.restoreFromSnapshot(act, chapter, scenesInChapter, chapterTarget,
                totalScenes, currentHeroineId, batchId,
                charm, wit, bold, intel, emp, stamina);

            // 호감도 복원
            JsonNode affNode = root.path("affections");
            if (affNode.isArray()) {
                for (JsonNode a : affNode) {
                    Long charId = a.path("characterId").asLong(0);
                    int affection = a.path("affection").asInt(0);
                    affectionRepository.findByRoom_IdAndCharacter_Id(roomId, charId)
                        .ifPresent(existing -> {
                            existing.applyDelta(affection - existing.getAffection());
                            existing.sealChapterDelta();
                        });
                }
            }
        } catch (JsonProcessingException e) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "세이브 파일 파싱 실패: " + e.getMessage());
        }

        batchCache.purgeRoom(roomId);

        log.info("🎭 [LOAD] slot={} | roomId={}", slotNumber, roomId);
        return new LoadResult(roomId, slotNumber, true, "로드 완료");
    }

    private TheaterAct parseAct(String name) {
        try {
            return TheaterAct.valueOf(name);
        } catch (IllegalArgumentException e) {
            return TheaterAct.ACT_1_MEETING;
        }
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  Helpers
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    private String buildSnapshot(TheaterState state, Long roomId) {
        Map<String, Object> snap = new LinkedHashMap<>();
        Map<String, Object> stateSnap = new LinkedHashMap<>();
        stateSnap.put("currentAct", state.getCurrentAct().name());
        stateSnap.put("currentChapter", state.getCurrentChapter());
        stateSnap.put("scenesInCurrentChapter", state.getScenesInCurrentChapter());
        stateSnap.put("chapterTargetScenes", state.getChapterTargetScenes());
        stateSnap.put("totalSceneCount", state.getTotalSceneCount());
        stateSnap.put("currentHeroineId", state.getCurrentHeroineId());
        stateSnap.put("currentBatchId", state.getCurrentBatchId());
        stateSnap.put("statCharm", state.getStatCharm());
        stateSnap.put("statWit", state.getStatWit());
        stateSnap.put("statBoldness", state.getStatBoldness());
        stateSnap.put("statIntellect", state.getStatIntellect());
        stateSnap.put("statEmpathy", state.getStatEmpathy());
        stateSnap.put("intermissionStamina", state.getIntermissionStamina());
        snap.put("stateSnapshot", stateSnap);

        List<Map<String, Object>> affList = new ArrayList<>();
        for (TheaterHeroineAffection a : affectionRepository.findByRoom_Id(roomId)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("characterId", a.getCharacter().getId());
            m.put("affection", a.getAffection());
            m.put("totalScenes", a.getTotalScenes());
            m.put("confirmedMain", a.isConfirmedMain());
            affList.add(m);
        }
        snap.put("affections", affList);
        snap.put("savedAt", LocalDateTime.now().toString());

        try {
            return objectMapper.writeValueAsString(snap);
        } catch (JsonProcessingException e) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "세이브 직렬화 실패");
        }
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