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

    /**
     * [E-6.5] 정렬 방향을 파라미터로 열었다. 종전엔 ASC 고정이라 어드민 화면이 page 0을 요청하는 순간
     * <b>'가장 오래된 100건'</b>만 볼 수 있었다 — CS 문의는 거의 항상 최근 대화가 대상인데 그게 안 보였고,
     * 잘렸다는 사실조차 화면에 없었다. 기본값은 asc로 두어 기존 호출자 동작을 보존한다.
     *
     * <p>정렬 키 {@code createdAt}은 Mongo 문서의 실제 필드다 — {@code ChatLogDocument}가
     * {@code @Field("createdAt")}로 명시하고 {@code idx_room_created} 복합 인덱스가 그 필드를 탄다.
     * (Mongo는 필드명이 틀려도 예외 없이 '정렬 없음'으로 조용히 통과하므로 실측이 필요했다.)
     */
    @GetMapping("/rooms/{roomId}/logs")
    public Page<AdminChatLogResponse> roomLogs(@PathVariable Long roomId,
                                               @RequestParam(defaultValue = "0") int page,
                                               @RequestParam(defaultValue = "50") int size,
                                               @RequestParam(defaultValue = "asc") String sort) {
        Sort.Direction direction = "desc".equalsIgnoreCase(sort) ? Sort.Direction.DESC : Sort.Direction.ASC;
        return adminChatLogService.roomLogs(roomId,
            PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 200), Sort.by(direction, "createdAt")));
    }
}
