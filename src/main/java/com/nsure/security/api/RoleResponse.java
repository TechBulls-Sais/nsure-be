package com.nsure.security.api;

import java.time.LocalDateTime;
import java.util.List;

public record RoleResponse(
        Long id,
        String name,
        String description,
        boolean disabled,
        // Spec 04 §2: a desk-access role makes its holders staff.
        boolean deskAccess,
        // Spec 04 A-1: may never hold a permission, desk access or an approval limit.
        boolean protectedRole,
        List<String> permissions,
        Long createdBy,
        LocalDateTime createdDate,
        Long lastModifiedBy,
        LocalDateTime lastModifiedDate) {
}
