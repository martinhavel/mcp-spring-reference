package org.example.mcpref.ico;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

/** AC3 (unit level): only 8-digit values with a valid MOD11 check digit become an {@link Ico}. Fixture values only, no real identifiers. */
class IcoTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "87654326", // weighted sum 203, r=5  -> check digit 6
            "00000141", // weighted sum 11,  r=0  -> check digit 1
            "00000230", // weighted sum 12,  r=1  -> check digit 0
    })
    void acceptsValidChecksum(String value) {
        assertThat(Ico.parse(value).value()).isEqualTo(value);
    }

    @Test
    void stripsSurroundingWhitespace() {
        assertThat(Ico.parse(" 00000141 ").value()).isEqualTo("00000141");
    }

    @Test
    void keepsLeadingZerosAndRequiresThem() {
        assertThat(Ico.parse("00000141").value()).isEqualTo("00000141");
        assertThatThrownBy(() -> Ico.parse("0000141")).isInstanceOf(InvalidIcoException.class);
        assertThatThrownBy(() -> Ico.parse("141")).isInstanceOf(InvalidIcoException.class);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {
            "",
            "12345678",         // wrong check digit
            "00000000",         // wrong check digit (r=0 requires 1)
            "1234567",          // too short
            "000001410",        // too long
            "1234567a",
            "12 34 567",
            "00000141;",
            "00000141 OR 1=1",  // injection attempt
            "١٢٣٤٥٦٧٩", // non-ASCII digits
    })
    void rejectsEverythingElse(String value) {
        assertThatThrownBy(() -> Ico.parse(value)).isInstanceOf(InvalidIcoException.class);
    }
}
