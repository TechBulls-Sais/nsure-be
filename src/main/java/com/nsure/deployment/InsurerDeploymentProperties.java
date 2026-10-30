package com.nsure.deployment;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Which insurer this installation serves (nsure.insurer.*, from NSURE_* environment variables). One backend serves
 * exactly one insurer; nothing in a request can change it. Invalid values stop the application at startup.
 */
@Validated
@ConfigurationProperties("nsure.insurer")
public record InsurerDeploymentProperties(
        @NotBlank @Pattern(regexp = "[a-z0-9][a-z0-9-]{1,38}", message = "lower-case letters, digits and dashes")
        String code,
        @NotBlank @Pattern(regexp = "dev|test|uat|prod", message = "dev, test, uat or prod")
        String environment,
        @NotBlank String displayName,
        String legalName,
        @NotBlank @Pattern(regexp = "[A-Z]{3}", message = "an ISO 4217 code such as BWP") String currency,
        @NotBlank String timezone,
        String locale,
        String supportEmail,
        String supportPhone,
        @Pattern(regexp = "#[0-9a-fA-F]{6}", message = "a #rrggbb colour") String primaryColor,
        String logoUrl,
        boolean bindOnFirstStart) {
}
