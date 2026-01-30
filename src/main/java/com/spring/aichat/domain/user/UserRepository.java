package com.spring.aichat.domain.user;

import com.spring.aichat.domain.enums.AuthProvider;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {

    boolean existsByUsername(String username);
    boolean existsByEmail(String email);

    Optional<User> findByUsername(String username);
    Optional<User> findByEmail(String email);

    Optional<User> findByProviderAndProviderId(AuthProvider provider, String providerId);

    @Modifying
    @Query("update User m set m.energy = case when m.energy < 100 then m.energy + 1 else 100 end")
    int regenAllMembersEnergy();
}
