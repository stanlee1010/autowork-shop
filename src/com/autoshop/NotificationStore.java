package com.autoshop;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Messages for the manager and the director, saved to data/notifications.tsv.
 * One is written each time a sale is recorded with a discount or with a tax
 * rate different from the shop's standard rate. Each message remembers who
 * has already read it, so every reader has their own unread count.
 */
public class NotificationStore {
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    public static class Notification {
        private final int id;
        private final LocalDateTime at;
        private final int saleId;
        private final String text;
        private final Set<String> readBy;

        Notification(int id, LocalDateTime at, int saleId, String text, Set<String> readBy) {
            this.id = id;
            this.at = at;
            this.saleId = saleId;
            this.text = text;
            this.readBy = readBy;
        }

        public int getId() { return id; }
        public String getAtText() { return at.format(STAMP); }
        public int getSaleId() { return saleId; }
        public String getText() { return text; }
        public boolean isReadBy(String username) { return readBy.contains(username.toLowerCase()); }
    }

    private final Path file;
    private final List<Notification> items = new ArrayList<>();
    private int nextId = 1;

    public NotificationStore(Path file) {
        this.file = file;
        load();
    }

    public synchronized void add(int saleId, String text) {
        items.add(new Notification(nextId++, LocalDateTime.now().withNano(0), saleId, Sale.clean(text), new LinkedHashSet<>()));
        save();
    }

    /** Newest first. */
    public synchronized List<Notification> all() {
        List<Notification> copy = new ArrayList<>(items);
        Collections.reverse(copy);
        return copy;
    }

    public synchronized long unreadCount(String username) {
        return items.stream().filter(n -> !n.isReadBy(username)).count();
    }

    public synchronized void markAllRead(String username) {
        boolean changed = false;
        for (Notification n : items) changed |= n.readBy.add(username.toLowerCase());
        if (changed) save();
    }

    private void load() {
        if (!Files.exists(file)) return;
        try {
            for (String line : Files.readAllLines(file)) {
                if (line.isBlank() || line.startsWith("#")) continue;
                try {
                    String[] p = line.split("\t", -1);
                    if (p.length < 5) continue;
                    Set<String> readBy = new LinkedHashSet<>();
                    for (String u : p[3].split(",")) if (!u.isBlank()) readBy.add(u.trim().toLowerCase());
                    Notification n = new Notification(Integer.parseInt(p[0]), LocalDateTime.parse(p[1]),
                        Integer.parseInt(p[2]), p[4], readBy);
                    items.add(n);
                    nextId = Math.max(nextId, n.id + 1);
                } catch (RuntimeException e) {
                    System.err.println("Skipping unreadable notification line: " + e.getMessage());
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
            lines.add("# id\tat\tsaleId\treadBy(comma separated)\ttext");
            for (Notification n : items) {
                lines.add(String.join("\t", String.valueOf(n.id), n.at.toString(), String.valueOf(n.saleId),
                    String.join(",", n.readBy), n.text));
            }
            Files.write(file, lines);
        } catch (IOException e) {
            System.err.println("Could not save " + file + ": " + e.getMessage());
        }
    }
}
