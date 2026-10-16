package dev.advancedtrains.atcs.runtime;

import dev.advancedtrains.atcs.api.FareApi;
import dev.advancedtrains.atcs.api.FareQuote;
import dev.advancedtrains.atcs.api.FareQuoteEvent;
import dev.advancedtrains.atcs.fare.FareCalculator;
import dev.advancedtrains.atcs.fare.FareStore;
import dev.advancedtrains.atcs.fare.FareTable;
import dev.advancedtrains.atcs.network.Network;
import dev.advancedtrains.atcs.network.NetworkStore;
import dev.advancedtrains.atcs.network.Station;
import dev.advancedtrains.atcs.operation.RouteMeasure;
import dev.advancedtrains.atcs.operation.RoutePlanner;
import dev.advancedtrains.atcs.operation.TravelTimes;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/** The fare API as other plugins see it, over the fare table, the network and the learned track. */
public final class FareService implements FareApi {
    private final Plugin plugin;
    private final FareStore store;
    private final NetworkStore networks;
    private final Supplier<TravelTimes> times;
    private final RouteMeasure measure;

    public FareService(Plugin plugin, FareStore store, NetworkStore networks, Supplier<TravelTimes> times, RouteMeasure measure) {
        this.plugin = plugin;
        this.store = store;
        this.networks = networks;
        this.times = times;
        this.measure = measure;
    }

    public FareStore store() {
        return store;
    }

    /** A calculator over the current table and network. */
    public FareCalculator calculator() {
        Network network = networks.network();
        return new FareCalculator(store.table(), network, RoutePlanner.of(network, times.get(), measure));
    }

    @Override
    public String currency() {
        return store.table().currency();
    }

    @Override
    public Optional<FareQuote> quote(String fromStationId, String toStationId) {
        Optional<FareCalculator.Quote> quote = calculator().quote(fromStationId, toStationId);
        if (quote.isEmpty()) {
            return Optional.empty();
        }
        FareQuoteEvent event = new FareQuoteEvent(fromStationId, toStationId, quote.get().fare(), currency(),
                quote.get().layer().name().toLowerCase(Locale.ROOT), quote.get().reasoning());
        plugin.getServer().getPluginManager().callEvent(event);
        return Optional.of(new FareQuote(fromStationId, toStationId, event.getFare(), currency(), event.getLayer(), event.getReasoning()));
    }

    /** For the website: the currency and the configured fare of every pair of stations. */
    public Map<String, Object> summary() {
        Network network = networks.network();
        FareTable table = store.table();
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("currency", table.currency());
        root.put("configured", table.configured());
        Map<String, Map<String, Double>> matrix = new LinkedHashMap<>();
        if (table.configured()) {
            FareCalculator calculator = calculator();
            List<Station> stations = new ArrayList<>(network.stations().values());
            for (int first = 0; first < stations.size(); first++) {
                for (int second = first + 1; second < stations.size(); second++) {
                    String a = stations.get(first).id();
                    String b = stations.get(second).id();
                    calculator.quote(a, b).ifPresent(quote -> {
                        matrix.computeIfAbsent(a, ignored -> new LinkedHashMap<>()).put(b, quote.fare());
                        matrix.computeIfAbsent(b, ignored -> new LinkedHashMap<>()).put(a, quote.fare());
                    });
                }
            }
        }
        root.put("matrix", matrix);
        return root;
    }
}
