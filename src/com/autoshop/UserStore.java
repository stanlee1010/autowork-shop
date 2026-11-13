package com.autoshop;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

/**
 * Staff accounts, saved to data/users.csv. Passwords are stored as salted
 * PBKDF2 hashes, never as plain text.
 *
 * On the very first run one account is created so the owner can get in:
 *   username: owner     password: admin123
 * The owner is made to choose a new password at first login.
 */
public class UserStore {
    public static final String DEFAULT_OWNER = "owner";
    public static final String DEFAULT_PASSWORD = "admin123";
    public static final int MIN_PASSWORD_LENGTH = 6;

    private static final int ITERATIONS = 120_000;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final byte[] DUMMY_SALT = new byte[16];

    private final Path file;
    private final List<User> users = new ArrayList<>();

    public UserStore(Path file) {
        this.file = file;
        load();
        if (users.isEmpty()) {
            add(DEFAULT_OWNER, "Owner", Role.OWNER, DEFAULT_PASSWORD, true);
        }
    }

    public synchronized List<User> all() {
        return new ArrayList<>(users);
    }

    public synchronized Optional<User> find(String username) {
        String key = normalise(username);
        return users.stream().filter(u -> u.getUsername().equals(key)).findFirst();
    }

    /** Returns the user when the username and password are correct. */
    public synchronized Optional<User> verify(String username, String password) {
        Optional<User> u = find(username);
        if (u.isEmpty()) {
            hash(password, DUMMY_SALT); // take the same time whether or not the user exists
            return Optional.empty();
        }
        byte[] expected = HexFormat.of().parseHex(u.get().getHash());
        byte[] actual = hash(password, HexFormat.of().parseHex(u.get().getSalt()));
        return MessageDigest.isEqual(expected, actual) ? u : Optional.empty();
    }

    public synchronized User add(String username, String fullName, Role role, String password, boolean mustChange) {
        String key = normalise(username);
        if (!key.matches("[a-z0-9._-]{3,20}")) {
            throw new IllegalArgumentException("Username must be 3 to 20 letters, numbers, dots, dashes or underscores");
        }
        if (find(key).isPresent()) throw new IllegalArgumentException("That username is already taken");
        String name = fullName == null ? "" : fullName.trim();
        if (name.isEmpty()) throw new IllegalArgumentException("Full name is required");
        checkPassword(password);
        byte[] salt = newSalt();
        User u = new User(key, name, role, hex(salt), hex(hash(password, salt)), mustChange);
        users.add(u);
        save();
        return u;
    }

    public synchronized void setPassword(String username, String password, boolean mustChange) {
        User u = find(username).orElseThrow(() -> new IllegalArgumentException("User not found"));
        checkPassword(password);
        byte[] salt = newSalt();
        u.setPassword(hex(salt), hex(hash(password, salt)), mustChange);
        save();
    }

    public synchronized void setRole(String username, Role role) {
        User u = find(username).orElseThrow(() -> new IllegalArgumentException("User not found"));
        if (u.getRole() == Role.OWNER && role != Role.OWNER && ownerCount() <= 1) {
            throw new IllegalArgumentException("There must always be at least one Owner / Director");
        }
        u.setRole(role);
        save();
    }

    public synchronized void delete(String username) {
        User u = find(username).orElseThrow(() -> new IllegalArgumentException("User not found"));
        if (u.getRole() == Role.OWNER && ownerCount() <= 1) {
            throw new IllegalArgumentException("There must always be at least one Owner / Director");
        }
        users.remove(u);
        save();
    }

    private long ownerCount() {
        return users.stream().filter(u -> u.getRole() == Role.OWNER).count();
    }

    private static void checkPassword(String password) {
        if (password == null || password.length() < MIN_PASSWORD_LENGTH) {
            throw new IllegalArgumentException("Password must be at least " + MIN_PASSWORD_LENGTH + " characters");
        }
    }

    private static String normalise(String username) {
        return username == null ? "" : username.trim().toLowerCase();
    }

    private static byte[] newSalt() {
        byte[] salt = new byte[16];
        RANDOM.nextBytes(salt);
        return salt;
    }

    private static byte[] hash(String password, byte[] salt) {
        try {
            PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, ITERATIONS, 256);
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Password hashing is not available", e);
        }
    }

    private static String hex(byte[] bytes) {
        return HexFormat.of().formatHex(bytes);
    }

    private void load() {
        if (!Files.exists(file)) return;
        try {
            for (String line : Files.readAllLines(file)) {
                if (line.isBlank() || line.startsWith("#")) continue;
                User u = User.fromCsv(line);
                if (u != null) users.add(u);
            }
        } catch (IOException | RuntimeException e) {
            System.err.println("Could not read " + file + ": " + e.getMessage());
        }
    }

    private void save() {
        try {
            Files.createDirectories(file.getParent());
            List<String> lines = new ArrayList<>();
            lines.add("# username,fullName,role,salt,passwordHash,mustChangePassword");
            for (User u : users) lines.add(u.toCsv());
            Files.write(file, lines);
        } catch (IOException e) {
            System.err.println("Could not save " + file + ": " + e.getMessage());
        }
    }
}
