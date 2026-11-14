package spendwise;

import java.util.List;
import java.util.Map;
import spendwise.util.Json;
import spendwise.util.Util;

public class JsonTest {
    @SuppressWarnings("unchecked")
    public static void main(String[] args) {
        Map<String, Object> m = (Map<String, Object>) Json.parse("{\"a\": 1.5, \"b\": [1, \"x\\n\\\"q\\\"\", null, true], \"c\": {\"d\": \"₹\\u20b9\"}}");
        Check.eq(1.5, m.get("a"), "number");
        Check.eq("x\n\"q\"", ((List<Object>) m.get("b")).get(1), "escapes");
        Check.eq("₹₹", ((Map<String, Object>) m.get("c")).get("d"), "unicode");
        Check.eq("{\"n\":150,\"f\":94.4,\"s\":\"a\\\"b\",\"l\":[1,null],\"z\":null}",
            Json.stringify(Json.obj("n", 150.0, "f", 94.4, "s", "a\"b", "l", java.util.Arrays.asList(1L, null), "z", null)), "stringify");
        boolean threw = false;
        try { Json.parse("{bad"); } catch (IllegalArgumentException e) { threw = true; }
        Check.ok(threw, "bad JSON rejected");
        // JavaScript-like number handling
        Check.eq(0.0, Util.num(""), "empty text is 0");
        Check.ok(Double.isNaN(Util.num("12abc")), "bad number is NaN");
        Check.eq(150.5, Util.num("150.50"), "numeric text");
        Check.eq("94.4", Util.jsNum(94.4), "jsNum decimal");
        Check.eq("150", Util.jsNum(150.0), "jsNum whole");
        Check.ok(Util.validDate("2026-11-03") && !Util.validDate("2026-02-30") && !Util.validDate("03/11/2026"), "dates");
        Check.ok(Util.monthOk("2026-11") && !Util.monthOk("2026-13"), "months");
        Check.done("JsonTest");
    }
}
