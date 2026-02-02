package com.spring.aichat.controller;

import com.spring.aichat.domain.chat.ChatLog;
import com.spring.aichat.domain.chat.ChatLogRepository;
import com.spring.aichat.dto.chat.ChatLogResponse;
import com.spring.aichat.dto.chat.ChatRoomInfoResponse;
import com.spring.aichat.dto.chat.SendChatRequest;
import com.spring.aichat.dto.chat.SendChatResponse;
import com.spring.aichat.service.ChatService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.*;

/**
 * 채팅 API 컨트롤러
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/chat")
public class ChatController {

    private final ChatService chatService;
    private final ChatLogRepository chatLogRepository;

    /**
     * 채팅 전송: 특정 방에 메시지 보내기
     */
    @PostMapping("/rooms/{roomId}/messages")
    public SendChatResponse sendRestful(
        @PathVariable Long roomId,
        @RequestBody @Valid SendChatRequest request
    ) {
        return chatService.sendMessage(roomId, request.message());
    }

    /**
     * 채팅 로그 조회 (페이지네이션)
     */
    @GetMapping("/rooms/{roomId}/logs")
    public Page<ChatLogResponse> getLogs(
        @PathVariable Long roomId,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "50") int size
    ) {
        PageRequest pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));

        return chatLogRepository.findByRoom_Id(roomId, pageable)
            .map(this::toDto);
    }

    @GetMapping("/rooms/{roomId}")
    public ChatRoomInfoResponse getRoomInfo(
        @PathVariable Long roomId
    ) {
        return chatService.getChatRoomInfo(roomId);
    }

    @DeleteMapping("/rooms/{roomId}")
    public void deleteRoom(
        @PathVariable Long roomId
    ) {
        chatService.deleteChatRoom(roomId);
    }

    private ChatLogResponse toDto(ChatLog log) {
        return new ChatLogResponse(
            log.getId(),
            log.getRole(),
            log.getRawContent(),
            log.getCleanContent(),
            log.getEmotionTag(),
            log.getCreatedAt()
        );
    }
}
