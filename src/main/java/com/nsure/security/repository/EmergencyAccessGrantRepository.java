package com.nsure.security.repository;

import com.nsure.security.domain.EmergencyAccessGrant;
import java.util.List;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EmergencyAccessGrantRepository extends JpaRepository<EmergencyAccessGrant, Long> {

    @EntityGraph(attributePaths = "user")
    List<EmergencyAccessGrant> findTop200ByOrderByGrantedAtDescIdDesc();

    List<EmergencyAccessGrant> findByUserIdAndPermissionCodeAndRevokedAtIsNull(Long userId, String permissionCode);
}
