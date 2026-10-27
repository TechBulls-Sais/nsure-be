package com.nsure.security.api;

import java.time.LocalDateTime;
import java.util.List;

public record RoleResponse(
        Long id,
        String name,
        String description,
        boolean disabled,
        List<String> permissions,
        Long createdBy,
        LocalDateTime createdDate,
        Long lastModifiedBy,
        LocalDateTime lastModifiedDate) {
}
