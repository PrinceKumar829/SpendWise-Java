package spendwise.models;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import spendwise.config.Db;

/** Model = only SQL. One class serves both expenses and income (same shape). */
public class RecordModel {
    /** The four fields of an expense or income record. */
    public record Rec(double amount, String type, String description, String date) { }

    private final String table, typeCol, dateCol;

    public RecordModel(String table, String typeCol, String dateCol) {
        this.table = table;
        this.typeCol = typeCol;
        this.dateCol = dateCol;
    }

    private static boolean has(String s) { return s != null && !s.isEmpty(); }

    public List<Map<String, Object>> findAll(Map<String, String> q) throws Exception {
        StringBuilder sql = new StringBuilder("SELECT * FROM " + table + " WHERE 1=1");
        List<Object> p = new ArrayList<>();
        String search = q.get("search"), type = q.get("type"), from = q.get("from"), to = q.get("to");
        if (has(search)) { sql.append(" AND (description LIKE ? OR " + typeCol + " LIKE ?)"); p.add("%" + search + "%"); p.add("%" + search + "%"); }
        if (has(type))   { sql.append(" AND " + typeCol + " = ?"); p.add(type); }
        if (has(from))   { sql.append(" AND " + dateCol + " >= ?"); p.add(from); }
        if (has(to))     { sql.append(" AND " + dateCol + " <= ?"); p.add(to); }
        sql.append(" ORDER BY " + dateCol + " DESC, id DESC");
        return Db.query(sql.toString(), p.toArray());
    }

    public long insert(Rec r) throws Exception {
        return Db.insert("INSERT INTO " + table + " (amount, " + typeCol + ", description, " + dateCol + ") VALUES (?,?,?,?)",
            r.amount(), r.type(), r.description(), r.date());
    }

    public int update(String id, Rec r) throws Exception {
        return Db.update("UPDATE " + table + " SET amount=?, " + typeCol + "=?, description=?, " + dateCol + "=? WHERE id=?",
            r.amount(), r.type(), r.description(), r.date(), id);
    }

    public int remove(String id) throws Exception {
        return Db.update("DELETE FROM " + table + " WHERE id=?", id);
    }

    // GROUP BY + SUM: totals per month
    public List<Map<String, Object>> monthly() throws Exception {
        return Db.query("SELECT DATE_FORMAT(" + dateCol + ", '%Y-%m') AS month, SUM(amount) AS total, COUNT(*) AS entries "
            + "FROM " + table + " GROUP BY month ORDER BY month DESC");
    }
}
