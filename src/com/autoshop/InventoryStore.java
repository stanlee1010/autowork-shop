package com.autoshop;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * Keeps all products in memory and saves them to data/inventory.csv so stock
 * survives a restart. The first run seeds the three starting goods:
 * tyres, oil and batteries.
 */
public class InventoryStore {
    private final Path file;
    private final List<Product> products = new ArrayList<>();
    private int nextId = 1;

    public InventoryStore(Path file) {
        this.file = file;
        load();
        if (products.isEmpty()) {
            seedDefaults();
            save();
        }
    }

    private void seedDefaults() {
        // Tyres, by vehicle type. Quantity and price start at 0: fill them in on the Inventory page.
        seedGroup("Tyre ", "Tyres - Passenger cars",
            "145/80R13", "155/80R13", "165/65R14", "175/65R14", "185/65R15",
            "195/65R15", "195/55R16", "205/55R16", "205/60R16");
        seedGroup("Tyre ", "Tyres - SUVs and pickups",
            "215/60R16", "215/65R16", "225/65R17", "265/65R17", "265/70R16");
        seedGroup("Tyre ", "Tyres - Heavy vehicles and trucks",
            "295/80R22.5", "315/80R22.5", "385/65R22.5");

        // Oils: engine oil grades, then gearbox / transmission oils with what they are used for.
        seedGroup("Engine Oil ", "Oil - Engine oil",
            "0W-20", "0W-30", "0W-40", "5W-20", "5W-30", "5W-40",
            "10W-30", "10W-40", "15W-40", "20W-50");
        seedGroup("", "Oil - Gearbox and transmission",
            "Gear Oil 75W-80 (Manual gearbox)",
            "Gear Oil 75W-90 (Manual gearbox)",
            "Gear Oil 80W-90 (Compatible manual gearbox)",
            "ATF Dexron III (Compatible automatic gearbox)",
            "ATF Dexron VI (Compatible automatic gearbox)",
            "ATF Mercon (Compatible automatic gearbox)",
            "CVT Fluid (CVT transmission)",
            "DCT Fluid (Dual-clutch transmission)",
            "Gear Oil 75W-140 (Selected differentials)");

        seedGroup("Brake Fluid ", "Brake fluid",
            "DOT 3 (Selected older cars)",
            "DOT 4 (Most conventional cars)",
            "DOT 4 LV (Cars requiring low-viscosity brake fluid)",
            "DOT 5.1 (Cars specifying DOT 5.1)");

        seedGroup("", "Coolant",
            "G11 Coolant (Compatible older cooling systems)",
            "G12 Coolant (Compatible vehicle cooling systems)",
            "G12+ Coolant (Compatible cooling systems)",
            "G12++ Coolant (Compatible cooling systems)",
            "G13 Coolant (Compatible modern cooling systems)",
            "G12evo Coolant (Vehicles requiring this specification)",
            "Universal Antifreeze (Only where specifications permit)",
            "Ready-mixed Coolant 50/50 (Ready-to-use coolant)",
            "Antifreeze Concentrate");

        // Batteries, by vehicle type.
        seedGroup("Battery 12V ", "Batteries - Passenger cars",
            "35Ah", "40Ah", "45Ah", "50Ah", "55Ah", "60Ah", "65Ah");
        seedGroup("Battery 12V ", "Batteries - SUVs, pickups and large cars",
            "70Ah", "75Ah", "80Ah", "85Ah", "90Ah", "95Ah", "100Ah", "105Ah");
        seedGroup("Battery 12V ", "Batteries - Trucks and buses",
            "120Ah", "135Ah", "150Ah", "160Ah", "180Ah", "200Ah", "225Ah");
    }

    private void seedGroup(String namePrefix, String category, String... sizes) {
        for (String size : sizes) add(namePrefix + size, category, 0, 0.00, 2);
    }

    /** Main group of a category: "Tyres - Passenger cars" gives "Tyres". */
    public static String groupOf(String category) {
        int i = category.indexOf(" - ");
        return i < 0 ? category : category.substring(0, i);
    }

    public synchronized List<Product> all() {
        return Collections.unmodifiableList(new ArrayList<>(products));
    }

    public synchronized Optional<Product> find(int id) {
        return products.stream().filter(p -> p.getId() == id).findFirst();
    }

    public synchronized Product add(String name, String category, int qty, double price, int lowLevel) {
        Product p = new Product(nextId++, name, category, qty, price, lowLevel);
        products.add(p);
        save();
        return p;
    }

    public synchronized boolean adjustStock(int id, int delta) {
        Optional<Product> p = find(id);
        if (p.isEmpty()) return false;
        p.get().setQuantity(p.get().getQuantity() + delta);
        save();
        return true;
    }

    public synchronized boolean update(int id, String name, String category, int qty, double price, int lowLevel) {
        Optional<Product> op = find(id);
        if (op.isEmpty()) return false;
        Product p = op.get();
        p.setName(name);
        p.setCategory(category);
        p.setQuantity(qty);
        p.setUnitPrice(price);
        p.setLowStockLevel(lowLevel);
        save();
        return true;
    }

    public synchronized boolean delete(int id) {
        boolean removed = products.removeIf(p -> p.getId() == id);
        if (removed) save();
        return removed;
    }

    public synchronized List<String> categories() {
        List<String> cats = new ArrayList<>();
        for (Product p : products) {
            if (!cats.contains(p.getCategory())) cats.add(p.getCategory());
        }
        return cats;
    }

    /** Main groups only, e.g. Tyres, Oil, Batteries. */
    public synchronized List<String> groups() {
        List<String> groups = new ArrayList<>();
        for (Product p : products) {
            String g = groupOf(p.getCategory());
            if (!groups.contains(g)) groups.add(g);
        }
        return groups;
    }

    public synchronized int totalUnits() {
        return products.stream().mapToInt(Product::getQuantity).sum();
    }

    public synchronized double totalValue() {
        return products.stream().mapToDouble(Product::getStockValue).sum();
    }

    public synchronized long lowStockCount() {
        return products.stream().filter(Product::isLowStock).count();
    }

    private void load() {
        if (!Files.exists(file)) return;
        try {
            for (String line : Files.readAllLines(file)) {
                if (line.isBlank() || line.startsWith("#")) continue;
                Product p = Product.fromCsv(line);
                if (p != null) {
                    products.add(p);
                    nextId = Math.max(nextId, p.getId() + 1);
                }
            }
        } catch (IOException e) {
            System.err.println("Could not read " + file + ": " + e.getMessage());
        }
    }

    private void save() {
        try {
            Files.createDirectories(file.getParent());
            List<String> lines = new ArrayList<>();
            lines.add("# id,name,category,quantity,unitPrice,lowStockLevel");
            for (Product p : products) lines.add(p.toCsv());
            Files.write(file, lines);
        } catch (IOException e) {
            System.err.println("Could not save " + file + ": " + e.getMessage());
        }
    }
}
