package com.accubook.banking;

import java.util.List;

/**
 * Which columns of a statement CSV hold what (P10-1). The four bank layouts are
 * as their CSV downloads name the columns; a generic layout is mapped by the
 * user. Column names are compared ignoring case, spaces and punctuation.
 *
 * @param datePatterns date formats tried in order, e.g. {@code dd/MM/yy}
 * @param balance      the running balance column, or null if there is none
 */
public record StatementLayout(String name, String date, String narration, String reference, String withdrawal,
                              String deposit, String balance, List<String> datePatterns) {

    public static final StatementLayout HDFC = new StatementLayout("HDFC", "Date", "Narration", "Chq./Ref.No.",
            "Withdrawal Amt.", "Deposit Amt.", "Closing Balance", List.of("dd/MM/yy", "dd/MM/yyyy"));
    public static final StatementLayout ICICI = new StatementLayout("ICICI", "Transaction Date",
            "Transaction Remarks", "Cheque Number", "Withdrawal Amount (INR )", "Deposit Amount (INR )",
            "Balance (INR )", List.of("dd/MM/yyyy", "dd-MM-yyyy"));
    public static final StatementLayout SBI = new StatementLayout("SBI", "Txn Date", "Description",
            "Ref No./Cheque No.", "Debit", "Credit", "Balance", List.of("d MMM yyyy", "dd-MM-yyyy", "dd/MM/yyyy"));
    public static final StatementLayout AXIS = new StatementLayout("AXIS", "Tran Date", "PARTICULARS", "CHQNO", "DR",
            "CR", "BAL", List.of("dd-MM-yyyy", "dd/MM/yyyy"));

    public StatementLayout {
        datePatterns = List.copyOf(datePatterns);
    }

    public static List<StatementLayout> banks() {
        return List.of(HDFC, ICICI, SBI, AXIS);
    }
}
