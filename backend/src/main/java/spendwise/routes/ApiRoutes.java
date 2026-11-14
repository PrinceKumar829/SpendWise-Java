package spendwise.routes;

import java.util.List;
import spendwise.controllers.BudgetController;
import spendwise.controllers.CalendarController;
import spendwise.controllers.ChallengeController;
import spendwise.controllers.DashboardController;
import spendwise.controllers.GoalController;
import spendwise.controllers.GroupController;
import spendwise.controllers.InsightController;
import spendwise.controllers.RecordController;
import spendwise.controllers.ReportController;
import spendwise.models.RecordModel;
import spendwise.util.Router;

/** URL -> controller mapping. This one class replaces all the routes/*.js files of the Node version. */
public final class ApiRoutes {
    private ApiRoutes() { }

    public static void register(Router app) {
        // Expenses and income share the same code (RecordController); only the table and allowed types differ
        records(app, "/api/expenses", new RecordController(new RecordModel("expenses", "category", "expense_date"),
            List.of("Food", "Shopping", "Transportation", "Education", "Entertainment", "Health", "Bills", "Others"), true));
        records(app, "/api/income", new RecordController(new RecordModel("income", "source", "income_date"),
            List.of("Salary", "Pocket Money", "Freelance", "Scholarship", "Gift", "Others"), false));

        app.get("/api/dashboard", DashboardController::get);

        app.get("/api/groups", GroupController::list);
        app.post("/api/groups", GroupController::create);
        app.get("/api/groups/:id", GroupController::detail);
        app.delete("/api/groups/:id", GroupController::remove);
        app.post("/api/groups/:id/members", GroupController::addMember);
        app.post("/api/groups/:id/expenses", GroupController::addExpense);
        app.post("/api/groups/:id/settlements", GroupController::addSettlement);

        app.get("/api/budgets", BudgetController::list);
        app.post("/api/budgets", BudgetController::save);
        app.delete("/api/budgets/:id", BudgetController::remove);

        app.get("/api/goals", GoalController::list);
        app.post("/api/goals", GoalController::create);
        app.post("/api/goals/:id/contributions", GoalController::contribute);
        app.delete("/api/goals/:id", GoalController::remove);

        app.get("/api/insights", InsightController::get);

        app.get("/api/challenges", ChallengeController::list);
        app.post("/api/challenges", ChallengeController::create);
        app.delete("/api/challenges/:id", ChallengeController::remove);

        app.get("/api/calendar", CalendarController::month);
        app.get("/api/reports/monthly", ReportController::monthly);
    }

    private static void records(Router app, String base, RecordController c) {
        app.get(base, c::list);
        app.get(base + "/monthly", c::monthly);   // must come before '/:id'
        app.post(base, c::create);
        app.put(base + "/:id", c::update);
        app.delete(base + "/:id", c::remove);
    }
}
