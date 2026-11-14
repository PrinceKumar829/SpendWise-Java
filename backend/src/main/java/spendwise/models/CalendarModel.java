package spendwise.models;

import java.util.List;
import java.util.Map;
import spendwise.config.Db;

public final class CalendarModel {
    private CalendarModel() { }

    /** Returns [expenses per day, income per day] for the month (YYYY-MM). */
    public static List<List<Map<String, Object>>> month(String month) throws Exception {
        String start = month + "-01";
        List<Map<String, Object>> expenses = Db.query("SELECT expense_date AS day, SUM(amount) AS total, COUNT(*) AS entries FROM expenses "
            + "WHERE expense_date BETWEEN ? AND LAST_DAY(?) GROUP BY expense_date", start, start);
        List<Map<String, Object>> income = Db.query("SELECT income_date AS day, SUM(amount) AS total FROM income "
            + "WHERE income_date BETWEEN ? AND LAST_DAY(?) GROUP BY income_date", start, start);
        return List.of(expenses, income);
    }
}
