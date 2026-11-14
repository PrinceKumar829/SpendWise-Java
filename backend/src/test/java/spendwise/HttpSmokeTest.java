package spendwise;

import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import spendwise.config.Db;
import spendwise.util.Json;
import spendwise.util.Router;

/** Starts the real server on a free port and calls it over HTTP (a fake database stands in for MySQL). */
public class HttpSmokeTest {
    static int port;
    static String type;
    static byte[] raw;

    static int call(String method, String path, String body) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL("http://127.0.0.1:" + port + path).openConnection();
        c.setRequestMethod(method);
        if (body != null) {
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            try (OutputStream os = c.getOutputStream()) { os.write(body.getBytes(StandardCharsets.UTF_8)); }
        }
        int status = c.getResponseCode();
        try (InputStream in = status >= 400 ? c.getErrorStream() : c.getInputStream()) { raw = in == null ? new byte[0] : in.readAllBytes(); }
        type = c.getContentType();
        return status;
    }

    static String text() { return new String(raw, StandardCharsets.UTF_8); }

    public static void main(String[] args) throws Exception {
        HttpServer http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        Router app = Server.buildApp();
        http.createContext("/", app::handle);
        http.start();
        port = http.getAddress().getPort();
        try {
            Db.testBackend = new Db.Backend() {
                public List<Map<String, Object>> query(String sql, Object[] p) {
                    if (sql.startsWith("SELECT * FROM expenses"))
                        return List.of(Json.obj("id", 2L, "amount", 800.0, "category", "Shopping", "description", "T-shirts", "expense_date", "2026-10-20"),
                            Json.obj("id", 1L, "amount", 150.0, "category", "Food", "description", "Test Lunch", "expense_date", "2026-11-03"));
                    if (sql.contains("SUM(amount),0) FROM income") && sql.contains("AS expenses")) return List.of(Json.obj("income", 18000.0, "expenses", 2850.0));
                    if (sql.contains("GROUP BY category")) return List.of(Json.obj("category", "Food", "total", 150.0, "entries", 1L));
                    if (sql.contains("savings_contributions")) return List.of(Json.obj("saved", 0.0));
                    if (sql.contains("FROM budgets WHERE")) return List.of(Json.obj("amount", 30000.0));
                    if (sql.contains("FROM expenses e WHERE")) return List.of();
                    return List.of(Json.obj("ok", 1L));
                }
                public long update(String sql, Object[] p) { return 7; }
            };

            // normal API calls
            Check.eq(200, call("GET", "/api/health", null), "health with database");
            Check.ok(text().contains("\"database\":\"connected\""), "health body");
            Check.eq(200, call("GET", "/api/expenses?search=&type=&from=&to=", null), "list expenses");
            Check.ok(text().contains("\"total\":950") && text().contains("\"count\":2"), "expense total and count: " + text());
            Check.eq(201, call("POST", "/api/expenses", "{\"amount\":\"150\",\"type\":\"Food\",\"description\":\"Lunch\",\"date\":\"2026-11-03\"}"), "create expense");
            Check.ok(text().contains("\"id\":7") && text().contains("Saved successfully."), "create body");
            Check.eq(200, call("PUT", "/api/expenses/7", "{\"amount\":200,\"type\":\"Food\",\"description\":\"Lunch\",\"date\":\"2026-11-03\"}"), "update expense");
            Check.eq(200, call("DELETE", "/api/expenses/7", null), "delete expense");
            Check.eq(200, call("GET", "/api/budgets?month=2026-11", null), "budgets list");

            // validation and error messages (same texts as the Node version)
            Check.eq(400, call("POST", "/api/expenses", "{\"amount\":-5,\"type\":\"Food\",\"description\":\"x\",\"date\":\"2026-11-03\"}"), "negative amount");
            Check.ok(text().contains("Amount must be a positive number."), "negative amount message");
            Check.eq(400, call("POST", "/api/expenses", "{\"amount\":5,\"type\":\"Cars\",\"description\":\"x\",\"date\":\"2026-11-03\"}"), "bad category");
            Check.ok(text().contains("Type must be one of: Food, Shopping, Transportation, Education, Entertainment, Health, Bills, Others."), "category message");
            Check.eq(400, call("POST", "/api/income", "{\"amount\":5,\"type\":\"Salary\",\"date\":\"2026-02-30\"}"), "bad date");
            Check.ok(text().contains("Please choose a valid date."), "date message");
            Check.eq(400, call("POST", "/api/expenses", "{bad json"), "bad JSON");
            Check.eq(400, call("GET", "/api/budgets?month=bad", null), "bad month");
            Check.ok(text().contains("Month must look like 2026-11."), "month message");
            Check.eq(400, call("POST", "/api/budgets", "{\"month\":\"2026-11\",\"category\":\"Cars\",\"amount\":5}"), "bad budget category");
            Check.eq(400, call("POST", "/api/goals", "{\"name\":\"\",\"target_amount\":5}"), "goal name required");
            Check.eq(400, call("POST", "/api/challenges", "{\"type\":\"9-day\"}"), "bad challenge");
            Check.eq(404, call("GET", "/api/nothing", null), "unknown API route");
            Check.ok(text().contains("API route not found"), "404 message");

            // PDF report
            Check.eq(200, call("GET", "/api/reports/monthly?month=2026-11", null), "pdf status");
            Check.ok(type.startsWith("application/pdf") && text().startsWith("%PDF-1.4"), "pdf content type and header");
            Files.write(Path.of("/tmp/spendwise_test.pdf"), raw);

            // frontend files are served as static files
            Check.eq(200, call("GET", "/dashboard.html", null), "dashboard.html");
            Check.ok(type.startsWith("text/html") && text().contains("SpendWise"), "html served");
            Check.eq(200, call("GET", "/js/common.js", null), "common.js");
            Check.ok(type.startsWith("application/javascript"), "js type");
            Check.eq(200, call("GET", "/", null), "index");
            Check.eq(404, call("GET", "/..%2f..%2fetc%2fpasswd", null), "path traversal blocked");

            // database failure -> JSON 500 (no fake database, and no MySQL driver on this classpath)
            Db.testBackend = null;
            PrintStream err = System.err;
            System.setErr(new PrintStream(new ByteArrayOutputStream()));   // hide the expected stack trace
            int status = call("GET", "/api/health", null);
            System.setErr(err);
            Check.eq(500, status, "database error becomes 500");
            Check.ok(text().contains("\"message\":\"Server error\"") && text().contains("\"detail\""), "500 body: " + text());
        } finally {
            http.stop(0);
        }
        Check.done("HttpSmokeTest");
    }
}
