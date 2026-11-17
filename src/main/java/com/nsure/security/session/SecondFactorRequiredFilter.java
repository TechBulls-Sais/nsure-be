package com.nsure.security.session;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nsure.common.exception.ApiError;
import com.nsure.common.exception.ApiErrorWriter;
import com.nsure.security.domain.AppUser;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Staff and partners must pass the second factor before their sign-in is good for anything. Their password (HTTP
 * Basic) is accepted only by {@code POST /auth/session}, which answers with a one-time-code challenge; every other
 * request needs the session token that {@code POST /auth/session/verify} issues. Portal customers are not affected.
 * Runs after Basic authentication, so it sees who the password belongs to.
 */
public class SecondFactorRequiredFilter extends OncePerRequestFilter {

    private final ObjectMapper mapper;

    public SecondFactorRequiredFilter(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof AppUser user
                && signedInByPassword(request)
                && SessionController.requiresSecondFactor(user)
                && !opensSession(request)) {
            SecurityContextHolder.clearContext();
            ApiErrorWriter.write(response, mapper, HttpServletResponse.SC_UNAUTHORIZED, ApiError.UNAUTHENTICATED,
                    "Staff and partner accounts need a one-time code: sign in at /auth/session and use the session"
                            + " token it issues.");
            return;
        }
        chain.doFilter(request, response);
    }

    /** Signed in from a Basic header in this request, not from a verified session token. */
    private static boolean signedInByPassword(HttpServletRequest request) {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        return request.getAttribute(BearerTokenFilter.TOKEN_ATTRIBUTE) == null
                && header != null && header.regionMatches(true, 0, "Basic ", 0, 6);
    }

    private static boolean opensSession(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return "POST".equalsIgnoreCase(request.getMethod()) && "/auth/session".equals(path);
    }
}
