package com.nsure.mipas.reference;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;

@Entity
@Table(name = "gender")
public class Gender extends NamedLookup {

    protected Gender() {
    }
}
