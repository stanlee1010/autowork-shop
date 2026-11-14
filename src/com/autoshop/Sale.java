package com.autoshop;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * One sale to a customer.
 *
 * Selling:   the shopkeeper records it (PENDING), the storekeeper hands the
 *            goods over (ISSUED) and only then does stock go down.
 * Returning: the shopkeeper records the return (RETURN_PENDING), the
 *            storekeeper receives the goods back (RETURNED) and stock goes up.
 *
 * Money: each product can have an amount of money taken off every unit.
 *        Subtotal, minus the discounts, plus tax on what is left, gives the total.
 */
public class Sale {
    public enum Status {
        PENDING("Waiting for storekeeper"),
        ISSUED("Goods issued"),
        CANCELLED("Cancelled"),
        RETURN_PENDING("Return waiting for storekeeper"),
        RETURNED("Returned and refunded");

        private final String label;
        Status(String label) { this.label = label; }
        public String getLabel() { return label; }

        /** Does this sale count as money taken? */
        public boolean countsAsSale() { return this == PENDING || this == ISSUED || this == RETURN_PENDING; }
    }

    /**
     * One product on the sale. Name and price are copied so later edits do not change old sales.
     * discount is the amount of money taken off each unit.
     */
    public record Line(int productId, String name, int quantity, double unitPrice, double discount) {
        /** At the normal price, before discount. */
        public double total() { return quantity * unitPrice; }
        public double discountTotal() { return quantity * discount; }
    }

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final int id;
    private final LocalDateTime soldAt;
    private final String soldBy;
    private final String customer;
    private final String payment;
    private final List<Line> lines;
    private final double discountPercent;
    private final double discountAmount;   // whole-sale discounts: only on sales made before discounts became per item
    private final double taxPercent;   // the rate in force when the sale was made
    private Status status;
    private String handledBy;          // who did the latest step (issued, cancelled, returned)
    private LocalDateTime handledAt;
    private String note;               // reason for a return

    public Sale(int id, LocalDateTime soldAt, String soldBy, String customer, String payment,
                List<Line> lines, double discountPercent, double taxPercent,
                Status status, String handledBy, LocalDateTime handledAt, String note, double discountAmount) {
        this.id = id;
        this.soldAt = soldAt;
        this.soldBy = soldBy;
        this.customer = customer;
        this.payment = payment;
        this.lines = lines;
        this.discountPercent = discountPercent;
        this.discountAmount = discountAmount;
        this.taxPercent = taxPercent;
        this.status = status;
        this.handledBy = handledBy;
        this.handledAt = handledAt;
        this.note = note == null ? "" : note;
    }

    public int getId() { return id; }
    public LocalDateTime getSoldAt() { return soldAt; }
    public String getSoldAtText() { return soldAt.format(STAMP); }
    public String getSoldBy() { return soldBy; }
    public String getCustomer() { return customer; }
    public String getPayment() { return payment; }
    public List<Line> getLines() { return lines; }
    public double getDiscountPercent() { return discountPercent; }
    public double getTaxPercent() { return taxPercent; }
    public Status getStatus() { return status; }
    public String getHandledBy() { return handledBy; }
    public String getHandledAtText() { return handledAt == null ? "" : handledAt.format(STAMP); }
    public String getNote() { return note; }

    /** Goods at their normal prices. */
    public double subtotal() {
        return round(lines.stream().mapToDouble(Line::total).sum());
    }

    /** Everything taken off the sale: the discounts on each product, plus any old whole-sale discount. */
    public double discount() {
        double perItem = lines.stream().mapToDouble(Line::discountTotal).sum();
        double wholeSale = discountAmount > 0 ? discountAmount : subtotal() * discountPercent / 100.0;
        return round(Math.min(perItem + wholeSale, subtotal()));
    }

    public boolean hasDiscount() { return discount() > 0; }

    /** "per item", "10 percent" or "fixed amount", for receipts and lists. */
    public String discountKind() {
        if (lines.stream().anyMatch(l -> l.discount() > 0)) return "per item";
        if (discountAmount > 0) return "fixed amount";
        return (discountPercent == Math.rint(discountPercent) ? String.valueOf((long) discountPercent) : String.valueOf(discountPercent)) + " percent";
    }

    /** Tax is charged on the price after discount. */
    public double tax() {
        return round((subtotal() - discount()) * taxPercent / 100.0);
    }

    /** What the customer pays, and what is refunded on a return. */
    public double total() {
        return round(subtotal() - discount() + tax());
    }

    private static double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    void mark(Status status, String by) {
        this.status = status;
        this.handledBy = by;
        this.handledAt = LocalDateTime.now().withNano(0);
    }

    void setNote(String note) { this.note = clean(note); }

    // One sale per line of the file, fields separated by tabs.
    // Products are "id~quantity~price~discountEach~name" joined by "|".
    // Older sales have "id~quantity~price~name", with no discount on the product.

    String toTsv() {
        List<String> items = new ArrayList<>();
        for (Line l : lines) {
            items.add(l.productId() + "~" + l.quantity() + "~" + l.unitPrice() + "~" + l.discount() + "~" + clean(l.name()).replace("~", "-").replace("|", "/"));
        }
        return String.join("\t",
            String.valueOf(id), soldAt.toString(), soldBy, clean(customer), clean(payment), status.name(),
            handledBy == null ? "" : handledBy, handledAt == null ? "" : handledAt.toString(),
            String.join("|", items),
            String.valueOf(discountPercent), String.valueOf(taxPercent), clean(note), String.valueOf(discountAmount));
    }

    static Sale fromTsv(String line) {
        String[] p = line.split("\t", -1);
        if (p.length < 9) return null;
        List<Line> lines = new ArrayList<>();
        for (String item : p[8].split("\\|")) {
            String[] f = item.split("~", 5);
            if (f.length == 5) {
                lines.add(new Line(Integer.parseInt(f[0]), f[4], Integer.parseInt(f[1]), Double.parseDouble(f[2]), Double.parseDouble(f[3])));
            } else if (f.length == 4) {
                lines.add(new Line(Integer.parseInt(f[0]), f[3], Integer.parseInt(f[1]), Double.parseDouble(f[2]), 0));
            }
        }
        // Sales saved before discounts and tax existed have neither.
        double discount = p.length > 9 && !p[9].isEmpty() ? Double.parseDouble(p[9]) : 0;
        double tax = p.length > 10 && !p[10].isEmpty() ? Double.parseDouble(p[10]) : 0;
        String note = p.length > 11 ? p[11] : "";
        double discountAmount = p.length > 12 && !p[12].isEmpty() ? Double.parseDouble(p[12]) : 0;
        return new Sale(Integer.parseInt(p[0]), LocalDateTime.parse(p[1]), p[2], p[3], p[4], lines, discount, tax,
            Status.valueOf(p[5]), p[6].isEmpty() ? null : p[6], p[7].isEmpty() ? null : LocalDateTime.parse(p[7]), note, discountAmount);
    }

    /** Tabs and line breaks would break the file format. */
    static String clean(String s) {
        return s == null ? "" : s.replaceAll("[\\t\\r\\n]+", " ").trim();
    }
}
