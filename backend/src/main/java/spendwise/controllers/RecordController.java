package spendwise.controllers;

import static spendwise.util.HttpError.fail;

import java.util.List;
import java.util.Map;
import spendwise.models.RecordModel;
import spendwise.models.RecordModel.Rec;
import spendwise.util.Json;
import spendwise.util.Router.Req;
import spendwise.util.Router.Res;
import spendwise.util.Util;

/** Controller = validation + calling the model + sending JSON. One class serves expenses and income. */
public class RecordController {
    private final RecordModel model;
    private final List<String> types;
    private final boolean descRequired;

    public RecordController(RecordModel model, List<String> types, boolean descRequired) {
        this.model = model;
        this.types = types;
        this.descRequired = descRequired;
    }

    // Returns an error message, or null if the input is valid.
    private String validate(Map<String, Object> b) {
        double amount = Util.num(b.get("amount"));
        if (!Double.isFinite(amount) || amount <= 0 || amount > 99999999) return "Amount must be a positive number.";
        if (!(b.get("type") instanceof String type) || !types.contains(type)) return "Type must be one of: " + String.join(", ", types) + ".";
        String desc = Util.str(b.get("description"));
        if (descRequired && desc.trim().isEmpty()) return "Description is required.";
        if (desc.length() > 255) return "Description is too long (max 255).";
        if (!Util.validDate(Util.str(b.get("date")))) return "Please choose a valid date.";
        return null;
    }

    private Rec clean(Map<String, Object> b) {
        return new Rec(Util.num(b.get("amount")), (String) b.get("type"), Util.str(b.get("description")).trim(), Util.str(b.get("date")));
    }

    public Object list(Req req, Res res) throws Exception {
        List<Map<String, Object>> data = model.findAll(req.query);
        double total = 0;
        for (Map<String, Object> r : data) total += Util.num(r.get("amount"));
        return Json.obj("success", true, "total", total, "count", data.size(), "data", data);
    }

    public Object create(Req req, Res res) throws Exception {
        String err = validate(req.bodyMap());
        if (err != null) throw fail(400, err);
        long id = model.insert(clean(req.bodyMap()));
        res.status = 201;
        return Json.obj("success", true, "id", id, "message", "Saved successfully.");
    }

    public Object update(Req req, Res res) throws Exception {
        String err = validate(req.bodyMap());
        if (err != null) throw fail(400, err);
        if (model.update(req.params.get("id"), clean(req.bodyMap())) == 0) throw fail(404, "Record not found.");
        return Json.obj("success", true, "message", "Updated successfully.");
    }

    public Object remove(Req req, Res res) throws Exception {
        if (model.remove(req.params.get("id")) == 0) throw fail(404, "Record not found.");
        return Json.obj("success", true, "message", "Deleted.");
    }

    public Object monthly(Req req, Res res) throws Exception {
        return Json.obj("success", true, "data", model.monthly());
    }
}
