package dev.advancedtrains.atcs.api;

import java.util.List;

/**
 * A fare and where it came from.
 *
 * @param layer     {@code pair}, {@code zone}, {@code formula}, {@code none} (same station or no
 *                  route), or {@code plugin} when a {@link FareQuoteEvent} listener changed it
 * @param reasoning how the fare was worked out, in lines an operator can read
 */
public record FareQuote(String from, String to, double fare, String currency, String layer, List<String> reasoning) {
    public FareQuote {
        reasoning = List.copyOf(reasoning);
    }
}
