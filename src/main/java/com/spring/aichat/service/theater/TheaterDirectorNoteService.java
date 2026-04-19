package com.spring.aichat.service.theater;

import com.spring.aichat.domain.chat.ChatRoom;
import com.spring.aichat.domain.chat.ChatRoomRepository;
import com.spring.aichat.domain.theater.TheaterDirectorNote;
import com.spring.aichat.domain.theater.TheaterDirectorNoteRepository;
import com.spring.aichat.dto.theater.TheaterResponses.DirectorNoteView;
import com.spring.aichat.exception.BusinessException;
import com.spring.aichat.exception.ErrorCode;
import com.spring.aichat.exception.NotFoundException;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * [Phase 5.5-Theater] 감독 노트 서비스
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TheaterDirectorNoteService {

    private final ChatRoomRepository chatRoomRepository;
    private final TheaterDirectorNoteRepository directorNoteRepository;

    public List<DirectorNoteView> listNotes(Long roomId, String username) {
        ChatRoom room = getOwnedRoom(roomId, username);
        return directorNoteRepository.findByRoom_IdOrderByCreatedAtAsc(roomId).stream()
            .map(this::toView).toList();
    }

    @Transactional
    public DirectorNoteView createNote(Long roomId, String username, String content) {
        ChatRoom room = getOwnedRoom(roomId, username);
        TheaterDirectorNote note = TheaterDirectorNote.manual(room, content, null, null);
        directorNoteRepository.save(note);
        log.info("🎭 [NOTE] created | roomId={}", roomId);
        return toView(note);
    }

    @Transactional
    public DirectorNoteView updateNote(Long roomId, String username, Long noteId, String content) {
        getOwnedRoom(roomId, username);
        TheaterDirectorNote note = directorNoteRepository.findById(noteId)
            .orElseThrow(() -> new NotFoundException("노트를 찾을 수 없습니다."));
        if (!"MANUAL".equals(note.getNoteType())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "자동 생성된 노트는 수정할 수 없습니다.");
        }
        note.updateContent(content);
        return toView(note);
    }

    @Transactional
    public void deleteNote(Long roomId, String username, Long noteId) {
        getOwnedRoom(roomId, username);
        TheaterDirectorNote note = directorNoteRepository.findById(noteId)
            .orElseThrow(() -> new NotFoundException("노트를 찾을 수 없습니다."));
        if (!"MANUAL".equals(note.getNoteType())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "자동 생성된 노트는 삭제할 수 없습니다.");
        }
        directorNoteRepository.delete(note);
    }

    private DirectorNoteView toView(TheaterDirectorNote n) {
        return new DirectorNoteView(
            n.getId(), n.getNoteType(), n.getContent(),
            n.getActNumber(), n.getChapterNumber(),
            n.getRelatedHeroineId(), null,
            n.getRelatedIllustrationUrl(), n.getCreatedAt()
        );
    }

    private ChatRoom getOwnedRoom(Long roomId, String username) {
        ChatRoom room = chatRoomRepository.findById(roomId)
            .orElseThrow(() -> new NotFoundException("방을 찾을 수 없습니다."));
        if (!room.getUser().getUsername().equals(username)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "접근 권한이 없습니다.");
        }
        return room;
    }
}