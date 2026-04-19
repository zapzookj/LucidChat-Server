package com.spring.aichat.domain.theater;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TheaterBranchChoiceRepository extends JpaRepository<TheaterBranchChoice, Long> {

    List<TheaterBranchChoice> findByRoom_IdOrderByChosenAtAsc(Long roomId);

    List<TheaterBranchChoice> findByRoom_IdAndActNumberOrderByChosenAtAsc(Long roomId, int actNumber);

    void deleteByRoom_Id(Long roomId);
}