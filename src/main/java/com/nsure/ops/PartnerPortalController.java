package com.nsure.ops;

import com.fasterxml.jackson.databind.JsonNode;
import com.nsure.portfolio.PortfolioService;
import com.nsure.portfolio.PortfolioViews;
import com.nsure.security.service.PlatformSecurityContext;
import java.util.Map;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Partner workspaces (spec 04): structured submissions and the agent's quote, and staff processing of a submission
 * through its owning module.
 */
@RestController
public class PartnerPortalController {

    private final PartnerSubmissions submissions;
    private final OpsService ops;
    private final PortfolioService portfolio;
    private final PlatformSecurityContext security;

    public PartnerPortalController(PartnerSubmissions submissions, OpsService ops, PortfolioService portfolio,
            PlatformSecurityContext security) {
        this.submissions = submissions;
        this.ops = ops;
        this.portfolio = portfolio;
        this.security = security;
    }

    @PostMapping("/ops/portal/submissions")
    public Map<String, Object> submit(@RequestBody JsonNode body) {
        return submissions.submit(body);
    }

    /** An agent prices cover for a client in its book with the real rating service (no record is created). */
    @PostMapping("/ops/portal/quote")
    public PortfolioViews.QuoteView quote(@RequestBody JsonNode body) {
        var user = security.authenticatedUser();
        user.validateHasPermissionTo("USE_PARTNER_PORTAL");
        Map<String, Object> account = ops.partnerAccount(user);
        if (!"AGENT".equals(account.get("portalType"))) {
            throw new com.nsure.security.exception.NoAuthorizationException("Quotes are for agent and broker logins.");
        }
        submissions.requireClientOf((String) account.get("scopeRef"), body == null || !body.hasNonNull("customerRef")
                ? null : body.get("customerRef").asText());
        return portfolio.quote(body);
    }

    @PostMapping("/ops/portal-requests/{ref}/process")
    public Map<String, Object> process(@PathVariable String ref, @RequestBody(required = false) JsonNode body) {
        return submissions.process(ref, body);
    }
}
