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
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

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

    public PolicyDocumentService(EntityManager em, PolicyWorkflow policies, WorkflowHistory history,
            PlatformSecurityContext security, PortfolioService portfolio, DocumentFiles files) {
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

    @Transactional(readOnly = true)
    public ClaimDocumentService.StoredFile download(String ref, Long id) {
        security.authenticatedUser().validateHasPermissionTo("READ_POLICY");
        return open(policies.policy(ref), id);
    }

    // ── customer portal ──────────────────────────────────────────────────────────────────────────────

    @Transactional
    public PortfolioViews.ShopView shopGenerateSchedule(String ref) {
        CustomerAccount account = portfolio.customerForLogin();
        schedule(ownPolicy(account, ref), account.getLoginUsername());
        return portfolio.shop();
    }

    @Transactional(readOnly = true)
    public ClaimDocumentService.StoredFile shopDownload(String ref, Long id) {
        return open(ownPolicy(portfolio.customerForLogin(), ref), id);
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
                                d.getUploadedAt().withNano(0).toString())));
        return byPolicy;
    }

    private static List<PortfolioViews.DocumentView> views(EntityManager em, Long policyId) {
        return viewsOf(em, List.of(policyId)).getOrDefault(policyId, List.of());
    }

    private InsurancePolicy ownPolicy(CustomerAccount account, String ref) {
        InsurancePolicy policy = policies.policy(ref);
        if (!policy.getCustomerName().equals(account.getName())) {
            // Not theirs: answer as if it did not exist rather than confirm someone else's policy number.
            throw new NotFoundException("Policy " + ref + " not found");
        }
        return policy;
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
                        + "and d.documentType = :type", Long.class)
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
                        + "and d.layoutVersion < :layout order by d.policyId", Long.class)
                .setParameter("type", SCHEDULE_TYPE)
                .setParameter("layout", PolicySchedulePdf.LAYOUT_VERSION)
                .getResultList();
    }

    /**
     * Replaces every schedule on the policy with one in the current layout, built from the policy as it stands
     * now and credited to whoever issued the first one. The old files are removed only once the change commits.
     * Does nothing if another instance got there first.
     */
    @Transactional
    public int replaceOldSchedules(Long policyId) {
        InsurancePolicy policy = em.find(InsurancePolicy.class, policyId, LockModeType.PESSIMISTIC_WRITE);
        if (policy == null) {
            return 0;
        }
        List<PolicyDocument> schedules = em.createQuery("select d from PolicyDocument d where d.policyId = :policy "
                        + "and d.documentType = :type order by d.id", PolicyDocument.class)
                .setParameter("policy", policyId)
                .setParameter("type", SCHEDULE_TYPE)
                .getResultList();
        if (schedules.stream().allMatch(d -> d.getLayoutVersion() >= PolicySchedulePdf.LAYOUT_VERSION)) {
            return 0;
        }
        List<String> oldFiles = schedules.stream().map(PolicyDocument::getStoragePath).toList();
        schedules.forEach(em::remove);
        em.flush();
        issue(policy, schedules.get(0).getUploadedBy(), "replaces " + schedules.size()
                + (schedules.size() == 1 ? " schedule" : " schedules") + " in the old layout");
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                oldFiles.forEach(files::deleteQuietly);
            }
        });
        return schedules.size();
    }

    /** Renders the schedule from the policy as it stands now, files it, and records it in the policy history. */
    private void issue(InsurancePolicy policy, String generatedBy, String note) {
        CustomerAccount customer = em.createQuery(
                        "select c from CustomerAccount c where c.name = :name order by c.displayOrder, c.id",
                        CustomerAccount.class)
                .setParameter("name", policy.getCustomerName())
                .setMaxResults(1)
                .getResultStream()
                .findFirst()
                .orElse(null);
        InsuranceProduct product = em.createQuery(
                        "select p from InsuranceProduct p where p.name = :name", InsuranceProduct.class)
                .setParameter("name", policy.getProduct())
                .setMaxResults(1)
                .getResultStream()
                .findFirst()
                .orElse(null);
        byte[] pdf = PolicySchedulePdf.render(portfolio.policyView(policy), customer, product,
                policies.months(policy), generatedBy);

        String fileName = policy.getRef() + "-schedule-" + LocalDateTime.now().format(FILE_STAMP) + ".pdf";
        String storagePath = files.write(policy.getRef(), "pdf", pdf);
        try {
            em.persist(new PolicyDocument(policy.getId(), SCHEDULE_TYPE, fileName, "application/pdf", pdf.length,
                    storagePath, generatedBy, PolicySchedulePdf.LAYOUT_VERSION));
            em.flush();
        } catch (RuntimeException e) {
            files.deleteQuietly(storagePath);
            throw e;
        }
        history.record(WorkflowHistory.POLICY, policy.getRef(), policy.getCustomerName(), "Schedule Generated",
                policy.getStatus(), policy.getStatus(),
                SCHEDULE_TYPE + ": " + fileName + (note == null ? "" : " · " + note), generatedBy);
    }

    private ClaimDocumentService.StoredFile open(InsurancePolicy policy, Long id) {
        PolicyDocument document = em.find(PolicyDocument.class, id);
        if (document == null || !document.getPolicyId().equals(policy.getId())) {
            throw new NotFoundException("Document " + id + " not found on policy " + policy.getRef());
        }
        return new ClaimDocumentService.StoredFile(document.getFileName(), document.getContentType(),
                document.getSizeBytes(), files.read(document.getStoragePath(), "document " + id));
    }
}
