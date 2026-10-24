package com.accubook.tax;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WithholdingTest {

    private static final RulePack RULES = RulePacks.bundledPack();
    private static final LocalDate ON = LocalDate.of(2026, 6, 1);
    private static final String INDIVIDUAL = "ABCPK1234L";
    private static final String COMPANY = "ABCCK1234L";

    private static BigDecimal d(String v) {
        return new BigDecimal(v);
    }

    private static Withholding.Result tds(String code, String pan, String base, String priorBase, String priorTaxed) {
        WithholdingSection section = Withholding.resolve(RULES, WithholdingSection.Kind.TDS, code, pan, ON);
        return Withholding.compute(section, pan, d(base), new Withholding.Prior(d(priorBase), d(priorTaxed)));
    }

    @Test
    @DisplayName("PAN: format and holder status; the fourth letter decides individual or HUF")
    void pan() {
        assertThat(Pan.problem(INDIVIDUAL)).isEmpty();
        assertThat(Pan.problem("abcpk1234l")).isEmpty();
        assertThat(Pan.problem("ABCXK1234L")).isPresent();
        assertThat(Pan.problem("ABCPK12345")).isPresent();
        assertThat(Pan.individualOrHuf(INDIVIDUAL)).isTrue();
        assertThat(Pan.individualOrHuf("ABCHK1234L")).isTrue();
        assertThat(Pan.individualOrHuf(COMPANY)).isFalse();
    }

    @Test
    @DisplayName("194C resolves to 1% for an individual, 2% for a company, from the PAN")
    void resolvesByPan() {
        assertThat(tds("194C", INDIVIDUAL, "40000", "0", "0").amount()).isEqualByComparingTo("400.00");
        assertThat(tds("194C", COMPANY, "40000", "0", "0").amount()).isEqualByComparingTo("800.00");
        assertThat(tds("194C", COMPANY, "40000", "0", "0").section().section()).isEqualTo("194C-OTH");
    }

    @Test
    @DisplayName("no PAN: the no-PAN rate, 20%")
    void noPan() {
        Withholding.Result r = tds("194C", null, "40000", "0", "0");
        assertThat(r.rate()).isEqualByComparingTo("20");
        assertThat(r.amount()).isEqualByComparingTo("8000.00");
    }

    @Test
    @DisplayName("single limit is strict: 30,000 exactly is not taxed, 30,000.01 is")
    void singleLimitStrict() {
        assertThat(tds("194C", COMPANY, "30000", "0", "0").amount()).isEqualByComparingTo("0");
        assertThat(tds("194C", COMPANY, "30000.01", "0", "0").amount()).isEqualByComparingTo("600.00");
    }

    @Test
    @DisplayName("P9-12: crossing the 1 lakh annual limit also taxes the year's earlier untaxed bills")
    void catchUp() {
        // Earlier this year: 25,000 + 25,000 + 25,000, none taxed. Now 26,000: total 101,000 > 100,000.
        Withholding.Result r = tds("194C", COMPANY, "26000", "75000", "0");
        assertThat(r.taxedBase()).isEqualByComparingTo("101000");
        assertThat(r.amount()).isEqualByComparingTo("2020.00");
        // After the catch-up, the next bill is taxed on itself only.
        assertThat(tds("194C", COMPANY, "5000", "101000", "101000").amount()).isEqualByComparingTo("100.00");
    }

    @Test
    @DisplayName("an earlier bill taxed for its own size is not taxed again in the catch-up")
    void catchUpSkipsTaxed() {
        // Earlier: 35,000 (taxed, over the single limit) + 20,000 (untaxed). Now 50,000: total 105,000.
        Withholding.Result r = tds("194C", COMPANY, "50000", "55000", "35000");
        assertThat(r.taxedBase()).isEqualByComparingTo("70000");
    }

    @Test
    @DisplayName("194J from 1 Apr 2025: 10% on fees over 50,000, no annual limit")
    void professional() {
        assertThat(tds("194J-PROF", INDIVIDUAL, "50000", "0", "0").amount()).isEqualByComparingTo("0");
        assertThat(tds("194J-PROF", INDIVIDUAL, "60000", "200000", "0").taxedBase())
                .as("no annual catch-up").isEqualByComparingTo("60000");
    }

    @Test
    @DisplayName("194Q: only the part of the year's purchases above 50 lakh")
    void excessOnly() {
        assertThat(tds("194Q", COMPANY, "1000000", "4500000", "0").taxedBase()).isEqualByComparingTo("500000");
        assertThat(tds("194Q", COMPANY, "1000000", "4500000", "0").amount()).isEqualByComparingTo("500.00");
        assertThat(tds("194Q", COMPANY, "1000000", "6000000", "1000000").taxedBase()).isEqualByComparingTo("1000000");
        assertThat(tds("194Q", COMPANY, "100000", "0", "0").amount()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("194I from 1 Apr 2025: rent over 50,000 in a month")
    void monthlyRent() {
        assertThat(tds("194I-LAND", COMPANY, "50000", "0", "0").amount()).isEqualByComparingTo("0");
        assertThat(tds("194I-LAND", COMPANY, "30000", "30000", "0").amount())
                .as("the month's two bills, 60,000").isEqualByComparingTo("6000.00");
    }

    @Test
    @DisplayName("rounded half-up to the paisa (P9-11)")
    void paisa() {
        // 2% of 30,000.25 = 600.005 -> 600.01
        assertThat(tds("194C", COMPANY, "30000.25", "0", "0").amount()).isEqualByComparingTo("600.01");
    }

    @Test
    @DisplayName("a section not in force on the date is refused - 206C(1H) after 31 Mar 2025")
    void notInForce() {
        assertThatThrownBy(() -> Withholding.resolve(RULES, WithholdingSection.Kind.TCS, "206C(1H)", COMPANY, ON))
                .hasMessageContaining("not in force on 2026-06-01");
        assertThatThrownBy(() -> Withholding.resolve(RULES, WithholdingSection.Kind.TDS, "194Z", COMPANY, ON))
                .hasMessageContaining("194Z");
    }

    @Test
    @DisplayName("the financial year starts in April")
    void yearStart() {
        assertThat(Withholding.yearStart(LocalDate.of(2027, 3, 31))).isEqualTo(LocalDate.of(2026, 4, 1));
        assertThat(Withholding.yearStart(LocalDate.of(2026, 4, 1))).isEqualTo(LocalDate.of(2026, 4, 1));
    }

    @Test
    @DisplayName("without a PAN the rate is 20%, but the single limit still applies")
    void noPanKeepsThreshold() {
        assertThat(tds("194C", null, "25000", "0", "0").amount()).isEqualByComparingTo("0");
        assertThat(tds("194C", null, "30000.01", "0", "0").amount()).isEqualByComparingTo("6000.00");
    }

    @Test
    @DisplayName("194I on plant and machinery is 2%, on land and buildings 10%")
    void rentRates() {
        assertThat(tds("194I-PM", COMPANY, "60000", "0", "0").amount()).isEqualByComparingTo("1200.00");
        assertThat(tds("194I-LAND", COMPANY, "60000", "0", "0").amount()).isEqualByComparingTo("6000.00");
    }
}
