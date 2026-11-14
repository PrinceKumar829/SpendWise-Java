package spendwise;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import spendwise.config.Db;
import spendwise.config.Env;
import spendwise.routes.ApiRoutes;
import spendwise.util.Json;
import spendwise.util.Router;

/** Application entry point (equivalent of server.js). */
public class Server {

    /** Builds the router: health check plus all API routes. The frontend folder is served as static files. */
    public static Router buildApp() {
        Router app = new Router();

        // Health check: confirms the server and MySQL are both working
        app.get("/api/health", (req, res) -> {
            List<Map<String, Object>> rows = Db.query("SELECT 1 AS ok");
            Object ok = rows.get(0).get("ok");
            return Json.obj("success", true, "database", (ok instanceof Number n && n.intValue() == 1) ? "connected" : "error");
        });

        ApiRoutes.register(app);
        return app;
    }

    public static void main(String[] args) throws Exception {
        int port = Integer.parseInt(Env.get("PORT", "3000"));
        Router app = buildApp();
        HttpServer http = HttpServer.create(new InetSocketAddress(port), 0);
        http.createContext("/", app::handle);
        http.setExecutor(Executors.newFixedThreadPool(8));
        http.start();
        System.out.println("SpendWise running at http://localhost:" + port);
    }
}
