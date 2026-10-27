package com.nsure.audit.service;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "nsure.maker-checker")
public class MakerCheckerProperties {

    private boolean sameUserAllowed;

    public boolean isSameUserAllowed() {
        return sameUserAllowed;
    }

    public void setSameUserAllowed(boolean sameUserAllowed) {
        this.sameUserAllowed = sameUserAllowed;
    }
}
