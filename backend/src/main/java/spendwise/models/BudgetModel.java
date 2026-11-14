package spendwise.models;

import java.util.List;
import java.util.Map;
import spendwise.config.Db;

public final class BudgetModel {
    private BudgetModel() { }

    // Each budget row comes back with how much was spent, using a correlated subquery.
    // 'Overall' counts every category; other budgets count only their own category.
    public static List<Map<String, Object>> listWithSpent(String month) throws Exception {
        return Db.query("SELECT b.id, b.category, b.amount, "
            + "COALESCE((SELECT SUM(e.amount) FROM expenses e "
            + "WHERE e.expense_date BETWEEN ? AND LAST_DAY(?) "
            + "AND (b.category = 'Overall' OR e.category = b.category)), 0) AS spent "
            + "FROM budgets b WHERE b.budget_month = ? "
            + "ORDER BY b.category = 'Overall' DESC, b.category", month + "-01", month + "-01", month);
    }

    // UNIQUE(budget_month, category) + ON DUPLICATE KEY UPDATE = create or update in one query
    public static void upsert(String month, String category, double amount) throws Exception {
        Db.update("INSERT INTO budgets (budget_month, category, amount) VALUES (?,?,?) ON DUPLICATE KEY UPDATE amount = VALUES(amount)",
            month, category, amount);
    }

    public static int remove(String id) throws Exception {
        return Db.update("DELETE FROM budgets WHERE id = ?", id);
    }
}
