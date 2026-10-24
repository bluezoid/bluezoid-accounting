package com.accubook.accounting.report;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * A report table as CSV, JSON or XLSX. Money is written exactly - never through
 * a double. The XLSX is the minimal Office Open XML a spreadsheet opens: one
 * sheet, numbers as numbers, dates as dates, text inline; no library in the
 * installer for it.
 */
public final class TableExport {

    private TableExport() {
    }

    /**
     * RFC 4180 CSV with a header row; money as plain decimals; dates ISO. Text
     * that a spreadsheet would run as a formula (=, +, -, @) is prefixed with an
     * apostrophe; money never is, so a negative amount stays a number.
     */
    public static String csv(Table table) {
        StringBuilder out = new StringBuilder();
        out.append(String.join(",", table.columns().stream().map(c -> csvCell(c.name())).toList())).append("\r\n");
        for (List<Object> row : table.rows()) {
            for (int i = 0; i < row.size(); i++) {
                if (i > 0) {
                    out.append(',');
                }
                Object v = row.get(i);
                out.append(csvCell(v instanceof String t ? defused(t) : text(v)));
            }
            out.append("\r\n");
        }
        return out.toString();
    }

    /** {@code {"title": ..., "columns": [...], "rows": [[...], ...]}}; money as JSON numbers. */
    public static String json(Table table) {
        StringBuilder out = new StringBuilder("{\"title\":").append(jsonString(table.title())).append(",\"columns\":[");
        for (int i = 0; i < table.columns().size(); i++) {
            out.append(i > 0 ? "," : "").append(jsonString(table.columns().get(i).name()));
        }
        out.append("],\"rows\":[");
        for (int r = 0; r < table.rows().size(); r++) {
            out.append(r > 0 ? "," : "").append('[');
            List<Object> row = table.rows().get(r);
            for (int i = 0; i < row.size(); i++) {
                Object v = row.get(i);
                out.append(i > 0 ? "," : "").append(v == null ? "null" : v instanceof BigDecimal d
                        ? d.toPlainString() : jsonString(text(v)));
            }
            out.append(']');
        }
        return out.append("]}").toString();
    }

    /** A one-sheet workbook. */
    public static byte[] xlsx(Table table) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            put(zip, "[Content_Types].xml", """
                    <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                    <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">\
                    <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>\
                    <Default Extension="xml" ContentType="application/xml"/>\
                    <Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>\
                    <Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>\
                    <Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>\
                    </Types>""");
            put(zip, "_rels/.rels", """
                    <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                    <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">\
                    <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>\
                    </Relationships>""");
            put(zip, "xl/workbook.xml", """
                    <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                    <workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" \
                    xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">\
                    <sheets><sheet name="%s" sheetId="1" r:id="rId1"/></sheets></workbook>"""
                    .formatted(xml(sheetName(table.title()))));
            put(zip, "xl/_rels/workbook.xml.rels", """
                    <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                    <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">\
                    <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/>\
                    <Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>\
                    </Relationships>""");
            // Style 1: money (#,##0.00); style 2: a date (dd-mm-yyyy).
            put(zip, "xl/styles.xml", """
                    <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                    <styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">\
                    <numFmts count="1"><numFmt numFmtId="164" formatCode="dd-mm-yyyy"/></numFmts>\
                    <fonts count="1"><font/></fonts><fills count="1"><fill/></fills><borders count="1"><border/></borders>\
                    <cellStyleXfs count="1"><xf/></cellStyleXfs>\
                    <cellXfs count="3"><xf/><xf numFmtId="4" applyNumberFormat="1"/><xf numFmtId="164" applyNumberFormat="1"/></cellXfs>\
                    </styleSheet>""");
            StringBuilder sheet = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
                    + "<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><sheetData>");
            sheet.append("<row r=\"1\">");
            for (int i = 0; i < table.columns().size(); i++) {
                sheet.append(inline(ref(i, 1), table.columns().get(i).name()));
            }
            sheet.append("</row>");
            for (int r = 0; r < table.rows().size(); r++) {
                int rowNo = r + 2;
                sheet.append("<row r=\"").append(rowNo).append("\">");
                List<Object> row = table.rows().get(r);
                for (int i = 0; i < row.size(); i++) {
                    Object v = row.get(i);
                    if (v == null) {
                        continue;
                    }
                    if (v instanceof BigDecimal d) {
                        sheet.append("<c r=\"").append(ref(i, rowNo)).append("\" s=\"1\"><v>")
                                .append(d.toPlainString()).append("</v></c>");
                    } else if (v instanceof LocalDate date) {
                        long serial = ChronoUnit.DAYS.between(LocalDate.of(1899, 12, 30), date);
                        sheet.append("<c r=\"").append(ref(i, rowNo)).append("\" s=\"2\"><v>").append(serial)
                                .append("</v></c>");
                    } else {
                        sheet.append(inline(ref(i, rowNo), text(v)));
                    }
                }
                sheet.append("</row>");
            }
            sheet.append("</sheetData></worksheet>");
            put(zip, "xl/worksheets/sheet1.xml", sheet.toString());
        } catch (IOException e) {
            throw new IllegalStateException("Could not write the workbook", e);
        }
        return bytes.toByteArray();
    }

    private static void put(ZipOutputStream zip, String name, String content) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    private static String inline(String ref, String text) {
        return "<c r=\"" + ref + "\" t=\"inlineStr\"><is><t xml:space=\"preserve\">" + xml(text) + "</t></is></c>";
    }

    /** A1-style reference: column 0 is A, 26 is AA. */
    static String ref(int column, int row) {
        StringBuilder letters = new StringBuilder();
        int n = column + 1;
        while (n > 0) {
            int rem = (n - 1) % 26;
            letters.insert(0, (char) ('A' + rem));
            n = (n - 1) / 26;
        }
        return letters.toString() + row;
    }

    private static String sheetName(String title) {
        String name = title.replaceAll("[\\[\\]:*?/\\\\]", " ");
        return name.length() > 31 ? name.substring(0, 31) : name;
    }

    private static String text(Object v) {
        if (v == null) {
            return "";
        }
        return v instanceof BigDecimal d ? d.toPlainString() : v.toString();
    }

    private static String defused(String s) {
        return !s.isEmpty() && "=+-@\t\r".indexOf(s.charAt(0)) >= 0 ? "'" + s : s;
    }

    private static String csvCell(String s) {
        return s.contains(",") || s.contains("\"") || s.contains("\n") || s.contains("\r")
                ? "\"" + s.replace("\"", "\"\"") + "\"" : s;
    }

    private static String jsonString(String s) {
        StringBuilder out = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.append('"').toString();
    }

    private static String xml(String s) {
        StringBuilder out = new StringBuilder();
        for (char c : s.toCharArray()) {
            switch (c) {
                case '&' -> out.append("&amp;");
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '"' -> out.append("&quot;");
                default -> {
                    if (c >= 0x20 || c == '\t' || c == '\n' || c == '\r') {
                        out.append(c);
                    }
                }
            }
        }
        return out.toString();
    }
}
