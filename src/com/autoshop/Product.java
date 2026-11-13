package com.autoshop;

/**
 * A single stock item in the workshop inventory (e.g. a tyre, a bottle of oil, a battery).
 */
public class Product {
    private final int id;
    private String name;
    private String category;
    private int quantity;
    private double unitPrice;
    private int lowStockLevel;

    public Product(int id, String name, String category, int quantity, double unitPrice, int lowStockLevel) {
        this.id = id;
        this.name = name;
        this.category = category;
        this.quantity = quantity;
        this.unitPrice = unitPrice;
        this.lowStockLevel = lowStockLevel;
    }

    public int getId() { return id; }
    public String getName() { return name; }
    public String getCategory() { return category; }
    public int getQuantity() { return quantity; }
    public double getUnitPrice() { return unitPrice; }
    public int getLowStockLevel() { return lowStockLevel; }

    public void setName(String name) { this.name = name; }
    public void setCategory(String category) { this.category = category; }
    public void setQuantity(int quantity) { this.quantity = Math.max(0, quantity); }
    public void setUnitPrice(double unitPrice) { this.unitPrice = unitPrice; }
    public void setLowStockLevel(int lowStockLevel) { this.lowStockLevel = lowStockLevel; }

    public boolean isLowStock() { return quantity <= lowStockLevel; }
    public double getStockValue() { return quantity * unitPrice; }

    /** Serialise to one CSV line: id,name,category,quantity,unitPrice,lowStockLevel */
    public String toCsv() {
        return id + "," + esc(name) + "," + esc(category) + "," + quantity + "," + unitPrice + "," + lowStockLevel;
    }

    public static Product fromCsv(String line) {
        String[] p = line.split(",", -1);
        if (p.length < 6) return null;
        return new Product(
            Integer.parseInt(p[0]),
            unesc(p[1]),
            unesc(p[2]),
            Integer.parseInt(p[3]),
            Double.parseDouble(p[4]),
            Integer.parseInt(p[5])
        );
    }

    private static String esc(String s) { return s.replace(",", "&#44;"); }
    private static String unesc(String s) { return s.replace("&#44;", ","); }
}
