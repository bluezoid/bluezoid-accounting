package com.accubook.tax;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TanTest {

    @Test
    @DisplayName("a TAN is four letters, five digits and a letter, any case")
    void format() {
        assertThat(Tan.problem("MUMA12345B")).isEmpty();
        assertThat(Tan.problem("muma12345b")).isEmpty();
        assertThat(Tan.problem("MUM12345B")).isPresent();
        assertThat(Tan.problem("MUMA1234BB")).isPresent();
        assertThat(Tan.problem(null)).isPresent();
    }
}
