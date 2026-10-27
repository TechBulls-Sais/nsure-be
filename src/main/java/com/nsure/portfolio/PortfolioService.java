package com.nsure.portfolio;

import com.fasterxml.jackson.databind.JsonNode;
import com.nsure.common.exception.NotFoundException;
import com.nsure.rating.RateTableService;
import com.nsure.rating.RateTableViews;
import com.nsure.security.domain.AppUser;
import com.nsure.security.domain.Office;
import com.nsure.security.domain.Role;
import com.nsure.security.exception.NoAuthorizationException;
import com.nsure.security.repository.AppUserRepository;
import com.nsure.security.repository.RoleRepository;
import com.nsure.security.service.PlatformSecurityContext;
import java.util.Set;
import org.springframework.security.crypto.password.PasswordEncoder;
import jakarta.persistence.EntityManager;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PortfolioService {

    private static final DateTimeFormatter DISPLAY = DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.ENGLISH);
    // A customer's purchase that is not (or no longer) cover: awaiting its first premium, expired unpaid, or cancelled.
    private static final Set<String> REQUEST_STATES =
            Set.of(PolicyWorkflow.PENDING_PAYMENT, PolicyWorkflow.EXPIRED, PolicyWorkflow.CANCELLED);

    private final EntityManager em;
    private final PlatformSecurityContext security;
    private final AppUserRepository users;
    private final RoleRepository roles;
    private final PasswordEncoder passwords;
    private final RateTableService rates;
    private final PolicyWorkflow policyWorkflow;
    private final ClaimWorkflow claimWorkflow;
    private final WorkflowHistory history;

    public PortfolioService(
            EntityManager em,
            PlatformSecurityContext security,
            AppUserRepository users,
            RoleRepository roles,
            PasswordEncoder passwords,
            RateTableService rates,
            PolicyWorkflow policyWorkflow,
            ClaimWorkflow claimWorkflow,
            WorkflowHistory history) {
        this.em = em;
        this.security = security;
        this.users = users;
        this.roles = roles;
        this.passwords = passwords;
        this.rates = rates;
        this.policyWorkflow = policyWorkflow;
        this.claimWorkflow = claimWorkflow;
        this.history = history;
    }

    @Transactional(readOnly = true)
    public List<PortfolioViews.CustomerView> customers() {
        Map<String, List<PortfolioViews.CustomerTierView>> tiers = new HashMap<>();
        for (InsurancePolicy policy : em.createQuery(
                "select p from InsurancePolicy p order by p.id desc", InsurancePolicy.class).getResultList()) {
            tiers.computeIfAbsent(policy.getCustomerName(), name -> new ArrayList<>()).add(customerTier(policy));
        }
        return list(CustomerAccount.class).stream()
                .map(row -> customer(row, tiers.getOrDefault(row.getName(), List.of())))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<PortfolioViews.CoverView> applications() {
        return covers(list(InsuranceApplication.class), InsuranceApplication.class, true);
    }

    @Transactional(readOnly = true)
    public List<PortfolioViews.CoverView> policies() {
        return covers(list(InsurancePolicy.class), InsurancePolicy.class, true);
    }

    @Transactional(readOnly = true)
    public List<PortfolioViews.ClaimView> claims() {
        return list(InsuranceClaim.class).stream().map(this::claim).toList();
    }

    @Transactional(readOnly = true)
    public List<PortfolioViews.ProductView> products() {
        Map<Long, List<PortfolioViews.TierView>> tiers = activeTiers();
        return list(InsuranceProduct.class).stream()
                .map(product -> {
                    List<PortfolioViews.TierView> own = tiers.getOrDefault(product.getId(), List.of());
                    return new PortfolioViews.ProductView(
                            product.getName(), product.getIcon(), product.getColor(), product.getDescription(),
                            headlineCover(product, own), headlinePremium(product, own), product.getLiveCount(), own,
                            blank(product.getImageUrl()), blank(product.getImageFocus()),
                            blank(product.getNeedKey()), blank(product.getNeedLabel()), blank(product.getNeedIcon()),
                            blank(product.getHighlight()), blank(product.getSummary()), blank(product.getFits()),
                            lines(product.getBenefits()), blank(product.getCoverUnit()), blank(product.getProductType()));
                })
                .toList();
    }

    /** Products for the public landing page, in catalogue order. */
    @Transactional(readOnly = true)
    public List<PortfolioViews.PublicProductView> publicProducts() {
        Map<Long, List<PortfolioViews.TierView>> tiers = activeTiers();
        return list(InsuranceProduct.class).stream()
                .map(product -> {
                    List<PortfolioViews.TierView> own = tiers.getOrDefault(product.getId(), List.of());
                    return new PortfolioViews.PublicProductView(
                            product.getName(), blank(product.getNeedIcon()), blank(product.getHighlight()),
                            blank(product.getSummary()), blank(product.getTagline()), lines(product.getBenefits()),
                            blank(product.getImageUrl()), blank(product.getImageFocus()),
                            headlineCover(product, own), headlinePremium(product, own),
                            blank(product.getCoverUnit()), product.getFeaturedOrder(), own, blank(product.getProductType()));
                })
                .toList();
    }

    private Map<Long, List<PortfolioViews.TierView>> activeTiers() {
        Map<Long, List<PortfolioViews.TierView>> tiers = new HashMap<>();
        Map<Long, RateTableService.Priced> priced = rates.pricedTiers();
        Map<Long, List<RateTableViews.LifeRule>> rules = rates.lifeRules();
        for (CoverTier tier : em.createQuery(
                "select t from CoverTier t where t.active = true order by t.displayOrder asc, t.id asc",
                CoverTier.class).getResultList()) {
            // A rate-table-priced tier shows its cheapest Main Insured rate as a "from" price (spec 03 section 4.4).
            RateTableService.Priced rated = priced.get(tier.getId());
            int premium = rated == null ? tier.getPremium() : wholePula(rated.fromPremium());
            tiers.computeIfAbsent(tier.getProduct().getId(), id -> new ArrayList<>()).add(new PortfolioViews.TierView(
                    tier.getName(), tier.getCoverAmount(), premium, tier.getPremiumFrequency(),
                    tier.isPopular(), tier.getBenefits() == null ? "" : tier.getBenefits(),
                    rated != null, rated == null ? "" : rated.rateTable(),
                    rules.getOrDefault(tier.getId(), List.of())));
        }
        return tiers;
    }

    /**
     * Card headline cover. Tiers are the source of truth ("up to BWP 1,000,000"); the ins_product.cover
     * string is only used for a product without active tiers.
     */
    private static String headlineCover(InsuranceProduct product, List<PortfolioViews.TierView> tiers) {
        if (tiers.isEmpty()) {
            return blank(product.getCover());
        }
        int top = tiers.stream().mapToInt(PortfolioViews.TierView::cover).max().orElse(0);
        String unit = product.getCoverUnit() == null || product.getCoverUnit().isBlank()
                ? "" : " / " + product.getCoverUnit();
        return "up to " + bwp(top) + unit;
    }

    /** Card headline monthly premium: "from BWP 230" from the cheapest tier, else ins_product.premium. */
    private static String headlinePremium(InsuranceProduct product, List<PortfolioViews.TierView> tiers) {
        if (tiers.isEmpty()) {
            return bwp(digits(product.getPremium()));
        }
        return "from " + bwp(tiers.stream().mapToInt(PortfolioViews.TierView::premium).min().orElse(0));
    }

    private static String bwp(int amount) {
        return String.format(Locale.ENGLISH, "BWP %,d", amount);
    }

    @Transactional(readOnly = true)
    public List<PortfolioViews.SchemeView> schemes() {
        return list(GroupScheme.class).stream()
                .map(scheme -> new PortfolioViews.SchemeView(
                        scheme.getRef(), scheme.getName(), scheme.getSector(), scheme.getInitials(),
                        scheme.getMembers(), scheme.getPremium(), scheme.getRenewal(), scheme.getColor()))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<PortfolioViews.CoverView> receipts() {
        return list(PremiumReceipt.class).stream().map(row -> cover(row, List.of(), List.of())).toList();
    }

    @Transactional(readOnly = true)
    public List<PortfolioViews.ConfigView> productConfigs() {
        return list(ProductConfiguration.class).stream().map(this::config).toList();
    }

    @Transactional
    public PortfolioViews.CustomerView createCustomer(JsonNode body) {
        CustomerAccount row = new CustomerAccount();
        row.setRef(nextRef("CUS-", 4, CustomerAccount.class));
        row.setName(text(body, "name"));
        row.setEmail(text(body, "email"));
        row.setPhone(text(body, "phone"));
        String poiType = optional(body, "poiType");
        if (poiType != null && !poiType.isBlank()) {
            if (!Set.of("Omang", "Passport").contains(poiType)) {
                throw new IllegalArgumentException("Proof of identity must be Omang or Passport.");
            }
            row.setPoiType(poiType);
        }
        String poiNumber = optional(body, "poiNumber");
        if (poiNumber != null && !poiNumber.isBlank()) {
            row.setPoiNumber(poiNumber.trim());
        }
        String dateOfBirth = optional(body, "dateOfBirth");
        if (dateOfBirth != null && !dateOfBirth.isBlank()) {
            LocalDate dob;
            try {
                dob = LocalDate.parse(dateOfBirth);
            } catch (DateTimeParseException e) {
                throw new IllegalArgumentException("Date of birth must be a date like 1990-04-12.");
            }
            if (dob.isAfter(LocalDate.now())) {
                throw new IllegalArgumentException("Date of birth cannot be in the future.");
            }
            row.setDateOfBirth(dob);
        }
        row.setProduct("");
        row.setPolicyCount(0);
        row.setStatus("Pending KYC");
        row.setKycStatus("PENDING");
        // A politically exposed person needs enhanced due diligence before choosing cover (demo server: the
        // PEP → EDD-pending rule lives on the customer, so no path can skip it).
        if (body.path("pep").asBoolean(false)) {
            row.setEddStatus("PENDING");
        }
        row.setColor("green");
        row.setDisplayOrder(0);
        String username = optional(body, "username");
        if (username != null && !username.isBlank()) {
            row.setLoginUsername(createCustomerLogin(username, optional(body, "password")));
        }
        em.persist(row);
        return customer(row);
    }

    @Transactional
    public PortfolioViews.CoverView createApplication(JsonNode body) {
        InsuranceApplication row = new InsuranceApplication();
        fillCover(row, body, "APP-", 4, "Under review");
        List<RateTableService.PricedLife> lives = applyTier(row, body);
        em.persist(row);
        saveLives(row, lives);
        return cover(row);
    }

    @Transactional
    public PortfolioViews.CoverView updateApplication(String ref, JsonNode body) {
        InsuranceApplication row = one(InsuranceApplication.class, ref, "Application");
        if (body.hasNonNull("status")) {
            row.setStatus(body.get("status").asText());
        }
        return cover(row);
    }

    /** Staff-issued cover needs no online payment: it starts Active today (demo server admin_create_policy). */
    @Transactional
    public PortfolioViews.CoverView createPolicy(JsonNode body) {
        InsurancePolicy row = new InsurancePolicy();
        fillCover(row, body, "POL-", 5, PolicyWorkflow.ACTIVE);
        List<RateTableService.PricedLife> lives = applyTier(row, body);
        policyWorkflow.startActive(row);
        em.persist(row);
        saveLives(row, lives);
        policyWorkflow.logOpened(row, security.authenticatedUser().getUsername());
        List<CustomerAccount> matches = em.createQuery(
                        "select c from CustomerAccount c where c.name = :name order by c.displayOrder asc",
                        CustomerAccount.class)
                .setParameter("name", row.getCustomerName())
                .setMaxResults(1)
                .getResultList();
        if (matches.isEmpty()) {
            throw new NotFoundException("Customer " + row.getCustomerName() + " not found");
        }
        CustomerAccount account = matches.get(0);
        if (!"VERIFIED".equals(account.getKycStatus())) {
            throw new IllegalArgumentException("Mark KYC successful before adding a policy.");
        }
        account.setPolicyCount(account.getPolicyCount() + 1);
        account.setProduct(row.getProduct());
        return cover(row);
    }

    @Transactional(readOnly = true)
    public PortfolioViews.ShopView shop() {
        return shopFor(customerForLogin());
    }

    @Transactional
    public PortfolioViews.ShopView buy(JsonNode body) {
        CustomerAccount account = customerForLogin();
        if (!"VERIFIED".equals(account.getKycStatus())) {
            throw new IllegalArgumentException("KYC must be successful before you can buy a policy.");
        }
        String productName = text(body, "product");
        List<InsuranceProduct> products = em.createQuery(
                        "select p from InsuranceProduct p where p.name = :name", InsuranceProduct.class)
                .setParameter("name", productName)
                .getResultList();
        if (products.isEmpty()) {
            throw new NotFoundException("Product " + productName + " not found");
        }
        InsuranceProduct product = products.get(0);
        InsurancePolicy row = new InsurancePolicy();
        row.setRef(nextRef("POL-", 5, InsurancePolicy.class));
        row.setCustomerName(account.getName());
        row.setProduct(product.getName());
        row.setAmount(digits(product.getCover()));
        row.setPremium(BigDecimal.valueOf(digits(product.getPremium())));
        List<RateTableService.PricedLife> lives = applyTier(row, body);
        // Spec 01 BR-25: no plan selection while the customer's enhanced due diligence is open or failed.
        String edd = blank(account.getEddStatus());
        if ("PENDING".equals(edd) || "FAILED".equals(edd)) {
            throw new IllegalArgumentException(
                    "Your enhanced due diligence review must be cleared before you can request cover.");
        }
        // Spec 01 BR-8: one open purchase per product and tier, never a duplicate.
        policyWorkflow.expireUnpaid();
        Optional<String> open = em.createQuery(
                        "select p.ref from InsurancePolicy p where p.customerName = :name and p.product = :product"
                                + " and coalesce(p.tier, '') = :tier and p.status = :status", String.class)
                .setParameter("name", account.getName())
                .setParameter("product", row.getProduct())
                .setParameter("tier", blank(row.getTier()))
                .setParameter("status", PolicyWorkflow.PENDING_PAYMENT)
                .getResultStream()
                .findFirst();
        if (open.isPresent()) {
            throw new IllegalArgumentException("You already have " + row.getProduct()
                    + (row.getTier() == null ? "" : " " + row.getTier()) + " awaiting payment (" + open.get()
                    + "). Pay it from Your policies.");
        }
        // Reserved until paid within the payment window; it confers no cover until then (demo server checkout).
        policyWorkflow.openPending(row);
        row.setRecordDate(LocalDate.now().format(DISPLAY));
        row.setDisplayOrder(0);
        List<PolicyWorkflow.BeneficiaryInput> beneficiaries = beneficiaryInputs(body.get("beneficiaries"));
        em.persist(row);
        saveLives(row, lives);
        policyWorkflow.logOpened(row, account.getLoginUsername());
        for (PolicyWorkflow.BeneficiaryInput input : beneficiaries) {
            policyWorkflow.addBeneficiary(row.getRef(), input, account.getLoginUsername());
        }
        return shopFor(account);
    }

    // ── policy workflow ───────────────────────────────────────────────────────────────────────────

    /** Staff record a premium: a Pending Payment policy goes Active; an Active one is paid a period ahead. */
    @Transactional(noRollbackFor = IllegalArgumentException.class)
    public PortfolioViews.CoverView recordPayment(String ref, JsonNode body) {
        AppUser staff = security.authenticatedUser();
        staff.validateHasPermissionTo("UPDATE_POLICY");
        InsurancePolicy policy = one(InsurancePolicy.class, ref, "Policy");
        requireEddClear(accountFor(policy.getCustomerName()));
        return cover(policyWorkflow.pay(ref, defaultText(body, "method", "Bank EFT"), staff.getUsername()));
    }

    @Transactional(noRollbackFor = IllegalArgumentException.class)
    public PortfolioViews.CoverView cancelPolicy(String ref, JsonNode body) {
        AppUser staff = security.authenticatedUser();
        staff.validateHasPermissionTo("UPDATE_POLICY");
        return cover(policyWorkflow.cancel(ref, optional(body, "reason"), staff.getUsername()));
    }

    @Transactional
    public PortfolioViews.CoverView addBeneficiary(String ref, JsonNode body) {
        AppUser staff = security.authenticatedUser();
        staff.validateHasPermissionTo("UPDATE_POLICY");
        policyWorkflow.addBeneficiary(ref, beneficiaryInput(body), staff.getUsername());
        return cover(one(InsurancePolicy.class, ref, "Policy"));
    }

    @Transactional
    public PortfolioViews.CoverView removeBeneficiary(String ref, Long id) {
        AppUser staff = security.authenticatedUser();
        staff.validateHasPermissionTo("UPDATE_POLICY");
        policyWorkflow.removeBeneficiary(ref, id, staff.getUsername());
        return cover(one(InsurancePolicy.class, ref, "Policy"));
    }

    /** Runs the scheduled sweeps now: expire unpaid policies, lapse those past their grace period. */
    @Transactional
    public Map<String, List<String>> runPolicySweep() {
        security.authenticatedUser().validateHasPermissionTo("UPDATE_POLICY");
        return Map.of("expired", policyWorkflow.expireUnpaid(), "lapsed", policyWorkflow.lapseOverdue());
    }

    /** The customer pays the first premium of their own policy (or the next one of an active policy). */
    @Transactional(noRollbackFor = IllegalArgumentException.class)
    public PortfolioViews.ShopView shopPay(String ref, JsonNode body) {
        CustomerAccount account = customerForLogin();
        InsurancePolicy policy = ownPolicy(account, ref);
        if (!"VERIFIED".equals(account.getKycStatus())) {
            throw new IllegalArgumentException("Your identity must be verified before you can pay for cover.");
        }
        requireEddClear(account);
        policyWorkflow.pay(policy.getRef(), text(body, "method"), account.getLoginUsername());
        return shopFor(account);
    }

    @Transactional
    public PortfolioViews.ShopView shopAddBeneficiary(String ref, JsonNode body) {
        CustomerAccount account = customerForLogin();
        policyWorkflow.addBeneficiary(ownPolicy(account, ref).getRef(), beneficiaryInput(body), account.getLoginUsername());
        return shopFor(account);
    }

    @Transactional
    public PortfolioViews.ShopView shopRemoveBeneficiary(String ref, Long id) {
        CustomerAccount account = customerForLogin();
        policyWorkflow.removeBeneficiary(ownPolicy(account, ref).getRef(), id, account.getLoginUsername());
        return shopFor(account);
    }

    /** A claim lodged by the policyholder on their own policy (demo server submit_claim). */
    @Transactional
    public PortfolioViews.ShopView shopClaim(JsonNode body) {
        CustomerAccount account = customerForLogin();
        InsurancePolicy policy = ownPolicy(account, text(body, "policy"));
        claimWorkflow.lodge(lodgement(policy.getRef(), body), account.getLoginUsername());
        return shopFor(account);
    }

    private InsurancePolicy ownPolicy(CustomerAccount account, String ref) {
        InsurancePolicy policy = one(InsurancePolicy.class, ref, "Policy");
        if (!policy.getCustomerName().equals(account.getName())) {
            // Not theirs: answer as if it did not exist rather than confirm someone else's policy number.
            throw new NotFoundException("Policy " + ref + " not found");
        }
        return policy;
    }

    /** Spec 01 BR-25 / demo server _check_edd_gate: no plan selection or payment while EDD is open or failed. */
    private static void requireEddClear(CustomerAccount account) {
        String edd = blank(account.getEddStatus());
        if ("PENDING".equals(edd) || "FAILED".equals(edd)) {
            throw new IllegalArgumentException(account.getName() + "'s enhanced due diligence review is "
                    + edd.toLowerCase(Locale.ROOT) + ". It must be verified before cover can be paid for.");
        }
    }

    private List<PolicyWorkflow.BeneficiaryInput> beneficiaryInputs(JsonNode node) {
        if (node == null || node.isNull()) {
            return List.of();
        }
        if (!node.isArray()) {
            throw new IllegalArgumentException("beneficiaries must be a list.");
        }
        if (node.size() > PolicyWorkflow.MAX_BENEFICIARIES) {
            throw new IllegalArgumentException(
                    "A policy can have at most " + PolicyWorkflow.MAX_BENEFICIARIES + " beneficiaries.");
        }
        List<PolicyWorkflow.BeneficiaryInput> inputs = new ArrayList<>();
        node.forEach(item -> inputs.add(beneficiaryInput(item)));
        return inputs;
    }

    private static PolicyWorkflow.BeneficiaryInput beneficiaryInput(JsonNode body) {
        return new PolicyWorkflow.BeneficiaryInput(
                optional(body, "fullName"), optional(body, "relationship"), optional(body, "nationalId"),
                optional(body, "mobile"), optional(body, "payoutMethod"), optional(body, "accountRef"),
                body.path("primary").asBoolean(false));
    }

    // ── customer reviews (demo server admin_review_customer_status) ───────────────────────────────

    /**
     * Records the outcome of a customer's enhanced due diligence review, after KYC is successful. Only a customer
     * flagged for EDD (a politically exposed person) has one; rejecting needs a reason, which the customer sees.
     */
    @Transactional
    public PortfolioViews.CustomerView reviewEdd(String ref, JsonNode body) {
        AppUser reviewer = security.authenticatedUser();
        reviewer.validateHasPermissionTo("EDD_CUSTOMER");
        CustomerAccount row = one(CustomerAccount.class, ref, "Customer");
        if (!"VERIFIED".equals(row.getKycStatus())) {
            throw new IllegalArgumentException("Mark KYC successful before recording the EDD review.");
        }
        if (blank(row.getEddStatus()).isEmpty()) {
            throw new IllegalArgumentException("EDD review does not apply to this customer");
        }
        String decision = defaultText(body, "decision", "VERIFIED").toUpperCase(Locale.ROOT);
        if (!Set.of("VERIFIED", "FAILED").contains(decision)) {
            throw new IllegalArgumentException("EDD decision must be VERIFIED or FAILED.");
        }
        String reason = reviewReason(body, decision);
        String previous = row.getEddStatus();
        row.setEddStatus(decision);
        row.setEddRejectionReason(reason);
        history.record(WorkflowHistory.EDD_REVIEW, row.getRef(), row.getName(),
                "VERIFIED".equals(decision) ? "Approved" : "Rejected", previous, decision, reason, reviewer.getUsername());
        return customer(row);
    }

    private static String reviewReason(JsonNode body, String decision) {
        String reason = blank(optional(body, "reason")).trim();
        if ("VERIFIED".equals(decision)) {
            return null;
        }
        if (reason.isEmpty()) {
            throw new IllegalArgumentException("Please enter a reason for rejecting this record");
        }
        if (reason.length() > 500) {
            throw new IllegalArgumentException("Keep the reason to 500 characters.");
        }
        return reason;
    }

    private CustomerAccount accountFor(String customerName) {
        return em.createQuery(
                        "select c from CustomerAccount c where c.name = :name order by c.displayOrder asc",
                        CustomerAccount.class)
                .setParameter("name", customerName)
                .setMaxResults(1)
                .getResultStream()
                .findFirst()
                .orElseThrow(() -> new NotFoundException("Customer " + customerName + " not found"));
    }

    /**
     * Staff quote for a customer, tier and lives: the same pricing and rule checks as saving the application
     * or policy, so the form shows what will be stored.
     */
    @Transactional(readOnly = true)
    public PortfolioViews.QuoteView quote(JsonNode body) {
        if (security.authenticatedUser().hasRole("Policyholder")) {
            throw new NoAuthorizationException("Customers quote their own cover in the policy shop.");
        }
        return quote(text(body, "name"), text(body, "product"), text(body, "tier"), body);
    }

    /** The signed-in customer's quote; they are always the main insured. */
    @Transactional(readOnly = true)
    public PortfolioViews.QuoteView shopQuote(JsonNode body) {
        return quote(customerForLogin().getName(), text(body, "product"), text(body, "tier"), body);
    }

    private PortfolioViews.QuoteView quote(String customerName, String product, String tierName, JsonNode body) {
        CoverTier tier = tier(product, tierName);
        Optional<RateTableService.PolicyPrice> price = price(tier, customerName, lives(body));
        return new PortfolioViews.QuoteView(
                product, tier.getName(), tier.getCoverAmount(), price.isPresent(),
                price.map(RateTableService.PolicyPrice::rateTableVersion).orElse(""),
                price.map(p -> p.lines().stream().map(PortfolioService::lifeView).toList()).orElse(List.of()),
                price.map(RateTableService.PolicyPrice::monthlyTotal).orElse(BigDecimal.valueOf(tier.getPremium())));
    }

    /** KYC review: VERIFIED (the default) or FAILED with a reason the customer sees. */
    @Transactional
    public PortfolioViews.CustomerView reviewKyc(String ref, JsonNode body) {
        AppUser reviewer = security.authenticatedUser();
        reviewer.validateHasPermissionTo("KYC_CUSTOMER");
        CustomerAccount row = one(CustomerAccount.class, ref, "Customer");
        String decision = body == null ? "VERIFIED" : defaultText(body, "decision", "VERIFIED").toUpperCase(Locale.ROOT);
        if (!Set.of("VERIFIED", "FAILED").contains(decision)) {
            throw new IllegalArgumentException("KYC decision must be VERIFIED or FAILED.");
        }
        String reason = body == null ? null : reviewReason(body, decision);
        String previous = blank(row.getKycStatus());
        row.setKycStatus(decision);
        row.setStatus("VERIFIED".equals(decision) ? "Verified" : "KYC failed");
        row.setKycRejectionReason(reason);
        history.record(WorkflowHistory.KYC_REVIEW, row.getRef(), row.getName(),
                "VERIFIED".equals(decision) ? "Approved" : "Rejected", previous, decision, reason, reviewer.getUsername());
        return customer(row);
    }

    // ── claim workflow ────────────────────────────────────────────────────────────────────────────

    /** Staff lodge a claim for someone who cannot file it themselves (demo server admin_create_claim). */
    @Transactional
    public PortfolioViews.ClaimView createClaim(JsonNode body) {
        AppUser staff = security.authenticatedUser();
        staff.validateHasPermissionTo("CREATE_CLAIM");
        return claim(claimWorkflow.lodge(lodgement(text(body, "policy"), body), staff.getUsername()));
    }

    private static ClaimWorkflow.Lodgement lodgement(String policyRef, JsonNode body) {
        return new ClaimWorkflow.Lodgement(policyRef, optional(body, "claimantName"), optional(body, "claimantMobile"),
                optional(body, "claimantRelationship"), optional(body, "eventDate"), optional(body, "description"),
                optional(body, "type"));
    }

    @Transactional
    public PortfolioViews.ClaimView setClaimPayee(String ref, JsonNode body) {
        return claim(claimWorkflow.setPayee(ref, new ClaimWorkflow.Payee(
                optional(body, "name"), optional(body, "relationship"), optional(body, "mobile"),
                optional(body, "payoutMethod"), optional(body, "accountRef"), body.path("verified").asBoolean(false)),
                security.authenticatedUser()));
    }

    @Transactional
    public PortfolioViews.ClaimView rerunClaimChecks(String ref) {
        return claim(claimWorkflow.rerunChecks(ref, security.authenticatedUser()));
    }

    /** One of the person actions: assess, approve, reject or pay. */
    @Transactional
    public PortfolioViews.ClaimView claimAction(String ref, String action, JsonNode body) {
        AppUser actor = security.authenticatedUser();
        JsonNode input = body == null ? com.fasterxml.jackson.databind.node.NullNode.getInstance() : body;
        InsuranceClaim claim = switch (action) {
            case "assess" -> claimWorkflow.assess(ref, optional(input, "note"), actor);
            case "approve" -> claimWorkflow.approve(ref,
                    input.hasNonNull("payoutAmount") ? Integer.valueOf(input.get("payoutAmount").asInt()) : null, actor);
            case "reject" -> claimWorkflow.reject(ref, optional(input, "reason"), actor);
            case "pay" -> claimWorkflow.pay(ref, actor);
            default -> throw new IllegalArgumentException("Unknown claim action: " + action);
        };
        return claim(claim);
    }

    @Transactional(readOnly = true)
    public List<PortfolioViews.HistoryView> history(String type, String ref) {
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
        PremiumReceipt row = one(PremiumReceipt.class, ref, "Receipt");
        if (body.hasNonNull("status")) {
            row.setStatus(body.get("status").asText());
        }
        return cover(row);
    }

    @Transactional
    public PortfolioViews.ConfigView saveConfig(String ref, JsonNode body) {
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
        row.setPremium(amount(body, "premium"));
        row.setStatus(body.hasNonNull("status") ? body.get("status").asText() : defaultStatus);
        row.setRecordDate(body.hasNonNull("date") ? body.get("date").asText() : LocalDate.now().format(DISPLAY));
        row.setDisplayOrder(0);
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
            throw new NotFoundException(label + " " + ref + " not found");
        }
        return rows.get(0);
    }

    private PortfolioViews.CustomerView customer(CustomerAccount row) {
        List<PortfolioViews.CustomerTierView> tiers = em.createQuery(
                        "select p from InsurancePolicy p where p.customerName = :name order by p.id desc",
                        InsurancePolicy.class)
                .setParameter("name", row.getName())
                .getResultList()
                .stream()
                .map(this::customerTier)
                .toList();
        return customer(row, tiers);
    }

    private PortfolioViews.CustomerTierView customerTier(InsurancePolicy policy) {
        return new PortfolioViews.CustomerTierView(
                policy.getRef(), policy.getProduct(), policy.getTier() == null ? "" : policy.getTier());
    }

    /**
     * Applies the chosen tier: it must belong to the cover's product, and its cover and premium
     * replace whatever was sent. No tier keeps the record as sent (older clients, imports).
     * Returns the priced lives to store once the record is saved; none on a flat-priced tier.
     */
    private <T extends CoverRecord & TieredCover> List<RateTableService.PricedLife> applyTier(T row, JsonNode body) {
        String tierName = optional(body, "tier");
        List<RateTableService.LifeToPrice> others = lives(body);
        if (tierName == null || tierName.isBlank()) {
            if (!others.isEmpty()) {
                throw new IllegalArgumentException("Choose a tier before adding lives.");
            }
            return List.of();
        }
        CoverTier tier = tier(row.getProduct(), tierName);
        row.setTier(tier.getName());
        row.setAmount(tier.getCoverAmount());
        // Server-side pricing (R-18): a rate-table-priced plan sums every covered life's rate by age from the
        // current version and stamps that version on the record; any client-supplied premium is ignored.
        Optional<RateTableService.PolicyPrice> price = price(tier, row.getCustomerName(), others);
        row.setPremium(price.map(RateTableService.PolicyPrice::monthlyTotal).orElse(BigDecimal.valueOf(tier.getPremium())));
        row.setRateTableVersion(price.map(RateTableService.PolicyPrice::rateTableVersion).orElse(null));
        return price.map(RateTableService.PolicyPrice::lines).orElse(List.of());
    }

    /** The customer is always the main insured, priced from their own date of birth; the body adds the rest. */
    private Optional<RateTableService.PolicyPrice> price(
            CoverTier tier, String customerName, List<RateTableService.LifeToPrice> others) {
        List<RateTableService.LifeToPrice> lives = new ArrayList<>();
        lives.add(new RateTableService.LifeToPrice(selfRelationship(), customerName, dateOfBirth(customerName)));
        lives.addAll(others);
        Optional<RateTableService.PolicyPrice> price = rates.pricePolicy(tier, lives, null);
        if (price.isEmpty() && !others.isEmpty()) {
            throw new IllegalArgumentException("The " + tier.getName() + " plan has a flat premium and covers the "
                    + "main insured only, so lives cannot be added to it.");
        }
        return price;
    }

    /** The lives a request adds beside the main insured: {relationship, fullName, dateOfBirth}. */
    private List<RateTableService.LifeToPrice> lives(JsonNode body) {
        JsonNode node = body.get("lives");
        if (node == null || node.isNull()) {
            return List.of();
        }
        if (!node.isArray()) {
            throw new IllegalArgumentException("lives must be a list.");
        }
        if (node.size() > 40) {
            throw new IllegalArgumentException("A policy can cover at most 40 lives.");
        }
        String self = selfRelationship();
        List<RateTableService.LifeToPrice> lives = new ArrayList<>();
        for (JsonNode life : node) {
            String relationship = blank(optional(life, "relationship")).trim();
            String fullName = blank(optional(life, "fullName")).trim();
            if (relationship.equals(self)) {
                throw new IllegalArgumentException(
                        "The customer is the " + self + "; add only the other lives to cover.");
            }
            String dob = optional(life, "dateOfBirth");
            LocalDate dateOfBirth = null;
            if (dob != null && !dob.isBlank()) {
                try {
                    dateOfBirth = LocalDate.parse(dob.trim());
                } catch (DateTimeParseException e) {
                    throw new IllegalArgumentException(
                            (fullName.isEmpty() ? relationship : fullName) + ": date of birth must be a date like 1990-04-12.");
                }
            }
            lives.add(new RateTableService.LifeToPrice(relationship, fullName, dateOfBirth));
        }
        return lives;
    }

    private String selfRelationship() {
        return em.createQuery(
                        "select r.relationshipName from RelationshipType r where r.selfRelationship = true", String.class)
                .setMaxResults(1)
                .getResultStream()
                .findFirst()
                .orElse("Main Insured");
    }

    /** Stores the priced lives as Policy Life Cover rows (R-16), main insured first. */
    private void saveLives(CoverRecord row, List<RateTableService.PricedLife> lines) {
        int position = 0;
        for (RateTableService.PricedLife line : lines) {
            em.persist(new PolicyLifeCover(row, line, ++position));
        }
    }

    private CoverTier tier(String product, String tierName) {
        List<CoverTier> matches = em.createQuery(
                        "select t from CoverTier t where t.product.name = :product and t.name = :tier and t.active = true",
                        CoverTier.class)
                .setParameter("product", product)
                .setParameter("tier", tierName.trim())
                .getResultList();
        if (matches.isEmpty()) {
            throw new IllegalArgumentException(
                    "Tier " + tierName.trim() + " is not available for " + product + ".");
        }
        return matches.get(0);
    }

    private LocalDate dateOfBirth(String customerName) {
        return em.createQuery(
                        "select c from CustomerAccount c where c.name = :name order by c.displayOrder asc",
                        CustomerAccount.class)
                .setParameter("name", customerName)
                .setMaxResults(1)
                .getResultStream()
                .findFirst()
                .map(CustomerAccount::getDateOfBirth)
                .orElse(null);
    }

    /** Tier cards keep whole-pula "from" prices; rate rows carry thebe. */
    private static int wholePula(BigDecimal amount) {
        return Math.max(1, amount.setScale(0, RoundingMode.HALF_UP).intValue());
    }

    private PortfolioViews.CustomerView customer(
            CustomerAccount row, List<PortfolioViews.CustomerTierView> tiers) {
        return new PortfolioViews.CustomerView(
                row.getRef(), row.getName(), row.getEmail(), row.getPhone(),
                row.getProduct() == null ? "" : row.getProduct(), row.getPolicyCount(), row.getStatus(), row.getColor(),
                row.getKycStatus() == null ? "PENDING" : row.getKycStatus(),
                row.getLoginUsername() == null ? "" : row.getLoginUsername(),
                row.getPoiType() == null ? "" : row.getPoiType(),
                row.getPoiNumber() == null ? "" : row.getPoiNumber(),
                row.getDateOfBirth() == null ? "" : row.getDateOfBirth().toString(),
                row.getEddStatus() == null ? "" : row.getEddStatus(),
                tiers,
                blank(row.getKycRejectionReason()),
                blank(row.getEddRejectionReason()));
    }

    private String createCustomerLogin(String username, String password) {
        String login = username.trim().toLowerCase();
        if (!login.matches("[a-z0-9._-]{3,100}")) {
            throw new IllegalArgumentException("Username must be 3–100 letters, numbers, dots, or hyphens.");
        }
        if (password == null || password.length() < 8) {
            throw new IllegalArgumentException("Password must be at least 8 characters.");
        }
        if (users.findByUsername(login).isPresent()) {
            throw new IllegalArgumentException("Username " + login + " is already in use.");
        }
        Role policyholder = roles.findByName("Policyholder")
                .orElseThrow(() -> new IllegalArgumentException("Policyholder role is not available."));
        List<Office> offices = em.createQuery(
                        "select o from Office o where o.name = :name", Office.class)
                .setParameter("name", "Head Office")
                .setMaxResults(1)
                .getResultList();
        if (offices.isEmpty()) {
            throw new IllegalArgumentException("Head Office is not available.");
        }
        AppUser user = new AppUser();
        user.setUsername(login);
        user.setPassword(passwords.encode(password));
        user.setEnabled(true);
        user.setOffice(offices.get(0));
        user.replaceRoles(Set.of(policyholder));
        users.save(user);
        return login;
    }

    private PortfolioViews.ShopView shopFor(CustomerAccount account) {
        List<PortfolioViews.CoverView> policies = covers(em.createQuery(
                        "select p from InsurancePolicy p where p.customerName = :name order by p.id desc",
                        InsurancePolicy.class)
                .setParameter("name", account.getName())
                .getResultList(), InsurancePolicy.class, false);
        List<PortfolioViews.CoverView> requests = policies.stream().filter(p -> isRequest(p.status())).toList();
        List<PortfolioViews.ClaimView> claims = em.createQuery(
                        "select c from InsuranceClaim c where c.customerName = :name order by c.id desc", InsuranceClaim.class)
                .setParameter("name", account.getName())
                .getResultStream()
                .map(this::claim)
                .toList();
        return new PortfolioViews.ShopView(customer(account), products(),
                policies.stream().filter(p -> !isRequest(p.status())).toList(), requests, claims);
    }

    private static boolean isRequest(String status) {
        return REQUEST_STATES.contains(status);
    }

    /** The signed-in customer's own profile; staff logins are refused. */
    public CustomerAccount customerForLogin() {
        AppUser user = security.authenticatedUser();
        if (!user.hasRole("Policyholder")) {
            throw new NoAuthorizationException("Only a customer can use the policy shop.");
        }
        List<CustomerAccount> rows = em.createQuery(
                        "select c from CustomerAccount c where c.loginUsername = :username", CustomerAccount.class)
                .setParameter("username", user.getUsername())
                .getResultList();
        if (rows.isEmpty()) {
            throw new NotFoundException("No customer profile is linked to this login.");
        }
        return rows.get(0);
    }

    private static int digits(String value) {
        if (value == null) {
            return 0;
        }
        String number = value.replaceAll("[^0-9]", "");
        return number.isEmpty() ? 0 : Integer.parseInt(number);
    }

    private PortfolioViews.CoverView cover(CoverRecord row) {
        return cover(row, row instanceof TieredCover rated && rated.getRateTableVersion() != null
                ? livesOf(row.getClass(), List.of(row.getId())).getOrDefault(row.getId(), List.of()) : List.of(),
                row instanceof InsurancePolicy ? beneficiariesOf(List.of(row.getId())).getOrDefault(row.getId(), List.of())
                        : List.of());
    }

    private PortfolioViews.CoverView cover(CoverRecord row, List<PortfolioViews.LifeCoverView> lives,
            List<PortfolioViews.BeneficiaryView> beneficiaries) {
        InsurancePolicy policy = row instanceof InsurancePolicy p ? p : null;
        LocalDate today = LocalDate.now();
        return new PortfolioViews.CoverView(
                row.getRef(), row.getCustomerName(), row.getProduct(), row.getAmount(),
                row.getPremium(), row.getStatus(), row.getRecordDate(),
                row instanceof TieredCover tiered && tiered.getTier() != null ? tiered.getTier() : "",
                row instanceof TieredCover rated && rated.getRateTableVersion() != null ? rated.getRateTableVersion() : "",
                lives,
                policy != null ? blank(policy.getReviewedBy()) : "",
                policy != null ? blank(policy.getReviewNote()) : "",
                policy == null ? "" : date(policy.getCoverStartDate()),
                policy == null ? "" : date(policy.getNextPremiumDue()),
                policy == null ? "" : date(policy.getGracePeriodEnd()),
                policy == null || policy.getPaymentDueBy() == null ? "" : policy.getPaymentDueBy().withNano(0).toString(),
                policy == null ? "" : date(policy.getLapseDate()),
                policy == null ? "" : blank(policy.getPaymentMethod()),
                policy != null && PolicyWorkflow.ACTIVE.equals(policy.getStatus()) && policy.getNextPremiumDue() != null
                        && policy.getNextPremiumDue().isBefore(today) && policy.getGracePeriodEnd() != null
                        && !policy.getGracePeriodEnd().isBefore(today),
                beneficiaries);
    }

    private static String date(LocalDate value) {
        return value == null ? "" : value.toString();
    }

    /** Beneficiaries of the given policies, keyed by policy id, in one query. */
    private Map<Long, List<PortfolioViews.BeneficiaryView>> beneficiariesOf(List<Long> policyIds) {
        if (policyIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, List<PortfolioViews.BeneficiaryView>> byPolicy = new HashMap<>();
        for (Object[] row : em.createQuery(
                        "select b.policy.id, b from PolicyBeneficiary b where b.policy.id in :ids"
                                + " order by b.displayOrder, b.id", Object[].class)
                .setParameter("ids", policyIds)
                .getResultList()) {
            PolicyBeneficiary b = (PolicyBeneficiary) row[1];
            byPolicy.computeIfAbsent((Long) row[0], key -> new ArrayList<>()).add(new PortfolioViews.BeneficiaryView(
                    b.getId(), b.getFullName(), b.getRelationship(), blank(b.getNationalId()), blank(b.getMobile()),
                    blank(b.getPayoutMethod()), blank(b.getAccountRef()), b.isPrimary()));
        }
        return byPolicy;
    }

    /** Applications or policies, each with its covered lives, loaded in one query; all of them when complete. */
    private List<PortfolioViews.CoverView> covers(
            List<? extends CoverRecord> rows, Class<? extends CoverRecord> type, boolean complete) {
        Map<Long, List<PortfolioViews.LifeCoverView>> lives = rows.isEmpty() ? Map.of()
                : livesOf(type, complete ? null : rows.stream().map(CoverRecord::getId).toList());
        Map<Long, List<PortfolioViews.BeneficiaryView>> beneficiaries = type == InsurancePolicy.class
                ? beneficiariesOf(rows.stream().map(CoverRecord::getId).toList()) : Map.of();
        return rows.stream().map(row -> cover(row, lives.getOrDefault(row.getId(), List.of()),
                beneficiaries.getOrDefault(row.getId(), List.of()))).toList();
    }

    /** Policy Life Cover rows of applications or policies (only those ids, unless null), keyed by record id. */
    private Map<Long, List<PortfolioViews.LifeCoverView>> livesOf(Class<? extends CoverRecord> type, List<Long> ids) {
        String owner = type == InsurancePolicy.class ? "policy" : type == InsuranceApplication.class ? "application" : null;
        if (owner == null) {
            return Map.of();
        }
        var query = em.createQuery(
                "select l." + owner + ".id, l from PolicyLifeCover l where l." + owner + ".id "
                        + (ids == null ? "is not null" : "in :ids") + " order by l.displayOrder, l.id",
                Object[].class);
        if (ids != null) {
            query.setParameter("ids", ids);
        }
        Map<Long, List<PortfolioViews.LifeCoverView>> byOwner = new HashMap<>();
        for (Object[] row : query.getResultList()) {
            PolicyLifeCover life = (PolicyLifeCover) row[1];
            byOwner.computeIfAbsent((Long) row[0], key -> new ArrayList<>()).add(new PortfolioViews.LifeCoverView(
                    life.getLifeType(), life.getFullName(), life.getDateOfBirth().toString(), life.getAgeAtEntry(),
                    life.getCoverAmount(), life.getMonthlyPremium()));
        }
        return byOwner;
    }

    private static PortfolioViews.LifeCoverView lifeView(RateTableService.PricedLife line) {
        return new PortfolioViews.LifeCoverView(
                line.relationship().getRelationshipName(), line.fullName(), line.dateOfBirth().toString(),
                line.age(), line.coverAmount(), line.monthlyPremium());
    }

    private PortfolioViews.ClaimView claim(InsuranceClaim row) {
        return new PortfolioViews.ClaimView(
                row.getRef(), row.getCustomerName(), row.getProduct(), row.getAmount(), row.getPremium(),
                row.getStatus(), row.getRecordDate(), blank(row.getClaimType()), blank(row.getDescription()),
                blank(row.getEventDate()), blank(row.getPolicyRef()), blank(row.getClaimantName()),
                blank(row.getClaimantMobile()), blank(row.getClaimantRelationship()), blank(row.getProcessingType()),
                blank(row.getStpIneligibleReason()),
                ClaimWorkflow.results(row).entrySet().stream()
                        .map(entry -> new PortfolioViews.CheckView(entry.getKey(), entry.getValue()))
                        .toList(),
                new PortfolioViews.PayeeView(blank(row.getPayeeName()), blank(row.getPayeeRelationship()),
                        blank(row.getPayeeMobile()), blank(row.getPayeePayoutMethod()), blank(row.getPayeeAccountRef()),
                        blank(row.getPayeeSource()), row.isPayoutVerified(), blank(row.getPayoutVerifiedBy())),
                blank(row.getRecommendedBy()), blank(row.getAdjudicatedBy()), row.getFourEyesApplied(),
                blank(row.getRejectionReason()), row.getPayoutAmount(), blank(row.getPayoutReference()),
                row.getPaidAt() == null ? "" : row.getPaidAt().withNano(0).toString(),
                ClaimWorkflow.allowedActions(row.getStatus()),
                ClaimDocumentService.views(em, row.getId()));
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

    private static String blank(String value) {
        return value == null ? "" : value;
    }

    private static List<String> lines(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        return Arrays.stream(value.split("\\R")).map(String::trim).filter(line -> !line.isEmpty()).toList();
    }

    private static String text(JsonNode body, String field) {
        JsonNode node = body.get(field);
        if (node == null || !node.isTextual() || node.asText().isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return node.asText().trim();
    }

    private static String optional(JsonNode body, String field) {
        JsonNode node = body.get(field);
        if (node == null || node.isNull()) {
            return null;
        }
        return node.asText();
    }

    /** A money field sent as a number or numeric text; zero when absent. */
    private static BigDecimal amount(JsonNode body, String field) {
        JsonNode node = body.get(field);
        if (node == null || node.isNull() || node.asText().isBlank()) {
            return BigDecimal.ZERO;
        }
        try {
            return new BigDecimal(node.asText().trim()).setScale(2, RoundingMode.HALF_UP);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(field + " must be an amount like 120.50.");
        }
    }

    private static String defaultText(JsonNode body, String field, String fallback) {
        JsonNode node = body.get(field);
        if (node == null || node.isNull() || !node.isTextual() || node.asText().isBlank()) {
            return fallback;
        }
        return node.asText().trim();
    }
}
