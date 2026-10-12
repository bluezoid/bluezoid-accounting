package com.accubook.tax;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A statutory rule pack: state codes, GST slabs, thresholds and TDS/TCS
 * sections, each in force between dates.
 *
 * <h2>Format</h2>
 * Plain text a chartered accountant can review line by line (V-1):
 * <pre>
 * format=accubook-rulepack/1
 * version=2025.10.1
 * issued=2026-10-10
 * status=UNVERIFIED
 * source=https://...
 *
 * [states]
 * 27|Maharashtra|STATE
 * [gst-slabs]
 * 2025-09-22||0,0.25,3,5,18,40
 * [thresholds]
 * B2CL_INVOICE|2017-07-01||250000
 * [tds]
 * 194C|2025-04-01||1|20|SINGLE_OR_ANNUAL|30000|100000|Contractors
 * [tcs]
 * ...
 * [composition-special-states]
 * 12|2019-04-01|
 * [uqc]
 * NOS|Numbers
 * </pre>
 * Fields are separated by {@code |}; an empty "to" date means still in force.
 * Parsing is strict: anything unexpected is refused with its line number, because
 * a silently skipped line is a silently missing tax rule.
 */
public record RulePack(String version, LocalDate issued, String status, List<String> sources,
                       List<StateCode> states, List<GstSlabs> gstSlabs, List<Threshold> thresholds,
                       List<WithholdingSection> withholding, List<DatedState> compositionSpecialStates,
                       List<Uqc> uqcs) {

    /** A GST unit quantity code, as the returns' HSN summary must use (P9-16). */
    public record Uqc(String code, String description) {
    }

    /** A state code in a dated list, e.g. the states with the lower composition limit. */
    public record DatedState(String code, LocalDate from, LocalDate to) {
        public boolean appliesOn(LocalDate date) {
            return !date.isBefore(from) && (to == null || !date.isAfter(to));
        }
    }

    public static final String FORMAT = "accubook-rulepack/1";

    public RulePack {
        sources = List.copyOf(sources);
        states = List.copyOf(states);
        gstSlabs = List.copyOf(gstSlabs);
        thresholds = List.copyOf(thresholds);
        withholding = List.copyOf(withholding);
        compositionSpecialStates = List.copyOf(compositionSpecialStates);
        uqcs = List.copyOf(uqcs);
    }

    public Optional<Uqc> uqc(String code) {
        return code == null ? Optional.empty() : uqcs.stream().filter(u -> u.code().equals(code)).findFirst();
    }

    // ================================================================ queries

    public Optional<StateCode> state(String code) {
        return states.stream().filter(s -> s.code().equals(code)).findFirst();
    }

    /** The slab set in force on {@code date}. */
    public GstSlabs gstSlabsOn(LocalDate date) {
        return gstSlabs.stream().filter(s -> s.appliesOn(date)).findFirst()
                .orElseThrow(() -> new RulePackException("Rule pack " + version + " has no GST slabs for " + date));
    }

    public BigDecimal threshold(String key, LocalDate date) {
        return thresholds.stream().filter(t -> t.key().equals(key) && t.appliesOn(date)).findFirst()
                .map(Threshold::value)
                .orElseThrow(() -> new RulePackException("Rule pack " + version + " has no " + key + " for " + date));
    }

    /** The section in force on {@code date}, or empty if it does not apply then (e.g. 206C(1H) after 1 Apr 2025). */
    public Optional<WithholdingSection> section(WithholdingSection.Kind kind, String section, LocalDate date) {
        return withholding.stream()
                .filter(w -> w.kind() == kind && w.section().equals(section) && w.appliesOn(date))
                .findFirst();
    }

    /**
     * The composition turnover limit for a business registered in
     * {@code stateCode} on {@code date}: lower in the special-category states.
     */
    public BigDecimal compositionLimit(String stateCode, LocalDate date) {
        boolean special = compositionSpecialStates.stream()
                .anyMatch(s -> s.code().equals(stateCode) && s.appliesOn(date));
        return threshold(special ? "COMPOSITION_LIMIT_SPECIAL_STATES" : "COMPOSITION_LIMIT", date);
    }

    /** Version order: numeric parts compared numerically, so 2025.10 follows 2025.9. */
    public static int compareVersions(String a, String b) {
        String[] x = a.split("\\.");
        String[] y = b.split("\\.");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int left = i < x.length ? Integer.parseInt(x[i]) : 0;
            int right = i < y.length ? Integer.parseInt(y[i]) : 0;
            if (left != right) {
                return Integer.compare(left, right);
            }
        }
        return 0;
    }

    // ================================================================ parsing

    public static RulePack parse(String text) {
        Map<String, String> header = new LinkedHashMap<>();
        List<String> sources = new ArrayList<>();
        List<StateCode> states = new ArrayList<>();
        List<GstSlabs> slabs = new ArrayList<>();
        List<Threshold> thresholds = new ArrayList<>();
        List<WithholdingSection> withholding = new ArrayList<>();
        List<DatedState> special = new ArrayList<>();
        List<Uqc> uqcs = new ArrayList<>();
        String section = null;

        String[] lines = text.split("\n", -1);
        for (int n = 0; n < lines.length; n++) {
            String line = lines[n].strip();
            int lineNo = n + 1;
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            try {
                if (line.startsWith("[") && line.endsWith("]")) {
                    section = line.substring(1, line.length() - 1);
                    if (!List.of("states", "gst-slabs", "thresholds", "tds", "tcs",
                            "composition-special-states", "uqc").contains(section)) {
                        throw new RulePackException("unknown section [" + section + "]");
                    }
                    continue;
                }
                if (section == null) {
                    int eq = line.indexOf('=');
                    if (eq <= 0) {
                        throw new RulePackException("expected key=value");
                    }
                    String key = line.substring(0, eq);
                    String value = line.substring(eq + 1);
                    if (key.equals("source")) {
                        sources.add(value);
                    } else if (header.putIfAbsent(key, value) != null) {
                        throw new RulePackException("duplicate " + key);
                    }
                    continue;
                }
                String[] f = line.split("\\|", -1);
                switch (section) {
                    case "states" -> {
                        expect(f, 3);
                        if (!f[0].matches("\\d{2}")) {
                            throw new RulePackException("a state code is two digits");
                        }
                        states.add(new StateCode(f[0], required(f[1], "name"), StateCode.Kind.valueOf(f[2])));
                    }
                    case "gst-slabs" -> {
                        expect(f, 3);
                        slabs.add(new GstSlabs(date(f[0]), optionalDate(f[1]),
                                Arrays.stream(f[2].split(",")).map(r -> new BigDecimal(r.strip())).toList()));
                    }
                    case "thresholds" -> {
                        expect(f, 4);
                        thresholds.add(new Threshold(required(f[0], "key"), date(f[1]), optionalDate(f[2]),
                                new BigDecimal(f[3])));
                    }
                    case "uqc" -> {
                        expect(f, 2);
                        if (!f[0].matches("[A-Z]{3}")) {
                            throw new RulePackException("a UQC is three capital letters");
                        }
                        uqcs.add(new Uqc(f[0], required(f[1], "description")));
                    }
                    case "composition-special-states" -> {
                        expect(f, 3);
                        special.add(new DatedState(required(f[0], "state"), date(f[1]), optionalDate(f[2])));
                    }
                    default -> {
                        expect(f, 9);
                        withholding.add(new WithholdingSection(
                                section.equals("tds") ? WithholdingSection.Kind.TDS : WithholdingSection.Kind.TCS,
                                required(f[0], "section"), date(f[1]), optionalDate(f[2]), new BigDecimal(f[3]),
                                new BigDecimal(f[4]), WithholdingSection.ThresholdType.valueOf(f[5]),
                                optionalAmount(f[6]), optionalAmount(f[7]), f[8]));
                    }
                }
            } catch (RulePackException | IllegalArgumentException | DateTimeParseException e) {
                throw new RulePackException("Rule pack line " + lineNo + ": " + e.getMessage() + " - \"" + line + "\"", e);
            }
        }

        if (!FORMAT.equals(header.get("format"))) {
            throw new RulePackException("Not an AccuBook rule pack (format " + header.get("format") + ")");
        }
        String version = header.get("version");
        if (version == null || !version.matches("\\d+(\\.\\d+)*")) {
            throw new RulePackException("A rule pack needs a numeric version, e.g. 2025.10.1");
        }
        RulePack pack = new RulePack(version, date(required(header.get("issued"), "issued")),
                required(header.get("status"), "status"), sources, states, slabs, thresholds, withholding, special,
                uqcs);
        pack.requireNoOverlaps();
        return pack;
    }

    /**
     * Two entries for the same thing in force on the same day would make the
     * answer depend on file order. Refused.
     */
    private void requireNoOverlaps() {
        checkOverlaps("GST slabs", gstSlabs.stream().map(s -> new Object[]{"", s.from(), s.to()}).toList());
        checkOverlaps("threshold", thresholds.stream().map(t -> new Object[]{t.key(), t.from(), t.to()}).toList());
        checkOverlaps("section", withholding.stream()
                .map(w -> new Object[]{w.kind() + " " + w.section(), w.from(), w.to()}).toList());
        if (states.stream().map(StateCode::code).distinct().count() != states.size()) {
            throw new RulePackException("A state code appears twice");
        }
        if (uqcs.stream().map(Uqc::code).distinct().count() != uqcs.size()) {
            throw new RulePackException("A UQC appears twice");
        }
        checkOverlaps("composition special state", compositionSpecialStates.stream()
                .map(s -> new Object[]{s.code(), s.from(), s.to()}).toList());
        for (DatedState s : compositionSpecialStates) {
            if (state(s.code()).isEmpty()) {
                throw new RulePackException("Composition special state " + s.code() + " is not in [states]");
            }
        }
    }

    private static void checkOverlaps(String what, List<Object[]> spans) {
        for (int i = 0; i < spans.size(); i++) {
            for (int j = i + 1; j < spans.size(); j++) {
                Object[] a = spans.get(i);
                Object[] b = spans.get(j);
                if (!a[0].equals(b[0])) {
                    continue;
                }
                LocalDate aFrom = (LocalDate) a[1];
                LocalDate aTo = (LocalDate) a[2];
                LocalDate bFrom = (LocalDate) b[1];
                LocalDate bTo = (LocalDate) b[2];
                boolean overlap = (aTo == null || !bFrom.isAfter(aTo)) && (bTo == null || !aFrom.isAfter(bTo));
                if (overlap) {
                    throw new RulePackException("Two " + what + " entries " + (a[0].toString().isEmpty() ? "" : "for " + a[0] + " ")
                            + "are in force at once (" + aFrom + " and " + bFrom + ")");
                }
            }
        }
    }

    private static void expect(String[] fields, int count) {
        if (fields.length != count) {
            throw new RulePackException("expected " + count + " fields, found " + fields.length);
        }
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new RulePackException(name + " is required");
        }
        return value.strip();
    }

    private static LocalDate date(String value) {
        return LocalDate.parse(required(value, "date"));
    }

    private static LocalDate optionalDate(String value) {
        return value == null || value.isBlank() ? null : LocalDate.parse(value.strip());
    }

    private static BigDecimal optionalAmount(String value) {
        return value == null || value.isBlank() ? null : new BigDecimal(value.strip());
    }
}
