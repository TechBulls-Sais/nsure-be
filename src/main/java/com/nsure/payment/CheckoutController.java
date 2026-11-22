package com.nsure.payment;

import com.fasterxml.jackson.databind.JsonNode;
import com.nsure.portfolio.CustomerAccount;
import com.nsure.portfolio.PortfolioService;
import com.nsure.security.domain.AppUser;
import com.nsure.security.exception.NoAuthorizationException;
import com.nsure.security.service.PlatformSecurityContext;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.net.URI;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.HtmlUtils;

/**
 * The payment contract (spec 13 §6, §8): start a checkout, poll its status, and the three settlement triggers —
 * browser return, provider callback and (in {@link PaymentSweep}) the scheduled sweep. The triggers are thin adapters
 * over {@link PaymentSettlementService#settle}; none of them is a verdict.
 */
@RestController
public class CheckoutController {

    private final PaymentSettlementService settlement;
    private final PaymentGateways gateways;
    private final PaymentTransactionRepository transactions;
    private final PortfolioService portfolio;
    private final PlatformSecurityContext security;
    private final EntityManager em;
    private final String uiBaseUrl;
    private final String publicBaseUrl;
    private final org.springframework.transaction.support.TransactionTemplate rawLog;

    public CheckoutController(PaymentSettlementService settlement, PaymentGateways gateways,
            PaymentTransactionRepository transactions,
            PortfolioService portfolio, PlatformSecurityContext security, EntityManager em,
            org.springframework.transaction.PlatformTransactionManager transactionManager,
            @Value("${nsure.payments.ui-base-url:http://127.0.0.1:5180/#}") String uiBaseUrl,
            @Value("${nsure.payments.public-base-url:http://127.0.0.1:5180/api}") String publicBaseUrl) {
        this.settlement = settlement;
        this.gateways = gateways;
        this.transactions = transactions;
        this.portfolio = portfolio;
        this.security = security;
        this.em = em;
        this.rawLog = new org.springframework.transaction.support.TransactionTemplate(transactionManager);
        this.uiBaseUrl = trimSlash(uiBaseUrl);
        this.publicBaseUrl = trimSlash(publicBaseUrl);
    }

    /** A customer starts paying their own policy: {method}. Never takes an amount — the server re-prices (§8.4). */
    @PostMapping("/checkout/{policyRef}/pay")
    public PaymentSettlementService.PaymentInitiation pay(@PathVariable String policyRef, @RequestBody JsonNode body) {
        CustomerAccount account = portfolio.checkoutAllowed(policyRef);
        return settlement.initiate(policyRef, body.path("method").asText(null), account.getLoginUsername());
    }

    /** Polling (§8.3). A customer sees only their own transactions; staff need READ_PAYMENT. */
    @GetMapping("/checkout/transactions/{id}")
    @Transactional(readOnly = true)
    public PaymentStatus status(@PathVariable Long id) {
        PaymentTransaction tx = settlement.transaction(id);
        AppUser user = security.authenticatedUser();
        if (user.isPortalCustomer()) {
            CustomerAccount account = portfolio.customerForLogin();
            if (!account.getRef().equals(tx.getCustomerRef())) {
                throw new NoAuthorizationException("This payment is not yours.");
            }
        } else {
            user.validateHasPermissionTo("READ_PAYMENT");
        }
        return status(tx);
    }

    /** Trigger 1 — the browser comes back from the provider. Redirects, never renders (S-17). */
    @GetMapping("/payment/return")
    public ResponseEntity<Void> paymentReturn(@RequestParam(name = "TransID", required = false) String token) {
        if (token == null || token.isBlank()) {
            return redirect(uiBaseUrl + "/buy/payment?status=expired");
        }
        PaymentTransaction tx;
        try {
            tx = settlement.settle(token, PaymentSettlementService.RETURN);
        } catch (com.nsure.common.exception.NotFoundException ex) {
            return redirect(uiBaseUrl + "/buy/payment?status=expired");
        }
        String route = switch (tx.getStatus()) {
            case PaymentTransaction.SETTLED -> "/buy/confirmation?policy=" + tx.getPolicyRef() + "&tx=" + tx.getId();
            case PaymentTransaction.FAILED -> "/buy/payment?status=failed&tx=" + tx.getId();
            case PaymentTransaction.EXPIRED -> "/buy/payment?status=expired&tx=" + tx.getId();
            default -> "/buy/payment/pending?tx=" + tx.getId();
        };
        return redirect(uiBaseUrl + route);
    }

    /**
     * Trigger 2 — the provider's server-to-server callback. The body is logged raw and only names the transaction to
     * verify (S-19); the answer is always 200 so the provider never retries into duplicate processing (S-18).
     */
    @PostMapping(value = "/payment/callback", consumes = {MediaType.APPLICATION_FORM_URLENCODED_VALUE,
            MediaType.APPLICATION_JSON_VALUE, MediaType.ALL_VALUE})
    public ResponseEntity<String> callback(@RequestParam MultiValueMap<String, String> form,
            @RequestBody(required = false) String raw) {
        String token = first(form, "TransactionToken", "TransID", "token");
        // The raw body is kept in its own transaction, before and whatever the processing (S-19).
        rawLog.executeWithoutResult(status -> em.persist(new PaymentGatewayLog(null, "CALLBACK", "callback.raw",
                (raw == null ? "" : raw) + " " + form)));
        if (token != null) {
            try {
                settlement.settle(token, PaymentSettlementService.CALLBACK);
            } catch (RuntimeException ignored) {
                // The sweep retries; the provider must still get its 200.
            }
        }
        return ResponseEntity.ok("OK");
    }

    // ── staff reconciliation (NFR-11) ─────────────────────────────────────────────────────────────────

    @GetMapping("/payments/transactions")
    @Transactional(readOnly = true)
    public List<PaymentStatus> recent() {
        security.authenticatedUser().validateHasPermissionTo("READ_PAYMENT");
        return transactions.findAllByOrderByIdDesc(PageRequest.of(0, 200)).stream().map(this::status).toList();
    }

    @GetMapping("/payments/transactions/{id}/log")
    @Transactional(readOnly = true)
    public List<LogView> gatewayLog(@PathVariable Long id) {
        security.authenticatedUser().validateHasPermissionTo("READ_PAYMENT");
        return em.createQuery("select l from PaymentGatewayLog l where l.transactionId = :id order by l.id",
                        PaymentGatewayLog.class)
                .setParameter("id", id)
                .getResultStream()
                .map(l -> new LogView(l.getCreatedAt(), l.getProvider(), l.getOperation(), l.getPayload()))
                .toList();
    }

    // ── hosted checkout simulator: stands in for the provider's own page until DPO credentials exist ────────
    // Absent under the prod profile (unless demo payments are on): no simulated page may mark a checkout paid there.

    @GetMapping(value = "/payment/simulator/{token}", produces = MediaType.TEXT_HTML_VALUE)
    @Transactional(readOnly = true)
    public ResponseEntity<String> simulatorPage(@PathVariable String token) {
        SimulatedCheckout checkout = gateways.simulatorsEnabled() ? em.find(SimulatedCheckout.class, token) : null;
        if (checkout == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).contentType(MediaType.TEXT_HTML)
                    .body("<p>This payment session does not exist.</p>");
        }
        String amount = checkout.getCurrency() + " " + checkout.getAmount().toPlainString();
        String action = publicBaseUrl + "/payment/simulator/" + HtmlUtils.htmlEscape(token);
        String html = """
                <!doctype html><html lang="en"><head><meta charset="utf-8">
                <meta name="viewport" content="width=device-width, initial-scale=1">
                <title>Secure checkout (simulator)</title>
                <style>body{font-family:system-ui,sans-serif;background:#f4f5f2;margin:0;padding:24px;color:#1b2a1b}
                main{max-width:420px;margin:40px auto;background:#fff;border-radius:12px;padding:24px;
                box-shadow:0 2px 12px rgba(0,0,0,.08)}h1{font-size:20px;margin:0 0 4px}p{color:#4a5a4a}
                .amount{font-size:28px;font-weight:700;margin:16px 0}button{width:100%%;padding:12px;margin-top:8px;
                border-radius:8px;border:1px solid #2f4f2f;font-size:15px;cursor:pointer}
                .pay{background:#2f4f2f;color:#fff}.decline{background:#fff;color:#7a1f1f;border-color:#7a1f1f}
                small{display:block;margin-top:16px;color:#777}</style></head><body><main>
                <h1>Secure checkout</h1><p>%s</p><div class="amount">%s</div>
                <form method="post" action="%s"><input type="hidden" name="decision" value="pay">
                <button class="pay" type="submit">Pay %s</button></form>
                <form method="post" action="%s"><input type="hidden" name="decision" value="decline">
                <button class="decline" type="submit">Decline</button></form>
                <small>Payment provider simulator. It stands in for DPO hosted checkout until the live connection is
                configured; no card details are collected.</small></main></body></html>
                """.formatted(HtmlUtils.htmlEscape(checkout.getDescription() == null ? "" : checkout.getDescription()),
                HtmlUtils.htmlEscape(amount), action, HtmlUtils.htmlEscape(amount), action);
        return ResponseEntity.ok().contentType(MediaType.TEXT_HTML).body(html);
    }

    /** {decision: pay|decline, amount?}. {@code amount} lets a test charge a different sum (tamper check). */
    @PostMapping("/payment/simulator/{token}")
    @Transactional
    public ResponseEntity<Void> simulatorDecide(@PathVariable String token,
            @RequestParam MultiValueMap<String, String> form) {
        if (!gateways.simulatorsEnabled()) {
            return ResponseEntity.notFound().build();
        }
        SimulatedCheckout checkout = em.find(SimulatedCheckout.class, token);
        if (checkout != null && SimulatedCheckout.PENDING.equals(checkout.getOutcome())) {
            boolean pay = !"decline".equalsIgnoreCase(first(form, "decision"));
            String charged = first(form, "amount");
            checkout.decide(pay ? SimulatedCheckout.PAID : SimulatedCheckout.DECLINED,
                    pay ? (charged == null ? checkout.getAmount() : new BigDecimal(charged)) : null);
        }
        return redirect(publicBaseUrl + "/payment/return?TransID=" + token);
    }

    private PaymentStatus status(PaymentTransaction tx) {
        String message = switch (tx.getStatus()) {
            case PaymentTransaction.SETTLED -> "Payment received. Your policy " + tx.getPolicyRef() + " is active.";
            case PaymentTransaction.FAILED -> "Your payment did not go through. You have not been charged for "
                    + "this attempt; you can try again.";
            case PaymentTransaction.EXPIRED -> "This payment session expired. Please start the payment again.";
            case PaymentTransaction.RETURNED -> "Your bank returned this payment unpaid. The premium is still due.";
            case PaymentTransaction.REVERSED -> "This payment was reversed by your bank. The premium is still due.";
            default -> "We're confirming your payment with the provider.";
        };
        return new PaymentStatus(tx.getId(), tx.getStatus(), tx.isTerminal(),
                PaymentTransaction.SETTLED.equals(tx.getStatus()) ? tx.getPolicyRef() : null, tx.getPolicyRef(),
                tx.getPaymentMethod(), tx.getProvider(), tx.getAmount(), tx.getCurrency(), tx.getPurpose(),
                tx.getPeriodStart() + " to " + tx.getPeriodEnd(), tx.getFailureReason(), message, tx.getCreatedAt(),
                tx.getSettledAt(), tx.getSettledVia(), tx.getVerifyAttempts());
    }

    private static String first(MultiValueMap<String, String> form, String... names) {
        for (String name : names) {
            String value = form.getFirst(name);
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }

    private static ResponseEntity<Void> redirect(String location) {
        return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(location)).build();
    }

    private static String trimSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    /** Spec 13 §8.1 PaymentStatus; {@code customerMessage} is shown verbatim. */
    public record PaymentStatus(Long transactionId, String status, boolean terminal, String policyNumber,
            String policyRef, String method, String provider, BigDecimal amount, String currency, String purpose,
            String period, String failureReason, String customerMessage, LocalDateTime createdAt,
            LocalDateTime settledAt, String settledVia, int verifyAttempts) {
    }

    public record LogView(LocalDateTime at, String provider, String operation, String payload) {
    }
}
