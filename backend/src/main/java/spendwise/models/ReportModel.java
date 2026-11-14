package spendwise.models;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import spendwise.config.Db;

public final class ReportModel {
    private ReportModel() { }

    public static Map<String, Object> monthData(String month) throws Exception {
        String s = month + "-01";
        Map<String, Object> t = Db.one("SELECT (SELECT COALESCE(SUM(amount),0) FROM income   WHERE income_date  BETWEEN ? AND LAST_DAY(?)) AS income, "
            + "(SELECT COALESCE(SUM(amount),0) FROM expenses WHERE expense_date BETWEEN ? AND LAST_DAY(?)) AS expenses", s, s, s, s);
        List<Map<String, Object>> categories = Db.query("SELECT category, SUM(amount) AS total, COUNT(*) AS entries FROM expenses "
            + "WHERE expense_date BETWEEN ? AND LAST_DAY(?) GROUP BY category ORDER BY total DESC", s, s);
        Map<String, Object> g = Db.one("SELECT COALESCE(SUM(amount),0) AS saved FROM savings_contributions WHERE contributed_on BETWEEN ? AND LAST_DAY(?)", s, s);
        Map<String, Object> b = Db.one("SELECT amount FROM budgets WHERE budget_month = ? AND category = 'Overall'", month);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("income", t.get("income"));
        out.put("expenses", t.get("expenses"));
        out.put("categories", categories);
        out.put("goalSavings", g.get("saved"));
        out.put("budget", b == null ? null : b.get("amount"));
        return out;
    }
}
