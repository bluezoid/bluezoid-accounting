package com.accubook.banking;

import com.accubook.accounting.posting.EntryLine;
import com.accubook.accounting.posting.PostedVoucher;
import com.accubook.accounting.posting.PostingRequest;
import com.accubook.accounting.posting.TransactionalPosting;
import com.accubook.module.EntitlementGuard;
import com.accubook.module.ModuleTransactions;
import com.accubook.platform.id.Ids;
import com.accubook.platform.money.MoneyMapper;
import com.accubook.platform.money.Scale;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Banking use cases (Phase 10): bank details, statement import, one
 * reconciliation engine (D-29), bank-only items, and the reconciliation
 * statement.
 *
 * <p>Matches are proposed and confirmed by the user (P10-3); nothing is matched
 * silently. A match ties statement lines to the bank ledger's voucher entries,
 * one-to-one or one-to-many either way, and their amounts must agree exactly.
 */
public final class Banking {

    /** How far apart a book entry and a statement line may be to be proposed together. */
    static final int WINDOW_DAYS = 10;

    private final ModuleTransactions transactions;
    private final EntitlementGuard entitlement;
    private final TransactionalPosting posting;
    private final Clock clock;

    public Banking(ModuleTransactions transactions, EntitlementGuard entitlement, Clock clock) {
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.entitlement = Objects.requireNonNull(entitlement, "entitlement");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.posting = new TransactionalPosting(entitlement, clock);
    }

    // ============================================================ accounts

    /** Give a Cash &amp; Bank ledger its bank details. */
    public BankAccount registerAccount(String ledgerId, String bankName, String branch, String accountNumber,
                                       String ifsc, String type) {
        require("record a bank account");
        if (bankName == null || bankName.isBlank() || accountNumber == null || accountNumber.isBlank()) {
            throw new BankingException("A bank account needs the bank's name and the account number");
        }
        String code = ifsc == null || ifsc.isBlank() ? null : ifsc.strip().toUpperCase(Locale.ROOT);
        if (code != null && !code.matches("[A-Z]{4}0[A-Z0-9]{6}")) {
            throw new BankingException("\"" + ifsc + "\" is not an IFSC: four letters, 0, then six letters or digits");
        }
        write(c -> {
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO bank_account (ledger_id, bank_name, branch, "
                    + "account_number, ifsc, account_type, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)")) {
                ps.setString(1, ledgerId);
                ps.setString(2, bankName.strip());
                ps.setString(3, branch == null || branch.isBlank() ? null : branch.strip());
                ps.setString(4, accountNumber.replaceAll("\\s", ""));
                ps.setString(5, code);
                ps.setString(6, type);
                ps.setLong(7, clock.millis());
                ps.setLong(8, clock.millis());
                ps.executeUpdate();
            }
        }, "record the bank account");
        return account(ledgerId);
    }

    /**
     * P10-5: reconcile from {@code from}, starting at the statement's balance at
     * the end of the day before. Entries before it are not offered; once
     * anything is matched, this is fixed.
     */
    public void startReconciliation(String ledgerId, LocalDate from, BigDecimal openingStatementBalance) {
        require("start reconciliation");
        Objects.requireNonNull(from, "from");
        write(c -> {
            try (PreparedStatement ps = c.prepareStatement("UPDATE bank_account SET reconcile_from = ?, "
                    + "opening_statement_e4 = ?, updated_at = ? WHERE ledger_id = ?")) {
                ps.setString(1, from.toString());
                ps.setLong(2, money(openingStatementBalance));
                ps.setLong(3, clock.millis());
                ps.setString(4, ledgerId);
                if (ps.executeUpdate() == 0) {
                    throw new BankingException("No bank details are recorded for this ledger");
                }
            }
        }, "start reconciliation");
    }

    public BankAccount account(String ledgerId) {
        return read(c -> accountRow(c, ledgerId));
    }

    // ============================================================ statements

    /**
     * Import a statement CSV (P10-1, P10-2). Every line is read before anything
     * is written; one unreadable line refuses the file. Lines already imported
     * from an overlapping statement are recognised by their identity and not
     * added again; identical lines on one day stay distinct by occurrence.
     */
    public ImportResult importStatement(String ledgerId, String fileName, String csv, StatementLayout layout) {
        require("import a bank statement");
        List<StatementLine> lines = StatementParser.parse(csv, layout);
        List<String> identities = new ArrayList<>();
        Map<String, Integer> seen = new HashMap<>();
        for (StatementLine l : lines) {
            String base = l.date() + "|" + (l.deposit().signum() > 0 ? "D" : "W") + "|"
                    + l.net().abs().stripTrailingZeros().toPlainString() + "|"
                    + (l.reference() == null ? "" : l.reference()) + "|"
                    + l.narration().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "") + "|"
                    + (l.balance() == null ? "" : l.balance().stripTrailingZeros().toPlainString());
            identities.add(base + "|" + seen.merge(base, 1, Integer::sum));
        }
        String importId = Ids.newIdString();
        int[] added = {0};
        write(c -> {
            accountRow(c, ledgerId);
            List<Integer> fresh = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT EXISTS (SELECT 1 FROM bank_statement_line WHERE ledger_id = ? AND identity = ?)")) {
                for (int i = 0; i < identities.size(); i++) {
                    ps.setString(1, ledgerId);
                    ps.setString(2, identities.get(i));
                    try (ResultSet r = ps.executeQuery()) {
                        r.next();
                        if (r.getInt(1) == 0) {
                            fresh.add(i);
                        }
                    }
                }
            }
            added[0] = fresh.size();
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO bank_statement_import (id, ledger_id, "
                    + "file_name, layout, source, source_sha256, lines_read, lines_added, imported_at) "
                    + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
                ps.setString(1, importId);
                ps.setString(2, ledgerId);
                ps.setString(3, fileName);
                ps.setString(4, layout.name());
                ps.setString(5, csv);
                ps.setString(6, sha256(csv));
                ps.setInt(7, lines.size());
                ps.setInt(8, fresh.size());
                ps.setLong(9, clock.millis());
                ps.executeUpdate();
            }
            // The unique index (ledger, identity) is what stands behind the check above (R-27).
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO bank_statement_line (id, ledger_id, "
                    + "import_id, line_date, narration, reference, withdrawal_e4, deposit_e4, balance_e4, occurrence, "
                    + "identity) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
                for (int i : fresh) {
                    StatementLine l = lines.get(i);
                    String identity = identities.get(i);
                    ps.setString(1, Ids.newIdString());
                    ps.setString(2, ledgerId);
                    ps.setString(3, importId);
                    ps.setString(4, l.date().toString());
                    ps.setString(5, l.narration());
                    ps.setString(6, l.reference());
                    ps.setLong(7, money(l.withdrawal()));
                    ps.setLong(8, money(l.deposit()));
                    if (l.balance() == null) {
                        ps.setNull(9, java.sql.Types.INTEGER);
                    } else {
                        ps.setLong(9, money(l.balance()));
                    }
                    ps.setInt(10, Integer.parseInt(identity.substring(identity.lastIndexOf('|') + 1)));
                    ps.setString(11, identity);
                    ps.executeUpdate();
                }
            }
        }, "import the statement");
        return new ImportResult(importId, lines.size(), added[0], lines.size() - added[0]);
    }

    /** Statement lines from a date, oldest first, with whether each is matched. */
    public List<StoredLine> statementLines(String ledgerId, LocalDate from, LocalDate to) {
        return read(c -> lines(c, ledgerId, from, to, false));
    }

    // ========================================================= reconciliation

    /**
     * P10-3: for every unmatched statement line from the reconciliation start,
     * the best unmatched book entry of the same amount within {@value #WINDOW_DAYS}
     * days, scored on date, reference and party name. Proposed only.
     */
    public List<MatchSuggestion> suggestions(String ledgerId) {
        return read(c -> {
            BankAccount account = startedAccount(c, ledgerId);
            List<StoredLine> lines = lines(c, ledgerId, account.reconcileFrom(), null, true);
            List<BookEntry> entries = unmatchedEntries(c, ledgerId, account.reconcileFrom(), null);
            List<MatchSuggestion> result = new ArrayList<>();
            for (StoredLine line : lines) {
                List<BookEntry> same = entries.stream().filter(e -> e.net().compareTo(line.net()) == 0
                        && Math.abs(ChronoUnit.DAYS.between(e.date(), line.date())) <= WINDOW_DAYS).toList();
                if (same.isEmpty()) {
                    continue;
                }
                List<Object[]> scored = new ArrayList<>();
                for (BookEntry e : same) {
                    List<String> reasons = new ArrayList<>();
                    int score = score(line, e, reasons);
                    scored.add(new Object[]{e, score, reasons});
                }
                scored.sort(Comparator.comparingInt((Object[] o) -> (Integer) o[1]).reversed());
                BookEntry best = (BookEntry) scored.get(0)[0];
                @SuppressWarnings("unchecked")
                List<String> reasons = (List<String>) scored.get(0)[2];
                boolean certain = same.size() == 1 && referenceMatches(line, best);
                result.add(new MatchSuggestion(line, best, (Integer) scored.get(0)[1], reasons, certain,
                        same.stream().filter(e -> e != best).toList()));
            }
            return result;
        });
    }

    /**
     * Confirm a match: statement lines and book entries of this account whose
     * amounts agree exactly. Dated with the latest line's date - the bank's.
     */
    public String confirm(String ledgerId, List<String> lineIds, List<String> entryIds) {
        require("reconcile");
        if (lineIds.isEmpty() || entryIds.isEmpty()) {
            throw new BankingException("A match needs at least one statement line and one book entry");
        }
        if (lineIds.size() > 1 && entryIds.size() > 1) {
            throw new BankingException("Match one line to several entries, or several lines to one entry");
        }
        String id = Ids.newIdString();
        write(c -> {
            BankAccount account = startedAccount(c, ledgerId);
            BigDecimal linesNet = BigDecimal.ZERO;
            LocalDate cleared = null;
            for (String lineId : lineIds) {
                StoredLine l = line(c, ledgerId, lineId);
                if (l.matched()) {
                    throw new BankingException("The statement line of " + l.date() + " is already matched");
                }
                if (l.date().isBefore(account.reconcileFrom())) {
                    throw new BankingException("The statement line of " + l.date() + " is before reconciliation "
                            + "starts on " + account.reconcileFrom());
                }
                linesNet = linesNet.add(l.net());
                cleared = cleared == null || l.date().isAfter(cleared) ? l.date() : cleared;
            }
            BigDecimal entriesNet = BigDecimal.ZERO;
            for (String entryId : entryIds) {
                BookEntry e = entry(c, ledgerId, entryId);
                if (e.date().isBefore(account.reconcileFrom())) {
                    throw new BankingException("Voucher " + e.voucherNumber() + " is before reconciliation starts");
                }
                entriesNet = entriesNet.add(e.net());
            }
            if (linesNet.compareTo(entriesNet) != 0) {
                throw new BankingException("The statement says " + linesNet.toPlainString() + " and the books "
                        + entriesNet.toPlainString() + "; a match must agree exactly");
            }
            insertMatch(c, id, ledgerId, cleared, lineIds, entryIds);
        }, "confirm the match");
        return id;
    }

    /** P10-3: confirm every suggestion that is certain - unique, exact amount, exact reference. */
    public int confirmAllCertain(String ledgerId) {
        int n = 0;
        for (MatchSuggestion s : suggestions(ledgerId)) {
            if (s.certain()) {
                confirm(ledgerId, List.of(s.line().id()), List.of(s.entry().entryId()));
                n++;
            }
        }
        return n;
    }

    /** Remove a match, with a reason and a date; it stays on record. */
    public void unmatch(String matchId, String reason, LocalDate on) {
        require("reconcile");
        if (reason == null || reason.isBlank()) {
            throw new BankingException("Removing a match needs a reason");
        }
        write(c -> {
            try (PreparedStatement ps = c.prepareStatement("UPDATE bank_match SET removed_on = MAX(cleared_on, ?), "
                    + "removal_reason = ? WHERE id = ? AND removed_on IS NULL")) {
                ps.setString(1, on.toString());
                ps.setString(2, reason.strip());
                ps.setString(3, matchId);
                if (ps.executeUpdate() == 0) {
                    throw new BankingException("No such match, or it was already removed");
                }
            }
            for (String table : List.of("bank_match_line", "bank_match_entry")) {
                try (PreparedStatement ps = c.prepareStatement("UPDATE " + table + " SET active = 0 "
                        + "WHERE match_id = ?")) {
                    ps.setString(1, matchId);
                    ps.executeUpdate();
                }
            }
        }, "remove the match");
    }

    /**
     * P10-4: post a bank-only item - a charge, interest, a direct debit - from
     * its statement line, against a ledger the user chooses, dated the
     * statement's date, and match it at once.
     */
    public String postFromLine(String ledgerId, String lineId, String counterLedgerId, String narration) {
        require("post from a statement line");
        String matchId = Ids.newIdString();
        write(c -> {
            BankAccount account = startedAccount(c, ledgerId);
            StoredLine l = line(c, ledgerId, lineId);
            if (l.matched()) {
                throw new BankingException("The statement line of " + l.date() + " is already matched");
            }
            if (l.date().isBefore(account.reconcileFrom())) {
                throw new BankingException("The statement line is before reconciliation starts");
            }
            if (counterLedgerId.equals(ledgerId)) {
                throw new BankingException("Choose the ledger the charge or interest belongs to, not the bank's own");
            }
            boolean in = l.deposit().signum() > 0;
            BigDecimal amount = l.net().abs();
            String text = narration == null || narration.isBlank() ? l.narration() : narration.strip();
            PostedVoucher voucher = posting.postWithin(c, new PostingRequest(BankingModule.ID,
                    fiscalYear(c, l.date()), single(c, "SELECT id FROM voucher_type WHERE code = ?",
                    in ? "RECEIPT" : "PAYMENT"), l.date(), l.reference(), text,
                    in ? List.of(EntryLine.debit(ledgerId, amount), EntryLine.credit(counterLedgerId, amount))
                       : List.of(EntryLine.debit(counterLedgerId, amount), EntryLine.credit(ledgerId, amount)),
                    "bank-line:" + lineId));
            String entryId;
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT id FROM voucher_entry WHERE voucher_id = ? AND ledger_id = ?")) {
                ps.setString(1, voucher.voucherId());
                ps.setString(2, ledgerId);
                try (ResultSet r = ps.executeQuery()) {
                    r.next();
                    entryId = r.getString(1);
                }
            }
            insertMatch(c, matchId, ledgerId, l.date(), List.of(lineId), List.of(entryId));
        }, "post from the statement line");
        return matchId;
    }

    /**
     * The bank reconciliation statement as of {@code asOf}: balance per books,
     * the opening difference at the start (P10-5), cheques issued not presented,
     * deposits not credited and bank-only items, to the balance per bank.
     */
    public ReconciliationStatement statement(String ledgerId, LocalDate asOf) {
        return read(c -> {
            BankAccount account = startedAccount(c, ledgerId);
            BigDecimal books = booksBalance(c, ledgerId, asOf);
            BigDecimal booksAtStart = booksBalance(c, ledgerId, account.reconcileFrom().minusDays(1));
            BigDecimal opening = account.openingStatement().subtract(booksAtStart);
            List<BookEntry> issued = new ArrayList<>();
            List<BookEntry> deposited = new ArrayList<>();
            for (BookEntry e : unmatchedEntries(c, ledgerId, account.reconcileFrom(), asOf)) {
                (e.net().signum() < 0 ? issued : deposited).add(e);
            }
            List<StoredLine> bankOnly = lines(c, ledgerId, account.reconcileFrom(), asOf, false).stream()
                    .filter(l -> !matchedBy(c, l.id(), asOf)).toList();
            BigDecimal computed = books.add(opening)
                    .subtract(issued.stream().map(BookEntry::net).reduce(BigDecimal.ZERO, BigDecimal::add))
                    .subtract(deposited.stream().map(BookEntry::net).reduce(BigDecimal.ZERO, BigDecimal::add))
                    .add(bankOnly.stream().map(StoredLine::net).reduce(BigDecimal.ZERO, BigDecimal::add));
            return new ReconciliationStatement(ledgerId, asOf, books, opening, issued, deposited, bankOnly, computed,
                    statementBalance(c, ledgerId, asOf));
        });
    }

    // ================================================================ reads

    private static int score(StoredLine line, BookEntry e, List<String> reasons) {
        int score = 0;
        long days = Math.abs(ChronoUnit.DAYS.between(e.date(), line.date()));
        int dateScore = days == 0 ? 5 : days == 1 ? 3 : days <= 3 ? 1 : 0;
        if (dateScore > 0) {
            reasons.add(days == 0 ? "same day" : days + " day(s) apart");
        }
        score += dateScore;
        if (referenceMatches(line, e)) {
            score += 3;
            reasons.add("reference " + line.reference());
        }
        int party = 0;
        String narration = line.narration().toUpperCase(Locale.ROOT);
        for (String token : e.parties().toUpperCase(Locale.ROOT).split("[^A-Z0-9]+")) {
            if (token.length() >= 4 && narration.contains(token) && party < 3) {
                party++;
                reasons.add("names " + token.toLowerCase(Locale.ROOT));
            }
        }
        return score + party;
    }

    private static boolean referenceMatches(StoredLine line, BookEntry e) {
        if (line.reference() == null || e.references().isBlank()) {
            return false;
        }
        // Too short a reference proves nothing; leading zeros are a cheque book's padding (000045 is 45).
        if (line.reference().strip().length() < 3) {
            return false;
        }
        String ref = line.reference().strip().replaceFirst("^0+", "").toUpperCase(Locale.ROOT);
        for (String r : e.references().toUpperCase(Locale.ROOT).split("\\|")) {
            if (!r.isBlank() && r.strip().replaceFirst("^0+", "").equals(ref)) {
                return true;
            }
        }
        return false;
    }

    private static BankAccount accountRow(Connection c, String ledgerId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT b.ledger_id, l.name, b.bank_name, b.branch, "
                + "b.account_number, b.ifsc, b.account_type, b.reconcile_from, b.opening_statement_e4 "
                + "FROM bank_account b JOIN ledger l ON l.id = b.ledger_id WHERE b.ledger_id = ?")) {
            ps.setString(1, ledgerId);
            try (ResultSet r = ps.executeQuery()) {
                if (!r.next()) {
                    throw new BankingException("No bank details are recorded for this ledger");
                }
                long opening = r.getLong(9);
                boolean noOpening = r.wasNull();
                return new BankAccount(r.getString(1), r.getString(2), r.getString(3), r.getString(4),
                        r.getString(5), r.getString(6), r.getString(7),
                        r.getString(8) == null ? null : LocalDate.parse(r.getString(8)),
                        noOpening ? null : amount(opening));
            }
        }
    }

    private static BankAccount startedAccount(Connection c, String ledgerId) throws SQLException {
        BankAccount a = accountRow(c, ledgerId);
        if (a.reconcileFrom() == null) {
            throw new BankingException("Set the date reconciliation starts from, with the statement balance on it");
        }
        return a;
    }

    private static List<StoredLine> lines(Connection c, String ledgerId, LocalDate from, LocalDate to,
                                          boolean unmatchedOnly) throws SQLException {
        List<StoredLine> result = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement("SELECT s.id, s.line_date, s.narration, s.reference, "
                + "s.withdrawal_e4, s.deposit_e4, s.balance_e4, EXISTS (SELECT 1 FROM bank_match_line m "
                + "WHERE m.statement_line_id = s.id AND m.active = 1) FROM bank_statement_line s "
                + "WHERE s.ledger_id = ? AND s.line_date >= ? AND s.line_date <= ? ORDER BY s.line_date, s.rowid")) {
            ps.setString(1, ledgerId);
            ps.setString(2, from == null ? "0000-01-01" : from.toString());
            ps.setString(3, to == null ? "9999-12-31" : to.toString());
            try (ResultSet r = ps.executeQuery()) {
                while (r.next()) {
                    long balance = r.getLong(7);
                    boolean noBalance = r.wasNull();
                    StoredLine l = new StoredLine(r.getString(1), LocalDate.parse(r.getString(2)), r.getString(3),
                            r.getString(4), amount(r.getLong(5)), amount(r.getLong(6)),
                            noBalance ? null : amount(balance), r.getInt(8) == 1);
                    if (!unmatchedOnly || !l.matched()) {
                        result.add(l);
                    }
                }
            }
        }
        return result;
    }

    private static StoredLine line(Connection c, String ledgerId, String lineId) throws SQLException {
        return lines(c, ledgerId, null, null, false).stream().filter(l -> l.id().equals(lineId)).findFirst()
                .orElseThrow(() -> new BankingException("No such statement line on this account"));
    }

    /** As of a date: matched by a match cleared by then and not removed by then. */
    private static boolean matchedBy(Connection c, String lineId, LocalDate asOf) {
        try (PreparedStatement ps = c.prepareStatement("SELECT EXISTS (SELECT 1 FROM bank_match_line ml "
                + "JOIN bank_match m ON m.id = ml.match_id WHERE ml.statement_line_id = ? AND m.cleared_on <= ? "
                + "AND (m.removed_on IS NULL OR m.removed_on > ?))")) {
            ps.setString(1, lineId);
            ps.setString(2, asOf.toString());
            ps.setString(3, asOf.toString());
            try (ResultSet r = ps.executeQuery()) {
                r.next();
                return r.getInt(1) == 1;
            }
        } catch (SQLException e) {
            throw new BankingException("Could not read matches: " + e.getMessage(), e);
        }
    }

    private static final String ENTRY_SQL = """
            SELECT e.id, v.voucher_number, v.voucher_date, e.debit_amount_e4 - e.credit_amount_e4,
                   COALESCE(v.narration, ''),
                   COALESCE(v.reference_no, '') || '|' ||
                     COALESCE((SELECT reference_no FROM sales_settlement WHERE voucher_id = v.id), '') || '|' ||
                     COALESCE((SELECT reference_no FROM purchase_settlement WHERE voucher_id = v.id), ''),
                   COALESCE((SELECT group_concat(l.name, ' ') FROM voucher_entry o JOIN ledger l ON l.id = o.ledger_id
                             WHERE o.voucher_id = v.id AND o.ledger_id <> e.ledger_id), '')
            FROM voucher_entry e JOIN voucher v ON v.id = e.voucher_id
            WHERE e.ledger_id = ? AND v.is_posted = 1
            """;

    /**
     * Book entries on the ledger, dated from {@code from} to {@code to}, not in a
     * match live at {@code to} (or now, if null).
     */
    private static List<BookEntry> unmatchedEntries(Connection c, String ledgerId, LocalDate from, LocalDate to)
            throws SQLException {
        String live = to == null ? "m.removed_on IS NULL"
                : "m.cleared_on <= ?3 AND (m.removed_on IS NULL OR m.removed_on > ?3)";
        List<BookEntry> result = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(ENTRY_SQL.replace("e.ledger_id = ?", "e.ledger_id = ?1")
                + " AND v.voucher_date >= ?2" + (to == null ? "" : " AND v.voucher_date <= ?3")
                + " AND NOT EXISTS (SELECT 1 FROM bank_match_entry me JOIN bank_match m ON m.id = me.match_id "
                + "WHERE me.voucher_entry_id = e.id AND " + live + ") ORDER BY v.voucher_date, v.voucher_number")) {
            ps.setString(1, ledgerId);
            ps.setString(2, from.toString());
            if (to != null) {
                ps.setString(3, to.toString());
            }
            try (ResultSet r = ps.executeQuery()) {
                while (r.next()) {
                    result.add(entryRow(r));
                }
            }
        }
        return result;
    }

    private static BookEntry entry(Connection c, String ledgerId, String entryId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(ENTRY_SQL + " AND e.id = ?")) {
            ps.setString(1, ledgerId);
            ps.setString(2, entryId);
            try (ResultSet r = ps.executeQuery()) {
                if (!r.next()) {
                    throw new BankingException("No posted entry on this bank account: " + entryId);
                }
                BookEntry e = entryRow(r);
                try (PreparedStatement m = c.prepareStatement("SELECT EXISTS (SELECT 1 FROM bank_match_entry "
                        + "WHERE voucher_entry_id = ? AND active = 1)")) {
                    m.setString(1, entryId);
                    try (ResultSet mr = m.executeQuery()) {
                        mr.next();
                        if (mr.getInt(1) == 1) {
                            throw new BankingException("Voucher " + e.voucherNumber() + " is already matched");
                        }
                    }
                }
                return e;
            }
        }
    }

    private static BookEntry entryRow(ResultSet r) throws SQLException {
        return new BookEntry(r.getString(1), r.getString(2), LocalDate.parse(r.getString(3)), amount(r.getLong(4)),
                r.getString(5), r.getString(6), r.getString(7));
    }

    private static BigDecimal booksBalance(Connection c, String ledgerId, LocalDate asOf) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT COALESCE(SUM(e.debit_amount_e4 - e.credit_amount_e4), "
                + "0) FROM voucher_entry e JOIN voucher v ON v.id = e.voucher_id WHERE e.ledger_id = ? "
                + "AND v.is_posted = 1 AND v.voucher_date <= ?")) {
            ps.setString(1, ledgerId);
            ps.setString(2, asOf.toString());
            try (ResultSet r = ps.executeQuery()) {
                r.next();
                return amount(r.getLong(1));
            }
        }
    }

    /** The statement's own running balance at the end of {@code asOf}, if its lines carry one. */
    private static BigDecimal statementBalance(Connection c, String ledgerId, LocalDate asOf) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT balance_e4 FROM bank_statement_line WHERE "
                + "ledger_id = ? AND line_date <= ? AND balance_e4 IS NOT NULL ORDER BY line_date DESC, rowid DESC "
                + "LIMIT 1")) {
            ps.setString(1, ledgerId);
            ps.setString(2, asOf.toString());
            try (ResultSet r = ps.executeQuery()) {
                return r.next() ? amount(r.getLong(1)) : null;
            }
        }
    }

    private void insertMatch(Connection c, String id, String ledgerId, LocalDate cleared, List<String> lineIds,
                             List<String> entryIds) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO bank_match (id, ledger_id, cleared_on, "
                + "confirmed_at) VALUES (?, ?, ?, ?)")) {
            ps.setString(1, id);
            ps.setString(2, ledgerId);
            ps.setString(3, cleared.toString());
            ps.setLong(4, clock.millis());
            ps.executeUpdate();
        }
        Set<String> once = new HashSet<>();
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO bank_match_line (match_id, statement_line_id) "
                + "VALUES (?, ?)")) {
            for (String lineId : lineIds) {
                if (once.add(lineId)) {
                    ps.setString(1, id);
                    ps.setString(2, lineId);
                    ps.executeUpdate();
                }
            }
        }
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO bank_match_entry (match_id, voucher_entry_id) "
                + "VALUES (?, ?)")) {
            for (String entryId : entryIds) {
                if (once.add(entryId)) {
                    ps.setString(1, id);
                    ps.setString(2, entryId);
                    ps.executeUpdate();
                }
            }
        }
    }

    private static String fiscalYear(Connection c, LocalDate date) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT id, is_closed FROM fiscal_year "
                + "WHERE start_date <= ? AND end_date >= ?")) {
            ps.setString(1, date.toString());
            ps.setString(2, date.toString());
            try (ResultSet r = ps.executeQuery()) {
                if (!r.next()) {
                    throw new BankingException("No fiscal year covers " + date + ". Create that year first.");
                }
                if (r.getInt(2) == 1) {
                    throw new BankingException("The fiscal year containing " + date + " is closed");
                }
                return r.getString(1);
            }
        }
    }

    private static String single(Connection c, String sql, String parameter) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, parameter);
            try (ResultSet r = ps.executeQuery()) {
                if (!r.next()) {
                    throw new BankingException("Not found: " + parameter);
                }
                return r.getString(1);
            }
        }
    }

    private static BigDecimal amount(long stored) {
        return MoneyMapper.fromStored(stored, Scale.MONEY).setScale(2);
    }

    private static long money(BigDecimal amount) {
        return MoneyMapper.toStored(amount, Scale.MONEY);
    }

    static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private void require(String operation) {
        entitlement.requireWritable(BankingModule.ID, operation);
    }

    @FunctionalInterface
    private interface Work {
        void run(Connection connection) throws SQLException;
    }

    private void write(Work work, String what) {
        try {
            transactions.inTransaction(connection -> {
                work.run(connection);
                return null;
            });
        } catch (SQLException e) {
            throw new BankingException("Could not " + what + ": " + e.getMessage(), e);
        }
    }

    private <T> T read(ModuleTransactions.Work<T> work) {
        try {
            return transactions.read(work);
        } catch (SQLException e) {
            throw new BankingException("Could not read banking: " + e.getMessage(), e);
        }
    }
}
