package spendwise.util;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Locale;
import java.util.regex.Pattern;

/** Small helpers that copy how JavaScript handled numbers, dates and text in the Node version. */
public final class Util {
    private static final Pattern NUMBER = Pattern.compile("^[+-]?(\\d+\\.?\\d*|\\.\\d+)([eE][+-]?\\d+)?$");
    private static final Pattern DATE = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}$");
    private static final Pattern MONTH = Pattern.compile("^\\d{4}-(0[1-9]|1[0-2])$");

    private Util() { }

    /** Like JavaScript Number(x): numbers pass, numeric text is parsed, anything else is NaN. */
    public static double num(Object v) {
        if (v instanceof Number n) return n.doubleValue();
        if (v instanceof String s) {
            s = s.trim();
            if (s.isEmpty()) return 0;
            return NUMBER.matcher(s).matches() ? Double.parseDouble(s) : Double.NaN;
        }
        return Double.NaN;
    }

    /** A whole number id from a JSON value, or null when it is not one. */
    public static Long toId(Object v) {
        double d = num(v);
        return (Double.isFinite(d) && d == Math.rint(d)) ? Long.valueOf((long) d) : null;
    }

    /** Like String(x || ''). */
    public static String str(Object v) {
        if (v == null) return "";
        if (v instanceof Double d) return jsNum(d);
        return v.toString();
    }

    public static boolean validDate(String d) {
        if (d == null || !DATE.matcher(d).matches()) return false;
        try { LocalDate.parse(d); return true; } catch (Exception e) { return false; }
    }

    public static boolean monthOk(String m) { return m != null && MONTH.matcher(m).matches(); }

    public static double round1(double n) { return Math.round(n * 10) / 10.0; }
    public static double round2(double n) { return Math.round(n * 100) / 100.0; }

    /** toFixed(2) */
    public static String fmt2(double n) { return String.format(Locale.ROOT, "%.2f", n); }

    /** Prints a number the way JavaScript does inside text: 150 not 150.0, 94.4 not 94.40000001. */
    public static String jsNum(double d) {
        if (Double.isNaN(d)) return "NaN";
        if (d == Math.rint(d) && Math.abs(d) < 1e15) return Long.toString((long) d);
        return new BigDecimal(Double.toString(d)).stripTrailingZeros().toPlainString();
    }
}
