package com.nsure.mipas.capability;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface CapabilityGrantRepository extends JpaRepository<CapabilityGrant, Long> {

    @Query("select g.capability.code from CapabilityGrant g where g.role.name = :roleName order by g.capability.code")
    List<String> codesForRole(String roleName);

    long countByRole_Name(String roleName);
}
