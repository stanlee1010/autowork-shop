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
        // Tyres, by vehicle type. Prices are in Tanzanian shillings. The number after the category is the starting quantity of each item.
        seedGroup("Tyre ", "Tyres - Passenger cars", 20,
            "145/80R13 = 95000", "155/80R13 = 100000", "165/65R14 = 115000", "175/65R14 = 125000", "185/65R15 = 145000",
            "195/65R15 = 160000", "195/55R16 = 190000", "205/55R16 = 200000", "205/60R16 = 210000");
        seedGroup("Tyre ", "Tyres - SUVs and pickups", 12,
            "215/60R16 = 230000", "215/65R16 = 240000", "225/65R17 = 290000", "265/65R17 = 420000", "265/70R16 = 400000");
        seedGroup("Tyre ", "Tyres - Heavy vehicles and trucks", 6,
            "295/80R22.5 = 850000", "315/80R22.5 = 950000", "385/65R22.5 = 1100000");

        // Oils: engine oil grades, then gearbox / transmission oils with what they are used for.
        seedGroup("Engine Oil ", "Oil - Engine oil", 24,
            "0W-20 = 95000", "0W-30 = 95000", "0W-40 = 100000", "5W-20 = 80000", "5W-30 = 75000", "5W-40 = 80000",
            "10W-30 = 60000", "10W-40 = 60000", "15W-40 = 50000", "20W-50 = 45000");
        seedGroup("", "Oil - Gearbox and transmission", 12,
            "Gear Oil 75W-80 (Manual gearbox) = 22000",
            "Gear Oil 75W-90 (Manual gearbox) = 25000",
            "Gear Oil 80W-90 (Compatible manual gearbox) = 18000",
            "ATF Dexron III (Compatible automatic gearbox) = 18000",
            "ATF Dexron VI (Compatible automatic gearbox) = 28000",
            "ATF Mercon (Compatible automatic gearbox) = 20000",
            "CVT Fluid (CVT transmission) = 35000",
            "DCT Fluid (Dual-clutch transmission) = 45000",
            "Gear Oil 75W-140 (Selected differentials) = 40000");

        seedGroup("Brake Fluid ", "Brake fluid", 20,
            "DOT 3 (Selected older cars) = 8000",
            "DOT 4 (Most conventional cars) = 10000",
            "DOT 4 LV (Cars requiring low-viscosity brake fluid) = 18000",
            "DOT 5.1 (Cars specifying DOT 5.1) = 22000");

        seedGroup("", "Coolant", 15,
            "G11 Coolant (Compatible older cooling systems) = 15000",
            "G12 Coolant (Compatible vehicle cooling systems) = 18000",
            "G12+ Coolant (Compatible cooling systems) = 20000",
            "G12++ Coolant (Compatible cooling systems) = 22000",
            "G13 Coolant (Compatible modern cooling systems) = 25000",
            "G12evo Coolant (Vehicles requiring this specification) = 28000",
            "Universal Antifreeze (Only where specifications permit) = 12000",
            "Ready-mixed Coolant 50/50 (Ready-to-use coolant) = 10000",
            "Antifreeze Concentrate = 20000");

        // Batteries, by vehicle type.
        seedGroup("Battery 12V ", "Batteries - Passenger cars", 10,
            "35Ah = 120000", "40Ah = 135000", "45Ah = 150000", "50Ah = 165000", "55Ah = 180000", "60Ah = 195000", "65Ah = 210000");
        seedGroup("Battery 12V ", "Batteries - SUVs, pickups and large cars", 6,
            "70Ah = 230000", "75Ah = 245000", "80Ah = 260000", "85Ah = 275000", "90Ah = 290000", "95Ah = 305000", "100Ah = 320000", "105Ah = 340000");
        seedGroup("Battery 12V ", "Batteries - Trucks and buses", 4,
            "120Ah = 400000", "135Ah = 450000", "150Ah = 500000", "160Ah = 540000", "180Ah = 600000", "200Ah = 680000", "225Ah = 760000");
    }

    /** Each entry is "name = price", the price being in Tanzanian shillings for one unit. */
    private void seedGroup(String namePrefix, String category, int quantity, String... items) {
        for (String item : items) {
            int cut = item.lastIndexOf(" = ");
            add(namePrefix + item.substring(0, cut), category, quantity, Double.parseDouble(item.substring(cut + 3)), 2);
        }
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

    /**
     * Take several products out of stock in one go, for a sale. Either every
     * product is reduced or none is. Returns null on success, or a message
     * saying what is missing.
     */
    public synchronized String removeStock(java.util.Map<Integer, Integer> quantities) {
        for (java.util.Map.Entry<Integer, Integer> e : quantities.entrySet()) {
            Optional<Product> p = find(e.getKey());
            if (p.isEmpty()) return "A product on this sale no longer exists in the inventory";
            if (p.get().getQuantity() < e.getValue()) {
                return "Not enough " + p.get().getName() + " in stock: " + p.get().getQuantity() + " left, " + e.getValue() + " needed";
            }
        }
        for (java.util.Map.Entry<Integer, Integer> e : quantities.entrySet()) {
            Product p = find(e.getKey()).get();
            p.setQuantity(p.getQuantity() - e.getValue());
        }
        save();
        return null;
    }

    /**
     * Put several products back into stock in one go, for a customer return.
     * Returns how many of them no longer exist in the inventory and were skipped.
     */
    public synchronized int addStock(java.util.Map<Integer, Integer> quantities) {
        int missing = 0;
        for (java.util.Map.Entry<Integer, Integer> e : quantities.entrySet()) {
            Optional<Product> p = find(e.getKey());
            if (p.isEmpty()) { missing++; continue; }
            p.get().setQuantity(p.get().getQuantity() + e.getValue());
        }
        save();
        return missing;
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
