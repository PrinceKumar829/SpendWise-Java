package spendwise.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/** Settings come from real environment variables first, then from the .env file (same behaviour as dotenv in the Node version). */
public final class Env {
    private static final Map<String, String> FILE = new HashMap<>();

    static {
        Path p = Path.of(".env");
        if (Files.exists(p)) {
            try {
                for (String line : Files.readAllLines(p)) {
                    line = line.trim();
                    int i = line.indexOf('=');
                    if (line.isEmpty() || line.startsWith("#") || i < 0) continue;
                    String v = line.substring(i + 1).trim();
                    boolean quoted = v.length() >= 2 && ((v.startsWith("\"") && v.endsWith("\"")) || (v.startsWith("'") && v.endsWith("'")));
                    FILE.put(line.substring(0, i).trim(), quoted ? v.substring(1, v.length() - 1) : v);
                }
            } catch (IOException ignored) { }
        }
    }

    private Env() { }

    public static String get(String key, String fallback) {
        String v = System.getenv(key);
        if (v != null && !v.isEmpty()) return v;
        v = FILE.get(key);
        return v != null ? v : fallback;
    }
}
