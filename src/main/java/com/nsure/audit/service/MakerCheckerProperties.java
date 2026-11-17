package com.nsure.audit.service;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "nsure.maker-checker")
public class MakerCheckerProperties {

    private boolean sameUserAllowed;

    /**
     * Whether a user creation may be applied without a checker while nobody but its maker could approve it (the
     * first administrator of a fresh installation creating the second). Recorded and alerted when used.
     */
    private boolean soleCheckerBootstrap = true;

    public boolean isSameUserAllowed() {
        return sameUserAllowed;
    }

    public void setSameUserAllowed(boolean sameUserAllowed) {
        this.sameUserAllowed = sameUserAllowed;
    }

    public boolean isSoleCheckerBootstrap() {
        return soleCheckerBootstrap;
    }

    public void setSoleCheckerBootstrap(boolean soleCheckerBootstrap) {
        this.soleCheckerBootstrap = soleCheckerBootstrap;
    }
}
