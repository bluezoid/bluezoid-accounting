package com.accubook.banking;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Reads a bank statement CSV into lines, refusing anything it cannot read
 * exactly (P10-2).
 *
 * <ul>
 *   <li>The header row is found by its column names, after any preamble the
 *       bank prints above it.</li>
 *   <li>Amounts: commas, spaces, {@code ₹} and {@code INR} dropped; a {@code Dr}
 *       or {@code Cr} suffix and brackets read as signs. A cell that is still
 *       not a number to the paisa <b>refuses the file</b>, naming the row - the
 *       reference strips every I, N and R and imports such a cell as 0 (R-26).</li>
 *   <li>Rows with neither a withdrawal nor a deposit (opening and closing
 *       balance lines) are not transactions and are skipped.</li>
 *   <li>Where the file has running balances, each must follow from the one
 *       before; a statement listed newest first is read in date order. A break
 *       refuses the file.</li>
 * </ul>
 */
public final class StatementParser {

    private StatementParser() {
    }

    public static List<StatementLine> parse(String csv, StatementLayout layout) {
        List<List<String>> rows = rows(csv);
        int headerAt = -1;
        int[] col = null;
        for (int i = 0; i < rows.size() && headerAt < 0; i++) {
            int[] found = columns(rows.get(i), layout);
            if (found != null) {
                headerAt = i;
                col = found;
            }
        }
        if (headerAt < 0) {
            throw new BankingException("This is not a " + layout.name() + " statement: no row has the columns "
                    + layout.date() + ", " + layout.narration() + ", " + layout.withdrawal() + " and "
                    + layout.deposit());
        }
        List<StatementLine> lines = new ArrayList<>();
        for (int i = headerAt + 1; i < rows.size(); i++) {
            List<String> r = rows.get(i);
            int rowNo = i + 1;
            if (r.stream().allMatch(String::isBlank)) {
                continue;
            }
            String dateText = cell(r, col[0]);
            BigDecimal withdrawal = amount(cell(r, col[3]), rowNo, layout.withdrawal());
            BigDecimal deposit = amount(cell(r, col[4]), rowNo, layout.deposit());
            if (withdrawal.signum() == 0 && deposit.signum() == 0) {
                continue;
            }
            if (withdrawal.signum() < 0 || deposit.signum() < 0) {
                throw new BankingException("Row " + rowNo + ": a withdrawal or deposit cannot be negative");
            }
            if (withdrawal.signum() > 0 && deposit.signum() > 0) {
                throw new BankingException("Row " + rowNo + ": both a withdrawal and a deposit");
            }
            BigDecimal balance = col[5] < 0 || cell(r, col[5]).isBlank() ? null
                    : amount(cell(r, col[5]), rowNo, layout.balance());
            String narration = cell(r, col[1]).strip();
            String reference = col[2] < 0 ? "" : cell(r, col[2]).strip();
            lines.add(new StatementLine(rowNo, date(dateText, layout, rowNo), narration.isEmpty() ? "-" : narration,
                    reference.isEmpty() || reference.matches("0+") ? null : reference, withdrawal, deposit, balance));
        }
        return inOrder(lines);
    }

    /** P10-2: running balances must chain; a newest-first statement is turned round. */
    private static List<StatementLine> inOrder(List<StatementLine> lines) {
        if (lines.size() < 2 || lines.stream().anyMatch(l -> l.balance() == null)) {
            return lines;
        }
        if (chains(lines) < 0) {
            return lines;
        }
        List<StatementLine> reversed = new ArrayList<>(lines);
        Collections.reverse(reversed);
        if (chains(reversed) < 0) {
            return reversed;
        }
        StatementLine at = lines.get(chains(lines));
        throw new BankingException("Row " + at.row() + ": the balance " + at.balance().toPlainString()
                + " does not follow from the row before. The file is incomplete or altered; nothing was imported.");
    }

    /** The index of the first line whose balance does not follow, or -1 if they all do. */
    private static int chains(List<StatementLine> lines) {
        for (int i = 1; i < lines.size(); i++) {
            BigDecimal expected = lines.get(i - 1).balance().add(lines.get(i).net());
            if (expected.compareTo(lines.get(i).balance()) != 0) {
                return i;
            }
        }
        return -1;
    }

    /** R-26 put right: exact, signed, or refused. */
    static BigDecimal amount(String text, int row, String column) {
        String t = text == null ? "" : text.strip();
        if (t.isEmpty() || t.equals("-")) {
            return BigDecimal.ZERO;
        }
        boolean negative = false;
        String upper = t.toUpperCase(Locale.ROOT);
        if (upper.endsWith("DR")) {
            negative = true;
            t = t.substring(0, t.length() - 2);
        } else if (upper.endsWith("CR")) {
            t = t.substring(0, t.length() - 2);
        }
        t = t.strip();
        if (t.startsWith("(") && t.endsWith(")")) {
            negative = !negative;
            t = t.substring(1, t.length() - 1);
        }
        t = t.replace(",", "").replace("₹", "").replaceAll("(?i)^INR", "").replace(" ", "").strip();
        if (t.startsWith("-")) {
            negative = !negative;
            t = t.substring(1);
        }
        if (!t.matches("\\d+(\\.\\d{1,2})?")) {
            throw new BankingException("Row " + row + ": \"" + text + "\" in " + column
                    + " is not an amount. Nothing was imported.");
        }
        BigDecimal value = new BigDecimal(t);
        return negative ? value.negate() : value;
    }

    private static LocalDate date(String text, StatementLayout layout, int row) {
        String t = text.strip();
        for (String pattern : layout.datePatterns()) {
            try {
                return LocalDate.parse(t, DateTimeFormatter.ofPattern(pattern, Locale.ENGLISH));
            } catch (DateTimeParseException e) {
                // try the next pattern
            }
        }
        throw new BankingException("Row " + row + ": \"" + text + "\" is not a date in the " + layout.name()
                + " layout. Nothing was imported.");
    }

    private static int[] columns(List<String> header, StatementLayout layout) {
        String[] wanted = {layout.date(), layout.narration(), layout.reference(), layout.withdrawal(),
                layout.deposit(), layout.balance()};
        int[] at = new int[6];
        for (int w = 0; w < wanted.length; w++) {
            at[w] = -1;
            if (wanted[w] == null) {
                continue;
            }
            for (int c = 0; c < header.size(); c++) {
                if (key(header.get(c)).equals(key(wanted[w]))) {
                    at[w] = c;
                    break;
                }
            }
        }
        return at[0] >= 0 && at[1] >= 0 && at[3] >= 0 && at[4] >= 0 ? at : null;
    }

    private static String key(String name) {
        return name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    private static String cell(List<String> row, int index) {
        return index >= 0 && index < row.size() ? row.get(index) : "";
    }

    /** RFC 4180: quoted cells may hold commas, line breaks and doubled quotes. */
    static List<List<String>> rows(String csv) {
        String text = csv.startsWith("\uFEFF") ? csv.substring(1) : csv;
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quoted) {
                if (c == '"' && i + 1 < text.length() && text.charAt(i + 1) == '"') {
                    cell.append('"');
                    i++;
                } else if (c == '"') {
                    quoted = false;
                } else {
                    cell.append(c);
                }
            } else if (c == '"') {
                quoted = true;
            } else if (c == ',') {
                row.add(cell.toString());
                cell.setLength(0);
            } else if (c == '\n' || c == '\r') {
                if (c == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') {
                    i++;
                }
                row.add(cell.toString());
                cell.setLength(0);
                rows.add(row);
                row = new ArrayList<>();
            } else {
                cell.append(c);
            }
        }
        if (cell.length() > 0 || !row.isEmpty()) {
            row.add(cell.toString());
            rows.add(row);
        }
        return rows;
    }
}
