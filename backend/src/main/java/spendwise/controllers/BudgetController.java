package spendwise.controllers;

import static spendwise.util.HttpError.fail;

import java.util.List;
import java.util.Map;
import spendwise.models.BudgetModel;
import spendwise.util.Json;
import spendwise.util.Router.Req;
import spendwise.util.Router.Res;
import spendwise.util.Util;

public final class BudgetController {
    private static final List<String> CATS = List.of("Overall", "Food", "Shopping", "Transportation", "Education", "Entertainment", "Health", "Bills", "Others");

    private BudgetController() { }

    // status: ok (<80%), warning (80-99%), exceeded (100%+)
    public static String statusOf(double spent, double amount) {
        double p = spent / amount * 100;
        return p >= 100 ? "exceeded" : p >= 80 ? "warning" : "ok";
    }

    public static Object list(Req req, Res res) throws Exception {
        String month = req.query.get("month");
        if (!Util.monthOk(month)) throw fail(400, "Month must look like 2026-11.");
        List<Map<String, Object>> rows = BudgetModel.listWithSpent(month);
        for (Map<String, Object> r : rows) {
            double spent = Util.num(r.get("spent")), amount = Util.num(r.get("amount"));
            r.put("remaining", Util.round2(amount - spent));
            r.put("percent", Util.round2(spent / amount * 100));
            r.put("status", statusOf(spent, amount));
        }
        return Json.obj("success", true, "data", rows);
    }

    public static Object save(Req req, Res res) throws Exception {
        Map<String, Object> b = req.bodyMap();
        String month = Util.str(b.get("month"));
        if (!Util.monthOk(month)) throw fail(400, "Choose a valid month.");
        if (!(b.get("category") instanceof String category) || !CATS.contains(category)) throw fail(400, "Choose a valid category.");
        if (!(Util.num(b.get("amount")) > 0)) throw fail(400, "Budget must be greater than zero.");
        BudgetModel.upsert(month, category, Util.num(b.get("amount")));
        res.status = 201;
        return Json.obj("success", true, "message", "Budget saved.");
    }

    public static Object remove(Req req, Res res) throws Exception {
        if (BudgetModel.remove(req.params.get("id")) == 0) throw fail(404, "Budget not found.");
        return Json.obj("success", true, "message", "Budget deleted.");
    }
}
