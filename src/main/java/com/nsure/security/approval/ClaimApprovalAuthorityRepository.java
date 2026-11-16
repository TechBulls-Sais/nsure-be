package com.nsure.security.approval;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ClaimApprovalAuthorityRepository extends JpaRepository<ClaimApprovalAuthority, Long> {

    Optional<ClaimApprovalAuthority> findByRoleId(Long roleId);
}
