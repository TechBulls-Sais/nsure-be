package com.nsure.security.repository;

import com.nsure.security.domain.Permission;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PermissionRepository extends JpaRepository<Permission, Long> {

    Optional<Permission> findByCodeIgnoreCase(String code);

    List<Permission> findAllByOrderByGroupingAscCodeAsc();
}
