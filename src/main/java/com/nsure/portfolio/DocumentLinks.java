package com.nsure.portfolio;

import com.fasterxml.jackson.databind.JsonNode;
import com.nsure.common.exception.NotFoundException;
import com.nsure.security.domain.AppUser;
import com.nsure.security.exception.NoAuthorizationException;
import com.nsure.security.service.PlatformSecurityContext;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Short-lived authorised retrieval links (NS-SH09A-006, AT-SH09A-03). A link names one document and is bound to the
 * signed-in person who asked for it: it expires after {@code nsure.documents.link-minutes}, works once, and on use the
 * owning record's permission is checked again (staff: READ_POLICY / READ_CLAIM with health evidence /
 * READ_CUSTOMER / READ_SERVICE_CASE; a customer: their own policy or claim). Someone else holding a copied link, or
 * anyone after it expired, gets nothing. Each use is logged as a delivery, apart from the stored file.
 */
@Service
public class DocumentLinks {

    private static final Map<String, String> TABLE = Map.of("POLICY", "ins_policy_document", "CLAIM",
            "ins_claim_document", "IDENTITY", "pty_identity_document", "CASE", "svc_case_document");
    private final SecureRandom random = new SecureRandom();

    private final JdbcTemplate jdbc;
    private final PlatformSecurityContext security;
    private final DocumentFiles files;
    private final DocumentCustody custody;
    private final PortfolioService portfolio;
    private final int minutes;

    public DocumentLinks(JdbcTemplate jdbc, PlatformSecurityContext security, DocumentFiles files,
            DocumentCustody custody, PortfolioService portfolio,
            @Value("${nsure.documents.link-minutes:10}") int minutes) {
        this.jdbc = jdbc;
        this.security = security;
        this.files = files;
        this.custody = custody;
        this.portfolio = portfolio;
        this.minutes = Math.max(1, minutes);
    }

    /** {kind: POLICY | CLAIM | IDENTITY | CASE, documentId}: a link for the caller, if they may open the document. */
    @Transactional
    public Map<String, Object> create(JsonNode body) {
        AppUser user = security.authenticatedUser();
        String kind = body == null || !body.hasNonNull("kind") ? null : body.get("kind").asText().toUpperCase(Locale.ROOT);
        if (kind == null || !TABLE.containsKey(kind)) {
            throw new IllegalArgumentException("Kind must be POLICY, CLAIM, IDENTITY or CASE.");
        }
        long id = body.path("documentId").asLong(0);
        Doc doc = document(kind, id);
        allowed(user, doc);
        byte[] raw = new byte[24];
        random.nextBytes(raw);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        LocalDateTime now = LocalDateTime.now();
        jdbc.update("insert into doc_link (token_hash, doc_kind, document_id, owner_ref, username, created_at,"
                + " expires_at) values (?, ?, ?, ?, ?, ?, ?)", DocumentCustody.sha256(token.getBytes(
                java.nio.charset.StandardCharsets.UTF_8)), kind, id, doc.ownerRef(), user.getUsername(), now,
                now.plusMinutes(minutes));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("token", token);
        out.put("path", "/portfolio/document-links/" + token);
        out.put("expiresAt", String.valueOf(now.plusMinutes(minutes)));
        out.put("fileName", doc.fileName());
        return out;
    }

    /** Serves the linked document once, to the person the link was made for, before it expires. */
    @Transactional
    public ClaimDocumentService.StoredFile open(String token) {
        AppUser user = security.authenticatedUser();
        List<Map<String, Object>> rows = jdbc.queryForList("select * from doc_link where token_hash = ?",
                DocumentCustody.sha256(String.valueOf(token).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        String gone = "This link has expired or was already used. Ask for a new one.";
        if (rows.isEmpty()) {
            throw new NotFoundException(gone);
        }
        Map<String, Object> link = new LinkedHashMap<>();
        rows.get(0).forEach((k, v) -> link.put(k.toLowerCase(Locale.ROOT), v));
        LocalDateTime expires = ((java.sql.Timestamp) link.get("expires_at")).toLocalDateTime();
        if (link.get("used_at") != null || LocalDateTime.now().isAfter(expires)) {
            throw new NotFoundException(gone);
        }
        if (!user.getUsername().equals(link.get("username"))) {
            throw new NotFoundException(gone);
        }
        Doc doc = document((String) link.get("doc_kind"), ((Number) link.get("document_id")).longValue());
        allowed(user, doc);
        if (jdbc.update("update doc_link set used_at = ? where id = ? and used_at is null", LocalDateTime.now(),
                link.get("id")) == 0) {
            throw new NotFoundException(gone);
        }
        custody.verify(doc.sha256(), doc.storagePath(), doc.kind().toLowerCase(Locale.ROOT) + " document " + doc.id());
        custody.logAccess(doc.kind(), doc.id(), doc.ownerRef(), doc.fileName(), user.getUsername(), "LINK");
        return new ClaimDocumentService.StoredFile(doc.fileName(), doc.contentType(), doc.size(),
                files.read(doc.storagePath(), doc.kind().toLowerCase(Locale.ROOT) + " document " + doc.id()));
    }

    private record Doc(String kind, long id, String ownerRef, String customerRef, String documentType, String fileName,
            String contentType, long size, String storagePath, String sha256, String status) {
    }

    private Doc document(String kind, long id) {
        String sql = switch (kind) {
            case "POLICY" -> "select d.id, p.record_ref as owner_ref, p.customer_ref, d.document_type, d.file_name,"
                    + " d.content_type, d.size_bytes, d.storage_path, d.sha256, d.status from ins_policy_document d join"
                    + " ins_policy p on p.id = d.policy_id where d.id = ?";
            case "CLAIM" -> "select d.id, c.record_ref as owner_ref, c.customer_ref, d.document_type, d.file_name,"
                    + " d.content_type, d.size_bytes, d.storage_path, d.sha256, d.status from ins_claim_document d join"
                    + " ins_claim c on c.id = d.claim_id where d.id = ?";
            case "IDENTITY" -> "select d.id, d.customer_ref as owner_ref, d.customer_ref, d.document_type, d.file_name,"
                    + " d.content_type, d.size_bytes, d.storage_path, d.sha256, 'CURRENT' as status from"
                    + " pty_identity_document d where d.id = ?";
            default -> "select d.id, d.case_ref as owner_ref, s.customer_ref, d.document_type, d.file_name,"
                    + " d.content_type, d.size_bytes, d.storage_path, d.sha256, 'CURRENT' as status from"
                    + " svc_case_document d join svc_case s on s.record_ref = d.case_ref where d.id = ?";
        };
        List<Map<String, Object>> rows = jdbc.queryForList(sql, id);
        if (rows.isEmpty()) {
            throw new NotFoundException("Document " + id + " not found");
        }
        Map<String, Object> r = new LinkedHashMap<>();
        rows.get(0).forEach((k, v) -> r.put(k.toLowerCase(Locale.ROOT), v));
        if ("DISPOSED".equals(r.get("status"))) {
            throw new NotFoundException("Document " + id + " was disposed of under the retention schedule.");
        }
        return new Doc(kind, id, (String) r.get("owner_ref"), (String) r.get("customer_ref"),
                (String) r.get("document_type"), (String) r.get("file_name"), (String) r.get("content_type"),
                ((Number) r.get("size_bytes")).longValue(), (String) r.get("storage_path"), (String) r.get("sha256"),
                (String) r.get("status"));
    }

    /** The same access the owning record's own download needs. */
    private void allowed(AppUser user, Doc doc) {
        if (user.isPortalCustomer()) {
            CustomerAccount me = portfolio.customerForLogin();
            if (!Set.of("POLICY", "CLAIM").contains(doc.kind()) || !me.getRef().equals(doc.customerRef())) {
                throw new NotFoundException("Document " + doc.id() + " not found");
            }
            return;
        }
        String code = switch (doc.kind()) {
            case "POLICY" -> "READ_POLICY";
            case "CLAIM" -> "READ_CLAIM";
            case "IDENTITY" -> "READ_CUSTOMER";
            default -> "READ_SERVICE_CASE";
        };
        if (!user.hasPermissionTo(code)) {
            throw new NoAuthorizationException("You may not open this document.");
        }
        custody.requireSensitiveAccess(doc.documentType(), user, DocumentCustody.STAFF);
    }
}
