package com.accubook.app.provisioning;

import com.accubook.persistence.connection.Database;
import com.accubook.platform.id.Ids;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What a usable company contains on the day it is created.
 *
 * <p>Modelled on the reference application's {@code provisionOrganization},
 * which exists because its seed and its registration endpoint each built a
 * tenant differently - and the shorter one omitted every group the posting layer
 * looks up, so a self-registered tenant could not record a single payment.
 *
 * <p>One important departure. The reference resolves these groups and ledgers by
 * <em>display name</em>, so renaming "Cash &amp; Bank" breaks posting at runtime
 * rather than compile time. Here each carries a stable {@code system_code} and
 * the name is free for the user to change.
 */
public final class CompanyProvisioning {

    /** Groups the posting layer will resolve by code. Renaming the display name is safe. */
    public static final String CODE_CASH_AND_BANK = "CASH_AND_BANK";
    public static final String CODE_SUNDRY_DEBTORS = "SUNDRY_DEBTORS";
    public static final String CODE_SUNDRY_CREDITORS = "SUNDRY_CREDITORS";
    public static final String CODE_DUTIES_AND_TAXES = "DUTIES_AND_TAXES";

    /**
     * The default chart of accounts.
     *
     * <p>Ordered parent-before-child so a single pass can resolve parents.
     * {@code affectsGrossProfit} is set only on Direct Expenses: the reference
     * records that without it, a trader buying at 15,000 and selling at 70,000
     * showed a 100% gross margin instead of 78.6%.
     */
    private static final List<GroupSeed> GROUPS = List.of(
            new GroupSeed("ASSETS_ROOT", "Assets", "ASSETS", null, false),
            new GroupSeed("CURRENT_ASSETS", "Current Assets", "ASSETS", "ASSETS_ROOT", false),
            new GroupSeed(CODE_CASH_AND_BANK, "Cash & Bank", "ASSETS", "CURRENT_ASSETS", false),
            new GroupSeed(CODE_SUNDRY_DEBTORS, "Sundry Debtors", "ASSETS", "CURRENT_ASSETS", false),
            new GroupSeed("STOCK_IN_HAND", "Stock-in-Hand", "ASSETS", "CURRENT_ASSETS", false),
            new GroupSeed("FIXED_ASSETS", "Fixed Assets", "ASSETS", "ASSETS_ROOT", false),

            new GroupSeed("LIABILITIES_ROOT", "Liabilities", "LIABILITIES", null, false),
            new GroupSeed("CURRENT_LIABILITIES", "Current Liabilities", "LIABILITIES", "LIABILITIES_ROOT", false),
            new GroupSeed(CODE_SUNDRY_CREDITORS, "Sundry Creditors", "LIABILITIES", "CURRENT_LIABILITIES", false),
            new GroupSeed(CODE_DUTIES_AND_TAXES, "Duties & Taxes", "LIABILITIES", "CURRENT_LIABILITIES", false),
            new GroupSeed("LOANS_LIABILITY", "Loans (Liability)", "LIABILITIES", "LIABILITIES_ROOT", false),

            new GroupSeed("INCOME_ROOT", "Income", "INCOME", null, false),
            new GroupSeed("SALES_ACCOUNTS", "Sales Accounts", "INCOME", "INCOME_ROOT", false),
            new GroupSeed("OTHER_INCOME", "Other Income", "INCOME", "INCOME_ROOT", false),

            new GroupSeed("EXPENSES_ROOT", "Expenses", "EXPENSES", null, false),
            new GroupSeed("DIRECT_EXPENSES", "Direct Expenses", "EXPENSES", "EXPENSES_ROOT", true),
            new GroupSeed("INDIRECT_EXPENSES", "Indirect Expenses", "EXPENSES", "EXPENSES_ROOT", false),

            new GroupSeed("CAPITAL_ACCOUNT", "Capital Account", "EQUITY", null, false)
    );

    /**
     * Ledgers the core needs.
     *
     * <p>Only those the accounting core itself will use. Module ledgers - GST
     * Input/Output, PF Payable, Purchase Accounts - arrive with their modules,
     * per decision P2-1.
     */
    private static final List<LedgerSeed> LEDGERS = List.of(
            new LedgerSeed("CASH_IN_HAND", "Cash in Hand", CODE_CASH_AND_BANK),
            // Contra for balances that exist before the books begin. Without
            // somewhere to put the other half, an opening balance leaves the
            // trial balance out by its own amount.
            new LedgerSeed("OPENING_BALANCE_EQUITY", "Opening Balance Equity", "CAPITAL_ACCOUNT"),
            new LedgerSeed("RETAINED_EARNINGS", "Retained Earnings", "CAPITAL_ACCOUNT"),
            new LedgerSeed("ROUND_OFF", "Round Off", "INDIRECT_EXPENSES")
    );

    /** The eight standard voucher types, per company rather than global. */
    private static final List<VoucherTypeSeed> VOUCHER_TYPES = List.of(
            new VoucherTypeSeed("PAYMENT", "Payment", "PAYMENT", "PAY"),
            new VoucherTypeSeed("RECEIPT", "Receipt", "RECEIPT", "RCV"),
            new VoucherTypeSeed("CONTRA", "Contra", "CONTRA", "CON"),
            new VoucherTypeSeed("JOURNAL", "Journal", "JOURNAL", "JV"),
            new VoucherTypeSeed("SALES", "Sales", "SALES", "SAL"),
            new VoucherTypeSeed("PURCHASE", "Purchase", "PURCHASE", "PUR"),
            new VoucherTypeSeed("DEBIT_NOTE", "Debit Note", "DEBIT_NOTE", "DN"),
            new VoucherTypeSeed("CREDIT_NOTE", "Credit Note", "CREDIT_NOTE", "CN")
    );

    private CompanyProvisioning() {
    }

    /**
     * Populate a newly created company database.
     *
     * <p>Runs inside the caller's transaction so a partial company is never
     * left behind.
     */
    public static void provision(Connection connection, String companyId, String companyName,
                                 int fiscalYearStartMonth, LocalDate on) throws SQLException {
        long now = System.currentTimeMillis();
        insertCompanyInfo(connection, companyId, companyName, fiscalYearStartMonth, now);
        Map<String, String> groupIds = insertGroups(connection, now);
        insertLedgers(connection, groupIds, now);
        insertVoucherTypes(connection, now);
        insertFiscalYear(connection, FiscalYearBounds.forDate(on, fiscalYearStartMonth), now);
    }

    private static void insertCompanyInfo(Connection connection, String id, String name,
                                          int fiscalYearStartMonth, long now) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO company_info (id, name, fiscal_year_start_month, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?)")) {
            ps.setString(1, id);
            ps.setString(2, name);
            ps.setInt(3, fiscalYearStartMonth);
            ps.setLong(4, now);
            ps.setLong(5, now);
            ps.executeUpdate();
        }
    }

    private static Map<String, String> insertGroups(Connection connection, long now) throws SQLException {
        Map<String, String> idsByCode = new LinkedHashMap<>();
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO ledger_group (id, parent_id, system_code, name, nature, "
                        + "affects_gross_profit, is_system, sequence, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, 1, ?, ?, ?)")) {
            int sequence = 0;
            for (GroupSeed seed : GROUPS) {
                String id = Ids.newIdString();
                idsByCode.put(seed.code(), id);
                ps.setString(1, id);
                ps.setString(2, seed.parentCode() == null ? null : idsByCode.get(seed.parentCode()));
                ps.setString(3, seed.code());
                ps.setString(4, seed.name());
                ps.setString(5, seed.nature());
                ps.setInt(6, seed.affectsGrossProfit() ? 1 : 0);
                ps.setInt(7, sequence++);
                ps.setLong(8, now);
                ps.setLong(9, now);
                ps.executeUpdate();
            }
        }
        return idsByCode;
    }

    private static void insertLedgers(Connection connection, Map<String, String> groupIds, long now)
            throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                // is_system = 1: every seeded ledger is one a module resolves
                // by system_code, so none may be deleted by a customer.
                "INSERT INTO ledger (id, group_id, system_code, name, is_system, "
                        + "created_at, updated_at) VALUES (?, ?, ?, ?, 1, ?, ?)")) {
            for (LedgerSeed seed : LEDGERS) {
                String groupId = groupIds.get(seed.groupCode());
                if (groupId == null) {
                    // A programming error, not a data condition: the seed lists
                    // are compiled together.
                    throw new IllegalStateException(
                            "Ledger " + seed.code() + " names unknown group " + seed.groupCode());
                }
                ps.setString(1, Ids.newIdString());
                ps.setString(2, groupId);
                ps.setString(3, seed.code());
                ps.setString(4, seed.name());
                ps.setLong(5, now);
                ps.setLong(6, now);
                ps.executeUpdate();
            }
        }
    }

    private static void insertVoucherTypes(Connection connection, long now) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO voucher_type (id, code, name, nature, numbering_prefix, "
                        + "is_system, created_at, updated_at) VALUES (?, ?, ?, ?, ?, 1, ?, ?)")) {
            for (VoucherTypeSeed seed : VOUCHER_TYPES) {
                ps.setString(1, Ids.newIdString());
                ps.setString(2, seed.code());
                ps.setString(3, seed.name());
                ps.setString(4, seed.nature());
                ps.setString(5, seed.prefix());
                ps.setLong(6, now);
                ps.setLong(7, now);
                ps.executeUpdate();
            }
        }
    }

    private static void insertFiscalYear(Connection connection, FiscalYearBounds bounds, long now)
            throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO fiscal_year (id, name, start_date, end_date, created_at) "
                        + "VALUES (?, ?, ?, ?, ?)")) {
            ps.setString(1, Ids.newIdString());
            ps.setString(2, bounds.name());
            ps.setString(3, bounds.startDate().toString());
            ps.setString(4, bounds.endDate().toString());
            ps.setLong(5, now);
            ps.executeUpdate();
        }
    }

    private record GroupSeed(String code, String name, String nature, String parentCode,
                             boolean affectsGrossProfit) {
    }

    private record LedgerSeed(String code, String name, String groupCode) {
    }

    private record VoucherTypeSeed(String code, String name, String nature, String prefix) {
    }
}
