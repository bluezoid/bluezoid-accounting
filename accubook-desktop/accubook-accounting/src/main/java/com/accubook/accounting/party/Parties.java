package com.accubook.accounting.party;

import com.accubook.persistence.connection.Database;
import com.accubook.platform.id.Ids;
import com.accubook.platform.money.MoneyMapper;
import com.accubook.platform.money.Scale;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Creates and governs parties, each together with its ledger.
 *
 * <p>Parties are kernel (B-7): every plan has them and they are never
 * entitlement-gated.
 *
 * <h2>Bound by id, never by name</h2>
 * The reference finds a party's ledger with {@code upsertLedgerByName}, an
 * {@code INSERT ... ON CONFLICT (name) ... RETURNING id}. A party named "Cash in
 * Hand" is therefore silently attached to the cash ledger, and its receivables
 * post into cash (R-5). Here the ledger is created with the party, in the same
 * transaction, and linked by {@code party.ledger_id}. A name that already
 * belongs to any ledger is refused, never merged.
 */
public final class Parties {

    private final Database database;

    public Parties(Database database) {
        this.database = Objects.requireNonNull(database, "database");
    }

    // ---------------------------------------------------------------- reads

    public Optional<Party> findById(String partyId) {
        return queryOne("WHERE p.id = ?", partyId);
    }

    /** The party a ledger belongs to, if it belongs to one. */
    public Optional<Party> findByLedgerId(String ledgerId) {
        return queryOne("WHERE p.ledger_id = ?", ledgerId);
    }

    /** Every party, active or not, ordered by name. */
    public List<Party> all() {
        return queryList("", List.of());
    }

    /** Parties we sell to: customers and parties that are both. */
    public List<Party> customers() {
        return queryList("WHERE p.role IN ('CUSTOMER', 'BOTH')", List.of());
    }

    /** Parties we buy from: vendors and parties that are both. */
    public List<Party> vendors() {
        return queryList("WHERE p.role IN ('VENDOR', 'BOTH')", List.of());
    }

    // --------------------------------------------------------------- writes

    /**
     * Create a party and its ledger, atomically.
     *
     * <p>The ledger is an ordinary user ledger placed by {@link PartyRole}. It is
     * not a system ledger: a party with no history may be deleted.
     */
    public Party create(String name, PartyRole role, PartyProfile profile, long now) {
        String cleanName = requireName(name);
        Objects.requireNonNull(role, "role");
        PartyProfile details = profile == null ? PartyProfile.empty() : profile;
        String partyId = Ids.newIdString();
        String ledgerId = Ids.newIdString();

        execute(connection -> {
            refuseNameInUse(connection, cleanName, null);
            refuseCodeInUse(connection, details.code(), null);
            String groupId = groupIdFor(connection, role.defaultGroupCode());

            try (PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO ledger (id, group_id, name, is_system, created_at, updated_at) "
                            + "VALUES (?, ?, ?, 0, ?, ?)")) {
                ps.setString(1, ledgerId);
                ps.setString(2, groupId);
                ps.setString(3, cleanName);
                ps.setLong(4, now);
                ps.setLong(5, now);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO party (id, ledger_id, role, name, code, contact_person, email, "
                            + "phone, address_line1, address_city, state_code, postal_code, gstin, "
                            + "pan, credit_limit_e4, credit_days, created_at, updated_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
                ps.setString(1, partyId);
                ps.setString(2, ledgerId);
                ps.setString(3, role.name());
                ps.setString(4, cleanName);
                int next = bindProfile(ps, 5, details);
                ps.setLong(next, now);
                ps.setLong(next + 1, now);
                ps.executeUpdate();
            }
            return null;
        }, "create the party");

        return new Party(partyId, ledgerId, role, cleanName, details, true);
    }

    /**
     * Rename a party. The ledger is renamed, and the party follows it.
     *
     * <p>Safe because nothing resolves a party's ledger by name.
     */
    public void rename(String partyId, String newName, long now) {
        String cleanName = requireName(newName);
        execute(connection -> {
            String ledgerId = ledgerIdOf(connection, partyId);
            refuseNameInUse(connection, cleanName, ledgerId);
            try (PreparedStatement ps = connection.prepareStatement(
                    "UPDATE ledger SET name = ?, updated_at = ? WHERE id = ?")) {
                ps.setString(1, cleanName);
                ps.setLong(2, now);
                ps.setString(3, ledgerId);
                ps.executeUpdate();
            }
            return null;
        }, "rename the party");
    }

    /** Replace a party's descriptive details. */
    public void updateProfile(String partyId, PartyProfile profile, long now) {
        Objects.requireNonNull(profile, "profile");
        execute(connection -> {
            refuseCodeInUse(connection, profile.code(), partyId);
            try (PreparedStatement ps = connection.prepareStatement(
                    "UPDATE party SET code = ?, contact_person = ?, email = ?, phone = ?, "
                            + "address_line1 = ?, address_city = ?, state_code = ?, postal_code = ?, "
                            + "gstin = ?, pan = ?, credit_limit_e4 = ?, credit_days = ?, "
                            + "updated_at = ? WHERE id = ?")) {
                int next = bindProfile(ps, 1, profile);
                ps.setLong(next, now);
                ps.setString(next + 1, partyId);
                if (ps.executeUpdate() == 0) {
                    throw new PartyException("No such party: " + partyId);
                }
            }
            return null;
        }, "update the party");
    }

    /**
     * Change what a party is to the business.
     *
     * <p>A ledger with no postings simply moves to the new role's group. A
     * ledger with postings never moves: Debtors are ASSETS and Creditors are
     * LIABILITIES, and moving a posted ledger across natures would leave its
     * history accumulated under the opposite sign convention (R-3). So a posted
     * vendor may become {@code BOTH} - which accepts either group - but not a
     * plain customer.
     */
    public void changeRole(String partyId, PartyRole newRole, long now) {
        Objects.requireNonNull(newRole, "newRole");
        execute(connection -> {
            String ledgerId = ledgerIdOf(connection, partyId);
            String currentGroup = groupCodeOfLedger(connection, ledgerId);

            if (!newRole.acceptsGroup(currentGroup)) {
                if (hasPostings(connection, ledgerId)) {
                    throw new PartyException(
                            "This party already has entries in its ledger under " + groupLabel(currentGroup)
                                    + ", so it cannot become a " + newRole.name().toLowerCase(java.util.Locale.ROOT)
                                    + ": its balance would change sign. Mark it as both a customer and a "
                                    + "vendor instead, or create a new party.");
                }
                try (PreparedStatement ps = connection.prepareStatement(
                        "UPDATE ledger SET group_id = ?, updated_at = ? WHERE id = ?")) {
                    ps.setString(1, groupIdFor(connection, newRole.defaultGroupCode()));
                    ps.setLong(2, now);
                    ps.setString(3, ledgerId);
                    ps.executeUpdate();
                }
            }
            try (PreparedStatement ps = connection.prepareStatement(
                    "UPDATE party SET role = ?, updated_at = ? WHERE id = ?")) {
                ps.setString(1, newRole.name());
                ps.setLong(2, now);
                ps.setString(3, partyId);
                ps.executeUpdate();
            }
            return null;
        }, "change the party's role");
    }

    /**
     * Deactivate a party. Its ledger is deactivated with it, which refuses new
     * postings; its history and balance stay in the books (A-6).
     */
    public void deactivate(String partyId, long now) {
        setActive(partyId, false, now);
    }

    public void reactivate(String partyId, long now) {
        setActive(partyId, true, now);
    }

    /**
     * Delete a party and its ledger. Only when the ledger has never been posted
     * to; otherwise its entries are part of the books, and it is deactivated
     * instead.
     */
    public void delete(String partyId) {
        execute(connection -> {
            String ledgerId = ledgerIdOf(connection, partyId);
            if (hasPostings(connection, ledgerId)) {
                throw new PartyException(
                        "This party has entries in its ledger and cannot be deleted; they are part "
                                + "of the books. Deactivate it instead.");
            }
            // Party first: the ledger refuses deletion while a party points at it.
            try (PreparedStatement ps = connection.prepareStatement("DELETE FROM party WHERE id = ?")) {
                ps.setString(1, partyId);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = connection.prepareStatement("DELETE FROM ledger WHERE id = ?")) {
                ps.setString(1, ledgerId);
                ps.executeUpdate();
            }
            return null;
        }, "delete the party");
    }

    // ------------------------------------------------------------ helpers

    private void setActive(String partyId, boolean active, long now) {
        execute(connection -> {
            String ledgerId = ledgerIdOf(connection, partyId);
            // The ledger carries the flag; the party follows it by trigger.
            try (PreparedStatement ps = connection.prepareStatement(
                    "UPDATE ledger SET is_active = ?, updated_at = ? WHERE id = ?")) {
                ps.setInt(1, active ? 1 : 0);
                ps.setLong(2, now);
                ps.setString(3, ledgerId);
                ps.executeUpdate();
            }
            return null;
        }, active ? "reactivate the party" : "deactivate the party");
    }

    private static String requireName(String name) {
        if (name == null || name.isBlank()) {
            throw new PartyException("A party needs a name");
        }
        return name.strip();
    }

    /**
     * Refuse a name any ledger already has, ignoring case.
     *
     * <p>The column's {@code UNIQUE} is case-sensitive, so it would accept a
     * party "CASH IN HAND" beside the "Cash in Hand" ledger - two accounts no
     * one can tell apart in a picker. That is R-5's confusion by a different
     * route, so it is refused here.
     */
    private static void refuseNameInUse(Connection connection, String name, String exceptLedgerId)
            throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT name FROM ledger WHERE lower(name) = lower(?) AND id IS NOT ?")) {
            ps.setString(1, name);
            ps.setString(2, exceptLedgerId);
            try (ResultSet rows = ps.executeQuery()) {
                if (rows.next()) {
                    throw new PartyException("The name \"" + name + "\" is already used by the account \""
                            + rows.getString(1) + "\". A party needs a name no other account has.");
                }
            }
        }
    }

    private static void refuseCodeInUse(Connection connection, String code, String exceptPartyId)
            throws SQLException {
        if (code == null) {
            return;
        }
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT name FROM party WHERE lower(code) = lower(?) AND id IS NOT ?")) {
            ps.setString(1, code);
            ps.setString(2, exceptPartyId);
            try (ResultSet rows = ps.executeQuery()) {
                if (rows.next()) {
                    throw new PartyException("The code \"" + code + "\" is already used by \""
                            + rows.getString(1) + "\"");
                }
            }
        }
    }

    private static int bindProfile(PreparedStatement ps, int start, PartyProfile profile)
            throws SQLException {
        int i = start;
        ps.setString(i++, profile.code());
        ps.setString(i++, profile.contactPerson());
        ps.setString(i++, profile.email());
        ps.setString(i++, profile.phone());
        ps.setString(i++, profile.addressLine1());
        ps.setString(i++, profile.city());
        ps.setString(i++, profile.stateCode());
        ps.setString(i++, profile.postalCode());
        ps.setString(i++, profile.gstin());
        ps.setString(i++, profile.pan());
        if (profile.creditLimit() == null) {
            ps.setNull(i++, Types.INTEGER);
        } else {
            ps.setLong(i++, MoneyMapper.toStored(profile.creditLimit(), Scale.MONEY));
        }
        if (profile.creditDays() == null) {
            ps.setNull(i++, Types.INTEGER);
        } else {
            ps.setInt(i++, profile.creditDays());
        }
        return i;
    }

    private static String groupIdFor(Connection connection, String groupCode) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT id FROM ledger_group WHERE system_code = ?")) {
            ps.setString(1, groupCode);
            try (ResultSet rows = ps.executeQuery()) {
                if (!rows.next()) {
                    // Provisioning creates both groups and they cannot be
                    // deleted, so this is a damaged company file.
                    throw new PartyException("The " + groupLabel(groupCode)
                            + " group is missing from this company's chart of accounts");
                }
                return rows.getString(1);
            }
        }
    }

    private static String groupCodeOfLedger(Connection connection, String ledgerId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT g.system_code FROM ledger l JOIN ledger_group g ON g.id = l.group_id "
                        + "WHERE l.id = ?")) {
            ps.setString(1, ledgerId);
            try (ResultSet rows = ps.executeQuery()) {
                return rows.next() ? rows.getString(1) : null;
            }
        }
    }

    private static String ledgerIdOf(Connection connection, String partyId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT ledger_id FROM party WHERE id = ?")) {
            ps.setString(1, partyId);
            try (ResultSet rows = ps.executeQuery()) {
                if (!rows.next()) {
                    throw new PartyException("No such party: " + partyId);
                }
                return rows.getString(1);
            }
        }
    }

    private static boolean hasPostings(Connection connection, String ledgerId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT EXISTS (SELECT 1 FROM voucher_entry WHERE ledger_id = ?)")) {
            ps.setString(1, ledgerId);
            try (ResultSet rows = ps.executeQuery()) {
                rows.next();
                return rows.getInt(1) == 1;
            }
        }
    }

    private static String groupLabel(String groupCode) {
        return "SUNDRY_CREDITORS".equals(groupCode) ? "Sundry Creditors"
                : "SUNDRY_DEBTORS".equals(groupCode) ? "Sundry Debtors"
                : String.valueOf(groupCode);
    }

    private static String baseSelect() {
        // name and active come from the ledger, their source of truth.
        return """
                SELECT p.id, p.ledger_id, p.role, l.name, l.is_active, p.code, p.contact_person,
                       p.email, p.phone, p.address_line1, p.address_city, p.state_code,
                       p.postal_code, p.gstin, p.pan, p.credit_limit_e4, p.credit_days
                FROM party p
                JOIN ledger l ON l.id = p.ledger_id
                """;
    }

    private static Party map(ResultSet rows) throws SQLException {
        long limit = rows.getLong("credit_limit_e4");
        boolean noLimit = rows.wasNull();
        int days = rows.getInt("credit_days");
        boolean noDays = rows.wasNull();
        PartyProfile profile = new PartyProfile(
                rows.getString("code"),
                rows.getString("contact_person"),
                rows.getString("email"),
                rows.getString("phone"),
                rows.getString("address_line1"),
                rows.getString("address_city"),
                rows.getString("state_code"),
                rows.getString("postal_code"),
                rows.getString("gstin"),
                rows.getString("pan"),
                noLimit ? null : MoneyMapper.fromStored(limit, Scale.MONEY),
                noDays ? null : days);
        return new Party(
                rows.getString("id"),
                rows.getString("ledger_id"),
                PartyRole.valueOf(rows.getString("role")),
                rows.getString("name"),
                profile,
                rows.getInt("is_active") == 1);
    }

    private Optional<Party> queryOne(String where, String parameter) {
        List<Party> found = queryList(where, List.of(parameter));
        return found.isEmpty() ? Optional.empty() : Optional.of(found.get(0));
    }

    private List<Party> queryList(String where, List<String> parameters) {
        List<Party> parties = new ArrayList<>();
        try (Connection connection = database.openReadConnection();
             PreparedStatement ps = connection.prepareStatement(
                     baseSelect() + " " + where + " ORDER BY l.name")) {
            for (int i = 0; i < parameters.size(); i++) {
                ps.setString(i + 1, parameters.get(i));
            }
            try (ResultSet rows = ps.executeQuery()) {
                while (rows.next()) {
                    parties.add(map(rows));
                }
            }
        } catch (SQLException e) {
            throw new PartyException("Could not read parties", e);
        }
        return parties;
    }

    private void execute(SqlWork work, String description) {
        try {
            database.inTransaction(connection -> {
                work.run(connection);
                return null;
            });
        } catch (SQLException e) {
            throw new PartyException("Could not " + description + ": " + e.getMessage(), e);
        }
    }

    @FunctionalInterface
    private interface SqlWork {
        Object run(Connection connection) throws SQLException;
    }
}
