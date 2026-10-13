package com.nsure.mipas.reference;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;

@Entity
@Table(name = "courtesy")
public class Courtesy extends NamedLookup {

    protected Courtesy() {
    }
}
