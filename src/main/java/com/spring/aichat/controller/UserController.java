package com.spring.aichat.controller;

import com.spring.aichat.dto.user.UpdateUserRequest;
import com.spring.aichat.dto.user.UserResponse;
import com.spring.aichat.service.user.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/**
 * 유저 API 컨트롤러
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/users")
public class UserController {

    private final UserService userService;

    // TODO: 관리자용 유저 관리 API 추가 예정,
    // TODO: 본인 정보 조회용으로 username이 아닌 userId로 조회 (이게 속도가 더 빠를듯?)

    @GetMapping("/me")
    public UserResponse getMyInfo(Authentication authentication) {
        return userService.getMyInfo(authentication.getName());
    }

    @PatchMapping("/update")
    public void updateMyInfo(@RequestBody UpdateUserRequest request,
                             Authentication authentication) {
        userService.updateMyInfo(request, authentication.getName());
    }
}
