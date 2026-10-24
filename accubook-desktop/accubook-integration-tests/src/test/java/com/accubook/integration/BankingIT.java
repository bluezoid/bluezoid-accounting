package com.accubook.integration;

import com.accubook.accounting.party.Parties;
import com.accubook.accounting.party.Party;
import com.accubook.accounting.party.PartyRole;
import com.accubook.app.company.CompanyService;
import com.accubook.banking.BankAccount;
import com.accubook.banking.Banking;
import com.accubook.banking.BankingException;
import com.accubook.banking.ImportResult;
import com.accubook.banking.MatchSuggestion;
import com.accubook.banking.ReconciliationStatement;
import com.accubook.banking.StatementLayout;
import com.accubook.banking.StoredLine;
import com.accubook.module.EntitlementGuard;
import com.accubook.module.ModuleRegistry;
import com.accubook.module.ModuleState;
import com.accubook.module.ModuleTransactions;
import com.accubook.purchase.PurchaseBilling;
import com.accubook.sales.SalesInvoicing;
import com.accubook.tax.GstRegistration;
import com.accubook.tax.GstRegistrations;
import com.accubook.tax.RulePack;
import com.accubook.tax.RulePacks;
import com.accubook.trade.PaymentMode;
import com.accubook.trade.SettlementRequest;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Phase 10: a month at the bank - import, confirm, post charges, reconcile to nil. Figures by hand. */
@DisplayName("Banking")
class BankingIT {

    private static final RulePack RULES = RulePacks.bundledPack();
    private static final LocalDate MAY_1 = LocalDate.of(2026, 5, 1);
    private static final LocalDate MAY_5 = LocalDate.of(2026, 5, 5);

    /** HDFC's layout. Opening 1,00,000; NEFT in 11,800; cheque 45 out 5,900; two SMS charges of 17.70. */
    static final String STATEMENT = """
            HDFC BANK Ltd.,,,,,,
            Date,Narration,Chq./Ref.No.,Value Dt,Withdrawal Amt.,Deposit Amt.,Closing Balance
            01/05/26,OPENING BALANCE,,01/05/26,,,"1,00,000.00"
            02/05/26,"NEFT-MEHTA STORES, MUMBAI",N123456,02/05/26,,"11,800.00","1,11,800.00"
            03/05/26,CHQ PAID-RAO WHOLESALE,000045,03/05/26,"5,900.00",,"1,05,900.00"
            05/05/26,SMS CHARGES,,05/05/26,17.70,,"1,05,882.30"
            05/05/26,SMS CHARGES,,05/05/26,17.70,,"1,05,864.60"
            """;

    @TempDir
    Path dir;

    private CompanyService service;
    private ModuleTransactions transactions;
    private Banking banking;
    private SalesInvoicing sales;
    private PurchaseBilling purchases;
    private String bank;
    private String charges;
    private Party mehta;
    private Party rao;

    @BeforeEach
    void setUp() throws Exception {
        service = CompanyService.start(dir, ModuleRegistry.discover(List.of()), id -> ModuleState.ACTIVE);
        service.openCompany(service.createCompany("Asha Traders").id());
        transactions = service.openCompanyTransactions();
        EntitlementGuard guard = new EntitlementGuard(service.moduleStates());
        Clock clock = Clock.fixed(Instant.parse("2026-05-06T10:00:00Z"), ZoneOffset.UTC);
        new GstRegistrations(transactions, clock).register(GstRegistration.Scheme.UNREGISTERED, null, "27",
                LocalDate.of(2026, 4, 1), RULES);
        banking = new Banking(transactions, guard, clock);
        sales = new SalesInvoicing(transactions, guard, Optional.empty(), () -> RULES, clock);
        purchases = new PurchaseBilling(transactions, guard, Optional.empty(), () -> RULES, clock);
        bank = ledger("HDFC Current", "CASH_AND_BANK");
        charges = ledger("Bank Charges", "INDIRECT_EXPENSES");
        Parties parties = new Parties(transactions);
        mehta = parties.create("Mehta Stores", PartyRole.CUSTOMER, null, 0);
        rao = parties.create("Rao Wholesale", PartyRole.VENDOR, null, 0);
        banking.registerAccount(bank, "HDFC Bank", "Pune", "50100123456789", "HDFC0000123", "CURRENT");
        // The books before reconciliation starts: 1,00,000 already in the bank.
        receipt(LocalDate.of(2026, 4, 30), "100000", null);
        banking.startReconciliation(bank, MAY_1, new BigDecimal("100000"));
        receipt(LocalDate.of(2026, 5, 2), "11800", "N123456");
        payment(MAY_1, "5900", "45");
        payment(LocalDate.of(2026, 5, 4), "2000", "46");                  // not yet presented
    }

    @AfterEach
    void tearDown() throws Exception {
        service.close();
    }

    private String ledger(String name, String group) throws Exception {
        String id = java.util.UUID.randomUUID().toString();
        transactions.inTransaction(c -> {
            try (Statement s = c.createStatement()) {
                s.executeUpdate("INSERT INTO ledger (id, group_id, name, created_at, updated_at) SELECT '" + id
                        + "', id, '" + name + "', 0, 0 FROM ledger_group WHERE system_code = '" + group + "'");
            }
            return null;
        });
        return id;
    }

    private void receipt(LocalDate date, String amount, String reference) {
        sales.settlements().record(new SettlementRequest(mehta.id(), date, new BigDecimal(amount), bank,
                PaymentMode.NEFT, reference, null, null, List.of()));
    }

    private void payment(LocalDate date, String amount, String cheque) {
        purchases.settlements().record(new SettlementRequest(rao.id(), date, new BigDecimal(amount), bank,
                PaymentMode.CHEQUE, cheque, date, null, List.of()));
    }

    private List<StoredLine> lines() {
        return banking.statementLines(bank, MAY_1, null);
    }

    @Test
    @DisplayName("import, confirm the certain matches, post the charges, and the reconciliation comes to nil")
    void monthToNil() {
        ImportResult imported = banking.importStatement(bank, "may.csv", STATEMENT, StatementLayout.HDFC);
        assertThat(imported.linesAdded()).as("two identical SMS charges stay two (R-27)").isEqualTo(4);

        List<MatchSuggestion> suggestions = banking.suggestions(bank);
        assertThat(suggestions).hasSize(2).allMatch(MatchSuggestion::certain);
        assertThat(banking.confirmAllCertain(bank)).isEqualTo(2);

        for (StoredLine l : lines()) {
            if (!l.matched()) {
                banking.postFromLine(bank, l.id(), charges, null);
            }
        }

        ReconciliationStatement brs = banking.statement(bank, MAY_5);
        // Books: 1,00,000 + 11,800 - 5,900 - 2,000 - 35.40 = 1,03,864.60.
        assertThat(brs.balancePerBooks()).isEqualByComparingTo("103864.60");
        assertThat(brs.openingDifference()).isEqualByComparingTo("0");
        assertThat(brs.issuedNotPresented()).singleElement()
                .satisfies(e -> assertThat(e.net()).isEqualByComparingTo("-2000"));
        assertThat(brs.depositedNotCredited()).isEmpty();
        assertThat(brs.bankOnly()).isEmpty();
        assertThat(brs.balancePerBank()).isEqualByComparingTo("105864.60");
        assertThat(brs.difference()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("P10-2: an overlapping statement adds only its new lines")
    void overlappingImport() {
        banking.importStatement(bank, "may.csv", STATEMENT, StatementLayout.HDFC);

        ImportResult again = banking.importStatement(bank, "may-again.csv", STATEMENT
                + "06/05/26,INTEREST CREDIT,,06/05/26,,120.00,\"1,05,984.60\"\n", StatementLayout.HDFC);

        assertThat(again.linesAdded()).isEqualTo(1);
        assertThat(again.alreadyImported()).isEqualTo(4);
        assertThat(lines()).hasSize(5);
    }

    @Test
    @DisplayName("R-26: a file with an unreadable amount imports nothing")
    void unreadableFile() {
        assertThatThrownBy(() -> banking.importStatement(bank, "bad.csv", STATEMENT.replace("17.70,,\"1,05,882.30\"",
                "17.7O,,\"1,05,882.30\""), StatementLayout.HDFC)).isInstanceOf(BankingException.class)
                .hasMessageContaining("not an amount");
        assertThat(lines()).isEmpty();
    }

    @Test
    @DisplayName("a removed match puts the items back on the statement; the difference stays nil")
    void unmatch() {
        banking.importStatement(bank, "may.csv", STATEMENT, StatementLayout.HDFC);
        MatchSuggestion deposit = banking.suggestions(bank).stream()
                .filter(s -> s.line().deposit().signum() > 0).findFirst().orElseThrow();
        String match = banking.confirm(bank, List.of(deposit.line().id()), List.of(deposit.entry().entryId()));

        banking.unmatch(match, "Matched to the wrong receipt", MAY_5);

        ReconciliationStatement brs = banking.statement(bank, MAY_5);
        assertThat(brs.depositedNotCredited()).hasSize(1);
        assertThat(brs.bankOnly()).anySatisfy(l -> assertThat(l.deposit()).isEqualByComparingTo("11800"));
        assertThat(brs.difference()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("a match must agree exactly; several lines can match one entry; the start is fixed once matched")
    void rules() {
        banking.importStatement(bank, "may.csv", STATEMENT, StatementLayout.HDFC);
        List<StoredLine> ls = lines();
        StoredLine charge1 = ls.get(2);
        StoredLine charge2 = ls.get(3);
        MatchSuggestion deposit = banking.suggestions(bank).get(0);

        assertThatThrownBy(() -> banking.confirm(bank, List.of(charge1.id()), List.of(deposit.entry().entryId())))
                .hasMessageContaining("must agree exactly");

        // One book entry of 35.40 for both charges.
        payment(MAY_5, "35.40", null);
        String entry = banking.statement(bank, MAY_5).issuedNotPresented().stream()
                .filter(e -> e.net().compareTo(new BigDecimal("-35.40")) == 0).findFirst().orElseThrow().entryId();
        banking.confirm(bank, List.of(charge1.id(), charge2.id()), List.of(entry));

        assertThatThrownBy(() -> banking.startReconciliation(bank, LocalDate.of(2026, 4, 1), BigDecimal.ZERO))
                .hasMessageContaining("start date and balance are fixed");
        BankAccount account = banking.account(bank);
        assertThat(account.reconcileFrom()).isEqualTo(MAY_1);
    }

    @Test
    @DisplayName("P10-3: a unique same-amount entry without a matching reference is suggested, not certain")
    void notCertainWithoutReference() {
        receipt(MAY_5, "500", null);
        banking.importStatement(bank, "cash.csv", """
                Date,Narration,Chq./Ref.No.,Value Dt,Withdrawal Amt.,Deposit Amt.,Closing Balance
                05/05/26,CASH DEPOSIT,,05/05/26,,500.00,"1,00,500.00"
                """, StatementLayout.HDFC);

        assertThat(banking.suggestions(bank)).singleElement().satisfies(s -> {
            assertThat(s.certain()).isFalse();
            assertThat(s.reasons()).contains("same day");
        });
        assertThat(banking.confirmAllCertain(bank)).isZero();
    }
}
