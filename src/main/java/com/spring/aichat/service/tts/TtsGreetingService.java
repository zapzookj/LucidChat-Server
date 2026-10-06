package com.spring.aichat.service.tts;

import com.spring.aichat.domain.character.CharacterRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

/** Public access can only resolve a current official greeting, never a personal object key. */
@Service @RequiredArgsConstructor
public class TtsGreetingService {
    private final CharacterRepository characters;
    private final TtsSourceResolver sources;
    private final TtsAudioStorage storage;
    @Transactional(readOnly = true)
    public String key(String slug) {
        var c = characters.findBySlug(slug).orElseThrow(TtsSourceResolver::notFound);
        String voice = sources.officialVoice(c);
        if (voice == null || c.getFirstGreeting() == null || c.getFirstGreeting().isBlank()) throw TtsSourceResolver.notFound();
        return "greetings/" + c.getSlug() + "/" + TtsText.hash(c.getFirstGreeting() + "|" + voice) + ".mp3";
    }
    public boolean available(String slug) {
        try { return storage.exists(key(slug)); } catch (Exception e) { return false; }
    }
    public byte[] audio(String slug) {
        String key = key(slug);
        if (!storage.exists(key)) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "첫인사 음성이 아직 준비되지 않았습니다.");
        return storage.get(key);
    }
}
