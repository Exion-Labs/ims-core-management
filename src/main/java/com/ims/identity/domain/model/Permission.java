package com.ims.identity.domain.model;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Granular permissions per domain (PART, STOCK, COMPANY, BUILD, ORDER, ADMIN) and action (VIEW, CREATE, UPDATE, DELETE).
 */
public enum Permission {

    PART_VIEW("PART:VIEW", "PART", "VIEW"),
    PART_CREATE("PART:CREATE", "PART", "CREATE"),
    PART_UPDATE("PART:UPDATE", "PART", "UPDATE"),
    PART_DELETE("PART:DELETE", "PART", "DELETE"),

    STOCK_VIEW("STOCK:VIEW", "STOCK", "VIEW"),
    STOCK_CREATE("STOCK:CREATE", "STOCK", "CREATE"),
    STOCK_UPDATE("STOCK:UPDATE", "STOCK", "UPDATE"),
    STOCK_DELETE("STOCK:DELETE", "STOCK", "DELETE"),

    COMPANY_VIEW("COMPANY:VIEW", "COMPANY", "VIEW"),
    COMPANY_CREATE("COMPANY:CREATE", "COMPANY", "CREATE"),
    COMPANY_UPDATE("COMPANY:UPDATE", "COMPANY", "UPDATE"),
    COMPANY_DELETE("COMPANY:DELETE", "COMPANY", "DELETE"),

    BUILD_VIEW("BUILD:VIEW", "BUILD", "VIEW"),
    BUILD_CREATE("BUILD:CREATE", "BUILD", "CREATE"),
    BUILD_UPDATE("BUILD:UPDATE", "BUILD", "UPDATE"),
    BUILD_DELETE("BUILD:DELETE", "BUILD", "DELETE"),

    ORDER_VIEW("ORDER:VIEW", "ORDER", "VIEW"),
    ORDER_CREATE("ORDER:CREATE", "ORDER", "CREATE"),
    ORDER_UPDATE("ORDER:UPDATE", "ORDER", "UPDATE"),
    ORDER_DELETE("ORDER:DELETE", "ORDER", "DELETE"),

    ADMIN_VIEW("ADMIN:VIEW", "ADMIN", "VIEW"),
    ADMIN_CREATE("ADMIN:CREATE", "ADMIN", "CREATE"),
    ADMIN_UPDATE("ADMIN:UPDATE", "ADMIN", "UPDATE"),
    ADMIN_DELETE("ADMIN:DELETE", "ADMIN", "DELETE");

    private final String code;
    private final String domain;
    private final String action;

    Permission(String code, String domain, String action) {
        this.code = code;
        this.domain = domain;
        this.action = action;
    }

    public String getCode() {
        return code;
    }

    public String getDomain() {
        return domain;
    }

    public String getAction() {
        return action;
    }

    public static List<String> allCodes() {
        return Arrays.stream(values()).map(Permission::getCode).toList();
    }

    public static Set<String> allCodeSet() {
        return Arrays.stream(values()).map(Permission::getCode).collect(Collectors.toSet());
    }
}
