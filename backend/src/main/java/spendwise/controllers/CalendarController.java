package spendwise.controllers;

import static spendwise.util.HttpError.fail;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import spendwise.models.CalendarModel;
import spendwise.util.Json;
import spendwise.util.Router.Req;
import spendwise.util.Router.Res;
import spendwise.util.Util;

public final class CalendarController {
    private CalendarController() { }

    public static Object month(Req req, Res res) throws Exception {
        String month = req.query.get("month");
        if (!Util.monthOk(month)) throw fail(400, "Month must look like 2026-11.");
        List<List<Map<String, Object>>> data = CalendarModel.month(month);
        Map<String, Map<String, Object>> days = new LinkedHashMap<>();   // { '2026-11-03': { expenses, entries, income } }
        for (Map<String, Object> e : data.get(0)) days.put((String) e.get("day"), Json.obj("expenses", e.get("total"), "entries", e.get("entries"), "income", 0));
        for (Map<String, Object> i : data.get(1))
            days.computeIfAbsent((String) i.get("day"), k -> Json.obj("expenses", 0, "entries", 0, "income", 0)).put("income", i.get("total"));
        return Json.obj("success", true, "days", days);
    }
}
