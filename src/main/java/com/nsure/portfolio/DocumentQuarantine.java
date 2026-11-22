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
    /** Identity evidence for KYC, owned by the customer (owner_id is the customer's id). */
    public static final String IDENTITY = "IDENTITY";
    /** Evidence on a service request or complaint (owner_id is the case's id). */
    public static final String SERVICE_CASE = "SERVICE_CASE";
    /** The read permission of each kind's owning record: a reviewer must hold it as well as RELEASE_DOCUMENT. */
    private static final java.util.Map<String, String> READ = java.util.Map.of(CLAIM, "READ_CLAIM", IDENTITY,
            "READ_CUSTOMER", SERVICE_CASE, "READ_SERVICE_CASE");
    private static final String EICAR = "EICAR-STANDARD-ANTIVIRUS-TEST-FILE";
    /**
     * Until an installation connects a real scanner: a simulated check that only recognises the EICAR anti-virus test
     * signature (rejecting it, so the "infected file" path can be shown). It never calls a file clean.
     */
    static final UploadScanner SIMULATED = new UploadScanner() {
        @Override
        public String name() {
            return "Simulated EICAR check";
        }

        @Override
        public Verdict scan(String fileName, byte[] bytes) {
            if (new String(bytes, java.nio.charset.StandardCharsets.ISO_8859_1).contains(EICAR)) {
                return new Verdict(INFECTED, "EICAR anti-virus test signature (simulated scanner)");
            }
            return new Verdict(NOT_SCANNED, "No malware scanner is configured, so the file was not scanned (the"
                    + " simulated check found no test signature). A person must release it.");
        }
    };
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
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;
    private final boolean simulatedScanner;

    public DocumentQuarantine(EntityManager em, PlatformSecurityContext security, DocumentFiles files,
            DocumentCustody custody, WorkflowHistory history, EvidenceChain audit,
            ObjectProvider<UploadScanner> scanners, org.springframework.jdbc.core.JdbcTemplate jdbc,
            @org.springframework.beans.factory.annotation.Value("${nsure.documents.simulated-scanner:true}")
            boolean simulatedScanner) {
        this.jdbc = jdbc;
        this.simulatedScanner = simulatedScanner;
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
        UploadScanner scanner = scanner();
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

    /** The configured scanner, else the simulated EICAR check (unless switched off), else none. */
    private UploadScanner scanner() {
        UploadScanner scanner = scanners.getIfAvailable();
        return scanner != null ? scanner : simulatedScanner ? SIMULATED : null;
    }

    /**
     * Holds identity or service case evidence in quarantine (NS-SH09A-004), like claim evidence: stored and hashed,
     * never served or counted until released. The owner's history records it. A scanner finding rejects it at once.
     */
    public QuarantinedUpload receiveEvidence(String kind, Long ownerId, String ownerRef, String ownerName,
            DocumentIntake.Upload file, String documentType, String uploadedBy, String channel) {
        if (!IDENTITY.equals(kind) && !SERVICE_CASE.equals(kind)) {
            throw new IllegalArgumentException("Unknown evidence owner " + kind);
        }
        UploadScanner scanner = scanner();
        UploadScanner.Verdict verdict = scanner == null ? UploadScanner.NO_SCANNER
                : verdict(scanner, file.fileName(), file.bytes());
        String storagePath = files.write((IDENTITY.equals(kind) ? "identity/" : "cases/") + ownerRef, file.extension(),
                file.bytes());
        QuarantinedUpload upload = new QuarantinedUpload(kind, ownerId, ownerRef, documentType, file.fileName(),
                file.contentType(), file.bytes().length, storagePath, DocumentCustody.sha256(file.bytes()), uploadedBy,
                channel, verdict);
        try {
            em.persist(upload);
            em.flush();
        } catch (RuntimeException e) {
            files.deleteQuietly(storagePath);
            throw e;
        }
        ownerHistory(kind, ownerRef, ownerName, "Evidence uploaded", documentType + ": " + file.fileName()
                + " · held in quarantine until released", uploadedBy);
        if (UploadScanner.CLEAN.equals(verdict.result())) {
            fileEvidence(upload, "scanner:" + scanner.name(), verdict.detail());
        } else if (UploadScanner.INFECTED.equals(verdict.result())) {
            String by = "scanner:" + scanner.name();
            upload.rejected(by, "Malware found: " + verdict.detail());
            ownerHistory(kind, ownerRef, ownerName, "Evidence rejected", file.fileName() + ": malware found ("
                    + verdict.detail() + ")", by);
            audit.record(by, "Upload rejected by scanner", label(kind), ownerRef, file.fileName() + ": "
                    + verdict.detail());
        }
        return upload;
    }

    /** Files released identity or case evidence where its owner keeps documents of record. */
    private void fileEvidence(QuarantinedUpload upload, String releasedBy, String note) {
        Long documentId;
        if (IDENTITY.equals(upload.getOwnerKind())) {
            IdentityDocument document = new IdentityDocument(upload.getOwnerRef(), upload.getDocumentType(),
                    upload.getFileName(), upload.getContentType(), upload.getSizeBytes(), upload.getStoragePath(),
                    upload.getSha256(), upload.getUploadedBy());
            em.persist(document);
            em.flush();
            documentId = document.getId();
        } else {
            jdbc.update("insert into svc_case_document (case_ref, document_type, file_name, content_type, size_bytes,"
                    + " storage_path, sha256, uploaded_by, uploaded_at, channel, released_by, upload_id) values (?, ?, ?,"
                    + " ?, ?, ?, ?, ?, ?, ?, ?, ?)", upload.getOwnerRef(), upload.getDocumentType(), upload.getFileName(),
                    upload.getContentType(), upload.getSizeBytes(), upload.getStoragePath(), upload.getSha256(),
                    upload.getUploadedBy(), upload.getUploadedAt(), upload.getChannel(), releasedBy, upload.getId());
            documentId = jdbc.queryForObject("select id from svc_case_document where upload_id = ?", Long.class,
                    upload.getId());
        }
        upload.released(releasedBy, note, documentId);
        ownerHistory(upload.getOwnerKind(), upload.getOwnerRef(), ownerName(upload), "Evidence released",
                upload.getDocumentType() + ": " + upload.getFileName() + " · released from quarantine by " + releasedBy,
                releasedBy);
    }

    private void ownerHistory(String kind, String ref, String name, String action, String detail, String actor) {
        if (IDENTITY.equals(kind)) {
            history.record(WorkflowHistory.KYC_REVIEW, ref, name, action, null, null, detail, actor);
        } else if (SERVICE_CASE.equals(kind)) {
            history.record("Service Case", ref, name, action, null, null, detail, actor);
        }
    }

    private String ownerName(QuarantinedUpload q) {
        if (CLAIM.equals(q.getOwnerKind())) {
            InsuranceClaim claim = em.find(InsuranceClaim.class, q.getOwnerId());
            return claim == null ? "" : claim.getCustomerName();
        }
        List<String> names = IDENTITY.equals(q.getOwnerKind())
                ? jdbc.queryForList("select name from ins_customer where record_ref = ?", String.class, q.getOwnerRef())
                : jdbc.queryForList("select customer_name from svc_case where record_ref = ?", String.class,
                        q.getOwnerRef());
        return names.isEmpty() ? "" : names.get(0);
    }

    private static String label(String kind) {
        return IDENTITY.equals(kind) ? "Customer" : SERVICE_CASE.equals(kind) ? "Service case" : "Claim";
    }

    /** The reviewer also needs the read permission of the upload's owning record. */
    private void mayRead(AppUser user, QuarantinedUpload upload) {
        user.validateHasPermissionTo(READ.getOrDefault(upload.getOwnerKind(), "READ_CLAIM"));
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
        AppUser user = security.authenticatedUser();
        List<String> kinds = READ.entrySet().stream().filter(e -> user.hasPermissionTo(e.getValue()))
                .map(java.util.Map.Entry::getKey).toList();
        if (kinds.isEmpty()) {
            throw new NoAuthorizationException("User has no authority to read claim, identity or case evidence");
        }
        String state = status == null || status.isBlank() ? QuarantinedUpload.PENDING
                : status.trim().toUpperCase(Locale.ROOT);
        if (!"ALL".equals(state) && !STATES.contains(state)) {
            throw new IllegalArgumentException("Status must be PENDING, RELEASED, REJECTED or ALL.");
        }
        var query = em.createQuery("select q from QuarantinedUpload q where q.ownerKind in :kinds"
                + ("ALL".equals(state) ? "" : " and q.status = :status") + " order by q.id desc", QuarantinedUpload.class);
        query.setParameter("kinds", kinds);
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
        mayRead(user, upload);
        custody.requireSensitiveAccess(upload.getDocumentType(), user, DocumentCustody.STAFF);
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
        mayRead(user, upload);
        if (!CLAIM.equals(upload.getOwnerKind())) {
            custody.verify(upload.getSha256(), upload.getStoragePath(), "upload " + id);
            fileEvidence(upload, user.getUsername(), note);
            audit.record(user.getUsername(), "Upload released without a malware scan", label(upload.getOwnerKind()),
                    upload.getOwnerRef(), upload.getDocumentType() + ": " + upload.getFileName() + " (uploaded by "
                            + upload.getUploadedBy() + "): " + note);
            return view(upload);
        }
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
        mayRead(user, upload);
        upload.rejected(user.getUsername(), reason);
        if (!CLAIM.equals(upload.getOwnerKind())) {
            ownerHistory(upload.getOwnerKind(), upload.getOwnerRef(), ownerName(upload), "Evidence rejected",
                    upload.getDocumentType() + ": " + upload.getFileName() + " · " + reason, user.getUsername());
            audit.record(user.getUsername(), "Upload rejected", label(upload.getOwnerKind()), upload.getOwnerRef(),
                    upload.getDocumentType() + ": " + upload.getFileName() + ": " + reason);
            return view(upload);
        }
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

    /** Identity or case uploads of an owner still held or refused, for the owner's document list (no download). */
    public List<java.util.Map<String, Object>> held(String kind, String ownerRef) {
        return em.createQuery("select q from QuarantinedUpload q where q.ownerKind = :kind and q.ownerRef = :ref"
                        + " and q.status <> :released order by q.id desc", QuarantinedUpload.class)
                .setParameter("kind", kind).setParameter("ref", ownerRef)
                .setParameter("released", QuarantinedUpload.RELEASED)
                .getResultStream().map(q -> {
                    java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
                    m.put("uploadId", q.getId());
                    m.put("documentType", q.getDocumentType());
                    m.put("fileName", q.getFileName());
                    m.put("uploadedBy", q.getUploadedBy());
                    m.put("uploadedAt", time(q.getUploadedAt()));
                    m.put("state", q.isPending() ? DocumentIntake.QUARANTINED : DocumentIntake.REJECTED);
                    m.put("note", q.isPending() ? "Being checked before it can be opened or counted as evidence."
                            : "Not accepted: " + q.getDecisionReason());
                    return m;
                }).toList();
    }

    private UploadView view(QuarantinedUpload q) {
        return new UploadView(q.getId(), q.getOwnerKind(), q.getOwnerRef(), ownerName(q),
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
