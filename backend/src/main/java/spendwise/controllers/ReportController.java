package spendwise.controllers;

import static spendwise.util.HttpError.fail;

import spendwise.models.ReportModel;
import spendwise.services.ReportService;
import spendwise.util.Router.Req;
import spendwise.util.Router.Res;
import spendwise.util.Util;

public final class ReportController {
    private ReportController() { }

    public static Object monthly(Req req, Res res) throws Exception {
        String month = req.query.get("month");
        if (!Util.monthOk(month)) throw fail(400, "Month must look like 2026-11.");
        byte[] pdf = ReportService.buildPdf(month, ReportModel.monthData(month));
        res.headers.put("Content-Disposition", "attachment; filename=\"SpendWise-" + month + ".pdf\"");
        res.bytes("application/pdf", pdf);
        return null;
    }
}
