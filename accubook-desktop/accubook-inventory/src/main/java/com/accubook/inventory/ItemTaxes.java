package com.accubook.inventory;

import com.accubook.platform.money.MoneyMapper;
import com.accubook.platform.money.Scale;
import com.accubook.tax.TaxCategory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Reads of {@code item_tax}, shared by the use cases and the stock port. */
final class ItemTaxes {

    private static final String SELECT = "SELECT t.effective_from, t.category, t.gst_rate_e4, t.cess_rate_e4, "
            + "i.hsn_sac, t.rule_pack_version FROM item_tax t JOIN item i ON i.id = t.item_id WHERE t.item_id = ? ";

    private ItemTaxes() {
    }

    static Optional<ItemTaxRate> on(Connection connection, String itemId, LocalDate date) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(SELECT
                + "AND t.effective_from <= ? ORDER BY t.effective_from DESC LIMIT 1")) {
            ps.setString(1, itemId);
            ps.setString(2, date.toString());
            try (ResultSet rows = ps.executeQuery()) {
                return rows.next() ? Optional.of(map(rows)) : Optional.empty();
            }
        }
    }

    static List<ItemTaxRate> history(Connection connection, String itemId) throws SQLException {
        List<ItemTaxRate> result = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(SELECT + "ORDER BY t.effective_from")) {
            ps.setString(1, itemId);
            try (ResultSet rows = ps.executeQuery()) {
                while (rows.next()) {
                    result.add(map(rows));
                }
            }
        }
        return result;
    }

    private static ItemTaxRate map(ResultSet rows) throws SQLException {
        return new ItemTaxRate(LocalDate.parse(rows.getString(1)), TaxCategory.valueOf(rows.getString(2)),
                MoneyMapper.fromStored(rows.getLong(3), Scale.TAX_RATE).stripTrailingZeros(),
                MoneyMapper.fromStored(rows.getLong(4), Scale.TAX_RATE).stripTrailingZeros(),
                rows.getString(5), rows.getString(6));
    }
}
