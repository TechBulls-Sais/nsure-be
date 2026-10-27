package com.nsure.actuary;

import com.nsure.audit.domain.AuditableEntity;
import com.nsure.security.domain.AppUser;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "actuary")
public class Actuary extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", unique = true)
    private AppUser user;

    @Column(name = "full_name", nullable = false, length = 150)
    private String fullName;

    @Column(name = "registration_number", nullable = false, unique = true, length = 60)
    private String registrationNumber;

    @Column(length = 120)
    private String qualification;

    @Column(name = "professional_body", length = 150)
    private String professionalBody;

    @Column(length = 150)
    private String email;

    @Column(length = 30)
    private String phone;

    @Column(nullable = false)
    private boolean active = true;

    public Long getId() {
        return id;
    }

    public AppUser getUser() {
        return user;
    }

    public void setUser(AppUser user) {
        this.user = user;
    }

    public String getFullName() {
        return fullName;
    }

    public void setFullName(String fullName) {
        this.fullName = fullName;
    }

    public String getRegistrationNumber() {
        return registrationNumber;
    }

    public void setRegistrationNumber(String registrationNumber) {
        this.registrationNumber = registrationNumber;
    }

    public String getQualification() {
        return qualification;
    }

    public void setQualification(String qualification) {
        this.qualification = qualification;
    }

    public String getProfessionalBody() {
        return professionalBody;
    }

    public void setProfessionalBody(String professionalBody) {
        this.professionalBody = professionalBody;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getPhone() {
        return phone;
    }

    public void setPhone(String phone) {
        this.phone = phone;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }
}
