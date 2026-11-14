package spendwise;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import spendwise.services.SplitService;
import spendwise.services.SplitService.Balance;
import spendwise.services.SplitService.Share;

/** Same checks as splitService.test.js. Run: java -cp out:testout spendwise.SplitServiceTest */
public class SplitServiceTest {
    static Balance bal(long id, String name, double paid, double owed, double sp, double sr) {
        Map<String, Object> row = Map.of("paid", paid, "owed", owed, "settled_paid", sp, "settled_received", sr);
        return new Balance(id, name, paid, owed, SplitService.netBalance(row));
    }

    public static void main(String[] args) {
        // Dinner example from the brief: Rs 1500 between 3, Asha pays everything
        List<Share> split = SplitService.equalSplit(150000, List.of(1L, 2L, 3L));
        for (Share s : split) Check.eq(50000, s.paise(), "each share is Rs 500");

        // Uneven amounts never lose a paisa
        long sum = 0;
        for (Share s : SplitService.equalSplit(10000, List.of(1L, 2L, 3L))) sum += s.paise();
        Check.eq(10000, sum, "uneven split adds up");

        List<Balance> rows = new ArrayList<>(List.of(bal(1, "Asha", 1500, 500, 0, 0), bal(2, "Ravi", 0, 500, 0, 0), bal(3, "Meera", 0, 500, 0, 0)));
        List<Map<String, Object>> debts = SplitService.simplifyDebts(rows);
        Check.eq(2, debts.size(), "two debts");
        for (Map<String, Object> d : debts) { Check.eq(1L, d.get("to"), "debt goes to the payer"); Check.eq(500.0, d.get("amount"), "debt is Rs 500"); }

        // After Ravi settles, only Meera still owes
        rows.get(1).net = 0;
        rows.get(0).net = 50000;
        debts = SplitService.simplifyDebts(rows);
        Check.eq(1, debts.size(), "one debt left");
        Check.eq(3L, debts.get(0).get("from"), "Meera still owes");

        Check.ok(SplitService.checkCustomSplit(10000, List.of(new Share(1, 4000), new Share(2, 5000))) != null, "40+50 for 100 rejected");
        Check.ok(SplitService.checkCustomSplit(10000, List.of(new Share(1, 4000), new Share(2, 6000))) == null, "40+60 for 100 accepted");
        Check.done("SplitServiceTest");
    }
}
