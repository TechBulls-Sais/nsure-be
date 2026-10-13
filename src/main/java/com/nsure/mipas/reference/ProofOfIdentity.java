package com.nsure.mipas.reference;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;

@Entity
@Table(name = "proof_of_identity")
public class ProofOfIdentity extends NamedLookup {

    protected ProofOfIdentity() {
    }
}
