package com.spring.aichat.domain.user;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface UserRepository extends JpaRepository<User, Long> {
    @Modifying
    @Query("update User m set m.energy = case when m.energy < 100 then m.energy + 1 else 100 end")
    int regenAllMembersEnergy();
}
