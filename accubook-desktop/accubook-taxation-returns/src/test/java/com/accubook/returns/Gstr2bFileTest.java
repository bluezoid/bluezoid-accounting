package com.accubook.returns;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class Gstr2bFileTest {

    static final String SAMPLE = """
            {"data": {"gstin": "27PQRSX5678K1ZY", "rtnprd": "052026", "docdata": {
              "b2b": [{"ctin": "27LMNOP4321Q1Z4", "trdnm": "Rao Wholesale", "inv": [
                {"inum": "inv-12", "dt": "05-05-2026", "val": 1180, "pos": "27", "rev": "N", "itcavl": "Y",
                 "items": [{"num": 1, "rt": 18, "txval": 1000, "igst": 0, "cgst": 90, "sgst": 90, "cess": 0}]}]}],
              "cdnr": [{"ctin": "27LMNOP4321Q1Z4", "nt": [
                {"ntnum": "CN-3", "typ": "C", "dt": "20-05-2026", "val": 236.00, "rev": "N", "itcavl": "Y",
                 "items": [{"rt": 18, "txval": 200, "cgst": 18, "sgst": 18}]}]}]}}}
            """;

    @Test
    @DisplayName("reads B2B invoices and CDNR notes with the 2B field names")
    void parses() {
        Gstr2bFile f = Gstr2bFile.parse(SAMPLE);

        assertThat(f.period()).isEqualTo(ReturnPeriod.month(2026, 5));
        assertThat(f.gstin()).isEqualTo("27PQRSX5678K1ZY");
        assertThat(f.documents()).hasSize(2);
        Gstr2bFile.Doc inv = f.documents().get(0);
        assertThat(inv.type()).isEqualTo("INV");
        assertThat(inv.date()).isEqualTo(LocalDate.of(2026, 5, 5));
        assertThat(inv.cgst()).isEqualByComparingTo("90");
        assertThat(inv.tradeName()).isEqualTo("Rao Wholesale");
        assertThat(f.documents().get(1).type()).isEqualTo("CN");
        assertThat(f.documents().get(1).value()).isEqualByComparingTo("236");
    }

    @Test
    @DisplayName("accepts the GSTR-1 style names other tools write (idt, iamt/camt/samt)")
    void gstr1Names() {
        Gstr2bFile f = Gstr2bFile.parse(SAMPLE.replace("\"dt\": \"05-05-2026\"", "\"idt\": \"05-05-2026\"")
                .replace("\"cgst\": 90, \"sgst\": 90", "\"camt\": 90, \"samt\": 90"));

        assertThat(f.documents().get(0).sgst()).isEqualByComparingTo("90");
    }

    @Test
    @DisplayName("P9-18: numbers compare without case, spaces, / - . and leading zeros")
    void keys() {
        assertThat(Gstr2bFile.key("INV/0012")).isEqualTo(Gstr2bFile.key("inv-12"));
        assertThat(Gstr2bFile.key("A 1.07")).as("separators go; the digits join").isEqualTo("A107");
        assertThat(Gstr2bFile.key("100")).isEqualTo("100");
        assertThat(Gstr2bFile.key("INV-0")).isEqualTo("INV0");
        assertThat(Gstr2bFile.key("INV-12")).isNotEqualTo(Gstr2bFile.key("INV-120"));
    }

    @Test
    @DisplayName("refuses a malformed period, date or amount")
    void refuses() {
        assertThatThrownBy(() -> Gstr2bFile.parse(SAMPLE.replace("052026", "2026-05")))
                .hasMessageContaining("not MMYYYY");
        assertThatThrownBy(() -> Gstr2bFile.parse(SAMPLE.replace("05-05-2026", "2026-05-05")))
                .hasMessageContaining("DD-MM-YYYY");
        assertThatThrownBy(() -> Gstr2bFile.parse(SAMPLE.replace("\"val\": 1180", "\"val\": 1180.005")))
                .hasMessageContaining("more than two decimals");
    }
}
