package spendwise.models;

import java.util.List;
import java.util.Map;
import spendwise.config.Db;

public final class ChallengeModel {
    private static final String UNNECESSARY = "('Shopping','Entertainment','Others')";

    private ChallengeModel() { }

    // Progress is computed from the expenses table inside each challenge's date range
    public static List<Map<String, Object>> listWithProgress() throws Exception {
        return Db.query("SELECT c.id, c.challenge_type, c.start_date, c.end_date, c.status, "
            + "COALESCE((SELECT SUM(e.amount) FROM expenses e WHERE e.expense_date BETWEEN c.start_date AND c.end_date AND e.category IN " + UNNECESSARY + "), 0) AS unnecessary, "
            + "(SELECT COUNT(DISTINCT e.expense_date) FROM expenses e WHERE e.expense_date BETWEEN c.start_date AND c.end_date AND e.category IN " + UNNECESSARY + ") AS spend_days, "
            + "DATEDIFF(LEAST(CURDATE(), c.end_date), c.start_date) + 1 AS days_done, "
            + "DATEDIFF(c.end_date, c.start_date) + 1 AS total_days, "
            + "(CURDATE() > c.end_date) AS finished "
            + "FROM challenges c ORDER BY c.id DESC");
    }

    public static boolean hasActive() throws Exception {
        return !Db.query("SELECT id FROM challenges WHERE status = 'active' LIMIT 1").isEmpty();
    }

    public static void create(String type, int days) throws Exception {
        Db.update("INSERT INTO challenges (challenge_type, start_date, end_date) VALUES (?, CURDATE(), CURDATE() + INTERVAL ? DAY)", type, days - 1);
    }

    public static void setStatus(Object id, String status) throws Exception {
        Db.update("UPDATE challenges SET status = ? WHERE id = ?", status, id);
    }

    public static int remove(String id) throws Exception {
        return Db.update("DELETE FROM challenges WHERE id = ?", id);
    }
}
