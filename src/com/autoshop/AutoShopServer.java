package com.autoshop;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;

/**
 * Auto Workshop website. Uses only the JDK's built-in HTTP server, so no
 * external libraries are needed.
 *
 *   javac -d out src/com/autoshop/*.java
 *   java -cp out com.autoshop.AutoShopServer
 *
 * Then open http://localhost:8080
 *
 * Every page except the login page needs a signed-in member of staff, and
 * each page or action checks the permission that the user's role gives them.
 */
public class AutoShopServer {
    private static final long SESSION_MILLIS = 8L * 60 * 60 * 1000;   // signed in for 8 hours
    private static final int MAX_FAILED_LOGINS = 5;
    private static final long LOCK_MILLIS = 5L * 60 * 1000;           // then locked for 5 minutes
    private static final SecureRandom RANDOM = new SecureRandom();

    private final InventoryStore store;
    private final UserStore users;
    private final SalesStore sales;
    private final Settings settings;
    private final NotificationStore notifications;

    private record Session(String username, long expiresAt) {}
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private final Map<String, Integer> failedLogins = new ConcurrentHashMap<>();
    private final Map<String, Long> lockedUntil = new ConcurrentHashMap<>();

    /** A page that needs a signed-in user. */
    private interface Page {
        void handle(HttpExchange ex, User user) throws IOException;
    }

    public AutoShopServer(InventoryStore store, UserStore users, SalesStore sales, Settings settings, NotificationStore notifications) {
        this.store = store;
        this.users = users;
        this.sales = sales;
        this.settings = settings;
        this.notifications = notifications;
    }

    /** Optional arguments: port (default 8080) and data folder (default "data"). */
    public static void main(String[] args) throws IOException {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 8080;
        Path dataDir = Path.of(args.length > 1 ? args[1] : "data");

        InventoryStore store = new InventoryStore(dataDir.resolve("inventory.csv"));
        UserStore users = new UserStore(dataDir.resolve("users.csv"));
        SalesStore sales = new SalesStore(dataDir.resolve("sales.tsv"));
        Settings settings = new Settings(dataDir.resolve("settings.properties"));
        AutoShopServer app = new AutoShopServer(store, users, sales, settings,
            new NotificationStore(dataDir.resolve("notifications.tsv")));

        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        // Open to everyone
        server.createContext("/login", app::handleLogin);
        server.createContext("/logout", app::handleLogout);
        server.createContext("/style.css", app::handleCss);
        // Signed-in staff only. The permission is what the user's role must allow.
        server.createContext("/", app.guard(null, app::handleRoot));
        server.createContext("/account", app.guard(null, app::handleAccount));
        server.createContext("/inventory", app.guard(Permission.VIEW_INVENTORY, app::handleInventory));
        server.createContext("/inventory/add", app.guard(Permission.ADD_PRODUCT, app::handleAdd));
        server.createContext("/inventory/adjust", app.guard(Permission.ADJUST_STOCK, app::handleAdjust));
        server.createContext("/inventory/edit", app.guard(Permission.EDIT_PRODUCT, app::handleEdit));
        server.createContext("/inventory/delete", app.guard(Permission.DELETE_PRODUCT, app::handleDelete));
        // The Sales page is open to sellers, storekeepers and people who see the money; it checks inside.
        server.createContext("/sales", app.guard(null, app::handleSales));
        server.createContext("/sales/receipt", app.guard(null, app::handleReceipt));
        server.createContext("/sales/new", app.guard(Permission.SELL, app::handleSaleNew));
        server.createContext("/sales/cancel", app.guard(Permission.SELL, app::handleSaleCancel));
        server.createContext("/sales/issue", app.guard(Permission.ADJUST_STOCK, app::handleSaleIssue));
        server.createContext("/sales/return", app.guard(Permission.SELL, app::handleSaleReturn));
        server.createContext("/sales/return-undo", app.guard(Permission.SELL, app::handleSaleReturnUndo));
        server.createContext("/sales/receive", app.guard(Permission.ADJUST_STOCK, app::handleSaleReceive));
        server.createContext("/sales/tax", app.guard(null, app::handleTax));
        server.createContext("/notifications", app.guard(null, app::handleNotifications));
        server.createContext("/staff", app.guard(Permission.MANAGE_STAFF, app::handleStaff));
        server.createContext("/staff/add", app.guard(Permission.MANAGE_STAFF, app::handleStaffAdd));
        server.createContext("/staff/role", app.guard(Permission.MANAGE_STAFF, app::handleStaffRole));
        server.createContext("/staff/reset", app.guard(Permission.MANAGE_STAFF, app::handleStaffReset));
        server.createContext("/staff/delete", app.guard(Permission.MANAGE_STAFF, app::handleStaffDelete));
        server.setExecutor(Executors.newFixedThreadPool(8));
        server.start();
        System.out.println("Auto Workshop running at http://localhost:" + port);
    }

    // ------------------------------------------------------ login & sessions

    /**
     * Wraps a page so it only runs for a signed-in user whose role has the
     * needed permission. Pass null when any signed-in user may see the page.
     */
    private HttpHandler guard(Permission needed, Page page) {
        return ex -> {
            try {
                User user = currentUser(ex);
                if (user == null) {
                    redirect(ex, "/login");
                    return;
                }
                String path = ex.getRequestURI().getPath();
                if (user.isMustChangePassword() && !path.equals("/account")) {
                    redirect(ex, "/account");
                    return;
                }
                if (needed != null && !user.can(needed)) {
                    sendDenied(ex, user);
                    return;
                }
                page.handle(ex, user);
            } catch (RuntimeException e) {
                e.printStackTrace();
                sendText(ex, 500, "Server error");
            }
        };
    }

    private void sendDenied(HttpExchange ex, User user) throws IOException {
        String body = """
            <div class="page-head">
              <h1>%s No access</h1>
              <p class="muted">Your role (%s) is not allowed to do this. Ask the Owner / Director if you need access.</p>
            </div>
            <a class="btn" href="/">Back to dashboard</a>
            """.formatted(Icons.SHIELD, h(user.getRole().getLabel()));
        sendHtml(ex, 403, page(user, "No access", "", body));
    }

    /** The signed-in user for this request, or null. */
    private User currentUser(HttpExchange ex) {
        String token = cookie(ex, "session");
        if (token == null) return null;
        Session s = sessions.get(token);
        if (s == null) return null;
        if (s.expiresAt() < System.currentTimeMillis()) {
            sessions.remove(token);
            return null;
        }
        // Looked up every time, so a role change or deleted account takes effect at once.
        return users.find(s.username()).orElse(null);
    }

    private static String cookie(HttpExchange ex, String name) {
        List<String> headers = ex.getRequestHeaders().get("Cookie");
        if (headers == null) return null;
        for (String header : headers) {
            for (String part : header.split(";")) {
                String[] kv = part.trim().split("=", 2);
                if (kv.length == 2 && kv[0].equals(name)) return kv[1];
            }
        }
        return null;
    }

    private void handleLogin(HttpExchange ex) throws IOException {
        if (!"POST".equals(ex.getRequestMethod())) {
            if (currentUser(ex) != null) { redirect(ex, "/"); return; }
            sendHtml(ex, 200, loginPage("", ""));
            return;
        }
        Map<String, String> f = parseQuery(readBody(ex));
        String username = f.getOrDefault("username", "").trim().toLowerCase();
        String password = f.getOrDefault("password", "");

        Long lock = lockedUntil.get(username);
        if (lock != null && lock > System.currentTimeMillis()) {
            sendHtml(ex, 429, loginPage("Too many wrong passwords. Try again in a few minutes.", username));
            return;
        }

        Optional<User> user = users.verify(username, password);
        if (user.isEmpty()) {
            if (users.find(username).isPresent()) {
                int fails = failedLogins.merge(username, 1, Integer::sum);
                if (fails >= MAX_FAILED_LOGINS) {
                    lockedUntil.put(username, System.currentTimeMillis() + LOCK_MILLIS);
                    failedLogins.remove(username);
                }
            }
            sendHtml(ex, 401, loginPage("Wrong username or password.", username));
            return;
        }

        failedLogins.remove(username);
        lockedUntil.remove(username);
        byte[] raw = new byte[32];
        RANDOM.nextBytes(raw);
        String token = HexFormat.of().formatHex(raw);
        sessions.put(token, new Session(user.get().getUsername(), System.currentTimeMillis() + SESSION_MILLIS));
        ex.getResponseHeaders().add("Set-Cookie",
            "session=" + token + "; Path=/; HttpOnly; SameSite=Strict; Max-Age=" + (SESSION_MILLIS / 1000));
        redirect(ex, "/");
    }

    private void handleLogout(HttpExchange ex) throws IOException {
        String token = cookie(ex, "session");
        if (token != null) sessions.remove(token);
        ex.getResponseHeaders().add("Set-Cookie", "session=; Path=/; HttpOnly; SameSite=Strict; Max-Age=0");
        redirect(ex, "/login");
    }

    /** Sign out everywhere, e.g. after a password reset or a deleted account. */
    private void endSessionsOf(String username) {
        sessions.values().removeIf(s -> s.username().equals(username));
    }

    private String loginPage(String error, String username) {
        // The first-run hint disappears as soon as the owner has chosen their own password.
        boolean firstRun = users.find(UserStore.DEFAULT_OWNER).map(User::isMustChangePassword).orElse(false);
        String hint = firstRun
            ? "<p class=\"hint\">First time here? Sign in with username <strong>" + UserStore.DEFAULT_OWNER
                + "</strong> and password <strong>" + UserStore.DEFAULT_PASSWORD
                + "</strong>. You will be asked to choose a new password.</p>"
            : "";
        return """
            <!doctype html>
            <html lang="en">
            <head>
              <meta charset="utf-8">
              <meta name="viewport" content="width=device-width, initial-scale=1">
              <title>Sign in - Auto Workshop</title>
              <link rel="stylesheet" href="/style.css">
            </head>
            <body class="auth">
              <div class="login-card">
                <div class="brand">%s Auto Workshop</div>
                <h1>Staff sign in</h1>
                <p class="muted">Sign in with the account your Owner / Director gave you.</p>
                %s
                <form method="post" action="/login">
                  <label>Username<input name="username" value="%s" required autofocus autocomplete="username"></label>
                  <label>Password<input type="password" name="password" required autocomplete="current-password"></label>
                  <button class="btn primary">Sign in</button>
                </form>
                %s
              </div>
            </body>
            </html>
            """.formatted(
                Icons.WRENCH,
                error.isEmpty() ? "" : "<div class=\"flash error\">" + h(error) + "</div>",
                h(username),
                hint
            );
    }

    // -------------------------------------------------------------- account

    private void handleAccount(HttpExchange ex, User user) throws IOException {
        if (isPost(ex)) {
            Map<String, String> f = parseQuery(readBody(ex));
            String current = f.getOrDefault("current", "");
            String next = f.getOrDefault("next", "");
            String confirm = f.getOrDefault("confirm", "");
            try {
                if (users.verify(user.getUsername(), current).isEmpty()) throw new IllegalArgumentException("Current password is wrong");
                if (!next.equals(confirm)) throw new IllegalArgumentException("The two new passwords do not match");
                if (next.equals(current)) throw new IllegalArgumentException("Choose a password different from the current one");
                users.setPassword(user.getUsername(), next, false);
                redirect(ex, "/account?msg=" + enc("Password changed"));
            } catch (IllegalArgumentException e) {
                redirect(ex, "/account?err=" + enc(e.getMessage()));
            }
            return;
        }
        Map<String, String> q = parseQuery(ex.getRequestURI().getRawQuery());
        StringBuilder perms = new StringBuilder();
        for (Permission p : Permission.values()) {
            perms.append("<li class=\"").append(user.can(p) ? "yes" : "no").append("\">")
                 .append(user.can(p) ? "&#10003; " : "&#10007; ").append(h(p.getLabel())).append("</li>");
        }
        String body = """
            <div class="page-head">
              <h1>%s My account</h1>
              <p class="muted">%s &middot; username <strong>%s</strong> &middot; role <strong>%s</strong></p>
            </div>
            %s
            <div class="card">
              <h2>Change password</h2>
              <form method="post" action="/account" class="grid">
                <label>Current password<input type="password" name="current" required autocomplete="current-password"></label>
                <label>New password (at least %d characters)<input type="password" name="next" required minlength="%d" autocomplete="new-password"></label>
                <label>New password again<input type="password" name="confirm" required minlength="%d" autocomplete="new-password"></label>
                <div class="form-actions"><button class="btn primary">Change password</button></div>
              </form>
            </div>
            <div class="card">
              <h2>What your role can do</h2>
              <ul class="perms">%s</ul>
            </div>
            """.formatted(
                Icons.PEOPLE, h(user.getFullName()), h(user.getUsername()), h(user.getRole().getLabel()),
                (user.isMustChangePassword()
                    ? "<div class=\"flash\">Please choose your own password before you continue.</div>"
                    : "") + flash(q),
                UserStore.MIN_PASSWORD_LENGTH, UserStore.MIN_PASSWORD_LENGTH, UserStore.MIN_PASSWORD_LENGTH,
                perms
            );
        sendHtml(ex, 200, page(user, "My account", "/account", body));
    }

    // ------------------------------------------------------------ dashboard

    private void handleRoot(HttpExchange ex, User user) throws IOException {
        if (!ex.getRequestURI().getPath().equals("/")) {
            sendText(ex, 404, "Not found");
            return;
        }
        StringBuilder tiles = new StringBuilder();
        if (user.can(Permission.VIEW_INVENTORY)) {
            tiles.append(tile("Products", String.valueOf(store.all().size()), false));
            tiles.append(tile("Units in stock", String.valueOf(store.totalUnits()), false));
            if (user.can(Permission.VIEW_FINANCIALS)) {
                tiles.append(tile("Stock value", money(store.totalValue()), false));
            }
            tiles.append(tile("Low stock alerts", String.valueOf(store.lowStockCount()), store.lowStockCount() > 0));
        }
        if (seesSalesMoney(user)) {
            tiles.append(tile("Sales today", money(sales.totalOn(LocalDate.now())), false));
        }
        if (canSeeSales(user)) {
            tiles.append(tile("Sales waiting for storekeeper", String.valueOf(sales.pendingCount()), sales.pendingCount() > 0));
        }
        if (seesNotifications(user)) {
            long unread = notifications.unreadCount(user.getUsername());
            tiles.append(tile("Unread notifications", String.valueOf(unread), unread > 0));
        }
        String subtitle = user.getRole() == Role.OWNER
            ? "Full business control and financial overview."
            : "Welcome, " + h(user.getFullName()) + ". Use the list on the left to open the pages your role can use.";
        String body = """
            <div class="page-head">
              <h1>%s %s Dashboard</h1>
              <p class="muted">%s</p>
            </div>
            <div class="stats">%s</div>
            """.formatted(Icons.GRID, h(user.getRole().getLabel()), subtitle, tiles);
        sendHtml(ex, 200, page(user, "Dashboard", "/", body));
    }

    private static String tile(String label, String value, boolean warn) {
        return "<div class=\"stat" + (warn ? " warn" : "") + "\"><span class=\"label\">" + label
            + "</span><span class=\"value\">" + value + "</span></div>";
    }

    // ------------------------------------------------------------ inventory

    private void handleInventory(HttpExchange ex, User user) throws IOException {
        if (!ex.getRequestURI().getPath().equals("/inventory")) {
            sendText(ex, 404, "Not found");
            return;
        }
        Map<String, String> q = parseQuery(ex.getRequestURI().getRawQuery());
        String filter = q.getOrDefault("cat", "");
        String search = q.getOrDefault("q", "").trim();

        boolean canAdjust = user.can(Permission.ADJUST_STOCK);
        boolean canEdit = user.can(Permission.EDIT_PRODUCT);
        boolean canDelete = user.can(Permission.DELETE_PRODUCT);
        boolean canAdd = user.can(Permission.ADD_PRODUCT);
        boolean showValue = user.can(Permission.VIEW_FINANCIALS);
        boolean showActions = canAdjust || canEdit || canDelete;
        int columns = 5 + (showValue ? 1 : 0) + (showActions ? 1 : 0);

        String searchForm = """
            <form method="get" action="/inventory" class="search">
              %s
              <input type="search" name="q" value="%s" placeholder="Search products, e.g. 5W-30, 205/55R16, 60Ah, DOT 4" autofocus>
              <button class="btn primary">Search</button>
              %s
            </form>
            """.formatted(
                filter.isEmpty() ? "" : "<input type=\"hidden\" name=\"cat\" value=\"" + h(filter) + "\">",
                h(search),
                search.isEmpty() ? "" : "<a class=\"btn\" href=\"/inventory" + (filter.isEmpty() ? "" : "?cat=" + enc(filter)) + "\">Clear</a>"
            );

        StringBuilder rows = new StringBuilder();
        for (Product p : store.all()) {
            if (!filter.isEmpty()
                    && !p.getCategory().equalsIgnoreCase(filter)
                    && !InventoryStore.groupOf(p.getCategory()).equalsIgnoreCase(filter)) continue;
            if (!matchesSearch(p, search)) continue;

            rows.append("<tr class=\"").append(p.isLowStock() ? "low" : "").append("\">")
                .append("<td>").append(p.getId()).append("</td>")
                .append("<td><strong>").append(h(p.getName())).append("</strong></td>")
                .append("<td><span class=\"pill\">").append(h(p.getCategory())).append("</span></td>")
                .append("<td class=\"num\">").append(p.getQuantity())
                .append(p.isLowStock() ? " <span class=\"badge\">LOW</span>" : "").append("</td>")
                .append("<td class=\"num\">").append(money(p.getUnitPrice())).append("</td>");
            if (showValue) {
                rows.append("<td class=\"num col-value\">").append(money(p.getStockValue())).append("</td>");
            }
            if (showActions) {
                rows.append("<td class=\"actions\">");
                if (canAdjust) {
                    rows.append("<form method=\"post\" action=\"/inventory/adjust\" class=\"inline\">")
                        .append("<input type=\"hidden\" name=\"id\" value=\"").append(p.getId()).append("\">")
                        .append("<input type=\"number\" name=\"delta\" value=\"1\" min=\"1\" class=\"tiny\" title=\"Amount\">")
                        .append("<button name=\"op\" value=\"in\" class=\"btn ok\" title=\"Stock in\">+</button>")
                        .append("<button name=\"op\" value=\"out\" class=\"btn bad\" title=\"Stock out\">&minus;</button>")
                        .append("</form>");
                }
                if (canEdit) {
                    rows.append("<a class=\"btn\" href=\"/inventory/edit?id=").append(p.getId()).append("\">Edit</a> ");
                }
                if (canDelete) {
                    rows.append("<form method=\"post\" action=\"/inventory/delete\" class=\"inline\" data-name=\"").append(h(p.getName()))
                        .append("\" onsubmit=\"return confirm('Delete ' + this.dataset.name + '?')\">")
                        .append("<input type=\"hidden\" name=\"id\" value=\"").append(p.getId()).append("\">")
                        .append("<button class=\"btn bad\">Delete</button></form>");
                }
                rows.append("</td>");
            }
            rows.append("</tr>");
        }
        if (rows.length() == 0) {
            rows.append("<tr><td colspan=\"").append(columns).append("\" class=\"muted center\">")
                .append(search.isEmpty() ? "No products yet." : "No products match \"" + h(search) + "\".")
                .append("</td></tr>");
        }

        StringBuilder cats = new StringBuilder();
        cats.append("<a class=\"chip").append(filter.isEmpty() ? " active" : "").append("\" href=\"/inventory\">All</a>");
        for (String g : store.groups()) {
            cats.append("<a class=\"chip").append(g.equalsIgnoreCase(filter) ? " active" : "")
                .append("\" href=\"/inventory?cat=").append(enc(g)).append("\">").append(h(g)).append("</a>");
        }
        // Second row: vehicle types inside the selected group (e.g. Tyres - Passenger cars).
        String activeGroup = filter.isEmpty() ? "" : InventoryStore.groupOf(filter);
        StringBuilder subs = new StringBuilder();
        for (String c : store.categories()) {
            if (c.equals(InventoryStore.groupOf(c)) || !InventoryStore.groupOf(c).equalsIgnoreCase(activeGroup)) continue;
            subs.append("<a class=\"chip").append(c.equalsIgnoreCase(filter) ? " active" : "")
                .append("\" href=\"/inventory?cat=").append(enc(c)).append("\">")
                .append(h(c.substring(InventoryStore.groupOf(c).length() + 3))).append("</a>");
        }
        if (subs.length() > 0) cats.append("</div><div class=\"chips\">").append(subs);

        String addForm = !canAdd ? "" : """
            <div class="card">
              <h2>Add another good</h2>
              <p class="muted">Use this form to add anything that is not in the list yet, e.g. brake pads, filters, wipers.</p>
              <form method="post" action="/inventory/add" class="grid">
                <label>Product name<input name="name" required placeholder="e.g. Brake Pads (Front)"></label>
                <label>Category<input name="category" required list="cats" placeholder="e.g. Brakes"></label>
                <datalist id="cats">%s</datalist>
                <label>Quantity<input type="number" name="quantity" min="0" value="0" required></label>
                <label>Unit price (TSh)<input type="number" name="price" min="0" step="0.01" value="0.00" required></label>
                <label>Low stock alert at<input type="number" name="low" min="0" value="5" required></label>
                <div class="form-actions"><button class="btn primary">Add product</button></div>
              </form>
            </div>
            """.formatted(datalistOptions());

        String body = """
            <div class="page-head">
              <h1>%s Inventory</h1>
              <p class="muted">Parts, quantities, stock alerts, categories and pricing.</p>
            </div>
            %s
            <div class="chips">%s</div>
            <table>
              <thead>
                <tr><th>#</th><th>Product</th><th>Category</th><th class="num">Qty</th><th class="num">Unit price (TSh)</th>%s%s</tr>
              </thead>
              <tbody>%s</tbody>
            </table>
            %s
            """.formatted(
                Icons.BOX,
                flash(q) + searchForm,
                cats,
                showValue ? "<th class=\"num col-value\">Stock value</th>" : "",
                showActions ? "<th>Actions</th>" : "",
                rows,
                addForm
            );
        sendHtml(ex, 200, page(user, "Inventory", "/inventory", body));
    }

    /** True when every word typed in the search box appears in the product name or category. */
    private static boolean matchesSearch(Product p, String search) {
        if (search.isEmpty()) return true;
        String haystack = (p.getName() + " " + p.getCategory()).toLowerCase();
        for (String word : search.toLowerCase().split("\\s+")) {
            if (!haystack.contains(word)) return false;
        }
        return true;
    }

    private String datalistOptions() {
        StringBuilder sb = new StringBuilder();
        for (String c : store.categories()) sb.append("<option value=\"").append(h(c)).append("\">");
        return sb.toString();
    }

    private void handleAdd(HttpExchange ex, User user) throws IOException {
        if (!isPost(ex)) { redirect(ex, "/inventory"); return; }
        Map<String, String> f = parseQuery(readBody(ex));
        try {
            String name = f.getOrDefault("name", "").trim();
            String cat = f.getOrDefault("category", "").trim();
            if (name.isEmpty() || cat.isEmpty()) throw new IllegalArgumentException("Name and category are required");
            Product p = store.add(
                name, cat,
                Integer.parseInt(f.getOrDefault("quantity", "0")),
                Double.parseDouble(f.getOrDefault("price", "0")),
                Integer.parseInt(f.getOrDefault("low", "5"))
            );
            redirect(ex, "/inventory?msg=" + enc("Added " + p.getName()));
        } catch (RuntimeException e) {
            redirect(ex, "/inventory?err=" + enc("Could not add product: " + e.getMessage()));
        }
    }

    private void handleAdjust(HttpExchange ex, User user) throws IOException {
        if (!isPost(ex)) { redirect(ex, "/inventory"); return; }
        Map<String, String> f = parseQuery(readBody(ex));
        try {
            int id = Integer.parseInt(f.getOrDefault("id", "0"));
            int delta = Math.abs(Integer.parseInt(f.getOrDefault("delta", "1")));
            if ("out".equals(f.get("op"))) delta = -delta;
            store.adjustStock(id, delta);
            redirect(ex, "/inventory?msg=" + enc("Stock updated"));
        } catch (RuntimeException e) {
            redirect(ex, "/inventory?err=" + enc("Could not update stock: " + e.getMessage()));
        }
    }

    private void handleEdit(HttpExchange ex, User user) throws IOException {
        if (isPost(ex)) {
            Map<String, String> f = parseQuery(readBody(ex));
            try {
                boolean ok = store.update(
                    Integer.parseInt(f.getOrDefault("id", "0")),
                    f.getOrDefault("name", "").trim(),
                    f.getOrDefault("category", "").trim(),
                    Integer.parseInt(f.getOrDefault("quantity", "0")),
                    Double.parseDouble(f.getOrDefault("price", "0")),
                    Integer.parseInt(f.getOrDefault("low", "5"))
                );
                redirect(ex, "/inventory?" + (ok ? "msg=" + enc("Product saved") : "err=" + enc("Product not found")));
            } catch (RuntimeException e) {
                redirect(ex, "/inventory?err=" + enc("Could not save: " + e.getMessage()));
            }
            return;
        }
        Map<String, String> q = parseQuery(ex.getRequestURI().getRawQuery());
        int id;
        try { id = Integer.parseInt(q.getOrDefault("id", "0")); } catch (NumberFormatException e) { id = 0; }
        Product p = store.find(id).orElse(null);
        if (p == null) { redirect(ex, "/inventory?err=" + enc("Product not found")); return; }

        String body = """
            <div class="page-head">
              <h1>%s Edit product</h1>
              <p class="muted"><a href="/inventory">&larr; Back to inventory</a></p>
            </div>
            <div class="card">
              <form method="post" action="/inventory/edit" class="grid">
                <input type="hidden" name="id" value="%d">
                <label>Product name<input name="name" required value="%s"></label>
                <label>Category<input name="category" required list="cats" value="%s"></label>
                <datalist id="cats">%s</datalist>
                <label>Quantity<input type="number" name="quantity" min="0" value="%d" required></label>
                <label>Unit price (TSh)<input type="number" name="price" min="0" step="0.01" value="%.2f" required></label>
                <label>Low stock alert at<input type="number" name="low" min="0" value="%d" required></label>
                <div class="form-actions"><button class="btn primary">Save changes</button></div>
              </form>
            </div>
            """.formatted(Icons.BOX, p.getId(), h(p.getName()), h(p.getCategory()), datalistOptions(),
                          p.getQuantity(), p.getUnitPrice(), p.getLowStockLevel());
        sendHtml(ex, 200, page(user, "Edit product", "/inventory", body));
    }

    private void handleDelete(HttpExchange ex, User user) throws IOException {
        if (!isPost(ex)) { redirect(ex, "/inventory"); return; }
        Map<String, String> f = parseQuery(readBody(ex));
        try {
            boolean ok = store.delete(Integer.parseInt(f.getOrDefault("id", "0")));
            redirect(ex, "/inventory?" + (ok ? "msg=" + enc("Product deleted") : "err=" + enc("Product not found")));
        } catch (NumberFormatException e) {
            redirect(ex, "/inventory?err=" + enc("Invalid product id"));
        }
    }

    // ---------------------------------------------------------------- sales

    private static final int SALE_ROWS = 6;
    private static final String[] PAYMENT_METHODS = {"Cash", "Mobile money", "Card", "Bank transfer"};

    /** Sellers, storekeepers (who hand the goods over) and people who see the money may open Sales. */
    private static boolean canSeeSales(User user) {
        return user.can(Permission.SELL) || user.can(Permission.ADJUST_STOCK) || user.can(Permission.VIEW_FINANCIALS);
    }

    /** Storekeepers see what to hand over, but not prices and totals. */
    private static boolean seesSalesMoney(User user) {
        return user.can(Permission.SELL) || user.can(Permission.VIEW_FINANCIALS);
    }

    /** The director (owner) and the manager are told about discounts and tax set on sales. */
    private static boolean seesNotifications(User user) {
        return user.getRole() == Role.OWNER || user.getRole() == Role.MANAGER;
    }

    /** Owner, manager and accountant set the standard tax rate that the sale form starts with. */
    private static boolean canSetTax(User user) {
        return user.can(Permission.VIEW_FINANCIALS) && user.can(Permission.EDIT_PRODUCT);
    }

    /** 16.0 shows as "16", 7.5 as "7.5". */
    private static String pct(double v) {
        return v == Math.rint(v) ? String.valueOf((long) v) : String.valueOf(v);
    }

    private void handleSales(HttpExchange ex, User user) throws IOException {
        if (!ex.getRequestURI().getPath().equals("/sales")) {
            sendText(ex, 404, "Not found");
            return;
        }
        if (!canSeeSales(user)) { sendDenied(ex, user); return; }

        Map<String, String> q = parseQuery(ex.getRequestURI().getRawQuery());
        boolean canSell = user.can(Permission.SELL);
        boolean canIssue = user.can(Permission.ADJUST_STOCK);
        boolean showMoney = seesSalesMoney(user);
        String statusFilter = q.getOrDefault("status", "");

        StringBuilder body = new StringBuilder();
        body.append("<div class=\"page-head\"><h1>").append(Icons.CART).append(" Sales / POS</h1>")
            .append("<p class=\"muted\">The shopkeeper records the sale. The storekeeper then hands the goods over, and only then does the stock go down.</p></div>")
            .append(flash(q));

        // Summary tiles
        body.append("<div class=\"stats\">");
        if (showMoney) {
            body.append(tile("Sales today", money(sales.totalOn(LocalDate.now())), false));
            body.append(tile("Number of sales today", String.valueOf(sales.countOn(LocalDate.now())), false));
        }
        body.append(tile("Waiting for storekeeper", String.valueOf(sales.pendingCount()), sales.pendingCount() > 0));
        body.append("</div>");

        // New sale form
        if (canSell) {
            String options = productOptions();
            StringBuilder rows = new StringBuilder();
            for (int i = 1; i <= SALE_ROWS; i++) {
                rows.append("<div class=\"sale-row\"><select name=\"product").append(i).append("\"")
                    .append(i == 1 ? " required" : "").append(">").append(options).append("</select>")
                    .append("<input type=\"number\" name=\"qty").append(i).append("\" min=\"1\" value=\"1\" title=\"Quantity\">")
                    .append("<input type=\"number\" name=\"disc").append(i).append("\" min=\"0\" step=\"0.01\" value=\"0\" title=\"Amount of money off each one\"></div>");
            }
            StringBuilder pay = new StringBuilder();
            for (String m : PAYMENT_METHODS) pay.append("<option>").append(m).append("</option>");
            body.append("""
                <div class="card">
                  <h2>New sale</h2>
                  <p class="muted">Choose up to %d products. Leave the rows you do not need on "Choose a product".</p>
                  <form method="post" action="/sales/new">
                    <div class="sale-rows">
                      <div class="sale-row head"><span>Product</span><span>Quantity</span><span>Discount off each (TSh)</span></div>
                      %s
                    </div>
                    <div class="grid">
                      <label>Customer name (optional)<input name="customer" placeholder="Walk-in customer" maxlength="60"></label>
                      <label>Payment method<select name="payment">%s</select></label>
                      <label>Tax percent (standard rate is %s)<input type="number" name="tax" min="0" max="100" step="0.01" value="%s"></label>
                      <div class="form-actions">
                        <button class="btn primary">Record sale</button>
                        <span class="muted tax-note">Tax is a percentage of the total after the discounts, and is added to it. The manager and the director are notified of any discount, and of tax different from the standard rate.</span>
                      </div>
                    </div>
                  </form>
                </div>
                """.formatted(SALE_ROWS, rows, pay, pct(settings.getTaxPercent()), pct(settings.getTaxPercent())));
        }

        // Sales list
        body.append("<div class=\"chips\">");
        body.append("<a class=\"chip").append(statusFilter.isEmpty() ? " active" : "").append("\" href=\"/sales\">All sales</a>");
        for (Sale.Status st : Sale.Status.values()) {
            body.append("<a class=\"chip").append(st.name().equalsIgnoreCase(statusFilter) ? " active" : "")
                .append("\" href=\"/sales?status=").append(st.name()).append("\">").append(h(st.getLabel())).append("</a>");
        }
        body.append("</div>");

        body.append("<table><thead><tr><th>#</th><th>Date</th><th>Customer</th><th>Goods</th>");
        if (showMoney) body.append("<th class=\"num\">Total</th><th>Payment</th>");
        body.append("<th>Sold by</th><th>Status</th><th>Actions</th></tr></thead><tbody>");
        int shown = 0;
        for (Sale s : sales.all()) {
            if (!statusFilter.isEmpty() && !s.getStatus().name().equalsIgnoreCase(statusFilter)) continue;
            if (++shown > 200) break;
            body.append("<tr><td>").append(s.getId()).append("</td><td>").append(h(s.getSoldAtText())).append("</td><td>")
                .append(h(s.getCustomer())).append("</td><td>");
            for (Sale.Line l : s.getLines()) {
                body.append("<div>").append(l.quantity()).append(" &times; ").append(h(l.name())).append("</div>");
            }
            body.append("</td>");
            if (showMoney) {
                body.append("<td class=\"num\">").append(money(s.total()));
                if (s.hasDiscount()) {
                    body.append("<div class=\"muted small\">discount ").append(money(s.discount()))
                        .append(" (").append(s.discountKind()).append(")</div>");
                }
                if (s.getTaxPercent() > 0) {
                    body.append("<div class=\"muted small\">incl. tax ").append(money(s.tax())).append("</div>");
                }
                body.append("</td><td>").append(h(s.getPayment())).append("</td>");
            }
            body.append("<td>").append(h(s.getSoldBy())).append("</td><td><span class=\"status ")
                .append(s.getStatus().name().toLowerCase()).append("\">").append(h(s.getStatus().getLabel())).append("</span>");
            if (s.getHandledBy() != null) {
                body.append("<div class=\"muted small\">by ").append(h(s.getHandledBy())).append(", ").append(h(s.getHandledAtText())).append("</div>");
            }
            if (!s.getNote().isEmpty()) {
                body.append("<div class=\"muted small\">Reason: ").append(h(s.getNote())).append("</div>");
            }
            body.append("</td><td class=\"actions\">");
            if (s.getStatus() == Sale.Status.ISSUED && canSell) {
                body.append("<form method=\"post\" action=\"/sales/return\" class=\"inline\"><input type=\"hidden\" name=\"id\" value=\"")
                    .append(s.getId()).append("\"><input type=\"text\" name=\"reason\" placeholder=\"Reason for return\" required maxlength=\"80\">")
                    .append("<button class=\"btn bad\">Return</button></form>");
            }
            if (s.getStatus() == Sale.Status.RETURN_PENDING && canIssue) {
                body.append("<form method=\"post\" action=\"/sales/receive\" class=\"inline\"><input type=\"hidden\" name=\"id\" value=\"")
                    .append(s.getId()).append("\"><button class=\"btn ok\">Receive goods back</button></form>");
            }
            if (s.getStatus() == Sale.Status.RETURN_PENDING && canSell) {
                body.append("<form method=\"post\" action=\"/sales/return-undo\" class=\"inline\"><input type=\"hidden\" name=\"id\" value=\"")
                    .append(s.getId()).append("\"><button class=\"btn\">Undo return</button></form>");
            }
            if (s.getStatus() == Sale.Status.PENDING && canIssue) {
                body.append("<form method=\"post\" action=\"/sales/issue\" class=\"inline\"><input type=\"hidden\" name=\"id\" value=\"")
                    .append(s.getId()).append("\"><button class=\"btn ok\">Issue goods</button></form>");
            }
            if (s.getStatus() == Sale.Status.PENDING && canSell) {
                body.append("<form method=\"post\" action=\"/sales/cancel\" class=\"inline\" onsubmit=\"return confirm('Cancel sale ")
                    .append(s.getId()).append("?')\"><input type=\"hidden\" name=\"id\" value=\"")
                    .append(s.getId()).append("\"><button class=\"btn bad\">Cancel</button></form>");
            }
            if (showMoney) body.append("<a class=\"btn\" href=\"/sales/receipt?id=").append(s.getId()).append("\">Receipt</a>");
            body.append("</td></tr>");
        }
        if (shown == 0) {
            body.append("<tr><td colspan=\"").append(showMoney ? 9 : 7).append("\" class=\"muted center\">No sales to show yet.</td></tr>");
        }
        body.append("</tbody></table>");

        // Tax rate setting
        if (canSetTax(user)) {
            body.append("""
                <div class="card">
                  <h2>Standard tax rate</h2>
                  <p class="muted">The new sale form starts with this rate. The seller can change it for a sale, and the manager and the director are then notified. Sales already recorded keep the rate they were made with.</p>
                  <form method="post" action="/sales/tax" class="grid">
                    <label>Standard tax percent<input type="number" name="tax" min="0" max="100" step="0.01" value="%s" required></label>
                    <div class="form-actions"><button class="btn primary">Save tax rate</button></div>
                  </form>
                </div>
                """.formatted(pct(settings.getTaxPercent())));
        }

        sendHtml(ex, 200, page(user, "Sales", "/sales", body.toString()));
    }

    /** Dropdown of products grouped by category. Goods that cannot be sold right now are greyed out. */
    private String productOptions() {
        Map<Integer, Integer> reserved = sales.reserved();
        StringBuilder sb = new StringBuilder("<option value=\"\">Choose a product</option>");
        String current = null;
        for (Product p : store.all()) {
            if (!p.getCategory().equals(current)) {
                if (current != null) sb.append("</optgroup>");
                current = p.getCategory();
                sb.append("<optgroup label=\"").append(h(current)).append("\">");
            }
            int available = p.getQuantity() - reserved.getOrDefault(p.getId(), 0);
            String note;
            boolean sellable = false;
            if (p.getUnitPrice() <= 0) note = "no price set";
            else if (available <= 0) note = "out of stock";
            else { note = money(p.getUnitPrice()) + " each, " + available + " available"; sellable = true; }
            sb.append("<option value=\"").append(p.getId()).append("\"").append(sellable ? "" : " disabled").append(">")
              .append(h(p.getName())).append(" (").append(note).append(")</option>");
        }
        if (current != null) sb.append("</optgroup>");
        return sb.toString();
    }

    private void handleSaleNew(HttpExchange ex, User user) throws IOException {
        if (!isPost(ex)) { redirect(ex, "/sales"); return; }
        Map<String, String> f = parseQuery(readBody(ex));
        try {
            Map<Integer, Integer> wanted = new LinkedHashMap<>();
            Map<Integer, Double> discounts = new LinkedHashMap<>();
            for (int i = 1; i <= SALE_ROWS; i++) {
                String product = f.getOrDefault("product" + i, "").trim();
                if (product.isEmpty()) continue;
                int id;
                try {
                    id = Integer.parseInt(product);
                    wanted.merge(id, Integer.parseInt(f.getOrDefault("qty" + i, "1").trim()), Integer::sum);
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("Quantities must be whole numbers");
                }
                try {
                    String d = f.getOrDefault("disc" + i, "").trim();
                    // The same product on two rows gets the bigger of the two discounts.
                    discounts.merge(id, d.isEmpty() ? 0 : Double.parseDouble(d), Math::max);
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("Discounts must be amounts of money, like 5 or 2.50");
                }
            }
            String payment = f.getOrDefault("payment", PAYMENT_METHODS[0]);
            if (!List.of(PAYMENT_METHODS).contains(payment)) payment = PAYMENT_METHODS[0];
            String customer = f.getOrDefault("customer", "");
            if (customer.length() > 60) customer = customer.substring(0, 60);
            double standardTax = settings.getTaxPercent();
            double tax;
            try {
                String t = f.getOrDefault("tax", "").trim();
                tax = t.isEmpty() ? standardTax : Double.parseDouble(t);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Tax must be a number");
            }
            Sale sale = sales.create(store, user.getUsername(), customer, payment, wanted, discounts, tax);

            // Tell the manager and the director what the seller decided.
            List<String> what = new java.util.ArrayList<>();
            if (sale.hasDiscount()) {
                List<String> items = new java.util.ArrayList<>();
                for (Sale.Line l : sale.getLines()) {
                    if (l.discount() > 0) items.add(money(l.discount()) + " off each of " + l.quantity() + " x " + l.name());
                }
                what.add("gave a discount of " + String.join(", ", items) + " (" + money(sale.discount()) + " off in total)");
            }
            if (tax != standardTax) {
                what.add("charged " + pct(tax) + " percent tax (" + money(sale.tax()) + ") instead of the standard " + pct(standardTax) + " percent");
            }
            boolean notified = !what.isEmpty();
            if (notified) {
                notifications.add(sale.getId(), user.getFullName() + " (" + user.getUsername() + ") " + String.join(" and ", what)
                    + " on sale " + sale.getId() + " to " + sale.getCustomer() + ". Total " + money(sale.total()) + ".");
            }
            redirect(ex, "/sales?msg=" + enc("Sale " + sale.getId() + " recorded, total " + money(sale.total())
                + ". It is now waiting for the storekeeper to hand over the goods."
                + (notified ? " The manager and the director have been notified of the discount or tax." : "")));
        } catch (IllegalArgumentException e) {
            redirect(ex, "/sales?err=" + enc(e.getMessage()));
        }
    }

    private void handleSaleIssue(HttpExchange ex, User user) throws IOException {
        if (!isPost(ex)) { redirect(ex, "/sales"); return; }
        Map<String, String> f = parseQuery(readBody(ex));
        try {
            Sale sale = sales.issue(parseId(f.get("id")), store, user.getUsername());
            redirect(ex, "/sales?msg=" + enc("Goods issued for sale " + sale.getId() + ". Stock has been reduced."));
        } catch (IllegalArgumentException e) {
            redirect(ex, "/sales?err=" + enc(e.getMessage()));
        }
    }

    private void handleSaleCancel(HttpExchange ex, User user) throws IOException {
        if (!isPost(ex)) { redirect(ex, "/sales"); return; }
        Map<String, String> f = parseQuery(readBody(ex));
        try {
            Sale sale = sales.cancel(parseId(f.get("id")), user.getUsername());
            redirect(ex, "/sales?msg=" + enc("Sale " + sale.getId() + " cancelled. The goods are free to sell again."));
        } catch (IllegalArgumentException e) {
            redirect(ex, "/sales?err=" + enc(e.getMessage()));
        }
    }

    private void handleSaleReturn(HttpExchange ex, User user) throws IOException {
        if (!isPost(ex)) { redirect(ex, "/sales"); return; }
        Map<String, String> f = parseQuery(readBody(ex));
        try {
            String reason = f.getOrDefault("reason", "");
            if (reason.length() > 80) reason = reason.substring(0, 80);
            Sale sale = sales.requestReturn(parseId(f.get("id")), user.getUsername(), reason);
            redirect(ex, "/sales?msg=" + enc("Return recorded for sale " + sale.getId() + ". Refund due: " + money(sale.total())
                + ". It is now waiting for the storekeeper to receive the goods back."));
        } catch (IllegalArgumentException e) {
            redirect(ex, "/sales?err=" + enc(e.getMessage()));
        }
    }

    private void handleSaleReturnUndo(HttpExchange ex, User user) throws IOException {
        if (!isPost(ex)) { redirect(ex, "/sales"); return; }
        Map<String, String> f = parseQuery(readBody(ex));
        try {
            Sale sale = sales.undoReturn(parseId(f.get("id")), user.getUsername());
            redirect(ex, "/sales?msg=" + enc("Return undone. Sale " + sale.getId() + " stands as sold."));
        } catch (IllegalArgumentException e) {
            redirect(ex, "/sales?err=" + enc(e.getMessage()));
        }
    }

    private void handleSaleReceive(HttpExchange ex, User user) throws IOException {
        if (!isPost(ex)) { redirect(ex, "/sales"); return; }
        Map<String, String> f = parseQuery(readBody(ex));
        try {
            Sale sale = sales.receiveReturn(parseId(f.get("id")), store, user.getUsername());
            redirect(ex, "/sales?msg=" + enc("Goods received back for sale " + sale.getId() + ". Stock has been increased."));
        } catch (IllegalArgumentException e) {
            redirect(ex, "/sales?err=" + enc(e.getMessage()));
        }
    }

    private void handleTax(HttpExchange ex, User user) throws IOException {
        if (!canSetTax(user)) { sendDenied(ex, user); return; }
        if (!isPost(ex)) { redirect(ex, "/sales"); return; }
        Map<String, String> f = parseQuery(readBody(ex));
        try {
            double rate;
            try {
                rate = Double.parseDouble(f.getOrDefault("tax", "").trim());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Tax rate must be a number");
            }
            settings.setTaxPercent(rate);
            redirect(ex, "/sales?msg=" + enc("Standard tax rate set to " + pct(rate) + " percent."));
        } catch (IllegalArgumentException e) {
            redirect(ex, "/sales?err=" + enc(e.getMessage()));
        }
    }

    /** Messages for the manager and the director. Opening the page marks them as read. */
    private void handleNotifications(HttpExchange ex, User user) throws IOException {
        if (!seesNotifications(user)) { sendDenied(ex, user); return; }
        StringBuilder body = new StringBuilder();
        body.append("<div class=\"page-head\"><h1>").append(Icons.BELL).append(" Notifications</h1>")
            .append("<p class=\"muted\">Discounts and tax that sellers set on sales. New ones are marked until you have opened this page.</p></div>");
        body.append("<table><thead><tr><th>Date</th><th>Message</th><th>Sale</th></tr></thead><tbody>");
        int shown = 0;
        for (NotificationStore.Notification n : notifications.all()) {
            if (++shown > 200) break;
            boolean unread = !n.isReadBy(user.getUsername());
            body.append("<tr").append(unread ? " class=\"unread\"" : "").append("><td>").append(h(n.getAtText()));
            if (unread) body.append(" <span class=\"badge\">New</span>");
            body.append("</td><td>").append(h(n.getText())).append("</td><td><a class=\"btn\" href=\"/sales/receipt?id=")
                .append(n.getSaleId()).append("\">Receipt</a></td></tr>");
        }
        if (shown == 0) body.append("<tr><td colspan=\"3\" class=\"muted center\">No notifications yet.</td></tr>");
        body.append("</tbody></table>");
        notifications.markAllRead(user.getUsername());
        sendHtml(ex, 200, page(user, "Notifications", "/notifications", body.toString()));
    }

    private static int parseId(String s) {
        try {
            return Integer.parseInt(s == null ? "" : s.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Sale not found");
        }
    }

    private void handleReceipt(HttpExchange ex, User user) throws IOException {
        if (!seesSalesMoney(user)) { sendDenied(ex, user); return; }
        Map<String, String> q = parseQuery(ex.getRequestURI().getRawQuery());
        Sale s;
        try {
            s = sales.find(parseId(q.get("id"))).orElse(null);
        } catch (IllegalArgumentException e) {
            s = null;
        }
        if (s == null) { redirect(ex, "/sales?err=" + enc("Sale not found")); return; }

        StringBuilder lines = new StringBuilder();
        for (Sale.Line l : s.getLines()) {
            lines.append("<tr><td>").append(h(l.name()))
                 .append(l.discount() > 0 ? "<div class=\"muted small\">Discount " + money(l.discount()) + " off each</div>" : "")
                 .append("</td><td class=\"num\">").append(l.quantity())
                 .append("</td><td class=\"num\">").append(money(l.unitPrice())).append("</td><td class=\"num\">")
                 .append(money(l.total())).append("</td></tr>");
        }
        // Subtotal, discount and tax rows, shown only when there is a discount or tax.
        StringBuilder extra = new StringBuilder();
        if (s.hasDiscount() || s.getTaxPercent() > 0) {
            extra.append("<tr><td colspan=\"3\" class=\"num\">Subtotal</td><td class=\"num\">").append(money(s.subtotal())).append("</td></tr>");
        }
        if (s.hasDiscount()) {
            extra.append("<tr><td colspan=\"3\" class=\"num\">Discount (").append(s.discountKind())
                 .append(")</td><td class=\"num\">&minus;").append(money(s.discount())).append("</td></tr>");
        }
        if (s.getTaxPercent() > 0) {
            extra.append("<tr><td colspan=\"3\" class=\"num\">Tax ").append(pct(s.getTaxPercent()))
                 .append(" percent</td><td class=\"num\">").append(money(s.tax())).append("</td></tr>");
        }
        String body = """
            <div class="page-head no-print">
              <h1>%s Receipt</h1>
              <p class="muted"><a href="/sales">&larr; Back to sales</a></p>
            </div>
            <div class="card receipt">
              <h2>Auto Workshop</h2>
              <p class="muted">Receipt no. %d &middot; %s</p>
              <p>Customer: <strong>%s</strong><br>Served by: %s<br>Payment: %s<br>Status: %s</p>
              <table>
                <thead><tr><th>Item</th><th class="num">Qty</th><th class="num">Price</th><th class="num">Amount</th></tr></thead>
                <tbody>%s</tbody>
                <tfoot>%s<tr><th colspan="3" class="num">%s</th><th class="num total">%s</th></tr></tfoot>
              </table>
              <button class="btn primary no-print" onclick="window.print()">Print receipt</button>
            </div>
            """.formatted(Icons.CART, s.getId(), h(s.getSoldAtText()), h(s.getCustomer()), h(s.getSoldBy()),
                          h(s.getPayment()),
                          h(s.getStatus().getLabel()) + (s.getNote().isEmpty() ? "" : " (" + h(s.getNote()) + ")"),
                          lines, extra,
                          s.getStatus() == Sale.Status.RETURNED ? "Refunded" : "Total",
                          money(s.total()));
        sendHtml(ex, 200, page(user, "Receipt", "/sales", body));
    }

    // ------------------------------------------------------- staff accounts

    private void handleStaff(HttpExchange ex, User user) throws IOException {
        if (!ex.getRequestURI().getPath().equals("/staff")) {
            sendText(ex, 404, "Not found");
            return;
        }
        Map<String, String> q = parseQuery(ex.getRequestURI().getRawQuery());

        StringBuilder rows = new StringBuilder();
        for (User u : users.all()) {
            boolean self = u.getUsername().equals(user.getUsername());
            rows.append("<tr><td><strong>").append(h(u.getUsername())).append("</strong>")
                .append(self ? " <span class=\"pill\">you</span>" : "")
                .append(u.isMustChangePassword() ? " <span class=\"pill\">must set password</span>" : "")
                .append("</td><td>").append(h(u.getFullName())).append("</td><td>");
            // Role
            rows.append("<form method=\"post\" action=\"/staff/role\" class=\"inline\">")
                .append("<input type=\"hidden\" name=\"username\" value=\"").append(h(u.getUsername())).append("\">")
                .append("<select name=\"role\">").append(roleOptions(u.getRole())).append("</select>")
                .append("<button class=\"btn\">Save role</button></form></td><td class=\"actions\">");
            // Reset password and delete (not for your own account: use My account for that)
            if (!self) {
                rows.append("<form method=\"post\" action=\"/staff/reset\" class=\"inline\">")
                    .append("<input type=\"hidden\" name=\"username\" value=\"").append(h(u.getUsername())).append("\">")
                    .append("<input type=\"text\" name=\"password\" placeholder=\"New temporary password\" required minlength=\"")
                    .append(UserStore.MIN_PASSWORD_LENGTH).append("\" autocomplete=\"off\">")
                    .append("<button class=\"btn\">Reset password</button></form>")
                    .append("<form method=\"post\" action=\"/staff/delete\" class=\"inline\" data-name=\"").append(h(u.getUsername()))
                    .append("\" onsubmit=\"return confirm('Delete the account ' + this.dataset.name + '?')\">")
                    .append("<input type=\"hidden\" name=\"username\" value=\"").append(h(u.getUsername())).append("\">")
                    .append("<button class=\"btn bad\">Delete</button></form>");
            } else {
                rows.append("<a class=\"btn\" href=\"/account\">Change my password</a>");
            }
            rows.append("</td></tr>");
        }

        StringBuilder matrix = new StringBuilder("<tr><th>Role</th>");
        for (Permission p : Permission.values()) matrix.append("<th>").append(h(p.getLabel())).append("</th>");
        matrix.append("</tr>");
        for (Role r : Role.values()) {
            matrix.append("<tr><td><strong>").append(h(r.getLabel())).append("</strong></td>");
            for (Permission p : Permission.values()) {
                matrix.append("<td class=\"center ").append(r.can(p) ? "yes" : "no").append("\">")
                      .append(r.can(p) ? "&#10003;" : "&#10007;").append("</td>");
            }
            matrix.append("</tr>");
        }

        String body = """
            <div class="page-head">
              <h1>%s Employees</h1>
              <p class="muted">Staff accounts and roles. Each member of staff signs in with their own account and sees only what their role allows.</p>
            </div>
            %s
            <table>
              <thead><tr><th>Username</th><th>Full name</th><th>Role</th><th>Actions</th></tr></thead>
              <tbody>%s</tbody>
            </table>

            <div class="card">
              <h2>Add a member of staff</h2>
              <p class="muted">Give them the username and temporary password. They will be asked to choose their own password the first time they sign in.</p>
              <form method="post" action="/staff/add" class="grid">
                <label>Username<input name="username" required pattern="[A-Za-z0-9._\\-]{3,20}" placeholder="e.g. john" autocomplete="off"></label>
                <label>Full name<input name="fullName" required placeholder="e.g. John Banda" autocomplete="off"></label>
                <label>Role<select name="role">%s</select></label>
                <label>Temporary password<input type="text" name="password" required minlength="%d" autocomplete="off"></label>
                <div class="form-actions"><button class="btn primary">Create account</button></div>
              </form>
            </div>

            <div class="card">
              <h2>What each role can do</h2>
              <div class="scroll"><table class="matrix">%s</table></div>
            </div>
            """.formatted(Icons.PEOPLE, flash(q), rows, roleOptions(Role.STAFF), UserStore.MIN_PASSWORD_LENGTH, matrix);
        sendHtml(ex, 200, page(user, "Employees", "/staff", body));
    }

    private static String roleOptions(Role selected) {
        StringBuilder sb = new StringBuilder();
        for (Role r : Role.values()) {
            sb.append("<option value=\"").append(r.name()).append("\"").append(r == selected ? " selected" : "").append(">")
              .append(h(r.getLabel())).append("</option>");
        }
        return sb.toString();
    }

    private void handleStaffAdd(HttpExchange ex, User user) throws IOException {
        if (!isPost(ex)) { redirect(ex, "/staff"); return; }
        Map<String, String> f = parseQuery(readBody(ex));
        try {
            User created = users.add(f.get("username"), f.get("fullName"), Role.parse(f.get("role")), f.get("password"), true);
            redirect(ex, "/staff?msg=" + enc("Created account " + created.getUsername() + " as " + created.getRole().getLabel()));
        } catch (IllegalArgumentException e) {
            redirect(ex, "/staff?err=" + enc(e.getMessage()));
        }
    }

    private void handleStaffRole(HttpExchange ex, User user) throws IOException {
        if (!isPost(ex)) { redirect(ex, "/staff"); return; }
        Map<String, String> f = parseQuery(readBody(ex));
        try {
            Role role = Role.parse(f.get("role"));
            users.setRole(f.get("username"), role);
            redirect(ex, "/staff?msg=" + enc("Role updated to " + role.getLabel()));
        } catch (IllegalArgumentException e) {
            redirect(ex, "/staff?err=" + enc(e.getMessage()));
        }
    }

    private void handleStaffReset(HttpExchange ex, User user) throws IOException {
        if (!isPost(ex)) { redirect(ex, "/staff"); return; }
        Map<String, String> f = parseQuery(readBody(ex));
        try {
            String target = f.getOrDefault("username", "").trim().toLowerCase();
            if (target.equals(user.getUsername())) throw new IllegalArgumentException("Use My account to change your own password");
            users.setPassword(target, f.get("password"), true);
            endSessionsOf(target);
            lockedUntil.remove(target);
            failedLogins.remove(target);
            redirect(ex, "/staff?msg=" + enc("Password reset for " + target));
        } catch (IllegalArgumentException e) {
            redirect(ex, "/staff?err=" + enc(e.getMessage()));
        }
    }

    private void handleStaffDelete(HttpExchange ex, User user) throws IOException {
        if (!isPost(ex)) { redirect(ex, "/staff"); return; }
        Map<String, String> f = parseQuery(readBody(ex));
        try {
            String target = f.getOrDefault("username", "").trim().toLowerCase();
            if (target.equals(user.getUsername())) throw new IllegalArgumentException("You cannot delete your own account");
            users.delete(target);
            endSessionsOf(target);
            redirect(ex, "/staff?msg=" + enc("Deleted account " + target));
        } catch (IllegalArgumentException e) {
            redirect(ex, "/staff?err=" + enc(e.getMessage()));
        }
    }

    // ------------------------------------------------------------- templates

    private void handleCss(HttpExchange ex) throws IOException {
        byte[] bytes = CSS.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "text/css; charset=utf-8");
        ex.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(bytes); }
    }

    /** section is the sidebar link to highlight, e.g. "/inventory". */
    private String page(User user, String title, String section, String body) {
        return """
            <!doctype html>
            <html lang="en">
            <head>
              <meta charset="utf-8">
              <meta name="viewport" content="width=device-width, initial-scale=1">
              <title>%s - Auto Workshop</title>
              <link rel="stylesheet" href="/style.css">
            </head>
            <body>
              <aside class="side">
                <a class="brand" href="/">%s Auto Workshop</a>
                <nav>%s</nav>
                <div class="userbox">
                  <div class="who"><strong>%s</strong><span>%s</span></div>
                  <a class="btn" href="/account">My account</a>
                  <form method="post" action="/logout"><button class="btn bad">Sign out</button></form>
                </div>
              </aside>
              <div class="content">
                <main>%s</main>
                <footer class="muted">Auto Workshop Management &middot; Java %s</footer>
              </div>
            </body>
            </html>
            """.formatted(h(title), Icons.WRENCH, sidebar(user, section),
                          h(user.getFullName()), h(user.getRole().getLabel()),
                          body, System.getProperty("java.version"));
    }

    /**
     * Left-hand list of pages. A link is only shown when the user's role has
     * the permission beside it. Links with "#" are modules that are not built yet.
     */
    private String sidebar(User user, String section) {
        Object[][] links = {
            {Icons.GRID,      "Dashboard",   "/",          null},
            {Icons.BOX,       "Inventory",   "/inventory", Permission.VIEW_INVENTORY},
            {Icons.CART,      "Sales / POS", "/sales",     null},
            {Icons.BELL,      "Notifications", "/notifications", null},
            {Icons.TRUCK,     "Purchasing",  "#",          Permission.ADD_PRODUCT},
            {Icons.PEOPLE,    "Employees",   "/staff",     Permission.MANAGE_STAFF},
            {Icons.WALLET,    "Finance",     "#",          Permission.VIEW_FINANCIALS},
            {Icons.CHART,     "Reports",     "#",          Permission.VIEW_FINANCIALS},
            {Icons.WAREHOUSE, "Warehouse",   "#",          Permission.ADJUST_STOCK},
            {Icons.SHIELD,    "Security",    "#",          Permission.MANAGE_STAFF},
        };
        StringBuilder sb = new StringBuilder();
        for (Object[] l : links) {
            Permission needed = (Permission) l[3];
            if (needed != null && !user.can(needed)) continue;
            String href = (String) l[2];
            if (href.equals("/sales") && !canSeeSales(user)) continue;
            if (href.equals("/notifications") && !seesNotifications(user)) continue;
            if (!href.equals("#")) {
                long unread = href.equals("/notifications") && !href.equals(section) ? notifications.unreadCount(user.getUsername()) : 0;
                sb.append("<a class=\"nav-link").append(href.equals(section) ? " active" : "").append("\" href=\"").append(href).append("\">")
                  .append(l[0]).append("<span>").append(l[1]).append("</span>")
                  .append(unread > 0 ? "<span class=\"badge\">" + unread + "</span>" : "").append("</a>");
            } else {
                sb.append("<span class=\"nav-link soon\" title=\"Coming soon\">")
                  .append(l[0]).append("<span>").append(l[1]).append("</span></span>");
            }
        }
        return sb.toString();
    }

    /** Green message for ?msg=..., red message for ?err=... */
    private static String flash(Map<String, String> q) {
        String msg = q.getOrDefault("msg", "");
        String err = q.getOrDefault("err", "");
        StringBuilder sb = new StringBuilder();
        if (!msg.isEmpty()) sb.append("<div class=\"flash\">").append(h(msg)).append("</div>");
        if (!err.isEmpty()) sb.append("<div class=\"flash error\">").append(h(err)).append("</div>");
        return sb.toString();
    }

    private static final String CSS = """
        :root { --bg:#0b0c0f; --panel:#15171c; --line:#2a2d35; --text:#e8eaf0; --muted:#9aa0ad; --accent:#4f8cff; --ok:#2fbf71; --bad:#e5484d; --warn:#f5a524; }
        * { box-sizing:border-box; }
        body { margin:0; font-family:system-ui,Segoe UI,Roboto,sans-serif; background:var(--bg); color:var(--text); }
        a { color:var(--accent); text-decoration:none; }
        body { display:flex; min-height:100vh; }
        aside.side { width:230px; flex-shrink:0; background:var(--panel); border-right:1px solid var(--line); padding:18px 12px; position:sticky; top:0; height:100vh; overflow-y:auto; display:flex; flex-direction:column; }
        aside.side .brand { font-weight:700; color:var(--text); display:flex; align-items:center; gap:8px; padding:4px 10px 16px; margin-bottom:10px; border-bottom:1px solid var(--line); }
        aside.side nav { display:flex; flex-direction:column; gap:2px; flex:1; }
        .nav-link { display:flex; align-items:center; gap:12px; padding:10px; border-radius:8px; color:var(--muted); font-size:15px; }
        .nav-link svg.icon { width:19px; height:19px; color:currentColor; }
        a.nav-link:hover { background:#1d2027; color:var(--text); }
        .nav-link.active { background:rgba(79,140,255,.14); color:var(--text); }
        .nav-link.active svg.icon { color:var(--accent); }
        .nav-link.soon { opacity:.45; cursor:not-allowed; }
        .userbox { border-top:1px solid var(--line); padding:14px 6px 0; margin-top:12px; display:flex; flex-direction:column; gap:8px; }
        .userbox .who { display:flex; flex-direction:column; gap:2px; font-size:14px; }
        .userbox .who span { color:var(--muted); font-size:13px; }
        .userbox .btn { width:100%; text-align:center; }
        .content { flex:1; min-width:0; display:flex; flex-direction:column; }
        .content main { flex:1; width:100%; }
        @media (max-width:760px) {
          body { flex-direction:column; }
          aside.side { width:100%; height:auto; position:static; border-right:none; border-bottom:1px solid var(--line); padding:10px 12px; }
          aside.side .brand { border-bottom:none; padding-bottom:6px; margin-bottom:4px; }
          aside.side nav { flex-direction:row; overflow-x:auto; }
          .nav-link { white-space:nowrap; padding:8px 10px; }
          .userbox { flex-direction:row; align-items:center; flex-wrap:wrap; padding-top:10px; }
          .userbox .who { flex:1; }
          .userbox .btn { width:auto; }
        }
        main { max-width:1000px; margin:0 auto; padding:24px 16px 60px; }
        .page-head h1 { display:flex; align-items:center; gap:10px; margin:0 0 4px; font-size:22px; }
        .page-head p { margin:0 0 20px; }
        .muted { color:var(--muted); }
        .center { text-align:center; }
        svg.icon { width:22px; height:22px; stroke:currentColor; fill:none; stroke-width:1.8; stroke-linecap:round; stroke-linejoin:round; color:var(--accent); }
        .stats { display:grid; grid-template-columns:repeat(auto-fit,minmax(170px,1fr)); gap:12px; margin-bottom:24px; }
        .stat { background:var(--panel); border:1px solid var(--line); border-radius:10px; padding:14px 16px; display:flex; flex-direction:column; gap:4px; }
        .stat .label { color:var(--muted); font-size:13px; }
        .stat .value { font-size:24px; font-weight:700; }
        .stat.warn .value { color:var(--warn); }
        table { width:100%; border-collapse:collapse; background:var(--panel); border:1px solid var(--line); border-radius:10px; overflow:hidden; margin-bottom:24px; }
        th, td { padding:10px 12px; text-align:left; border-bottom:1px solid var(--line); vertical-align:middle; }
        th { color:var(--muted); font-weight:600; font-size:13px; }
        tr:last-child td { border-bottom:none; }
        td.num, th.num { text-align:right; font-variant-numeric:tabular-nums; }
        tr.low td { background:rgba(245,165,36,.06); }
        .badge { background:var(--warn); color:#000; font-size:11px; font-weight:700; border-radius:4px; padding:2px 6px; margin-left:6px; }
        .pill { border:1px solid var(--line); border-radius:999px; padding:2px 10px; font-size:13px; font-weight:400; }
        .chips { display:flex; gap:8px; flex-wrap:wrap; margin-bottom:12px; }
        .chip { border:1px solid var(--line); border-radius:999px; padding:4px 12px; color:var(--muted); font-size:13px; }
        .chip.active, .chip:hover { color:var(--text); border-color:var(--accent); }
        .actions { white-space:nowrap; }
        .inline { display:inline-flex; gap:4px; align-items:center; margin-right:6px; }
        .btn { display:inline-block; border:1px solid var(--line); background:#1d2027; color:var(--text); border-radius:6px; padding:5px 10px; font-size:13px; cursor:pointer; }
        .btn:hover { border-color:var(--accent); }
        .btn.primary { background:var(--accent); border-color:var(--accent); color:#fff; padding:9px 16px; font-size:14px; }
        .btn.ok { color:var(--ok); } .btn.bad { color:var(--bad); }
        input, select { background:#0f1115; color:var(--text); border:1px solid var(--line); border-radius:6px; padding:7px 9px; font-size:14px; width:100%; }
        input:focus, select:focus { outline:none; border-color:var(--accent); }
        .inline input, .inline select { width:auto; }
        input.tiny { width:56px; }
        .search { display:flex; gap:8px; align-items:center; margin-bottom:14px; }
        .search input { flex:1; padding:10px 12px; font-size:15px; }
        .search .btn { padding:9px 16px; font-size:14px; }
        .sale-rows { display:flex; flex-direction:column; gap:8px; margin-top:14px; }
        .sale-row { display:grid; grid-template-columns:1fr 110px 190px; gap:8px; }
        .sale-row.head { color:var(--muted); font-size:13px; }
        .status { border-radius:999px; padding:2px 10px; font-size:13px; white-space:nowrap; border:1px solid var(--line); }
        .status.pending { color:var(--warn); border-color:var(--warn); }
        .status.issued { color:var(--ok); border-color:var(--ok); }
        .status.cancelled { color:var(--muted); }
        .status.return_pending { color:var(--warn); border-color:var(--warn); white-space:normal; }
        .status.returned { color:var(--bad); border-color:var(--bad); }
        .tax-note { margin-left:12px; font-size:13px; }
        tr.unread td { background:rgba(245,165,36,.08); }
        .small { font-size:12px; margin-top:4px; }
        .receipt { max-width:560px; }
        .receipt table { margin:14px 0; }
        .receipt .total { font-size:18px; color:var(--text); }
        @media print {
          aside.side, footer, .no-print { display:none !important; }
          body, .card, table { background:#fff !important; color:#000 !important; }
          .card, table { border-color:#999 !important; }
          th, td, .muted, .receipt .total { color:#000 !important; border-color:#999 !important; }
        }
        .card { background:var(--panel); border:1px solid var(--line); border-radius:12px; padding:20px; margin-bottom:24px; }
        .card h2 { margin:0 0 4px; font-size:18px; }
        .grid { display:grid; grid-template-columns:repeat(auto-fit,minmax(200px,1fr)); gap:14px; margin-top:16px; }
        .grid label, .login-card label { display:flex; flex-direction:column; gap:6px; font-size:13px; color:var(--muted); }
        .form-actions { grid-column:1 / -1; }
        .flash { background:rgba(79,140,255,.12); border:1px solid var(--accent); border-radius:8px; padding:10px 14px; margin-bottom:16px; }
        .flash.error { background:rgba(229,72,77,.12); border-color:var(--bad); }
        .perms { list-style:none; padding:0; margin:14px 0 0; display:grid; gap:6px; }
        .yes { color:var(--ok); } .no { color:var(--muted); }
        .scroll { overflow-x:auto; margin-top:14px; }
        table.matrix { margin-bottom:0; font-size:13px; }
        table.matrix th { font-weight:500; }
        body.auth { align-items:center; justify-content:center; padding:16px; }
        .login-card { width:100%; max-width:380px; background:var(--panel); border:1px solid var(--line); border-radius:14px; padding:28px; }
        .login-card .brand { display:flex; align-items:center; gap:8px; font-weight:700; margin-bottom:18px; }
        .login-card h1 { margin:0 0 4px; font-size:22px; }
        .login-card p { margin:0 0 18px; font-size:14px; }
        .login-card form { display:flex; flex-direction:column; gap:14px; }
        .login-card input { padding:10px 12px; font-size:15px; }
        .login-card .hint { margin:18px 0 0; padding-top:14px; border-top:1px solid var(--line); color:var(--muted); font-size:13px; }
        .login-card .hint strong { color:var(--text); }
        footer { text-align:center; padding:20px; font-size:13px; border-top:1px solid var(--line); }
        @media (max-width:700px) { .actions { white-space:normal; } .col-value { display:none; } }
        """;

    // --------------------------------------------------------------- helpers

    private static boolean isPost(HttpExchange ex) {
        return "POST".equals(ex.getRequestMethod());
    }

    private static String money(double v) {
        // Tanzanian shillings. Cents are shown only when there are any (tax can produce them).
        return "TSh " + String.format(v == Math.rint(v) ? "%,.0f" : "%,.2f", v);
    }

    /** Escape text for safe HTML output. */
    private static String h(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;");
    }

    private static String enc(String s) {
        return URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8);
    }

    private static Map<String, String> parseQuery(String raw) {
        Map<String, String> m = new HashMap<>();
        if (raw == null || raw.isEmpty()) return m;
        for (String pair : raw.split("&")) {
            int i = pair.indexOf('=');
            String k = i < 0 ? pair : pair.substring(0, i);
            String v = i < 0 ? "" : pair.substring(i + 1);
            try {
                m.put(URLDecoder.decode(k, StandardCharsets.UTF_8), URLDecoder.decode(v, StandardCharsets.UTF_8));
            } catch (IllegalArgumentException ignored) {
                // badly encoded pair: skip it
            }
        }
        return m;
    }

    private static String readBody(HttpExchange ex) throws IOException {
        return new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    }

    private static void sendHtml(HttpExchange ex, int status, String html) throws IOException {
        byte[] bytes = html.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(bytes); }
    }

    private static void sendText(HttpExchange ex, int status, String text) throws IOException {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(bytes); }
    }

    private static void redirect(HttpExchange ex, String location) throws IOException {
        ex.getResponseHeaders().set("Location", location);
        ex.sendResponseHeaders(303, -1);
        ex.close();
    }

    /** Small inline SVG icons matching the dashboard design. */
    static final class Icons {
        private static String svg(String paths) {
            return "<svg class=\"icon\" viewBox=\"0 0 24 24\">" + paths + "</svg>";
        }
        static final String GRID      = svg("<rect x='3' y='3' width='7' height='7'/><rect x='14' y='3' width='7' height='7'/><rect x='3' y='14' width='7' height='7'/><rect x='14' y='14' width='7' height='7'/>");
        static final String BOX       = svg("<path d='M21 16V8a2 2 0 0 0-1-1.73l-7-4a2 2 0 0 0-2 0l-7 4A2 2 0 0 0 3 8v8a2 2 0 0 0 1 1.73l7 4a2 2 0 0 0 2 0l7-4A2 2 0 0 0 21 16z'/><path d='M3.3 7 12 12l8.7-5'/><path d='M12 22V12'/>");
        static final String BELL      = svg("<path d='M18 8a6 6 0 0 0-12 0c0 7-3 9-3 9h18s-3-2-3-9'/><path d='M13.7 21a2 2 0 0 1-3.4 0'/>");
        static final String CART      = svg("<circle cx='9' cy='21' r='1'/><circle cx='20' cy='21' r='1'/><path d='M1 1h4l2.7 13.4a2 2 0 0 0 2 1.6h9.7a2 2 0 0 0 2-1.6L23 6H6'/>");
        static final String TRUCK     = svg("<rect x='1' y='3' width='15' height='13'/><path d='M16 8h4l3 3v5h-7V8z'/><circle cx='5.5' cy='18.5' r='2.5'/><circle cx='18.5' cy='18.5' r='2.5'/>");
        static final String PEOPLE    = svg("<path d='M17 21v-2a4 4 0 0 0-4-4H5a4 4 0 0 0-4 4v2'/><circle cx='9' cy='7' r='4'/><path d='M23 21v-2a4 4 0 0 0-3-3.87'/><path d='M16 3.13a4 4 0 0 1 0 7.75'/>");
        static final String WALLET    = svg("<path d='M20 7H4a2 2 0 0 0-2 2v10a2 2 0 0 0 2 2h16a2 2 0 0 0 2-2V9a2 2 0 0 0-2-2z'/><path d='M16 3H8a2 2 0 0 0-2 2v2h12V5a2 2 0 0 0-2-2z'/><circle cx='17' cy='14' r='1'/>");
        static final String CHART     = svg("<path d='M18 20V10'/><path d='M12 20V4'/><path d='M6 20v-6'/>");
        static final String WAREHOUSE = svg("<path d='M3 21V8l9-5 9 5v13'/><path d='M3 21h18'/><rect x='7' y='13' width='10' height='8'/><path d='M7 17h10'/>");
        static final String SHIELD    = svg("<path d='M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z'/><path d='m9 12 2 2 4-4'/>");
        static final String WRENCH    = svg("<path d='M14.7 6.3a1 1 0 0 0 0 1.4l1.6 1.6a1 1 0 0 0 1.4 0l3.77-3.77a6 6 0 0 1-7.94 7.94l-6.91 6.91a2.12 2.12 0 0 1-3-3l6.91-6.91a6 6 0 0 1 7.94-7.94l-3.76 3.76z'/>");
    }
}
