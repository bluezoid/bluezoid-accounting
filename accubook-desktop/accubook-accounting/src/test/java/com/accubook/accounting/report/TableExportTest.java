package com.accubook.accounting.report;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Exports carry the books exactly: money never through a double, text never as a formula. */
class TableExportTest {

    private static final Table TABLE = new Table("Day book: April", List.of(
            new Table.Column("Date", Table.Kind.DATE),
            new Table.Column("Party", Table.Kind.TEXT),
            new Table.Column("Amount", Table.Kind.MONEY)), List.of(
            Arrays.asList(LocalDate.of(2026, 4, 1), "Mehta Stores, Pune", new BigDecimal("12345678901234.56")),
            Arrays.asList(LocalDate.of(2026, 4, 2), "Say \"hi\" <&>", new BigDecimal("-0.10")),
            Arrays.asList(LocalDate.of(2026, 4, 3), "=HYPERLINK(\"x\")", null)));

    @Test
    @DisplayName("CSV: quoted where needed, money exact and negative stays a number, formulas defused")
    void csv() {
        assertEquals("""
                Date,Party,Amount\r
                2026-04-01,"Mehta Stores, Pune",12345678901234.56\r
                2026-04-02,"Say ""hi"" <&>",-0.10\r
                2026-04-03,"'=HYPERLINK(""x"")",\r
                """, TableExport.csv(TABLE));
    }

    @Test
    @DisplayName("JSON: money as exact numbers, nulls as null, strings escaped")
    void json() {
        assertEquals("{\"title\":\"Day book: April\",\"columns\":[\"Date\",\"Party\",\"Amount\"],\"rows\":["
                + "[\"2026-04-01\",\"Mehta Stores, Pune\",12345678901234.56],"
                + "[\"2026-04-02\",\"Say \\\"hi\\\" <&>\",-0.10],"
                + "[\"2026-04-03\",\"=HYPERLINK(\\\"x\\\")\",null]]}", TableExport.json(TABLE));
    }

    @Test
    @DisplayName("XLSX: the parts a spreadsheet needs; dates as serials, money as numbers, text inline and escaped")
    void xlsx() throws IOException {
        Map<String, String> parts = unzip(TableExport.xlsx(TABLE));

        String sheet = parts.get("xl/worksheets/sheet1.xml");
        assertAll(
                () -> assertTrue(parts.keySet().containsAll(List.of("[Content_Types].xml", "_rels/.rels",
                        "xl/workbook.xml", "xl/_rels/workbook.xml.rels", "xl/styles.xml"))),
                () -> assertTrue(parts.get("xl/workbook.xml").contains("<sheet name=\"Day book  April\""),
                        "a colon is not allowed in a sheet name"),
                () -> assertTrue(sheet.contains("<c r=\"A2\" s=\"2\"><v>46113</v></c>"), "1 April 2026 is 46113"),
                () -> assertTrue(sheet.contains("<c r=\"C2\" s=\"1\"><v>12345678901234.56</v></c>")),
                () -> assertTrue(sheet.contains("<c r=\"C3\" s=\"1\"><v>-0.10</v></c>")),
                () -> assertTrue(sheet.contains("<t xml:space=\"preserve\">Say &quot;hi&quot; &lt;&amp;&gt;</t>")),
                () -> assertTrue(sheet.contains("<c r=\"B4\" t=\"inlineStr\">"), "inline text is never a formula"),
                () -> assertTrue(!sheet.contains("r=\"C4\""), "an empty cell is left out"));
    }

    @Test
    @DisplayName("A1 references run past Z")
    void references() {
        assertAll(
                () -> assertEquals("A1", TableExport.ref(0, 1)),
                () -> assertEquals("Z9", TableExport.ref(25, 9)),
                () -> assertEquals("AA1", TableExport.ref(26, 1)),
                () -> assertEquals("AZ2", TableExport.ref(51, 2)),
                () -> assertEquals("BA3", TableExport.ref(52, 3)));
    }

    @Test
    @DisplayName("a row with the wrong number of cells is refused")
    void ragged() {
        assertThrows(IllegalArgumentException.class, () -> new Table("t",
                List.of(new Table.Column("a", Table.Kind.TEXT)), List.of(List.of("x", "y"))));
    }

    private static Map<String, String> unzip(byte[] bytes) throws IOException {
        Map<String, String> parts = new HashMap<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            for (ZipEntry e = zip.getNextEntry(); e != null; e = zip.getNextEntry()) {
                parts.put(e.getName(), new String(zip.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        return parts;
    }
}
