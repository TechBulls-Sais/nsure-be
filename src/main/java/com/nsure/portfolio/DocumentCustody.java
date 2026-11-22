package com.nsure.portfolio;

import com.nsure.audit.evidence.EvidenceChain;
import com.nsure.common.exception.ConflictException;
import jakarta.persistence.EntityManager;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Custody of issued and uploaded documents (Solution Blueprint §8, SH-09A):
 *
 * <ul>
 *   <li>each file carries the SHA-256 of its bytes, recorded when it is stored and checked on every download, so a
 *       changed or swapped file is refused rather than served;</li>
 *   <li>an issued document is never overwritten or deleted: a correction or a new layout is a controlled replacement
 *       that marks the old one SUPERSEDED, with the reason and a link to its replacement;</li>
 *   <li>every download is logged with who, through which channel and when.</li>
 * </ul>
 */
@Component
public class DocumentCustody {

    public static final String CURRENT = "CURRENT";
    public static final String SUPERSEDED = "SUPERSEDED";
    public static final String POLICY = "POLICY";
    public static final String CLAIM = "CLAIM";
    public static final String STAFF = "STAFF";
    public static final String PORTAL = "PORTAL";

    private final EntityManager em;
    private final DocumentFiles files;
    private final EvidenceChain evidence;

    public DocumentCustody(EntityManager em, DocumentFiles files, EvidenceChain evidence) {
        this.em = em;
        this.files = files;
        this.evidence = evidence;
    }

    public static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Refuses a file whose bytes no longer match the hash recorded when it was stored. Older rows have no hash. */
    public void verify(String expectedSha256, String storagePath, String what) {
        if (expectedSha256 == null) {
            return;
        }
        if (!expectedSha256.equalsIgnoreCase(files.sha256Of(storagePath, what))) {
            throw new ConflictException("The stored file for " + what + " failed its integrity check, so it is not "
                    + "served. Report it to the document administrator.");
        }
    }

    /** Logs one download. */
    public void logAccess(String kind, Long documentId, String ownerRef, String fileName, String username, String channel) {
        em.persist(new DocumentAccess(kind, documentId, ownerRef, fileName, username, channel));
        // Opening a document is sensitive access (NS-SH11-005).
        evidence.record(username, "Downloaded " + kind + " document", kind, ownerRef, fileName + " via " + channel);
    }

    /** The downloads of a policy's or claim's documents, newest first. */
    List<AccessView> accessLog(String ownerRef) {
        return em.createQuery("select a from DocumentAccess a where a.ownerRef = :ref order by a.accessedAt desc, a.id desc",
                        DocumentAccess.class)
                .setParameter("ref", ownerRef)
                .setMaxResults(500)
                .getResultStream()
                .map(a -> new AccessView(a.getDocumentKind(), a.getDocumentId(), a.getFileName(), a.getUsername(),
                        a.getChannel(), a.getAccessedAt()))
                .toList();
    }

    public record AccessView(String kind, Long documentId, String fileName, String username, String channel,
            LocalDateTime accessedAt) {
    }
}
