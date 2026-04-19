package com.spring.aichat.controller;

import com.spring.aichat.dto.theater.TheaterRequests.CreateDirectorNoteRequest;
import com.spring.aichat.dto.theater.TheaterRequests.SaveSlotRequest;
import com.spring.aichat.dto.theater.TheaterRequests.UpdateDirectorNoteRequest;
import com.spring.aichat.dto.theater.TheaterResponses.*;
import com.spring.aichat.service.theater.TheaterDirectorNoteService;
import com.spring.aichat.service.theater.TheaterEndingService;
import com.spring.aichat.service.theater.TheaterSaveLoadService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * [Phase 5.5-Theater] Theater 엔딩/세이브/노트 통합 엔드포인트
 *
 * === Ending ===
 * POST    /api/v1/theater/rooms/{roomId}/ending
 *
 * === Save / Load ===
 * GET     /api/v1/theater/rooms/{roomId}/saves
 * POST    /api/v1/theater/rooms/{roomId}/saves
 * POST    /api/v1/theater/rooms/{roomId}/saves/{slotNumber}/load
 *
 * === Director Notes ===
 * GET     /api/v1/theater/rooms/{roomId}/notes
 * POST    /api/v1/theater/rooms/{roomId}/notes
 * PATCH   /api/v1/theater/rooms/{roomId}/notes/{noteId}
 * DELETE  /api/v1/theater/rooms/{roomId}/notes/{noteId}
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/theater/rooms/{roomId}")
public class TheaterFinalityController {

    private final TheaterEndingService endingService;
    private final TheaterSaveLoadService saveLoadService;
    private final TheaterDirectorNoteService directorNoteService;

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  엔딩
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    @PostMapping("/ending")
    @PreAuthorize("@authGuard.checkRoomOwnership(#roomId, principal.subject)")
    public TheaterEnding triggerEnding(
        @PathVariable Long roomId,
        Authentication authentication
    ) {
        return endingService.triggerEnding(roomId, authentication.getName());
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  세이브 / 로드
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    @GetMapping("/saves")
    @PreAuthorize("@authGuard.checkRoomOwnership(#roomId, principal.subject)")
    public List<SaveSlotView> listSaves(
        @PathVariable Long roomId,
        Authentication authentication
    ) {
        return saveLoadService.listSlots(roomId, authentication.getName());
    }

    @PostMapping("/saves")
    @PreAuthorize("@authGuard.checkRoomOwnership(#roomId, principal.subject)")
    public SaveResult save(
        @PathVariable Long roomId,
        @RequestBody @Valid SaveSlotRequest request,
        Authentication authentication
    ) {
        return saveLoadService.save(
            roomId, authentication.getName(), request.slotNumber(), request.label()
        );
    }

    @PostMapping("/saves/{slotNumber}/load")
    @PreAuthorize("@authGuard.checkRoomOwnership(#roomId, principal.subject)")
    public LoadResult load(
        @PathVariable Long roomId,
        @PathVariable int slotNumber,
        Authentication authentication
    ) {
        return saveLoadService.load(roomId, authentication.getName(), slotNumber);
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  감독 노트
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    @GetMapping("/notes")
    @PreAuthorize("@authGuard.checkRoomOwnership(#roomId, principal.subject)")
    public List<DirectorNoteView> listNotes(
        @PathVariable Long roomId,
        Authentication authentication
    ) {
        return directorNoteService.listNotes(roomId, authentication.getName());
    }

    @PostMapping("/notes")
    @PreAuthorize("@authGuard.checkRoomOwnership(#roomId, principal.subject)")
    public DirectorNoteView createNote(
        @PathVariable Long roomId,
        @RequestBody @Valid CreateDirectorNoteRequest request,
        Authentication authentication
    ) {
        return directorNoteService.createNote(roomId, authentication.getName(), request.content());
    }

    @PatchMapping("/notes/{noteId}")
    @PreAuthorize("@authGuard.checkRoomOwnership(#roomId, principal.subject)")
    public DirectorNoteView updateNote(
        @PathVariable Long roomId,
        @PathVariable Long noteId,
        @RequestBody @Valid UpdateDirectorNoteRequest request,
        Authentication authentication
    ) {
        return directorNoteService.updateNote(
            roomId, authentication.getName(), noteId, request.content());
    }

    @DeleteMapping("/notes/{noteId}")
    @PreAuthorize("@authGuard.checkRoomOwnership(#roomId, principal.subject)")
    public ResponseEntity<Void> deleteNote(
        @PathVariable Long roomId,
        @PathVariable Long noteId,
        Authentication authentication
    ) {
        directorNoteService.deleteNote(roomId, authentication.getName(), noteId);
        return ResponseEntity.noContent().build();
    }
}