package com.nsure.portfolio;

import com.fasterxml.jackson.databind.JsonNode;
import com.nsure.integration.Events;
import com.nsure.integration.Outbox;
import jakarta.persistence.EntityManager;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Everything an individual application captures besides the cover (NS-IL01-002/003): proposer, payer, payment
 * mandate, channel, disclosure answers, the declaration and the evidence status. A draft may be incomplete;
 * submitting needs every mandatory answer, a declaration, a payer who can be identified and a mandate the payment
 * method requires. Outstanding mandatory evidence does not stop submission: the application waits in "Evidence
 * needed", and issue is refused with the missing items named (AT-IL01-02).
 */
@Component
public class ApplicationCapture {

    public static final String DRAFT = "Draft";
    public static final String UNDER_REVIEW = "Under review";
    public static final String EVIDENCE_NEEDED = "Evidence needed";
    /** The workflow-log type of an application's timeline (IL01 workspace). */
    public static final String HISTORY = "Application";
    static final Set<String> CHANNELS = Set.of("DIRECT", "BRANCH", "AGENT", "BROKER", "CALL_CENTRE", "PORTAL");
    static final Set<String> MANDATES = Set.of("DEBIT_ORDER", "MOBILE_WALLET", "CARD", "BANK_EFT");

    private final EntityManager em;
    private final Outbox outbox;
    private final ProductTerms terms;
    private final com.fasterxml.jackson.databind.ObjectMapper mapper;
    /** Payer/proposer relationships allowed besides the relationship types (demo default; NS-IL01-003). */
    private final List<String> extraRelationships;

    public ApplicationCapture(EntityManager em, Outbox outbox, ProductTerms terms,
            com.fasterxml.jackson.databind.ObjectMapper mapper,
            @org.springframework.beans.factory.annotation.Value("${nsure.new-business.extra-payer-relationships:Employer,Guardian}")
            String extraRelationships) {
        this.em = em;
        this.outbox = outbox;
        this.terms = terms;
        this.mapper = mapper;
        this.extraRelationships = java.util.Arrays.stream(extraRelationships.split(",")).map(String::trim)
                .filter(v -> !v.isEmpty()).toList();
    }

    /** Copies whatever the body carries onto the application; answers are stored once it has a reference. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void capture(InsuranceApplication app, JsonNode body, String actor) {
        if (body == null) {
            return;
        }
        if (body.has("proposerName") || body.has("proposerRef")) {
            app.setProposerName(clip(text(body, "proposerName"), 100));
            app.setProposerRelationship(clip(text(body, "proposerRelationship"), 60));
            // NS-IL01-002: a proposer who is already a party is linked by reference; the name comes from the record.
            CustomerAccount party = party(text(body, "proposerRef"), "proposer");
            app.setProposerRef(party == null ? null : party.getRef());
            if (party != null) {
                app.setProposerName(clip(party.getName(), 100));
            }
        }
        if (body.has("payerName") || body.has("payerRef")) {
            app.setPayerName(clip(text(body, "payerName"), 100));
            app.setPayerRelationship(clip(text(body, "payerRelationship"), 60));
            app.setPayerIdNumber(clip(text(body, "payerIdNumber"), 40));
            CustomerAccount party = party(text(body, "payerRef"), "payer");
            app.setPayerRef(party == null ? null : party.getRef());
            if (party != null) {
                app.setPayerName(clip(party.getName(), 100));
                if (app.getPayerIdNumber() == null) {
                    app.setPayerIdNumber(clip(party.getPoiNumber(), 40));
                }
            }
        }
        if (body.has("beneficiaries")) {
            app.setBeneficiaries(beneficiariesJson(app, body.get("beneficiaries")));
        }
        if (body.has("mandateMethod")) {
            String method = upper(text(body, "mandateMethod"));
            if (method != null && !MANDATES.contains(method)) {
                throw new IllegalArgumentException("Payment mandate must be one of " + String.join(", ", MANDATES) + ".");
            }
            app.setMandateMethod(method);
            // A resumed draft is shown the account only masked, so a debit order whose bank details are not sent
            // again keeps the ones on record; any other method drops them.
            boolean keepBank = "DEBIT_ORDER".equals(method);
            if (body.has("mandateInstitution") || !keepBank) {
                app.setMandateInstitution(clip(text(body, "mandateInstitution"), 60));
            }
            if (body.has("mandateAccount") || !keepBank) {
                app.setMandateAccount(clip(text(body, "mandateAccount"), 60));
            }
            String day = text(body, "mandateDebitDay");
            if (day != null) {
                try {
                    int d = Integer.parseInt(day);
                    if (d < 1 || d > 28) {
                        throw new NumberFormatException();
                    }
                    app.setMandateDebitDay(d);
                } catch (NumberFormatException ex) {
                    throw new IllegalArgumentException("The debit day must be a day of the month from 1 to 28.");
                }
            }
        }
        if (body.has("channel")) {
            String channel = upper(text(body, "channel"));
            if (channel != null && !CHANNELS.contains(channel)) {
                throw new IllegalArgumentException("Channel must be one of " + String.join(", ", CHANNELS) + ".");
            }
            app.setChannel(channel);
            app.setIntermediaryCode(clip(text(body, "intermediaryCode"), 30));
        }
        if (body.has("occupation")) {
            app.setOccupation(clip(text(body, "occupation"), 100));
        }
        if (body.has("termYears")) {
            // Section 06 Term Life: the selected term must be one the product sells.
            String term = text(body, "termYears");
            Integer years = term == null ? null : Integer.valueOf(term);
            List<Integer> options = termOptions(app.getProduct());
            if (years != null && !options.contains(years)) {
                throw new IllegalArgumentException(app.getProduct() + (options.isEmpty() ? " has no selected policy term."
                        : " is sold for terms of " + options.stream().map(String::valueOf)
                                .collect(java.util.stream.Collectors.joining(", ")) + " years."));
            }
            app.setTermYears(years);
        }
        if (body.path("declarationAccepted").asBoolean(false) && app.getDeclaredAt() == null) {
            app.setDeclaredBy(actor);
            app.setDeclaredAt(LocalDateTime.now());
        }
    }

    /** Stores the disclosure answers the body carries (an object of code → answer). */
    @Transactional(propagation = Propagation.MANDATORY)
    public void answer(InsuranceApplication app, JsonNode body, String actor) {
        JsonNode answers = body == null ? null : body.get("disclosures");
        if (answers == null || !answers.isObject()) {
            return;
        }
        for (ApplicationRecords.Question q : questions(app.getProduct())) {
            JsonNode given = answers.get(q.getCode());
            if (given == null || given.isNull() || given.asText().isBlank()) {
                continue;
            }
            String value = given.asText().trim();
            if ("YES_NO".equals(q.getAnswerType())) {
                value = value.toUpperCase(Locale.ROOT);
                if (!Set.of("YES", "NO").contains(value)) {
                    throw new IllegalArgumentException(q.getQuestion() + " Answer YES or NO.");
                }
            }
            String answer = clip(value, 500);
            ApplicationRecords.Answer existing = answerFor(app.getRef(), q.getCode());
            if (existing == null) {
                em.persist(new ApplicationRecords.Answer(app.getRef(), q, answer, actor));
            } else {
                existing.change(answer, actor);
            }
        }
    }

    /** Every reason the application cannot be submitted yet, each one specific enough to act on. */
    public List<String> submissionProblems(InsuranceApplication app) {
        List<String> problems = new ArrayList<>();
        Set<String> answered = new HashSet<>();
        if (app.getRef() != null) {
            em.createQuery("select a from ApplicationAnswer a where a.applicationRef = :ref", ApplicationRecords.Answer.class)
                    .setParameter("ref", app.getRef()).getResultStream().forEach(a -> answered.add(a.getCode()));
        }
        for (ApplicationRecords.Question q : questions(app.getProduct())) {
            if (q.isMandatory() && !answered.contains(q.getCode())) {
                problems.add("Answer the disclosure question: " + q.getQuestion());
            }
        }
        if (app.getPayerName() != null && blank(app.getPayerRelationship())) {
            problems.add("Say how the payer " + app.getPayerName() + " is related to the policyholder.");
        }
        // NS-IL01-003: proposer and payer relationships must be eligible relationship types.
        List<String> allowed = relationships();
        if (!blank(app.getPayerRelationship()) && !containsIgnoreCase(allowed, app.getPayerRelationship())) {
            problems.add("The payer's relationship \"" + app.getPayerRelationship() + "\" is not an eligible relationship ("
                    + String.join(", ", allowed) + ").");
        }
        if (app.getProposerName() != null && blank(app.getProposerRelationship())) {
            problems.add("Say how the proposer " + app.getProposerName() + " is related to the life insured.");
        }
        if (!blank(app.getProposerRelationship()) && !containsIgnoreCase(allowed, app.getProposerRelationship())) {
            problems.add("The proposer's relationship \"" + app.getProposerRelationship()
                    + "\" is not an eligible relationship (" + String.join(", ", allowed) + ").");
        }
        if ("DEBIT_ORDER".equals(app.getMandateMethod())
                && (blank(app.getMandateInstitution()) || blank(app.getMandateAccount()) || app.getMandateDebitDay() == null)) {
            problems.add("A debit order needs the bank, the account number and the debit day.");
        }
        if (app.getChannel() != null && Set.of("AGENT", "BROKER").contains(app.getChannel()) && blank(app.getIntermediaryCode())) {
            problems.add("An agent or broker application needs the intermediary's code.");
        }
        // NS-IL01-002: the intermediary must be appointed and active today.
        if (!blank(app.getIntermediaryCode())) {
            com.nsure.distribution.Intermediary intermediary = em.createQuery(
                            "select i from Intermediary i where i.ref = :ref", com.nsure.distribution.Intermediary.class)
                    .setParameter("ref", app.getIntermediaryCode().trim()).getResultStream().findFirst().orElse(null);
            if (intermediary == null) {
                problems.add("Intermediary " + app.getIntermediaryCode() + " is not an appointed intermediary.");
            } else if (!com.nsure.distribution.Intermediary.ACTIVE.equals(intermediary.getStatus())
                    || intermediary.getAppointmentExpiry().isBefore(java.time.LocalDate.now())) {
                problems.add("Intermediary " + app.getIntermediaryCode() + " is not active (appointment suspended or"
                        + " expired).");
            }
        }
        // NS-IL01-001: the product must be distributed through the application's channel.
        String scope = distributionProblem(app.getProduct(), app.getChannel() == null ? "DIRECT" : app.getChannel());
        if (scope != null) {
            problems.add(scope);
        }
        if (app.getDeclaredAt() == null) {
            problems.add("The applicant's declaration has not been accepted.");
        }
        return problems;
    }

    /**
     * Submits a complete application (ApplicationSubmitted). It goes to "Evidence needed" while mandatory evidence is
     * outstanding, otherwise "Under review".
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void submit(InsuranceApplication app, CustomerAccount customer, String actor) {
        submit(app, customer, actor, true);
    }

    /**
     * As {@link #submit(InsuranceApplication, CustomerAccount, String)}; {@code complete} false is a legacy portal
     * purchase that asked no disclosure questions (portal-disclosures-required off): the rules still assess it.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void submit(InsuranceApplication app, CustomerAccount customer, String actor, boolean complete) {
        List<String> problems = complete ? submissionProblems(app) : List.of();
        if (!problems.isEmpty()) {
            throw new IllegalArgumentException("The application cannot be submitted yet: " + String.join(" ", problems));
        }
        app.setSubmittedBy(actor);
        app.setSubmittedAt(LocalDateTime.now());
        app.setStatus(missingEvidence(app, customer).isEmpty() ? UNDER_REVIEW : EVIDENCE_NEEDED);
        Map<String, Object> submitted = new LinkedHashMap<>();
        submitted.put("applicationRef", app.getRef());
        submitted.put("customerRef", customer.getRef());
        submitted.put("product", app.getProduct());
        submitted.put("channel", app.getChannel() == null ? "DIRECT" : app.getChannel());
        submitted.put("quoteRef", app.getQuoteRef() == null ? "" : app.getQuoteRef());
        outbox.publish(Events.APPLICATION_SUBMITTED, app.getRef(), submitted, actor);
    }

    /** Records a piece of evidence as received; an application that was only waiting for evidence goes back to review. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordEvidence(InsuranceApplication app, CustomerAccount customer, String documentType, String note,
            String actor) {
        boolean known = requirements(app).stream().anyMatch(r -> r.getDocumentType().equals(documentType));
        if (!known) {
            throw new IllegalArgumentException(documentType + " is not evidence this application asks for.");
        }
        if (evidence(app.getRef()).stream().anyMatch(e -> e.getDocumentType().equals(documentType))) {
            throw new com.nsure.common.exception.ConflictException(documentType + " is already recorded as received.");
        }
        if (blank(note) || note.trim().length() < 5) {
            throw new IllegalArgumentException("Say what was received and checked (at least 5 characters).");
        }
        em.persist(new ApplicationRecords.Evidence(app.getRef(), documentType, clip(note.trim(), 300), actor));
        em.flush();
        if (EVIDENCE_NEEDED.equals(app.getStatus()) && missingEvidence(app, customer).isEmpty()) {
            app.setStatus(UNDER_REVIEW);
        }
    }

    /** Issue needs a submitted application with no mandatory evidence outstanding (AT-IL01-02). */
    public void requireIssuable(InsuranceApplication app, CustomerAccount customer) {
        if (DRAFT.equals(app.getStatus())) {
            throw new IllegalArgumentException(app.getRef() + " is a draft. Complete and submit it before issue.");
        }
        List<String> missing = missingEvidence(app, customer);
        if (!missing.isEmpty()) {
            throw new IllegalArgumentException(app.getRef() + " cannot be issued until this evidence is received: "
                    + String.join(", ", missing) + ". Record each item on the application when it arrives.");
        }
    }

    public PortfolioViews.ApplicationChecklistView checklist(InsuranceApplication app, CustomerAccount customer) {
        Map<String, String> answers = new LinkedHashMap<>();
        em.createQuery("select a from ApplicationAnswer a where a.applicationRef = :ref", ApplicationRecords.Answer.class)
                .setParameter("ref", app.getRef()).getResultStream().forEach(a -> answers.put(a.getCode(), a.getAnswer()));
        List<PortfolioViews.DisclosureItem> disclosures = questions(app.getProduct()).stream()
                .map(q -> new PortfolioViews.DisclosureItem(q.getCode(), q.getQuestion(), q.getAnswerType(),
                        q.isMandatory(), answers.get(q.getCode())))
                .toList();
        List<ApplicationRecords.Evidence> received = evidence(app.getRef());
        List<PortfolioViews.ApplicationEvidenceItem> items = new ArrayList<>();
        for (ApplicationRecords.EvidenceRequirement r : requirements(app)) {
            ApplicationRecords.Evidence e = received.stream().filter(x -> x.getDocumentType().equals(r.getDocumentType()))
                    .findFirst().orElse(null);
            boolean kyc = "KYC".equals(r.getSatisfiedBy()) && "VERIFIED".equals(customer.getKycStatus());
            items.add(new PortfolioViews.ApplicationEvidenceItem(r.getDocumentType(), r.isMandatory(), e != null || kyc,
                    kyc ? "Customer KYC verified" : e == null ? null : e.getNote(),
                    e == null ? null : e.getRecordedBy(), e == null ? null : e.getRecordedAt(), r.getSatisfiedBy()));
        }
        String payer = app.getPayerName() == null ? "The policyholder"
                : app.getPayerName() + (app.getPayerRelationship() == null ? "" : " (" + app.getPayerRelationship() + ")");
        String proposer = app.getProposerName() == null ? "The policyholder"
                : app.getProposerName() + (app.getProposerRelationship() == null ? "" : " (" + app.getProposerRelationship() + ")");
        // Disclosures and the declaration gate submission, so only a draft is asked to fix them. An application
        // submitted before capture existed has none: that is history, not something anyone can still supply.
        boolean draft = DRAFT.equals(app.getStatus());
        boolean preCapture = !draft && app.getDeclaredAt() == null;
        return new PortfolioViews.ApplicationChecklistView(app.getRef(), app.getStatus(), proposer, payer,
                app.getMandateMethod(), app.getMandateInstitution(), mask(app.getMandateAccount()),
                app.getMandateDebitDay(), app.getChannel() == null ? "DIRECT" : app.getChannel(),
                app.getIntermediaryCode(), app.getDeclaredBy(), app.getDeclaredAt(), app.getSubmittedBy(),
                app.getSubmittedAt(), disclosures, items, draft ? submissionProblems(app) : List.of(),
                missingEvidence(app, customer), preCapture, app.getPayerName(), app.getPayerRelationship(),
                app.getRowVersion(), app.getOccupation(), app.getProposerName(), app.getProposerRelationship(),
                app.getProposerRef(), app.getPayerRef(), app.getTermYears(), termOptions(app.getProduct()),
                beneficiaries(app).stream()
                        .map(b -> new PortfolioViews.BeneficiaryView(null, b.fullName(), b.relationship(), b.nationalId(),
                                b.mobile(), null, null, b.primary()))
                        .toList());
    }

    public List<PortfolioViews.DisclosureItem> questionsFor(String product) {
        return questions(product).stream().map(q -> new PortfolioViews.DisclosureItem(q.getCode(), q.getQuestion(),
                q.getAnswerType(), q.isMandatory(), null)).toList();
    }

    List<String> missingEvidence(InsuranceApplication app, CustomerAccount customer) {
        Set<String> received = new HashSet<>();
        evidence(app.getRef()).forEach(e -> received.add(e.getDocumentType()));
        List<String> missing = new ArrayList<>();
        for (ApplicationRecords.EvidenceRequirement r : requirements(app)) {
            boolean kyc = "KYC".equals(r.getSatisfiedBy()) && "VERIFIED".equals(customer.getKycStatus());
            if (r.isMandatory() && !kyc && !received.contains(r.getDocumentType())) {
                missing.add(r.getDocumentType());
            }
        }
        return missing;
    }

    private List<ApplicationRecords.EvidenceRequirement> requirements(InsuranceApplication app) {
        return em.createQuery("select r from ApplicationEvidenceRequirement r where (r.product is null or r.product = :product)"
                        + " and (r.minSumAssured is null or r.minSumAssured <= :amount) order by r.displayOrder",
                        ApplicationRecords.EvidenceRequirement.class)
                .setParameter("product", app.getProduct())
                .setParameter("amount", app.getAmount() == null ? java.math.BigDecimal.ZERO : app.getAmount())
                .getResultList();
    }

    private List<ApplicationRecords.Question> questions(String product) {
        return em.createQuery("select q from ApplicationQuestion q where q.product is null or q.product = :product"
                + " order by q.displayOrder", ApplicationRecords.Question.class).setParameter("product", product).getResultList();
    }

    private List<ApplicationRecords.Evidence> evidence(String ref) {
        if (ref == null) {
            return List.of();
        }
        return em.createQuery("select e from ApplicationEvidence e where e.applicationRef = :ref", ApplicationRecords.Evidence.class)
                .setParameter("ref", ref).getResultList();
    }

    private ApplicationRecords.Answer answerFor(String ref, String code) {
        return em.createQuery("select a from ApplicationAnswer a where a.applicationRef = :ref and a.code = :code",
                ApplicationRecords.Answer.class).setParameter("ref", ref).setParameter("code", code)
                .getResultStream().findFirst().orElse(null);
    }

    /**
     * Why the product may not be sold through the channel (NS-IL01-001), or null. A product with no distribution
     * scope rows is sold on every channel.
     */
    public String distributionProblem(String product, String channel) {
        List<String> channels = em.createQuery("select d.channel from NbDistributionScope d where d.product = :p",
                String.class).setParameter("p", product).getResultList();
        if (channels.isEmpty() || channels.contains(channel)) {
            return null;
        }
        return product + " is not distributed through the " + channel + " channel (sold through: "
                + String.join(", ", channels) + ").";
    }

    /** Payer and proposer relationships: the relationship types other than the main insured, plus the extras. */
    public List<String> relationships() {
        List<String> names = new ArrayList<>(em.createQuery("select r.relationshipName from RelationshipType r"
                + " where r.selfRelationship = false order by r.id", String.class).getResultList());
        for (String extra : extraRelationships) {
            if (!containsIgnoreCase(names, extra)) {
                names.add(extra);
            }
        }
        return names;
    }

    /** The saleable policy terms of a product in years (product servicing rule); empty when it sells none. */
    public List<Integer> termOptions(String product) {
        List<String> found = em.createQuery("select r.termOptions from ServicingRule r where r.product = :p", String.class)
                .setParameter("p", product).getResultList();
        String raw = found.isEmpty() ? null : found.get(0);
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        return java.util.Arrays.stream(raw.split(",")).map(String::trim).filter(v -> !v.isEmpty())
                .map(Integer::valueOf).toList();
    }

    /** The term a policy takes: the one selected, or the longest the product sells when none was chosen. */
    public Integer termFor(InsuranceApplication app) {
        List<Integer> options = termOptions(app.getProduct());
        if (options.isEmpty()) {
            return null;
        }
        return app.getTermYears() != null ? app.getTermYears() : options.stream().max(Integer::compareTo).orElse(null);
    }

    /** The beneficiaries nominated on the application (NS-IL01-002). */
    public List<PolicyWorkflow.BeneficiaryInput> beneficiaries(InsuranceApplication app) {
        List<PolicyWorkflow.BeneficiaryInput> list = new ArrayList<>();
        if (blank(app.getBeneficiaries())) {
            return list;
        }
        try {
            for (JsonNode b : mapper.readTree(app.getBeneficiaries())) {
                list.add(new PolicyWorkflow.BeneficiaryInput(text(b, "fullName"), text(b, "relationship"),
                        text(b, "nationalId"), text(b, "mobile"), null, null, b.path("primary").asBoolean(false)));
            }
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            throw new IllegalStateException(ex);
        }
        return list;
    }

    private String beneficiariesJson(InsuranceApplication app, JsonNode node) {
        if (node == null || node.isNull() || (node.isArray() && node.isEmpty())) {
            return null;
        }
        if (!node.isArray()) {
            throw new IllegalArgumentException("beneficiaries must be a list.");
        }
        int max;
        try {
            max = terms.inForce(app.getProduct(), java.time.LocalDate.now()).getMaxBeneficiaries();
        } catch (IllegalStateException ex) {
            max = 5;
        }
        if (node.size() > max) {
            throw new IllegalArgumentException("A " + app.getProduct() + " policy can have at most " + max
                    + (max == 1 ? " beneficiary." : " beneficiaries."));
        }
        com.fasterxml.jackson.databind.node.ArrayNode out = mapper.createArrayNode();
        boolean primary = false;
        for (JsonNode b : node) {
            String name = text(b, "fullName");
            String relationship = text(b, "relationship");
            if (name == null || name.length() < 2 || relationship == null) {
                throw new IllegalArgumentException("Each beneficiary needs a full name and a relationship.");
            }
            com.fasterxml.jackson.databind.node.ObjectNode row = out.addObject();
            row.put("fullName", clip(name, 100));
            row.put("relationship", clip(relationship, 60));
            row.put("nationalId", clip(text(b, "nationalId"), 40));
            row.put("mobile", clip(text(b, "mobile"), 40));
            boolean isPrimary = b.path("primary").asBoolean(false) && !primary;
            primary = primary || isPrimary;
            row.put("primary", isPrimary);
        }
        return out.toString();
    }

    private CustomerAccount party(String ref, String role) {
        if (ref == null) {
            return null;
        }
        return em.createQuery("select c from CustomerAccount c where c.ref = :ref", CustomerAccount.class)
                .setParameter("ref", ref.trim()).getResultStream().findFirst()
                .orElseThrow(() -> new IllegalArgumentException("The " + role + " " + ref + " is not a known party."));
    }

    private static boolean containsIgnoreCase(List<String> values, String value) {
        return values.stream().anyMatch(v -> v.equalsIgnoreCase(value.trim()));
    }

    private static String mask(String account) {
        if (account == null || account.isBlank()) {
            return null;
        }
        String v = account.trim();
        return v.length() <= 4 ? v : "•••" + v.substring(v.length() - 4);
    }

    private static String text(JsonNode body, String field) {
        JsonNode v = body.get(field);
        return v == null || v.isNull() || v.asText().isBlank() ? null : v.asText().trim();
    }

    private static String upper(String value) {
        return value == null ? null : value.toUpperCase(Locale.ROOT).replace(' ', '_');
    }

    private static String clip(String value, int max) {
        return value == null ? null : value.length() <= max ? value : value.substring(0, max);
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
