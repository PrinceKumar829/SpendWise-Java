package spendwise.models;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import spendwise.config.Db;

/** All dashboard SQL lives here. */
public final class DashboardModel {
    private static final String THIS_MONTH = "BETWEEN DATE_FORMAT(CURDATE(), '%Y-%m-01') AND LAST_DAY(CURDATE())";

    // Small "calendar" tables built with UNION ALL, so days/months with no spending still appear (as 0)
    private static final String LAST_7_DAYS;
    private static final String LAST_6_MONTHS;
    static {
        StringBuilder d = new StringBuilder();
        for (int i = 0; i < 7; i++) d.append(i > 0 ? " UNION ALL " : "").append("SELECT CURDATE() - INTERVAL ").append(6 - i).append(" DAY AS day");
        LAST_7_DAYS = d.toString();
        StringBuilder m = new StringBuilder();
        for (int i = 0; i < 6; i++) m.append(i > 0 ? " UNION ALL " : "").append("SELECT DATE_FORMAT(CURDATE() - INTERVAL ").append(5 - i).append(" MONTH, '%Y-%m') AS month");
        LAST_6_MONTHS = m.toString();
    }

    private DashboardModel() { }

    public static Map<String, Object> getDashboard() throws Exception {
        // 1. All-time totals (two scalar subqueries)
        Map<String, Object> totals = Db.one("SELECT (SELECT COALESCE(SUM(amount),0) FROM income)   AS income, "
            + "(SELECT COALESCE(SUM(amount),0) FROM expenses) AS expenses");
        // 2. Current month totals + this month's overall budget (NULL if none set)
        Map<String, Object> month = Db.one("SELECT (SELECT COALESCE(SUM(amount),0) FROM income   WHERE income_date  " + THIS_MONTH + ") AS income, "
            + "(SELECT COALESCE(SUM(amount),0) FROM expenses WHERE expense_date " + THIS_MONTH + ") AS expenses, "
            + "(SELECT amount FROM budgets WHERE budget_month = DATE_FORMAT(CURDATE(), '%Y-%m') AND category = 'Overall') AS budget");
        // 3. Category pie: GROUP BY + SUM
        List<Map<String, Object>> categories = Db.query("SELECT category, SUM(amount) AS total, COUNT(*) AS entries FROM expenses "
            + "WHERE expense_date " + THIS_MONTH + " GROUP BY category ORDER BY total DESC");
        // 4. Recent transactions: expenses and income merged with UNION ALL
        List<Map<String, Object>> recent = Db.query("(SELECT 'expense' AS kind, id, amount, category AS label, description, expense_date AS tx_date FROM expenses) "
            + "UNION ALL "
            + "(SELECT 'income', id, amount, source, description, income_date FROM income) "
            + "ORDER BY tx_date DESC, id DESC LIMIT 6");
        // 5. Weekly graph: LEFT JOIN keeps days with no expenses
        List<Map<String, Object>> weekly = Db.query("SELECT d.day, COALESCE(SUM(e.amount),0) AS total "
            + "FROM (" + LAST_7_DAYS + ") d LEFT JOIN expenses e ON e.expense_date = d.day "
            + "GROUP BY d.day ORDER BY d.day");
        // 6. Monthly graph + income vs expense for the last 6 months
        List<Map<String, Object>> monthly = Db.query("SELECT m.month, "
            + "(SELECT COALESCE(SUM(amount),0) FROM expenses WHERE DATE_FORMAT(expense_date,'%Y-%m') = m.month) AS expenses, "
            + "(SELECT COALESCE(SUM(amount),0) FROM income   WHERE DATE_FORMAT(income_date, '%Y-%m') = m.month) AS income "
            + "FROM (" + LAST_6_MONTHS + ") m ORDER BY m.month");

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("totals", totals);
        out.put("month", month);
        out.put("categories", categories);
        out.put("recent", recent);
        out.put("weekly", weekly);
        out.put("monthly", monthly);
        return out;
    }
}
