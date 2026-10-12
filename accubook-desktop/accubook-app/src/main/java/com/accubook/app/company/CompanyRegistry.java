package com.accubook.app.company;

import com.accubook.persistence.connection.Database;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The list of companies in this installation, stored in {@code app.db}.
 *
 * <p>Package-private: the registry is an internal collaborator of
 * {@link CompanyService}, not part of the module's contract.
 *
 * <p>Holds only pointers and metadata. Every accounting row lives in the
 * company's own file, which is what makes isolation structural: a query cannot
 * reach another company's data because that data is in a file this connection
 * has not opened.
 */
final class CompanyRegistry {

    private final Database appDatabase;

    CompanyRegistry(Database appDatabase) {
        this.appDatabase = appDatabase;
    }

    /**
     * Record a company.
     *
     * <p>Called only after the company's database file has been created and
     * validated. The ordering matters: an orphaned file can be found and
     * cleaned up, whereas a registry row pointing at a file that was never
     * created is a broken installation.
     */
    void register(Company company) throws SQLException {
        appDatabase.inTransaction(connection -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO company (id, name, schema_version, created_at, last_opened_at) "
                            + "VALUES (?, ?, ?, ?, ?)")) {
                ps.setString(1, company.id());
                ps.setString(2, company.name());
                ps.setInt(3, company.schemaVersion());
                ps.setLong(4, company.createdAt().toEpochMilli());
                if (company.lastOpenedAt() == null) {
                    ps.setNull(5, java.sql.Types.INTEGER);
                } else {
                    ps.setLong(5, company.lastOpenedAt().toEpochMilli());
                }
                ps.executeUpdate();
            }
            return null;
        });
    }

    /** All companies, most recently opened first, then alphabetically. */
    List<Company> findAll() throws SQLException {
        List<Company> companies = new ArrayList<>();
        try (Connection connection = appDatabase.openReadConnection();
             PreparedStatement ps = connection.prepareStatement(
                     "SELECT id, name, schema_version, created_at, last_opened_at "
                             + "FROM company ORDER BY last_opened_at DESC NULLS LAST, name ASC");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                companies.add(map(rs));
            }
        }
        return companies;
    }

    Optional<Company> findById(String id) throws SQLException {
        try (Connection connection = appDatabase.openReadConnection();
             PreparedStatement ps = connection.prepareStatement(
                     "SELECT id, name, schema_version, created_at, last_opened_at "
                             + "FROM company WHERE id = ?")) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        }
    }

    /** Record that a company was opened, and the schema version it is now at. */
    void markOpened(String id, int schemaVersion) throws SQLException {
        appDatabase.inTransaction(connection -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "UPDATE company SET last_opened_at = ?, schema_version = ? WHERE id = ?")) {
                ps.setLong(1, Instant.now().toEpochMilli());
                ps.setInt(2, schemaVersion);
                ps.setString(3, id);
                ps.executeUpdate();
            }
            return null;
        });
    }

    private static Company map(ResultSet rs) throws SQLException {
        long lastOpened = rs.getLong("last_opened_at");
        boolean neverOpened = rs.wasNull() || lastOpened == 0;
        return new Company(
                rs.getString("id"),
                rs.getString("name"),
                rs.getInt("schema_version"),
                Instant.ofEpochMilli(rs.getLong("created_at")),
                neverOpened ? null : Instant.ofEpochMilli(lastOpened));
    }
}
