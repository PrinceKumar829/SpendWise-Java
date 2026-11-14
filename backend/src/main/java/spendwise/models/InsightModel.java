package spendwise.models;

import java.util.List;
import java.util.Map;
import spendwise.config.Db;

public final class InsightModel {
    private InsightModel() { }

    public static Map<String, Object> months() throws Exception {
        return Db.one("SELECT DATE_FORMAT(CURDATE(), '%Y-%m') AS cur, DATE_FORMAT(CURDATE() - INTERVAL 1 MONTH, '%Y-%m') AS prev");
    }

    // This month and last month, grouped by month + category
    public static List<Map<String, Object>> categoryByMonth() throws Exception {
        return Db.query("SELECT DATE_FORMAT(expense_date, '%Y-%m') AS month, category, SUM(amount) AS total, COUNT(*) AS entries "
            + "FROM expenses WHERE expense_date >= DATE_FORMAT(CURDATE() - INTERVAL 1 MONTH, '%Y-%m-01') GROUP BY month, category");
    }

    // Which categories are used most often (last 90 days)
    public static List<Map<String, Object>> frequency() throws Exception {
        return Db.query("SELECT category, COUNT(*) AS entries, SUM(amount) AS total FROM expenses "
            + "WHERE expense_date >= CURDATE() - INTERVAL 90 DAY GROUP BY category ORDER BY entries DESC, total DESC");
    }

    // Spending by day of week (1 = Sunday) over the last 90 days
    public static List<Map<String, Object>> weekday() throws Exception {
        return Db.query("SELECT DAYOFWEEK(expense_date) AS dow, SUM(amount) AS total FROM expenses "
            + "WHERE expense_date >= CURDATE() - INTERVAL 90 DAY GROUP BY dow");
    }

    public static Map<String, Object> money() throws Exception {
        return Db.one("SELECT "
            + "(SELECT COALESCE(SUM(amount),0) FROM income   WHERE income_date  BETWEEN DATE_FORMAT(CURDATE(), '%Y-%m-01') AND LAST_DAY(CURDATE())) AS income, "
            + "(SELECT COALESCE(SUM(amount),0) FROM expenses WHERE expense_date BETWEEN DATE_FORMAT(CURDATE(), '%Y-%m-01') AND LAST_DAY(CURDATE())) AS expenses");
    }
}
