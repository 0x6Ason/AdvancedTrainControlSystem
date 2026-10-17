package dev.advancedtrains.atcs.control;

import dev.advancedtrains.atcs.network.Ids;
import dev.advancedtrains.atcs.network.Network;
import dev.advancedtrains.atcs.network.ServiceKey;
import dev.advancedtrains.atcs.network.PlatformRef;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Predicate;

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
            + " the trains it is for: a service (1/express), a line (1), the station they reach next (main), *,"
            + " 'free main:2' (trains for main while platform 2 is free) or 'depot' (trains sent to the depot)";

    /** A selector for trains whose next station's platform is free: {@code free:<station>:<platform>}. */
    static final String FREE = "free:";
    /** A selector for trains a dispatcher sent to the depot. */
    static final String DEPOT = "depot";

    /** What a switch knows about the train in front of it. */
    public record Context(@Nullable ServiceKey service, @Nullable String nextStationId, boolean toDepot,
                          Predicate<PlatformRef> platformFree) {
        public Context(@Nullable ServiceKey service, @Nullable String nextStationId) {
            this(service, nextStationId, false, platform -> true);
        }
    }

    /**
     * @param selectors services, lines, stations, {@code *}, {@code depot} or {@code free:station:platform};
     *                  an empty list means every train
     */
    public record Rule(Heading heading, List<String> selectors) {
        public Rule {
            selectors = List.copyOf(selectors);
        }

        boolean matches(Context train, Network network) {
            if (selectors.isEmpty()) {
                return true;
            }
            for (String selector : selectors) {
                if (selectorMatches(selector, train, network)) {
                    return true;
                }
            }
            return false;
        }

        private static boolean selectorMatches(String selector, Context train, Network network) {
            if (selector.equals("*")) {
                return true;
            }
            if (selector.equals(DEPOT)) {
                return train.toDepot();
            }
            if (selector.startsWith(FREE)) {
                PlatformRef platform = freePlatform(selector);
                return platform != null && platform.stationId().equals(train.nextStationId()) && train.platformFree().test(platform);
            }
            if (train.toDepot()) {
                return false;
            }
            if (selector.contains("/")) {
                return train.service() != null && train.service().equals(ServiceKey.parse(selector));
            }
            boolean line = network.line(selector).isPresent() && train.service() != null && train.service().lineId().equals(selector);
            boolean station = network.station(selector).isPresent() && selector.equals(train.nextStationId());
            return line || station;
        }

        /** The platform a {@code free} selector names, or null. */
        static @Nullable PlatformRef freePlatform(String selector) {
            if (!selector.startsWith(FREE)) {
                return null;
            }
            String[] parts = selector.substring(FREE.length()).split(":", 2);
            return parts.length == 2 ? new PlatformRef(parts[0], parts[1]) : null;
        }

        /** The platform this rule sends trains to while it is free, if it is such a rule. */
        public Optional<PlatformRef> freePlatform() {
            for (String selector : selectors) {
                PlatformRef platform = freePlatform(selector);
                if (platform != null) {
                    return Optional.of(platform);
                }
            }
            return Optional.empty();
        }

        public String format() {
            List<String> words = new ArrayList<>();
            for (String selector : selectors) {
                words.add(selector.startsWith(FREE) ? "free " + selector.substring(FREE.length()) : selector);
            }
            return heading.word() + (words.isEmpty() ? "" : " " + String.join(" ", words));
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
                } else if (word.equals(DEPOT)) {
                    selectors.add(DEPOT);
                } else if (word.equals("free")) {
                    String platform = index + 1 < words.length ? words[++index] : "";
                    String[] parts = platform.split(":", 2);
                    if (parts.length != 2 || parts[0].isEmpty() || parts[1].isEmpty()) {
                        throw new IllegalArgumentException("'free' needs the platform to fill, for example 'free main:2'");
                    }
                    selectors.add(FREE + Ids.normalize("station", parts[0]) + ":" + Ids.normalize("platform", parts[1]));
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
        return pick(new Context(service, nextStationId), network).map(Rule::heading);
    }

    /** The first rule that fits the train, if any. */
    public Optional<Rule> pick(Context train, Network network) {
        for (Rule rule : rules) {
            if (rule.matches(train, network)) {
                return Optional.of(rule);
            }
        }
        return Optional.empty();
    }

    /** Selectors that name nothing in the network, for warnings when the sign is built. */
    public List<String> unknownSelectors(Network network) {
        List<String> unknown = new ArrayList<>();
        for (Rule rule : rules) {
            for (String selector : rule.selectors()) {
                if (selector.equals("*") || selector.equals(DEPOT)) {
                    continue;
                }
                PlatformRef free = Rule.freePlatform(selector);
                boolean known = free != null
                        ? network.station(free.stationId()).flatMap(station -> station.platform(free.platformId())).isPresent()
                        : selector.contains("/")
                        ? network.service(ServiceKey.parse(selector)).isPresent()
                        : network.line(selector).isPresent() || network.station(selector).isPresent();
                if (!known) {
                    unknown.add(free != null ? "platform " + free : selector);
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
