package com.autoshop;

/** A staff account. The password itself is never stored, only a salted hash of it. */
public class User {
    private final String username;
    private String fullName;
    private Role role;
    private String salt;   // hex
    private String hash;   // hex
    private boolean mustChangePassword;

    public User(String username, String fullName, Role role, String salt, String hash, boolean mustChangePassword) {
        this.username = username;
        this.fullName = fullName;
        this.role = role;
        this.salt = salt;
        this.hash = hash;
        this.mustChangePassword = mustChangePassword;
    }

    public String getUsername() { return username; }
    public String getFullName() { return fullName; }
    public Role getRole() { return role; }
    public String getSalt() { return salt; }
    public String getHash() { return hash; }
    public boolean isMustChangePassword() { return mustChangePassword; }
    public boolean can(Permission p) { return role.can(p); }

    void setRole(Role role) { this.role = role; }
    void setPassword(String salt, String hash, boolean mustChange) {
        this.salt = salt;
        this.hash = hash;
        this.mustChangePassword = mustChange;
    }

    /** username,fullName,role,salt,hash,mustChangePassword */
    String toCsv() {
        return username + "," + fullName.replace(",", " ") + "," + role.name() + "," + salt + "," + hash + "," + mustChangePassword;
    }

    static User fromCsv(String line) {
        String[] p = line.split(",", -1);
        if (p.length < 6) return null;
        return new User(p[0], p[1], Role.parse(p[2]), p[3], p[4], Boolean.parseBoolean(p[5]));
    }
}
