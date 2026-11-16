package com.nsure.security.repository;

import com.nsure.security.domain.AppUser;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AppUserRepository extends JpaRepository<AppUser, Long> {

    @EntityGraph(attributePaths = {
            "roles",
            "roles.permissions",
            "emergencyGrants"
    })
    Optional<AppUser> findByUsername(String username);
}
