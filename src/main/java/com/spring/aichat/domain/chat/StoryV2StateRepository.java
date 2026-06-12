package com.spring.aichat.domain.chat;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * [D-5 / E-2b] Story V2 서사 상태(나침반) Repository.
 *
 * <p>주 사용처:
 * <ul>
 *   <li>디렉터 prompt [나침반] 빌딩 시 room별 열린 thread 조회.</li>
 *   <li>디렉터 응답의 narrative_threads 델타 반영 후 저장.</li>
 *   <li>스토리 초기화(cascadeResetRoom) 시 thread 리셋.</li>
 * </ul>
 *
 * <p>room_id는 v2 {@link ChatRoom}과 1:1 (unique) — 단건 조회.
 */
public interface StoryV2StateRepository extends JpaRepository<StoryV2State, Long> {

    /** v2 ChatRoom의 서사 상태 (1:1). 없으면 empty → 호출 측에서 lazy-create. */
    Optional<StoryV2State> findByRoomId(Long roomId);

    /** 스토리 초기화/방 삭제 시 정리. */
    void deleteByRoomId(Long roomId);
}