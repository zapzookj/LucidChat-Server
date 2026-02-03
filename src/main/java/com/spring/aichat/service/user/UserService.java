package com.spring.aichat.service.user;

import com.spring.aichat.domain.user.User;
import com.spring.aichat.domain.user.UserRepository;
import com.spring.aichat.dto.user.UpdateUserRequest;
import com.spring.aichat.dto.user.UserResponse;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;

    public UserResponse getMyInfo(String username) {
        User currentUser = userRepository.findByUsername(username)
            .orElseThrow(() -> new RuntimeException("User not found"));

        return new UserResponse(
            currentUser.getId(),
            currentUser.getUsername(),
            currentUser.getNickname(),
            currentUser.getEmail(),
            currentUser.getProfileDescription()
        );
    }

    @Transactional
    public void updateMyInfo(UpdateUserRequest request, String username) {
        User currentUser = userRepository.findByUsername(username)
            .orElseThrow(() -> new RuntimeException("User not found"));

        if (request.nickname() != null) {
            currentUser.updateNickName(request.nickname());
        }
        if (request.profileDescription() != null) {
            currentUser.updateProfileDescription(request.profileDescription());
        }

        userRepository.save(currentUser);
    }
}
