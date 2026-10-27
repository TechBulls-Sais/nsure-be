package com.nsure.audit.service;

import java.util.Optional;
import org.springframework.data.domain.AuditorAware;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import com.nsure.security.domain.AppUser;

@Component("auditorAware")
public class AuditorAwareImpl implements AuditorAware<Long> {

    static final long SYSTEM_USER_ID = 1L;

    @Override
    public Optional<Long> getCurrentAuditor() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof AppUser user && user.getId() != null) {
            return Optional.of(user.getId());
        }
        return Optional.of(SYSTEM_USER_ID);
    }
}
