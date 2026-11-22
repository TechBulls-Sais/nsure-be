package com.nsure.portfolio;

import com.nsure.common.exception.ConflictException;
import com.nsure.common.exception.NotFoundException;
import com.nsure.security.domain.AppUser;
import com.nsure.security.exception.NoAuthorizationException;
import com.nsure.security.service.PlatformSecurityContext;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Documents filed on a policy. Today that is the policy schedule: the PDF that sets out the policy's cover, premium,
 * lives and beneficiaries. It is issued once per policy, after the first premium starts cover; a new or renewed
 * policy has its own number and gets its own schedule. Staff need a policy permission; a customer may only touch
 * their own policies.
 */
@Service
public class PolicyDocumentService {

    public static final String SCHEDULE_TYPE = "Policy schedule";

    private static final DateTimeFormatter FILE_STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    /** Who may generate on the staff side: anyone who can issue or service a policy. */
    private static final List<String> STAFF_GENERATE = List.of("CREATE_POLICY", "UPDATE_POLICY");

    private final EntityManager em;
    private final PolicyWorkflow policies;
    private final WorkflowHistory history;
    private final PlatformSecurityContext security;
    private final PortfolioService portfolio;
    private final DocumentFiles files;
    private final DocumentCustody custody;
    private final com.fasterxml.jackson.databind.ObjectMapper mapper;

    public PolicyDocumentService(EntityManager em, PolicyWorkflow policies, WorkflowHistory history,
            PlatformSecurityContext security, PortfolioService portfolio, DocumentFiles files,
            DocumentCustody custody, com.fasterxml.jackson.databind.ObjectMapper mapper) {
        this.custody = custody;
        this.mapper = mapper;
        this.em = em;
        this.policies = policies;
        this.history = history;
        this.security = security;
        this.portfolio = portfolio;
        this.files = files;
    }

    // ── staff ────────────────────────────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<PortfolioViews.DocumentView> list(String ref) {
        security.authenticatedUser().validateHasPermissionTo("READ_POLICY");
        return views(em, policies.policy(ref).getId());
    }

    @Transactional
    public List<PortfolioViews.DocumentView> generateSchedule(String ref) {
        AppUser staff = security.authenticatedUser();
        if (STAFF_GENERATE.stream().noneMatch(staff::hasPermissionTo)) {
            throw new NoAuthorizationException("User has no authority to generate policy documents");
        }
        InsurancePolicy policy = policies.policy(ref);
        schedule(policy, staff.getUsername());
        return views(em, policy.getId());
    }

    @Transactional
    public ClaimDocumentService.StoredFile download(String ref, Long id) {
        AppUser staff = security.authenticatedUser();
        staff.validateHasPermissionTo("READ_POLICY");
        return open(policies.policy(ref), id, staff.getUsername(), DocumentCustody.STAFF);
    }

    /**
     * A controlled replacement (Blueprint §8): issues a new schedule from the policy as it stands now and marks the
     * given one SUPERSEDED with the reason and a link to the new one. The old file is kept.
     */
    @Transactional
    public List<PortfolioViews.DocumentView> reissue(String ref, Long id, String reason) {
        AppUser staff = security.authenticatedUser();
        if (STAFF_GENERATE.stream().noneMatch(staff::hasPermissionTo)) {
            throw new NoAuthorizationException("User has no authority to reissue policy documents");
        }
        String why = reason == null ? "" : reason.trim();
        if (why.length() < 5 || why.length() > 500) {
            throw new IllegalArgumentException("Give the reason for the replacement (5 to 500 characters).");
        }
        InsurancePolicy policy = policies.policy(ref);
        em.lock(policy, LockModeType.PESSIMISTIC_WRITE);
        PolicyDocument old = em.find(PolicyDocument.class, id);
        if (old == null || !old.getPolicyId().equals(policy.getId())) {
            throw new NotFoundException("Document " + id + " not found on policy " + ref);
        }
        if (!SCHEDULE_TYPE.equals(old.getDocumentType()) || !old.isCurrent()) {
            throw new ConflictException("Only the current policy schedule can be reissued.");
        }
        PolicyDocument replacement = issue(policy, staff.getUsername(), "replaces " + old.getFileName() + ": " + why);
        old.supersede(replacement.getId(), why);
        return views(em, policy.getId());
    }

    /**
     * SH09A template preview: the schedule as it would be issued now, from the current policy data and wording, marked
     * as a preview. Nothing is filed, hashed or sent; it is not a document of record.
     */
    @Transactional(readOnly = true)
    public ClaimDocumentService.StoredFile previewSchedule(String ref) {
        AppUser staff = security.authenticatedUser();
        if (STAFF_GENERATE.stream().noneMatch(staff::hasPermissionTo)) {
            throw new NoAuthorizationException("User has no authority to preview policy documents");
        }
        InsurancePolicy policy = policies.policy(ref);
        CustomerAccount customer = customerOf(policy);
        byte[] pdf = PolicySchedulePdf.render(portfolio.policyView(policy), customer, productOf(policy),
                policies.terms(policy), policies.months(policy),
                staff.getUsername() + " · PREVIEW, NOT ISSUED", wording(policy.getProduct()));
        return new ClaimDocumentService.StoredFile(policy.getRef() + "-schedule-PREVIEW.pdf", "application/pdf",
                pdf.length, new org.springframework.core.io.ByteArrayResource(pdf));
    }

    /** Who downloaded this policy's documents, and when (READ_AUDIT). */
    @Transactional(readOnly = true)
    public List<DocumentCustody.AccessView> accessLog(String ref) {
        security.authenticatedUser().validateHasPermissionTo("READ_AUDIT");
        return custody.accessLog(policies.policy(ref).getRef());
    }

    // ── customer portal ──────────────────────────────────────────────────────────────────────────────

    @Transactional
    public PortfolioViews.ShopView shopGenerateSchedule(String ref) {
        CustomerAccount account = portfolio.customerForLogin();
        schedule(ownPolicy(account, ref), account.getLoginUsername());
        return portfolio.shop();
    }

    @Transactional
    public ClaimDocumentService.StoredFile shopDownload(String ref, Long id) {
        CustomerAccount account = portfolio.customerForLogin();
        return open(ownPolicy(account, ref), id, account.getLoginUsername(), DocumentCustody.PORTAL);
    }

    /** The documents of each given policy, oldest first, keyed by policy id, in one query. */
    static Map<Long, List<PortfolioViews.DocumentView>> viewsOf(EntityManager em, List<Long> policyIds) {
        Map<Long, List<PortfolioViews.DocumentView>> byPolicy = new HashMap<>();
        if (policyIds.isEmpty()) {
            return byPolicy;
        }
        em.createQuery("select d from PolicyDocument d where d.policyId in :ids order by d.id", PolicyDocument.class)
                .setParameter("ids", policyIds)
                .getResultStream()
                .forEach(d -> byPolicy.computeIfAbsent(d.getPolicyId(), key -> new ArrayList<>()).add(
                        new PortfolioViews.DocumentView(d.getId(), d.getDocumentType(), d.getFileName(),
                                d.getContentType(), d.getSizeBytes(), d.getUploadedBy(),
                                d.getUploadedAt().withNano(0).toString(), d.getStatus(), d.getSha256(),
                                d.getSupersededBy(), d.getSupersededReason(), DocumentIntake.GENERATED,
                                DocumentIntake.sourceLabel(DocumentIntake.SYSTEM),
                                DocumentIntake.classification(d.getDocumentType()), generatedFrom(d), null)));
        return byPolicy;
    }

    /** "Layout v3 · wording WRD-0001 v1, WRD-0002 v1 · policy data version 4": what the schedule was made from. */
    private static String generatedFrom(PolicyDocument d) {
        if (d.getSnapshotSha256() == null) {
            return "Layout v" + d.getLayoutVersion() + "; issued before wording and data versions were recorded.";
        }
        return "Layout v" + d.getLayoutVersion() + " · wording "
                + (d.getWordingVersions() == null || d.getWordingVersions().isBlank() ? "none on file" : d.getWordingVersions())
                + " · policy data version " + d.getDataVersion() + " · data snapshot SHA-256 "
                + d.getSnapshotSha256().substring(0, 12) + "…";
    }

    static List<PortfolioViews.DocumentView> views(EntityManager em, Long policyId) {
        return viewsOf(em, List.of(policyId)).getOrDefault(policyId, List.of());
    }

    private InsurancePolicy ownPolicy(CustomerAccount account, String ref) {
        InsurancePolicy policy = policies.policy(ref);
        if (!account.getRef().equals(policy.getCustomerRef())) {
            // Not theirs: answer as if it did not exist rather than confirm someone else's policy number.
            throw new NotFoundException("Policy " + ref + " not found");
        }
        return policy;
    }

    /**
     * Spec 01 BR-60: the schedule is generated and attached when the first premium is paid. Runs after the payment has
     * committed (an outbox handler), so a PDF failure is retried from the failed-delivery queue and never touches the
     * payment (spec 13 S-15/S-16). Idempotent: a schedule already on file is left alone.
     */
    @Transactional
    public void issueOnActivation(String ref) {
        InsurancePolicy policy = policies.policy(ref);
        if (policy.getCoverStartDate() == null) {
            return;
        }
        Long existing = em.createQuery("select count(d) from PolicyDocument d where d.policyId = :policy "
                        + "and d.documentType = :type and d.status = 'CURRENT'", Long.class)
                .setParameter("policy", policy.getId())
                .setParameter("type", SCHEDULE_TYPE)
                .getSingleResult();
        if (existing == 0) {
            schedule(policy, WorkflowHistory.SYSTEM);
        }
    }

    /**
     * Issues the policy's one schedule, files it, and records it in the policy history. Refused before cover starts
     * and once a schedule is on file; the row lock means a double click, or two users at once, issue only one.
     */
    private void schedule(InsurancePolicy policy, String generatedBy) {
        em.lock(policy, LockModeType.PESSIMISTIC_WRITE);
        if (policy.getCoverStartDate() == null) {
            throw new ConflictException("Policy " + policy.getRef() + " is " + policy.getStatus()
                    + ". Its schedule is issued once the first premium is paid and cover starts.");
        }
        Long existing = em.createQuery("select count(d) from PolicyDocument d where d.policyId = :policy "
                        + "and d.documentType = :type and d.status = 'CURRENT'", Long.class)
                .setParameter("policy", policy.getId())
                .setParameter("type", SCHEDULE_TYPE)
                .getSingleResult();
        if (existing > 0) {
            throw new ConflictException("Policy " + policy.getRef() + " already has its policy schedule. "
                    + "A schedule is issued once per policy; a new or renewed policy gets its own.");
        }
        issue(policy, generatedBy, null);
    }

    /** Ids of the policies holding a schedule made in an older layout, for {@link DocumentLayoutUpgrade}. */
    @Transactional(readOnly = true)
    public List<Long> policiesWithOldSchedules() {
        return em.createQuery("select distinct d.policyId from PolicyDocument d where d.documentType = :type "
                        + "and d.status = 'CURRENT' and d.layoutVersion < :layout order by d.policyId", Long.class)
                .setParameter("type", SCHEDULE_TYPE)
                .setParameter("layout", PolicySchedulePdf.LAYOUT_VERSION)
                .getResultList();
    }

    /**
     * Issues one schedule in the current layout, built from the policy as it stands now and credited to whoever issued
     * the first one, and marks the old-layout schedules SUPERSEDED by it (Blueprint §8: the files issued earlier are
     * kept, never overwritten). Does nothing if another instance got there first.
     */
    @Transactional
    public int replaceOldSchedules(Long policyId) {
        InsurancePolicy policy = em.find(InsurancePolicy.class, policyId, LockModeType.PESSIMISTIC_WRITE);
        if (policy == null) {
            return 0;
        }
        List<PolicyDocument> schedules = em.createQuery("select d from PolicyDocument d where d.policyId = :policy "
                        + "and d.documentType = :type and d.status = 'CURRENT' order by d.id", PolicyDocument.class)
                .setParameter("policy", policyId)
                .setParameter("type", SCHEDULE_TYPE)
                .getResultList();
        if (schedules.isEmpty() || schedules.stream().allMatch(d -> d.getLayoutVersion() >= PolicySchedulePdf.LAYOUT_VERSION)) {
            return 0;
        }
        PolicyDocument replacement = issue(policy, schedules.get(0).getUploadedBy(), "replaces " + schedules.size()
                + (schedules.size() == 1 ? " schedule" : " schedules") + " in the old layout, kept as superseded");
        String reason = "Reissued in the current schedule layout (v" + PolicySchedulePdf.LAYOUT_VERSION + ")";
        schedules.forEach(old -> old.supersede(replacement.getId(), reason));
        return schedules.size();
    }

    /**
     * Freezes the policy as it stands now (under the caller's row lock) into a data snapshot with the approved wording
     * clauses for its product, renders the schedule from that, and files the PDF and the snapshot together with their
     * hashes, the wording versions and the policy's data version (NS-SH09A-003). Records it in the policy history.
     */
    private PolicyDocument issue(InsurancePolicy policy, String generatedBy, String note) {
        CustomerAccount customer = customerOf(policy);
        InsuranceProduct product = productOf(policy);
        PortfolioViews.CoverView view = portfolio.policyView(policy);
        ProductVersion terms = policies.terms(policy);
        int months = policies.months(policy);
        List<PolicySchedulePdf.Clause> wording = wording(policy.getProduct());
        String wordingVersions = String.join(", ", wording.stream().map(c -> c.ref() + " v" + c.version()).toList());
        byte[] snapshot = snapshot(policy, view, customer, product, terms, months, wording, generatedBy);
        byte[] pdf = PolicySchedulePdf.render(view, customer, product, terms, months, generatedBy, wording);

        String fileName = policy.getRef() + "-schedule-" + LocalDateTime.now().format(FILE_STAMP) + ".pdf";
        String storagePath = files.write(policy.getRef(), "pdf", pdf);
        String snapshotPath = files.write(policy.getRef(), "json", snapshot);
        PolicyDocument document = new PolicyDocument(policy.getId(), SCHEDULE_TYPE, fileName, "application/pdf",
                pdf.length, storagePath, generatedBy, PolicySchedulePdf.LAYOUT_VERSION);
        document.seal(DocumentCustody.sha256(pdf));
        document.generatedFrom(wordingVersions, policy.getRowVersion(), snapshotPath, DocumentCustody.sha256(snapshot));
        try {
            em.persist(document);
            em.flush();
        } catch (RuntimeException e) {
            files.deleteQuietly(storagePath);
            files.deleteQuietly(snapshotPath);
            throw e;
        }
        DocumentText.index(em, DocumentText.POLICY_TABLE, document.getId(), pdf, "application/pdf");
        history.record(WorkflowHistory.POLICY, policy.getRef(), policy.getCustomerName(), "Schedule Generated",
                policy.getStatus(), policy.getStatus(),
                SCHEDULE_TYPE + ": " + fileName + (note == null ? "" : " · " + note), generatedBy);
        return document;
    }

    private CustomerAccount customerOf(InsurancePolicy policy) {
        return policy.getCustomerRef() == null ? null : em.createQuery(
                        "select c from CustomerAccount c where c.ref = :ref", CustomerAccount.class)
                .setParameter("ref", policy.getCustomerRef())
                .setMaxResults(1)
                .getResultStream()
                .findFirst()
                .orElse(null);
    }

    private InsuranceProduct productOf(InsurancePolicy policy) {
        return em.createQuery("select p from InsuranceProduct p where p.name = :name", InsuranceProduct.class)
                .setParameter("name", policy.getProduct())
                .setMaxResults(1)
                .getResultStream()
                .findFirst()
                .orElse(null);
    }

    /** The approved, effective wording clauses for the product, in reference order (NS-SH09A-001). */
    private List<PolicySchedulePdf.Clause> wording(String product) {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery("select record_ref, version_no, clause_type, title, body from doc_wording"
                        + " where product = ?1 and status = 'APPROVED' and (effective_from is null or effective_from <= ?2)"
                        + " order by record_ref")
                .setParameter(1, product)
                .setParameter(2, java.sql.Date.valueOf(java.time.LocalDate.now()))
                .getResultList();
        return rows.stream().map(r -> new PolicySchedulePdf.Clause((String) r[0], ((Number) r[1]).intValue(),
                (String) r[2], (String) r[3], (String) r[4])).toList();
    }

    /** The data the schedule is rendered from, as JSON: kept beside the PDF so the issue can be traced and replayed. */
    private byte[] snapshot(InsurancePolicy policy, PortfolioViews.CoverView view, CustomerAccount customer,
            InsuranceProduct product, ProductVersion terms, int months, List<PolicySchedulePdf.Clause> wording,
            String generatedBy) {
        Map<String, Object> data = new java.util.LinkedHashMap<>();
        data.put("policyRef", policy.getRef());
        data.put("dataVersion", policy.getRowVersion());
        data.put("takenAt", LocalDateTime.now().toString());
        data.put("issuedBy", generatedBy);
        data.put("layoutVersion", PolicySchedulePdf.LAYOUT_VERSION);
        data.put("policy", view);
        if (customer != null) {
            Map<String, Object> c = new java.util.LinkedHashMap<>();
            c.put("ref", customer.getRef());
            c.put("dateOfBirth", customer.getDateOfBirth() == null ? null : customer.getDateOfBirth().toString());
            c.put("poiType", customer.getPoiType());
            c.put("email", customer.getEmail());
            c.put("phone", customer.getPhone());
            data.put("customer", c);
        }
        if (product != null) {
            data.put("product", Map.of("summary", String.valueOf(product.getSummary()),
                    "benefits", String.valueOf(product.getBenefits())));
        }
        if (terms != null) {
            data.put("terms", Map.of("label", String.valueOf(terms.getLabel()),
                    "wordingRef", String.valueOf(terms.getWordingRef()),
                    "waitingPeriodDays", terms.getWaitingPeriodDays(), "gracePeriodDays", terms.getGracePeriodDays()));
        }
        data.put("premiumMonths", months);
        data.put("wording", wording);
        try {
            return mapper.writeValueAsBytes(data);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("The schedule data could not be frozen", e);
        }
    }

    private ClaimDocumentService.StoredFile open(InsurancePolicy policy, Long id, String username, String channel) {
        PolicyDocument document = em.find(PolicyDocument.class, id);
        if (document == null || !document.getPolicyId().equals(policy.getId())) {
            throw new NotFoundException("Document " + id + " not found on policy " + policy.getRef());
        }
        custody.verify(document.getSha256(), document.getStoragePath(), "document " + id);
        custody.logAccess(DocumentCustody.POLICY, id, policy.getRef(), document.getFileName(), username, channel);
        return new ClaimDocumentService.StoredFile(document.getFileName(), document.getContentType(),
                document.getSizeBytes(), files.read(document.getStoragePath(), "document " + id));
    }
}
