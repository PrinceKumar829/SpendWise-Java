package spendwise.models;

import java.sql.Connection;
import java.util.List;
import java.util.Map;
import spendwise.config.Db;

/** Group SQL only. Functions accept an optional connection so they can run inside a transaction (pass null otherwise). */
public final class GroupModel {
    private GroupModel() { }

    public static List<Map<String, Object>> list() throws Exception {
        return Db.query("SELECT g.id, g.name, g.created_at, COUNT(m.id) AS member_count "
            + "FROM `groups` g LEFT JOIN group_members m ON m.group_id = g.id "
            + "GROUP BY g.id ORDER BY g.id DESC");
    }

    public static Map<String, Object> find(Object id, Connection c) throws Exception {
        return Db.oneOn(c, "SELECT * FROM `groups` WHERE id = ?", id);
    }

    public static int remove(Object id) throws Exception {
        return Db.update("DELETE FROM `groups` WHERE id = ?", id);
    }

    public static void lockGroup(Object id, Connection c) throws Exception {
        Db.queryOn(c, "SELECT id FROM `groups` WHERE id = ? FOR UPDATE", id);
    }

    public static List<Map<String, Object>> members(Object groupId, Connection c) throws Exception {
        return Db.queryOn(c, "SELECT id, name FROM group_members WHERE group_id = ? ORDER BY id", groupId);
    }

    public static long addMember(Object groupId, String name, Connection c) throws Exception {
        return Db.insertOn(c, "INSERT INTO group_members (group_id, name) VALUES (?, ?)", groupId, name);
    }

    public static long insertGroup(String name, Connection c) throws Exception {
        return Db.insertOn(c, "INSERT INTO `groups` (name) VALUES (?)", name);
    }

    // Totals per member, using subqueries
    public static List<Map<String, Object>> balanceRows(Object groupId, Connection c) throws Exception {
        return Db.queryOn(c, "SELECT m.id, m.name, "
            + "(SELECT COALESCE(SUM(amount), 0) FROM shared_expenses WHERE paid_by = m.id) AS paid, "
            + "(SELECT COALESCE(SUM(share_amount), 0) FROM expense_participants WHERE member_id = m.id) AS owed, "
            + "(SELECT COALESCE(SUM(amount), 0) FROM settlements WHERE from_member = m.id) AS settled_paid, "
            + "(SELECT COALESCE(SUM(amount), 0) FROM settlements WHERE to_member = m.id) AS settled_received "
            + "FROM group_members m WHERE m.group_id = ? ORDER BY m.id", groupId);
    }

    // History: JOIN for the payer's name, GROUP_CONCAT to list everyone's share in one text column
    public static List<Map<String, Object>> expenseHistory(Object groupId) throws Exception {
        return Db.query("SELECT se.id, se.description, se.amount, se.split_type, se.expense_date, payer.name AS paid_by_name, "
            + "GROUP_CONCAT(CONCAT(pm.name, ' ₹', ep.share_amount) ORDER BY pm.id SEPARATOR ', ') AS shares "
            + "FROM shared_expenses se "
            + "JOIN group_members payer ON payer.id = se.paid_by "
            + "JOIN expense_participants ep ON ep.shared_expense_id = se.id "
            + "JOIN group_members pm ON pm.id = ep.member_id "
            + "WHERE se.group_id = ? GROUP BY se.id ORDER BY se.expense_date DESC, se.id DESC", groupId);
    }

    public static List<Map<String, Object>> settlementHistory(Object groupId) throws Exception {
        return Db.query("SELECT s.id, s.amount, s.settled_on, f.name AS from_name, t.name AS to_name "
            + "FROM settlements s JOIN group_members f ON f.id = s.from_member JOIN group_members t ON t.id = s.to_member "
            + "WHERE s.group_id = ? ORDER BY s.settled_on DESC, s.id DESC", groupId);
    }

    public static long insertExpense(Object groupId, long paidBy, double amount, String description, String splitType, String date, Connection c) throws Exception {
        return Db.insertOn(c, "INSERT INTO shared_expenses (group_id, paid_by, amount, description, split_type, expense_date) VALUES (?,?,?,?,?,?)",
            groupId, paidBy, amount, description, splitType, date);
    }

    public static void insertParticipants(List<Object[]> rows, Connection c) throws Exception {
        Db.batchOn(c, "INSERT INTO expense_participants (shared_expense_id, member_id, share_amount) VALUES (?,?,?)", rows);
    }

    public static void insertSettlement(Object groupId, long from, long to, double amount, String date, String token, Connection c) throws Exception {
        Db.insertOn(c, "INSERT INTO settlements (group_id, from_member, to_member, amount, settled_on, request_token) VALUES (?,?,?,?,?,?)",
            groupId, from, to, amount, date, token);
    }
}
