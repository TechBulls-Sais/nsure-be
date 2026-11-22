package com.nsure.portfolio;

import com.nsure.common.exception.ConflictException;
import com.nsure.common.exception.NotFoundException;
import com.nsure.security.domain.AppUser;
import com.nsure.security.service.PlatformSecurityContext;
import jakarta.persistence.EntityManager;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

/**
 * Identity evidence for KYC (NS-SH01-004, spec 04 customer self-service): a customer whose identity is not yet
 * verified uploads a copy of their Omang or passport (and proof of address) from the portal; staff who review KYC
 * see and open it on the customer record. Uploading never verifies anyone: a KYC reviewer still decides. A customer
 * whose KYC was rejected who sends new evidence is put back in the review queue (Pending).
 */
@Service
public class IdentityDocumentService {

    public static final long MAX_BYTES = 10L * 1024 * 1024;
    public static final List<String> TYPES = List.of("Omang", "Passport", "Proof of address");
    /** Uploads a customer may have on file; enough for front, back and proof of address several times over. */
    static final int MAX_FILES = 10;

    /**
     * One identity document. {@code state} is CURRENT for a released document of record, or QUARANTINED / REJECTED
     * for an upload still being checked or refused (no id: it cannot be opened), with {@code note} saying why.
     */
    public record IdentityDocumentView(Long id, String documentType, String fileName, String contentType, long size,
            String uploadedBy, LocalDateTime uploadedAt, String sha256, String state, String note) {
    }

    private final EntityManager em;
    private final PlatformSecurityContext security;
    private final PortfolioService portfolio;
    private final DocumentFiles files;
    private final DocumentCustody custody;
    private final WorkflowHistory history;
    private final PartyRegister parties;
    private final DocumentQuarantine quarantine;

    public IdentityDocumentService(EntityManager em, PlatformSecurityContext security, PortfolioService portfolio,
            DocumentFiles files, DocumentCustody custody, WorkflowHistory history, PartyRegister parties,
            DocumentQuarantine quarantine) {
        this.quarantine = quarantine;
        this.em = em;
        this.security = security;
        this.portfolio = portfolio;
        this.files = files;
        this.custody = custody;
        this.history = history;
        this.parties = parties;
    }

    // ── the customer's own (portal) ───────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<IdentityDocumentView> mine() {
        return list(portfolio.customerForLogin().getRef());
    }

    @Transactional
    public List<IdentityDocumentView> upload(MultipartFile file, String type) {
        CustomerAccount customer = portfolio.customerForLogin();
        String actor = security.authenticatedUser().getUsername();
        if ("VERIFIED".equals(customer.getKycStatus())) {
            throw new ConflictException("Your identity is already verified; no more documents are needed.");
        }
        String documentType = type == null || type.isBlank() ? null : type.trim();
        if (documentType == null || !TYPES.contains(documentType)) {
            throw new IllegalArgumentException("Document type must be one of: " + String.join(", ", TYPES) + ".");
        }
        Long held = em.createQuery("select count(d) from IdentityDocument d where d.customerRef = :ref", Long.class)
                .setParameter("ref", customer.getRef()).getSingleResult();
        if (held >= MAX_FILES) {
            throw new IllegalArgumentException("You have already sent " + MAX_FILES + " documents. Our team will"
                    + " contact you if anything else is needed.");
        }
        // NS-SH09A-004: identity evidence waits in quarantine until someone other than the uploader releases it.
        DocumentIntake.Upload upload = DocumentIntake.read(file);
        quarantine.receiveEvidence(DocumentQuarantine.IDENTITY, customer.getId(), customer.getRef(), customer.getName(),
                upload, documentType, actor, DocumentCustody.PORTAL);
        String previous = customer.getKycStatus();
        if ("FAILED".equals(previous)) {
            // New evidence after a rejection: the review opens again; the rejection stays in the history.
            customer.setKycStatus("PENDING");
            customer.setStatus("Pending KYC");
            customer.setKycRejectionReason(null);
            parties.kycChanged(customer, "KYC", previous, "PENDING", actor);
        }
        // The review log (and with it the audit chain) shows what arrived, for the reviewer.
        return list(customer.getRef());
    }

    /** Staff upload identity evidence the customer handed in (branch, email): quarantined like a portal upload. */
    @Transactional
    public List<IdentityDocumentView> staffUpload(String customerRef, MultipartFile file, String type) {
        AppUser user = security.authenticatedUser();
        user.validateHasPermissionTo("UPDATE_CUSTOMER");
        CustomerAccount customer = customer(customerRef);
        String documentType = type == null || type.isBlank() ? null : type.trim();
        if (documentType == null || !TYPES.contains(documentType)) {
            throw new IllegalArgumentException("Document type must be one of: " + String.join(", ", TYPES) + ".");
        }
        DocumentIntake.Upload upload = DocumentIntake.read(file);
        quarantine.receiveEvidence(DocumentQuarantine.IDENTITY, customer.getId(), customer.getRef(), customer.getName(),
                upload, documentType, user.getUsername(), DocumentCustody.STAFF);
        return list(customer.getRef());
    }

    // ── staff (KYC review) ───────────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<IdentityDocumentView> forCustomer(String customerRef) {
        security.authenticatedUser().validateHasPermissionTo("READ_CUSTOMER");
        return list(customer(customerRef).getRef());
    }

    /** Opens one file: its hash is checked and the access is logged (NS-SH11-005). */
    @Transactional
    public ClaimDocumentService.StoredFile download(String customerRef, Long id) {
        AppUser user = security.authenticatedUser();
        user.validateHasPermissionTo("READ_CUSTOMER");
        CustomerAccount customer = customer(customerRef);
        IdentityDocument document = em.find(IdentityDocument.class, id);
        if (document == null || !document.getCustomerRef().equals(customer.getRef())) {
            throw new NotFoundException("Document " + id + " not found for customer " + customer.getRef());
        }
        custody.verify(document.getSha256(), document.getStoragePath(), "identity document " + id);
        custody.logAccess("IDENTITY", id, customer.getRef(), document.getFileName(), user.getUsername(),
                DocumentCustody.STAFF);
        return new ClaimDocumentService.StoredFile(document.getFileName(), document.getContentType(),
                document.getSizeBytes(), files.read(document.getStoragePath(), "identity document " + id));
    }

    // ── internals ────────────────────────────────────────────────────────────────────────────────

    private List<IdentityDocumentView> list(String customerRef) {
        List<IdentityDocumentView> out = new java.util.ArrayList<>();
        for (Map<String, Object> h : quarantine.held(DocumentQuarantine.IDENTITY, customerRef)) {
            out.add(new IdentityDocumentView(null, (String) h.get("documentType"), (String) h.get("fileName"), null, 0,
                    (String) h.get("uploadedBy"), LocalDateTime.parse((String) h.get("uploadedAt")), null,
                    (String) h.get("state"), (String) h.get("note")));
        }
        em.createQuery("select d from IdentityDocument d where d.customerRef = :ref order by d.id desc",
                        IdentityDocument.class)
                .setParameter("ref", customerRef).getResultStream()
                .map(d -> new IdentityDocumentView(d.getId(), d.getDocumentType(), d.getFileName(), d.getContentType(),
                        d.getSizeBytes(), d.getUploadedBy(), d.getUploadedAt(), d.getSha256(), "CURRENT", ""))
                .forEach(out::add);
        return out;
    }

    private CustomerAccount customer(String ref) {
        return em.createQuery("select c from CustomerAccount c where c.ref = :ref", CustomerAccount.class)
                .setParameter("ref", ref == null ? "" : ref.trim().toUpperCase(Locale.ROOT))
                .getResultStream().findFirst()
                .orElseThrow(() -> new NotFoundException("Customer " + ref + " not found"));
    }
}
