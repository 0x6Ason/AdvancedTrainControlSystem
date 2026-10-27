package dev.advancedtrains.atcs.fare;

import dev.advancedtrains.atcs.network.Ids;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Reads and writes {@code fares.yml}, a file meant to be edited by hand. */
public final class FareCodec {
    public static final int FORMAT_VERSION = 2;

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
        if (root.isSet("format")) {
            integer(root, "format", FORMAT_VERSION, 1, FORMAT_VERSION, errors);
        }
        FareTable.Pricing pricing = FareTable.Pricing.LEGACY;
        try {
            pricing = FareTable.Pricing.valueOf(text(root.get("pricing"), "pricing", "legacy", errors).toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            errors.add("pricing: expected legacy, cumulative, highest, lowest or custom");
        }
        String calculation = text(root.get("calculation"), "calculation", FareExpression.ADDITIVE, errors);
        try {
            FareExpression.compile(calculation);
        } catch (IllegalArgumentException exception) {
            errors.add(exception.getMessage());
            calculation = FareExpression.ADDITIVE;
        }
        String currency = text(root.get("currency"), "currency", "$", errors);
        FareTable.Formula formula = formula(section(root, "formula", errors), errors);
        FareTable.Billing billing = billing(section(root, "billing", errors), errors);
        Map<String, String> zones = new LinkedHashMap<>();
        ConfigurationSection zonesSection = section(root, "zones", errors);
        if (zonesSection != null) {
            for (String station : zonesSection.getKeys(false)) {
                Object zone = zonesSection.get(station);
                if (!(zone instanceof String name) || name.isBlank()) {
                    errors.add("zones." + station + ": expected a nonempty zone name");
                    continue;
                }
                try {
                    zones.put(Ids.normalize("station", station), name.strip().toLowerCase(Locale.ROOT));
                } catch (IllegalArgumentException exception) {
                    errors.add("zones: " + exception.getMessage());
                }
            }
        }
        Map<String, Double> zoneFares = new LinkedHashMap<>();
        ConfigurationSection zoneFaresSection = section(root, "zone-fares", errors);
        if (zoneFaresSection != null) {
            for (String key : zoneFaresSection.getKeys(false)) {
                String[] parts = key.split("-", 2);
                if (parts.length != 2 || parts[0].isBlank() || parts[1].isBlank()) {
                    errors.add("zone-fares." + key + ": expected 'zone-zone: fare'");
                    continue;
                }
                int before = errors.size();
                double fare = number(zoneFaresSection, key, 0, errors);
                if (errors.size() == before) {
                    zoneFares.put(FareTable.zoneKey(parts[0], parts[1]), fare);
                }
            }
        }
        Map<String, FareTable.Pair> pairs = new LinkedHashMap<>();
        ConfigurationSection pairsSection = section(root, "pairs", errors);
        if (pairsSection != null) {
            for (String key : pairsSection.getKeys(false)) {
                String[] parts = key.split("\\|", 2);
                if (parts.length != 2) {
                    errors.add("pairs." + key + ": expected 'station|station'");
                    continue;
                }
                try {
                    String pairKey = FareTable.pairKey(parts[0], parts[1]);
                    int before = errors.size();
                    double fare;
                    boolean keep = false;
                    ConfigurationSection pair = pairsSection.getConfigurationSection(key);
                    if (pair != null) {
                        fare = monetary(pair.get("fare"), "pairs." + key + ".fare", 0, errors);
                        keep = bool(pair, "keep", true, errors);
                    } else {
                        fare = monetary(pairsSection.get(key), "pairs." + key, 0, errors);
                    }
                    if (errors.size() == before) {
                        pairs.put(pairKey, new FareTable.Pair(fare, keep));
                    }
                } catch (IllegalArgumentException exception) {
                    errors.add("pairs." + key + ": " + exception.getMessage());
                }
            }
        }
        // FareStore accepts a partial table on first load. Never let an invalid file enable billing.
        if (!errors.isEmpty() && billing.enabled()) {
            billing = new FareTable.Billing(false, billing.freeLines(), billing.minimum(), billing.transferWindowSeconds(),
                    billing.transferDiscount(), billing.transferDiscountPercent());
        }
        return new ReadResult(new FareTable(currency, formula, zones, zoneFares, pairs, billing, pricing, calculation), errors);
    }

    private static FareTable.Billing billing(@Nullable ConfigurationSection section, List<String> errors) {
        if (section == null) {
            return FareTable.Billing.DEFAULT;
        }
        boolean enabled = bool(section, "enabled", false, errors);
        Set<String> freeLines = new LinkedHashSet<>();
        if (section.isSet("free-lines")) {
            Object raw = section.get("free-lines");
            if (raw instanceof List<?> lines) {
                for (Object line : lines) {
                    if (!(line instanceof String id)) {
                        errors.add("billing.free-lines: expected line IDs as strings");
                        continue;
                    }
                    try {
                        freeLines.add(Ids.normalize("line", id));
                    } catch (IllegalArgumentException exception) {
                        errors.add("billing.free-lines: " + exception.getMessage());
                    }
                }
            } else {
                errors.add("billing.free-lines: expected a list of line IDs");
            }
        }
        double minimum = number(section, "minimum", 0, errors);
        int window = integer(section, "transfer-window-seconds", 180, 0, 3600, errors);
        double discount = number(section, "transfer-discount", 0, errors);
        double percent = number(section, "transfer-discount-percent", 0, errors);
        if (percent > 100) {
            errors.add("billing.transfer-discount-percent: must be between 0 and 100");
            percent = 0;
        }
        return new FareTable.Billing(enabled, freeLines, minimum, window, discount, percent);
    }

    private static FareTable.Formula formula(@Nullable ConfigurationSection section, List<String> errors) {
        if (section == null) {
            return FareTable.Formula.NONE;
        }
        List<FareTable.Band> bands = new ArrayList<>();
        if (section.isSet("distance-bands")) {
            Object raw = section.get("distance-bands");
            if (raw instanceof List<?> entries) {
                for (int index = 0; index < entries.size(); index++) {
                    String path = "formula.distance-bands[" + index + "]";
                    if (!(entries.get(index) instanceof Map<?, ?> entry)) {
                        errors.add(path + ": expected {up-to: blocks, fare: amount}");
                        continue;
                    }
                    int before = errors.size();
                    double fare = monetary(entry.get("fare"), path + ".fare", 0, errors);
                    double upTo = 0;
                    if (entry.containsKey("up-to")) {
                        Object value = entry.get("up-to");
                        if (!(value instanceof Number number) || !Double.isFinite(number.doubleValue())) {
                            errors.add(path + ".up-to: expected a finite number");
                        } else {
                            upTo = number.doubleValue();
                        }
                    }
                    if (errors.size() == before) {
                        bands.add(new FareTable.Band(upTo, fare));
                    }
                }
            } else {
                errors.add("formula.distance-bands: expected a list of distance bands");
            }
        }
        for (int index = 1; index < bands.size(); index++) {
            FareTable.Band previous = bands.get(index - 1);
            FareTable.Band current = bands.get(index);
            if (previous.unbounded() || !current.unbounded() && current.upTo() <= previous.upTo()) {
                errors.add("formula.distance-bands: finite bounds must ascend, with any unbounded band last");
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

    private static @Nullable ConfigurationSection section(ConfigurationSection parent, String key, List<String> errors) {
        ConfigurationSection section = parent.getConfigurationSection(key);
        if (parent.isSet(key) && section == null) {
            errors.add(path(parent, key) + ": expected a mapping");
        }
        return section;
    }

    private static String path(ConfigurationSection section, String key) {
        String parent = section.getCurrentPath();
        return parent == null || parent.isEmpty() ? key : parent + "." + key;
    }

    private static String text(@Nullable Object raw, String path, String fallback, List<String> errors) {
        if (raw == null) {
            return fallback;
        }
        if (raw instanceof String text) {
            return text;
        }
        errors.add(path + ": expected a string");
        return fallback;
    }

    private static boolean bool(ConfigurationSection section, String key, boolean fallback, List<String> errors) {
        if (!section.isSet(key)) {
            return fallback;
        }
        if (section.get(key) instanceof Boolean value) {
            return value;
        }
        errors.add(path(section, key) + ": expected a boolean");
        return fallback;
    }

    private static int integer(ConfigurationSection section, String key, int fallback, int min, int max, List<String> errors) {
        if (!section.isSet(key)) {
            return fallback;
        }
        Object raw = section.get(key);
        if (raw instanceof Byte || raw instanceof Short || raw instanceof Integer || raw instanceof Long) {
            long value = ((Number) raw).longValue();
            if (value >= min && value <= max) {
                return (int) value;
            }
        }
        errors.add(path(section, key) + ": expected an integer between " + min + " and " + max);
        return fallback;
    }

    private static double number(ConfigurationSection section, String key, double fallback, List<String> errors) {
        return section.isSet(key) ? monetary(section.get(key), path(section, key), fallback, errors) : fallback;
    }

    private static double monetary(@Nullable Object raw, String path, double fallback, List<String> errors) {
        if (raw instanceof Number number && Double.isFinite(number.doubleValue()) && number.doubleValue() >= 0) {
            return number.doubleValue();
        }
        errors.add(path + ": expected a finite nonnegative number");
        return fallback;
    }

    public String write(FareTable table) {
        YamlConfiguration root = new YamlConfiguration();
        root.options().setHeader(List.of(
                "Fare rules. pricing: legacy | cumulative | highest | lowest | custom.",
                "  legacy: pair overrides zone overrides formula (existing files keep this mode).",
                "  Other modes use the whole observed path, including loops and repeated crossings.",
                "  cumulative: base once + stops + changes + distance + zones + pairs.",
                "  highest/lowest: max/min(formula, zones, pairs), ignoring unavailable alternatives.",
                "  custom: calculation combines money components with + - * /, sum(...), min(...), max(...).",
                "  Divide only by positive constants. A configured zero is an applicable price.",
                "  formula = base + stops + changes + distance. Zone/pair amounts add per station hop.",
                "  Free lines are excluded. Transfer discounts apply to the next paid line's contribution;",
                "  rounding and the higher of formula.minimum and billing.minimum apply to the total.",
                "  Do not reuse a generated total-fare matrix as per-hop supplements.",
                "billing: automatic fares, charged through Vault when a journey ends (/atcs fare billing).",
                "  Rides on free-lines cost nothing. Changing to another paid line within",
                "  transfer-window-seconds takes transfer-discount-percent, then transfer-discount, off",
                "  that ride. minimum is the least a paid journey costs, even one ending where it began.",
                "/atcs fare check <from> <to> shows the fare and how it was worked out.",
                "With legacy pricing, /atcs fare generate writes every pair as a draft (fare-matrix.yml) to correct by hand;",
                "/atcs fare apply loads the draft. Pairs with keep: true were set by hand and are never",
                "replaced by a later generate. Apply changes with /atcs reload."));
        root.set("format", FORMAT_VERSION);
        root.set("currency", table.currency());
        root.set("pricing", table.pricing().name().toLowerCase(Locale.ROOT));
        root.set("calculation", table.calculation());
        FareTable.Billing billing = table.billing();
        root.set("billing.enabled", billing.enabled());
        root.set("billing.free-lines", new ArrayList<>(billing.freeLines()));
        root.set("billing.minimum", billing.minimum());
        root.set("billing.transfer-window-seconds", billing.transferWindowSeconds());
        root.set("billing.transfer-discount", billing.transferDiscount());
        root.set("billing.transfer-discount-percent", billing.transferDiscountPercent());
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
