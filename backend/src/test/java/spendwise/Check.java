package spendwise;

/** Tiny assertion helper (no test library needed). */
public final class Check {
    public static int passed = 0;

    private Check() { }

    public static void ok(boolean cond, String what) {
        if (!cond) throw new AssertionError("FAILED: " + what);
        passed++;
    }

    public static void eq(Object expected, Object actual, String what) {
        if (!String.valueOf(expected).equals(String.valueOf(actual)))
            throw new AssertionError("FAILED: " + what + " | expected <" + expected + "> but was <" + actual + ">");
        passed++;
    }

    public static void done(String name) { System.out.println(name + ": all " + passed + " checks passed"); }
}
