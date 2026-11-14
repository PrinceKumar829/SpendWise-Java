package spendwise;

import java.util.List;
import java.util.Map;
import spendwise.services.InsightService;

/** Same checks as insightService.test.js. */
public class InsightServiceTest {
    static Map<String, Object> cat(String month, String category, double total) { return Map.of("month", month, "category", category, "total", total); }

    @SuppressWarnings("unchecked")
    static double points(Map<String, Object> r, int i) {
        return (Double) ((List<Map<String, Object>>) ((Map<String, Object>) r.get("score")).get("breakdown")).get(i).get("points");
    }

    @SuppressWarnings("unchecked")
    public static void main(String[] args) {
        Map<String, Object> months = Map.of("cur", "2026-11", "prev", "2026-10");

        // Shopping jumps from 500 to 1500 -> unusual. Food 1700 -> 1800 is normal. Overall budget 10000, spent 3300 -> full budget points
        Map<String, Object> a = InsightService.analyse(months,
            List.of(cat("2026-10", "Shopping", 500), cat("2026-11", "Shopping", 1500), cat("2026-10", "Food", 1700), cat("2026-11", "Food", 1800)),
            List.of(), List.of(), Map.of("income", 15000.0, "expenses", 3300.0), Map.of("amount", 10000.0, "spent", 3300.0), List.of());
        Check.eq("Food", ((Map<String, Object>) a.get("top")).get("category"), "top category");
        List<Map<String, Object>> unusual = (List<Map<String, Object>>) a.get("unusual");
        Check.eq(1, unusual.size(), "one unusual category");
        Check.eq("Shopping", unusual.get(0).get("category"), "Shopping is unusual");
        Check.eq(40.0, points(a, 0), "budget points");
        long total = (Long) ((Map<String, Object>) a.get("score")).get("total");
        Check.ok(total >= 0 && total <= 100, "score within 0-100");

        // Budget exceeded (120%) lowers the budget points to about 6.7
        Map<String, Object> b = InsightService.analyse(months, List.of(), List.of(), List.of(), Map.of("income", 10000.0, "expenses", 12000.0),
            Map.of("amount", 10000.0, "spent", 12000.0), List.of());
        Check.ok(points(b, 0) < 10, "exceeded budget gives few points");

        // Empty database: no crash, neutral score
        Map<String, Object> e = InsightService.analyse(months, List.of(), List.of(), List.of(), Map.of("income", 0.0, "expenses", 0.0), null, List.of());
        Check.eq(1, ((List<String>) e.get("suggestions")).size(), "one prompt message");
        Check.ok((Long) ((Map<String, Object>) e.get("score")).get("total") <= 100, "empty score within range");

        // The numbers from the report screenshots: Food 150 this month, last month 2700 across four categories, budget 30000, no income
        Map<String, Object> s = InsightService.analyse(months,
            List.of(cat("2026-10", "Food", 250), cat("2026-10", "Education", 1200), cat("2026-10", "Entertainment", 450), cat("2026-10", "Shopping", 800), cat("2026-11", "Food", 150)),
            List.of(Map.of("category", "Food", "entries", 2L, "total", 400.0)), List.of(), Map.of("income", 0.0, "expenses", 150.0),
            Map.of("amount", 30000.0, "spent", 150.0), List.of(Map.of("saved", 0.0, "target_amount", 10000.0)));
        Check.eq(-94.4, s.get("change"), "change vs last month");
        Check.eq(70L, ((Map<String, Object>) s.get("score")).get("total"), "health score 70");
        Check.eq("Good", ((Map<String, Object>) s.get("score")).get("label"), "label Good");
        Check.eq(23L, s.get("potential_saving"), "15% of Rs 150 is Rs 23");
        Check.done("InsightServiceTest");
    }
}
