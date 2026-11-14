package spendwise.controllers;

import static spendwise.util.HttpError.fail;

import java.util.List;
import java.util.Map;
import spendwise.models.ChallengeModel;
import spendwise.util.Json;
import spendwise.util.Router.Req;
import spendwise.util.Router.Res;
import spendwise.util.Util;

public final class ChallengeController {
    private static final Map<String, Integer> DAYS = Map.of("7-day", 7, "30-day", 30);

    private ChallengeController() { }

    public static Object list(Req req, Res res) throws Exception {
        List<Map<String, Object>> rows = ChallengeModel.listWithProgress();
        for (Map<String, Object> c : rows) {   // No-spend rule: any "unnecessary" spending fails the challenge
            boolean finished = Util.num(c.get("finished")) != 0;
            String status = Util.num(c.get("unnecessary")) > 0 ? "failed" : finished ? "completed" : "active";
            if (!status.equals(c.get("status"))) { ChallengeModel.setStatus(c.get("id"), status); c.put("status", status); }
            c.put("percent", Math.min(Math.round(Util.num(c.get("days_done")) / Util.num(c.get("total_days")) * 100), 100));
        }
        return Json.obj("success", true, "unnecessary_categories", List.of("Shopping", "Entertainment", "Others"), "data", rows);
    }

    public static Object create(Req req, Res res) throws Exception {
        String type = Util.str(req.bodyMap().get("type"));
        Integer days = DAYS.get(type);
        if (days == null) throw fail(400, "Choose a 7-day or 30-day challenge.");
        if (ChallengeModel.hasActive()) throw fail(409, "You already have an active challenge. Finish or delete it first.");
        ChallengeModel.create(type, days);
        res.status = 201;
        return Json.obj("success", true, "message", type + " no-spend challenge started. Good luck!");
    }

    public static Object remove(Req req, Res res) throws Exception {
        if (ChallengeModel.remove(req.params.get("id")) == 0) throw fail(404, "Challenge not found.");
        return Json.obj("success", true, "message", "Challenge removed.");
    }
}
