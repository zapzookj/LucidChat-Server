package com.spring.aichat.domain.theater;

import com.spring.aichat.domain.enums.BranchLevel;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TheaterBranchChoiceRepository extends JpaRepository<TheaterBranchChoice, Long> {

    List<TheaterBranchChoice> findByRoom_IdOrderByChosenAtAsc(Long roomId);

    List<TheaterBranchChoice> findByRoom_IdAndActNumberOrderByChosenAtAsc(Long roomId, int actNumber);

    /**
     * [Polish · LOCATION fix] 특정 방의 특정 (Act, Chapter)에 특정 분기 레벨 선택 기록이 있는지.
     *
     * 사용처:
     *  - TheaterLobbyService.buildRoomInfo: requiresLocationChoice 결정
     *  - TheaterService.requestNextBatch: LOCATION choice 가드 통과 여부
     *
     * 인덱스 idx_theater_branch_act(room_id, act_number, chapter_number)가 존재해서
     * lookup 비용은 무시할 만함.
     */
    boolean existsByRoom_IdAndActNumberAndChapterNumberAndBranchLevel(
        Long roomId, int actNumber, int chapterNumber, BranchLevel branchLevel
    );

    void deleteByRoom_Id(Long roomId);
}