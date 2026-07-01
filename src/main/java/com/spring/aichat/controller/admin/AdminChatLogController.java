package com.spring.aichat.controller.admin;

import com.spring.aichat.dto.admin.AdminChatLogResponse;
import com.spring.aichat.dto.admin.AdminRoomSummary;
import com.spring.aichat.service.admin.AdminChatLogService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** 관리자 CS 로그 뷰어. 유저→방→로그. */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/admin/chatlogs")
public class AdminChatLogController {

    private final AdminChatLogService adminChatLogService;

    @GetMapping("/users/{userId}/rooms")
    public List<AdminRoomSummary> userRooms(@PathVariable Long userId) {
        return adminChatLogService.userRooms(userId);
    }

    @GetMapping("/rooms/{roomId}/logs")
    public Page<AdminChatLogResponse> roomLogs(@PathVariable Long roomId,
                                               @RequestParam(defaultValue = "0") int page,
                                               @RequestParam(defaultValue = "50") int size) {
        return adminChatLogService.roomLogs(roomId,
            PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 200), Sort.by(Sort.Direction.ASC, "createdAt")));
    }
}
