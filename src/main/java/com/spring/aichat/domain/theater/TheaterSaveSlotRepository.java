package com.spring.aichat.domain.theater;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface TheaterSaveSlotRepository extends JpaRepository<TheaterSaveSlot, Long> {

    Optional<TheaterSaveSlot> findByRoom_IdAndSlotNumber(Long roomId, int slotNumber);

    List<TheaterSaveSlot> findByRoom_IdOrderBySlotNumberAsc(Long roomId);

    /** Quick Save 슬롯 (slotNumber=0) 조회 */
    default Optional<TheaterSaveSlot> findQuickSave(Long roomId) {
        return findByRoom_IdAndSlotNumber(roomId, 0);
    }

    void deleteByRoom_Id(Long roomId);

    void deleteByRoom_IdAndSlotNumber(Long roomId, int slotNumber);
}