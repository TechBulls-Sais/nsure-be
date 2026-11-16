package com.nsure.common;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * One correlation ID per request (NS-API-001, NS-SH11-005): taken from the caller's {@code X-Correlation-Id} when it
 * is a safe token, otherwise generated. It is echoed in the response header, written to every log line, carried in
 * error bodies, and stamped on the events and audit evidence the request produces.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationId extends OncePerRequestFilter {

    public static final String HEADER = "X-Correlation-Id";
    static final String MDC_KEY = "correlationId";
    private static final Pattern SAFE = Pattern.compile("[A-Za-z0-9._-]{8,64}");

    /** The current request's correlation ID, or null outside a request. */
    public static String current() {
        return MDC.get(MDC_KEY);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String supplied = request.getHeader(HEADER);
        String id = supplied != null && SAFE.matcher(supplied).matches() ? supplied : UUID.randomUUID().toString();
        MDC.put(MDC_KEY, id);
        response.setHeader(HEADER, id);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }
}
