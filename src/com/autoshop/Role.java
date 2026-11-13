package com.autoshop;

import java.util.EnumSet;
import java.util.Set;

/**
 * Staff roles. To change what a role can do, edit its permission list here.
 * To add a new role, add a new line in the same style.
 */
public enum Role {
    OWNER("Owner / Director", EnumSet.allOf(Permission.class)),

    MANAGER("Manager", EnumSet.of(
        Permission.VIEW_INVENTORY, Permission.ADJUST_STOCK, Permission.ADD_PRODUCT,
        Permission.EDIT_PRODUCT, Permission.DELETE_PRODUCT, Permission.VIEW_FINANCIALS)),

    STOREKEEPER("Storekeeper", EnumSet.of(
        Permission.VIEW_INVENTORY, Permission.ADJUST_STOCK, Permission.ADD_PRODUCT)),

    CASHIER("Cashier / Sales", EnumSet.of(Permission.VIEW_INVENTORY)),

    MECHANIC("Mechanic", EnumSet.of(Permission.VIEW_INVENTORY));

    private final String label;
    private final Set<Permission> permissions;

    Role(String label, Set<Permission> permissions) {
        this.label = label;
        this.permissions = permissions;
    }

    public String getLabel() { return label; }
    public Set<Permission> getPermissions() { return permissions; }
    public boolean can(Permission p) { return permissions.contains(p); }

    public static Role parse(String s) {
        try {
            return Role.valueOf(s == null ? "" : s.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unknown role");
        }
    }
}
