package com.autoshop;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * All sales, saved to data/sales.tsv.
 *
 * Recording a sale does not touch the stock. The goods are "reserved" so they
 * cannot be sold twice, and the stock goes down when the storekeeper issues
 * them. Cancelling a waiting sale frees the goods again.
 */
public class SalesStore {
    private final Path file;
    private final List<Sale> sales = new ArrayList<>();
    private int nextId = 1;

    public SalesStore(Path file) {
        this.file = file;
        load();
    }

    /** Newest first. */
    public synchronized List<Sale> all() {
        List<Sale> copy = new ArrayList<>(sales);
        java.util.Collections.reverse(copy);
        return copy;
    }

    public synchronized Optional<Sale> find(int id) {
        return sales.stream().filter(s -> s.getId() == id).findFirst();
    }

    /** Product id to quantity sold but not yet handed over. */
    public synchronized Map<Integer, Integer> reserved() {
        Map<Integer, Integer> map = new HashMap<>();
        for (Sale s : sales) {
            if (s.getStatus() != Sale.Status.PENDING) continue;
            for (Sale.Line l : s.getLines()) map.merge(l.productId(), l.quantity(), Integer::sum);
        }
        return map;
    }

    /** Sales and returns that the storekeeper still has to deal with. */
    public synchronized long pendingCount() {
        return sales.stream()
            .filter(s -> s.getStatus() == Sale.Status.PENDING || s.getStatus() == Sale.Status.RETURN_PENDING)
            .count();
    }

    /** Money taken for sales made on a day. Cancelled and returned sales do not count. */
    public synchronized double totalOn(LocalDate day) {
        return sales.stream()
            .filter(s -> s.getStatus().countsAsSale() && s.getSoldAt().toLocalDate().equals(day))
            .mapToDouble(Sale::total).sum();
    }

    public synchronized long countOn(LocalDate day) {
        return sales.stream()
            .filter(s -> s.getStatus().countsAsSale() && s.getSoldAt().toLocalDate().equals(day))
            .count();
    }

    /**
     * Record a new sale. wanted is product id to quantity; discounts is
     * product id to the amount of money taken off each unit of that product.
     * Throws IllegalArgumentException with a message for the user when it cannot be sold.
     */
    public synchronized Sale create(InventoryStore inventory, String soldBy, String customer, String payment,
                                    Map<Integer, Integer> wanted, Map<Integer, Double> discounts, double taxPercent) {
        if (wanted.isEmpty()) throw new IllegalArgumentException("Choose at least one product");
        if (Double.isNaN(taxPercent) || taxPercent < 0 || taxPercent > 100) {
            throw new IllegalArgumentException("Tax must be between 0 and 100 percent");
        }
        Map<Integer, Integer> reserved = reserved();
        List<Sale.Line> lines = new ArrayList<>();
        for (Map.Entry<Integer, Integer> e : wanted.entrySet()) {
            Product p = inventory.find(e.getKey()).orElseThrow(() -> new IllegalArgumentException("A chosen product no longer exists"));
            int qty = e.getValue();
            if (qty <= 0) throw new IllegalArgumentException("Quantity for " + p.getName() + " must be at least 1");
            if (p.getUnitPrice() <= 0) {
                throw new IllegalArgumentException(p.getName() + " has no price yet. A manager must set its price before it can be sold");
            }
            int available = p.getQuantity() - reserved.getOrDefault(p.getId(), 0);
            if (qty > available) {
                throw new IllegalArgumentException("Only " + Math.max(available, 0) + " of " + p.getName() + " available to sell");
            }
            double off = discounts.getOrDefault(p.getId(), 0.0);
            if (Double.isNaN(off) || off < 0) throw new IllegalArgumentException("Discount for " + p.getName() + " cannot be below 0");
            if (off > p.getUnitPrice()) {
                throw new IllegalArgumentException("Discount for " + p.getName() + " is more than its price");
            }
            lines.add(new Sale.Line(p.getId(), p.getName(), qty, p.getUnitPrice(), off));
        }
        String who = Sale.clean(customer);
        Sale sale = new Sale(nextId++, LocalDateTime.now().withNano(0), soldBy,
            who.isEmpty() ? "Walk-in customer" : who, Sale.clean(payment), lines, 0, taxPercent,
            Sale.Status.PENDING, null, null, "", 0);
        sales.add(sale);
        save();
        return sale;
    }

    /** The storekeeper hands the goods over: stock goes down. */
    public synchronized Sale issue(int id, InventoryStore inventory, String by) {
        Sale sale = find(id).orElseThrow(() -> new IllegalArgumentException("Sale not found"));
        if (sale.getStatus() != Sale.Status.PENDING) throw new IllegalArgumentException("Sale " + id + " is not waiting to be issued");
        Map<Integer, Integer> out = new LinkedHashMap<>();
        for (Sale.Line l : sale.getLines()) out.merge(l.productId(), l.quantity(), Integer::sum);
        String problem = inventory.removeStock(out);
        if (problem != null) throw new IllegalArgumentException(problem);
        sale.mark(Sale.Status.ISSUED, by);
        save();
        return sale;
    }

    /** Only a sale that is still waiting can be cancelled. */
    public synchronized Sale cancel(int id, String by) {
        Sale sale = find(id).orElseThrow(() -> new IllegalArgumentException("Sale not found"));
        if (sale.getStatus() != Sale.Status.PENDING) {
            throw new IllegalArgumentException("Sale " + id + " can no longer be cancelled");
        }
        sale.mark(Sale.Status.CANCELLED, by);
        save();
        return sale;
    }

    /** The customer brings the goods back. Only a sale whose goods were handed over can be returned. */
    public synchronized Sale requestReturn(int id, String by, String reason) {
        Sale sale = find(id).orElseThrow(() -> new IllegalArgumentException("Sale not found"));
        if (sale.getStatus() != Sale.Status.ISSUED) {
            throw new IllegalArgumentException("Sale " + id + " cannot be returned: its goods are not with the customer");
        }
        String why = Sale.clean(reason);
        if (why.isEmpty()) throw new IllegalArgumentException("Give a reason for the return");
        sale.setNote(why);
        sale.mark(Sale.Status.RETURN_PENDING, by);
        save();
        return sale;
    }

    /** A return recorded by mistake goes back to being a normal issued sale. */
    public synchronized Sale undoReturn(int id, String by) {
        Sale sale = find(id).orElseThrow(() -> new IllegalArgumentException("Sale not found"));
        if (sale.getStatus() != Sale.Status.RETURN_PENDING) {
            throw new IllegalArgumentException("Sale " + id + " has no return waiting");
        }
        sale.setNote("");
        sale.mark(Sale.Status.ISSUED, by);
        save();
        return sale;
    }

    /** The storekeeper takes the returned goods back: stock goes up. */
    public synchronized Sale receiveReturn(int id, InventoryStore inventory, String by) {
        Sale sale = find(id).orElseThrow(() -> new IllegalArgumentException("Sale not found"));
        if (sale.getStatus() != Sale.Status.RETURN_PENDING) {
            throw new IllegalArgumentException("Sale " + id + " has no return waiting");
        }
        Map<Integer, Integer> back = new LinkedHashMap<>();
        for (Sale.Line l : sale.getLines()) back.merge(l.productId(), l.quantity(), Integer::sum);
        inventory.addStock(back);
        sale.mark(Sale.Status.RETURNED, by);
        save();
        return sale;
    }

    private void load() {
        if (!Files.exists(file)) return;
        try {
            for (String line : Files.readAllLines(file)) {
                if (line.isBlank() || line.startsWith("#")) continue;
                try {
                    Sale s = Sale.fromTsv(line);
                    if (s != null) {
                        sales.add(s);
                        nextId = Math.max(nextId, s.getId() + 1);
                    }
                } catch (RuntimeException e) {
                    System.err.println("Skipping unreadable sale line: " + e.getMessage());
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
            lines.add("# id\tsoldAt\tsoldBy\tcustomer\tpayment\tstatus\thandledBy\thandledAt\tproducts(id~qty~price~discountEach~name|...)\tdiscountPercent\ttaxPercent\treturnReason\tdiscountAmount");
            for (Sale s : sales) lines.add(s.toTsv());
            Files.write(file, lines);
        } catch (IOException e) {
            System.err.println("Could not save " + file + ": " + e.getMessage());
        }
    }
}
