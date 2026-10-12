package com.nsure.security.domain;

import java.util.Locale;

public final class PermissionCodes {

    public static final String ALL_FUNCTIONS = "ALL_FUNCTIONS";
    public static final String ALL_FUNCTIONS_READ = "ALL_FUNCTIONS_READ";
    public static final String CHECKER_SUPER_USER = "CHECKER_SUPER_USER";

    private PermissionCodes() {
    }

    public static String code(String action, String entity) {
        return action.toUpperCase(Locale.ROOT) + "_" + entity.toUpperCase(Locale.ROOT);
    }

    public static String checkerCode(String permissionCode) {
        return "CHECKER_" + permissionCode;
    }
}
