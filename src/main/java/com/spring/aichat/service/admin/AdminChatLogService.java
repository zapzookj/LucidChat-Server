package com.spring.aichat.service.admin;

import com.spring.aichat.domain.chat.ChatLogMongoRepository;
import com.spring.aichat.domain.chat.ChatRoomRepository;
import com.spring.aichat.dto.admin.AdminChatLogResponse;
import com.spring.aichat.dto.admin.AdminRoomSummary;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * CS 로그 뷰어 (Phase 6) — 문의 대응용. 유저 → 방 목록(RDB) → 방 로그(Mongo) 브리징.
 */
@Service
@RequiredArgsConstructor
public class AdminChatLogService {

    private final ChatRoomRepository chatRoomRepository;
    private final ChatLogMongoRepository chatLogMongoRepository;

    @Transactional(readOnly = true)
    public List<AdminRoomSummary> userRooms(Long userId) {
        return chatRoomRepository.findByUser_IdOrderByIdDesc(userId).stream()
            .map(r -> new AdminRoomSummary(
                r.getId(),
                r.getCharacter() != null ? r.getCharacter().getName() : null,
                r.getChatMode() != null ? r.getChatMode().name() : null,
                chatLogMongoRepository.countByRoomId(r.getId())))
            .toList();
    }

    public Page<AdminChatLogResponse> roomLogs(Long roomId, Pageable pageable) {
        return chatLogMongoRepository.findByRoomId(roomId, pageable).map(AdminChatLogResponse::from);
    }
}
