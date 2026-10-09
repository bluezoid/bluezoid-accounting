package com.accubook.licensing.token;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Serialises an {@link EntitlementToken} to the exact bytes that get signed.
 *
 * <p>A deliberately plain, line-oriented format rather than JSON. The signed
 * bytes must be reproducible <em>byte for byte</em> on both sides, and JSON
 * gives too many ways to differ that a reader will not notice: key ordering,
 * whitespace, number formatting, escaping. A signature that fails because a
 * serialiser reordered two fields is a support nightmare, so the format removes
 * the possibility.
 *
 * <p>Fields are written in a fixed order, one per line, {@code key=value}.
 * Unknown fields are rejected on read rather than ignored - a token this build
 * does not fully understand is one it must not act on.
 */
public final class TokenCodec {

    private static final String FIELD_SEPARATOR = "\n";
    private static final String LIST_SEPARATOR = ",";

    private TokenCodec() {
    }

    /** The canonical bytes covered by the signature. */
    public static byte[] canonicalBytes(EntitlementToken token) {
        return encode(token).getBytes(StandardCharsets.UTF_8);
    }

    public static String encode(EntitlementToken token) {
        StringBuilder out = new StringBuilder();
        line(out, "v", "1");
        line(out, "licenceId", token.licenceId());
        line(out, "customerId", token.customerId());
        line(out, "plan", token.plan());
        // Sorted so two servers producing the same entitlement produce the same
        // bytes regardless of internal set ordering.
        line(out, "modules", String.join(LIST_SEPARATOR, sorted(token.modules())));
        line(out, "subscriptionStart", token.subscriptionStart().toString());
        line(out, "subscriptionEnd", token.subscriptionEnd().toString());
        line(out, "renewalWindowDays", Integer.toString(token.renewalWindowDays()));
        line(out, "machineFingerprint", String.join(LIST_SEPARATOR, token.machineFingerprint()));
        line(out, "suspended", Boolean.toString(token.suspended()));
        line(out, "issuedAt", Long.toString(token.issuedAt().toEpochMilli()));
        line(out, "tokenId", token.tokenId());
        return out.toString();
    }

    public static EntitlementToken decode(String encoded) {
        java.util.Map<String, String> fields = new java.util.LinkedHashMap<>();
        for (String line : encoded.split(FIELD_SEPARATOR)) {
            if (line.isBlank()) {
                continue;
            }
            int equals = line.indexOf('=');
            if (equals < 0) {
                throw new MalformedTokenException("Malformed line in entitlement: " + line);
            }
            fields.put(line.substring(0, equals), line.substring(equals + 1));
        }

        String version = require(fields, "v");
        if (!"1".equals(version)) {
            // Refusing rather than guessing: a newer token may mean something
            // different by a field this build thinks it understands.
            throw new MalformedTokenException(
                    "Entitlement format v" + version + " is newer than this version of AccuBook understands.");
        }

        try {
            return new EntitlementToken(
                    require(fields, "licenceId"),
                    require(fields, "customerId"),
                    require(fields, "plan"),
                    splitToSet(require(fields, "modules")),
                    LocalDate.parse(require(fields, "subscriptionStart")),
                    LocalDate.parse(require(fields, "subscriptionEnd")),
                    Integer.parseInt(require(fields, "renewalWindowDays")),
                    splitToList(require(fields, "machineFingerprint")),
                    Boolean.parseBoolean(require(fields, "suspended")),
                    Instant.ofEpochMilli(Long.parseLong(require(fields, "issuedAt"))),
                    require(fields, "tokenId"));
        } catch (RuntimeException e) {
            throw new MalformedTokenException("Entitlement could not be read: " + e.getMessage());
        }
    }

    private static void line(StringBuilder out, String key, String value) {
        if (value != null && (value.contains("\n") || value.contains("="))) {
            throw new IllegalArgumentException("Field '" + key + "' contains a reserved character");
        }
        out.append(key).append('=').append(value == null ? "" : value).append(FIELD_SEPARATOR);
    }

    private static String require(java.util.Map<String, String> fields, String key) {
        String value = fields.get(key);
        if (value == null) {
            throw new MalformedTokenException("Entitlement is missing '" + key + "'");
        }
        return value;
    }

    private static Set<String> splitToSet(String raw) {
        return new LinkedHashSet<>(splitToList(raw));
    }

    private static List<String> splitToList(String raw) {
        if (raw.isBlank()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (String part : raw.split(LIST_SEPARATOR)) {
            if (!part.isBlank()) {
                values.add(part.strip());
            }
        }
        return values;
    }

    private static List<String> sorted(Set<String> values) {
        String[] array = values.toArray(new String[0]);
        Arrays.sort(array);
        return List.of(array);
    }
}
