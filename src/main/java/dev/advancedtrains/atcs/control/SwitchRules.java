package dev.advancedtrains.atcs.control;

import dev.advancedtrains.atcs.network.Ids;
import dev.advancedtrains.atcs.network.Network;
import dev.advancedtrains.atcs.network.ServiceKey;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * The rules of an {@code atcs switch} sign: one per line, each a direction followed by the trains it
 * is for. Trains are picked by service ({@code 1/express}), by line ({@code 1}), by the station
 * they reach next ({@code golden-beach}) or {@code *} for every train. The first rule that fits
 * wins; a train no rule fits leaves the switch as it is.
 *
 * <pre>
 * [+train]
 * atcs switch
 * left 1/express
 * right *
 * </pre>
 */
public record SwitchRules(List<Rule> rules) {

    public static final String HELP = "each line is a direction (left, right, continue, north, ...) followed by"
            + " the trains it is for: a service (1/express), a line (1), the station they reach next (main), or *";

    /** @param selectors services, lines, stations or {@code *}; an empty list means every train */
    public record Rule(Heading heading, List<String> selectors) {
        public Rule {
            selectors = List.copyOf(selectors);
        }

        boolean matches(@Nullable ServiceKey service, @Nullable String nextStationId, Network network) {
            if (selectors.isEmpty()) {
                return true;
            }
            for (String selector : selectors) {
                if (selectorMatches(selector, service, nextStationId, network)) {
                    return true;
                }
            }
            return false;
        }

        private static boolean selectorMatches(String selector, @Nullable ServiceKey service,
                                               @Nullable String nextStationId, Network network) {
            if (selector.equals("*")) {
                return true;
            }
            if (selector.contains("/")) {
                return service != null && service.equals(ServiceKey.parse(selector));
            }
            boolean line = network.line(selector).isPresent() && service != null && service.lineId().equals(selector);
            boolean station = network.station(selector).isPresent() && selector.equals(nextStationId);
            return line || station;
        }

        public String format() {
            return heading.word() + (selectors.isEmpty() ? "" : " " + String.join(" ", selectors));
        }
    }

    public SwitchRules {
        rules = List.copyOf(rules);
    }

    /**
     * @param lines sign lines 3 and 4 and any extra lines below the sign; blank lines are skipped
     * @throws IllegalArgumentException with a player-readable message when the text is invalid
     */
    public static SwitchRules parse(List<String> lines) {
        List<Rule> rules = new ArrayList<>();
        for (String line : lines) {
            String text = line.strip().toLowerCase(Locale.ROOT);
            if (text.isEmpty()) {
                continue;
            }
            String[] words = text.split("[\\s,]+");
            Heading heading = Heading.parse(words[0]);
            if (heading == null) {
                throw new IllegalArgumentException("'" + words[0] + "' is not a direction: " + HELP);
            }
            List<String> selectors = new ArrayList<>();
            for (int index = 1; index < words.length; index++) {
                String word = words[index];
                if (word.isEmpty()) {
                    continue;
                }
                if (word.equals("*") || word.equals("all") || word.equals("any")) {
                    selectors.add("*");
                } else if (word.contains("/")) {
                    selectors.add(ServiceKey.parse(word).toString());
                } else {
                    selectors.add(Ids.normalize("line or station", word));
                }
            }
            rules.add(new Rule(heading, selectors));
        }
        if (rules.isEmpty()) {
            throw new IllegalArgumentException("Line 3 must give a rule: " + HELP);
        }
        return new SwitchRules(rules);
    }

    /** The direction for a train running {@code service} whose next station is {@code nextStationId}. */
    public Optional<Heading> choose(@Nullable ServiceKey service, @Nullable String nextStationId, Network network) {
        for (Rule rule : rules) {
            if (rule.matches(service, nextStationId, network)) {
                return Optional.of(rule.heading());
            }
        }
        return Optional.empty();
    }

    /** Selectors that name nothing in the network, for warnings when the sign is built. */
    public List<String> unknownSelectors(Network network) {
        List<String> unknown = new ArrayList<>();
        for (Rule rule : rules) {
            for (String selector : rule.selectors()) {
                if (selector.equals("*")) {
                    continue;
                }
                boolean known = selector.contains("/")
                        ? network.service(ServiceKey.parse(selector)).isPresent()
                        : network.line(selector).isPresent() || network.station(selector).isPresent();
                if (!known) {
                    unknown.add(selector);
                }
            }
        }
        return unknown;
    }

    public String describe() {
        List<String> parts = new ArrayList<>();
        for (Rule rule : rules) {
            parts.add(rule.format());
        }
        return "switch the track by service: " + String.join("; ", parts);
    }
}
