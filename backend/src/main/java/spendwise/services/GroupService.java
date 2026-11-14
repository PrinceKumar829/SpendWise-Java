package spendwise.services;

import static spendwise.util.HttpError.fail;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import spendwise.config.Db;
import spendwise.models.GroupModel;
import spendwise.services.SplitService.Balance;
import spendwise.services.SplitService.Share;
import spendwise.util.Util;

/** Business rules + transactions for groups. */
public final class GroupService {
    private static final Pattern TOKEN = Pattern.compile("^[\\w-]{8,64}$");

    private GroupService() { }

    public static long createGroup(Object nameValue, Object membersValue) throws Exception {
        String name = Util.str(nameValue).trim();
        Set<String> unique = new LinkedHashSet<>();
        if (membersValue instanceof List<?> list) {
            for (Object o : list) {
                String s = Util.str(o).trim();
                if (!s.isEmpty()) unique.add(s);
            }
        }
        List<String> names = new ArrayList<>(unique);
        if (name.isEmpty()) throw fail(400, "Group name is required.");
        if (names.size() < 2) throw fail(400, "A group needs at least 2 different members.");
        // one transaction: the group and all members are saved together or not at all
        return Db.tx(c -> {
            long id = GroupModel.insertGroup(name, c);
            for (String n : names) GroupModel.addMember(id, n, c);
            return id;
        });
    }

    public static void addMember(String groupId, Object nameValue) throws Exception {
        String name = Util.str(nameValue).trim();
        if (name.isEmpty()) throw fail(400, "Member name is required.");
        if (GroupModel.find(groupId, null) == null) throw fail(404, "Group not found.");
        try {
            GroupModel.addMember(groupId, name, null);
        } catch (SQLException e) {
            if (e.getErrorCode() == 1062) throw fail(409, "That member is already in the group.");   // duplicate name
            throw e;
        }
    }

    private static List<Balance> balances(String groupId, Connection c) throws Exception {
        List<Balance> out = new ArrayList<>();
        for (Map<String, Object> r : GroupModel.balanceRows(groupId, c))
            out.add(new Balance(Util.toId(r.get("id")), (String) r.get("name"), Util.num(r.get("paid")), Util.num(r.get("owed")), SplitService.netBalance(r)));
        return out;
    }

    public static Map<String, Object> getDetail(String groupId) throws Exception {
        Map<String, Object> group = GroupModel.find(groupId, null);
        if (group == null) throw fail(404, "Group not found.");
        List<Balance> bal = balances(groupId, null);
        List<Map<String, Object>> balOut = new ArrayList<>();
        for (Balance b : bal) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", b.id);
            m.put("name", b.name);
            m.put("paid", b.paid);
            m.put("owed", b.owed);
            m.put("net", SplitService.toRupees(b.net));
            balOut.add(m);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("group", group);
        out.put("members", GroupModel.members(groupId, null));
        out.put("balances", balOut);
        out.put("debts", SplitService.simplifyDebts(bal));
        out.put("expenses", GroupModel.expenseHistory(groupId));
        out.put("settlements", GroupModel.settlementHistory(groupId));
        return out;
    }

    public static void addSharedExpense(String groupId, Map<String, Object> b) throws Exception {
        List<Map<String, Object>> members = GroupModel.members(groupId, null);
        if (members.isEmpty()) throw fail(404, "Group not found.");
        List<Long> ids = new ArrayList<>();
        for (Map<String, Object> m : members) ids.add(Util.toId(m.get("id")));

        long total = SplitService.toPaise(Util.num(b.get("amount")));
        String description = Util.str(b.get("description")).trim();
        Long paidBy = Util.toId(b.get("paid_by"));
        String splitType = Util.str(b.get("split_type"));
        String date = Util.str(b.get("date"));
        if (description.isEmpty()) throw fail(400, "Description is required.");
        if (!(total > 0)) throw fail(400, "Amount must be greater than zero.");
        if (paidBy == null || !ids.contains(paidBy)) throw fail(400, "Choose who paid.");
        if (!Util.validDate(date)) throw fail(400, "Choose a valid date.");
        if (!splitType.equals("equal") && !splitType.equals("custom")) throw fail(400, "Invalid split type.");
        if (!(b.get("participants") instanceof List<?> participants) || participants.isEmpty()) throw fail(400, "Select at least one participant.");

        List<Share> shares = new ArrayList<>();
        if (splitType.equals("equal")) {
            Set<Long> pids = new LinkedHashSet<>();
            for (Object o : participants) {
                Long id = Util.toId(o);
                if (id == null || !ids.contains(id)) throw fail(400, "Participants must be group members.");
                pids.add(id);
            }
            shares = SplitService.equalSplit(total, new ArrayList<>(pids));
        } else {
            Set<Long> seen = new LinkedHashSet<>();
            for (Object o : participants) {
                Map<?, ?> p = (o instanceof Map<?, ?> mm) ? mm : Map.of();
                Long id = Util.toId(p.get("member_id"));
                shares.add(new Share(id == null ? -1 : id, SplitService.toPaise(Util.num(p.get("amount")))));
            }
            for (Share s : shares) if (!seen.add(s.memberId())) throw fail(400, "A member appears twice.");
            for (Share s : shares) if (!ids.contains(s.memberId()) || s.paise() <= 0) throw fail(400, "Every custom amount must be above zero.");
            String err = SplitService.checkCustomSplit(total, shares);
            if (err != null) throw fail(400, err);
        }

        final List<Share> finalShares = shares;
        // one transaction: the bill and every participant row are saved together or not at all
        Db.tx(c -> {
            long id = GroupModel.insertExpense(groupId, paidBy, total / 100.0, description, splitType, date, c);
            List<Object[]> rows = new ArrayList<>();
            for (Share s : finalShares) rows.add(new Object[] { id, s.memberId(), s.paise() / 100.0 });
            GroupModel.insertParticipants(rows, c);
            return id;
        });
    }

    // Settlement rules:
    //  1. the group row is locked, so two simultaneous requests are handled one at a time
    //  2. the amount cannot exceed what the payer owes AND what the receiver is owed
    //  3. request_token is UNIQUE, so the same request cannot be saved twice
    public static void addSettlement(String groupId, Map<String, Object> b) throws Exception {
        Long from = Util.toId(b.get("from")), to = Util.toId(b.get("to"));
        long paise = SplitService.toPaise(Util.num(b.get("amount")));
        String date = Util.str(b.get("date")), token = Util.str(b.get("token"));
        if (from != null && from.equals(to)) throw fail(400, "Payer and receiver must be different people.");
        if (!(paise > 0)) throw fail(400, "Amount must be greater than zero.");
        if (!Util.validDate(date)) throw fail(400, "Choose a valid date.");
        if (!TOKEN.matcher(token).matches()) throw fail(400, "Missing request token.");
        try {
            Db.tx(c -> {
                GroupModel.lockGroup(groupId, c);
                Balance a = null, r = null;
                for (Balance x : balances(groupId, c)) {
                    if (from != null && x.id == from) a = x;
                    if (to != null && x.id == to) r = x;
                }
                if (a == null || r == null) throw fail(400, "Both people must be members of this group.");
                long max = Math.min(-a.net, r.net);
                if (max <= 0) throw fail(400, a.name + " does not owe " + r.name + " anything right now.");
                if (paise > max) throw fail(400, a.name + " only owes " + r.name + " up to ₹" + Util.fmt2(SplitService.toRupees(max)) + ".");
                GroupModel.insertSettlement(groupId, from, to, paise / 100.0, date, token, c);
                return null;
            });
        } catch (SQLException e) {
            if (e.getErrorCode() == 1062) throw fail(409, "This settlement was already recorded.");   // same token twice
            throw e;
        }
    }
}
