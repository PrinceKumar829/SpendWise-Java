package spendwise.models;

import java.util.List;
import java.util.Map;
import spendwise.config.Db;

public final class GoalModel {
    private GoalModel() { }

    // LEFT JOIN so goals with no contributions still appear (saved = 0)
    public static List<Map<String, Object>> list() throws Exception {
        return Db.query("SELECT g.id, g.name, g.target_amount, g.deadline, COALESCE(SUM(c.amount), 0) AS saved "
            + "FROM savings_goals g LEFT JOIN savings_contributions c ON c.goal_id = g.id "
            + "GROUP BY g.id ORDER BY g.id DESC");
    }

    public static Map<String, Object> find(String id) throws Exception {
        return Db.one("SELECT g.id, g.target_amount, COALESCE(SUM(c.amount), 0) AS saved "
            + "FROM savings_goals g LEFT JOIN savings_contributions c ON c.goal_id = g.id WHERE g.id = ? GROUP BY g.id", id);
    }

    public static void create(String name, double target, String deadline) throws Exception {
        Db.update("INSERT INTO savings_goals (name, target_amount, deadline) VALUES (?,?,?)", name, target, deadline);
    }

    public static void contribute(String goalId, double amount) throws Exception {
        Db.update("INSERT INTO savings_contributions (goal_id, amount, contributed_on) VALUES (?,?,CURDATE())", goalId, amount);
    }

    public static int remove(String id) throws Exception {
        return Db.update("DELETE FROM savings_goals WHERE id = ?", id);
    }
}
