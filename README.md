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
| Accountant | View inventory and stock value, edit goods and prices |
| Shopkeeper | Records sales, prints receipts, can view and search inventory |
| Storekeeper | Issues goods for recorded sales, stock in / out, view inventory |
| Staff | View and search inventory, stock in / out |

To change what a role can do, edit `Role.java`. The Employees page shows the
full table of roles and permissions.

## Pages

| Page | What it is |
|------|------------|
| `/login` | Staff sign in |
| `/` | Dashboard with stock totals |
| `/inventory` | Goods list with search, category filter, stock in / out, add, edit, delete |
| `/sales` | Sales / POS: record sales with discount and tax, issue goods, returns, receipts |
| `/notifications` | Discounts and tax set on sales (owner and manager only) |
| `/staff` | Employees: create accounts, change roles, reset passwords (owner only) |
| `/account` | Change your own password and see what your role can do |

## How a sale works

1. The **shopkeeper** opens Sales / POS, picks the goods and records the sale.
   The goods are reserved so they cannot be sold twice. Stock does not change yet.
2. The sale shows as *Waiting for storekeeper*.
3. The **storekeeper** clicks *Issue goods* when handing them over. Only then
   does the stock go down.

A waiting sale can be cancelled, which frees the goods. Goods without a price
cannot be sold: a manager sets the price on the Inventory page first.

### Discounts and tax

The shopkeeper sets both on the new sale form.

* **Discount** is per item and by amount: beside each product the shopkeeper
  types the amount of money to take off each unit. Two tyres with 5.00 off
  each gives 10.00 off. It cannot be more than the product's price.
* **Tax** is a percentage of the total after the discounts, added on top. The form starts
  with the standard rate, which the owner, manager or accountant sets at the
  bottom of the Sales page (it starts at 0). The shopkeeper can change it for
  a sale. Each sale keeps the rate it was made with.

Total = subtotal - discounts + tax, where tax = (subtotal - discounts) x tax percent.

All money is in Tanzanian shillings (TSh). The starting goods come with example
prices for one tyre, battery or bottle, and an example quantity in stock;
change them on the Inventory page.

### Notifications

When a sale is recorded with a discount, or with tax different from the
standard rate, a message is saved for the **manager** and the **owner /
director**. They see a count beside *Notifications* in the left list and on
the dashboard. Opening the Notifications page marks the messages as read for
that person only. Notifications appear inside the site; no email or SMS is sent.

### Returns and refunds

1. The **shopkeeper** clicks *Return* on an issued sale and gives a reason.
   The amount to refund is the sale total.
2. The sale shows as *Return waiting for storekeeper*.
3. The **storekeeper** clicks *Receive goods back*. Only then does the stock
   go up, and the sale stops counting in the sales totals.

A return recorded by mistake can be undone before the goods are received.
Returns are for the whole sale; returning only some of the items is not built.

## Data

Everything is saved in the `data` folder:

* `inventory.csv` - the goods. Delete it to go back to the starting list.
* `sales.tsv` - every sale, with its goods, total and status.
* `settings.properties` - the standard tax rate.
* `notifications.tsv` - messages for the manager and owner, and who has read them.
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
src/com/autoshop/Sale.java            - one sale and its goods
src/com/autoshop/SalesStore.java      - saves sales, reserves and issues stock
src/com/autoshop/Settings.java        - shop settings (standard tax rate)
src/com/autoshop/NotificationStore.java - messages for the manager and owner
src/com/autoshop/Product.java         - one stock item
src/com/autoshop/InventoryStore.java  - saves goods, starting list
```

## Adding the other modules

Purchasing, Finance and the rest are placeholders in the left list.
To build one: add a `createContext("/sales", app.guard(PERMISSION, app::handleSales))`
line in `AutoShopServer.main`, write the handler method, and change the `"#"`
link for it in `sidebar()` to the new path.
