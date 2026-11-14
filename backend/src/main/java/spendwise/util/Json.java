package spendwise.util;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** A very small JSON reader and writer, so no library is needed. */
public final class Json {
    private Json() { }

    /** Builds an ordered object: Json.obj("success", true, "id", 5). */
    public static Map<String, Object> obj(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    // ---------------- writing ----------------
    public static String stringify(Object v) {
        StringBuilder sb = new StringBuilder();
        write(sb, v);
        return sb.toString();
    }

    private static void write(StringBuilder sb, Object v) {
        if (v == null) sb.append("null");
        else if (v instanceof String s) quote(sb, s);
        else if (v instanceof Boolean b) sb.append(b.booleanValue());
        else if (v instanceof Number n) number(sb, n);
        else if (v instanceof Map<?, ?> m) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (!first) sb.append(',');
                first = false;
                quote(sb, String.valueOf(e.getKey()));
                sb.append(':');
                write(sb, e.getValue());
            }
            sb.append('}');
        } else if (v instanceof Iterable<?> it) {
            sb.append('[');
            boolean first = true;
            for (Object o : it) {
                if (!first) sb.append(',');
                first = false;
                write(sb, o);
            }
            sb.append(']');
        } else quote(sb, v.toString());
    }

    private static void number(StringBuilder sb, Number n) {
        if (n instanceof Long || n instanceof Integer || n instanceof Short || n instanceof Byte) { sb.append(n.longValue()); return; }
        double d = n.doubleValue();
        if (Double.isNaN(d) || Double.isInfinite(d)) sb.append("null");
        else if (d == Math.rint(d) && Math.abs(d) < 1e15) sb.append((long) d);
        else sb.append(new BigDecimal(Double.toString(d)).stripTrailingZeros().toPlainString());
    }

    private static void quote(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                case '\b': sb.append("\\b"); break;
                case '\f': sb.append("\\f"); break;
                default:
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        sb.append('"');
    }

    // ---------------- reading ----------------
    /** Objects become LinkedHashMap, arrays ArrayList, numbers Double. Throws IllegalArgumentException on bad JSON. */
    public static Object parse(String s) {
        Reader r = new Reader(s);
        r.skip();
        Object v = r.value();
        r.skip();
        if (r.i < s.length()) throw new IllegalArgumentException("Unexpected data at position " + r.i);
        return v;
    }

    private static final class Reader {
        final String s;
        int i = 0;

        Reader(String s) { this.s = s; }

        void skip() { while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++; }

        IllegalArgumentException err() { return new IllegalArgumentException("Invalid JSON at position " + i); }

        void expect(char c) {
            if (i >= s.length() || s.charAt(i) != c) throw err();
            i++;
        }

        Object value() {
            if (i >= s.length()) throw err();
            char c = s.charAt(i);
            if (c == '{') return object();
            if (c == '[') return array();
            if (c == '"') return string();
            if (s.startsWith("true", i)) { i += 4; return Boolean.TRUE; }
            if (s.startsWith("false", i)) { i += 5; return Boolean.FALSE; }
            if (s.startsWith("null", i)) { i += 4; return null; }
            return number();
        }

        Map<String, Object> object() {
            expect('{');
            Map<String, Object> m = new LinkedHashMap<>();
            skip();
            if (i < s.length() && s.charAt(i) == '}') { i++; return m; }
            while (true) {
                skip();
                String k = string();
                skip();
                expect(':');
                skip();
                m.put(k, value());
                skip();
                if (i < s.length() && s.charAt(i) == ',') { i++; continue; }
                expect('}');
                return m;
            }
        }

        List<Object> array() {
            expect('[');
            List<Object> l = new ArrayList<>();
            skip();
            if (i < s.length() && s.charAt(i) == ']') { i++; return l; }
            while (true) {
                skip();
                l.add(value());
                skip();
                if (i < s.length() && s.charAt(i) == ',') { i++; continue; }
                expect(']');
                return l;
            }
        }

        String string() {
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (true) {
                if (i >= s.length()) throw err();
                char c = s.charAt(i++);
                if (c == '"') return sb.toString();
                if (c != '\\') { sb.append(c); continue; }
                if (i >= s.length()) throw err();
                char e = s.charAt(i++);
                switch (e) {
                    case 'n': sb.append('\n'); break;
                    case 'r': sb.append('\r'); break;
                    case 't': sb.append('\t'); break;
                    case 'b': sb.append('\b'); break;
                    case 'f': sb.append('\f'); break;
                    case 'u':
                        if (i + 4 > s.length()) throw err();
                        sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                        i += 4;
                        break;
                    default: sb.append(e);   // \" \\ \/
                }
            }
        }

        Double number() {
            int start = i;
            while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) i++;
            if (start == i) throw err();
            try { return Double.valueOf(s.substring(start, i)); } catch (NumberFormatException e) { throw err(); }
        }
    }
}
