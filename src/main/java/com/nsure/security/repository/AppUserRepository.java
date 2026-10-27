package com.nsure.security.repository;

import com.nsure.security.domain.AppUser;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AppUserRepository extends JpaRepository<AppUser, Long> {

    @EntityGraph(attributePaths = {
            "office",
            "roles",
            "roles.permissions",
            "capabilityRoles",
            "capabilityRoles.grants"
    })
    Optional<AppUser> findByUsername(String username);

    @Query("""
            select u from AppUser u
            join u.office o
            where o.hierarchy like concat(:hierarchy, '%')
            order by u.username
            """)
    List<AppUser> findInHierarchy(@Param("hierarchy") String hierarchy);
}
