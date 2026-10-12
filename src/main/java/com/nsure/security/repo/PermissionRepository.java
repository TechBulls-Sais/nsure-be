package com.nsure.security.repo;

import com.nsure.security.domain.Permission;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PermissionRepository extends JpaRepository<Permission, Long> {

    Optional<Permission> findByCodeIgnoreCase(String code);
}
