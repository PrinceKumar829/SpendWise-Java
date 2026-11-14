package spendwise.controllers;

import java.util.LinkedHashMap;
import java.util.Map;
import spendwise.services.InsightService;
import spendwise.util.Router.Req;
import spendwise.util.Router.Res;

public final class InsightController {
    private InsightController() { }

    public static Object get(Req req, Res res) throws Exception {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("success", true);
        out.putAll(InsightService.getInsights());
        return out;
    }
}
