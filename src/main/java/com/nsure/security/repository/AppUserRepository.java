package com.nsure.security.repository;

import com.nsure.security.domain.AppUser;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AppUserRepository extends JpaRepository<AppUser, Long> {

    @EntityGraph(attributePaths = {
            "roles",
            "roles.permissions",
            "emergencyGrants"
    })
    Optional<AppUser> findByUsername(String username);

    /** How many enabled users other than {@code userId} hold {@code code} through an enabled role. */
    @Query("select count(distinct u.id) from AppUser u join u.roles r join r.permissions p"
            + " where u.enabled = true and r.disabled = false and upper(p.code) = upper(:code) and u.id <> :userId")
    long countOtherHolders(@Param("code") String code, @Param("userId") Long userId);
}
