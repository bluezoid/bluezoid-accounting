package com.accubook.returns;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;

/**
 * Just enough JSON for portal files: objects keep their key order, numbers are
 * written as given, money to two places. No library: the output must be
 * byte-stable so its SHA-256 identifies a filed return.
 */
final class Json {

    private Json() {
    }

    /** Money for the portal: two decimal places. */
    static BigDecimal amount(BigDecimal value) {
        return value.setScale(2, RoundingMode.UNNECESSARY);
    }

    static String write(Object value) {
        StringBuilder out = new StringBuilder();
        write(out, value);
        return out.toString();
    }

    private static void write(StringBuilder out, Object value) {
        if (value == null) {
            out.append("null");
        } else if (value instanceof String s) {
            out.append('"');
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
            out.append('"');
        } else if (value instanceof BigDecimal d) {
            out.append(d.toPlainString());
        } else if (value instanceof Number || value instanceof Boolean) {
            out.append(value);
        } else if (value instanceof Map<?, ?> map) {
            out.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : map.entrySet()) {
                if (!first) {
                    out.append(',');
                }
                first = false;
                write(out, e.getKey().toString());
                out.append(':');
                write(out, e.getValue());
            }
            out.append('}');
        } else if (value instanceof List<?> list) {
            out.append('[');
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) {
                    out.append(',');
                }
                write(out, list.get(i));
            }
            out.append(']');
        } else {
            throw new IllegalArgumentException("Not JSON: " + value.getClass());
        }
    }
}
