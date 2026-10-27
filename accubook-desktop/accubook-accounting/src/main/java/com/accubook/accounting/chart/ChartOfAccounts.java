package com.accubook.accounting.chart;

import com.accubook.module.ModuleTransactions;
import com.accubook.persistence.connection.Database;
import com.accubook.platform.id.Ids;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Reads and governs the chart of accounts, and computes the trial balance.
 *
 * <p>Owns the master tables - {@code ledger}, {@code ledger_group} - and nothing
 * else. It never writes {@code voucher} or {@code voucher_entry}: those belong
 * to {@code PostingService}, and there is no second path to them.
 */
public final class ChartOfAccounts {

    private final ModuleTransactions transactions;

    /** Over the guarded transactions the UI and modules are given; they hold no {@code Database}. */
    public ChartOfAccounts(ModuleTransactions transactions) {
        this.transactions = Objects.requireNonNull(transactions, "transactions");
    }

    /** For kernel code that does hold a {@code Database}. */
    public ChartOfAccounts(Database database) {
        this(over(Objects.requireNonNull(database, "database")));
    }

    private static ModuleTransactions over(Database database) {
        return new ModuleTransactions() {
            @Override
            public <T> T inTransaction(Work<T> work) throws SQLException {
                return database.inTransaction(work::execute);
            }

            @Override
            public <T> T read(Work<T> work) throws SQLException {
                try (Connection connection = database.openReadConnection()) {
                    return work.execute(connection);
                }
            }
        };
    }

    // ---------------------------------------------------------------- reads

    /** Resolve an account by its stable handle. Never by display name. */
    public Optional<LedgerAccount> findBySystemCode(String systemCode) {
        return queryOne("WHERE l.system_code = ?", systemCode);
    }

    public Optional<LedgerAccount> findById(String id) {
        return queryOne("WHERE l.id = ?", id);
    }

    /** Every account, active or not, ordered by name. */
    public List<LedgerAccount> allAccounts() {
        return read("read the chart of accounts", connection -> {
            List<LedgerAccount> accounts = new ArrayList<>();
            try (Statement statement = connection.createStatement();
                 ResultSet rows = statement.executeQuery(baseSelect() + " ORDER BY l.name")) {
                while (rows.next()) {
                    accounts.add(mapAccount(rows));
                }
            }
            return accounts;
        });
    }

    /** Every group, roots first, then in the order the chart lists them. */
    public List<LedgerGroup> groups() {
        return read("read the ledger groups", connection -> {
            List<LedgerGroup> groups = new ArrayList<>();
            try (Statement statement = connection.createStatement();
                 ResultSet rows = statement.executeQuery("SELECT id, parent_id, system_code, name, nature, is_system "
                         + "FROM ledger_group ORDER BY parent_id IS NOT NULL, sequence, name")) {
                while (rows.next()) {
                    groups.add(new LedgerGroup(rows.getString(1), rows.getString(2), rows.getString(3),
                            rows.getString(4), AccountNature.valueOf(rows.getString(5)), rows.getInt(6) == 1));
                }
            }
            return groups;
        });
    }

    // ------------------------------------------------------------ creation

    /**
     * S2-1: a group is created under an existing one and takes its nature and
     * its place in gross profit; its cash-flow class follows its ancestry.
     * There is no way to create a root, so no way to put an expense among the
     * assets.
     */
    public LedgerGroup createGroup(String name, String parentId, long now) {
        String clean = requireName(name, "A group needs a name");
        String id = Ids.newIdString();
        return write("create the group", connection -> {
            AccountNature nature;
            int grossProfit;
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT nature, affects_gross_profit FROM ledger_group WHERE id = ?")) {
                ps.setString(1, parentId);
                try (ResultSet rows = ps.executeQuery()) {
                    if (!rows.next()) {
                        throw new ChartException("Choose the group this one belongs under");
                    }
                    nature = AccountNature.valueOf(rows.getString(1));
                    grossProfit = rows.getInt(2);
                }
            }
            if (countWhere(connection, "SELECT COUNT(*) FROM ledger_group WHERE name = ?", clean) > 0) {
                throw new ChartException("A group named \"" + clean + "\" already exists");
            }
            try (PreparedStatement ps = connection.prepareStatement("INSERT INTO ledger_group (id, parent_id, name, "
                    + "nature, affects_gross_profit, is_system, sequence, created_at, updated_at) VALUES "
                    + "(?, ?, ?, ?, ?, 0, (SELECT COALESCE(MAX(sequence), 0) + 1 FROM ledger_group), ?, ?)")) {
                ps.setString(1, id);
                ps.setString(2, parentId);
                ps.setString(3, clean);
                ps.setString(4, nature.name());
                ps.setInt(5, grossProfit);
                ps.setLong(6, now);
                ps.setLong(7, now);
                ps.executeUpdate();
            }
            return new LedgerGroup(id, parentId, null, clean, nature, false);
        });
    }

    /**
     * Create a ledger in a group. S2-2: never under Sundry Debtors or Sundry
     * Creditors - a receivable or payable is a party's, created with the party,
     * so invoices, receipts, ageing and GST details all have someone to belong to.
     */
    public LedgerAccount createLedger(String name, String groupId, long now) {
        String clean = requireName(name, "An account needs a name");
        String id = Ids.newIdString();
        write("create the account", connection -> {
            AccountNature nature = natureOfGroup(connection, groupId);
            if (underAny(connection, groupId, "SUNDRY_DEBTORS", "SUNDRY_CREDITORS")) {
                throw new ChartException("Customers and vendors are added as parties, which creates their "
                        + "account in Sundry Debtors or Sundry Creditors");
            }
            if (countWhere(connection, "SELECT COUNT(*) FROM ledger WHERE name = ?", clean) > 0) {
                throw new ChartException("An account named \"" + clean + "\" already exists");
            }
            try (PreparedStatement ps = connection.prepareStatement("INSERT INTO ledger (id, group_id, name, "
                    + "created_at, updated_at) VALUES (?, ?, ?, ?, ?)")) {
                ps.setString(1, id);
                ps.setString(2, groupId);
                ps.setString(3, clean);
                ps.setLong(4, now);
                ps.setLong(5, now);
                ps.executeUpdate();
            }
            return nature;
        });
        return findById(id).orElseThrow();
    }

    private static String requireName(String name, String message) {
        if (name == null || name.isBlank()) {
            throw new ChartException(message);
        }
        return name.strip();
    }

    /** Whether the group, or any group above it, has one of these system codes. */
    private static boolean underAny(Connection connection, String groupId, String... codes) throws SQLException {
        String current = groupId;
        for (int depth = 0; current != null && depth < 64; depth++) {
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT system_code, parent_id FROM ledger_group WHERE id = ?")) {
                ps.setString(1, current);
                try (ResultSet rows = ps.executeQuery()) {
                    if (!rows.next()) {
                        return false;
                    }
                    String code = rows.getString(1);
                    if (code != null && List.of(codes).contains(code)) {
                        return true;
                    }
                    current = rows.getString(2);
                }
            }
        }
        return false;
    }

    // --------------------------------------------------------- governance

    /**
     * Rename an account.
     *
     * <p>Permitted for system accounts too (P6-2). Nothing resolves an account
     * by its display name, so a rename cannot break posting - unlike the
     * reference application, where renaming "Round Off" makes the next invoice
     * create a second one.
     */
    public void rename(String ledgerId, String newName, long now) {
        if (newName == null || newName.isBlank()) {
            throw new ChartException("An account needs a name");
        }
        execute(connection -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "UPDATE ledger SET name = ?, updated_at = ? WHERE id = ?")) {
                ps.setString(1, newName.strip());
                ps.setLong(2, now);
                ps.setString(3, ledgerId);
                if (ps.executeUpdate() == 0) {
                    throw new ChartException("No such account: " + ledgerId);
                }
            }
            return null;
        }, "rename the account");
    }

    /**
     * Move an account to a different group.
     *
     * <p>Refused when the target group has a different nature and the account
     * already has postings. The reference permits it unconditionally - its PATCH
     * handler passes {@code groupId} straight through - which flips the sign
     * convention for every future entry while the accumulated history keeps the
     * old one, leaving the account a mixture of two.
     */
    public void moveToGroup(String ledgerId, String targetGroupId, long now) {
        execute(connection -> {
            if (countWhere(connection, "SELECT COUNT(*) FROM party WHERE ledger_id = ?", ledgerId) > 0) {
                // Where a party's ledger lives follows from the party's role.
                // Moving it here would let the two disagree.
                throw new ChartException(
                        "This account belongs to a party. Change the party's role instead of "
                                + "moving its account.");
            }
            AccountNature current = natureOfLedger(connection, ledgerId);
            AccountNature target = natureOfGroup(connection, targetGroupId);
            if (current != target && hasPostings(connection, ledgerId)) {
                throw new ChartException(
                        "This account has already been posted to, so it cannot move from a "
                                + current + " group to a " + target + " group: its existing "
                                + "balance was accumulated under the opposite sign convention. "
                                + "Create a new account and transfer the balance instead.");
            }
            try (PreparedStatement ps = connection.prepareStatement(
                    "UPDATE ledger SET group_id = ?, updated_at = ? WHERE id = ?")) {
                ps.setString(1, targetGroupId);
                ps.setLong(2, now);
                ps.setString(3, ledgerId);
                ps.executeUpdate();
            }
            return null;
        }, "move the account");
    }

    /**
     * Deactivate an account.
     *
     * <p>System accounts cannot be deactivated: a module resolves them and would
     * fail at the moment it needed one.
     *
     * <p>A deactivated account keeps its history and continues to appear in the
     * trial balance while it holds a balance (A-6). Deactivation hides it from
     * pickers, never from the books - dropping it from the report would make the
     * two columns disagree.
     */
    public void deactivate(String ledgerId, long now) {
        execute(connection -> {
            if (isSystem(connection, ledgerId)) {
                throw new ChartException(
                        "This is a system account and cannot be deactivated. Modules resolve "
                                + "it by its stable code and would fail without it.");
            }
            try (PreparedStatement ps = connection.prepareStatement(
                    "UPDATE ledger SET is_active = 0, updated_at = ? WHERE id = ?")) {
                ps.setLong(1, now);
                ps.setString(2, ledgerId);
                if (ps.executeUpdate() == 0) {
                    throw new ChartException("No such account: " + ledgerId);
                }
            }
            return null;
        }, "deactivate the account");
    }

    public void reactivate(String ledgerId, long now) {
        execute(connection -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "UPDATE ledger SET is_active = 1, updated_at = ? WHERE id = ?")) {
                ps.setLong(1, now);
                ps.setString(2, ledgerId);
                ps.executeUpdate();
            }
            return null;
        }, "reactivate the account");
    }

    /**
     * Delete an account.
     *
     * <p>Only ever a user account that has never been posted to. A system
     * account is refused here and again by a trigger; an account with history is
     * deactivated instead, because deleting it would remove entries the trial
     * balance depends on.
     */
    public void delete(String ledgerId) {
        execute(connection -> {
            if (isSystem(connection, ledgerId)) {
                throw new ChartException(
                        "A system account cannot be deleted. Deactivate it if it is unused.");
            }
            if (hasPostings(connection, ledgerId)) {
                throw new ChartException(
                        "This account has postings and cannot be deleted; its entries are part "
                                + "of the books. Deactivate it instead.");
            }
            try (PreparedStatement ps = connection.prepareStatement(
                    "DELETE FROM ledger WHERE id = ?")) {
                ps.setString(1, ledgerId);
                ps.executeUpdate();
            }
            return null;
        }, "delete the account");
    }

    /**
     * Delete a group.
     *
     * <p>A-9: a group with child ledgers or child groups would orphan them.
     * SQLite's foreign key would normally refuse, but P2 measured that
     * {@code foreign_keys} defaults OFF per connection, so it is checked here
     * and by triggers rather than relied upon.
     */
    public void deleteGroup(String groupId) {
        execute(connection -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT is_system FROM ledger_group WHERE id = ?")) {
                ps.setString(1, groupId);
                try (ResultSet rows = ps.executeQuery()) {
                    if (!rows.next()) {
                        throw new ChartException("No such group: " + groupId);
                    }
                    if (rows.getInt(1) == 1) {
                        throw new ChartException("A system group cannot be deleted.");
                    }
                }
            }
            if (countWhere(connection, "SELECT COUNT(*) FROM ledger WHERE group_id = ?", groupId) > 0) {
                throw new ChartException(
                        "This group still contains accounts. Move or remove them first.");
            }
            if (countWhere(connection,
                    "SELECT COUNT(*) FROM ledger_group WHERE parent_id = ?", groupId) > 0) {
                throw new ChartException(
                        "This group still contains sub-groups. Remove them first.");
            }
            try (PreparedStatement ps = connection.prepareStatement(
                    "DELETE FROM ledger_group WHERE id = ?")) {
                ps.setString(1, groupId);
                ps.executeUpdate();
            }
            return null;
        }, "delete the group");
    }

    // ------------------------------------------------------- trial balance

    /**
     * The trial balance for one fiscal year (P6-3).
     *
     * <p>Computed by {@link TrialBalanceQuery}, which is the only trial-balance
     * query in the application - read-only callers such as the UI use it
     * directly on a read connection.
     */
    public TrialBalance trialBalance(String fiscalYearId) {
        return read("compute the trial balance", connection -> TrialBalanceQuery.compute(connection, fiscalYearId));
    }

    // ------------------------------------------------------------ helpers

    private static String baseSelect() {
        return """
                SELECT l.id, l.group_id, l.system_code, l.name, g.nature,
                       l.is_system, l.is_active
                FROM ledger l
                JOIN ledger_group g ON g.id = l.group_id
                """;
    }

    private static LedgerAccount mapAccount(ResultSet rows) throws SQLException {
        return new LedgerAccount(
                rows.getString("id"),
                rows.getString("group_id"),
                rows.getString("system_code"),
                rows.getString("name"),
                AccountNature.valueOf(rows.getString("nature")),
                rows.getInt("is_system") == 1,
                rows.getInt("is_active") == 1);
    }

    private Optional<LedgerAccount> queryOne(String where, String parameter) {
        return read("read the account", connection -> {
            try (PreparedStatement ps = connection.prepareStatement(baseSelect() + " " + where)) {
                ps.setString(1, parameter);
                try (ResultSet rows = ps.executeQuery()) {
                    return rows.next() ? Optional.of(mapAccount(rows)) : Optional.empty();
                }
            }
        });
    }

    private <T> void execute(SqlWork<T> work, String description) {
        write(description, work);
    }

    private <T> T write(String description, SqlWork<T> work) {
        try {
            return transactions.inTransaction(work::run);
        } catch (SQLException e) {
            throw new ChartException("Could not " + description + ": " + e.getMessage(), e);
        }
    }

    private <T> T read(String description, SqlWork<T> work) {
        try {
            return transactions.read(work::run);
        } catch (SQLException e) {
            throw new ChartException("Could not " + description + ": " + e.getMessage(), e);
        }
    }

    @FunctionalInterface
    private interface SqlWork<T> {
        T run(Connection connection) throws SQLException;
    }

    private static boolean isSystem(Connection connection, String ledgerId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT is_system FROM ledger WHERE id = ?")) {
            ps.setString(1, ledgerId);
            try (ResultSet rows = ps.executeQuery()) {
                if (!rows.next()) {
                    throw new ChartException("No such account: " + ledgerId);
                }
                return rows.getInt(1) == 1;
            }
        }
    }

    private static boolean hasPostings(Connection connection, String ledgerId) throws SQLException {
        return countWhere(connection,
                "SELECT COUNT(*) FROM voucher_entry WHERE ledger_id = ?", ledgerId) > 0;
    }

    private static long countWhere(Connection connection, String sql, String parameter)
            throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, parameter);
            try (ResultSet rows = ps.executeQuery()) {
                rows.next();
                return rows.getLong(1);
            }
        }
    }

    private static AccountNature natureOfLedger(Connection connection, String ledgerId)
            throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT g.nature FROM ledger l JOIN ledger_group g ON g.id = l.group_id "
                        + "WHERE l.id = ?")) {
            ps.setString(1, ledgerId);
            try (ResultSet rows = ps.executeQuery()) {
                if (!rows.next()) {
                    throw new ChartException("No such account: " + ledgerId);
                }
                return AccountNature.valueOf(rows.getString(1));
            }
        }
    }

    private static AccountNature natureOfGroup(Connection connection, String groupId)
            throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT nature FROM ledger_group WHERE id = ?")) {
            ps.setString(1, groupId);
            try (ResultSet rows = ps.executeQuery()) {
                if (!rows.next()) {
                    throw new ChartException("No such group: " + groupId);
                }
                return AccountNature.valueOf(rows.getString(1));
            }
        }
    }
}
