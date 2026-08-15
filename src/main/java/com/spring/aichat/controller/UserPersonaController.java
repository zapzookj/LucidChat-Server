package com.spring.aichat.controller;

import com.spring.aichat.domain.user.UserPersona;
import com.spring.aichat.service.persona.UserPersonaService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;

/**
 * [블록 B 페르소나] 활성 프로필 + 저장 카드(세이브 슬롯) API.
 *
 * GET    /api/v1/personas/profile      — 활성 프로필 조회 (없으면 부트스트랩)
 * PATCH  /api/v1/personas/profile      — 프로필 편집 (이름/나이/성별/소개/렌즈 5축)
 * GET    /api/v1/personas              — 카드 목록 + 슬롯 정보 {cards, slotLimit, slotUsed}
 * POST   /api/v1/personas              — 현재 프로필을 카드로 저장 (무료 3/구독 10)
 * POST   /api/v1/personas/{id}/load    — 카드를 프로필로 로드 (카드 보존)
 * DELETE /api/v1/personas/{id}         — 카드 삭제
 *
 * 방 스냅샷은 생성 시점 프로필 복사(소급 불변) — 진행 중 방은 프로필 수정의 영향을 받지 않는다.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/personas")
public class UserPersonaController {

    private final UserPersonaService personaService;

    public record PersonaView(Long personaId, String name, Integer age, String gender, String personaText,
                              int allure, int friendliness, int trust, int charisma, int mystique,
                              LocalDateTime updatedAt) {
        static PersonaView of(UserPersona p) {
            return new PersonaView(p.getId(), p.getName(), p.getAge(), p.getGenderOrDefault().name(),
                p.getPersonaText(), p.getLensAllure(), p.getLensFriendliness(), p.getLensTrust(),
                p.getLensCharisma(), p.getLensMystique(), p.getUpdatedAt());
        }
    }

    public record CardListResponse(List<PersonaView> cards, int slotLimit, int slotUsed) {}

    @GetMapping("/profile")
    public PersonaView profile(Authentication authentication) {
        return PersonaView.of(personaService.getOrCreateProfile(authentication.getName()));
    }

    @PatchMapping("/profile")
    public PersonaView updateProfile(@RequestBody UserPersonaService.ProfilePayload payload,
                                     Authentication authentication) {
        return PersonaView.of(personaService.updateProfile(authentication.getName(), payload));
    }

    @GetMapping
    public CardListResponse list(Authentication authentication) {
        UserPersonaService.CardList result = personaService.listCards(authentication.getName());
        return new CardListResponse(
            result.cards().stream().map(PersonaView::of).toList(),
            result.slotLimit(), result.slotUsed());
    }

    @PostMapping
    public PersonaView saveCard(Authentication authentication) {
        return PersonaView.of(personaService.saveCard(authentication.getName()));
    }

    @PostMapping("/{personaId}/load")
    public PersonaView loadCard(@PathVariable Long personaId, Authentication authentication) {
        return PersonaView.of(personaService.loadCard(authentication.getName(), personaId));
    }

    @DeleteMapping("/{personaId}")
    public ResponseEntity<Void> delete(@PathVariable Long personaId, Authentication authentication) {
        personaService.deleteCard(authentication.getName(), personaId);
        return ResponseEntity.noContent().build();
    }
}
