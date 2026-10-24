package com.accubook.returns;

import com.accubook.module.EntitlementGuard;
import com.accubook.module.ModuleTransactions;
import com.accubook.platform.id.Ids;
import com.accubook.platform.money.MoneyMapper;
import com.accubook.platform.money.Scale;
import com.accubook.tax.CompanyIdentity;
import com.accubook.tax.RulePack;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * E-invoice and e-way bill payloads, and the portals' responses (P9e).
 *
 * <p><b>Nothing here makes up an IRN, acknowledgement, signed QR or e-way bill
 * number.</b> The payloads are built from the figures stored at posting and
 * sent by the user to the IRP or the EWB portal; what those return is imported,
 * checked to name this document, and kept with the response (P9-28).
 */
public final class EInvoices {

    private static final ZoneId INDIA = ZoneId.of("Asia/Kolkata");
    private static final DateTimeFormatter SLASHED = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private final ModuleTransactions transactions;
    private final EntitlementGuard entitlement;
    private final Supplier<RulePack> rules;
    private final Clock clock;

    public EInvoices(ModuleTransactions transactions, EntitlementGuard entitlement, Supplier<RulePack> rules,
                     Clock clock) {
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.entitlement = Objects.requireNonNull(entitlement, "entitlement");
        this.rules = Objects.requireNonNull(rules, "rules");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    // ===================================================== applicability (P9-25)

    /** Declare whether e-invoicing applies from {@code from}; a later declaration overrides from its date. */
    public void declare(LocalDate from, boolean applies) {
        entitlement.requireWritable(TaxationReturnsModule.ID, "declare e-invoicing");
        write(connection -> {
            try (PreparedStatement ps = connection.prepareStatement("INSERT INTO einvoice_applicability "
                    + "(effective_from, applies, created_at) VALUES (?, ?, ?)")) {
                ps.setString(1, Objects.requireNonNull(from, "from").toString());
                ps.setInt(2, applies ? 1 : 0);
                ps.setLong(3, clock.millis());
                ps.executeUpdate();
            }
        }, "declare e-invoicing");
    }

    public boolean appliesOn(LocalDate date) {
        return read(connection -> applies(connection, date));
    }

    /**
     * P9-25: a warning when the books' own turnover for the financial year of
     * {@code date} passes the rule pack's e-invoicing limit and e-invoicing is
     * not declared to apply. The legal test is PAN-wide and over any year since
     * 2017-18, so this can only prompt, never decide.
     */
    public Optional<String> turnoverWarning(LocalDate date) {
        return read(connection -> {
            if (applies(connection, date)) {
                return Optional.<String>empty();
            }
            LocalDate from = LocalDate.of(date.getMonthValue() >= 4 ? date.getYear() : date.getYear() - 1, 4, 1);
            BigDecimal turnover;
            try (PreparedStatement ps = connection.prepareStatement("SELECT COALESCE(SUM(CASE kind WHEN "
                    + "'SALES_INVOICE' THEN taxable_e4 ELSE -taxable_e4 END), 0) FROM sales_document "
                    + "WHERE status = 'POSTED' AND document_date BETWEEN ? AND ?")) {
                ps.setString(1, from.toString());
                ps.setString(2, from.plusYears(1).minusDays(1).toString());
                try (ResultSet r = ps.executeQuery()) {
                    r.next();
                    turnover = money(r.getLong(1));
                }
            }
            BigDecimal limit = rules.get().threshold("EINVOICE_TURNOVER", date);
            return turnover.compareTo(limit) > 0 ? Optional.of("Turnover in the books since " + from + " is "
                    + turnover.toPlainString() + ", above the e-invoicing limit of " + limit.toPlainString()
                    + ". Declare the date e-invoicing applies from.") : Optional.<String>empty();
        });
    }

    // ============================================================ e-invoice

    /**
     * The IRP e-invoice JSON (schema 1.1) for a posted B2B invoice or credit
     * note. Every problem is listed at once, so the user can fix them together.
     */
    public String einvoicePayload(String documentId) {
        requireReadable();
        return read(connection -> {
            Doc doc = doc(connection, documentId);
            CompanyIdentity.Address seller = sellerAddress(connection);
            List<String> problems = new ArrayList<>();
            if (!applies(connection, doc.date)) {
                problems.add("E-invoicing is not declared to apply on " + doc.date);
            }
            common(doc, seller, problems);
            if (doc.partyGstin == null) {
                problems.add("Only an invoice to a registered buyer (with a GSTIN) is e-invoiced");
            }
            if (!problems.isEmpty()) {
                throw new ReturnException("The e-invoice cannot be built: " + String.join("; ", problems));
            }
            Map<String, Object> root = new LinkedHashMap<>();
            root.put("Version", "1.1");
            Map<String, Object> tran = new LinkedHashMap<>();
            tran.put("TaxSch", "GST");
            tran.put("SupTyp", "B2B");
            tran.put("RegRev", "N");
            tran.put("IgstOnIntra", "N");
            root.put("TranDtls", tran);
            Map<String, Object> docDtls = new LinkedHashMap<>();
            docDtls.put("Typ", "CREDIT_NOTE".equals(doc.kind) ? "CRN" : "INV");
            docDtls.put("No", doc.number);
            docDtls.put("Dt", doc.date.format(SLASHED));
            root.put("DocDtls", docDtls);
            root.put("SellerDtls", party(doc.companyGstin, seller.legalName(), seller.line1(), seller.city(),
                    seller.pin(), seller.stateCode(), null));
            root.put("BuyerDtls", party(doc.partyGstin, doc.partyName, doc.partyAddress, doc.partyCity, doc.partyPin,
                    doc.partyGstin.substring(0, 2), doc.placeOfSupply));
            List<Object> items = new ArrayList<>();
            int n = 1;
            for (Line l : doc.lines) {
                Map<String, Object> m = new LinkedHashMap<>();
                BigDecimal gross = l.quantity.multiply(l.rate).setScale(2, RoundingMode.HALF_UP);
                m.put("SlNo", String.valueOf(n++));
                m.put("PrdDesc", l.description);
                m.put("IsServc", l.service() ? "Y" : "N");
                m.put("HsnCd", l.hsn);
                m.put("Qty", l.quantity.setScale(3, RoundingMode.HALF_UP));
                m.put("Unit", l.uqc == null ? "OTH" : l.uqc);
                m.put("UnitPrice", l.rate.setScale(3, RoundingMode.HALF_UP));
                m.put("TotAmt", gross);
                m.put("Discount", gross.subtract(l.amount));
                m.put("AssAmt", l.amount);
                m.put("GstRt", l.gstRate);
                m.put("IgstAmt", l.igst);
                m.put("CgstAmt", l.cgst);
                m.put("SgstAmt", l.sgst);
                m.put("CesRt", l.cessRate);
                m.put("CesAmt", l.cess);
                m.put("TotItemVal", l.amount.add(l.tax()));
                items.add(m);
            }
            root.put("ItemList", items);
            Map<String, Object> val = new LinkedHashMap<>();
            val.put("AssVal", doc.sum(Line::amount));
            val.put("CgstVal", doc.sum(Line::cgst));
            val.put("SgstVal", doc.sum(Line::sgst));
            val.put("IgstVal", doc.sum(Line::igst));
            val.put("CesVal", doc.sum(Line::cess));
            val.put("RndOffAmt", doc.roundOff);
            val.put("TotInvVal", doc.total);
            root.put("ValDtls", val);
            if (doc.originalNumber != null) {
                root.put("RefDtls", Map.of("PrecDocDtls", List.of(Map.of("InvNo", doc.originalNumber,
                        "InvDt", doc.originalDate.format(SLASHED)))));
            }
            return Json.write(root);
        });
    }

    /**
     * Import the IRP's response to an IRN request (P9-28). The signed invoice
     * inside it must name this document - its number, date and the company's
     * GSTIN - and carry the same IRN. The NIC signature is not verified here:
     * that needs NIC's public key, which is not bundled.
     */
    public void importIrpResponse(String documentId, String json) {
        entitlement.requireWritable(TaxationReturnsModule.ID, "import an e-invoice response");
        Map<String, Object> root = object(JsonReader.read(json));
        Map<String, Object> data = root.get("Data") instanceof Map ? object(root.get("Data")) : root;
        String irn = text(data, "Irn");
        String signedInvoice = text(data, "SignedInvoice");
        Map<String, Object> signed = jwtPayload(signedInvoice);
        Map<String, Object> invoice = signed.get("data") instanceof String s ? object(JsonReader.read(s))
                : object(signed.get("data"));
        write(connection -> {
            Doc doc = doc(connection, documentId);
            Map<String, Object> docDtls = object(invoice.get("DocDtls"));
            Map<String, Object> seller = object(invoice.get("SellerDtls"));
            List<String> mismatch = new ArrayList<>();
            if (!doc.number.equals(text(docDtls, "No"))) {
                mismatch.add("number " + text(docDtls, "No"));
            }
            if (!doc.date.format(SLASHED).equals(text(docDtls, "Dt"))) {
                mismatch.add("date " + text(docDtls, "Dt"));
            }
            if (!Objects.equals(doc.companyGstin, text(seller, "Gstin"))) {
                mismatch.add("seller " + text(seller, "Gstin"));
            }
            if (invoice.containsKey("Irn") && !irn.equals(text(invoice, "Irn"))) {
                mismatch.add("an IRN different from the response's");
            }
            if (!mismatch.isEmpty()) {
                throw new ReturnException("This response is not for " + doc.number + ": it has "
                        + String.join(", ", mismatch));
            }
            try (PreparedStatement ps = connection.prepareStatement("INSERT INTO einvoice_record (id, document_id, "
                    + "irn, ack_no, ack_at, signed_qr, response, response_sha256, imported_at) "
                    + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
                ps.setString(1, Ids.newIdString());
                ps.setString(2, documentId);
                ps.setString(3, irn);
                ps.setString(4, text(data, "AckNo"));
                ps.setLong(5, instant(text(data, "AckDt"), "yyyy-MM-dd HH:mm:ss"));
                ps.setString(6, text(data, "SignedQRCode"));
                ps.setString(7, json);
                ps.setString(8, GstReturns.sha256(json));
                ps.setLong(9, clock.millis());
                ps.executeUpdate();
            }
        }, "import the e-invoice response");
    }

    /** The IRN recorded for a document, if any. */
    public Optional<String> irn(String documentId) {
        return read(connection -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT irn FROM einvoice_record WHERE document_id = ?")) {
                ps.setString(1, documentId);
                try (ResultSet r = ps.executeQuery()) {
                    return r.next() ? Optional.of(r.getString(1)) : Optional.<String>empty();
                }
            }
        });
    }

    /** Invoices cancelled in AccuBook whose IRN is still on record: cancel those on the IRP too (P9-26). */
    public List<String> irnsToCancel() {
        return read(connection -> {
            List<String> result = new ArrayList<>();
            try (PreparedStatement ps = connection.prepareStatement("SELECT e.irn FROM einvoice_record e "
                    + "JOIN sales_document d ON d.id = e.document_id WHERE d.status = 'CANCELLED' ORDER BY e.ack_at");
                 ResultSet r = ps.executeQuery()) {
                while (r.next()) {
                    result.add(r.getString(1));
                }
            }
            return result;
        });
    }

    // =========================================================== e-way bill

    /** P9-27: whether the invoice's goods need an e-way bill - advice only. */
    public EwayAdvice ewayAdvice(String documentId) {
        requireReadable();
        return read(connection -> advice(doc(connection, documentId)));
    }

    /** The e-way bill JSON (EWB API) for a posted invoice of goods. */
    public String ewayPayload(String documentId, Transport transport) {
        requireReadable();
        Objects.requireNonNull(transport, "transport");
        return read(connection -> {
            Doc doc = doc(connection, documentId);
            CompanyIdentity.Address seller = sellerAddress(connection);
            List<String> problems = new ArrayList<>();
            common(doc, seller, problems);
            if (!"SALES_INVOICE".equals(doc.kind)) {
                problems.add("This builds the e-way bill of an outward invoice; returned goods move inward");
            }
            List<Line> goods = doc.lines.stream().filter(l -> !l.service()).toList();
            if (goods.isEmpty()) {
                problems.add("An e-way bill is for goods; this document has none");
            }
            if (!problems.isEmpty()) {
                throw new ReturnException("The e-way bill cannot be built: " + String.join("; ", problems));
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("supplyType", "O");
            m.put("subSupplyType", "1");
            m.put("docType", "INV");
            m.put("docNo", doc.number);
            m.put("docDate", doc.date.format(SLASHED));
            m.put("fromGstin", doc.companyGstin == null ? "URP" : doc.companyGstin);
            m.put("fromTrdName", seller.legalName());
            m.put("fromAddr1", seller.line1());
            m.put("fromPlace", seller.city());
            m.put("fromPincode", Integer.parseInt(seller.pin()));
            m.put("fromStateCode", Integer.parseInt(seller.stateCode()));
            m.put("actFromStateCode", Integer.parseInt(seller.stateCode()));
            m.put("toGstin", doc.partyGstin == null ? "URP" : doc.partyGstin);
            m.put("toTrdName", doc.partyName);
            m.put("toAddr1", doc.partyAddress);
            m.put("toPlace", doc.partyCity);
            m.put("toPincode", Integer.parseInt(doc.partyPin));
            m.put("toStateCode", Integer.parseInt(doc.placeOfSupply));
            m.put("actToStateCode", Integer.parseInt(doc.placeOfSupply));
            m.put("transactionType", 1);
            m.put("totalValue", sum(goods, Line::amount));
            m.put("cgstValue", sum(goods, Line::cgst));
            m.put("sgstValue", sum(goods, Line::sgst));
            m.put("igstValue", sum(goods, Line::igst));
            m.put("cessValue", sum(goods, Line::cess));
            m.put("totInvValue", doc.total);
            m.put("transMode", String.valueOf(transport.mode()));
            m.put("transDistance", String.valueOf(transport.distanceKm()));
            m.put("transporterId", transport.transporterId());
            m.put("transporterName", transport.transporterName());
            m.put("transDocNo", transport.transDocNo());
            m.put("transDocDate", transport.transDocDate() == null ? null : transport.transDocDate().format(SLASHED));
            m.put("vehicleNo", transport.vehicleNo());
            m.put("vehicleType", transport.vehicleType());
            List<Object> items = new ArrayList<>();
            for (Line l : goods) {
                Map<String, Object> i = new LinkedHashMap<>();
                boolean inter = "INTER_STATE".equals(doc.supplyType);
                i.put("productName", l.description);
                i.put("hsnCode", Integer.parseInt(l.hsn));
                i.put("quantity", l.quantity.setScale(3, RoundingMode.HALF_UP));
                i.put("qtyUnit", l.uqc == null ? "OTH" : l.uqc);
                i.put("cgstRate", inter ? BigDecimal.ZERO : l.gstRate.divide(BigDecimal.valueOf(2)));
                i.put("sgstRate", inter ? BigDecimal.ZERO : l.gstRate.divide(BigDecimal.valueOf(2)));
                i.put("igstRate", inter ? l.gstRate : BigDecimal.ZERO);
                i.put("cessRate", l.cessRate);
                i.put("taxableAmount", l.amount);
                items.add(i);
            }
            m.put("itemList", items);
            return Json.write(m);
        });
    }

    /**
     * Import an e-way bill's details from the EWB portal (P9-28). They must name
     * this document - number, date and the company's GSTIN - so the generation
     * acknowledgement alone, which does not, is refused.
     */
    public void importEwayResponse(String documentId, String json) {
        entitlement.requireWritable(TaxationReturnsModule.ID, "import an e-way bill");
        Map<String, Object> root = object(JsonReader.read(json));
        Map<String, Object> data = root.get("data") instanceof Map ? object(root.get("data")) : root;
        String ewbNo = text(data, "ewbNo", "ewayBillNo");
        if (!data.containsKey("docNo")) {
            throw new ReturnException("Import the e-way bill's details, which name the invoice - not only the "
                    + "generation acknowledgement");
        }
        write(connection -> {
            Doc doc = doc(connection, documentId);
            if (!doc.number.equals(text(data, "docNo")) || !doc.date.format(SLASHED).equals(text(data, "docDate"))
                    || !Objects.equals(doc.companyGstin, text(data, "fromGstin"))) {
                throw new ReturnException("This e-way bill is for " + text(data, "docNo") + " of "
                        + text(data, "docDate") + ", not " + doc.number);
            }
            try (PreparedStatement ps = connection.prepareStatement("INSERT INTO eway_bill_record (id, document_id, "
                    + "ewb_no, generated_at, valid_until, response, response_sha256, imported_at) "
                    + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)")) {
                ps.setString(1, Ids.newIdString());
                ps.setString(2, documentId);
                ps.setString(3, ewbNo);
                ps.setLong(4, instant(text(data, "ewbDate", "ewayBillDate"), "dd/MM/yyyy hh:mm:ss a"));
                String valid = data.containsKey("validUpto") && data.get("validUpto") != null
                        ? text(data, "validUpto") : null;
                if (valid == null) {
                    ps.setNull(5, java.sql.Types.INTEGER);
                } else {
                    ps.setLong(5, instant(valid, "dd/MM/yyyy hh:mm:ss a"));
                }
                ps.setString(6, json);
                ps.setString(7, GstReturns.sha256(json));
                ps.setLong(8, clock.millis());
                ps.executeUpdate();
            }
        }, "import the e-way bill");
    }

    // ============================================================== helpers

    private EwayAdvice advice(Doc doc) {
        BigDecimal value = BigDecimal.ZERO.setScale(2);
        for (Line l : doc.lines) {
            // Rule 138: taxable goods with their tax; exempt, nil-rated and non-GST goods left out.
            boolean exemptOrNonGst = l.category != null && !"TAXABLE".equals(l.category);
            if (!l.service() && !exemptOrNonGst) {
                value = value.add(l.amount).add(l.tax());
            }
        }
        String from = doc.supplierState;
        BigDecimal limit = rules.get().ewayBillLimit(from, doc.placeOfSupply, doc.date);
        boolean required = value.compareTo(limit) > 0;
        String where = from.equals(doc.placeOfSupply) ? "intra-state" : "inter-state";
        return new EwayAdvice(required, value, limit, required
                ? "Goods worth " + value.toPlainString() + " with tax move " + where + "; above "
                + limit.toPlainString() + " an e-way bill is needed"
                : "Goods worth " + value.toPlainString() + " with tax, within the " + where + " limit of "
                + limit.toPlainString());
    }

    private static void common(Doc doc, CompanyIdentity.Address seller, List<String> problems) {
        if (!"POSTED".equals(doc.status)) {
            problems.add("Only a posted document is reported; this one is " + doc.status.toLowerCase(Locale.ROOT));
        }
        if (!"REGULAR".equals(doc.scheme)) {
            problems.add("A " + (doc.scheme == null ? "pre-GST" : doc.scheme.toLowerCase(Locale.ROOT))
                    + " document is not e-invoiced");
        }
        if (seller == null) {
            problems.add("Record the company's legal name and address");
        } else if (CompanyIdentity.pinProblem(seller.pin()).isPresent()) {
            problems.add("The company's PIN code: " + CompanyIdentity.pinProblem(seller.pin()).get());
        }
        if (doc.partyAddress == null || doc.partyCity == null) {
            problems.add(doc.partyName + " needs an address and a city");
        }
        if (CompanyIdentity.pinProblem(doc.partyPin).isPresent()) {
            problems.add(doc.partyName + "'s PIN code: " + CompanyIdentity.pinProblem(doc.partyPin).get());
        }
        for (Line l : doc.lines) {
            if (l.hsn == null) {
                problems.add("\"" + l.description + "\" has no HSN/SAC code");
            }
        }
    }

    private static Map<String, Object> party(String gstin, String name, String address, String city, String pin,
                                             String stateCode, String placeOfSupply) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("Gstin", gstin);
        m.put("LglNm", name);
        if (placeOfSupply != null) {
            m.put("Pos", placeOfSupply);
        }
        m.put("Addr1", address);
        m.put("Loc", city);
        m.put("Pin", Integer.parseInt(pin));
        m.put("Stcd", stateCode);
        return m;
    }

    private record Line(String description, String category, BigDecimal quantity, BigDecimal rate, BigDecimal amount,
                        String hsn, String uqc, BigDecimal gstRate, BigDecimal cessRate, BigDecimal igst,
                        BigDecimal cgst, BigDecimal sgst, BigDecimal cess) {
        boolean service() {
            return hsn != null && hsn.startsWith("99");
        }

        BigDecimal tax() {
            return igst.add(cgst).add(sgst).add(cess);
        }
    }

    private record Doc(String id, String kind, String number, LocalDate date, String status, String scheme,
                       String companyGstin, String partyGstin, String partyName, String partyAddress, String partyCity,
                       String partyPin, String placeOfSupply, String supplierState, String supplyType,
                       BigDecimal total, BigDecimal roundOff, String originalNumber, LocalDate originalDate,
                       List<Line> lines) {
        BigDecimal sum(java.util.function.Function<Line, BigDecimal> f) {
            return EInvoices.sum(lines, f);
        }
    }

    private static BigDecimal sum(List<Line> lines, java.util.function.Function<Line, BigDecimal> f) {
        return lines.stream().map(f).reduce(BigDecimal.ZERO.setScale(2), BigDecimal::add);
    }

    private static Doc doc(Connection connection, String documentId) throws SQLException {
        Doc header;
        try (PreparedStatement ps = connection.prepareStatement("""
                SELECT d.id, d.kind, d.number, d.document_date, d.status, d.gst_scheme,
                       (SELECT gstin FROM gst_registration WHERE effective_from <= d.document_date
                        ORDER BY effective_from DESC LIMIT 1),
                       d.party_gstin, p.name, p.address_line1, p.address_city, p.postal_code, d.place_of_supply,
                       d.supplier_state, d.supply_type, d.total_e4, d.round_off_e4, o.number, o.document_date
                FROM sales_document d JOIN party p ON p.id = d.party_id
                LEFT JOIN sales_document o ON o.id = d.original_document_id
                WHERE d.id = ?
                """)) {
            ps.setString(1, documentId);
            try (ResultSet r = ps.executeQuery()) {
                if (!r.next()) {
                    throw new ReturnException("No such sales document: " + documentId);
                }
                header = new Doc(r.getString(1), r.getString(2), r.getString(3), LocalDate.parse(r.getString(4)),
                        r.getString(5), r.getString(6), r.getString(7), r.getString(8), r.getString(9),
                        r.getString(10), r.getString(11), r.getString(12), r.getString(13), r.getString(14),
                        r.getString(15), money(r.getLong(16)), money(r.getLong(17)), r.getString(18),
                        r.getString(19) == null ? null : LocalDate.parse(r.getString(19)), List.of());
            }
        }
        List<Line> lines = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement("""
                SELECT l.description, l.tax_category, l.quantity_e4, l.rate_e4, l.amount_e4, l.hsn_sac, l.uqc,
                       l.gst_rate_e4, l.cess_rate_e4,
                       COALESCE(SUM(CASE WHEN t.component = 'IGST' THEN t.amount_e4 END), 0),
                       COALESCE(SUM(CASE WHEN t.component = 'CGST' THEN t.amount_e4 END), 0),
                       COALESCE(SUM(CASE WHEN t.component IN ('SGST', 'UTGST') THEN t.amount_e4 END), 0),
                       COALESCE(SUM(CASE WHEN t.component = 'CESS' THEN t.amount_e4 END), 0)
                FROM sales_document_line l LEFT JOIN sales_tax_line t ON t.line_id = l.id
                WHERE l.document_id = ? GROUP BY l.id ORDER BY l.sequence
                """)) {
            ps.setString(1, documentId);
            try (ResultSet r = ps.executeQuery()) {
                while (r.next()) {
                    lines.add(new Line(r.getString(1), r.getString(2),
                            MoneyMapper.fromStored(r.getLong(3), Scale.QUANTITY),
                            MoneyMapper.fromStored(r.getLong(4), Scale.MONEY), money(r.getLong(5)), r.getString(6),
                            r.getString(7), MoneyMapper.fromStored(r.getLong(8), Scale.TAX_RATE).stripTrailingZeros(),
                            MoneyMapper.fromStored(r.getLong(9), Scale.TAX_RATE).stripTrailingZeros(),
                            money(r.getLong(10)), money(r.getLong(11)), money(r.getLong(12)), money(r.getLong(13))));
                }
            }
        }
        return new Doc(header.id, header.kind, header.number, header.date, header.status, header.scheme,
                header.companyGstin, header.partyGstin, header.partyName, header.partyAddress, header.partyCity,
                header.partyPin, header.placeOfSupply, header.supplierState, header.supplyType, header.total,
                header.roundOff, header.originalNumber, header.originalDate, lines);
    }

    private static CompanyIdentity.Address sellerAddress(Connection connection) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("SELECT legal_name, address_line1, address_city, "
                + "address_state_code, address_postal_code FROM company_info"); ResultSet r = ps.executeQuery()) {
            if (!r.next() || r.getString(2) == null) {
                return null;
            }
            return new CompanyIdentity.Address(r.getString(1), r.getString(2), r.getString(3), r.getString(4),
                    r.getString(5));
        }
    }

    private static boolean applies(Connection connection, LocalDate date) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("SELECT applies FROM einvoice_applicability "
                + "WHERE effective_from <= ? ORDER BY effective_from DESC LIMIT 1")) {
            ps.setString(1, date.toString());
            try (ResultSet r = ps.executeQuery()) {
                return r.next() && r.getInt(1) == 1;
            }
        }
    }

    /** The payload of a signed JWT, decoded but not verified. */
    private static Map<String, Object> jwtPayload(String jwt) {
        String[] parts = jwt.split("\\.");
        if (parts.length != 3) {
            throw new ReturnException("The signed invoice is not a signed token");
        }
        try {
            return object(JsonReader.read(new String(Base64.getUrlDecoder().decode(parts[1]),
                    StandardCharsets.UTF_8)));
        } catch (IllegalArgumentException e) {
            throw new ReturnException("The signed invoice cannot be read", e);
        }
    }

    private static long instant(String text, String pattern) {
        try {
            return LocalDateTime.parse(text, DateTimeFormatter.ofPattern(pattern, Locale.ENGLISH)).atZone(INDIA)
                    .toInstant().toEpochMilli();
        } catch (DateTimeParseException e) {
            throw new ReturnException("\"" + text + "\" is not a portal date and time", e);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object value) {
        if (!(value instanceof Map)) {
            throw new ReturnException("The response is not the portal's JSON object");
        }
        return (Map<String, Object>) value;
    }

    private static String text(Map<String, Object> m, String... keys) {
        for (String key : keys) {
            Object v = m.get(key);
            if (v != null) {
                return v instanceof BigDecimal d ? d.toPlainString() : v.toString();
            }
        }
        throw new ReturnException("The response has no " + String.join(" or ", keys));
    }

    private static BigDecimal money(long stored) {
        return MoneyMapper.fromStored(stored, Scale.MONEY).setScale(2);
    }

    @FunctionalInterface
    private interface Work {
        void run(Connection connection) throws SQLException;
    }

    private void write(Work work, String what) {
        try {
            transactions.inTransaction(connection -> {
                work.run(connection);
                return null;
            });
        } catch (SQLException e) {
            throw new ReturnException(e.getMessage() != null && e.getMessage().contains("UNIQUE")
                    ? "That is already recorded" : "Could not " + what + ": " + e.getMessage(), e);
        }
    }

    private <T> T read(ModuleTransactions.Work<T> work) {
        try {
            return transactions.read(work);
        } catch (SQLException e) {
            throw new ReturnException("Could not read: " + e.getMessage(), e);
        }
    }

    private void requireReadable() {
        if (!entitlement.isReadable(TaxationReturnsModule.ID)) {
            throw new ReturnException("Tax returns are not part of this licence");
        }
    }
}
