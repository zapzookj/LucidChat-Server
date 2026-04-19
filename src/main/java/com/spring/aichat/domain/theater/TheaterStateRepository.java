package com.spring.aichat.domain.theater;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface TheaterStateRepository extends JpaRepository<TheaterState, Long> {

    Optional<TheaterState> findByRoom_Id(Long roomId);

    boolean existsByRoom_Id(Long roomId);

    void deleteByRoom_Id(Long roomId);
}