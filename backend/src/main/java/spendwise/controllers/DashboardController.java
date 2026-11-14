package spendwise.controllers;

import java.util.Map;
import spendwise.models.DashboardModel;
import spendwise.util.Json;
import spendwise.util.Router.Req;
import spendwise.util.Router.Res;
import spendwise.util.Util;

public final class DashboardController {
    private DashboardController() { }

    public static Object get(Req req, Res res) throws Exception {
        @SuppressWarnings("unchecked")
        Map<String, Object> d = DashboardModel.getDashboard();
        @SuppressWarnings("unchecked")
        Map<String, Object> t = (Map<String, Object>) d.get("totals"), m = (Map<String, Object>) d.get("month");
        double ti = Util.num(t.get("income")), te = Util.num(t.get("expenses")), mi = Util.num(m.get("income")), me = Util.num(m.get("expenses"));
        return Json.obj(
            "success", true,
            "totals", Json.obj("income", ti, "expenses", te, "balance", ti - te),
            "month", Json.obj("income", mi, "expenses", me, "savings", mi - me, "budget", m.get("budget")),
            "categories", d.get("categories"), "recent", d.get("recent"), "weekly", d.get("weekly"), "monthly", d.get("monthly"));
    }
}
