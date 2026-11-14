package spendwise.services;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import spendwise.models.BudgetModel;
import spendwise.models.GoalModel;
import spendwise.models.InsightModel;
import spendwise.util.Util;

/** Smart Spending Detective: plain rule-based analysis (no AI / machine learning). */
public final class InsightService {
    private static final Map<String, String> DISCRETIONARY = new LinkedHashMap<>();
    private static final List<String> DAYS = Arrays.asList("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat");

    static {
        DISCRETIONARY.put("Food", "cook or carry lunch two more days a week");
        DISCRETIONARY.put("Shopping", "wait 48 hours before any non-essential purchase");
        DISCRETIONARY.put("Entertainment", "keep one paid outing or subscription a week");
        DISCRETIONARY.put("Others", "review these entries and re-categorise or cut them");
    }

    private InsightService() { }

    private static double clamp(double n, double lo, double hi) { return Math.min(Math.max(n, lo), hi); }
    private static String n(double d) { return Util.jsNum(d); }
    private static double sum(Map<String, Double> m) { double s = 0; for (double v : m.values()) s += v; return s; }

    /** Pure function: takes data, returns the analysis. Easy to test (budget may be null). */
    public static Map<String, Object> analyse(Map<String, Object> months, List<Map<String, Object>> cat, List<Map<String, Object>> freq,
                                              List<Map<String, Object>> wd, Map<String, Object> money, Map<String, Object> budget,
                                              List<Map<String, Object>> goals) {
        Map<String, Double> thisM = new LinkedHashMap<>(), lastM = new LinkedHashMap<>();
        for (Map<String, Object> r : cat)
            (String.valueOf(r.get("month")).equals(months.get("cur")) ? thisM : lastM).put((String) r.get("category"), Util.num(r.get("total")));
        double thisTotal = sum(thisM), lastTotal = sum(lastM);
        Double change = lastTotal > 0 ? Util.round1((thisTotal - lastTotal) / lastTotal * 100) : null;
        String topCat = null;
        double topVal = 0;
        for (Map.Entry<String, Double> e : thisM.entrySet()) if (topCat == null || e.getValue() > topVal) { topCat = e.getKey(); topVal = e.getValue(); }

        // Rule: a category is "unusual" if it rose by 30%+ and by at least Rs 200 (or is new and at least Rs 500)
        Set<String> allCats = new LinkedHashSet<>(thisM.keySet());
        allCats.addAll(lastM.keySet());
        List<Map<String, Object>> comparison = new ArrayList<>();
        for (String c : allCats) {
            double t = thisM.getOrDefault(c, 0.0), l = lastM.getOrDefault(c, 0.0);
            boolean unusual = t - l >= 200 && (l == 0 ? t >= 500 : (t - l) / l >= 0.3);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("category", c);
            m.put("this_month", t);
            m.put("last_month", l);
            m.put("increase_pct", l != 0 ? (Object) Util.round1((t - l) / l * 100) : null);
            m.put("unusual", unusual);
            comparison.add(m);
        }
        comparison.sort((a, b) -> Double.compare(Util.num(b.get("this_month")), Util.num(a.get("this_month"))));
        List<Map<String, Object>> unusualList = new ArrayList<>();
        for (Map<String, Object> m : comparison) if ((Boolean) m.get("unusual")) unusualList.add(m);

        double income = Util.num(money.get("income")), expenses = Util.num(money.get("expenses"));
        Double savingsRate = income > 0 ? (income - expenses) / income * 100 : null;

        // ----- Suggestions from actual data -----
        List<String> suggestions = new ArrayList<>();
        if (topCat != null) suggestions.add(topCat + " is your biggest category this month: " + n(Util.round1(topVal / thisTotal * 100)) + "% of your spending.");
        for (Map<String, Object> u : unusualList) {
            Object inc = u.get("increase_pct");
            suggestions.add(u.get("category") + " jumped " + (inc == null ? "from nothing" : n((Double) inc) + "%") + " (₹" + n(Util.num(u.get("last_month")))
                + " to ₹" + n(Util.num(u.get("this_month"))) + "). Try keeping it near last month's level.");
        }
        if (change != null && change > 0) suggestions.add("Spending so far this month is " + n(change) + "% above all of last month. Slow down before month end.");
        if (savingsRate != null && savingsRate < 20) suggestions.add("You are saving " + n(Util.round1(savingsRate)) + "% of this month's income. A common target is 20%.");
        if (budget == null) suggestions.add("You have no overall budget for this month. Set one on the Budgets page.");
        else if (Util.num(budget.get("spent")) >= Util.num(budget.get("amount")) * 0.8)
            suggestions.add("You have used " + n(Util.round1(Util.num(budget.get("spent")) / Util.num(budget.get("amount")) * 100)) + "% of your monthly budget.");
        if (!freq.isEmpty())
            suggestions.add(freq.get(0).get("category") + " is your most frequent category (" + n(Util.num(freq.get(0).get("entries"))) + " entries in 90 days). Small repeat purchases add up.");
        if (suggestions.isEmpty()) suggestions.add("Add some expenses and income to get personalised suggestions.");

        // ----- Smart savings recommendations: aim to trim discretionary categories by 15% -----
        Map<String, Double> base = thisTotal > 0 ? thisM : lastM;
        List<Map<String, Object>> recommendations = new ArrayList<>();
        long potential = 0;
        for (Map.Entry<String, String> e : DISCRETIONARY.entrySet()) {
            double cur = base.getOrDefault(e.getKey(), 0.0);
            if (cur > 0) {
                long cut = Math.round(cur * 0.15);
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("category", e.getKey());
                m.put("current", cur);
                m.put("suggested_cut", cut);
                m.put("tip", e.getValue());
                recommendations.add(m);
                potential += cut;
            }
        }

        // ----- Financial health score (0-100) -----
        List<Map<String, Object>> parts = new ArrayList<>();
        Double pct = budget != null ? Util.num(budget.get("spent")) / Util.num(budget.get("amount")) * 100 : null;   // 1. Budget usage: 40 points
        double bp = pct == null ? 20 : pct <= 80 ? 40 : pct <= 100 ? 40 - (pct - 80) : pct <= 130 ? 20 - (pct - 100) * 2 / 3 : 0;
        parts.add(part("Budget usage", bp, 40, pct == null ? "No budget set, so you get a neutral 20."
            : n(Util.round1(pct)) + "% of budget used. Full marks up to 80%, falling to 20 at 100%, and 0 at 130%."));
        double rateP = savingsRate == null ? 0 : clamp(savingsRate / 20, 0, 1) * 20;                                  // 2. Savings: 20 (rate) + 10 (goals)
        Double goalAvg = null;
        if (!goals.isEmpty()) {
            double s = 0;
            for (Map<String, Object> g : goals) s += Math.min(Util.num(g.get("saved")) / Util.num(g.get("target_amount")), 1);
            goalAvg = s / goals.size();
        }
        double goalP = goalAvg == null ? 5 : goalAvg * 10;
        parts.add(part("Savings progress", rateP + goalP, 30,
            "Savings rate " + (savingsRate == null ? "unknown (no income this month)" : n(Util.round1(savingsRate)) + "% (20 points at 20% or more)") + "; "
            + (goalAvg == null ? "no goals yet (neutral 5)" : n(Util.round1(goalAvg * 100)) + "% average goal progress (up to 10)") + "."));
        double pp = change == null ? 20 : clamp(30 * (1 - Math.max(change, 0) / 50), 0, 30);                          // 3. Patterns: 30 points
        double patP = Math.max(pp - unusualList.size() * 5, 0);
        parts.add(part("Spending pattern", patP, 30,
            (change == null ? "No last month to compare (neutral 20)" : "Spending " + (change <= 0 ? "is not above" : n(change) + "% above") + " last month (0 points at +50%)")
            + "; minus 5 for each unusual category (" + unusualList.size() + ")."));
        double pointSum = 0;
        for (Map<String, Object> p : parts) pointSum += Util.num(p.get("points"));
        long total = Math.round(pointSum);
        String label = total >= 80 ? "Excellent" : total >= 60 ? "Good" : total >= 40 ? "Fair" : "Needs attention";

        List<Map<String, Object>> weekday = new ArrayList<>();
        for (int i = 0; i < DAYS.size(); i++) {
            double t = 0;
            for (Map<String, Object> w : wd) if (Util.num(w.get("dow")) == i + 1) { t = Util.num(w.get("total")); break; }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("day", DAYS.get(i));
            m.put("total", t);
            weekday.add(m);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        if (topCat != null) { Map<String, Object> t = new LinkedHashMap<>(); t.put("category", topCat); t.put("total", topVal); out.put("top", t); }
        else out.put("top", null);
        out.put("this_total", thisTotal);
        out.put("last_total", lastTotal);
        out.put("change", change);
        out.put("comparison", comparison);
        out.put("unusual", unusualList);
        out.put("frequency", freq);
        out.put("weekday", weekday);
        out.put("suggestions", suggestions);
        out.put("recommendations", recommendations);
        out.put("potential_saving", potential);
        Map<String, Object> score = new LinkedHashMap<>();
        score.put("total", total);
        score.put("label", label);
        score.put("breakdown", parts);
        out.put("score", score);
        return out;
    }

    private static Map<String, Object> part(String name, double points, int max, String reason) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("points", Util.round1(points));
        m.put("max", max);
        m.put("reason", reason);
        return m;
    }

    public static Map<String, Object> getInsights() throws Exception {
        Map<String, Object> months = InsightModel.months();
        List<Map<String, Object>> budgets = BudgetModel.listWithSpent((String) months.get("cur"));
        Map<String, Object> budget = null;
        for (Map<String, Object> b : budgets) if ("Overall".equals(b.get("category"))) { budget = b; break; }
        return analyse(months, InsightModel.categoryByMonth(), InsightModel.frequency(), InsightModel.weekday(), InsightModel.money(), budget, GoalModel.list());
    }
}
