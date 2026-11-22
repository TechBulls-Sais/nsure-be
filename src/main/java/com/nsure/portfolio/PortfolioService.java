package com.nsure.portfolio;

import com.fasterxml.jackson.databind.JsonNode;
import com.nsure.common.Money;
import com.nsure.common.exception.NotFoundException;
import com.nsure.configuration.PolicyAttributes;
import com.nsure.rating.RateTableService;
import com.nsure.rating.RateTableViews;
import com.nsure.security.domain.AppUser;
import com.nsure.security.domain.Role;
import com.nsure.security.exception.NoAuthorizationException;
import com.nsure.security.otp.OneTimeCodes;
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
    private final ClaimFinancials claimFinancials;
    private final QuoteService quotes;
    private final ApplicationCapture capture;
    private final com.fasterxml.jackson.databind.ObjectMapper mapper;
    private final com.nsure.integration.Outbox outbox;
    private final WorkflowHistory history;
    private final PolicyAttributes policyAttributes;
    private final ReferenceNumbers references;
    private final DeclarationService declarations;
    private final ProductConfigHistory configHistory;
    /** Lazily resolved: the payment module depends on the portfolio, not the other way round. */
    private final org.springframework.beans.factory.ObjectProvider<com.nsure.payment.PaymentSettlementService> settlement;
    /** IL02: every submitted application is underwritten before it can be issued. */
    private final org.springframework.beans.factory.ObjectProvider<com.nsure.underwriting.UnderwritingService> underwriting;
    /** Customer sign-up codes: per request, expiring, attempt-limited; the fixed demo code only in dev/demo. */
    private final OneTimeCodes codes;
    /**
     * NS-IL01-005/006: whether staff may issue a policy without an underwriting-cleared application. Off in every
     * installation (application.yml); only legacy loads and older tests that predate applications switch it on.
     */
    @org.springframework.beans.factory.annotation.Value("${nsure.new-business.direct-issue-enabled:true}")
    private boolean directIssueEnabled;
    /** NS-IL01-003: whether a portal purchase must carry the product's mandatory disclosures and the declaration. */
    @org.springframework.beans.factory.annotation.Value("${nsure.new-business.portal-disclosures-required:false}")
    private boolean portalDisclosuresRequired;
    /** Days after submission an application's own evidence is due (demo default; IL01 missing-evidence queue). */
    @org.springframework.beans.factory.annotation.Value("${nsure.new-business.evidence-due-days:14}")
    private int evidenceDueDays;

    public PortfolioService(
            EntityManager em,
            PlatformSecurityContext security,
            AppUserRepository users,
            RoleRepository roles,
            PasswordEncoder passwords,
            RateTableService rates,
            PolicyWorkflow policyWorkflow,
            ClaimWorkflow claimWorkflow,
            WorkflowHistory history,
            PolicyAttributes policyAttributes,
            ReferenceNumbers references,
            org.springframework.beans.factory.ObjectProvider<com.nsure.payment.PaymentSettlementService> settlement,
            DeclarationService declarations, ClaimFinancials claimFinancials, QuoteService quotes,
            com.fasterxml.jackson.databind.ObjectMapper mapper, com.nsure.integration.Outbox outbox,
            ApplicationCapture capture, ProductConfigHistory configHistory,
            org.springframework.beans.factory.ObjectProvider<com.nsure.underwriting.UnderwritingService> underwriting,
            OneTimeCodes codes) {
        this.codes = codes;
        this.underwriting = underwriting;
        this.configHistory = configHistory;
        this.capture = capture;
        this.mapper = mapper;
        this.outbox = outbox;
        this.quotes = quotes;
        this.claimFinancials = claimFinancials;
        this.references = references;
        this.declarations = declarations;
        this.settlement = settlement;
        this.em = em;
        this.security = security;
        this.users = users;
        this.roles = roles;
        this.passwords = passwords;
        this.rates = rates;
        this.policyWorkflow = policyWorkflow;
        this.claimWorkflow = claimWorkflow;
        this.history = history;
        this.policyAttributes = policyAttributes;
    }

    /**
     * Party search (SH01 workspace): customers whose name, reference, identity number, email or phone contains the
     * term (case and spacing ignored); all customers without one. At most 200 matches for a search.
     */
    @Transactional(readOnly = true)
    public List<PortfolioViews.CustomerView> customers(String search) {
        String term = search == null ? "" : search.trim().toLowerCase(Locale.ROOT);
        List<CustomerAccount> rows;
        if (term.isEmpty()) {
            rows = list(CustomerAccount.class);
        } else {
            String digits = term.replaceAll("\\D", "");
            boolean phone = digits.length() >= 3;
            jakarta.persistence.TypedQuery<CustomerAccount> query = em.createQuery(
                            "select c from CustomerAccount c where lower(c.name) like :term"
                            + " or lower(c.ref) like :term or lower(c.email) like :term"
                            + " or lower(replace(c.poiNumber, ' ', '')) like :compact"
                            + (phone ? " or replace(replace(c.phone, ' ', ''), '+', '') like :digits" : "")
                            + " order by c.displayOrder asc, c.id desc", CustomerAccount.class)
                    .setParameter("term", "%" + term + "%")
                    .setParameter("compact", "%" + term.replace(" ", "") + "%")
                    .setMaxResults(200);
            if (phone) {
                query.setParameter("digits", "%" + digits + "%");
            }
            rows = query.getResultList();
        }
        // Linked by customer reference, never by name: two customers may share a name (AT-SH11-01).
        Map<String, List<PortfolioViews.CustomerTierView>> tiers = new HashMap<>();
        for (InsurancePolicy policy : em.createQuery(
                "select p from InsurancePolicy p where p.customerRef is not null order by p.id desc",
                InsurancePolicy.class).getResultList()) {
            tiers.computeIfAbsent(policy.getCustomerRef(), ref -> new ArrayList<>()).add(customerTier(policy));
        }
        return rows.stream()
                .map(row -> customer(row, tiers.getOrDefault(row.getRef(), List.of())))
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
                        scheme.getMembers(), scheme.getPremium(), scheme.getRenewal(), scheme.getColor(),
                        scheme.getStatus() == null ? "" : scheme.getStatus()))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<PortfolioViews.CoverView> receipts() {
        return list(PremiumReceipt.class).stream().map(row -> cover(row, List.of(), List.of(), List.of())).toList();
    }

    @Transactional(readOnly = true)
    public List<PortfolioViews.ConfigView> productConfigs() {
        return list(ProductConfiguration.class).stream().map(this::config).toList();
    }

    /**
     * Identity rules and the duplicate check (NS-SH01-001/002). Field-injected so the long constructor above stays
     * as it is.
     */
    @org.springframework.beans.factory.annotation.Autowired
    private PartyRegister parties;

    @Transactional
    public PortfolioViews.CustomerView createCustomer(JsonNode body) {
        AppUser staff = security.authenticatedUser();
        staff.validateHasPermissionTo("CREATE_CUSTOMER");
        return customer(newCustomer(body, staff.getUsername()));
    }

    /**
     * A new customer record. Staff (actor given) may keep a record whose identity number another customer holds
     * only with a reason, and it is referred for duplicate review; self sign-up (no actor) never gets that far.
     */
    private CustomerAccount newCustomer(JsonNode body, String staffActor) {
        CustomerAccount row = new CustomerAccount();
        row.setRef(nextRef("CUS-", 4, CustomerAccount.class));
        row.setName(text(body, "name"));
        row.setEmail(text(body, "email"));
        row.setPhone(text(body, "phone"));
        PartyRegister.Identity identity = parties.identity(optional(body, "poiType"), optional(body, "poiNumber"));
        if (identity != null) {
            row.setPoiType(identity.type());
            row.setPoiNumber(identity.number());
        }
        if (staffActor == null) {
            parties.refuseKnownIdentity(identity);
        } else {
            row.setDuplicateReview(parties.referral(identity, optional(body, "duplicateReason")));
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
        parties.created(row, staffActor == null ? row.getLoginUsername() : staffActor,
                staffActor == null ? "SELF_SIGNUP" : "STAFF");
        return row;
    }

    // ── customer self sign-up (public) ────────────────────────────────────────────────────────────

    /**
     * What the customer gets back when a sign-up code is on its way: the challenge to send with the code, and in a
     * dev or demo installation only, the demo code to type ({@link OneTimeCodes}).
     */
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    public record SignupCodeView(String sentTo, String email, String challenge, java.time.LocalDateTime expiresAt,
            int attemptsAllowed, String demoCode) {
    }

    /**
     * Step 1 of sign-up: check the details before sending the code, so the customer fixes them first. Each request
     * gets its own code, bound to the phone it was sent to. With no messaging provider outside dev/demo, sign-up is
     * refused here.
     */
    @Transactional
    public SignupCodeView requestSignupOtp(JsonNode body) {
        checkSignupDetails(body);
        String phone = text(body, "phone");
        String digits = phone.replaceAll("\\D", "");
        OneTimeCodes.Issued issued = codes.issue(OneTimeCodes.Purpose.CUSTOMER_SIGNUP, digits, phone);
        String tail = digits.length() > 3 ? digits.substring(digits.length() - 3) : digits;
        return new SignupCodeView("•••• " + tail, text(body, "email"), issued.challenge(), issued.expiresAt(),
                issued.attemptsAllowed(), issued.demoCode());
    }

    /**
     * Step 2 of sign-up: the code for this request's challenge must match, for the same phone it was sent to; then
     * the customer is created exactly as staff would add them.
     */
    @Transactional
    public PortfolioViews.CustomerView signUp(JsonNode body) {
        checkSignupDetails(body);
        String digits = text(body, "phone").replaceAll("\\D", "");
        codes.verify(OneTimeCodes.Purpose.CUSTOMER_SIGNUP, optional(body, "challenge"), optional(body, "otp"), digits);
        return customer(newCustomer(body, null));
    }

    /** Self sign-up needs every field the staff Add customer form requires, including the sign-in details. */
    private void checkSignupDetails(JsonNode body) {
        String name = text(body, "name");
        if (name.length() < 2) {
            throw new IllegalArgumentException("Full name must be at least 2 characters.");
        }
        if (!text(body, "email").matches("[^@\\s]+@[^@\\s]+\\.[^@\\s]+")) {
            throw new IllegalArgumentException("Enter a valid email address.");
        }
        text(body, "phone");
        // The number must fit its document type, and one already registered is refused before a code is sent.
        parties.refuseKnownIdentity(parties.identity(text(body, "poiType"), text(body, "poiNumber")));
        text(body, "dateOfBirth");
        String username = text(body, "username").toLowerCase();
        if (!username.matches("[a-z0-9._-]{3,100}")) {
            throw new IllegalArgumentException("Username must be 3–100 letters, numbers, dots, or hyphens.");
        }
        String password = optional(body, "password");
        if (password == null || password.length() < 8) {
            throw new IllegalArgumentException("Password must be at least 8 characters.");
        }
        if (users.findByUsername(username).isPresent()) {
            throw new IllegalArgumentException("Username " + username + " is already in use.");
        }
    }

    /**
     * An application is taken up on an accepted quote (NS-IL01-001, NS-SH03-003): the one named in quoteRef, or a
     * quote priced now from the application's inputs and accepted with it. The application keeps that quote's price.
     */
    @Transactional
    public PortfolioViews.CoverView createApplication(JsonNode body) {
        AppUser actor = security.authenticatedUser();
        InsuranceApplication row = new InsuranceApplication();
        CustomerAccount customer = customerFrom(body);
        fillCover(row, body, customer, "APP-", 4, "Under review");
        String quoteRef = optional(body, "quoteRef");
        Quote quote = null;
        List<RateTableService.PricedLife> lives;
        if (quoteRef != null && !quoteRef.isBlank()) {
            quote = quotes.requireIssuable(quoteRef.trim(), null);
            if (!quote.getCustomerRef().equals(customer.getRef())) {
                throw new IllegalArgumentException(quote.getRef() + " was quoted for another customer.");
            }
            if (quote.getApplicationRef() != null) {
                throw new com.nsure.common.exception.ConflictException(quote.getRef() + " was already taken up on "
                        + quote.getApplicationRef() + ".");
            }
            applyQuote(row, quote);
            lives = quotes.pricedLives(quote);
        } else {
            // NS-IL01-004: the server prices every application from the product's tier; a premium sent with it is
            // never taken as the price (A-33).
            if (blank(optional(body, "tier")).isBlank()) {
                throw new IllegalArgumentException("Choose a tier for " + row.getProduct() + ": an application is"
                        + " priced from the product's tiers, not from a premium sent with it.");
            }
            QuoteService.PricedOffer offer = applyTierOffer(row, body);
            lives = offer == null ? List.of() : offer.lines();
            if (offer != null) {
                quote = quotes.record(customer, row.getProduct(), offer, actor.getUsername());
                // A draft's quote is only priced: the applicant accepts it when the application is submitted with
                // the declaration, not before (A-16).
                if (!draftRequested(body)) {
                    acceptWithApplication(quote, actor);
                }
            }
        }
        boolean draft = draftRequested(body);
        capture.capture(row, body, actor.getUsername());
        capture.answer(row, body, actor.getUsername());
        if (draft) {
            row.setStatus(ApplicationCapture.DRAFT);
        }
        em.persist(row);
        saveLives(row, lives);
        if (quote != null) {
            row.setQuote(quote.getRef(), quote.getVersionNo());
            quotes.linkApplication(quote, row.getRef());
        }
        if (!draft) {
            // NS-IL01-003: submission needs every mandatory answer, the declaration, a payer and a usable mandate.
            capture.submit(row, customer, actor.getUsername());
            // NS-IL01-005: the rules accept an eligible case; anything else is referred to an underwriter.
            underwriting.getObject().assess(row, customer, actor.getUsername());
        }
        logApplication(row, draft ? "Saved as draft" : "Submitted", null,
                quote == null ? null : "Priced on " + quote.getRef() + " v" + quote.getVersionNo(), actor.getUsername());
        em.flush(); // the returned row version is the stored one (If-Match on the next change)
        return cover(row);
    }

    private static boolean sameAmount(BigDecimal a, BigDecimal b) {
        return a == null ? b == null : b != null && a.compareTo(b) == 0;
    }

    private static boolean draftRequested(JsonNode body) {
        return ApplicationCapture.DRAFT.equalsIgnoreCase(blank(optional(body, "status")));
    }

    /** The quote priced with an application is accepted when the application is submitted (NS-IL01-001). */
    private void acceptWithApplication(Quote quote, AppUser actor) {
        quotes.accept(quote.getRef(), quote.getVersionNo(), actor.getUsername(),
                "Accepted with the application, recorded by " + actor.getUsername());
    }

    /** One step on the application's timeline (IL01 workspace): what happened and the status it moved between. */
    private void logApplication(InsuranceApplication app, String action, String previous, String details, String actor) {
        history.record(ApplicationCapture.HISTORY, app.getRef(), app.getCustomerName(), action, previous,
                app.getStatus(), details, actor);
    }

    /** An application not yet issued takes the accepted version's price and lives. */
    private void takeQuote(InsuranceApplication application, Quote quote) {
        applyQuote(application, quote);
        em.createQuery("delete from PolicyLifeCover l where l.application = :app")
                .setParameter("app", application).executeUpdate();
        saveLives(application, quotes.pricedLives(quote));
        application.setQuote(quote.getRef(), quote.getVersionNo());
    }

    /** Prices a record exactly as the quote did: its tier, cover, premium and rate version. */
    private <T extends CoverRecord & TieredCover> void applyQuote(T row, Quote quote) {
        row.setProduct(quote.getProduct());
        row.setTier(quote.getTier());
        row.setAmount(quote.getSumAssured());
        row.setPremium(quote.getPremium());
        row.setRateTableVersion(Quote.RATE_TABLE.equals(quote.getPricingMethod()) ? quote.getRateVersion() : null);
    }

    // ── application capture (NS-IL01-002/003) ─────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public PortfolioViews.ApplicationChecklistView applicationChecklist(String ref) {
        security.authenticatedUser().validateHasPermissionTo("READ_APPLICATION");
        InsuranceApplication app = one(InsuranceApplication.class, ref, "Application");
        return capture.checklist(app, customerOf(app));
    }

    /**
     * IL01 missing-evidence queue: every item still outstanding on an open application, from its own checklist (due
     * {@code nsure.new-business.evidence-due-days} after submission, a demo default) and from its underwriting case
     * (the underwriter's due date), oldest due first.
     */
    @Transactional(readOnly = true)
    public List<PortfolioViews.EvidenceQueueItem> evidenceQueue() {
        AppUser user = security.authenticatedUser();
        user.validateHasPermissionTo("READ_APPLICATION");
        LocalDate today = LocalDate.now();
        List<PortfolioViews.EvidenceQueueItem> rows = new ArrayList<>();
        List<InsuranceApplication> open = em.createQuery("select a from InsuranceApplication a where a.status in :open"
                        + " order by a.id", InsuranceApplication.class)
                .setParameter("open", List.of(ApplicationCapture.UNDER_REVIEW, ApplicationCapture.EVIDENCE_NEEDED,
                        "Offered", "Ready to issue"))
                .getResultList();
        Map<String, InsuranceApplication> byRef = new HashMap<>();
        for (InsuranceApplication app : open) {
            byRef.put(app.getRef(), app);
            CustomerAccount customer = customerOf(app);
            LocalDate due = (app.getSubmittedAt() == null ? today : app.getSubmittedAt().toLocalDate())
                    .plusDays(evidenceDueDays);
            for (String item : capture.missingEvidence(app, customer)) {
                rows.add(new PortfolioViews.EvidenceQueueItem(app.getRef(), app.getCustomerName(), app.getProduct(),
                        app.getStatus(), "APPLICATION", item, app.getCustomerName(), due, due.isBefore(today), null, 0,
                        app.getSubmittedAt()));
            }
        }
        boolean clinical = user.hasPermissionTo("UNDERWRITE_APPLICATION");
        if (user.hasPermissionTo("READ_UNDERWRITING")) {
            for (Map<String, Object> e : underwriting.getObject().outstandingEvidenceItems()) {
                InsuranceApplication app = byRef.get((String) e.get("applicationRef"));
                if (app == null) {
                    continue;
                }
                boolean restricted = Boolean.TRUE.equals(e.get("restricted"));
                LocalDate due = (LocalDate) e.get("dueDate");
                rows.add(new PortfolioViews.EvidenceQueueItem(app.getRef(), app.getCustomerName(), app.getProduct(),
                        app.getStatus(), "UNDERWRITING", restricted && !clinical ? "Restricted medical evidence"
                        : (String) e.get("requirement"), (String) e.get("lifeName"), due, due.isBefore(today),
                        (String) e.get("caseRef"), (Integer) e.get("reminders"), app.getSubmittedAt()));
            }
        }
        rows.sort(java.util.Comparator.comparing(PortfolioViews.EvidenceQueueItem::dueDate));
        return rows;
    }

    /** The policy terms a product sells (section 06 Term Life); empty when it sells none. */
    @Transactional(readOnly = true)
    public List<Integer> applicationTerms(String product) {
        AppUser user = security.authenticatedUser();
        if (user.isPortalCustomer()) {
            customerForLogin();
        } else if (!user.hasPermissionTo("CREATE_APPLICATION")) {
            user.validateHasPermissionTo("READ_APPLICATION");
        }
        return capture.termOptions(product);
    }

    @Transactional(readOnly = true)
    public List<String> applicationRelationships() {
        AppUser user = security.authenticatedUser();
        if (!user.hasPermissionTo("CREATE_APPLICATION")) {
            user.validateHasPermissionTo("READ_APPLICATION");
        }
        return capture.relationships();
    }

    @Transactional(readOnly = true)
    public List<PortfolioViews.DisclosureItem> applicationQuestions(String product) {
        AppUser user = security.authenticatedUser();
        if (!user.hasPermissionTo("CREATE_APPLICATION")) {
            user.validateHasPermissionTo("READ_APPLICATION");
        }
        return capture.questionsFor(product);
    }

    /** Completes a draft: proposer, payer, mandate, channel, answers and the declaration. */
    @Transactional
    public PortfolioViews.ApplicationChecklistView updateApplicationDetails(String ref, JsonNode body) {
        AppUser actor = security.authenticatedUser();
        actor.validateHasPermissionTo("UPDATE_APPLICATION");
        InsuranceApplication app = one(InsuranceApplication.class, ref, "Application");
        com.nsure.common.ExpectedVersion.check("Application " + ref, app.getRowVersion());
        if (!ApplicationCapture.DRAFT.equals(app.getStatus())) {
            throw new IllegalArgumentException(ref + " was submitted; its answers and declaration can no longer change.");
        }
        capture.capture(app, body, actor.getUsername());
        capture.answer(app, body, actor.getUsername());
        logApplication(app, "Details updated", app.getStatus(),
                app.getDeclaredAt() == null ? null : "Declaration accepted", actor.getUsername());
        em.flush();
        return capture.checklist(app, customerOf(app));
    }

    @Transactional
    public PortfolioViews.ApplicationChecklistView submitApplication(String ref) {
        AppUser actor = security.authenticatedUser();
        if (!actor.hasPermissionTo("CREATE_APPLICATION")) {
            actor.validateHasPermissionTo("UPDATE_APPLICATION");
        }
        InsuranceApplication app = one(InsuranceApplication.class, ref, "Application");
        com.nsure.common.ExpectedVersion.check("Application " + ref, app.getRowVersion());
        if (!ApplicationCapture.DRAFT.equals(app.getStatus())) {
            throw new com.nsure.common.exception.ConflictException(ref + " is already " + app.getStatus() + ".");
        }
        CustomerAccount customer = customerOf(app);
        capture.submit(app, customer, actor.getUsername());
        // The draft's priced quote is accepted now, with the declaration; one accepted meanwhile stays as it is.
        if (app.getQuoteRef() != null) {
            Quote quote = quotes.latest(app.getQuoteRef());
            if (Quote.QUOTED.equals(quote.getStatus())) {
                acceptWithApplication(quote, actor);
                if (app.getQuoteVersion() == null || quote.getVersionNo() != app.getQuoteVersion()) {
                    takeQuote(app, quote);
                }
            }
        }
        underwriting.getObject().assess(app, customer, actor.getUsername());
        logApplication(app, "Submitted", ApplicationCapture.DRAFT, null, actor.getUsername());
        em.flush();
        return capture.checklist(app, customer);
    }

    /** {documentType, note}: evidence received and checked. */
    @Transactional
    public PortfolioViews.ApplicationChecklistView recordApplicationEvidence(String ref, JsonNode body) {
        AppUser actor = security.authenticatedUser();
        actor.validateHasPermissionTo("UPDATE_APPLICATION");
        InsuranceApplication app = one(InsuranceApplication.class, ref, "Application");
        com.nsure.common.ExpectedVersion.check("Application " + ref, app.getRowVersion());
        String before = app.getStatus();
        CustomerAccount customer = customerOf(app);
        capture.recordEvidence(app, customer, text(body, "documentType"), optional(body, "note"), actor.getUsername());
        // The last piece of evidence on an application underwriting has already cleared makes it ready to issue.
        if (ApplicationCapture.UNDER_REVIEW.equals(app.getStatus()) && underwriting.getObject().isCleared(app.getRef())) {
            app.setStatus("Ready to issue");
        }
        logApplication(app, "Evidence received", before, text(body, "documentType"), actor.getUsername());
        em.flush();
        return capture.checklist(app, customer);
    }

    // ── quotes (NS-IL01-001/004) ──────────────────────────────────────────────────────────────────

    /** A stored quote for a customer: priced now, valid for the configured days, not yet accepted. */
    @Transactional
    public PortfolioViews.QuoteRecordView createQuote(JsonNode body) {
        AppUser actor = staffQuoter();
        CustomerAccount customer = customerFrom(body);
        String product = text(body, "product");
        return quotes.view(quotes.record(customer, product, offerFor(customer, product, text(body, "tier"), body),
                actor.getUsername()));
    }

    @Transactional(readOnly = true)
    public List<PortfolioViews.QuoteRecordView> quotes(String customerRef) {
        staffReader();
        return quotes.latestQuotes(customerRef).stream().map(quotes::view).toList();
    }

    /** Every version of a quote, newest first; earlier offers stay readable (AT-IL01-01). */
    @Transactional(readOnly = true)
    public List<PortfolioViews.QuoteRecordView> quoteVersions(String ref) {
        staffReader();
        return quotes.versions(ref).stream().map(quotes::view).toList();
    }

    /**
     * The customer accepted the latest offer. When the quote was taken up on an application that is not yet issued,
     * the application takes the accepted price and lives.
     */
    @Transactional
    public PortfolioViews.QuoteRecordView acceptQuote(String ref, JsonNode body) {
        AppUser actor = staffQuoter();
        Integer version = body != null && body.hasNonNull("version") ? body.get("version").asInt() : null;
        String note = optional(body, "note");
        Quote quote = quotes.accept(ref, version, actor.getUsername(), note == null || note.isBlank()
                ? "Customer's acceptance recorded by " + actor.getUsername() : note.trim());
        if (quote.getApplicationRef() != null) {
            InsuranceApplication application = one(InsuranceApplication.class, quote.getApplicationRef(), "Application");
            if (!"Issued".equals(application.getStatus())) {
                BigDecimal premiumBefore = application.getPremium();
                BigDecimal amountBefore = application.getAmount();
                takeQuote(application, quote);
                logApplication(application, "Quote accepted", application.getStatus(),
                        quote.getRef() + " v" + quote.getVersionNo(), actor.getUsername());
                // NS-IL01-004, NS-IL02-006: underwriting decided on the earlier price; a changed price or cover is
                // reassessed (never the earlier offer's premium silently applied to the new quote).
                if (!sameAmount(premiumBefore, application.getPremium()) || !sameAmount(amountBefore, application.getAmount())) {
                    underwriting.getObject().materialChange(application, customerOf(application), quote.getRef() + " v"
                            + quote.getVersionNo() + " accepted with a changed price or cover", actor.getUsername());
                }
            }
        }
        return quotes.view(quote);
    }

    /** Prices the quote again from the current facts and rates: the next version, with what changed (NS-IL01-004). */
    @Transactional
    public PortfolioViews.QuoteRecordView requote(String ref) {
        AppUser actor = staffQuoter();
        Quote previous = quotes.latest(ref);
        CustomerAccount customer = em.createQuery("select c from CustomerAccount c where c.ref = :ref", CustomerAccount.class)
                .setParameter("ref", previous.getCustomerRef()).getResultStream().findFirst()
                .orElseThrow(() -> new NotFoundException("Customer " + previous.getCustomerRef() + " not found"));
        com.fasterxml.jackson.databind.node.ObjectNode facts = mapper.createObjectNode();
        com.fasterxml.jackson.databind.node.ArrayNode others = facts.putArray("lives");
        JsonNode inputs;
        try {
            inputs = mapper.readTree(previous.getInputsJson());
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            throw new IllegalStateException(ex);
        }
        // The main insured is priced from the customer record as it is now; the other lives as quoted.
        for (int i = 1; i < inputs.path("lives").size(); i++) {
            others.add(inputs.path("lives").get(i));
        }
        QuoteService.PricedOffer offer = offerFor(customer, previous.getProduct(), previous.getTier(), facts);
        return quotes.view(quotes.revise(previous, customer, offer, actor.getUsername()));
    }

    /** Prices a stored version again under its own rate version and date (NS-SH03-005). */
    @Transactional(readOnly = true)
    public PortfolioViews.QuoteReplayView replayQuote(String ref, Integer version) {
        staffReader();
        return quotes.replay(version == null ? quotes.latest(ref) : quotes.version(ref, version));
    }

    private QuoteService.PricedOffer offerFor(CustomerAccount customer, String product, String tierName, JsonNode body) {
        CoverTier tier = tier(product, tierName);
        List<RateTableService.LifeToPrice> others = lives(body);
        Optional<RateTableService.PolicyPrice> price = price(tier, customer, others);
        requireFlatTierAge(tier, customer, price);
        List<RateTableService.LifeToPrice> all = new ArrayList<>();
        all.add(new RateTableService.LifeToPrice(selfRelationship(), customer.getName(), customer.getDateOfBirth()));
        all.addAll(others);
        return new QuoteService.PricedOffer(tier, all, price);
    }

    private AppUser staffQuoter() {
        AppUser user = security.authenticatedUser();
        if (user.isPortalCustomer()) {
            throw new NoAuthorizationException("Customers see their price in the policy shop.");
        }
        if (!user.hasPermissionTo("CREATE_APPLICATION")) {
            user.validateHasPermissionTo("CREATE_POLICY");
        }
        return user;
    }

    private AppUser staffReader() {
        AppUser user = security.authenticatedUser();
        if (!user.hasPermissionTo("READ_APPLICATION")) {
            user.validateHasPermissionTo("READ_POLICY");
        }
        return user;
    }

    @Transactional
    public PortfolioViews.CoverView updateApplication(String ref, JsonNode body) {
        InsuranceApplication row = one(InsuranceApplication.class, ref, "Application");
        if (!body.hasNonNull("status")) {
            return cover(row);
        }
        com.nsure.common.ExpectedVersion.check("Application " + ref, row.getRowVersion());
        String before = row.getStatus();
        // The application's state follows its workflow; only these two moves are made by hand.
        String status = body.get("status").asText().trim();
        if ("Issued".equalsIgnoreCase(row.getStatus())) {
            throw new com.nsure.common.exception.ConflictException(ref + " is already issued.");
        }
        if ("Ready to issue".equals(status)) {
            CustomerAccount customer = customerOf(row);
            capture.requireIssuable(row, customer);
            // NS-IL02-006: only an application underwriting has cleared can be made ready to issue.
            underwriting.getObject().clearedTerms(row, customer, security.authenticatedUser().getUsername());
            row.setStatus("Ready to issue");
        } else if ("Withdrawn".equals(status)) {
            row.setStatus("Withdrawn");
        } else {
            throw new IllegalArgumentException("An application can only be moved to Ready to issue or Withdrawn here;"
                    + " its other states come from submission, underwriting and issue.");
        }
        logApplication(row, "Status changed", before, null, security.authenticatedUser().getUsername());
        em.flush();
        return cover(row);
    }

    /**
     * Staff issue an offer (Nsure Life NS-IL01-006): the policy starts Pending Payment for a KYC-verified customer whose
     * due diligence is clear, priced on the server, and goes Active only when its first premium is recorded, through
     * the same path as every payment (PolicyIssued, schedule, commission and messages follow). A client-supplied
     * status is ignored. Issued from an application, that application is marked Issued in the same transaction and
     * cannot be issued twice; a repeated submit with the same Idempotency-Key returns the same policy.
     */
    @Transactional
    public PortfolioViews.CoverView createPolicy(JsonNode body, String idempotencyKey) {
        return issue(body, idempotencyKey, false);
    }

    /**
     * IL05: a lender's loan submission opens its credit life policy (an offer until the first premium). It is
     * assessed by the credit product rule (entry and cessation ages, term, benefits) instead of an application, and
     * only for a product distributed through lenders.
     */
    @Transactional
    public PortfolioViews.CoverView createLoanPolicy(JsonNode body, String idempotencyKey) {
        String scope = capture.distributionProblem(text(body, "product"), "LENDER");
        if (scope != null) {
            throw new IllegalArgumentException(scope);
        }
        return issue(body, idempotencyKey, true);
    }

    private PortfolioViews.CoverView issue(JsonNode body, String idempotencyKey, boolean lenderLoan) {
        AppUser staff = security.authenticatedUser();
        String key = idempotencyKey == null || idempotencyKey.isBlank() ? null : idempotencyKey.trim();
        if (key != null) {
            Optional<InsurancePolicy> earlier = em.createQuery(
                            "select p from InsurancePolicy p where p.issueKey = :key", InsurancePolicy.class)
                    .setParameter("key", key).getResultStream().findFirst();
            if (earlier.isPresent()) {
                // A repeat of the same request gets the same policy; the key on a different request is a conflict,
                // never silently the earlier policy (§10 issue contract).
                requireSameIssue(earlier.get(), body, key);
                return cover(earlier.get());
            }
        }
        InsuranceApplication application = null;
        String applicationRef = optional(body, "applicationRef");
        if (applicationRef != null && !applicationRef.isBlank()) {
            application = one(InsuranceApplication.class, applicationRef.trim(), "Application");
            em.lock(application, jakarta.persistence.LockModeType.PESSIMISTIC_WRITE);
            // NS-TXN-001: issued from the application as the caller last saw it (If-Match), if they say which.
            com.nsure.common.ExpectedVersion.check("Application " + application.getRef(), application.getRowVersion());
        }
        if (application == null && !directIssueEnabled && !lenderLoan) {
            // NS-IL01-005/006: every policy comes from an application the underwriting rules or an underwriter cleared.
            throw new IllegalArgumentException("Policies are issued from an application that underwriting has cleared."
                    + " Create and submit an application for this customer, then issue it when it is Ready to issue.");
        }
        // Issued from an application, the customer is the application's; otherwise the request names them.
        boolean named = !blank(optional(body, "customerRef")).isBlank() || !blank(optional(body, "name")).isBlank();
        CustomerAccount account = application != null && !named ? customerOf(application) : customerFrom(body);
        if (!"VERIFIED".equals(account.getKycStatus())) {
            throw new IllegalArgumentException("Mark KYC successful before adding a policy.");
        }
        requireEddClear(account);
        if (application != null) {
            if (!account.getRef().equals(application.getCustomerRef())) {
                throw new IllegalArgumentException(application.getRef() + " belongs to another customer.");
            }
            if ("Issued".equalsIgnoreCase(application.getStatus())) {
                String issued = em.createQuery("select p.ref from InsurancePolicy p where p.applicationRef = :app",
                                String.class)
                        .setParameter("app", application.getRef()).getResultStream().findFirst().orElse("a policy");
                throw new com.nsure.common.exception.ConflictException(
                        application.getRef() + " is already issued as " + issued + ".");
            }
            if (Set.of("Declined", "Rejected", "Withdrawn").contains(application.getStatus())) {
                throw new IllegalArgumentException(application.getRef() + " is " + application.getStatus()
                        + " and cannot be issued.");
            }
        }
        if (application != null && !body.hasNonNull("product") && body instanceof com.fasterxml.jackson.databind.node.ObjectNode node) {
            // Issued from an application, the product is the application's unless the request repeats it.
            node.put("product", application.getProduct());
        }
        InsurancePolicy row = new InsurancePolicy();
        fillCover(row, body, account, "POL-", 5, PolicyWorkflow.PENDING_PAYMENT);
        com.nsure.underwriting.UnderwritingService.Terms underwritten = null;
        if (application != null) {
            capture.requireIssuable(application, account);
            // NS-IL02-006: no issue while underwriting, its evidence or an unaccepted offer is still open.
            underwritten = underwriting.getObject().clearedTerms(application, account, staff.getUsername());
        }
        // NS-SH03-003, AT-IL01-01: an application issues at its accepted, still-valid quote, never re-priced.
        Quote quote = application != null && application.getQuoteRef() != null
                ? quotes.requireIssuable(application.getQuoteRef(), application.getQuoteVersion()) : null;
        requireAcceptedOffer(body, application, quote);
        List<RateTableService.PricedLife> lives;
        if (quote != null) {
            applyQuote(row, quote);
            lives = quotes.pricedLives(quote);
        } else {
            lives = applyTier(row, body);
        }
        if (underwritten != null && underwritten.description() != null) {
            // NS-IL02-005: the accepted offer's loading and exclusions travel onto the contract.
            if (underwritten.premium() != null) {
                row.setPremium(underwritten.premium());
            }
            row.setUwTerms(underwritten.description());
        }
        if (application != null) {
            // Section 06 Term Life: the policy carries the term selected on the application.
            row.setTermYears(capture.termFor(application));
        }
        // Staff issue: every required insurer-configured field must be present (guide §15).
        policyAttributes.pinNewPolicy(row, body, true);
        policyWorkflow.openPending(row);
        row.setIssueKey(key);
        row.setApplicationRef(application == null ? null : application.getRef());
        em.persist(row);
        saveLives(row, lives);
        if (quote != null) {
            row.setQuote(quote.getRef(), quote.getVersionNo());
            quotes.linkPolicy(quote, row.getRef());
        }
        policyWorkflow.logOpened(row, staff.getUsername());
        if (application != null) {
            // NS-IL01-002: the beneficiaries nominated on the application are the policy's from the start.
            for (PolicyWorkflow.BeneficiaryInput nominated : capture.beneficiaries(application)) {
                policyWorkflow.addBeneficiary(row.getRef(), nominated, staff.getUsername());
            }
            String before = application.getStatus();
            application.setStatus("Issued");
            logApplication(application, "Issued", before, "Policy offer " + row.getRef() + " awaits its first premium",
                    staff.getUsername());
            // IL01: the issue of an application is requested; cover itself follows the first premium (PolicyIssued).
            Map<String, Object> requested = new java.util.LinkedHashMap<>();
            requested.put("applicationRef", application.getRef());
            requested.put("policyRef", row.getRef());
            requested.put("customerRef", account.getRef());
            requested.put("product", row.getProduct());
            requested.put("quoteRef", quote == null ? "" : quote.getRef());
            requested.put("quoteVersion", quote == null ? 0 : quote.getVersionNo());
            outbox.publish(com.nsure.integration.Events.ISSUE_REQUESTED, application.getRef(), requested,
                    staff.getUsername());
        }
        em.flush();
        return cover(row);
    }

    /** The same Idempotency-Key must carry the same request: application, customer, product and tier. */
    private static void requireSameIssue(InsurancePolicy earlier, JsonNode body, String key) {
        List<String> differs = new ArrayList<>();
        String app = blank(optional(body, "applicationRef")).trim();
        if (!app.equalsIgnoreCase(blank(earlier.getApplicationRef()))) {
            differs.add("application");
        }
        String customerRef = blank(optional(body, "customerRef")).trim();
        if (!customerRef.isEmpty() && !customerRef.equalsIgnoreCase(blank(earlier.getCustomerRef()))) {
            differs.add("customer");
        }
        String product = blank(optional(body, "product")).trim();
        if (!product.isEmpty() && !product.equals(earlier.getProduct())) {
            differs.add("product");
        }
        String tier = blank(optional(body, "tier")).trim();
        if (!tier.isEmpty() && !tier.equalsIgnoreCase(blank(earlier.getTier()))) {
            differs.add("tier");
        }
        if (!differs.isEmpty()) {
            throw new com.nsure.audit.exception.IdempotencyConflictException(null, "COMPLETED", "Idempotency-Key " + key
                    + " was already used to issue " + earlier.getRef() + " for a different request ("
                    + String.join(", ", differs) + "). Use a new key for a new request.", null);
        }
    }

    /**
     * {acceptedOfferId?, acceptedOfferVersion?}: when the caller names the offer the customer accepted, issue goes
     * ahead only if it is still the application's accepted quote version (§10 issue contract).
     */
    private static void requireAcceptedOffer(JsonNode body, InsuranceApplication application, Quote quote) {
        String offer = blank(optional(body, "acceptedOfferId")).trim();
        boolean versionGiven = body.hasNonNull("acceptedOfferVersion");
        if (offer.isEmpty() && !versionGiven) {
            return;
        }
        if (application == null || quote == null) {
            throw new IllegalArgumentException("An accepted offer can only be named when issuing from an application"
                    + " priced on a quote.");
        }
        if ((!offer.isEmpty() && !offer.equalsIgnoreCase(quote.getRef()))
                || (versionGiven && body.get("acceptedOfferVersion").asInt() != quote.getVersionNo())) {
            throw new com.nsure.common.exception.ConflictException(application.getRef() + " now stands on offer "
                    + quote.getRef() + " version " + quote.getVersionNo() + ", not the one named. Refresh and confirm"
                    + " the customer accepted it.");
        }
    }

    @Transactional(readOnly = true)
    public PortfolioViews.ShopView shop() {
        return shopFor(customerForLogin());
    }

    /**
     * A customer's purchase in the portal (NS-IL01-005): it is an application, priced from the product's tier and
     * assessed by the underwriting rules. Inside every limit the policy opens at once, Pending Payment, linked to the
     * application; otherwise the application waits for an underwriter and the customer sees it under their
     * applications. Nothing confers cover before the first premium.
     */
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
        String scope = capture.distributionProblem(product.getName(), "PORTAL");
        if (scope != null) {
            throw new IllegalArgumentException(scope);
        }
        InsurancePolicy row = new InsurancePolicy();
        row.setRef(nextRef("POL-", 5, InsurancePolicy.class));
        row.setCustomerName(account.getName());
        row.setCustomerRef(account.getRef());
        row.setProduct(product.getName());
        row.setAmount(Money.of(digits(product.getCover())));
        row.setPremium(BigDecimal.valueOf(digits(product.getPremium())));
        QuoteService.PricedOffer offer = applyTierOffer(row, body);
        if (offer == null && hasTiers(product.getName())) {
            // NS-IL01-001: a product with plans is priced from the chosen plan on the server, never from display text.
            throw new IllegalArgumentException("Choose a plan for " + product.getName() + ".");
        }
        List<RateTableService.PricedLife> lives = offer == null ? List.of() : offer.lines();
        // A portal purchase is pinned to the current form; staff complete any required extra fields later.
        policyAttributes.pinNewPolicy(row, body, false);
        // Spec 01 BR-25: no plan selection while the customer's enhanced due diligence is open or failed.
        String edd = blank(account.getEddStatus());
        if ("PENDING".equals(edd) || "FAILED".equals(edd)) {
            throw new IllegalArgumentException(
                    "Your enhanced due diligence review must be cleared before you can request cover.");
        }
        // Spec 01 BR-8: one open purchase per product and tier, never a duplicate.
        policyWorkflow.expireUnpaid();
        Optional<String> open = em.createQuery(
                        "select p.ref from InsurancePolicy p where p.customerRef = :customer and p.product = :product"
                                + " and coalesce(p.tier, '') = :tier and p.status = :status", String.class)
                .setParameter("customer", account.getRef())
                .setParameter("product", row.getProduct())
                .setParameter("tier", blank(row.getTier()))
                .setParameter("status", PolicyWorkflow.PENDING_PAYMENT)
                .getResultStream()
                .findFirst();
        if (open.isPresent() || openPortalApplication(account, row.getProduct(), row.getTier()).isPresent()) {
            // BR-8: re-entering checkout reuses the unexpired pending policy or open application, never a duplicate.
            return shopFor(account);
        }
        String actor = account.getLoginUsername();
        // NS-IL01-005: the purchase is an application, underwritten before any policy exists.
        InsuranceApplication application = new InsuranceApplication();
        application.setRef(nextRef("APP-", 4, InsuranceApplication.class));
        application.setCustomerName(account.getName());
        application.setCustomerRef(account.getRef());
        application.setProduct(row.getProduct());
        application.setTier(row.getTier());
        application.setAmount(row.getAmount());
        application.setPremium(row.getPremium());
        application.setRateTableVersion(row.getRateTableVersion());
        application.setStatus(ApplicationCapture.UNDER_REVIEW);
        application.setRecordDate(LocalDate.now().format(DISPLAY));
        application.setDisplayOrder(0);
        application.setChannel("PORTAL");
        com.fasterxml.jackson.databind.node.ObjectNode details = mapper.createObjectNode();
        if (body.has("occupation")) {
            details.set("occupation", body.get("occupation"));
        }
        if (body.has("beneficiaries")) {
            details.set("beneficiaries", body.get("beneficiaries"));
        }
        if (body.has("termYears")) {
            details.set("termYears", body.get("termYears"));
        }
        capture.capture(application, details, actor);
        boolean answered = body.has("disclosures") && body.get("disclosures").isObject() && !body.get("disclosures").isEmpty();
        if (answered || portalDisclosuresRequired) {
            if (body.path("declarationAccepted").asBoolean(false)) {
                application.setDeclaredBy(actor);
                application.setDeclaredAt(java.time.LocalDateTime.now());
            }
        } else {
            // The purchase itself is the customer's declaration where the portal asks no questions (legacy clients).
            application.setDeclaredBy(actor);
            application.setDeclaredAt(java.time.LocalDateTime.now());
        }
        em.persist(application);
        saveLives(application, lives);
        capture.answer(application, body, actor);
        Quote quote = null;
        if (offer != null) {
            // The customer accepts the offer by checking out: the quote and its trace are kept with the application.
            quote = quotes.record(account, row.getProduct(), offer, actor);
            quotes.accept(quote.getRef(), quote.getVersionNo(), actor, "Accepted by the customer at checkout");
            application.setQuote(quote.getRef(), quote.getVersionNo());
            quotes.linkApplication(quote, application.getRef());
        }
        capture.submit(application, account, actor, answered || portalDisclosuresRequired);
        underwriting.getObject().assess(application, account, actor);
        logApplication(application, "Submitted", null, "Customer portal purchase", actor);
        if (!"Ready to issue".equals(application.getStatus())) {
            // Referred, or evidence needed: an underwriter takes it from here; the customer sees it as an application.
            return shopFor(account);
        }
        // Reserved until paid within the payment window; it confers no cover until then (demo server checkout).
        policyWorkflow.openPending(row);
        row.setRecordDate(LocalDate.now().format(DISPLAY));
        row.setDisplayOrder(0);
        row.setApplicationRef(application.getRef());
        row.setTermYears(capture.termFor(application));
        List<PolicyWorkflow.BeneficiaryInput> beneficiaries = new ArrayList<>(capture.beneficiaries(application));
        if (beneficiaries.isEmpty()) {
            beneficiaries.addAll(beneficiaryInputs(body.get("beneficiaries")));
        }
        em.persist(row);
        saveLives(row, lives);
        if (quote != null) {
            quotes.linkPolicy(quote, row.getRef());
            row.setQuote(quote.getRef(), quote.getVersionNo());
        }
        policyWorkflow.logOpened(row, actor);
        for (PolicyWorkflow.BeneficiaryInput input : beneficiaries) {
            policyWorkflow.addBeneficiary(row.getRef(), input, actor);
        }
        String before = application.getStatus();
        application.setStatus("Issued");
        logApplication(application, "Issued", before, "Policy offer " + row.getRef() + " awaits its first premium", actor);
        Map<String, Object> requested = new java.util.LinkedHashMap<>();
        requested.put("applicationRef", application.getRef());
        requested.put("policyRef", row.getRef());
        requested.put("customerRef", account.getRef());
        requested.put("product", row.getProduct());
        requested.put("quoteRef", quote == null ? "" : quote.getRef());
        requested.put("quoteVersion", quote == null ? 0 : quote.getVersionNo());
        outbox.publish(com.nsure.integration.Events.ISSUE_REQUESTED, application.getRef(), requested, actor);
        return shopFor(account);
    }

    /**
     * Section 06 product packs: every plan of a product, saleable or withdrawn, for maintenance (UPDATE_TIER).
     */
    @Transactional(readOnly = true)
    public List<PortfolioViews.TierRecordView> productTiers(String product) {
        security.authenticatedUser().validateHasPermissionTo("READ_PRODUCT");
        return em.createQuery("select t from CoverTier t where t.product.name = :p order by t.displayOrder, t.coverAmount",
                        CoverTier.class).setParameter("p", product).getResultList().stream()
                .map(t -> new PortfolioViews.TierRecordView(t.getId(), product, t.getName(), t.getCoverAmount(),
                        t.getPremium(), t.getPremiumFrequency(), t.isPopular(), t.getBenefits(), t.getDisplayOrder(),
                        t.isActive()))
                .toList();
    }

    /**
     * {cover, premium, frequency?, popular?, benefits?, displayOrder?, active?}: adds or changes a saleable plan of
     * a product (UPDATE_TIER). Issued policies keep their own cover and premium; a withdrawn plan is not sold.
     */
    @Transactional
    public List<PortfolioViews.TierRecordView> saveTier(String productName, String tierName, JsonNode body) {
        AppUser user = security.authenticatedUser();
        user.validateHasPermissionTo("UPDATE_TIER");
        InsuranceProduct product = em.createQuery("select p from InsuranceProduct p where p.name = :n", InsuranceProduct.class)
                .setParameter("n", productName).getResultStream().findFirst()
                .orElseThrow(() -> new NotFoundException("Product " + productName + " not found"));
        String name = blank(tierName).trim();
        if (name.isEmpty() || name.length() > 60) {
            throw new IllegalArgumentException("A plan needs a name of at most 60 characters.");
        }
        CoverTier tier = em.createQuery("select t from CoverTier t where t.product = :p and t.name = :n", CoverTier.class)
                .setParameter("p", product).setParameter("n", name).getResultStream().findFirst().orElse(null);
        int cover = body.path("cover").asInt(tier == null ? 0 : tier.getCoverAmount());
        int premium = body.path("premium").asInt(tier == null ? 0 : tier.getPremium());
        if (cover <= 0 || premium <= 0) {
            throw new IllegalArgumentException("A plan needs a cover amount and a premium above zero.");
        }
        String frequency = defaultText(body, "frequency", tier == null ? "Monthly" : tier.getPremiumFrequency());
        String benefits = body.has("benefits") ? optional(body, "benefits") : tier == null ? null : tier.getBenefits();
        if (benefits != null && benefits.length() > 500) {
            throw new IllegalArgumentException("Describe the plan's benefits in at most 500 characters.");
        }
        boolean created = tier == null;
        if (created) {
            tier = new CoverTier(product, name);
        }
        tier.change(cover, premium, frequency, body.path("popular").asBoolean(!created && tier.isPopular()), benefits,
                body.path("displayOrder").asInt(created ? 9 : tier.getDisplayOrder()),
                body.path("active").asBoolean(created || tier.isActive()));
        em.persist(tier);
        em.flush();
        history.record("Product", product.getName(), product.getName(), created ? "Plan added" : "Plan changed", null,
                null, name + ": cover " + cover + ", premium " + premium + " " + frequency.toLowerCase(Locale.ROOT)
                        + (tier.isActive() ? "" : " (withdrawn)"), user.getUsername());
        return productTiers(product.getName());
    }

    /** The disclosure questions the portal asks for a product (NS-IL01-003). */
    @Transactional(readOnly = true)
    public List<PortfolioViews.DisclosureItem> shopQuestions(String product) {
        customerForLogin();
        return capture.questionsFor(product);
    }

    /**
     * {accept, note?}: the customer's own answer to an underwriting offer on their portal application (IL02). Their
     * acceptance is recorded as theirs, with the time; the application is then ready for staff to issue.
     */
    @Transactional
    public PortfolioViews.ShopView shopAnswerOffer(String applicationRef, JsonNode body) {
        CustomerAccount account = customerForLogin();
        InsuranceApplication app = one(InsuranceApplication.class, applicationRef, "Application");
        if (!account.getRef().equals(app.getCustomerRef())) {
            throw new NotFoundException("Application " + applicationRef + " not found");
        }
        underwriting.getObject().answerOfferAsApplicant(app, body.path("accept").asBoolean(false),
                "Answered by the customer in the portal", account.getLoginUsername());
        return shopFor(account);
    }

    private boolean hasTiers(String product) {
        return em.createQuery("select count(t) from CoverTier t where t.product.name = :p and t.active = true", Long.class)
                .setParameter("p", product).getSingleResult() > 0;
    }

    /** An application the customer started in the portal for this product and plan that is still being decided. */
    private Optional<String> openPortalApplication(CustomerAccount account, String product, String tier) {
        return em.createQuery("select a.ref from InsuranceApplication a where a.customerRef = :c and a.product = :p"
                        + " and coalesce(a.tier, '') = :t and a.channel = 'PORTAL' and a.status in :open", String.class)
                .setParameter("c", account.getRef()).setParameter("p", product).setParameter("t", blank(tier))
                .setParameter("open", List.of(ApplicationCapture.UNDER_REVIEW, ApplicationCapture.EVIDENCE_NEEDED,
                        "Offered", "Ready to issue"))
                .getResultStream().findFirst();
    }

    // ── policy workflow ───────────────────────────────────────────────────────────────────────────

    /** Staff record a premium: a Pending Payment policy goes Active; an Active one is paid a period ahead. */
    @Transactional(noRollbackFor = IllegalArgumentException.class)
    public PortfolioViews.CoverView recordPayment(String ref, JsonNode body) {
        AppUser staff = security.authenticatedUser();
        staff.validateHasPermissionTo("UPDATE_POLICY");
        InsurancePolicy policy = one(InsurancePolicy.class, ref, "Policy");
        com.nsure.common.ExpectedVersion.check("Policy " + ref, policy.getRowVersion());
        requireEddClear(customerOf(policy));
        // A premium staff collected offline: the MANUAL rail, through the same settlement path as every payment.
        com.nsure.payment.PaymentSettlementService.PaymentInitiation result = settlement.getObject()
                .initiate(ref, defaultText(body, "method", "Bank EFT"), staff.getUsername(), true);
        if (com.nsure.payment.PaymentTransaction.FAILED.equals(result.status())) {
            throw new IllegalArgumentException(result.failureReason());
        }
        return cover(one(InsurancePolicy.class, ref, "Policy"));
    }

    @Transactional(noRollbackFor = IllegalArgumentException.class)
    public PortfolioViews.CoverView cancelPolicy(String ref, JsonNode body) {
        AppUser staff = security.authenticatedUser();
        staff.validateHasPermissionTo("UPDATE_POLICY");
        expectPolicyVersion(ref);
        return cover(policyWorkflow.cancel(ref, optional(body, "reason"), staff.getUsername()));
    }

    @Transactional
    public PortfolioViews.CoverView addBeneficiary(String ref, JsonNode body) {
        AppUser staff = security.authenticatedUser();
        staff.validateHasPermissionTo("UPDATE_POLICY");
        expectPolicyVersion(ref);
        policyWorkflow.addBeneficiary(ref, beneficiaryInput(body), staff.getUsername());
        return cover(one(InsurancePolicy.class, ref, "Policy"));
    }

    @Transactional
    public PortfolioViews.CoverView removeBeneficiary(String ref, Long id) {
        AppUser staff = security.authenticatedUser();
        staff.validateHasPermissionTo("UPDATE_POLICY");
        expectPolicyVersion(ref);
        policyWorkflow.removeBeneficiary(ref, id, staff.getUsername());
        return cover(one(InsurancePolicy.class, ref, "Policy"));
    }

    /** NS-TXN-001: a staff command sent with If-Match runs only on the policy version the caller saw. */
    private void expectPolicyVersion(String ref) {
        com.nsure.common.ExpectedVersion.check("Policy " + ref, one(InsurancePolicy.class, ref, "Policy").getRowVersion());
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
        CustomerAccount account = checkoutAllowed(ref);
        com.nsure.payment.PaymentSettlementService.PaymentInitiation result = settlement.getObject()
                .initiate(ref, text(body, "method"), account.getLoginUsername());
        if ("REDIRECT".equals(result.mode())) {
            throw new com.nsure.common.exception.ConflictException("Card payments continue on the secure checkout "
                    + "page: start them with POST /checkout/" + ref + "/pay.");
        }
        if (com.nsure.payment.PaymentTransaction.FAILED.equals(result.status())) {
            throw new IllegalArgumentException(result.failureReason());
        }
        return shopFor(account);
    }

    /**
     * The checks before a customer may pay (spec 06 §6.1): their own policy (ownership), identity verified, and the
     * EDD gate cleared (W-19). Returns their account.
     */
    @Transactional(noRollbackFor = IllegalArgumentException.class)
    public CustomerAccount checkoutAllowed(String ref) {
        CustomerAccount account = customerForLogin();
        InsurancePolicy policy = ownPolicy(account, ref);
        // BR-55: an overdue pending policy expires inline on any payment attempt.
        policyWorkflow.expireIfOverdue(policy);
        if (!"VERIFIED".equals(account.getKycStatus())) {
            throw new IllegalArgumentException("Your identity must be verified before you can pay for cover.");
        }
        requireEddClear(account);
        // BR-5 / BR-6: an OTP-verified declaration for this purchase, and no lock after wrong codes.
        declarations.assertDeclaredForPayment(account, policy);
        return account;
    }

    // ── declaration and consent (spec 01 BR-5, BR-6) ──────────────────────────────────────────────

    public DeclarationService.LockState shopDeclarationLock() {
        return declarations.lockState(customerForLogin());
    }

    public DeclarationService.OtpSent shopDeclarationOtp(String ref) {
        CustomerAccount account = customerForLogin();
        return declarations.sendOtp(account, ownPolicy(account, ref));
    }

    public ConsentView shopDeclare(String ref, JsonNode body) {
        CustomerAccount account = customerForLogin();
        return consent(declarations.verify(account, ownPolicy(account, ref),
                body == null ? com.fasterxml.jackson.databind.node.NullNode.getInstance() : body));
    }

    @Transactional(readOnly = true)
    public List<ConsentView> declarations(String ref) {
        security.authenticatedUser().validateHasPermissionTo("READ_AUDIT");
        return declarations.forPolicy(ref).stream().map(PortfolioService::consent).toList();
    }

    private static ConsentView consent(DeclarationConsent row) {
        return new ConsentView(row.getId(), row.getPolicyRef(), row.getCustomerRef(), row.isAccuracyConfirmed(),
                row.isTermsAccepted(), row.isDataProtectionAccepted(), row.isFalseInformationAcknowledged(),
                row.getOtpVerifiedAt(), row.getRecordedBy());
    }

    /** One immutable declaration record. */
    public record ConsentView(Long id, String policyRef, String customerRef, boolean accuracy, boolean terms,
            boolean dataProtection, boolean falseInformation, java.time.LocalDateTime verifiedAt, String recordedBy) {
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
        if (!account.getRef().equals(policy.getCustomerRef())) {
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
        com.nsure.common.ExpectedVersion.check("Customer " + ref, row.getRowVersion());
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
        parties.kycChanged(row, "EDD", previous, decision, reviewer.getUsername());
        em.flush();
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

    /**
     * The customer a staff request is about (Nsure Life NS-SH01-001, AT-SH01-01): by {@code customerRef}, or by name
     * only when exactly one customer has it. Two customers who share a name are never guessed between.
     */
    private CustomerAccount customerFrom(JsonNode body) {
        String ref = optional(body, "customerRef");
        if (ref != null && !ref.isBlank()) {
            return em.createQuery("select c from CustomerAccount c where c.ref = :ref", CustomerAccount.class)
                    .setParameter("ref", ref.trim().toUpperCase(Locale.ROOT))
                    .getResultStream().findFirst()
                    .orElseThrow(() -> new NotFoundException("Customer " + ref.trim() + " not found"));
        }
        String name = optional(body, "name");
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Choose the customer.");
        }
        List<CustomerAccount> named = em.createQuery("select c from CustomerAccount c where c.name = :name",
                        CustomerAccount.class)
                .setParameter("name", name.trim())
                .setMaxResults(2)
                .getResultList();
        if (named.isEmpty()) {
            throw new NotFoundException("Customer " + name.trim() + " not found");
        }
        if (named.size() > 1) {
            throw new IllegalArgumentException("More than one customer is named " + name.trim()
                    + ". Choose the customer by reference (CUS-…).");
        }
        return named.get(0);
    }

    /** The customer a cover record belongs to, by its reference. */
    private CustomerAccount customerOf(CoverRecord row) {
        if (row.getCustomerRef() == null) {
            throw new IllegalArgumentException(row.getRef() + " is not linked to a customer record.");
        }
        return em.createQuery("select c from CustomerAccount c where c.ref = :ref", CustomerAccount.class)
                .setParameter("ref", row.getCustomerRef())
                .getResultStream().findFirst()
                .orElseThrow(() -> new NotFoundException("Customer " + row.getCustomerRef() + " not found"));
    }

    /**
     * Staff quote for a customer, tier and lives: the same pricing and rule checks as saving the application
     * or policy, so the form shows what will be stored.
     */
    @Transactional(readOnly = true)
    public PortfolioViews.QuoteView quote(JsonNode body) {
        if (security.authenticatedUser().isPortalCustomer()) {
            throw new NoAuthorizationException("Customers quote their own cover in the policy shop.");
        }
        return quote(customerFrom(body), text(body, "product"), text(body, "tier"), body);
    }

    /** The signed-in customer's quote; they are always the main insured. */
    @Transactional(readOnly = true)
    public PortfolioViews.QuoteView shopQuote(JsonNode body) {
        return quote(customerForLogin(), text(body, "product"), text(body, "tier"), body);
    }

    private PortfolioViews.QuoteView quote(CustomerAccount customer, String product, String tierName, JsonNode body) {
        CoverTier tier = tier(product, tierName);
        Optional<RateTableService.PolicyPrice> price = price(tier, customer, lives(body));
        requireFlatTierAge(tier, customer, price);
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
        com.nsure.common.ExpectedVersion.check("Customer " + ref, row.getRowVersion());
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
        parties.kycChanged(row, "KYC", previous, decision, reviewer.getUsername());
        em.flush();
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
                optional(body, "type"), optional(body, "coveredLife"));
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

    /** Payout amount resolution (spec 04 §5.4): empty means the full cover; anything else must be a number. */
    private static BigDecimal payoutAmount(JsonNode input) {
        JsonNode value = input.get("payoutAmount");
        if (value == null || value.isNull() || (value.isTextual() && value.asText().isBlank())) {
            return null;
        }
        BigDecimal amount;
        try {
            amount = value.isNumber() ? value.decimalValue() : new BigDecimal(value.asText().trim());
        } catch (NumberFormatException ex) {
            throw new com.nsure.common.exception.InvalidFieldException("payoutAmount", "Payout amount must be a number");
        }
        // Pula and thebe (NS-DAT-003): more than two decimal places is not an amount of money.
        if (amount.stripTrailingZeros().scale() > 2) {
            throw new com.nsure.common.exception.InvalidFieldException("payoutAmount",
                    "Payout amount can have at most two decimal places");
        }
        return amount.setScale(2, RoundingMode.UNNECESSARY);
    }

    /** One of the person actions: assess, approve, reject or pay. */
    @Transactional
    public PortfolioViews.ClaimView claimAction(String ref, String action, JsonNode body) {
        AppUser actor = security.authenticatedUser();
        JsonNode input = body == null ? com.fasterxml.jackson.databind.node.NullNode.getInstance() : body;
        InsuranceClaim claim = switch (action) {
            case "assess" -> claimWorkflow.assess(ref, optional(input, "note"), actor);
            case "approve" -> claimWorkflow.approve(ref, payoutAmount(input),
                    optional(input, "overrideReason"), optional(input, "fourEyesOverrideReason"),
                    optional(input, "evidenceOverrideReason"), actor);
            case "reject" -> claimWorkflow.reject(ref, optional(input, "reason"), actor);
            case "pay" -> claimWorkflow.pay(ref, actor);
            case "confirm-payout" -> claimWorkflow.confirmPayout(ref, optional(input, "providerReference"), actor);
            case "payout-failed" -> claimWorkflow.payoutFailed(ref, optional(input, "reason"), actor);
            case "reopen" -> claimWorkflow.reopen(ref, optional(input, "reason"), actor);
            case "close" -> claimWorkflow.close(ref, optional(input, "note"), input.path("retainRecovery").asBoolean(false),
                    actor);
            default -> throw new IllegalArgumentException("Unknown claim action: " + action);
        };
        return claim(claim);
    }

    /** A claim's reserve movements and the benefit limits it draws on (NS-SH05-003/005). */
    @Transactional(readOnly = true)
    public PortfolioViews.ClaimFinancialsView claimFinancials(String ref) {
        security.authenticatedUser().validateHasPermissionTo("READ_CLAIM");
        InsuranceClaim claim = one(InsuranceClaim.class, ref, "Claim");
        InsurancePolicy policy = claim.getPolicyRef() == null ? null : policyWorkflow.policy(claim.getPolicyRef());
        ClaimFinancials.Limits limits = policy == null ? null : claimFinancials.limits(claim, policy);
        return new PortfolioViews.ClaimFinancialsView(claim.getRef(), claim.getReserveAmount(),
                claimFinancials.movements(ref).stream().map(m -> new PortfolioViews.ReserveMovementView(m.getChange(),
                        m.getBalance(), m.getReason(), m.getCreatedBy(), m.getCreatedAt())).toList(),
                limits == null ? null : limits.lifeLimit(), limits == null ? null : limits.lifeUsed(),
                limits == null ? null : limits.policyLimit(), limits == null ? null : limits.policyUsed(),
                claimWorkflow.decisions(ref).stream().map(d -> new PortfolioViews.ClaimDecisionView(d.getSequenceNo(),
                        d.getDecision(), d.getReason(), d.getAmount(), d.getDecidedBy(), d.getDecidedAt(),
                        d.getSupersededBy())).toList(),
                claimWorkflow.eventCover(claim),
                em.createQuery("select r.ref, r.treaty.ref, r.recoveryAmount, r.status, r.receivedAmount from Recovery r"
                                + " where r.claimRef = :ref order by r.id", Object[].class)
                        .setParameter("ref", ref).getResultList().stream()
                        .map(r -> new PortfolioViews.ClaimRecoveryView((String) r[0], (String) r[1], (BigDecimal) r[2],
                                (String) r[3], (BigDecimal) r[4])).toList());
    }

    @Transactional(readOnly = true)
    public List<PortfolioViews.HistoryView> history(String type, String ref) {
        String entity = switch (type) {
            case "policy" -> WorkflowHistory.POLICY;
            case "claim" -> WorkflowHistory.CLAIM;
            case "kyc" -> WorkflowHistory.KYC_REVIEW;
            case "edd" -> WorkflowHistory.EDD_REVIEW;
            case "application" -> ApplicationCapture.HISTORY;
            default -> throw new IllegalArgumentException("History type must be policy, claim, kyc, edd or application.");
        };
        return history.of(entity, ref);
    }

    @Transactional
    public PortfolioViews.ConfigView saveConfig(String ref, JsonNode body) {
        AppUser actor = security.authenticatedUser();
        actor.validateHasPermissionTo(ref == null ? "CREATE_PRODUCT" : "UPDATE_PRODUCT");
        ProductConfiguration row = ref == null
                ? new ProductConfiguration()
                : one(ProductConfiguration.class, ref, "Product configuration");
        // Spec 04 §3: activating or deactivating a catalogue entity is product.activate, separate from editing it
        // (an Actuary may edit products but not switch them on or off).
        String requested = defaultText(body, "status", "Draft");
        String current = ref == null ? "Draft" : row.getStatus();
        boolean activationChange = !requested.equalsIgnoreCase(current == null ? "" : current)
                && (requested.toLowerCase(java.util.Locale.ROOT).contains("active")
                        || (current != null && current.toLowerCase(java.util.Locale.ROOT).contains("active")));
        if (activationChange) {
            actor.validateHasPermissionTo("ACTIVATE_PRODUCT");
        }
        // The state being replaced, kept below as an immutable snapshot (NS-SH02-003, NS-DAT-004).
        JsonNode before = ref == null ? null : configHistory.content(row);
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
        JsonNode after = configHistory.content(row);
        boolean active = "active".equalsIgnoreCase(requested);
        if (active && (!"active".equalsIgnoreCase(current) || before == null || !before.equals(after))) {
            // Maker-checker: content reaches Active only as reviewed, and only by someone who did not write it.
            configHistory.requireIndependentActivation(ref, current, before, after, actor.getUsername());
        }
        if (ref == null) {
            em.persist(row);
        }
        configHistory.record(row, before, actor.getUsername());
        configHistory.publishStatusChange(row, current, actor.getUsername());
        return config(row);
    }

    @Transactional(readOnly = true)
    public List<ProductConfigHistory.VersionView> configVersions(String ref) {
        security.authenticatedUser().validateHasPermissionTo("READ_PRODUCT");
        one(ProductConfiguration.class, ref, "Product configuration");
        return configHistory.versions(ref);
    }

    private void fillCover(CoverRecord row, JsonNode body, CustomerAccount customer, String prefix, int width,
            String defaultStatus) {
        row.setRef(nextRef(prefix, width, row.getClass()));
        // The customer reference is authoritative; the name is copied from the customer record (NS-SH01-001).
        row.setCustomerName(customer.getName());
        row.setCustomerRef(customer.getRef());
        row.setProduct(text(body, "product"));
        row.setAmount(amount(body, "amount"));
        row.setPremium(amount(body, "premium"));
        row.setStatus(body.hasNonNull("status") ? body.get("status").asText() : defaultStatus);
        row.setRecordDate(body.hasNonNull("date") ? body.get("date").asText() : LocalDate.now().format(DISPLAY));
        row.setDisplayOrder(0);
    }

    /** Race-free reference from a locked series (spec 01 BR-59). */
    private <T> String nextRef(String prefix, int width, Class<T> type) {
        return references.next(prefix, width, () -> references.highest(type.getSimpleName(), prefix));
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
                        "select p from InsurancePolicy p where p.customerRef = :customer order by p.id desc",
                        InsurancePolicy.class)
                .setParameter("customer", row.getRef())
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
        QuoteService.PricedOffer offer = applyTierOffer(row, body);
        return offer == null ? List.of() : offer.lines();
    }

    /** As applyTier, keeping the whole priced offer (tier, every life, price) to store as a quote; null without a tier. */
    private <T extends CoverRecord & TieredCover> QuoteService.PricedOffer applyTierOffer(T row, JsonNode body) {
        String tierName = optional(body, "tier");
        List<RateTableService.LifeToPrice> others = lives(body);
        if (tierName == null || tierName.isBlank()) {
            if (!others.isEmpty()) {
                throw new IllegalArgumentException("Choose a tier before adding lives.");
            }
            return null;
        }
        CoverTier tier = tier(row.getProduct(), tierName);
        row.setTier(tier.getName());
        row.setAmount(Money.of(tier.getCoverAmount()));
        // Server-side pricing (R-18): a rate-table-priced plan sums every covered life's rate by age from the
        // current version and stamps that version on the record; any client-supplied premium is ignored.
        Optional<RateTableService.PolicyPrice> price = price(tier, customerOf(row), others);
        requireFlatTierAge(tier, customerOf(row), price);
        row.setPremium(price.map(RateTableService.PolicyPrice::monthlyTotal).orElse(BigDecimal.valueOf(tier.getPremium())));
        row.setRateTableVersion(price.map(RateTableService.PolicyPrice::rateTableVersion).orElse(null));
        CustomerAccount customer = customerOf(row);
        List<RateTableService.LifeToPrice> all = new ArrayList<>();
        all.add(new RateTableService.LifeToPrice(selfRelationship(), customer.getName(), customer.getDateOfBirth()));
        all.addAll(others);
        return new QuoteService.PricedOffer(tier, all, price);
    }

    /** NS-IL01-001: a flat-priced plan still has entry ages; a rated plan checked each life's age when it priced it. */
    private void requireFlatTierAge(CoverTier tier, CustomerAccount customer,
            Optional<RateTableService.PolicyPrice> price) {
        if (price.isPresent()) {
            return;
        }
        String problem = underwriting.getObject().entryAgeProblem(tier.getProduct().getName(), customer.getDateOfBirth());
        if (problem != null) {
            throw new IllegalArgumentException(problem);
        }
    }

    /** The customer is always the main insured, priced from their own date of birth; the body adds the rest. */
    private Optional<RateTableService.PolicyPrice> price(
            CoverTier tier, CustomerAccount customer, List<RateTableService.LifeToPrice> others) {
        List<RateTableService.LifeToPrice> lives = new ArrayList<>();
        lives.add(new RateTableService.LifeToPrice(selfRelationship(), customer.getName(), customer.getDateOfBirth()));
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
                blank(row.getEddRejectionReason()),
                blank(row.getDuplicateReview()),
                row.getRowVersion());
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
        Role policyholder = roles.findByName(AppUser.PORTAL_ROLE)
                .orElseThrow(() -> new IllegalArgumentException("Policyholder role is not available."));
        AppUser user = new AppUser();
        user.setUsername(login);
        user.setPassword(passwords.encode(password));
        user.setEnabled(true);
        user.replaceRoles(Set.of(policyholder));
        users.save(user);
        return login;
    }

    private PortfolioViews.ShopView shopFor(CustomerAccount account) {
        List<PortfolioViews.CoverView> policies = covers(em.createQuery(
                        "select p from InsurancePolicy p where p.customerRef = :customer order by p.id desc",
                        InsurancePolicy.class)
                .setParameter("customer", account.getRef())
                .getResultList(), InsurancePolicy.class, false);
        List<PortfolioViews.CoverView> requests = policies.stream().filter(p -> isRequest(p.status())).toList();
        List<PortfolioViews.ClaimView> claims = em.createQuery(
                        "select c from InsuranceClaim c where c.customerRef = :customer order by c.id desc", InsuranceClaim.class)
                .setParameter("customer", account.getRef())
                .getResultStream()
                .map(this::claim)
                .toList();
        // NS-UX-001: the customer sees when each record last changed, from its own workflow log.
        Map<String, java.time.LocalDateTime> updated = new HashMap<>();
        List<String> refs = new ArrayList<>();
        policies.forEach(p -> refs.add(p.id()));
        claims.forEach(c -> refs.add(c.id()));
        if (!refs.isEmpty()) {
            for (Object[] row : em.createQuery("select l.entityRef, max(l.performedAt) from WorkflowLog l"
                            + " where l.entityType in (:types) and l.entityRef in (:refs) group by l.entityRef", Object[].class)
                    .setParameter("types", List.of(WorkflowHistory.POLICY, WorkflowHistory.CLAIM))
                    .setParameter("refs", refs).getResultList()) {
                updated.put((String) row[0], (java.time.LocalDateTime) row[1]);
            }
        }
        // NS-IL01-005: applications still with underwriting (or declined) show as applications, not as cover.
        List<PortfolioViews.CoverView> applications = covers(em.createQuery(
                        "select a from InsuranceApplication a where a.customerRef = :customer and a.status not in :done"
                                + " order by a.id desc", InsuranceApplication.class)
                .setParameter("customer", account.getRef())
                .setParameter("done", List.of("Issued", ApplicationCapture.DRAFT))
                .getResultList(), InsuranceApplication.class, false);
        Map<String, Map<String, Object>> offers = underwriting.getObject()
                .pendingOffers(applications.stream().map(PortfolioViews.CoverView::id).toList());
        return new PortfolioViews.ShopView(customer(account), products(),
                policies.stream().filter(p -> !isRequest(p.status())).toList(), requests, claims, updated, applications,
                offers);
    }

    private static boolean isRequest(String status) {
        return REQUEST_STATES.contains(status);
    }

    /** The signed-in customer's own profile; staff logins are refused. */
    public CustomerAccount customerForLogin() {
        AppUser user = security.authenticatedUser();
        if (!user.isPortalCustomer()) {
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

    /** One policy as the workspace shows it: lives, beneficiaries and documents included. */
    public PortfolioViews.CoverView policyView(InsurancePolicy policy) {
        return cover(policy);
    }

    private PortfolioViews.CoverView cover(CoverRecord row) {
        boolean policy = row instanceof InsurancePolicy;
        return cover(row, row instanceof TieredCover rated && rated.getRateTableVersion() != null
                ? livesOf(row.getClass(), List.of(row.getId())).getOrDefault(row.getId(), List.of()) : List.of(),
                policy ? beneficiariesOf(List.of(row.getId())).getOrDefault(row.getId(), List.of()) : List.of(),
                policy ? PolicyDocumentService.viewsOf(em, List.of(row.getId())).getOrDefault(row.getId(), List.of())
                        : List.of());
    }

    private PortfolioViews.CoverView cover(CoverRecord row, List<PortfolioViews.LifeCoverView> lives,
            List<PortfolioViews.BeneficiaryView> beneficiaries, List<PortfolioViews.DocumentView> documents) {
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
                beneficiaries,
                documents,
                policy != null ? policyWorkflow.terms(policy).getMaxBeneficiaries() : maxBeneficiaries(row.getProduct()),
                row instanceof InsuranceApplication a ? blank(a.getQuoteRef()) : policy != null ? blank(policy.getQuoteRef()) : "",
                row instanceof InsuranceApplication a2 ? a2.getQuoteVersion() : policy != null ? policy.getQuoteVersion() : null,
                blank(row.getCustomerRef()),
                row instanceof InsuranceApplication a3 ? Long.valueOf(a3.getRowVersion())
                        : policy != null ? Long.valueOf(policy.getRowVersion()) : null);
    }

    private int maxBeneficiaries(String product) {
        return em.createQuery("select p.maxBeneficiaries from InsuranceProduct p where p.name = :name", Integer.class)
                .setParameter("name", product)
                .getResultStream()
                .findFirst()
                .orElse(PolicyWorkflow.MAX_BENEFICIARIES);
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
                                + " and b.removedFrom is null order by b.displayOrder, b.id", Object[].class)
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
        List<Long> ids = rows.stream().map(CoverRecord::getId).toList();
        Map<Long, List<PortfolioViews.BeneficiaryView>> beneficiaries = type == InsurancePolicy.class
                ? beneficiariesOf(ids) : Map.of();
        Map<Long, List<PortfolioViews.DocumentView>> documents = type == InsurancePolicy.class
                ? PolicyDocumentService.viewsOf(em, ids) : Map.of();
        return rows.stream().map(row -> cover(row, lives.getOrDefault(row.getId(), List.of()),
                beneficiaries.getOrDefault(row.getId(), List.of()), documents.getOrDefault(row.getId(), List.of())))
                .toList();
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
        // After a change, write it first so the view carries the version a client must send back (NS-TXN-001).
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()
                && !org.springframework.transaction.support.TransactionSynchronizationManager.isCurrentTransactionReadOnly()
                && em.contains(row)) {
            em.flush();
        }
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
                ClaimWorkflow.allowedActions(row),
                ClaimDocumentService.views(em, row.getId()),
                blank(row.getCheckOverrideReason()),
                blank(ClaimWorkflow.auditFlag(row)),
                row.getStpEligible(),
                blank(row.getStpIneligibleCode()),
                blank(row.getFourEyesOverrideReason()),
                blank(row.getEvidenceOverrideReason()),
                row.getClosedAt() == null ? "" : row.getClosedAt().withNano(0).toString(),
                blank(row.getClosedBy()),
                blank(row.getCloseNote()),
                row.getRowVersion(),
                blank(row.getCoveredLifeName()).isEmpty() ? row.getCustomerName() : row.getCoveredLifeName(),
                row.getReserveAmount(),
                claimFinancials.evidence(row),
                claimFinancials.related(row));
    }

    private PortfolioViews.ConfigView config(ProductConfiguration row) {
        return new PortfolioViews.ConfigView(
                row.getRef(), row.getName(), row.getCode(), row.getDomain(), row.getCurrency(), row.getDescription(),
                row.getEffectiveOn(), row.getMinAge(), row.getMaxAge(), row.getWaitingMonths(), row.getCoverAmount(),
                row.getPremiumAmount(), row.getFrequency(), row.getGraceDays(), row.getUnderwriting(),
                split(row.getBenefits()), split(row.getChannels()), row.getWording(), row.isApprovals(),
                row.getStatus(), row.getVersionNo(), configHistory.madeBy(row));
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
