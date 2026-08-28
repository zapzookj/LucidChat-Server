package com.spring.aichat.domain.theater;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface TheaterDirectorNoteRepository extends JpaRepository<TheaterDirectorNote, Long> {

    /**
     * [D-15 · IDOR] 노트를 <b>방 스코프로만</b> 꺼내는 질의.
     *
     * <p>컨트롤러({@code TheaterFinalityController} {@code @PreAuthorize checkRoomOwnership})는
     * <b>방 소유권만</b> 검사하고, 서비스는 {@code findById(noteId)}로 노트를 꺼냈다.
     * 즉 "내 소유 roomId + 남의 노트 id" 조합이면 남의 MANUAL 감독 노트를 수정·삭제할 수 있었다.
     * docs/13 B-13과 동일한 패턴 — 스코프 질의로 교체한다.
     */
    Optional<TheaterDirectorNote> findByIdAndRoom_Id(Long id, Long roomId);

    List<TheaterDirectorNote> findByRoom_IdOrderByCreatedAtAsc(Long roomId);

    List<TheaterDirectorNote> findByRoom_IdAndNoteTypeOrderByCreatedAtAsc(Long roomId, String noteType);

    void deleteByRoom_Id(Long roomId);
}