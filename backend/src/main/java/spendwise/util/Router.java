package spendwise.util;

import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import spendwise.config.Env;

/**
 * A tiny version of Express: register routes with get/post/put/delete, then every request goes through handle().
 * It also serves the frontend folder as static files and sends JSON errors, like server.js did.
 */
public class Router {
    /** A controller function. Return a value to send it as JSON, or call res.bytes(...) for a file download. */
    public interface Handler { Object handle(Req req, Res res) throws Exception; }

    public static class Req {
        public String method;
        public String path;
        public Map<String, String> query = new HashMap<>();
        public Map<String, String> params = new HashMap<>();   // values of :id style parts
        public Object body;                                    // parsed JSON body (a Map for objects)

        /** The JSON body as a map (empty when there is no body). */
        @SuppressWarnings("unchecked")
        public Map<String, Object> bodyMap() {
            return body instanceof Map ? (Map<String, Object>) body : new HashMap<>();
        }
    }

    public static class Res {
        public int status = 200;
        public String contentType;
        public byte[] raw;
        public Map<String, String> headers = new LinkedHashMap<>();

        public void bytes(String type, byte[] data) { this.contentType = type; this.raw = data; }
    }

    private record Route(String method, String[] segs, Handler handler) { }

    private final List<Route> routes = new ArrayList<>();

    public void get(String path, Handler h) { routes.add(new Route("GET", split(path), h)); }
    public void post(String path, Handler h) { routes.add(new Route("POST", split(path), h)); }
    public void put(String path, Handler h) { routes.add(new Route("PUT", split(path), h)); }
    public void delete(String path, Handler h) { routes.add(new Route("DELETE", split(path), h)); }

    // ---------------- request handling ----------------
    public void handle(HttpExchange ex) throws IOException {
        try {
            ex.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
            ex.getResponseHeaders().set("Access-Control-Allow-Methods", "GET,POST,PUT,DELETE,OPTIONS");
            ex.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type");
            String method = ex.getRequestMethod();
            String path = ex.getRequestURI().getPath();
            if (method.equals("OPTIONS")) { ex.sendResponseHeaders(204, -1); return; }
            if (path.equals("/api") || path.startsWith("/api/")) api(ex, method, path);
            else staticFile(ex, method, path);
        } finally {
            ex.close();
        }
    }

    private void api(HttpExchange ex, String method, String path) throws IOException {
        try {
            String[] segs = split(path);
            Route found = null;
            Map<String, String> params = null;
            for (Route r : routes) {
                if (!r.method().equals(method)) continue;
                params = match(r.segs(), segs);
                if (params != null) { found = r; break; }
            }
            if (found == null) {   // same as app.use('/api', ...) 404 in server.js
                sendJson(ex, 404, Json.obj("success", false, "message", "API route not found"));
                return;
            }
            Req req = new Req();
            req.method = method;
            req.path = path;
            req.params = params;
            req.query = parseQuery(ex.getRequestURI().getRawQuery());
            if (method.equals("POST") || method.equals("PUT")) {
                String text = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                if (!text.isBlank()) {
                    try { req.body = Json.parse(text); }
                    catch (IllegalArgumentException e) { throw HttpError.fail(400, "Invalid JSON in request body."); }
                }
            }
            Res res = new Res();
            Object out = found.handler().handle(req, res);
            if (res.raw != null) send(ex, res.status, res.contentType, res.raw, res.headers);
            else sendJson(ex, res.status, out);
        } catch (HttpError e) {
            sendJson(ex, e.status, Json.obj("success", false, "message", e.getMessage()));
        } catch (Exception e) {   // central error handler (database errors end up here)
            e.printStackTrace();
            sendJson(ex, 500, Json.obj("success", false, "message", "Server error", "detail", String.valueOf(e.getMessage())));
        }
    }

    // ---------------- static files (the frontend folder) ----------------
    private void staticFile(HttpExchange ex, String method, String path) throws IOException {
        if (!method.equals("GET") && !method.equals("HEAD")) { send(ex, 404, "text/plain; charset=utf-8", "Not Found".getBytes(StandardCharsets.UTF_8), Map.of()); return; }
        Path base = Path.of(Env.get("FRONTEND_DIR", "frontend")).toAbsolutePath().normalize();
        Path file = base.resolve("." + (path.equals("/") ? "/index.html" : path)).normalize();
        if (Files.isDirectory(file)) file = file.resolve("index.html");
        if (!file.startsWith(base) || !Files.isRegularFile(file)) {
            send(ex, 404, "text/plain; charset=utf-8", "Not Found".getBytes(StandardCharsets.UTF_8), Map.of());
            return;
        }
        send(ex, 200, contentType(file.getFileName().toString()), Files.readAllBytes(file), Map.of());
    }

    private static String contentType(String name) {
        String n = name.toLowerCase();
        if (n.endsWith(".html")) return "text/html; charset=utf-8";
        if (n.endsWith(".css")) return "text/css; charset=utf-8";
        if (n.endsWith(".js")) return "application/javascript; charset=utf-8";
        if (n.endsWith(".json")) return "application/json; charset=utf-8";
        if (n.endsWith(".svg")) return "image/svg+xml";
        if (n.endsWith(".png")) return "image/png";
        if (n.endsWith(".jpg") || n.endsWith(".jpeg")) return "image/jpeg";
        if (n.endsWith(".ico")) return "image/x-icon";
        return "application/octet-stream";
    }

    // ---------------- helpers ----------------
    private static void sendJson(HttpExchange ex, int status, Object body) throws IOException {
        send(ex, status, "application/json; charset=utf-8", Json.stringify(body).getBytes(StandardCharsets.UTF_8), Map.of());
    }

    private static void send(HttpExchange ex, int status, String type, byte[] data, Map<String, String> headers) throws IOException {
        ex.getResponseHeaders().set("Content-Type", type);
        for (Map.Entry<String, String> h : headers.entrySet()) ex.getResponseHeaders().set(h.getKey(), h.getValue());
        boolean head = ex.getRequestMethod().equals("HEAD");
        ex.sendResponseHeaders(status, head ? -1 : data.length);
        if (!head) try (OutputStream os = ex.getResponseBody()) { os.write(data); }
    }

    /** "/api/groups/:id/members" -> ["api","groups",":id","members"] (trailing slashes are ignored). */
    private static String[] split(String path) {
        List<String> out = new ArrayList<>();
        for (String s : path.split("/")) if (!s.isEmpty()) out.add(s);
        return out.toArray(new String[0]);
    }

    private static Map<String, String> match(String[] pattern, String[] actual) {
        if (pattern.length != actual.length) return null;
        Map<String, String> params = new HashMap<>();
        for (int i = 0; i < pattern.length; i++) {
            if (pattern[i].startsWith(":")) params.put(pattern[i].substring(1), actual[i]);
            else if (!pattern[i].equals(actual[i])) return null;
        }
        return params;
    }

    private static Map<String, String> parseQuery(String raw) {
        Map<String, String> m = new HashMap<>();
        if (raw == null || raw.isEmpty()) return m;
        for (String pair : raw.split("&")) {
            if (pair.isEmpty()) continue;
            int i = pair.indexOf('=');
            String k = i < 0 ? pair : pair.substring(0, i);
            String v = i < 0 ? "" : pair.substring(i + 1);
            m.put(URLDecoder.decode(k, StandardCharsets.UTF_8), URLDecoder.decode(v, StandardCharsets.UTF_8));
        }
        return m;
    }
}
