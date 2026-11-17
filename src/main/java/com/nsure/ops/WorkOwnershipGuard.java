package com.nsure.ops;

import com.nsure.common.exception.ConflictException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.server.PathContainer;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

/**
 * Makes the modules' own actions respect work-queue ownership (NS-SH10-002: concurrent operators cannot unknowingly
 * execute the same task). A POST that acts on an item someone else has taken from the queue is refused with 409
 * naming the owner; they release it or a supervisor assigns it. Work nobody has taken is unaffected, and so is the
 * owner. Service desk cases keep their owner on the case and are checked by the service desk itself.
 */
@Component
public class WorkOwnershipGuard implements HandlerInterceptor, WebMvcConfigurer {

    /** The module action, and the queue workflows whose item (keyed by the {ref} in the path) it works on. */
    private record Rule(PathPattern pattern, List<String> workflows) {
    }

    private static final PathPatternParser PARSER = new PathPatternParser();

    private static final List<Rule> RULES = List.of(
            rule("/underwriting/cases/{ref}/{action:decision|evidence|offer|reopen}", "UNDERWRITING"),
            rule("/underwriting/cases/{ref}/evidence/{id}/resolve", "UNDERWRITING"),
            rule("/servicing/endorsements/{ref}/decision", "ENDORSEMENT"),
            rule("/servicing/benefit-events/{ref}/{action:decision|payment}", "BENEFIT"),
            rule("/care-cash/events/{ref}/{action:decision|payment}", "CARE_CASH"),
            rule("/group-life/events/{ref}/decision", "SCHEME_EVENT"),
            rule("/group-life/quotes/{ref}/{action:progress|revision|activation}", "GROUP_QUOTE"),
            rule("/portfolio/claims/{ref}/{action:checks|assess}", "CLAIM"),
            rule("/portfolio/claims/{ref}/approve", "CLAIM_DECISION"),
            rule("/portfolio/claims/{ref}/reject", "CLAIM", "CLAIM_DECISION"),
            rule("/portfolio/claims/{ref}/{action:pay|confirm-payout|payout-failed}", "CLAIM_PAYOUT"),
            rule("/finance/manual-journals/{ref}/{action:approve|reject}", "MANUAL_JOURNAL"),
            rule("/reinsurance/treaties/{ref}/approve", "TREATY"),
            rule("/communications/templates/{ref}/approve", "TEMPLATE"),
            rule("/ops/portal-requests/{ref}/close", "PORTAL_REQUEST"),
            rule("/audits/{ref}/{action:approve|reject}", "ACCESS_APPROVAL"));

    private static Rule rule(String pattern, String... workflows) {
        return new Rule(PARSER.parse(pattern), List.of(workflows));
    }

    private final ObjectProvider<WorkQueueService> queue;

    public WorkOwnershipGuard(ObjectProvider<WorkQueueService> queue) {
        this.queue = queue;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(this);
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!"POST".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            return true;
        }
        String path = request.getRequestURI().substring(request.getContextPath().length());
        PathContainer container = PathContainer.parsePath(path);
        for (Rule rule : RULES) {
            PathPattern.PathMatchInfo match = rule.pattern().matchAndExtract(container);
            if (match == null) {
                continue;
            }
            String ref = match.getUriVariables().get("ref");
            List<String> keys = rule.workflows().stream().map(w -> w + ":" + ref).toList();
            for (Map.Entry<String, String> owned : queue.getObject().owners(keys).entrySet()) {
                if (owned.getValue() != null && !owned.getValue().equals(auth.getName())) {
                    throw new ConflictException(ref + " is owned by " + owned.getValue() + " in the work queue. Ask"
                            + " them to release it, or a supervisor to assign it to you.");
                }
            }
            return true;
        }
        return true;
    }
}
