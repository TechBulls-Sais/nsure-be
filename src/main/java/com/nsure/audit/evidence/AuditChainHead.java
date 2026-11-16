package com.nsure.audit.evidence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * The end of the evidence chain: the last sequence number and hash. Locked while evidence is appended, so records
 * join the chain one transaction at a time, and compared on verification, so removing the newest records is caught.
 */
@Entity
@Table(name = "audit_chain_head")
public class AuditChainHead {

    static final long ID = 1L;

    @Id
    private Long id;

    @Column(name = "last_sequence", nullable = false)
    private long lastSequence;

    @Column(name = "last_hash", nullable = false, length = 64)
    private String lastHash;

    protected AuditChainHead() {
    }

    AuditChainHead(String genesis) {
        this.id = ID;
        this.lastSequence = 0;
        this.lastHash = genesis;
    }

    void advance(long sequence, String hash) {
        this.lastSequence = sequence;
        this.lastHash = hash;
    }

    public long getLastSequence() {
        return lastSequence;
    }

    public String getLastHash() {
        return lastHash;
    }
}
