package spendwise.config;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MySQL access (JDBC). Equivalent of config/db.js plus the mysql2 helpers.
 * Every query uses "?" placeholders, so user input never becomes part of the SQL text.
 * Rows are returned as maps: DECIMAL -> double, DATE -> "YYYY-MM-DD" text, integers -> long.
 */
public final class Db {
    /** Work that runs inside one database transaction. */
    public interface TxWork<T> { T run(Connection c) throws Exception; }

    /** Lets unit tests run without MySQL. Always null in the real application. */
    public interface Backend {
        List<Map<String, Object>> query(String sql, Object[] params) throws Exception;
        long update(String sql, Object[] params) throws Exception;
    }
    public static Backend testBackend = null;

    private Db() { }

    private static String url() {
        return "jdbc:mysql://" + Env.get("DB_HOST", "localhost") + ":" + Env.get("DB_PORT", "3306") + "/" + Env.get("DB_NAME", "spendwise")
            + "?useUnicode=true&characterEncoding=UTF-8&serverTimezone=UTC&allowPublicKeyRetrieval=true&useSSL=false";
    }

    public static Connection open() throws SQLException {
        return DriverManager.getConnection(url(), Env.get("DB_USER", "root"), Env.get("DB_PASSWORD", ""));
    }

    // ---------- reading ----------
    public static List<Map<String, Object>> query(String sql, Object... p) throws Exception { return queryOn(null, sql, p); }

    public static List<Map<String, Object>> queryOn(Connection c, String sql, Object... p) throws Exception {
        if (testBackend != null) return testBackend.query(sql, p);
        boolean own = (c == null);
        Connection cn = own ? open() : c;
        try (PreparedStatement ps = cn.prepareStatement(sql)) {
            bind(ps, p);
            try (ResultSet rs = ps.executeQuery()) { return rows(rs); }
        } finally { if (own) cn.close(); }
    }

    /** First row, or null when there are no rows. */
    public static Map<String, Object> one(String sql, Object... p) throws Exception { return oneOn(null, sql, p); }

    public static Map<String, Object> oneOn(Connection c, String sql, Object... p) throws Exception {
        List<Map<String, Object>> r = queryOn(c, sql, p);
        return r.isEmpty() ? null : r.get(0);
    }

    // ---------- writing ----------
    /** Returns the number of matched rows (affectedRows in mysql2). */
    public static int update(String sql, Object... p) throws Exception { return updateOn(null, sql, p); }

    public static int updateOn(Connection c, String sql, Object... p) throws Exception {
        if (testBackend != null) return (int) testBackend.update(sql, p);
        boolean own = (c == null);
        Connection cn = own ? open() : c;
        try (PreparedStatement ps = cn.prepareStatement(sql)) {
            bind(ps, p);
            return ps.executeUpdate();
        } finally { if (own) cn.close(); }
    }

    /** Runs an INSERT and returns the new auto-increment id (insertId in mysql2). */
    public static long insert(String sql, Object... p) throws Exception { return insertOn(null, sql, p); }

    public static long insertOn(Connection c, String sql, Object... p) throws Exception {
        if (testBackend != null) return testBackend.update(sql, p);
        boolean own = (c == null);
        Connection cn = own ? open() : c;
        try (PreparedStatement ps = cn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            bind(ps, p);
            ps.executeUpdate();
            try (ResultSet k = ps.getGeneratedKeys()) { return k.next() ? k.getLong(1) : 0L; }
        } finally { if (own) cn.close(); }
    }

    /** Inserts many rows with one prepared statement (replaces mysql2's "VALUES ?" shortcut). */
    public static void batchOn(Connection c, String sql, List<Object[]> rows) throws Exception {
        if (testBackend != null) { for (Object[] r : rows) testBackend.update(sql, r); return; }
        boolean own = (c == null);
        Connection cn = own ? open() : c;
        try (PreparedStatement ps = cn.prepareStatement(sql)) {
            for (Object[] r : rows) { bind(ps, r); ps.addBatch(); }
            ps.executeBatch();
        } finally { if (own) cn.close(); }
    }

    /** All queries inside work() are saved together (commit) or not at all (rollback). */
    public static <T> T tx(TxWork<T> work) throws Exception {
        if (testBackend != null) return work.run(null);
        try (Connection c = open()) {
            c.setAutoCommit(false);
            try {
                T result = work.run(c);
                c.commit();
                return result;
            } catch (Exception e) {
                c.rollback();
                throw e;
            }
        }
    }

    // ---------- helpers ----------
    private static void bind(PreparedStatement ps, Object[] p) throws SQLException {
        for (int i = 0; i < p.length; i++) ps.setObject(i + 1, p[i]);
    }

    private static List<Map<String, Object>> rows(ResultSet rs) throws SQLException {
        ResultSetMetaData md = rs.getMetaData();
        int n = md.getColumnCount();
        List<Map<String, Object>> out = new ArrayList<>();
        while (rs.next()) {
            Map<String, Object> row = new LinkedHashMap<>();
            for (int i = 1; i <= n; i++) row.put(md.getColumnLabel(i), value(rs, i, md.getColumnType(i)));
            out.add(row);
        }
        return out;
    }

    private static Object value(ResultSet rs, int i, int type) throws SQLException {
        switch (type) {
            case Types.DECIMAL: case Types.NUMERIC: {
                BigDecimal b = rs.getBigDecimal(i);
                return b == null ? null : b.doubleValue();
            }
            case Types.FLOAT: case Types.DOUBLE: case Types.REAL: {
                double d = rs.getDouble(i);
                return rs.wasNull() ? null : d;
            }
            case Types.TINYINT: case Types.SMALLINT: case Types.INTEGER: case Types.BIGINT: {
                long l = rs.getLong(i);
                return rs.wasNull() ? null : l;
            }
            case Types.BIT: case Types.BOOLEAN: {
                boolean b = rs.getBoolean(i);
                return rs.wasNull() ? null : (b ? 1L : 0L);
            }
            default:
                return rs.getString(i);   // text, ENUM, DATE ("2026-11-03"), TIMESTAMP ...
        }
    }
}
