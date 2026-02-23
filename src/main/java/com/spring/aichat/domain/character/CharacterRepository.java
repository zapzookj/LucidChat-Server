package com.spring.aichat.domain.character;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CharacterRepository extends JpaRepository<Character, Long> {

    Optional<Character> findByName(String name);

    /** [Phase 4 — Lobby] 이용 가능한 캐릭터 목록 (정렬 순서) */
//    List<Character> findAllByOrderByDisplayOrderAsc();
}