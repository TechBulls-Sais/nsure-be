package com.nsure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "nsure.maker-checker")
public class MakerCheckerProperties {

    /**
     * When false, the maker and the checker of a command must be different users.
     */
    private boolean allowSameUser = false;

    public boolean isAllowSameUser() {
        return allowSameUser;
    }

    public void setAllowSameUser(boolean allowSameUser) {
        this.allowSameUser = allowSameUser;
    }
}
