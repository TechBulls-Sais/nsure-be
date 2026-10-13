package com.nsure.portfolio;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;

@Entity
@Table(name = "ins_application")
public class InsuranceApplication extends CoverRecord {
}
