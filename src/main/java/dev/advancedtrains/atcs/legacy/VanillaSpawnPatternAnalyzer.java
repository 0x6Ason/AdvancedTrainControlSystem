package dev.advancedtrains.atcs.legacy;

import java.util.OptionalInt;

/**
 * Counts a spawn pattern made exclusively from regular minecarts, such as mmm, 9m, or 12m.
 */
public final class VanillaSpawnPatternAnalyzer {
    private VanillaSpawnPatternAnalyzer() {
    }

    public static OptionalInt regularMinecartCount(String pattern) {
        String compact = pattern == null ? "" : pattern.replaceAll("\\s+", "").toLowerCase();
        if (compact.isEmpty()) {
            return OptionalInt.empty();
        }

        int total = 0;
        int index = 0;
        while (index < compact.length()) {
            int numberStart = index;
            while (index < compact.length() && Character.isDigit(compact.charAt(index))) {
                index++;
            }
            String amountText = compact.substring(numberStart, index);
            if (index >= compact.length() || compact.charAt(index) != 'm') {
                return OptionalInt.empty();
            }
            index++;
            int amount;
            try {
                amount = amountText.isEmpty() ? 1 : Integer.parseInt(amountText);
                total = Math.addExact(total, amount);
            } catch (NumberFormatException | ArithmeticException exception) {
                return OptionalInt.empty();
            }
        }
        return total > 0 ? OptionalInt.of(total) : OptionalInt.empty();
    }
}

