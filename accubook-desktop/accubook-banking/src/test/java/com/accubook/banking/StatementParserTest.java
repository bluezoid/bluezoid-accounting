package com.accubook.banking;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StatementParserTest {

    static final String HDFC = """
            HDFC BANK Ltd.,,,,,,
            Statement of account,,,,,,
            Date,Narration,Chq./Ref.No.,Value Dt,Withdrawal Amt.,Deposit Amt.,Closing Balance
            01/05/26,OPENING BALANCE,,01/05/26,,,"1,00,000.00"
            02/05/26,"NEFT-MEHTA STORES, MUMBAI",N123456,02/05/26,,"11,800.00","1,11,800.00"
            03/05/26,CHQ PAID-RAO WHOLESALE,000045,03/05/26,"5,900.00",,"1,05,900.00"
            05/05/26,SMS CHARGES,,05/05/26,17.70,,"1,05,882.30"
            05/05/26,SMS CHARGES,,05/05/26,17.70,,"1,05,864.60"
            """;

    private static BigDecimal d(String v) {
        return new BigDecimal(v);
    }

    @Test
    @DisplayName("an HDFC statement: preamble skipped, balance rows skipped, commas and quotes read")
    void hdfc() {
        List<StatementLine> lines = StatementParser.parse(HDFC, StatementLayout.HDFC);

        assertThat(lines).hasSize(4);
        assertThat(lines.get(0).date()).isEqualTo(LocalDate.of(2026, 5, 2));
        assertThat(lines.get(0).narration()).isEqualTo("NEFT-MEHTA STORES, MUMBAI");
        assertThat(lines.get(0).deposit()).isEqualByComparingTo("11800");
        assertThat(lines.get(1).withdrawal()).isEqualByComparingTo("5900");
        assertThat(lines.get(1).reference()).isEqualTo("000045");
        assertThat(lines.get(2).balance()).isEqualByComparingTo("105882.30");
        assertThat(lines.get(3).withdrawal()).as("two identical charges stay two").isEqualByComparingTo("17.70");
    }

    @Test
    @DisplayName("R-26: Dr/Cr suffixes and brackets are signs; an unreadable amount refuses the file")
    void amounts() {
        assertThat(StatementParser.amount("500.00 Dr", 1, "x")).isEqualByComparingTo("-500");
        assertThat(StatementParser.amount("1,234.50Cr", 1, "x")).isEqualByComparingTo("1234.50");
        assertThat(StatementParser.amount("(500.00)", 1, "x")).isEqualByComparingTo("-500");
        assertThat(StatementParser.amount("₹ 1,00,000", 1, "x")).isEqualByComparingTo("100000");
        assertThat(StatementParser.amount("INR 250.5", 1, "x")).isEqualByComparingTo("250.50");
        assertThat(StatementParser.amount("", 1, "x")).isEqualByComparingTo("0");
        assertThatThrownBy(() -> StatementParser.amount("12.345", 7, "Deposit Amt."))
                .hasMessageContaining("Row 7").hasMessageContaining("not an amount");
        assertThatThrownBy(() -> StatementParser.amount("N/A", 7, "Deposit Amt."))
                .hasMessageContaining("Nothing was imported");
    }

    @Test
    @DisplayName("P10-2: a balance that does not follow from the row before refuses the file")
    void balanceBreak() {
        String broken = HDFC.replace("\"1,05,900.00\"", "\"1,05,000.00\"");

        assertThatThrownBy(() -> StatementParser.parse(broken, StatementLayout.HDFC))
                .hasMessageContaining("does not follow").hasMessageContaining("nothing was imported");
    }

    @Test
    @DisplayName("a statement listed newest first is read in date order")
    void newestFirst() {
        String sbi = """
                Txn Date,Value Date,Description,Ref No./Cheque No.,Debit,Credit,Balance
                5 May 2026,5 May 2026,BANK CHARGES,,59.00,,9941.00
                2 May 2026,2 May 2026,UPI/RENT,UPI1,,5000.00,10000.00
                1 May 2026,1 May 2026,ATM CASH,ATM9,1000.00,,5000.00
                """;

        List<StatementLine> lines = StatementParser.parse(sbi, StatementLayout.SBI);

        assertThat(lines).extracting(StatementLine::date).containsExactly(LocalDate.of(2026, 5, 1),
                LocalDate.of(2026, 5, 2), LocalDate.of(2026, 5, 5));
    }

    @Test
    @DisplayName("not this layout, or a bad date: refused with the reason")
    void refusals() {
        assertThatThrownBy(() -> StatementParser.parse(HDFC, StatementLayout.AXIS))
                .hasMessageContaining("not a AXIS statement");
        assertThatThrownBy(() -> StatementParser.parse(HDFC.replace("03/05/26", "2026-05-03"), StatementLayout.HDFC))
                .hasMessageContaining("is not a date");
        assertThatThrownBy(() -> StatementParser.parse(HDFC.replace(",\"11,800.00\"", "\"500.00\",\"11,800.00\""),
                StatementLayout.HDFC)).hasMessageContaining("both a withdrawal and a deposit");
    }

    @Test
    @DisplayName("RFC 4180: doubled quotes and line breaks inside a quoted cell")
    void csv() {
        assertThat(StatementParser.rows("a,\"b \"\"q\"\"\nc\",d\r\ne,f")).containsExactly(
                List.of("a", "b \"q\"\nc", "d"), List.of("e", "f"));
    }

    @Test
    @DisplayName("P10-1: a layout the user maps - here a co-operative bank's columns, with no reference and no balance")
    void generic() {
        StatementLayout mapped = new StatementLayout("Saraswat", "Value Date", "Particulars", null, "Paid Out",
                "Paid In", null, List.of("yyyy-MM-dd"));

        List<StatementLine> lines = StatementParser.parse("""
                Value Date,Particulars,Paid Out,Paid In
                2026-05-02,RTGS FROM MEHTA,,"2,50,000.00"
                2026-05-03,ATM WDL,"10,000",
                """, mapped);

        assertThat(lines).extracting(StatementLine::net).containsExactly(d("250000.00"), d("-10000"));
        assertThat(lines).allMatch(l -> l.reference() == null && l.balance() == null);
    }
}
