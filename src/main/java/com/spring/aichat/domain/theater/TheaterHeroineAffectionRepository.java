package com.spring.aichat.domain.theater;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface TheaterHeroineAffectionRepository extends JpaRepository<TheaterHeroineAffection, Long> {

    List<TheaterHeroineAffection> findByRoom_Id(Long roomId);

    Optional<TheaterHeroineAffection> findByRoom_IdAndCharacter_Id(Long roomId, Long characterId);

    @Query("SELECT a FROM TheaterHeroineAffection a " +
        "WHERE a.room.id = :roomId ORDER BY a.affection DESC")
    List<TheaterHeroineAffection> findByRoomOrderByAffectionDesc(@Param("roomId") Long roomId);

    void deleteByRoom_Id(Long roomId);
}