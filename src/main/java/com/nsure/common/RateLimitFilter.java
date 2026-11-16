package com.nsure.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nsure.common.exception.ApiError;
import com.nsure.common.exception.ApiErrorWriter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Base64;
import java.util.Deque;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Throttles the endpoints that guess passwords or send messages (NS-API-001: 429 for rate limit). Signing in is
 * limited per client address and username, so one person mistyping does not lock out a whole office; the public
 * sign-up and code-request endpoints are limited per address. Over the limit the answer is 429 with
 * {@code Retry-After}. Counts are kept in memory per installation (one backend per insurer).
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class RateLimitFilter extends OncePerRequestFilter {

    private record Rule(String method, String path, int limit, Duration window, boolean perUser) {
    }

    private final Rule[] rules;
    private final ObjectMapper mapper;
    private final Map<String, Deque<Long>> hits = new ConcurrentHashMap<>();

    public RateLimitFilter(ObjectMapper mapper,
            @Value("${nsure.rate-limit.sign-in-per-15-minutes:20}") int signIn,
            @Value("${nsure.rate-limit.signup-code-per-15-minutes:10}") int signupCode,
            @Value("${nsure.rate-limit.signup-per-15-minutes:20}") int signup) {
        this.mapper = mapper;
        Duration window = Duration.ofMinutes(15);
        this.rules = new Rule[] {
            new Rule("POST", "/auth/session", signIn, window, true),
            new Rule("POST", "/public/signup/otp", signupCode, window, false),
            new Rule("POST", "/public/signup", signup, window, false),
        };
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Rule rule = ruleFor(request);
        if (rule != null) {
            String key = rule.path() + "|" + request.getRemoteAddr() + (rule.perUser() ? "|" + username(request) : "");
            long retryAfter = take(key, rule);
            if (retryAfter > 0) {
                response.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(retryAfter));
                ApiErrorWriter.write(response, mapper, 429, ApiError.RATE_LIMITED,
                        "Too many attempts. Please wait " + Math.max(1, (retryAfter + 59) / 60)
                                + " minute(s) and try again.");
                return;
            }
        }
        chain.doFilter(request, response);
    }

    private Rule ruleFor(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        for (Rule rule : rules) {
            if (rule.method().equalsIgnoreCase(request.getMethod()) && rule.path().equals(path)) {
                return rule;
            }
        }
        return null;
    }

    /** Seconds until another attempt is allowed, or 0 when this one is within the limit (and counted). */
    private long take(String key, Rule rule) {
        long now = System.currentTimeMillis();
        long windowStart = now - rule.window().toMillis();
        if (hits.size() > 10_000) {
            // Forget addresses that have gone quiet, so the map cannot grow without bound.
            hits.entrySet().removeIf(e -> { synchronized (e.getValue()) { return e.getValue().isEmpty()
                    || e.getValue().peekLast() <= windowStart; } });
        }
        Deque<Long> times = hits.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (times) {
            while (!times.isEmpty() && times.peekFirst() <= windowStart) {
                times.pollFirst();
            }
            if (times.size() >= rule.limit()) {
                return Math.max(1, (times.peekFirst() + rule.window().toMillis() - now) / 1000);
            }
            times.addLast(now);
            return 0;
        }
    }

    /** The username in a Basic header, lower-cased; empty when there is none. Never the password. */
    private static String username(HttpServletRequest request) {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.regionMatches(true, 0, "Basic ", 0, 6)) {
            return "";
        }
        try {
            String decoded = new String(Base64.getDecoder().decode(header.substring(6).trim()), StandardCharsets.UTF_8);
            int colon = decoded.indexOf(':');
            return (colon < 0 ? decoded : decoded.substring(0, colon)).trim().toLowerCase(Locale.ROOT);
        } catch (IllegalArgumentException ex) {
            return "";
        }
    }
}
