package com.nsure.mipas.reference;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;

@Entity
@Table(name = "payout_method")
public class PayoutMethod extends NamedLookup {

    protected PayoutMethod() {
    }
}
