package com.autoshop;

import java.util.EnumSet;
import java.util.Set;

/**
 * Staff roles. To change what a role can do, edit its permission list here.
 * To add a new role, add a new line in the same style.
 */
public enum Role {
    /** Everything, including creating staff accounts and setting their roles. */
    OWNER("Owner / Director", EnumSet.allOf(Permission.class)),

    /** Runs the shop day to day: full control of inventory, sales, and sees the money figures. */
    MANAGER("Manager", EnumSet.of(
        Permission.VIEW_INVENTORY, Permission.ADJUST_STOCK, Permission.ADD_PRODUCT,
        Permission.EDIT_PRODUCT, Permission.DELETE_PRODUCT, Permission.VIEW_FINANCIALS,
        Permission.SELL)),

    /** Looks after the money: sees inventory, prices and stock value, and can correct prices. */
    ACCOUNTANT("Accountant", EnumSet.of(
        Permission.VIEW_INVENTORY, Permission.VIEW_FINANCIALS, Permission.EDIT_PRODUCT)),

    /** Sells to customers and can see what is in stock. Does not change stock. */
    SHOPKEEPER("Shopkeeper", EnumSet.of(
        Permission.VIEW_INVENTORY, Permission.SELL)),

    /** Takes goods out after a sale and brings goods in after a delivery. */
    STOREKEEPER("Storekeeper", EnumSet.of(
        Permission.VIEW_INVENTORY, Permission.ADJUST_STOCK)),

    /** General shop floor staff: looks up goods and records stock in and stock out. */
    STAFF("Staff", EnumSet.of(
        Permission.VIEW_INVENTORY, Permission.ADJUST_STOCK));

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
        String name = s == null ? "" : s.trim().toUpperCase();
        // Accounts saved under role names that no longer exist become Staff.
        if (name.equals("CASHIER") || name.equals("MECHANIC")) return STAFF;
        try {
            return Role.valueOf(name);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unknown role");
        }
    }
}
