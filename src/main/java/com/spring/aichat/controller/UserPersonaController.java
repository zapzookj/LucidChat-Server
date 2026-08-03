package com.spring.aichat.controller;

import com.spring.aichat.domain.user.UserPersona;
import com.spring.aichat.service.persona.UserPersonaService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * [2026-08-04 페르소나 풀 패키지] 페르소나 카드 CRUD.
 *
 * GET    /api/v1/personas          — 내 카드 목록
 * POST   /api/v1/personas          — 생성 (상한 10, 스탯은 구독 티어 게이트)
 * PATCH  /api/v1/personas/{id}     — 수정 (진행 중 방은 스냅샷이라 불변)
 * DELETE /api/v1/personas/{id}     — 삭제
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/personas")
public class UserPersonaController {

    private final UserPersonaService personaService;

    public record PersonaView(Long personaId, String name, String gender, String personaText,
                              int charm, int wit, int boldness, int intellect, int empathy) {
        static PersonaView of(UserPersona p) {
            return new PersonaView(p.getId(), p.getName(), p.getGenderOrDefault().name(),
                p.getPersonaText(), p.getStatCharm(), p.getStatWit(), p.getStatBoldness(),
                p.getStatIntellect(), p.getStatEmpathy());
        }
    }

    @GetMapping
    public List<PersonaView> list(Authentication authentication) {
        return personaService.list(authentication.getName()).stream().map(PersonaView::of).toList();
    }

    @PostMapping
    public PersonaView create(@RequestBody UserPersonaService.PersonaPayload payload,
                              Authentication authentication) {
        return PersonaView.of(personaService.create(authentication.getName(), payload));
    }

    @PatchMapping("/{personaId}")
    public PersonaView update(@PathVariable Long personaId,
                              @RequestBody UserPersonaService.PersonaPayload payload,
                              Authentication authentication) {
        return PersonaView.of(personaService.update(authentication.getName(), personaId, payload));
    }

    @DeleteMapping("/{personaId}")
    public ResponseEntity<Void> delete(@PathVariable Long personaId, Authentication authentication) {
        personaService.delete(authentication.getName(), personaId);
        return ResponseEntity.noContent().build();
    }
}
