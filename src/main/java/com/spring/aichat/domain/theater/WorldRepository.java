package com.spring.aichat.domain.theater;

import com.spring.aichat.domain.enums.WorldId;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface WorldRepository extends JpaRepository<World, WorldId> {

    List<World> findByActiveTrueOrderByDisplayOrderAsc();
}