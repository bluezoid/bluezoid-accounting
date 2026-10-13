package com.accubook.returns;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A strict JSON reader for portal files. Numbers become {@link BigDecimal}
 * exactly as written - never a double, so ₹0.1 stays 0.1 (the reference parses
 * money with {@code Number()}, R-22). Objects keep their key order.
 */
final class JsonReader {

    private final String text;
    private int at;

    private JsonReader(String text) {
        this.text = text;
    }

    static Object read(String text) {
        JsonReader reader = new JsonReader(text);
        reader.space();
        Object value = reader.value();
        reader.space();
        if (reader.at != text.length()) {
            throw reader.error("unexpected text after the value");
        }
        return value;
    }

    private Object value() {
        if (at >= text.length()) {
            throw error("unexpected end");
        }
        char c = text.charAt(at);
        return switch (c) {
            case '{' -> object();
            case '[' -> array();
            case '"' -> string();
            case 't' -> literal("true", Boolean.TRUE);
            case 'f' -> literal("false", Boolean.FALSE);
            case 'n' -> literal("null", null);
            default -> number();
        };
    }

    private Map<String, Object> object() {
        Map<String, Object> map = new LinkedHashMap<>();
        at++;
        space();
        if (peek() == '}') {
            at++;
            return map;
        }
        while (true) {
            space();
            if (peek() != '"') {
                throw error("expected a key");
            }
            String key = string();
            space();
            expect(':');
            space();
            if (map.put(key, value()) != null) {
                throw error("duplicate key \"" + key + "\"");
            }
            space();
            if (peek() == ',') {
                at++;
                continue;
            }
            expect('}');
            return map;
        }
    }

    private List<Object> array() {
        List<Object> list = new ArrayList<>();
        at++;
        space();
        if (peek() == ']') {
            at++;
            return list;
        }
        while (true) {
            space();
            list.add(value());
            space();
            if (peek() == ',') {
                at++;
                continue;
            }
            expect(']');
            return list;
        }
    }

    private String string() {
        at++;
        StringBuilder out = new StringBuilder();
        while (true) {
            if (at >= text.length()) {
                throw error("unterminated string");
            }
            char c = text.charAt(at++);
            if (c == '"') {
                return out.toString();
            }
            if (c != '\\') {
                out.append(c);
                continue;
            }
            char e = text.charAt(at++);
            switch (e) {
                case '"', '\\', '/' -> out.append(e);
                case 'b' -> out.append('\b');
                case 'f' -> out.append('\f');
                case 'n' -> out.append('\n');
                case 'r' -> out.append('\r');
                case 't' -> out.append('\t');
                case 'u' -> {
                    out.append((char) Integer.parseInt(text.substring(at, at + 4), 16));
                    at += 4;
                }
                default -> throw error("bad escape \\" + e);
            }
        }
    }

    private BigDecimal number() {
        int start = at;
        while (at < text.length() && "+-0123456789.eE".indexOf(text.charAt(at)) >= 0) {
            at++;
        }
        if (start == at) {
            throw error("unexpected character '" + text.charAt(at) + "'");
        }
        try {
            return new BigDecimal(text.substring(start, at));
        } catch (NumberFormatException e) {
            throw error("bad number " + text.substring(start, at));
        }
    }

    private Object literal(String word, Object value) {
        if (!text.startsWith(word, at)) {
            throw error("unexpected text");
        }
        at += word.length();
        return value;
    }

    private void space() {
        while (at < text.length() && Character.isWhitespace(text.charAt(at))) {
            at++;
        }
    }

    private char peek() {
        if (at >= text.length()) {
            throw error("unexpected end");
        }
        return text.charAt(at);
    }

    private void expect(char c) {
        if (peek() != c) {
            throw error("expected '" + c + "'");
        }
        at++;
    }

    private ReturnException error(String message) {
        return new ReturnException("Not a valid JSON file at character " + at + ": " + message);
    }
}
