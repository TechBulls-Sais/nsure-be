package com.nsure.configuration;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

/**
 * One approved extra field on a policy form. Types are a small allow-list (guide §15); nothing here is executable.
 * Core insurance values (cover, premium, dates) are typed columns and can never be defined as custom fields.
 */
@Entity
@Table(name = "policy_field_definition")
public class PolicyFieldDefinition {

    public static final Set<String> TYPES = Set.of("STRING", "NUMBER", "DATE", "BOOLEAN", "ENUM");

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "form_version_id", nullable = false)
    private PolicyFormVersion form;

    @Column(name = "field_key", nullable = false, length = 40)
    private String key;

    @Column(name = "field_type", nullable = false, length = 20)
    private String type;

    @Column(nullable = false, length = 100)
    private String label;

    @Column(name = "required_flag", nullable = false, length = 1)
    private String requiredFlag;

    @Column(name = "sensitive_flag", nullable = false, length = 1)
    private String sensitiveFlag;

    @Column(name = "max_length")
    private Integer maxLength;

    @Column(name = "allowed_values", length = 500)
    private String allowedValues;

    @Column(name = "help_text", length = 200)
    private String helpText;

    @Column(name = "display_order", nullable = false)
    private int displayOrder;

    protected PolicyFieldDefinition() {
    }

    PolicyFieldDefinition(PolicyFormVersion form, String key, String type, String label, boolean required,
            boolean sensitive, Integer maxLength, List<String> allowedValues, String helpText, int displayOrder) {
        this.form = form;
        this.key = key;
        this.type = type;
        this.label = label;
        this.requiredFlag = required ? "Y" : "N";
        this.sensitiveFlag = sensitive ? "Y" : "N";
        this.maxLength = maxLength;
        this.allowedValues = allowedValues == null || allowedValues.isEmpty() ? null : String.join(",", allowedValues);
        this.helpText = helpText;
        this.displayOrder = displayOrder;
    }

    public String getKey() {
        return key;
    }

    public String getType() {
        return type;
    }

    public String getLabel() {
        return label;
    }

    public boolean isRequired() {
        return "Y".equals(requiredFlag);
    }

    public boolean isSensitive() {
        return "Y".equals(sensitiveFlag);
    }

    public Integer getMaxLength() {
        return maxLength;
    }

    public List<String> getAllowedValues() {
        return allowedValues == null ? List.of() : Arrays.stream(allowedValues.split(",")).map(String::trim).toList();
    }

    public String getHelpText() {
        return helpText;
    }

    public int getDisplayOrder() {
        return displayOrder;
    }
}
