package com.nsure.security.web;

import java.time.LocalDateTime;
import java.util.List;

public record UserResponse(
        Long id,
        String username,
        boolean enabled,
        Long officeId,
        List<RoleSummary> roles,
        Long createdBy,
        LocalDateTime createdDate,
        Long lastModifiedBy,
        LocalDateTime lastModifiedDate
) {
    public record RoleSummary(Long id, String name) {
    }
}
