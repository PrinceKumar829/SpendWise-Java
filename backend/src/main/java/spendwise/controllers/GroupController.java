package spendwise.controllers;

import static spendwise.util.HttpError.fail;

import java.util.LinkedHashMap;
import java.util.Map;
import spendwise.models.GroupModel;
import spendwise.services.GroupService;
import spendwise.util.Json;
import spendwise.util.Router.Req;
import spendwise.util.Router.Res;

public final class GroupController {
    private GroupController() { }

    public static Object list(Req req, Res res) throws Exception {
        return Json.obj("success", true, "data", GroupModel.list());
    }

    public static Object detail(Req req, Res res) throws Exception {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("success", true);
        out.putAll(GroupService.getDetail(req.params.get("id")));
        return out;
    }

    public static Object create(Req req, Res res) throws Exception {
        Map<String, Object> b = req.bodyMap();
        long id = GroupService.createGroup(b.get("name"), b.get("members"));
        res.status = 201;
        return Json.obj("success", true, "id", id, "message", "Group created.");
    }

    public static Object remove(Req req, Res res) throws Exception {
        if (GroupModel.remove(req.params.get("id")) == 0) throw fail(404, "Group not found.");
        return Json.obj("success", true, "message", "Group deleted.");
    }

    public static Object addMember(Req req, Res res) throws Exception {
        GroupService.addMember(req.params.get("id"), req.bodyMap().get("name"));
        res.status = 201;
        return Json.obj("success", true, "message", "Member added.");
    }

    public static Object addExpense(Req req, Res res) throws Exception {
        GroupService.addSharedExpense(req.params.get("id"), req.bodyMap());
        res.status = 201;
        return Json.obj("success", true, "message", "Shared expense recorded.");
    }

    public static Object addSettlement(Req req, Res res) throws Exception {
        GroupService.addSettlement(req.params.get("id"), req.bodyMap());
        res.status = 201;
        return Json.obj("success", true, "message", "Settlement recorded.");
    }
}
