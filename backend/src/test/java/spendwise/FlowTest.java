package spendwise;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import spendwise.config.Db;
import spendwise.controllers.DashboardController;
import spendwise.controllers.GroupController;
import spendwise.controllers.InsightController;
import spendwise.util.HttpError;
import spendwise.util.Json;
import spendwise.util.Router.Req;
import spendwise.util.Router.Res;

/**
 * Runs the real controllers and services against a FAKE database (no MySQL needed).
 * It checks the business rules, the transaction logic and the JSON shapes.
 */
public class FlowTest {
    interface Thrower { void run() throws Exception; }

    static final List<String> log = new ArrayList<>();
    static boolean duplicateToken = false;

    static Map<String, Object> row(Object... kv) { return Json.obj(kv); }

    @SafeVarargs
    static List<Map<String, Object>> rows(Map<String, Object>... r) { return Arrays.asList(r); }

    static void expectError(int status, String message, Thrower t) {
        try { t.run(); } catch (HttpError e) {
            Check.eq(status, e.status, "status of: " + message);
            Check.eq(message, e.getMessage(), "message");
            return;
        } catch (Exception e) { throw new AssertionError("wrong exception: " + e, e); }
        throw new AssertionError("expected an error: " + message);
    }

    static Req req(String id, String json) {
        Req r = new Req();
        if (id != null) r.params.put("id", id);
        if (json != null) r.body = Json.parse(json);
        return r;
    }

    @SuppressWarnings("unchecked")
    public static void main(String[] args) throws Exception {
        // ---------- 1. dashboard shape (regression test for the totals bug) ----------
        Db.testBackend = new Db.Backend() {
            public List<Map<String, Object>> query(String sql, Object[] p) { return rows(row("income", 100.0, "expenses", 40.0, "budget", null)); }
            public long update(String sql, Object[] p) { return 1; }
        };
        Map<String, Object> dash = (Map<String, Object>) DashboardController.get(new Req(), new Res());
        Check.eq(100.0, ((Map<String, Object>) dash.get("totals")).get("income"), "dashboard total income");
        Check.eq(60.0, ((Map<String, Object>) dash.get("totals")).get("balance"), "dashboard balance");
        Check.eq(60.0, ((Map<String, Object>) dash.get("month")).get("savings"), "dashboard savings");
        Check.ok(dash.get("weekly") instanceof List, "weekly is a list");
        Json.stringify(dash);

        // ---------- 2. insights endpoint with the numbers from the report screenshots ----------
        Db.testBackend = new Db.Backend() {
            public List<Map<String, Object>> query(String sql, Object[] p) {
                if (sql.contains("AS cur")) return rows(row("cur", "2026-11", "prev", "2026-10"));
                if (sql.contains("FROM budgets b")) return rows(row("id", 1L, "category", "Overall", "amount", 30000.0, "spent", 150.0));
                if (sql.contains("GROUP BY month, category"))
                    return rows(row("month", "2026-10", "category", "Food", "total", 250.0, "entries", 1L), row("month", "2026-10", "category", "Education", "total", 1200.0, "entries", 1L),
                        row("month", "2026-10", "category", "Entertainment", "total", 450.0, "entries", 1L), row("month", "2026-10", "category", "Shopping", "total", 800.0, "entries", 1L),
                        row("month", "2026-11", "category", "Food", "total", 150.0, "entries", 1L));
                if (sql.contains("ORDER BY entries DESC")) return rows(row("category", "Food", "entries", 2L, "total", 400.0));
                if (sql.contains("DAYOFWEEK")) return rows(row("dow", 2L, "total", 1200.0), row("dow", 3L, "total", 950.0));
                if (sql.contains("(SELECT COALESCE(SUM(amount),0) FROM income")) return rows(row("income", 0.0, "expenses", 150.0));
                if (sql.contains("FROM savings_goals g")) return rows(row("id", 1L, "name", "new laptop", "target_amount", 10000.0, "deadline", null, "saved", 0.0));
                throw new IllegalStateException("unexpected SQL: " + sql);
            }
            public long update(String sql, Object[] p) { return 1; }
        };
        Map<String, Object> ins = (Map<String, Object>) InsightController.get(new Req(), new Res());
        Map<String, Object> score = (Map<String, Object>) ins.get("score");
        Check.eq(70L, score.get("total"), "insights score");
        Check.eq(-94.4, ins.get("change"), "insights change");
        Check.eq(1200.0, ((List<Map<String, Object>>) ins.get("weekday")).get(1).get("total"), "Monday total");
        Check.ok(Json.stringify(ins).contains("\"label\":\"Good\""), "insights JSON");

        // ---------- 3. group balances and "who owes whom" ----------
        List<Map<String, Object>> memberRows = rows(row("id", 1L, "name", "Asha"), row("id", 2L, "name", "Ravi"), row("id", 3L, "name", "Meera"));
        List<Map<String, Object>> balanceRows = rows(
            row("id", 1L, "name", "Asha", "paid", 1500.0, "owed", 500.0, "settled_paid", 0.0, "settled_received", 0.0),
            row("id", 2L, "name", "Ravi", "paid", 0.0, "owed", 500.0, "settled_paid", 0.0, "settled_received", 0.0),
            row("id", 3L, "name", "Meera", "paid", 0.0, "owed", 500.0, "settled_paid", 0.0, "settled_received", 0.0));
        Db.testBackend = new Db.Backend() {
            public List<Map<String, Object>> query(String sql, Object[] p) {
                if (sql.contains("FOR UPDATE")) return rows(row("id", 1L));
                if (sql.contains("SELECT * FROM `groups`")) return rows(row("id", 1L, "name", "Goa trip", "created_at", "2026-11-03 10:00:00"));
                if (sql.contains("settled_paid")) return balanceRows;
                if (sql.contains("FROM group_members WHERE group_id")) return memberRows;
                return List.of();
            }
            public long update(String sql, Object[] p) throws SQLException {
                log.add(sql + " " + Arrays.toString(p));
                if (duplicateToken && sql.contains("INSERT INTO settlements")) throw new SQLException("Duplicate entry", "23000", 1062);
                return 9;
            }
        };
        Map<String, Object> detail = (Map<String, Object>) GroupController.detail(req("1", null), new Res());
        List<Map<String, Object>> debts = (List<Map<String, Object>>) detail.get("debts");
        Check.eq(2, debts.size(), "two debts");
        Check.eq(500.0, debts.get(0).get("amount"), "each owes 500");
        Check.eq("Asha", debts.get(0).get("to_name"), "owed to the payer");
        Check.eq(1000.0, ((List<Map<String, Object>>) detail.get("balances")).get(0).get("net"), "payer net");

        // ---------- 4. settlements: checks, limits, duplicates ----------
        String ok = "{\"from\":2,\"to\":1,\"amount\":500,\"date\":\"2026-11-03\",\"token\":\"abc12345-token\"}";
        log.clear();
        Res res = new Res();
        GroupController.addSettlement(req("1", ok), res);
        Check.eq(201, res.status, "settlement created");
        Check.ok(log.size() == 1 && log.get(0).contains("INSERT INTO settlements") && log.get(0).contains("abc12345-token"), "settlement saved once");
        expectError(400, "Ravi only owes Asha up to ₹500.00.", () -> GroupController.addSettlement(req("1",
            "{\"from\":2,\"to\":1,\"amount\":600,\"date\":\"2026-11-03\",\"token\":\"abc12345-token2\"}"), new Res()));
        expectError(400, "Asha does not owe Ravi anything right now.", () -> GroupController.addSettlement(req("1",
            "{\"from\":1,\"to\":2,\"amount\":10,\"date\":\"2026-11-03\",\"token\":\"abc12345-token3\"}"), new Res()));
        expectError(400, "Payer and receiver must be different people.", () -> GroupController.addSettlement(req("1",
            "{\"from\":2,\"to\":2,\"amount\":10,\"date\":\"2026-11-03\",\"token\":\"abc12345-token4\"}"), new Res()));
        expectError(400, "Missing request token.", () -> GroupController.addSettlement(req("1", "{\"from\":2,\"to\":1,\"amount\":10,\"date\":\"2026-11-03\"}"), new Res()));
        expectError(400, "Amount must be greater than zero.", () -> GroupController.addSettlement(req("1",
            "{\"from\":2,\"to\":1,\"amount\":0,\"date\":\"2026-11-03\",\"token\":\"abc12345-token5\"}"), new Res()));
        duplicateToken = true;   // the database reports a repeated token
        expectError(409, "This settlement was already recorded.", () -> GroupController.addSettlement(req("1", ok), new Res()));
        duplicateToken = false;

        // ---------- 5. shared expenses: equal and custom splits ----------
        log.clear();
        GroupController.addExpense(req("1", "{\"description\":\"Dinner\",\"amount\":100,\"paid_by\":1,\"date\":\"2026-11-03\",\"split_type\":\"equal\",\"participants\":[1,2,3]}"), new Res());
        List<String> shareLines = new ArrayList<>();
        for (String l : log) if (l.contains("expense_participants")) shareLines.add(l);
        Check.eq(3, shareLines.size(), "three participant rows");
        Check.ok(shareLines.get(0).endsWith(", 33.34]") && shareLines.get(1).endsWith(", 33.33]") && shareLines.get(2).endsWith(", 33.33]"), "33.34 / 33.33 / 33.33 -> " + shareLines);
        expectError(400, "Custom amounts add up to ₹90.00 but the expense is ₹100.00.", () -> GroupController.addExpense(req("1",
            "{\"description\":\"Taxi\",\"amount\":100,\"paid_by\":1,\"date\":\"2026-11-03\",\"split_type\":\"custom\",\"participants\":[{\"member_id\":1,\"amount\":40},{\"member_id\":2,\"amount\":50}]}"), new Res()));
        expectError(400, "A member appears twice.", () -> GroupController.addExpense(req("1",
            "{\"description\":\"Taxi\",\"amount\":100,\"paid_by\":1,\"date\":\"2026-11-03\",\"split_type\":\"custom\",\"participants\":[{\"member_id\":1,\"amount\":40},{\"member_id\":1,\"amount\":60}]}"), new Res()));
        expectError(400, "Choose who paid.", () -> GroupController.addExpense(req("1",
            "{\"description\":\"Taxi\",\"amount\":100,\"paid_by\":99,\"date\":\"2026-11-03\",\"split_type\":\"equal\",\"participants\":[1]}"), new Res()));
        expectError(400, "Select at least one participant.", () -> GroupController.addExpense(req("1",
            "{\"description\":\"Taxi\",\"amount\":100,\"paid_by\":1,\"date\":\"2026-11-03\",\"split_type\":\"equal\",\"participants\":[]}"), new Res()));
        expectError(400, "A group needs at least 2 different members.", () -> GroupController.create(req(null, "{\"name\":\"Trip\",\"members\":[\"Asha\",\" asha \",\"\"]}".replace(" asha ", "Asha")), new Res()));
        Db.testBackend = null;
        Check.done("FlowTest");
    }
}
