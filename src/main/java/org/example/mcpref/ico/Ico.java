package org.example.mcpref.ico;

import java.util.regex.Pattern;

/**
 * A validated Czech company identification number (IČO).
 *
 * <p>The only way to obtain an instance is {@link #parse(String)}, so holding an {@code Ico}
 * proves the value is exactly 8 digits with a correct MOD11 check digit. This is the only
 * parameter that is ever forwarded to the upstream MCP tool.
 */
public record Ico(String value) {

    private static final Pattern EIGHT_DIGITS = Pattern.compile("\\d{8}");
    private static final int[] WEIGHTS = {8, 7, 6, 5, 4, 3, 2};

    public Ico {
        if (value == null || !EIGHT_DIGITS.matcher(value).matches() || !hasValidCheckDigit(value)) {
            throw new InvalidIcoException("IČO must be 8 digits with a valid MOD11 check digit");
        }
    }

    public static Ico parse(String raw) {
        return new Ico(raw == null ? null : raw.strip());
    }

    /**
     * MOD11: weighted sum of the first seven digits (weights 8..2), r = sum mod 11,
     * check digit = (11 - r) mod 10 (so r=0 gives 1 and r=1 gives 0).
     */
    static boolean hasValidCheckDigit(String digits) {
        int sum = 0;
        for (int i = 0; i < WEIGHTS.length; i++) {
            sum += (digits.charAt(i) - '0') * WEIGHTS[i];
        }
        int expected = (11 - sum % 11) % 10;
        return digits.charAt(7) - '0' == expected;
    }

    @Override
    public String toString() {
        return value;
    }
}
