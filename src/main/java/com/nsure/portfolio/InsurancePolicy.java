package com.nsure.portfolio;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;

@Entity
@Table(name = "ins_policy")
public class InsurancePolicy extends CoverRecord {
}
