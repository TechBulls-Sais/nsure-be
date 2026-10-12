package com.nsure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "nsure.audit")
public class AuditProperties {

    /**
     * User id stamped on rows written when nobody is signed in. The Liquibase seed inserts this user first.
     */
    private long systemUserId = 1L;

    /**
     * Maximum characters stored in command_as_json. Larger payloads are replaced with a hash and a truncated copy.
     */
    private int maxCommandJsonLength = 8000;

    public long getSystemUserId() {
        return systemUserId;
    }

    public void setSystemUserId(long systemUserId) {
        this.systemUserId = systemUserId;
    }

    public int getMaxCommandJsonLength() {
        return maxCommandJsonLength;
    }

    public void setMaxCommandJsonLength(int maxCommandJsonLength) {
        this.maxCommandJsonLength = maxCommandJsonLength;
    }
}
