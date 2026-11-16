package com.nsure.portfolio;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

/** One download of a policy or claim document (Solution Blueprint §8: access and download audit). */
@Entity
@Table(name = "document_access_log")
public class DocumentAccess {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "document_kind", nullable = false, length = 20)
    private String documentKind;

    @Column(name = "document_id", nullable = false)
    private Long documentId;

    @Column(name = "owner_ref", nullable = false, length = 40)
    private String ownerRef;

    @Column(name = "file_name", nullable = false, length = 255)
    private String fileName;

    @Column(nullable = false, length = 100)
    private String username;

    @Column(nullable = false, length = 20)
    private String channel;

    @Column(name = "accessed_at", nullable = false)
    private LocalDateTime accessedAt;

    protected DocumentAccess() {
    }

    DocumentAccess(String documentKind, Long documentId, String ownerRef, String fileName, String username,
            String channel) {
        this.documentKind = documentKind;
        this.documentId = documentId;
        this.ownerRef = ownerRef;
        this.fileName = fileName;
        this.username = username;
        this.channel = channel;
        this.accessedAt = LocalDateTime.now();
    }

    public Long getId() {
        return id;
    }

    public String getDocumentKind() {
        return documentKind;
    }

    public Long getDocumentId() {
        return documentId;
    }

    public String getOwnerRef() {
        return ownerRef;
    }

    public String getFileName() {
        return fileName;
    }

    public String getUsername() {
        return username;
    }

    public String getChannel() {
        return channel;
    }

    public LocalDateTime getAccessedAt() {
        return accessedAt;
    }
}
