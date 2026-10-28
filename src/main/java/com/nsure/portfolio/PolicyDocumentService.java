package com.nsure.portfolio;

import com.nsure.common.exception.NotFoundException;
import com.nsure.security.domain.AppUser;
import com.nsure.security.exception.NoAuthorizationException;
import com.nsure.security.service.PlatformSecurityContext;
import jakarta.persistence.EntityManager;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Documents filed on a policy. Today that is the generated policy schedule: a PDF snapshot of the policy, kept so
 * each version stays on record. Staff need a policy permission; a customer may only touch their own policies.
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

    /** Builds the schedule from the policy as it stands now, files it, and records it in the policy history. */
    private void schedule(InsurancePolicy policy, String generatedBy) {
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
                history.of(WorkflowHistory.POLICY, policy.getRef()), generatedBy);

        String fileName = policy.getRef() + "-schedule-" + LocalDateTime.now().format(FILE_STAMP) + ".pdf";
        String storagePath = files.write(policy.getRef(), "pdf", pdf);
        try {
            em.persist(new PolicyDocument(policy.getId(), SCHEDULE_TYPE, fileName, "application/pdf", pdf.length,
                    storagePath, generatedBy));
            em.flush();
        } catch (RuntimeException e) {
            files.deleteQuietly(storagePath);
            throw e;
        }
        history.record(WorkflowHistory.POLICY, policy.getRef(), policy.getCustomerName(), "Schedule Generated",
                policy.getStatus(), policy.getStatus(), SCHEDULE_TYPE + ": " + fileName, generatedBy);
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
