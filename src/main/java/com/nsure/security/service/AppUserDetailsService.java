package com.nsure.security.service;

import com.nsure.security.domain.AppUser;
import com.nsure.security.repository.AppUserRepository;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AppUserDetailsService implements UserDetailsService {

    private final AppUserRepository users;

    public AppUserDetailsService(AppUserRepository users) {
        this.users = users;
    }

    @Override
    @Transactional(readOnly = true)
    public UserDetails loadUserByUsername(String username) {
        AppUser user = users.findByUsername(username)
                .orElseThrow(() -> new UsernameNotFoundException(username));
        // Resolve both role collections before the session closes. Hibernate defers the
        // second eager collection until it is touched, and Spring Security reads
        // authorities only after this method returns.
        user.getAuthorities();
        return user;
    }
}
