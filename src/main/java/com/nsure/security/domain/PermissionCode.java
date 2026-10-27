package com.nsure.security.domain;

public final class PermissionCode {

    private PermissionCode() {
    }

    public static String code(String action, String entity) {
        return action.toUpperCase() + "_" + entity.toUpperCase();
    }
}
