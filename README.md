# Auto Workshop Website (Java)

A business website for an auto workshop, written in plain Java with no
external libraries. Staff sign in, and each person sees and can do only what
their role allows.

## Run it

Requires Java 21 or newer.

* Windows: double-click `run.bat` (or run it from a terminal)
* Mac / Linux: `./run.sh`

Then open <http://localhost:8080> in your browser. Keep the terminal window
open: closing it stops the website.

Manual commands if you prefer:

```
javac -d out src/com/autoshop/*.java
java -cp out com.autoshop.AutoShopServer
```

## First sign in

On the very first run one account is created:

    username: owner
    password: admin123

You are made to choose your own password straight away. After that, open
**Employees** in the left list to create an account for each member of staff.

## Roles

| Role | Can do |
|------|--------|
| Owner / Director | Everything, including staff accounts |
| Manager | Everything in inventory, and sees stock value. No staff accounts |
| Storekeeper | View inventory, stock in / out, add goods |
| Cashier / Sales | View and search inventory only |
| Mechanic | View and search inventory only |

To change what a role can do, edit `Role.java`. The Employees page shows the
full table of roles and permissions.

## Pages

| Page | What it is |
|------|------------|
| `/login` | Staff sign in |
| `/` | Dashboard with stock totals |
| `/inventory` | Goods list with search, category filter, stock in / out, add, edit, delete |
| `/staff` | Employees: create accounts, change roles, reset passwords (owner only) |
| `/account` | Change your own password and see what your role can do |

## Data

Everything is saved in the `data` folder:

* `inventory.csv` - the goods. Delete it to go back to the starting list.
* `users.csv` - staff accounts. Passwords are stored as salted hashes, never
  as plain text. Delete it to go back to the single `owner / admin123` account
  (do this if the owner password is ever forgotten).

## Project layout

```
src/com/autoshop/AutoShopServer.java  - web server, pages, login, styling
src/com/autoshop/Role.java            - roles and what each can do
src/com/autoshop/Permission.java      - the list of permissions
src/com/autoshop/User.java            - one staff account
src/com/autoshop/UserStore.java       - saves accounts, checks passwords
src/com/autoshop/Product.java         - one stock item
src/com/autoshop/InventoryStore.java  - saves goods, starting list
```

## Adding the other modules

Sales, Purchasing, Finance and the rest are placeholders in the left list.
To build one: add a `createContext("/sales", app.guard(PERMISSION, app::handleSales))`
line in `AutoShopServer.main`, write the handler method, and change the `"#"`
link for it in `sidebar()` to the new path.
