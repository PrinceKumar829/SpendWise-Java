# SpendWise – Smart Expense & Split Tracker (Java backend)

This is the same SpendWise project with the **Node.js / Express backend rewritten in Java**.
Nothing else was changed: the frontend (HTML, CSS and browser JavaScript), the MySQL schema, the sample data,
the REST API (same URLs, same JSON, same messages) and every SQL query are exactly the same as before.

Stack: HTML, CSS, vanilla JavaScript, Chart.js (browser) · **Java 17+** · MySQL 8 (JDBC).
There is no framework: the Java code uses only the JDK (built-in `HttpServer`, `java.sql`) plus the MySQL driver.

> The JavaScript files in `frontend/js` still have to be JavaScript, because browsers only run JavaScript.
> Only the server side (Node.js code) was converted to Java.

## Setup
1. Install **JDK 17 or newer** (`javac -version` must work) and **MySQL 8.0.16+** (older MySQL ignores CHECK constraints).
2. Create the database: `mysql -u root -p < backend/database.sql`
   (If you ran an earlier version of this file, run `DROP DATABASE spendwise;` first. The foreign keys changed.)
3. Optional demo data: `mysql -u root -p < backend/sample_data.sql`
4. Copy `.env.example` to `.env` and put in your MySQL password. (`DB_PORT` is optional, default 3306.)
5. Start the server, using **either** way:
   - **Maven:** `mvn compile exec:java`
   - **No Maven:** download `mysql-connector-j-8.4.0.jar` into the `lib/` folder (see `lib/README.txt`),
     then run `./run.sh` (macOS/Linux) or `run.bat` (Windows).
6. Open http://localhost:3000 and check the connection at http://localhost:3000/api/health
7. `./run-tests.sh` (or `run-tests.bat`) runs all tests. They need no database.

The Chart.js and Lucide scripts load from a CDN, so you need internet access when using the app.

## Where the old JavaScript files went
| Node.js version | Java version (`backend/src/main/java/spendwise/`) |
|---|---|
| `server.js` | `Server.java` (starts the server, health check) |
| Express itself | `util/Router.java` (routes, JSON errors, serves the `frontend` folder) |
| `config/db.js` (mysql2 pool) | `config/Db.java` (JDBC helpers, transactions) and `config/Env.java` (.env reader) |
| `routes/*.js` | `routes/ApiRoutes.java` |
| `controllers/*.js` | `controllers/*.java` |
| `services/*.js` | `services/*.java` (`SplitService`, `GroupService`, `InsightService`, `ReportService`) |
| `models/*.js` | `models/*.java` (SQL only) |
| `services/http.js` | `util/HttpError.java` |
| pdfkit | a small built-in PDF writer inside `ReportService.java` |
| `*.test.js` | `backend/src/test/java/spendwise/*Test.java` |

## Pages
| Page | What it does |
|---|---|
| dashboard.html | Totals, budget progress, 4 charts, recent transactions |
| expenses.html / income.html | Add, edit, delete, search, filter by category and date |
| groups.html | Groups, equal/custom splits, balances, who owes whom, settlements |
| budgets.html | Monthly and per-category budgets, 80% warning, exceeded alert |
| goals.html | Savings goals and contributions |
| insights.html | Health score, Spending Detective, savings tips, no-spend challenge, PDF download |
| calendar.html | Month calendar, click a date to see its transactions |

## API summary (unchanged)
- `/api/expenses`, `/api/income`: GET (search, type, from, to), POST, PUT /:id, DELETE /:id; also `/monthly`
- `/api/dashboard`: GET
- `/api/groups`: GET, POST; `/:id` GET, DELETE; `/:id/members`, `/:id/expenses`, `/:id/settlements` POST
- `/api/budgets?month=YYYY-MM`: GET; POST (create or update); DELETE /:id
- `/api/goals`: GET, POST; `/:id/contributions` POST; DELETE /:id
- `/api/insights`: GET; `/api/challenges`: GET, POST, DELETE /:id
- `/api/calendar?month=YYYY-MM`: GET; `/api/reports/monthly?month=YYYY-MM`: PDF download

## Project design (useful for the viva)
- **Layers:** routes (URLs) → controllers (validation, JSON) → services (business rules, transactions) → models (SQL only).
- **Money in splits** is handled in whole paise (`long`) to avoid floating-point errors. See `SplitService.java`.
- **Transactions** (`Db.tx`) are used when creating a group, saving a shared expense and recording a settlement.
- **Duplicate settlements** are blocked three ways: a row lock (`SELECT ... FOR UPDATE`), a check against the balances, and a UNIQUE request token.
- **SQL used:** JOIN, LEFT JOIN, GROUP BY, SUM/COUNT, GROUP_CONCAT, UNION ALL, subqueries, ON DUPLICATE KEY UPDATE, FOR UPDATE.
- **Prepared statements:** every query uses `?` placeholders, so user input cannot change the SQL.
- **Health score (0–100):** budget usage 40 + savings progress 30 + spending pattern 30. The Insights page shows the working.
- **Unusual increase rule:** a category up 30% or more and at least Rs 200 versus last month (or new and at least Rs 500).
- **No-spend challenge:** spending in Shopping, Entertainment or Others during the challenge counts as unnecessary and fails it.
- **localStorage** stores only the theme choice. All data is in MySQL.

## Manual test checklist
1. `/api/health` shows `connected`.
2. Add, edit and delete an expense and an income; try an empty amount and a negative amount (should be rejected).
3. Group of 3, ₹1,500 dinner paid by one person: the other two should each owe ₹500. Settle once, then try the same settlement again.
4. Custom split with amounts that do not add up: should be rejected with a message.
5. Budget ₹1,000 for this month, then add expenses to pass 80% and 100%: warning and alert appear on Budgets and the dashboard.
6. Goal with a contribution larger than the remaining amount: should be rejected.
7. Insights page loads on an empty database without errors, then with sample data.
8. Start a 7-day challenge, add a Shopping expense, reload Insights: status becomes failed.
9. Download a PDF for the current month and open it.
10. Toggle dark mode on the dashboard and resize to phone width.

## Known limitations
- The app has no user accounts, so everyone using one database shares the same data.
- The PDF uses "Rs." because the built-in PDF fonts cannot print the rupee sign.
- The Settle button asks for an amount (pre-filled with the full debt), so partial payments work.
- The server opens one database connection per query (simple for a college project); a connection pool would be the next step for heavy use.
