package spendwise.services;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import spendwise.util.Util;

/**
 * Pure maths for splitting. No database here, so it is easy to test and explain.
 * All money is converted to paise (whole numbers) to avoid floating-point errors like 0.1 + 0.2.
 */
public final class SplitService {
    private SplitService() { }

    /** One member's share of a bill, in paise. */
    public record Share(long memberId, long paise) { }

    /** A member's money position. net > 0 means "is owed money", net < 0 means "owes money" (in paise). */
    public static final class Balance {
        public final long id;
        public final String name;
        public final double paid, owed;
        public long net;

        public Balance(long id, String name, double paid, double owed, long net) {
            this.id = id;
            this.name = name;
            this.paid = paid;
            this.owed = owed;
            this.net = net;
        }
    }

    public static long toPaise(double rupees) { return Math.round(rupees * 100); }
    public static double toRupees(long paise) { return paise / 100.0; }

    // Equal split: 1500 / 3 -> 500 each. 100 / 3 -> 33.34, 33.33, 33.33 (leftover paise go to the first people)
    public static List<Share> equalSplit(long totalPaise, List<Long> memberIds) {
        long base = totalPaise / memberIds.size();
        long leftover = totalPaise - base * memberIds.size();
        List<Share> out = new ArrayList<>();
        for (long id : memberIds) out.add(new Share(id, base + (leftover-- > 0 ? 1 : 0)));
        return out;
    }

    // Custom split: amounts must add up exactly to the total. Returns an error message or null.
    public static String checkCustomSplit(long totalPaise, List<Share> items) {
        long sum = 0;
        for (Share s : items) sum += s.paise();
        if (sum != totalPaise)
            return "Custom amounts add up to ₹" + Util.fmt2(toRupees(sum)) + " but the expense is ₹" + Util.fmt2(toRupees(totalPaise)) + ".";
        return null;
    }

    // net = paid - own share + settlements paid - settlements received
    public static long netBalance(Map<String, Object> row) {
        return toPaise(Util.num(row.get("paid"))) - toPaise(Util.num(row.get("owed")))
            + toPaise(Util.num(row.get("settled_paid"))) - toPaise(Util.num(row.get("settled_received")));
    }

    private static final class Entry {
        final long id;
        final String name;
        long net;

        Entry(long id, String name, long net) { this.id = id; this.name = name; this.net = net; }
    }

    // Who owes whom: repeatedly match the biggest debtor with the biggest creditor.
    public static List<Map<String, Object>> simplifyDebts(List<Balance> balances) {
        List<Entry> creditors = new ArrayList<>(), debtors = new ArrayList<>();
        for (Balance b : balances) {
            if (b.net > 0) creditors.add(new Entry(b.id, b.name, b.net));
            else if (b.net < 0) debtors.add(new Entry(b.id, b.name, -b.net));
        }
        creditors.sort((a, b) -> Long.compare(b.net, a.net));
        debtors.sort((a, b) -> Long.compare(b.net, a.net));

        List<Map<String, Object>> debts = new ArrayList<>();
        int i = 0, j = 0;
        while (i < debtors.size() && j < creditors.size()) {
            long pay = Math.min(debtors.get(i).net, creditors.get(j).net);
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("from", debtors.get(i).id);
            d.put("from_name", debtors.get(i).name);
            d.put("to", creditors.get(j).id);
            d.put("to_name", creditors.get(j).name);
            d.put("amount", toRupees(pay));
            debts.add(d);
            debtors.get(i).net -= pay;
            creditors.get(j).net -= pay;
            if (debtors.get(i).net == 0) i++;
            if (creditors.get(j).net == 0) j++;
        }
        return debts;
    }
}
