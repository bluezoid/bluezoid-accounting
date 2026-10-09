package com.accubook.accounting.posting.internal;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HexFormat;

/**
 * Appends a hash-chained audit entry.
 *
 * <p>Written <strong>inside</strong> the posting transaction, not after it. If
 * audit were a separate transaction, a crash between the two would leave a
 * posted voucher with no audit record - and an audit trail that is only mostly
 * complete is not one an auditor can rely on. The reference application emitted
 * audit per handler and drifted to roughly 70% coverage.
 *
 * <p>Each row carries the previous row's hash, so removing or altering one is
 * detectable even though the P2 triggers already make it impossible through
 * SQL.
 */
final class AuditWriter {

    private AuditWriter() {
    }

    static void record(Connection connection, String action, String entityType,
                       String entityId, String detail, long occurredAt) throws SQLException {
        long sequence;
        String previousHash;
        try (PreparedStatement select = connection.prepareStatement(
                "SELECT sequence, entry_hash FROM audit_log ORDER BY sequence DESC LIMIT 1");
             ResultSet rows = select.executeQuery()) {
            if (rows.next()) {
                sequence = rows.getLong(1) + 1;
                previousHash = rows.getString(2);
            } else {
                sequence = 1;
                previousHash = null;
            }
        }

        String payload = sequence + "|" + occurredAt + "|" + action + "|" + entityType
                + "|" + (entityId == null ? "" : entityId)
                + "|" + (detail == null ? "" : detail)
                + "|" + (previousHash == null ? "" : previousHash);

        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO audit_log (id, sequence, occurred_at, action, entity_type, "
                        + "entity_id, detail, previous_hash, entry_hash) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            insert.setString(1, com.accubook.platform.id.Ids.newIdString());
            insert.setLong(2, sequence);
            insert.setLong(3, occurredAt);
            insert.setString(4, action);
            insert.setString(5, entityType);
            insert.setString(6, entityId);
            insert.setString(7, detail);
            insert.setString(8, previousHash);
            insert.setString(9, sha256(payload));
            insert.executeUpdate();
        }
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the platform", e);
        }
    }
}
