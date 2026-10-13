package com.accubook.returns;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JsonReaderTest {

    @Test
    @DisplayName("numbers stay exact decimals - 0.1 is 0.1, never 0.1000000000000000055")
    void exactNumbers() {
        Object v = JsonReader.read("{\"a\": 0.1, \"b\": [1, 2.50, -3e2]}");
        @SuppressWarnings("unchecked")
        Map<String, Object> m = (Map<String, Object>) v;
        assertThat(m.get("a")).isEqualTo(new BigDecimal("0.1"));
        assertThat(m.get("b")).isEqualTo(List.of(new BigDecimal("1"), new BigDecimal("2.50"),
                new BigDecimal("-3e2")));
    }

    @Test
    @DisplayName("strings, escapes, literals and key order")
    void strings() {
        @SuppressWarnings("unchecked")
        Map<String, Object> m = (Map<String, Object>) JsonReader.read(
                "{\"z\":\"a\\\"b\\\\c\\n\\u0041\",\"y\":true,\"x\":null}");
        assertThat(m.keySet()).containsExactly("z", "y", "x");
        assertThat(m.get("z")).isEqualTo("a\"b\\c\nA");
        assertThat(m.get("y")).isEqualTo(Boolean.TRUE);
        assertThat(m.get("x")).isNull();
    }

    @Test
    @DisplayName("malformed input is refused, with where")
    void strict() {
        assertThatThrownBy(() -> JsonReader.read("{\"a\":1,}")).hasMessageContaining("expected a key");
        assertThatThrownBy(() -> JsonReader.read("{\"a\":1} x")).hasMessageContaining("after the value");
        assertThatThrownBy(() -> JsonReader.read("{\"a\":1,\"a\":2}")).hasMessageContaining("duplicate key");
        assertThatThrownBy(() -> JsonReader.read("[1, 2")).hasMessageContaining("unexpected end");
    }

    @Test
    @DisplayName("what the writer writes, the reader reads back")
    void roundTrip() {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("s", "x \"y\"\n");
        m.put("n", new BigDecimal("12.30"));
        m.put("l", List.of(Boolean.FALSE));
        assertThat(JsonReader.read(Json.write(m))).isEqualTo(m);
    }
}
