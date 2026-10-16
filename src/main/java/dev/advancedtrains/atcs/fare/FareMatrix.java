package dev.advancedtrains.atcs.fare;

import dev.advancedtrains.atcs.network.Network;
import dev.advancedtrains.atcs.network.Station;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * The whole origin-destination matrix as a draft the operator corrects by hand, and the merge of
 * that draft back into the table. The draft keeps the generated value next to the editable one, so
 * on apply the pairs that were changed are recognised and marked as set by hand.
 */
public final class FareMatrix {
    public static final String DRAFT_FILE = "fare-matrix.yml";

    /**
     * @param pairs     the editable fares, by pair key
     * @param generated what the generator wrote, by pair key
     */
    public record Draft(Map<String, Double> pairs, Map<String, Double> generated) {
        public Draft {
            pairs = new TreeMap<>(pairs);
            generated = new TreeMap<>(generated);
        }

        /** Pairs whose fare differs from the generated one: the operator's corrections. */
        public Map<String, Double> edited() {
            Map<String, Double> edited = new TreeMap<>();
            pairs.forEach((key, fare) -> {
                Double original = generated.get(key);
                if (original == null || Math.abs(original - fare) > 0.0001) {
                    edited.put(key, fare);
                }
            });
            return edited;
        }
    }

    private FareMatrix() {
    }

    /** Every pair of stations: kept pairs as they are, the rest from the calculator. */
    public static Draft generate(Network network, FareCalculator calculator) {
        Map<String, Double> pairs = new TreeMap<>();
        List<Station> stations = new ArrayList<>(network.stations().values());
        for (int first = 0; first < stations.size(); first++) {
            for (int second = first + 1; second < stations.size(); second++) {
                String a = stations.get(first).id();
                String b = stations.get(second).id();
                String key = FareTable.pairKey(a, b);
                Optional<FareTable.Pair> kept = calculator.table().pair(a, b).filter(FareTable.Pair::keep);
                if (kept.isPresent()) {
                    pairs.put(key, kept.get().fare());
                    continue;
                }
                FareTable withoutPair = calculator.table().withPair(a, b, null);
                FareCalculator fresh = new FareCalculator(withoutPair, network, plannerOf(calculator));
                fresh.quote(a, b).ifPresent(quote -> pairs.put(key, quote.fare()));
            }
        }
        return new Draft(pairs, pairs);
    }

    private static dev.advancedtrains.atcs.operation.RoutePlanner plannerOf(FareCalculator calculator) {
        return calculator.planner();
    }

    public static String writeDraft(Draft draft, Network network, FareTable table, Instant at) {
        YamlConfiguration root = new YamlConfiguration();
        root.options().setHeader(List.of(
                "Fare matrix drafted by /atcs fare generate at " + at + ".",
                "Edit the fares under pairs: that are wrong, then run /atcs fare apply. A fare you changed",
                "is kept by every later generate; one left as it is follows the formula and zones again.",
                "Do not edit generated: it is how apply tells your changes apart. Fares are in " + table.currency() + "."));
        for (Map.Entry<String, Double> entry : draft.pairs().entrySet()) {
            root.set("pairs." + entry.getKey(), entry.getValue());
            List<String> ids = FareTable.stationsOf(entry.getKey());
            root.setComments("pairs." + entry.getKey(), List.of(network.stationName(ids.get(0)) + " - " + network.stationName(ids.get(1))));
        }
        for (Map.Entry<String, Double> entry : draft.generated().entrySet()) {
            root.set("generated." + entry.getKey(), entry.getValue());
        }
        return root.saveToString();
    }

    public static Draft readDraft(String yaml) throws InvalidConfigurationException {
        YamlConfiguration root = new YamlConfiguration();
        root.loadFromString(yaml);
        Map<String, Double> pairs = new LinkedHashMap<>();
        Map<String, Double> generated = new LinkedHashMap<>();
        var pairsSection = root.getConfigurationSection("pairs");
        if (pairsSection != null) {
            for (String key : pairsSection.getKeys(false)) {
                if (pairsSection.isDouble(key) || pairsSection.isInt(key)) {
                    pairs.put(key, pairsSection.getDouble(key));
                }
            }
        }
        var generatedSection = root.getConfigurationSection("generated");
        if (generatedSection != null) {
            for (String key : generatedSection.getKeys(false)) {
                if (generatedSection.isDouble(key) || generatedSection.isInt(key)) {
                    generated.put(key, generatedSection.getDouble(key));
                }
            }
        }
        return new Draft(pairs, generated);
    }

    /**
     * Loads the draft into the table: every pair becomes explicit; the ones the operator changed,
     * and the ones already set by hand, are marked to keep.
     */
    public static FareTable apply(FareTable table, Draft draft) {
        Map<String, Double> edited = draft.edited();
        Map<String, FareTable.Pair> pairs = new LinkedHashMap<>();
        draft.pairs().forEach((key, fare) -> {
            FareTable.Pair existing = table.pairs().get(key);
            boolean keep = edited.containsKey(key) || (existing != null && existing.keep() && Math.abs(existing.fare() - fare) < 0.0001);
            pairs.put(key, new FareTable.Pair(fare, keep));
        });
        table.pairs().forEach((key, pair) -> {
            if (!pairs.containsKey(key) && pair.keep()) {
                pairs.put(key, pair);
            }
        });
        return table.withPairs(pairs);
    }
}
