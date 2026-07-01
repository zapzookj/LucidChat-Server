package com.spring.aichat.controller.admin;

import com.spring.aichat.dto.admin.CharacterAdminResponse;
import com.spring.aichat.dto.admin.CharacterVisibilityRequest;
import com.spring.aichat.service.admin.AdminCharacterService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** 관리자 캐릭터 콘텐츠 관리. */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/admin/characters")
public class AdminCharacterController {

    private final AdminCharacterService adminCharacterService;

    @GetMapping
    public List<CharacterAdminResponse> list() {
        return adminCharacterService.list();
    }

    @PostMapping("/{id}/visibility")
    public CharacterAdminResponse visibility(@PathVariable Long id,
                                             @RequestBody CharacterVisibilityRequest req,
                                             Authentication auth) {
        return adminCharacterService.updateVisibility(auth.getName(), id, req);
    }
}
