package com.nsure.portal;

import com.fasterxml.jackson.databind.JsonNode;
import com.nsure.portfolio.ClaimWorkflow;
import com.nsure.portfolio.CustomerAccount;
import com.nsure.portfolio.InsuranceClaim;
import com.nsure.portfolio.InsurancePolicy;
import com.nsure.portfolio.InsuranceProduct;
import com.nsure.portfolio.PolicyWorkflow;
import com.nsure.portfolio.PortfolioMapper;
import com.nsure.portfolio.PortfolioService;
import com.nsure.portfolio.PortfolioViews;
import com.nsure.security.domain.AppUser;
import com.nsure.security.exception.NoAuthorizationException;
import com.nsure.security.service.PlatformSecurityContext;
import com.nsure.web.ResourceNotFoundException;
import jakarta.persistence.EntityManager;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The customer's own portal, as on the demo server: buy cover (reserved as Pending Payment), pay for it,
 * manage beneficiaries, and lodge and follow claims on their own policies.
 */
@Service
public class PortalService {

    private static final DateTimeFormatter DISPLAY = DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.ENGLISH);

    private final EntityManager em;
    private final PlatformSecurityContext security;
    private final PolicyWorkflow policyWorkflow;
    private final ClaimWorkflow claimWorkflow;
    private final PortfolioMapper mapper;

    public PortalService(EntityManager em, PlatformSecurityContext security, PolicyWorkflow policyWorkflow,
            ClaimWorkflow claimWorkflow, PortfolioMapper mapper) {
        this.em = em;
        this.security = security;
        this.policyWorkflow = policyWorkflow;
        this.claimWorkflow = claimWorkflow;
        this.mapper = mapper;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> profile() {
        AppUser user = requirePortalUser();
        CustomerAccount customer = customerFor(user);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("username", user.getUsername());
        body.put("customerRef", customer.getRef());
        body.put("name", customer.getName());
        body.put("email", customer.getEmail());
        body.put("phone", customer.getPhone());
        body.put("status", customer.getStatus());
        body.put("kycVerified", "Verified".equals(customer.getStatus()));
        body.put("kycRejectionReason", blank(customer.getKycRejectionReason()));
        body.put("pep", customer.isPep());
        body.put("eddStatus", blank(customer.getEddStatus()));
        body.put("eddRejectionReason", blank(customer.getEddRejectionReason()));
        body.put("eddBlocked", customer.isPep() && !"VERIFIED".equals(customer.getEddStatus()));
        body.put("policies", customer.getPolicyCount());
        return body;
    }

    @Transactional(readOnly = true)
    public List<PortfolioViews.ProductView> products() {
        requirePortalUser();
        return em.createQuery(
                        "select e from InsuranceProduct e order by e.displayOrder asc, e.id asc",
                        InsuranceProduct.class)
                .getResultList()
                .stream()
                .map(product -> new PortfolioViews.ProductView(
                        product.getName(), product.getIcon(), product.getColor(), product.getDescription(),
                        product.getCover(), product.getPremium(), product.getLiveCount()))
                .toList();
    }

    /** Every policy of the customer: awaiting payment, active, lapsed, expired or cancelled. */
    @Transactional(readOnly = true)
    public List<PortfolioViews.CoverView> myPolicies() {
        CustomerAccount customer = customerFor(requirePortalUser());
        return mapper.covers(em.createQuery(
                        "select e from InsurancePolicy e where e.customerName = :name order by e.displayOrder asc, e.id desc",
                        InsurancePolicy.class)
                .setParameter("name", customer.getName())
                .getResultList());
    }

    @Transactional(readOnly = true)
    public List<PortfolioViews.ClaimView> myClaims() {
        CustomerAccount customer = customerFor(requirePortalUser());
        return em.createQuery(
                        "select c from InsuranceClaim c where c.customerName = :name order by c.id desc", InsuranceClaim.class)
                .setParameter("name", customer.getName())
                .getResultStream()
                .map(mapper::claim)
                .toList();
    }

    /**
     * Step 1 of checkout (demo server create_pending_policy): the policy is reserved as Pending Payment and must be
     * paid within the payment window, or it expires. It confers no cover until paid.
     */
    @Transactional
    public Map<String, Object> purchase(JsonNode body) {
        AppUser user = requirePortalUser();
        CustomerAccount customer = customerFor(user);
        if (!"Verified".equals(customer.getStatus())) {
            throw new IllegalArgumentException("KYC must be verified before you can buy a policy.");
        }
        PortfolioService.requireEddClear(customer);
        String productName = requiredText(body, "product");
        InsuranceProduct product = em.createQuery(
                        "select e from InsuranceProduct e where e.name = :name", InsuranceProduct.class)
                .setParameter("name", productName)
                .getResultList()
                .stream()
                .findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("Product " + productName + " not found"));

        // One open purchase per product, never a duplicate.
        policyWorkflow.expireUnpaid();
        Optional<String> open = em.createQuery(
                        "select p.ref from InsurancePolicy p where p.customerName = :name and p.product = :product"
                                + " and p.status = :status", String.class)
                .setParameter("name", customer.getName())
                .setParameter("product", product.getName())
                .setParameter("status", PolicyWorkflow.PENDING_PAYMENT)
                .getResultStream()
                .findFirst();
        if (open.isPresent()) {
            throw new IllegalArgumentException("You already have " + product.getName() + " awaiting payment ("
                    + open.get() + "). Pay it from Your policies.");
        }

        InsurancePolicy policy = new InsurancePolicy();
        policy.setRef(nextRef("POL-", 5, InsurancePolicy.class));
        policy.setCustomerName(customer.getName());
        policy.setProduct(product.getName());
        policy.setAmount(moneyDigits(product.getCover()));
        policy.setPremium(moneyDigits(product.getPremium()));
        policy.setRecordDate(LocalDate.now().format(DISPLAY));
        policy.setDisplayOrder(0);
        policyWorkflow.openPending(policy);
        em.persist(policy);
        policyWorkflow.logOpened(policy, user.getUsername());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("policyId", policy.getRef());
        result.put("product", product.getName());
        result.put("amount", policy.getAmount());
        result.put("premium", policy.getPremium());
        result.put("status", policy.getStatus());
        result.put("paymentDueBy", policy.getPaymentDueBy().withNano(0).toString());
        return result;
    }

    /** Step 2 of checkout: pay the first premium (or the next one of an active policy). */
    @Transactional(noRollbackFor = IllegalArgumentException.class)
    public PortfolioViews.CoverView pay(String ref, JsonNode body) {
        AppUser user = requirePortalUser();
        CustomerAccount customer = customerFor(user);
        InsurancePolicy policy = ownPolicy(customer, ref);
        if (!"Verified".equals(customer.getStatus())) {
            throw new IllegalArgumentException("Your identity must be verified before you can pay for cover.");
        }
        PortfolioService.requireEddClear(customer);
        return mapper.cover(policyWorkflow.pay(policy.getRef(), requiredText(body, "method"), user.getUsername()));
    }

    @Transactional
    public PortfolioViews.CoverView addBeneficiary(String ref, JsonNode body) {
        AppUser user = requirePortalUser();
        InsurancePolicy policy = ownPolicy(customerFor(user), ref);
        policyWorkflow.addBeneficiary(policy.getRef(), PortfolioService.beneficiaryInput(body), user.getUsername());
        return mapper.cover(policy);
    }

    @Transactional
    public PortfolioViews.CoverView removeBeneficiary(String ref, Long id) {
        AppUser user = requirePortalUser();
        InsurancePolicy policy = ownPolicy(customerFor(user), ref);
        policyWorkflow.removeBeneficiary(policy.getRef(), id, user.getUsername());
        return mapper.cover(policy);
    }

    /** A claim lodged by the policyholder on their own policy (demo server submit_claim). */
    @Transactional
    public PortfolioViews.ClaimView lodgeClaim(JsonNode body) {
        AppUser user = requirePortalUser();
        InsurancePolicy policy = ownPolicy(customerFor(user), requiredText(body, "policy"));
        return mapper.claim(claimWorkflow.lodge(PortfolioService.lodgement(policy.getRef(), body), user.getUsername()));
    }

    private InsurancePolicy ownPolicy(CustomerAccount customer, String ref) {
        InsurancePolicy policy = policyWorkflow.policy(ref);
        if (!policy.getCustomerName().equals(customer.getName())) {
            // Not theirs: answer as if it did not exist rather than confirm someone else's policy number.
            throw new ResourceNotFoundException("Policy " + ref + " not found");
        }
        return policy;
    }

    private AppUser requirePortalUser() {
        AppUser user = security.authenticatedUser();
        if (!user.isCustomerPortal()) {
            throw new NoAuthorizationException("Only a customer portal account can use these endpoints.");
        }
        if (user.getCustomerRef() == null || user.getCustomerRef().isBlank()) {
            throw new NoAuthorizationException("This portal account is not linked to a customer record.");
        }
        return user;
    }

    private CustomerAccount customerFor(AppUser user) {
        List<CustomerAccount> rows = em.createQuery(
                        "select e from CustomerAccount e where e.ref = :ref", CustomerAccount.class)
                .setParameter("ref", user.getCustomerRef())
                .getResultList();
        if (rows.isEmpty()) {
            throw new ResourceNotFoundException("Customer " + user.getCustomerRef() + " not found");
        }
        return rows.get(0);
    }

    private <T> String nextRef(String prefix, int width, Class<T> type) {
        int max = 0;
        for (String ref : em.createQuery("select e.ref from " + type.getSimpleName() + " e", String.class).getResultList()) {
            max = Math.max(max, Integer.parseInt(ref.substring(prefix.length())));
        }
        return prefix + String.format("%0" + width + "d", max + 1);
    }

    private static int moneyDigits(String value) {
        String digits = value == null ? "" : value.replaceAll("[^0-9]", "");
        if (digits.isBlank()) {
            return 0;
        }
        return Integer.parseInt(digits);
    }

    private static String requiredText(JsonNode body, String field) {
        JsonNode node = body.get(field);
        if (node == null || !node.isTextual() || node.asText().isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return node.asText().trim();
    }

    private static String blank(String value) {
        return value == null ? "" : value;
    }
}
