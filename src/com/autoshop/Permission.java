package com.autoshop;

/** One thing a member of staff may be allowed to do. Roles are built from these. */
public enum Permission {
    VIEW_INVENTORY("See the inventory list and search it"),
    ADJUST_STOCK("Stock in and stock out, and issue sold goods"),
    ADD_PRODUCT("Add new goods"),
    EDIT_PRODUCT("Edit names, prices and alert levels"),
    DELETE_PRODUCT("Delete goods"),
    SELL("Record sales to customers and cancel waiting sales"),
    VIEW_FINANCIALS("See stock value and financial figures"),
    MANAGE_STAFF("Create staff accounts and set their roles");

    private final String label;

    Permission(String label) { this.label = label; }

    public String getLabel() { return label; }
}
