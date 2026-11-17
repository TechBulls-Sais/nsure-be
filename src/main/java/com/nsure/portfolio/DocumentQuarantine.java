package com.nsure.portfolio;

import com.fasterxml.jackson.databind.JsonNode;
import com.nsure.audit.evidence.EvidenceChain;
import com.nsure.common.exception.ConflictException;
import com.nsure.common.exception.NotFoundException;
import com.nsure.security.domain.AppUser;
import com.nsure.security.exception.NoAuthorizationException;
import com.nsure.security.service.PlatformSecurityContext;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The upload quarantine (NS-SH09A-004, AT-SH09A-02). Every uploaded file enters it: it is written to storage and
 * hashed, but it is not a document of record, so it is never served by the normal download endpoints and never
 * counts towards an evidence checklist. It leaves quarantine in one of two ways:
 *
 * <ul>
 *   <li><b>released</b>: by a configured {@link UploadScanner} reporting it clean, or, since no scanner ships, by a
 *       person holding RELEASE_DOCUMENT who is not the uploader, with a written note. A person's release is recorded
 *       as RELEASED_UNSCANNED, never as clean, and goes to the evidence chain;</li>
 *   <li><b>rejected</b>: by that person with a reason the uploader sees, or by a scanner finding. The file is kept but
 *       never served except to the reviewer.</li>
 * </ul>
 *
 * A reviewer may open the file to decide (logged as sensitive access).
 */
@Service
public class DocumentQuarantine {

    public static final String CLAIM = DocumentCustody.CLAIM;
    private static final String RELEASE = "RELEASE_DOCUMENT";
    private static final Set<String> STATES = Set.of(QuarantinedUpload.PENDING, QuarantinedUpload.RELEASED,
            QuarantinedUpload.REJECTED);
    private static final Set<String> CLOSED = Set.of(ClaimWorkflow.PAID, ClaimWorkflow.REJECTED);

    private final EntityManager em;
    private final PlatformSecurityContext security;
    private final DocumentFiles files;
    private final DocumentCustody custody;
    private final WorkflowHistory history;
    private final EvidenceChain audit;
    private final ObjectProvider<UploadScanner> scanners;

    public DocumentQuarantine(EntityManager em, PlatformSecurityContext security, DocumentFiles files,
            DocumentCustody custody, WorkflowHistory history, EvidenceChain audit,
            ObjectProvider<UploadScanner> scanners) {
        this.em = em;
        this.security = security;
        this.files = files;
        this.custody = custody;
        this.history = history;
        this.audit = audit;
        this.scanners = scanners;
    }

    /** One upload in the quarantine queue. */
    public record UploadView(Long id, String ownerKind, String ownerRef, String customerName, String documentType,
            String classification, String fileName, String contentType, long size, String sha256, String uploadedBy,
            String channel, String uploadedAt, String status, String scanResult, String scanDetail, String decidedBy,
            String decidedAt, String decisionReason, Long documentId) {
    }

    // ── intake (inside the upload's transaction) ─────────────────────────────────────────────────────

    /**
     * Holds a validated upload in quarantine: stores the bytes, records the row and the claim history, and applies a
     * scanner's verdict if one is configured. Nothing here makes the file a claim document unless a scanner passes it.
     */
    QuarantinedUpload receive(InsuranceClaim claim, byte[] bytes, String fileName, String extension, String contentType,
            String documentType, String uploadedBy, String channel) {
        UploadScanner scanner = scanners.getIfAvailable();
        UploadScanner.Verdict verdict = scanner == null ? UploadScanner.NO_SCANNER : verdict(scanner, fileName, bytes);
        String storagePath = files.write(claim.getRef(), extension, bytes);
        QuarantinedUpload upload = new QuarantinedUpload(CLAIM, claim.getId(), claim.getRef(), documentType, fileName,
                contentType, bytes.length, storagePath, DocumentCustody.sha256(bytes), uploadedBy, channel, verdict);
        try {
            em.persist(upload);
            em.flush();
        } catch (RuntimeException e) {
            files.deleteQuietly(storagePath);
            throw e;
        }
        history.record(WorkflowHistory.CLAIM, claim.getRef(), claim.getCustomerName(), "Document Received",
                claim.getStatus(), claim.getStatus(), documentType + ": " + fileName
                        + " · held in quarantine until released", uploadedBy);
        String by = scanner == null ? null : "scanner:" + scanner.name();
        if (UploadScanner.CLEAN.equals(verdict.result())) {
            file(claim, upload, by, verdict.detail(), DocumentIntake.CLEAN);
        } else if (UploadScanner.INFECTED.equals(verdict.result())) {
            upload.rejected(by, "Malware found: " + verdict.detail());
            audit.record(by, "Upload rejected by scanner", "Claim", claim.getRef(),
                    fileName + ": " + verdict.detail());
        }
        return upload;
    }

    private static UploadScanner.Verdict verdict(UploadScanner scanner, String fileName, byte[] bytes) {
        try {
            UploadScanner.Verdict verdict = scanner.scan(fileName, bytes);
            if (verdict == null || !Set.of(UploadScanner.CLEAN, UploadScanner.INFECTED, UploadScanner.NOT_SCANNED)
                    .contains(verdict.result())) {
                return new UploadScanner.Verdict(UploadScanner.NOT_SCANNED, scanner.name() + " gave no verdict.");
            }
            return verdict;
        } catch (RuntimeException e) {
            // A scanner outage holds the file for a person; it never lets it through.
            return new UploadScanner.Verdict(UploadScanner.NOT_SCANNED, scanner.name() + " failed: " + e.getMessage());
        }
    }

    // ── the queue ────────────────────────────────────────────────────────────────────────────────────

    /** Uploads in a state (PENDING by default, or ALL), newest first. Claim uploads need READ_CLAIM. */
    @Transactional(readOnly = true)
    public List<UploadView> list(String status) {
        security.authenticatedUser().validateHasPermissionTo("READ_CLAIM");
        String state = status == null || status.isBlank() ? QuarantinedUpload.PENDING
                : status.trim().toUpperCase(Locale.ROOT);
        if (!"ALL".equals(state) && !STATES.contains(state)) {
            throw new IllegalArgumentException("Status must be PENDING, RELEASED, REJECTED or ALL.");
        }
        var query = em.createQuery("select q from QuarantinedUpload q"
                + ("ALL".equals(state) ? "" : " where q.status = :status") + " order by q.id desc", QuarantinedUpload.class);
        if (!"ALL".equals(state)) {
            query.setParameter("status", state);
        }
        return query.setMaxResults(500).getResultStream().map(this::view).toList();
    }

    /** The reviewer opens the held file to decide; logged as sensitive access. Nobody else can open it. */
    @Transactional
    public ClaimDocumentService.StoredFile inspect(Long id) {
        AppUser user = security.authenticatedUser();
        user.validateHasPermissionTo(RELEASE);
        QuarantinedUpload upload = upload(id);
        custody.verify(upload.getSha256(), upload.getStoragePath(), "upload " + id);
        custody.logAccess("UPLOAD", id, upload.getOwnerRef(), upload.getFileName(), user.getUsername(),
                DocumentCustody.STAFF);
        return new ClaimDocumentService.StoredFile(upload.getFileName(), upload.getContentType(), upload.getSizeBytes(),
                files.read(upload.getStoragePath(), "upload " + id));
    }

    /** {note}: files the upload as a claim document, without a malware scan; the note says what was checked. */
    @Transactional
    public UploadView release(Long id, JsonNode body) {
        AppUser user = reviewer();
        String note = reason(body, "note", "Say what you checked before releasing the file (5 to 500 characters).");
        QuarantinedUpload upload = pending(id, user);
        InsuranceClaim claim = em.find(InsuranceClaim.class, upload.getOwnerId(), LockModeType.PESSIMISTIC_WRITE);
        if (claim == null) {
            throw new NotFoundException("Claim " + upload.getOwnerRef() + " not found");
        }
        if (CLOSED.contains(claim.getStatus())) {
            throw new ConflictException("Claim " + claim.getRef() + " is " + claim.getStatus()
                    + ", so no more documents can be added. Reject the upload instead.");
        }
        custody.verify(upload.getSha256(), upload.getStoragePath(), "upload " + id);
        file(claim, upload, user.getUsername(), note, DocumentIntake.RELEASED_UNSCANNED);
        audit.record(user.getUsername(), "Upload released without a malware scan", "Claim", claim.getRef(),
                upload.getDocumentType() + ": " + upload.getFileName() + " (uploaded by " + upload.getUploadedBy()
                        + "): " + note);
        return view(upload);
    }

    /** {reason}: refuses the upload; the uploader sees the reason, and the file is never served. */
    @Transactional
    public UploadView reject(Long id, JsonNode body) {
        AppUser user = reviewer();
        String reason = reason(body, "reason", "Give the reason the file is not accepted (5 to 500 characters).");
        QuarantinedUpload upload = pending(id, user);
        upload.rejected(user.getUsername(), reason);
        InsuranceClaim claim = em.find(InsuranceClaim.class, upload.getOwnerId());
        if (claim != null) {
            history.record(WorkflowHistory.CLAIM, claim.getRef(), claim.getCustomerName(), "Document Rejected",
                    claim.getStatus(), claim.getStatus(), upload.getDocumentType() + ": " + upload.getFileName()
                            + " · " + reason, user.getUsername());
        }
        audit.record(user.getUsername(), "Upload rejected", "Claim", upload.getOwnerRef(),
                upload.getDocumentType() + ": " + upload.getFileName() + ": " + reason);
        return view(upload);
    }

    /** Files the upload as a current claim document, credited to its uploader, and links the two. */
    private void file(InsuranceClaim claim, QuarantinedUpload upload, String releasedBy, String note, String scanStatus) {
        ClaimDocument document = new ClaimDocument(claim.getId(), upload.getDocumentType(), upload.getFileName(),
                upload.getContentType(), upload.getSizeBytes(), upload.getStoragePath(), upload.getUploadedBy(), 0);
        document.seal(upload.getSha256());
        document.intake(scanStatus, upload.getChannel(), releasedBy, upload.getUploadedAt());
        em.persist(document);
        em.flush();
        // Indexed for the register's content search only now that it is released (NS-SH09A-005).
        DocumentText.index(em, DocumentText.CLAIM_TABLE, document.getId(),
                files.bytes(upload.getStoragePath(), "upload " + upload.getId()), upload.getContentType());
        upload.released(releasedBy, note, document.getId());
        history.record(WorkflowHistory.CLAIM, claim.getRef(), claim.getCustomerName(), "Document Added",
                claim.getStatus(), claim.getStatus(), upload.getDocumentType() + ": " + upload.getFileName()
                        + " · released from quarantine by " + releasedBy, releasedBy);
    }

    // ── document lists ───────────────────────────────────────────────────────────────────────────────

    /** The uploads on a claim still held or refused, as entries in its document list (no id: they cannot be opened). */
    static List<PortfolioViews.DocumentView> heldViews(EntityManager em, Long claimId) {
        return em.createQuery("select q from QuarantinedUpload q where q.ownerKind = :kind and q.ownerId = :claim"
                        + " and q.status <> :released order by q.id", QuarantinedUpload.class)
                .setParameter("kind", CLAIM)
                .setParameter("claim", claimId)
                .setParameter("released", QuarantinedUpload.RELEASED)
                .getResultStream()
                .map(q -> new PortfolioViews.DocumentView(null, q.getDocumentType(), q.getFileName(),
                        q.getContentType(), q.getSizeBytes(), q.getUploadedBy(),
                        q.getUploadedAt().withNano(0).toString(),
                        q.isPending() ? DocumentIntake.QUARANTINED : DocumentIntake.REJECTED, q.getSha256(), null, null,
                        q.isPending() ? DocumentIntake.QUARANTINED : DocumentIntake.REJECTED,
                        DocumentIntake.sourceLabel(q.getChannel()), DocumentIntake.classification(q.getDocumentType()),
                        q.isPending() ? "Being checked before it can be opened or counted as evidence."
                                : "Not accepted: " + q.getDecisionReason(),
                        q.getId()))
                .toList();
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────────────

    private AppUser reviewer() {
        AppUser user = security.authenticatedUser();
        user.validateHasPermissionTo(RELEASE);
        return user;
    }

    private QuarantinedUpload upload(Long id) {
        QuarantinedUpload upload = em.find(QuarantinedUpload.class, id);
        if (upload == null) {
            throw new NotFoundException("Upload " + id + " not found");
        }
        return upload;
    }

    /** A pending upload the reviewer may decide: they did not upload it themselves (four eyes). */
    private QuarantinedUpload pending(Long id, AppUser reviewer) {
        QuarantinedUpload upload = em.find(QuarantinedUpload.class, id, LockModeType.PESSIMISTIC_WRITE);
        if (upload == null) {
            throw new NotFoundException("Upload " + id + " not found");
        }
        if (!upload.isPending()) {
            throw new ConflictException(upload.getFileName() + " was already " + upload.getStatus().toLowerCase(Locale.ROOT)
                    + " by " + upload.getDecidedBy() + ".");
        }
        if (upload.getUploadedBy().equalsIgnoreCase(reviewer.getUsername())) {
            throw new NoAuthorizationException("You uploaded " + upload.getFileName()
                    + ", so another person must release or reject it.");
        }
        return upload;
    }

    private static String reason(JsonNode body, String field, String message) {
        String value = body == null || !body.hasNonNull(field) ? "" : body.get(field).asText().trim();
        if (value.length() < 5 || value.length() > 500) {
            throw new IllegalArgumentException(message);
        }
        return value;
    }

    private UploadView view(QuarantinedUpload q) {
        InsuranceClaim claim = CLAIM.equals(q.getOwnerKind()) ? em.find(InsuranceClaim.class, q.getOwnerId()) : null;
        return new UploadView(q.getId(), q.getOwnerKind(), q.getOwnerRef(), claim == null ? "" : claim.getCustomerName(),
                q.getDocumentType(), DocumentIntake.classification(q.getDocumentType()), q.getFileName(),
                q.getContentType(), q.getSizeBytes(), q.getSha256(), q.getUploadedBy(), q.getChannel(),
                time(q.getUploadedAt()), q.getStatus(), q.getScanResult(), blank(q.getScanDetail()),
                blank(q.getDecidedBy()), time(q.getDecidedAt()), blank(q.getDecisionReason()), q.getDocumentId());
    }

    private static String time(LocalDateTime value) {
        return value == null ? "" : value.withNano(0).toString();
    }

    private static String blank(String value) {
        return value == null ? "" : value;
    }
}
