package dev.advancedtrains.atcs.api;

import java.util.Optional;

/**
 * Fares between stations, for other plugins (a card or gate plugin, a shop). Obtain it from
 * Bukkit's services manager:
 *
 * <pre>
 * FareApi fares = Bukkit.getServicesManager().load(FareApi.class);
 * fares.quote("main", "golden-beach").ifPresent(quote -> charge(player, quote.fare()));
 * </pre>
 *
 * Every quote passes through a {@link FareQuoteEvent}, which a plugin may use to change it.
 */
public interface FareApi {

    /** The symbol shown with fares, for example {@code $}. */
    String currency();

    /**
     * The fare from one station to another, by station id as in network.yml.
     *
     * @return empty when either station is unknown
     */
    Optional<FareQuote> quote(String fromStationId, String toStationId);
}
