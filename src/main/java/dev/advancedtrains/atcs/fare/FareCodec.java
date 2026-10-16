package dev.advancedtrains.atcs.fare;

import dev.advancedtrains.atcs.network.Ids;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Reads and writes {@code fares.yml}, a file meant to be edited by hand. */
public final class FareCodec {
    public static final int FORMAT_VERSION = 1;

    public record ReadResult(FareTable table, List<String> errors) {
        public ReadResult {
            errors = List.copyOf(errors);
        }

        public boolean clean() {
            return errors.isEmpty();
        }
    }

    public ReadResult read(String yaml) {
        YamlConfiguration root = new YamlConfiguration();
        try {
            root.loadFromString(yaml);
        } catch (InvalidConfigurationException exception) {
            return new ReadResult(FareTable.EMPTY, List.of("not valid YAML: " + exception.getMessage()));
        }
        return read(root);
    }

    public ReadResult read(ConfigurationSection root) {
        List<String> errors = new ArrayList<>();
        String currency = root.getString("currency", "$");
        FareTable.Formula formula = formula(root.getConfigurationSection("formula"), errors);
        Map<String, String> zones = new LinkedHashMap<>();
        ConfigurationSection zonesSection = root.getConfigurationSection("zones");
        if (zonesSection != null) {
            for (String station : zonesSection.getKeys(false)) {
                try {
                    zones.put(Ids.normalize("station", station), String.valueOf(zonesSection.get(station)).strip().toLowerCase(java.util.Locale.ROOT));
                } catch (IllegalArgumentException exception) {
                    errors.add("zones: " + exception.getMessage());
                }
            }
        }
        Map<String, Double> zoneFares = new LinkedHashMap<>();
        ConfigurationSection zoneFaresSection = root.getConfigurationSection("zone-fares");
        if (zoneFaresSection != null) {
            for (String key : zoneFaresSection.getKeys(false)) {
                String[] parts = key.split("-", 2);
                if (parts.length != 2 || !zoneFaresSection.isDouble(key) && !zoneFaresSection.isInt(key)) {
                    errors.add("zone-fares." + key + ": expected 'zone-zone: fare'");
                    continue;
                }
                zoneFares.put(FareTable.zoneKey(parts[0], parts[1]), zoneFaresSection.getDouble(key));
            }
        }
        Map<String, FareTable.Pair> pairs = new LinkedHashMap<>();
        ConfigurationSection pairsSection = root.getConfigurationSection("pairs");
        if (pairsSection != null) {
            for (String key : pairsSection.getKeys(false)) {
                String[] parts = key.split("\\|", 2);
                if (parts.length != 2) {
                    errors.add("pairs." + key + ": expected 'station|station'");
                    continue;
                }
                try {
                    String pairKey = FareTable.pairKey(parts[0], parts[1]);
                    if (pairsSection.isConfigurationSection(key)) {
                        ConfigurationSection pair = pairsSection.getConfigurationSection(key);
                        pairs.put(pairKey, new FareTable.Pair(pair.getDouble("fare"), pair.getBoolean("keep", true)));
                    } else if (pairsSection.isDouble(key) || pairsSection.isInt(key)) {
                        pairs.put(pairKey, new FareTable.Pair(pairsSection.getDouble(key), false));
                    } else {
                        errors.add("pairs." + key + ": expected a fare, or {fare: .., keep: true}");
                    }
                } catch (IllegalArgumentException exception) {
                    errors.add("pairs." + key + ": " + exception.getMessage());
                }
            }
        }
        return new ReadResult(new FareTable(currency, formula, zones, zoneFares, pairs), errors);
    }

    private static FareTable.Formula formula(ConfigurationSection section, List<String> errors) {
        if (section == null) {
            return FareTable.Formula.NONE;
        }
        List<FareTable.Band> bands = new ArrayList<>();
        for (Map<?, ?> entry : section.getMapList("distance-bands")) {
            Object upTo = entry.get("up-to");
            Object fare = entry.get("fare");
            if (!(fare instanceof Number)) {
                errors.add("formula.distance-bands: every band needs a fare");
                continue;
            }
            bands.add(new FareTable.Band(upTo instanceof Number number ? number.doubleValue() : 0, ((Number) fare).doubleValue()));
        }
        for (int index = 1; index < bands.size(); index++) {
            if (!bands.get(index).unbounded() && bands.get(index).upTo() <= bands.get(index - 1).upTo()) {
                errors.add("formula.distance-bands: bands must be in ascending order of up-to");
                break;
            }
        }
        return new FareTable.Formula(
                number(section, "flat", 0, errors),
                number(section, "per-stop", 0, errors),
                number(section, "per-change", 0, errors),
                bands,
                number(section, "round-to", 0, errors),
                number(section, "minimum", 0, errors));
    }

    private static double number(ConfigurationSection section, String key, double fallback, List<String> errors) {
        if (!section.isSet(key)) {
            return fallback;
        }
        if (!section.isDouble(key) && !section.isInt(key)) {
            errors.add("formula." + key + ": expected a number");
            return fallback;
        }
        double value = section.getDouble(key);
        if (value < 0) {
            errors.add("formula." + key + ": cannot be negative");
            return fallback;
        }
        return value;
    }

    public String write(FareTable table) {
        YamlConfiguration root = new YamlConfiguration();
        root.options().setHeader(List.of(
                "Fares between stations. The highest layer that applies wins:",
                "  1. pairs: an explicit fare between two stations, in either direction",
                "  2. zone-fares: a fare between two zones, with each station in a zone under zones:",
                "  3. formula: flat + per-stop + per-change + a distance band over the track distance",
                "/atcs fare check <from> <to> shows the fare and which layer gave it.",
                "/atcs fare generate writes every pair as a draft (fare-matrix.yml) to correct by hand;",
                "/atcs fare apply loads the draft. Pairs with keep: true were set by hand and are never",
                "replaced by a later generate. Apply changes with /atcs reload."));
        root.set("format", FORMAT_VERSION);
        root.set("currency", table.currency());
        FareTable.Formula formula = table.formula();
        root.set("formula.flat", formula.flat());
        root.set("formula.per-stop", formula.perStop());
        root.set("formula.per-change", formula.perChange());
        List<Map<String, Object>> bands = new ArrayList<>();
        for (FareTable.Band band : formula.bands()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            if (!band.unbounded()) {
                entry.put("up-to", band.upTo());
            }
            entry.put("fare", band.fare());
            bands.add(entry);
        }
        root.set("formula.distance-bands", bands);
        root.set("formula.round-to", formula.roundTo());
        root.set("formula.minimum", formula.minimum());
        root.createSection("zones");
        table.zones().forEach((station, zone) -> root.set("zones." + station, zone));
        root.createSection("zone-fares");
        table.zoneFares().forEach((key, fare) -> root.set("zone-fares." + key, fare));
        root.createSection("pairs");
        table.pairs().forEach((key, pair) -> {
            if (pair.keep()) {
                root.set("pairs." + key + ".fare", pair.fare());
                root.set("pairs." + key + ".keep", true);
            } else {
                root.set("pairs." + key, pair.fare());
            }
        });
        return root.saveToString();
    }
}
