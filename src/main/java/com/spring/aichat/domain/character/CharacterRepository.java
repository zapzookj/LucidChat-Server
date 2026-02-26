package com.spring.aichat.domain.character;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CharacterRepository extends JpaRepository<Character, Long> {

    Optional<Character> findByName(String name);

    /** [Phase 5] slug 기반 조회 — 시더 업서트 + 에셋 경로 resolve */
    Optional<Character> findBySlug(String slug);
}