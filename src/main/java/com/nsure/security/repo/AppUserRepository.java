package com.nsure.security.repo;

import com.nsure.security.domain.AppUser;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AppUserRepository extends JpaRepository<AppUser, Long> {

    @EntityGraph(attributePaths = {"roles", "roles.permissions"})
    Optional<AppUser> findByUsername(String username);

    boolean existsByUsername(String username);

    @EntityGraph(attributePaths = {"roles"})
    @Query("""
            select user from AppUser user
            where :headOffice = true
               or user.officeId in (
                    select office.id from Office office
                    where office.hierarchy like concat(:hierarchy, '%')
               )
            order by user.username
            """)
    List<AppUser> findVisible(@Param("headOffice") boolean headOffice, @Param("hierarchy") String hierarchy);
}
