package spendwise.controllers;

import static spendwise.util.HttpError.fail;

import java.util.List;
import java.util.Map;
import spendwise.models.GoalModel;
import spendwise.util.Json;
import spendwise.util.Router.Req;
import spendwise.util.Router.Res;
import spendwise.util.Util;

public final class GoalController {
    private GoalController() { }

    public static Object list(Req req, Res res) throws Exception {
        List<Map<String, Object>> rows = GoalModel.list();
        for (Map<String, Object> g : rows) {
            double target = Util.num(g.get("target_amount")), saved = Util.num(g.get("saved"));
            g.put("remaining", Util.round2(Math.max(target - saved, 0)));
            g.put("percent", Math.min(Util.round2(saved / target * 100), 100));
        }
        return Json.obj("success", true, "data", rows);
    }

    public static Object create(Req req, Res res) throws Exception {
        Map<String, Object> b = req.bodyMap();
        String name = Util.str(b.get("name")).trim(), deadline = Util.str(b.get("deadline"));
        if (name.isEmpty()) throw fail(400, "Goal name is required.");
        if (!(Util.num(b.get("target_amount")) > 0)) throw fail(400, "Target must be greater than zero.");
        if (!deadline.isEmpty() && !Util.validDate(deadline)) throw fail(400, "Deadline is not a valid date.");
        GoalModel.create(name, Util.num(b.get("target_amount")), deadline.isEmpty() ? null : deadline);
        res.status = 201;
        return Json.obj("success", true, "message", "Goal created.");
    }

    public static Object contribute(Req req, Res res) throws Exception {
        double amount = Util.num(req.bodyMap().get("amount"));
        String id = req.params.get("id");
        Map<String, Object> goal = GoalModel.find(id);
        if (goal == null) throw fail(404, "Goal not found.");
        if (!(amount > 0)) throw fail(400, "Amount must be greater than zero.");
        double left = Util.round2(Util.num(goal.get("target_amount")) - Util.num(goal.get("saved")));
        if (amount > left) throw fail(400, left > 0 ? "Only ₹" + Util.fmt2(left) + " is left to reach this goal." : "This goal is already complete.");
        GoalModel.contribute(id, amount);
        res.status = 201;
        return Json.obj("success", true, "message", "Contribution added.");
    }

    public static Object remove(Req req, Res res) throws Exception {
        if (GoalModel.remove(req.params.get("id")) == 0) throw fail(404, "Goal not found.");
        return Json.obj("success", true, "message", "Goal deleted.");
    }
}
