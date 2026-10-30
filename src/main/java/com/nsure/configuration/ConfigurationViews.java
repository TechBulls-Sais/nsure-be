package com.nsure.configuration;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDateTime;
import java.util.List;

public final class ConfigurationViews {

    private ConfigurationViews() {
    }

    public record FieldView(String key, String type, String label, boolean required, boolean sensitive,
            Integer maxLength, List<String> allowedValues, String helpText, int order) {
    }

    public record FormView(Long formVersionId, String product, Integer versionNo, String status, String releaseCode,
            List<FieldView> fields) {
    }

    public record ReleaseView(Long id, String code, String description, String status, String checksum,
            String schemaVersion, String createdBy, LocalDateTime createdAt, String submittedBy,
            LocalDateTime submittedAt, String approvedBy, LocalDateTime approvedAt, String activatedBy,
            LocalDateTime activatedAt, String rejectionReason, long rowVersion, List<FormView> forms) {
    }

    /**
     * A policy's extra details, always against the form it was issued under. {@code values} hides sensitive fields
     * from callers who cannot update the policy.
     */
    public record PolicyAttributesView(String policyRef, String product, long rowVersion, FormView form,
            JsonNode values, boolean currentForm) {
    }
}
