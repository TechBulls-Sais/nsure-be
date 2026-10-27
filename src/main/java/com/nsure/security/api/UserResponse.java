package com.nsure.security.api;

import java.time.LocalDateTime;
import java.util.List;

public record UserResponse(
        Long id,
        String username,
        boolean enabled,
        Long officeId,
        String officeName,
        String officeHierarchy,
        List<String> roles,
        List<Long> roleIds,
        Long createdBy,
        LocalDateTime createdDate,
        Long lastModifiedBy,
        LocalDateTime lastModifiedDate) {
}
