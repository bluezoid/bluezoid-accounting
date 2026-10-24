package com.accubook.tax;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CompanyIdentityTest {

    @Test
    @DisplayName("a PIN code is six digits and does not start with 0")
    void pin() {
        assertThat(CompanyIdentity.pinProblem("411001")).isEmpty();
        assertThat(CompanyIdentity.pinProblem("011001")).isPresent();
        assertThat(CompanyIdentity.pinProblem("41100")).isPresent();
        assertThat(CompanyIdentity.pinProblem(null)).isPresent();
    }
}
