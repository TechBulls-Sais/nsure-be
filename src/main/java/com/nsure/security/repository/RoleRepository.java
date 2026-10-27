package com.nsure.security.repository;

import com.nsure.security.domain.Role;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RoleRepository extends JpaRepository<Role, Long> {

    List<Role> findAllByOrderByNameAsc();

    Optional<Role> findByName(String name);
}
