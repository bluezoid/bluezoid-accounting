package com.accubook.accounting.report;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** The chart of accounts as the reports read it: groups with their ancestry, and ledgers. */
final class Chart {

    record Group(String id, String parentId, String code, String name, String nature, boolean grossProfit,
                 int sequence) {
    }

    record Ledger(String id, String name, String groupId, String code) {
    }

    final Map<String, Group> groups = new HashMap<>();
    final Map<String, Ledger> ledgers = new HashMap<>();

    static Chart load(Connection c) throws SQLException {
        Chart chart = new Chart();
        try (PreparedStatement ps = c.prepareStatement("SELECT id, parent_id, system_code, name, nature, "
                + "affects_gross_profit, sequence FROM ledger_group"); ResultSet r = ps.executeQuery()) {
            while (r.next()) {
                chart.groups.put(r.getString(1), new Group(r.getString(1), r.getString(2), r.getString(3),
                        r.getString(4), r.getString(5), r.getInt(6) == 1, r.getInt(7)));
            }
        }
        try (PreparedStatement ps = c.prepareStatement("SELECT id, name, group_id, system_code FROM ledger");
             ResultSet r = ps.executeQuery()) {
            while (r.next()) {
                chart.ledgers.put(r.getString(1), new Ledger(r.getString(1), r.getString(2), r.getString(3),
                        r.getString(4)));
            }
        }
        return chart;
    }

    /** The group and its ancestors, nearest first. */
    List<Group> lineage(String groupId) {
        List<Group> result = new ArrayList<>();
        Group g = groups.get(groupId);
        while (g != null && result.size() < 64) {
            result.add(g);
            g = g.parentId() == null ? null : groups.get(g.parentId());
        }
        return result;
    }

    boolean under(String groupId, String systemCode) {
        return lineage(groupId).stream().anyMatch(g -> systemCode.equals(g.code()));
    }

    /** The top-level group's name under the root of its nature, for headings. */
    Group heading(String groupId) {
        List<Group> lineage = lineage(groupId);
        return lineage.size() >= 2 ? lineage.get(lineage.size() - 2) : lineage.get(0);
    }

    Group groupOf(String ledgerId) {
        return groups.get(ledgers.get(ledgerId).groupId());
    }
}
