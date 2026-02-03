package com.spring.aichat.security;

import com.spring.aichat.domain.chat.ChatRoom;
import com.spring.aichat.domain.chat.ChatRoomRepository;
import com.spring.aichat.exception.BusinessException;
import com.spring.aichat.exception.ErrorCode;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class AuthGuard {

    private final ChatRoomRepository chatRoomRepository;

    /**
     * 해당 채팅방의 소유자인지 검증
     * @param roomId 채팅방 ID
     * @param username 현재 로그인한 사용자명 (Principal)
     */
    @Transactional
    public boolean checkRoomOwnership(Long roomId, String username) {
        ChatRoom room = chatRoomRepository.findById(roomId)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "채팅방을 찾을 수 없습니다."));

        return room.getUser().getUsername().equals(username);
    }
}
