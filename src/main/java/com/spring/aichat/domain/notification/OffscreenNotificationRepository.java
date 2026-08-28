package com.spring.aichat.domain.notification;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * [V2 Story] 오프스크린 캐릭터 알림 Repository.
 *
 * <p>주 사용처:
 * - 디렉터 prompt 빌딩 — 활성(미응답) 알림을 [8] CUMULATIVE MEMORY 또는
 *   별도 [9-misc] 섹션에 인젝션 ("○○로부터 받은 알림에 아직 답하지 않음")
 * - 알림 토스트 노출 — topic_concluded=true 직후 미확인 알림 조회
 * - 발신 가드 — 같은 캐릭터 쿨다운 체크 (24h)
 * - 만료 처리 스케줄러 — 미응답 알림 자동 폐기
 */
public interface OffscreenNotificationRepository extends JpaRepository<OffscreenNotification, Long> {

    /**
     * [docs/13 B-13 · docs/19 §F D-31] 방 스코프 단건 조회.
     *
     * <p>컨트롤러의 {@code @PreAuthorize}는 <b>방 소유권만</b> 검사하므로, 알림 조회를
     * {@code findById}로 하면 '내 방 id + 남의 알림 id' 조합으로 타 유저 알림을
     * 읽음 처리할 수 있다(IDOR). 소유권 검사와 조회의 스코프를 일치시킨다.
     */
    Optional<OffscreenNotification> findByIdAndChatRoom_Id(Long id, Long chatRoomId);

    /**
     * 방의 활성 알림 — 미응답 + 미만료. 디렉터 prompt 인젝션용.
     */
    List<OffscreenNotification>
    findByChatRoom_IdAndRespondedAtIsNullAndExpiresAtAfterOrderBySentAtAsc(
        Long chatRoomId, LocalDateTime now);

    /**
     * 방의 미확인 알림 — 토스트 UI 노출용.
     */
    List<OffscreenNotification>
    findByChatRoom_IdAndReadAtIsNullOrderBySentAtDesc(Long chatRoomId);

    /**
     * 같은 캐릭터의 가장 최근 알림 — 쿨다운(24h) 가드용.
     */
    Optional<OffscreenNotification>
    findTopByChatRoom_IdAndFromCharacterIdOrderBySentAtDesc(Long chatRoomId, Long fromCharacterId);

    /**
     * 만료 시한 도달한 미응답 알림 일괄 조회 — 스케줄러가 페널티 적용 후 폐기.
     */
    List<OffscreenNotification>
    findByExpiresAtBeforeAndRespondedAtIsNull(LocalDateTime threshold);

    /**
     * 미확인 알림 개수 — UI 배지 표시용.
     */
    long countByChatRoom_IdAndReadAtIsNull(Long chatRoomId);

    /** 스토리 초기화 시 일괄 삭제 */
    void deleteByChatRoom_Id(Long chatRoomId);
}