package com.spring.aichat.domain.theater;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TheaterDirectorNoteRepository extends JpaRepository<TheaterDirectorNote, Long> {

    List<TheaterDirectorNote> findByRoom_IdOrderByCreatedAtAsc(Long roomId);

    List<TheaterDirectorNote> findByRoom_IdAndNoteTypeOrderByCreatedAtAsc(Long roomId, String noteType);

    void deleteByRoom_Id(Long roomId);
}