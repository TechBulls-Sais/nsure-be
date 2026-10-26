package com.nsure.portfolio;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.nsure.mipas.capability.Capability;
import com.nsure.mipas.capability.CapabilityService;
import com.nsure.security.domain.AppUser;
import com.nsure.security.exception.NoAuthorizationException;
import com.nsure.security.service.PlatformSecurityContext;
import com.nsure.web.ResourceNotFoundException;
import jakarta.persistence.EntityManager;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PortfolioService {

    private static final DateTimeFormatter DISPLAY = DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.ENGLISH);
    static final String KYC_PENDING = "Pending KYC";
    static final String KYC_VERIFIED = "Verified";
    static final String KYC_FAILED = "KYC failed";

    private final EntityManager em;
    private final PlatformSecurityContext security;
    private final CapabilityService capabilities;
    private final PolicyWorkflow policyWorkflow;
    private final ClaimWorkflow claimWorkflow;
    private final WorkflowHistory history;
    private final PortfolioMapper mapper;

    public PortfolioService(EntityManager em, PlatformSecurityContext security, CapabilityService capabilities,
            PolicyWorkflow policyWorkflow, ClaimWorkflow claimWorkflow, WorkflowHistory history,
            PortfolioMapper mapper) {
        this.em = em;
        this.security = security;
        this.capabilities = capabilities;
        this.policyWorkflow = policyWorkflow;
        this.claimWorkflow = claimWorkflow;
        this.history = history;
        this.mapper = mapper;
    }

    @Transactional(readOnly = true)
    public List<PortfolioViews.CustomerView> customers() {
        requireStaff();
        return list(CustomerAccount.class).stream().map(mapper::customer).toList();
    }

    @Transactional(readOnly = true)
    public List<PortfolioViews.CoverView> applications() {
        requireStaff();
        return list(InsuranceApplication.class).stream().map(mapper::cover).toList();
    }

    @Transactional(readOnly = true)
    public List<PortfolioViews.CoverView> policies() {
        requireStaff();
        return mapper.covers(list(InsurancePolicy.class));
    }

    @Transactional(readOnly = true)
    public List<PortfolioViews.ClaimView> claims() {
        requireStaff();
        return list(InsuranceClaim.class).stream().map(mapper::claim).toList();
    }

    @Transactional(readOnly = true)
    public List<PortfolioViews.ProductView> products() {
        requireStaff();
        return list(InsuranceProduct.class).stream()
                .map(product -> new PortfolioViews.ProductView(
                        product.getName(), product.getIcon(), product.getColor(), product.getDescription(),
                        product.getCover(), product.getPremium(), product.getLiveCount()))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<PortfolioViews.SchemeView> schemes() {
        requireStaff();
        return list(GroupScheme.class).stream()
                .map(scheme -> new PortfolioViews.SchemeView(
                        scheme.getRef(), scheme.getName(), scheme.getSector(), scheme.getInitials(),
                        scheme.getMembers(), scheme.getPremium(), scheme.getRenewal(), scheme.getColor()))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<PortfolioViews.CoverView> receipts() {
        requireStaff();
        return list(PremiumReceipt.class).stream().map(mapper::cover).toList();
    }

    @Transactional(readOnly = true)
    public List<PortfolioViews.ConfigView> productConfigs() {
        requireStaff();
        return list(ProductConfiguration.class).stream().map(this::config).toList();
    }

    @Transactional
    public PortfolioViews.CustomerView createCustomer(JsonNode body) {
        requireStaff();
        CustomerAccount row = new CustomerAccount();
        row.setRef(nextRef("CUS-", 4, CustomerAccount.class));
        row.setName(text(body, "name"));
        row.setEmail(text(body, "email"));
        row.setPhone(text(body, "phone"));
        row.setProduct("");
        row.setPolicyCount(0);
        row.setStatus(KYC_PENDING);
        row.setColor("green");
        row.setDisplayOrder(0);
        // A politically exposed person needs enhanced due diligence before choosing cover (demo server: the
        // PEP → EDD-pending rule lives on the customer, so no path can skip it).
        if (body.path("pep").asBoolean(false)) {
            row.setPep(true);
            row.setEddStatus("PENDING");
        }
        em.persist(row);
        return mapper.customer(row);
    }

    /** Contact details only; verification goes through the KYC review so it is always recorded. */
    @Transactional
    public PortfolioViews.CustomerView updateCustomer(String ref, JsonNode body) {
        requireStaff();
        CustomerAccount row = one(CustomerAccount.class, ref, "Customer");
        if (body.hasNonNull("status") && !body.get("status").asText().trim().equals(row.getStatus())) {
            throw new IllegalArgumentException("Change verification with the KYC review, not by editing the status.");
        }
        if (body.hasNonNull("name")) {
            row.setName(body.get("name").asText().trim());
        }
        if (body.hasNonNull("email")) {
            row.setEmail(body.get("email").asText().trim());
        }
        if (body.hasNonNull("phone")) {
            row.setPhone(body.get("phone").asText().trim());
        }
        return mapper.customer(row);
    }

    // ── customer reviews (demo server admin_review_customer_status) ───────────────────────────────

    /** KYC review: VERIFIED (the default) or FAILED with a reason the customer sees. */
    @Transactional
    public PortfolioViews.CustomerView reviewKyc(String ref, JsonNode body) {
        AppUser reviewer = staffWith(Capability.CUSTOMER_WRITE);
        CustomerAccount row = one(CustomerAccount.class, ref, "Customer");
        String decision = decision(body, "KYC");
        String reason = reviewReason(body, decision);
        String previous = row.getStatus();
        row.setStatus("VERIFIED".equals(decision) ? KYC_VERIFIED : KYC_FAILED);
        row.setKycRejectionReason(reason);
        history.record(WorkflowHistory.KYC_REVIEW, row.getRef(), row.getName(),
                "VERIFIED".equals(decision) ? "Approved" : "Rejected", previous, row.getStatus(), reason,
                reviewer.getUsername());
        return mapper.customer(row);
    }

    /** EDD review of a politically exposed customer, after KYC; rejecting needs a reason the customer sees. */
    @Transactional
    public PortfolioViews.CustomerView reviewEdd(String ref, JsonNode body) {
        AppUser reviewer = staffWith(Capability.CUSTOMER_WRITE);
        CustomerAccount row = one(CustomerAccount.class, ref, "Customer");
        if (!row.isPep()) {
            throw new IllegalArgumentException("EDD review does not apply to this customer");
        }
        if (!KYC_VERIFIED.equals(row.getStatus())) {
            throw new IllegalArgumentException("Verify KYC before recording the EDD review.");
        }
        String decision = decision(body, "EDD");
        String reason = reviewReason(body, decision);
        String previous = row.getEddStatus();
        row.setEddStatus(decision);
        row.setEddRejectionReason(reason);
        history.record(WorkflowHistory.EDD_REVIEW, row.getRef(), row.getName(),
                "VERIFIED".equals(decision) ? "Approved" : "Rejected", previous, decision, reason, reviewer.getUsername());
        return mapper.customer(row);
    }

    private static String decision(JsonNode body, String review) {
        String decision = defaultText(body, "decision", "VERIFIED").toUpperCase(Locale.ROOT);
        if (!Set.of("VERIFIED", "FAILED").contains(decision)) {
            throw new IllegalArgumentException(review + " decision must be VERIFIED or FAILED.");
        }
        return decision;
    }

    private static String reviewReason(JsonNode body, String decision) {
        if ("VERIFIED".equals(decision)) {
            return null;
        }
        String reason = blank(optional(body, "reason")).trim();
        if (reason.isEmpty()) {
            throw new IllegalArgumentException("Please enter a reason for rejecting this record");
        }
        if (reason.length() > 500) {
            throw new IllegalArgumentException("Keep the reason to 500 characters.");
        }
        return reason;
    }

    @Transactional
    public PortfolioViews.CoverView createApplication(JsonNode body) {
        requireStaff();
        InsuranceApplication row = new InsuranceApplication();
        fillCover(row, body, "APP-", 4, "Under review");
        em.persist(row);
        return mapper.cover(row);
    }

    @Transactional
    public PortfolioViews.CoverView updateApplication(String ref, JsonNode body) {
        requireStaff();
        InsuranceApplication row = one(InsuranceApplication.class, ref, "Application");
        if (body.hasNonNull("status")) {
            row.setStatus(body.get("status").asText());
        }
        return mapper.cover(row);
    }

    // ── policy workflow (staff) ───────────────────────────────────────────────────────────────────

    /** Staff-issued cover needs no online payment: it starts Active today (demo server admin_create_policy). */
    @Transactional
    public PortfolioViews.CoverView createPolicy(JsonNode body) {
        AppUser staff = staffWith(Capability.POLICY_CREATE);
        InsurancePolicy row = new InsurancePolicy();
        fillCover(row, body, "POL-", 5, PolicyWorkflow.ACTIVE);
        row.setStatus(PolicyWorkflow.ACTIVE);
        policyWorkflow.startActive(row);
        em.persist(row);
        policyWorkflow.logOpened(row, staff.getUsername());
        List<CustomerAccount> matches = em.createQuery(
                        "select c from CustomerAccount c where c.name = :name order by c.displayOrder asc",
                        CustomerAccount.class)
                .setParameter("name", row.getCustomerName())
                .setMaxResults(1)
                .getResultList();
        if (!matches.isEmpty()) {
            CustomerAccount account = matches.get(0);
            account.setPolicyCount(account.getPolicyCount() + 1);
        }
        return mapper.cover(row);
    }

    /** Staff record a premium: a Pending Payment policy goes Active; an Active one is paid a period ahead. */
    @Transactional(noRollbackFor = IllegalArgumentException.class)
    public PortfolioViews.CoverView recordPayment(String ref, JsonNode body) {
        AppUser staff = staffWith(Capability.POLICY_WRITE);
        InsurancePolicy policy = one(InsurancePolicy.class, ref, "Policy");
        requireEddClear(accountFor(policy.getCustomerName()));
        return mapper.cover(policyWorkflow.pay(ref, defaultText(body, "method", "Bank EFT"), staff.getUsername()));
    }

    @Transactional(noRollbackFor = IllegalArgumentException.class)
    public PortfolioViews.CoverView cancelPolicy(String ref, JsonNode body) {
        AppUser staff = staffWith(Capability.POLICY_WRITE);
        return mapper.cover(policyWorkflow.cancel(ref, optional(body, "reason"), staff.getUsername()));
    }

    @Transactional
    public PortfolioViews.CoverView addBeneficiary(String ref, JsonNode body) {
        AppUser staff = staffWith(Capability.POLICY_WRITE);
        policyWorkflow.addBeneficiary(ref, beneficiaryInput(body), staff.getUsername());
        return mapper.cover(one(InsurancePolicy.class, ref, "Policy"));
    }

    @Transactional
    public PortfolioViews.CoverView removeBeneficiary(String ref, Long id) {
        AppUser staff = staffWith(Capability.POLICY_WRITE);
        policyWorkflow.removeBeneficiary(ref, id, staff.getUsername());
        return mapper.cover(one(InsurancePolicy.class, ref, "Policy"));
    }

    /** Runs the scheduled sweeps now: expire unpaid policies, lapse those past their grace period. */
    @Transactional
    public Map<String, List<String>> runPolicySweep() {
        staffWith(Capability.POLICY_WRITE);
        return Map.of("expired", policyWorkflow.expireUnpaid(), "lapsed", policyWorkflow.lapseOverdue());
    }

    public static PolicyWorkflow.BeneficiaryInput beneficiaryInput(JsonNode body) {
        return new PolicyWorkflow.BeneficiaryInput(
                optional(body, "fullName"), optional(body, "relationship"), optional(body, "nationalId"),
                optional(body, "mobile"), optional(body, "payoutMethod"), optional(body, "accountRef"),
                body.path("primary").asBoolean(false));
    }

    /** Demo server _check_edd_gate: no plan selection or payment while EDD is open or failed. */
    public static void requireEddClear(CustomerAccount account) {
        String edd = blank(account.getEddStatus());
        if (account.isPep() && !"VERIFIED".equals(edd)) {
            throw new IllegalArgumentException(account.getName() + "'s enhanced due diligence review is "
                    + (edd.isEmpty() ? "pending" : edd.toLowerCase(Locale.ROOT))
                    + ". It must be verified before cover can be chosen or paid for.");
        }
    }

    // ── claim workflow (staff) ────────────────────────────────────────────────────────────────────

    /** Staff lodge a claim for someone who cannot file it themselves (demo server admin_create_claim). */
    @Transactional
    public PortfolioViews.ClaimView createClaim(JsonNode body) {
        AppUser staff = staffWith(Capability.CLAIM_CREATE);
        return mapper.claim(claimWorkflow.lodge(lodgement(text(body, "policy"), body), staff.getUsername()));
    }

    public static ClaimWorkflow.Lodgement lodgement(String policyRef, JsonNode body) {
        return new ClaimWorkflow.Lodgement(policyRef, optional(body, "claimantName"), optional(body, "claimantMobile"),
                optional(body, "claimantRelationship"), optional(body, "eventDate"), optional(body, "description"),
                optional(body, "type"));
    }

    @Transactional
    public PortfolioViews.ClaimView setClaimPayee(String ref, JsonNode body) {
        AppUser staff = requireStaff();
        return mapper.claim(claimWorkflow.setPayee(ref, new ClaimWorkflow.Payee(
                optional(body, "name"), optional(body, "relationship"), optional(body, "mobile"),
                optional(body, "payoutMethod"), optional(body, "accountRef"), body.path("verified").asBoolean(false)),
                staff));
    }

    @Transactional
    public PortfolioViews.ClaimView rerunClaimChecks(String ref) {
        return mapper.claim(claimWorkflow.rerunChecks(ref, requireStaff()));
    }

    /** One of the person actions: assess, approve, reject or pay. */
    @Transactional
    public PortfolioViews.ClaimView claimAction(String ref, String action, JsonNode body) {
        AppUser actor = requireStaff();
        JsonNode input = body == null ? NullNode.getInstance() : body;
        InsuranceClaim claim = switch (action) {
            case "assess" -> claimWorkflow.assess(ref, optional(input, "note"), actor);
            case "approve" -> claimWorkflow.approve(ref,
                    input.hasNonNull("payoutAmount") ? Integer.valueOf(input.get("payoutAmount").asInt()) : null,
                    optional(input, "overrideReason"), actor);
            case "reject" -> claimWorkflow.reject(ref, optional(input, "reason"), actor);
            case "pay" -> claimWorkflow.pay(ref, actor);
            default -> throw new IllegalArgumentException("Unknown claim action: " + action);
        };
        return mapper.claim(claim);
    }

    @Transactional(readOnly = true)
    public List<PortfolioViews.HistoryView> history(String type, String ref) {
        requireStaff();
        String entity = switch (type) {
            case "policy" -> WorkflowHistory.POLICY;
            case "claim" -> WorkflowHistory.CLAIM;
            case "kyc" -> WorkflowHistory.KYC_REVIEW;
            case "edd" -> WorkflowHistory.EDD_REVIEW;
            default -> throw new IllegalArgumentException("History type must be policy, claim, kyc or edd.");
        };
        return history.of(entity, ref);
    }

    @Transactional
    public PortfolioViews.CoverView updateReceipt(String ref, JsonNode body) {
        requireStaff();
        PremiumReceipt row = one(PremiumReceipt.class, ref, "Receipt");
        if (body.hasNonNull("status")) {
            row.setStatus(body.get("status").asText());
        }
        return mapper.cover(row);
    }

    @Transactional
    public PortfolioViews.ConfigView saveConfig(String ref, JsonNode body) {
        requireStaff();
        ProductConfiguration row = ref == null
                ? new ProductConfiguration()
                : one(ProductConfiguration.class, ref, "Product configuration");
        if (ref == null) {
            row.setRef(nextRef("CFG-", 3, ProductConfiguration.class));
            row.setDisplayOrder(0);
            row.setVersionNo(1);
        } else {
            row.setVersionNo(row.getVersionNo() + 1);
        }
        row.setName(text(body, "name"));
        row.setCode(text(body, "code"));
        row.setDomain(defaultText(body, "domain", "Individual Life"));
        row.setCurrency(defaultText(body, "currency", "BWP"));
        row.setDescription(defaultText(body, "description", row.getName()));
        row.setEffectiveOn(defaultText(body, "effective", "2026-10-01"));
        row.setMinAge(body.path("minAge").asInt(18));
        row.setMaxAge(body.path("maxAge").asInt(65));
        row.setWaitingMonths(body.path("waiting").asInt(3));
        row.setCoverAmount(body.path("cover").asInt(500000));
        row.setPremiumAmount(body.path("premium").asInt(420));
        row.setFrequency(defaultText(body, "frequency", "Monthly"));
        row.setGraceDays(body.path("grace").asInt(30));
        row.setUnderwriting(defaultText(body, "underwriting", "Manual review"));
        row.setBenefits(join(body.get("benefits")));
        row.setChannels(join(body.get("channels")));
        row.setWording(defaultText(body, "wording", "Standard life wording · v1.0"));
        row.setApprovals(body.path("approvals").asBoolean(false));
        row.setStatus(defaultText(body, "status", "Draft"));
        if (ref == null) {
            em.persist(row);
        }
        return config(row);
    }

    private void fillCover(CoverRecord row, JsonNode body, String prefix, int width, String defaultStatus) {
        row.setRef(nextRef(prefix, width, row.getClass()));
        row.setCustomerName(text(body, "name"));
        row.setProduct(text(body, "product"));
        row.setAmount(body.path("amount").asInt());
        row.setPremium(body.path("premium").asInt(0));
        row.setStatus(body.hasNonNull("status") ? body.get("status").asText() : defaultStatus);
        row.setRecordDate(body.hasNonNull("date") ? body.get("date").asText() : LocalDate.now().format(DISPLAY));
        row.setDisplayOrder(0);
    }

    private CustomerAccount accountFor(String customerName) {
        return em.createQuery(
                        "select c from CustomerAccount c where c.name = :name order by c.displayOrder asc",
                        CustomerAccount.class)
                .setParameter("name", customerName)
                .setMaxResults(1)
                .getResultStream()
                .findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("Customer " + customerName + " not found"));
    }

    private <T> String nextRef(String prefix, int width, Class<T> type) {
        int max = 0;
        for (String ref : em.createQuery("select e.ref from " + type.getSimpleName() + " e", String.class).getResultList()) {
            max = Math.max(max, Integer.parseInt(ref.substring(prefix.length())));
        }
        return prefix + String.format("%0" + width + "d", max + 1);
    }

    private <T> List<T> list(Class<T> type) {
        return em.createQuery(
                "select e from " + type.getSimpleName() + " e order by e.displayOrder asc, e.id desc", type)
                .getResultList();
    }

    private <T> T one(Class<T> type, String ref, String label) {
        List<T> rows = em.createQuery(
                "select e from " + type.getSimpleName() + " e where e.ref = :ref", type)
                .setParameter("ref", ref)
                .getResultList();
        if (rows.isEmpty()) {
            throw new ResourceNotFoundException(label + " " + ref + " not found");
        }
        return rows.get(0);
    }

    private PortfolioViews.ConfigView config(ProductConfiguration row) {
        return new PortfolioViews.ConfigView(
                row.getRef(), row.getName(), row.getCode(), row.getDomain(), row.getCurrency(), row.getDescription(),
                row.getEffectiveOn(), row.getMinAge(), row.getMaxAge(), row.getWaitingMonths(), row.getCoverAmount(),
                row.getPremiumAmount(), row.getFrequency(), row.getGraceDays(), row.getUnderwriting(),
                split(row.getBenefits()), split(row.getChannels()), row.getWording(), row.isApprovals(),
                row.getStatus(), row.getVersionNo());
    }

    private static List<String> split(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        return Arrays.stream(value.split(",")).map(String::trim).filter(part -> !part.isEmpty()).toList();
    }

    private static String join(JsonNode node) {
        if (node == null || !node.isArray()) {
            return "";
        }
        StringBuilder joined = new StringBuilder();
        node.forEach(item -> {
            if (joined.length() > 0) {
                joined.append(", ");
            }
            joined.append(item.asText());
        });
        return joined.toString();
    }

    private static String text(JsonNode body, String field) {
        JsonNode node = body.get(field);
        if (node == null || !node.isTextual() || node.asText().isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return node.asText().trim();
    }

    private static String optional(JsonNode body, String field) {
        JsonNode node = body == null ? null : body.get(field);
        if (node == null || node.isNull()) {
            return null;
        }
        return node.asText();
    }

    private static String defaultText(JsonNode body, String field, String fallback) {
        JsonNode node = body == null ? null : body.get(field);
        if (node == null || node.isNull() || !node.isTextual() || node.asText().isBlank()) {
            return fallback;
        }
        return node.asText().trim();
    }

    private static String blank(String value) {
        return value == null ? "" : value;
    }

    private AppUser requireStaff() {
        AppUser user = security.authenticatedUser();
        if (user.isCustomerPortal()) {
            throw new NoAuthorizationException("Customer portal accounts cannot use operations APIs.");
        }
        return user;
    }

    private AppUser staffWith(Capability capability) {
        AppUser user = requireStaff();
        capabilities.require(capability);
        return user;
    }
}
