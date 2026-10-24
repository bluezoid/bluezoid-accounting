package com.accubook.banking;

import com.accubook.module.FeatureModule;
import com.accubook.module.HistoricalView;
import com.accubook.module.HistoricalView.Column;
import com.accubook.module.HistoricalView.Column.Kind;
import com.accubook.module.ModuleId;

import java.util.List;
import java.util.Set;

/** Identity, table ownership and historical views of banking. */
public final class BankingModule implements FeatureModule {

    public static final ModuleId ID = ModuleId.of("banking");

    @Override
    public ModuleId id() {
        return ID;
    }

    @Override
    public String displayName() {
        return "Banking";
    }

    @Override
    public int navigationOrder() {
        return 30;
    }

    @Override
    public Set<String> ownedTables() {
        return Set.of("bank_account", "bank_statement_import", "bank_statement_line", "bank_match",
                "bank_match_line", "bank_match_entry");
    }

    /** Migration 18 created these tables. */
    @Override
    public int requiredSchemaVersion() {
        return 18;
    }

    /** A customer who drops banking can still read every statement line imported. */
    @Override
    public List<HistoricalView> historicalViews() {
        return List.of(new HistoricalView("Bank statement lines", "bank_statement_line", List.of(
                new Column("line_date", "Date", Kind.DATE),
                new Column("narration", "Narration", Kind.TEXT),
                new Column("reference", "Reference", Kind.TEXT),
                new Column("withdrawal_e4", "Withdrawal", Kind.MONEY),
                new Column("deposit_e4", "Deposit", Kind.MONEY),
                new Column("balance_e4", "Balance", Kind.MONEY)), "line_date", true));
    }
}
