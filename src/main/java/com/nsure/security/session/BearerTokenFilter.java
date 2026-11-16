package com.nsure.security.session;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nsure.security.domain.AppUser;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import com.nsure.common.exception.ApiError;
import com.nsure.common.exception.ApiErrorWriter;
import java.util.Optional;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Signs a request in from {@code Authorization: Bearer <session token>}. A token that is unknown, expired, idle too
 * long or revoked is answered 401 at once, so the browser signs the person out. Requests without a Bearer token pass
 * on unchanged (HTTP Basic still serves API clients).
 */
public class BearerTokenFilter extends OncePerRequestFilter {

    public static final String TOKEN_ATTRIBUTE = BearerTokenFilter.class.getName() + ".token";
    private static final String PREFIX = "Bearer ";

    private final SessionTokenService sessions;
    private final ObjectMapper mapper;

    public BearerTokenFilter(SessionTokenService sessions, ObjectMapper mapper) {
        this.sessions = sessions;
        this.mapper = mapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.regionMatches(true, 0, PREFIX, 0, PREFIX.length())) {
            chain.doFilter(request, response);
            return;
        }
        String token = header.substring(PREFIX.length()).trim();
        Optional<AppUser> user = sessions.authenticate(token);
        if (user.isEmpty()) {
            ApiErrorWriter.write(response, mapper, HttpServletResponse.SC_UNAUTHORIZED, ApiError.UNAUTHENTICATED,
                    "Your session has ended. Please sign in again.");
            return;
        }
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                user.get(), null, user.get().getAuthorities()));
        SecurityContextHolder.setContext(context);
        request.setAttribute(TOKEN_ATTRIBUTE, token);
        try {
            chain.doFilter(request, response);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }
}
