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
    static final Set<String> CHANNELS = Set.of("DIRECT", "BRANCH", "AGENT", "BROKER", "CALL_CENTRE");
    static final Set<String> MANDATES = Set.of("DEBIT_ORDER", "MOBILE_WALLET", "CARD", "BANK_EFT");

    private final EntityManager em;
    private final Outbox outbox;

    public ApplicationCapture(EntityManager em, Outbox outbox) {
        this.em = em;
        this.outbox = outbox;
    }

    /** Copies whatever the body carries onto the application; answers are stored once it has a reference. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void capture(InsuranceApplication app, JsonNode body, String actor) {
        if (body == null) {
            return;
        }
        if (body.has("proposerName")) {
            app.setProposerName(clip(text(body, "proposerName"), 100));
            app.setProposerRelationship(clip(text(body, "proposerRelationship"), 60));
        }
        if (body.has("payerName")) {
            app.setPayerName(clip(text(body, "payerName"), 100));
            app.setPayerRelationship(clip(text(body, "payerRelationship"), 60));
            app.setPayerIdNumber(clip(text(body, "payerIdNumber"), 40));
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
        if ("DEBIT_ORDER".equals(app.getMandateMethod())
                && (blank(app.getMandateInstitution()) || blank(app.getMandateAccount()) || app.getMandateDebitDay() == null)) {
            problems.add("A debit order needs the bank, the account number and the debit day.");
        }
        if (app.getChannel() != null && Set.of("AGENT", "BROKER").contains(app.getChannel()) && blank(app.getIntermediaryCode())) {
            problems.add("An agent or broker application needs the intermediary's code.");
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
        List<String> problems = submissionProblems(app);
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
                app.getRowVersion());
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
