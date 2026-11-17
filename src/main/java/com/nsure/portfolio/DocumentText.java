package com.nsure.portfolio;

import jakarta.persistence.EntityManager;
import java.io.IOException;
import java.util.Locale;
import java.util.Set;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;

/**
 * The full-text index of documents (NS-SH09A-005): the text of a PDF, lower-cased with whitespace collapsed, kept in
 * the document row's {@code search_text} so the register can search content. Generated documents are indexed when
 * issued, uploads when released from quarantine (never while held). Images have no text: there is no OCR.
 */
final class DocumentText {

    /** The tables that carry a search_text column; the table name is never taken from a request. */
    static final String POLICY_TABLE = "ins_policy_document";
    static final String CLAIM_TABLE = "ins_claim_document";
    private static final Set<String> TABLES = Set.of(POLICY_TABLE, CLAIM_TABLE);
    private static final int MAX_CHARS = 200_000;

    private DocumentText() {
    }

    /** The searchable text of a PDF, or null for anything else or a file that cannot be read. */
    static String extract(byte[] bytes, String contentType) {
        if (!"application/pdf".equals(contentType) || bytes == null || bytes.length == 0) {
            return null;
        }
        try (PDDocument doc = Loader.loadPDF(bytes)) {
            String text = new PDFTextStripper().getText(doc).replaceAll("\\s+", " ").trim().toLowerCase(Locale.ROOT);
            return text.isEmpty() ? null : text.length() > MAX_CHARS ? text.substring(0, MAX_CHARS) : text;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** Stores the text on the document row (inside the caller's transaction). */
    static void index(EntityManager em, String table, Long id, byte[] bytes, String contentType) {
        if (!TABLES.contains(table)) {
            throw new IllegalArgumentException("Not a document table: " + table);
        }
        String text = extract(bytes, contentType);
        if (text != null) {
            em.createNativeQuery("update " + table + " set search_text = ?1 where id = ?2")
                    .setParameter(1, text)
                    .setParameter(2, id)
                    .executeUpdate();
        }
    }
}
