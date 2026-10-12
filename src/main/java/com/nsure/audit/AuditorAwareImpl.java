package com.nsure.audit;

import com.nsure.config.AuditProperties;
import com.nsure.security.domain.AppUser;
import java.util.Optional;
import org.springframework.data.domain.AuditorAware;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

@Component("auditorAware")
public class AuditorAwareImpl implements AuditorAware<Long> {

    private final AuditProperties properties;

    public AuditorAwareImpl(AuditProperties properties) {
        this.properties = properties;
    }

    @Override
    public Optional<Long> getCurrentAuditor() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof AppUser user && user.getId() != null) {
            return Optional.of(user.getId());
        }
        return Optional.of(properties.getSystemUserId());
    }
}
