package com.autoshop;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/** Shop-wide settings, saved to data/settings.properties. */
public class Settings {
    private final Path file;
    private double taxPercent = 0;   // no tax until someone sets the rate

    public Settings(Path file) {
        this.file = file;
        if (!Files.exists(file)) return;
        try (InputStream in = Files.newInputStream(file)) {
            Properties p = new Properties();
            p.load(in);
            taxPercent = Double.parseDouble(p.getProperty("taxPercent", "0"));
        } catch (IOException | NumberFormatException e) {
            System.err.println("Could not read " + file + ": " + e.getMessage());
        }
    }

    public synchronized double getTaxPercent() { return taxPercent; }

    public synchronized void setTaxPercent(double percent) {
        if (Double.isNaN(percent) || percent < 0 || percent > 100) {
            throw new IllegalArgumentException("Tax rate must be between 0 and 100");
        }
        taxPercent = percent;
        try {
            Files.createDirectories(file.getParent());
            Properties p = new Properties();
            p.setProperty("taxPercent", String.valueOf(taxPercent));
            try (OutputStream out = Files.newOutputStream(file)) {
                p.store(out, "Auto Workshop settings");
            }
        } catch (IOException e) {
            System.err.println("Could not save " + file + ": " + e.getMessage());
        }
    }
}
