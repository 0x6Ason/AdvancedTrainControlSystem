package dev.advancedtrains.atcs.operation;

import dev.advancedtrains.atcs.control.StopOptions;
import dev.advancedtrains.atcs.control.SwitchRules;
import dev.advancedtrains.atcs.network.Ids;
import dev.advancedtrains.atcs.network.ServiceKey;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The text of an ATCS TrainCarts sign:
 *
 * <pre>
 * [+train]        [+train]          [+train]      [+train]         [+train]        [+train]
 * atcs stop       atcs service      atcs clear    atcs indicator   atcs station    atcs switch
 * main            a/local-north                   main:2           main:2          left 1/express
 * 2                                               approach a       10s continue    right *
 * </pre>
 *
 * A stop sign marks a platform without binding it in network.yml (line 3 {@code station} or
 * {@code station:platform}, line 4 an optional platform, default {@code 1}). A service sign assigns
 * passing trains to a service; a clear sign removes the assignment. An indicator sign drives the
 * levers on its block while a train is at, approaching or just past a station ({@link Indicator}).
 * A station sign is a platform that also stops the trains whose service calls there and lets the
 * others run through ({@link StopOptions}); a switch sign sets the junction by service
 * ({@link SwitchRules}).
 */
public sealed interface AtcsSign {

    String KINDS = "'atcs stop', 'atcs station', 'atcs switch', 'atcs service', 'atcs clear' or 'atcs indicator'";

    record StopSign(String stationId, String platformId) implements AtcsSign {
    }

    record StationSign(String stationId, String platformId, StopOptions options) implements AtcsSign {
        public String destinationName() {
            return stationId + ":" + platformId;
        }
    }

    record SwitchSign(SwitchRules rules) implements AtcsSign {
    }

    record ServiceSign(ServiceKey service) implements AtcsSign {
    }

    record ClearSign() implements AtcsSign {
    }

    record IndicatorSign(Indicator indicator) implements AtcsSign {
    }

    static AtcsSign parse(String action, String third, String fourth) {
        return parse(action, third, fourth, List.of());
    }

    /**
     * @param action     the second sign line, starting with {@code atcs}
     * @param extraLines lines of further signs placed under this one, used by switch signs
     * @throws IllegalArgumentException with a player-readable message when the text is invalid
     */
    static AtcsSign parse(String action, String third, String fourth, List<String> extraLines) {
        String[] words = action.strip().toLowerCase(Locale.ROOT).split("\\s+");
        if (words.length < 2 || !words[0].equals("atcs")) {
            throw new IllegalArgumentException("Line 2 must be " + KINDS);
        }
        String argument = third.strip();
        String extra = fourth.strip();
        return switch (words[1]) {
            case "stop", "platform" -> {
                if (argument.isEmpty()) {
                    throw new IllegalArgumentException("Line 3 must name the station, for example 'main' or 'main:2'");
                }
                int colon = argument.indexOf(':');
                String station = colon < 0 ? argument : argument.substring(0, colon);
                String platform = colon < 0 ? (extra.isEmpty() ? "1" : extra) : argument.substring(colon + 1);
                yield new StopSign(Ids.normalize("station", station), Ids.normalize("platform", platform));
            }
            case "station", "halt" -> {
                if (argument.isEmpty()) {
                    throw new IllegalArgumentException("Line 3 must name the station and platform, for example 'main' or 'main:2'");
                }
                int colon = argument.indexOf(':');
                String station = colon < 0 ? argument : argument.substring(0, colon);
                String platform = colon < 0 ? "1" : argument.substring(colon + 1);
                yield new StationSign(Ids.normalize("station", station), Ids.normalize("platform", platform),
                        StopOptions.parse(fourth));
            }
            case "switch", "switcher", "junction" -> {
                List<String> lines = new ArrayList<>();
                lines.add(third);
                lines.add(fourth);
                lines.addAll(extraLines);
                yield new SwitchSign(SwitchRules.parse(lines));
            }
            case "service", "assign" -> {
                if (argument.isEmpty()) {
                    throw new IllegalArgumentException("Line 3 must name the service as line/service, for example 'a/local'");
                }
                ServiceKey key = argument.contains("/") || extra.isEmpty()
                        ? ServiceKey.parse(argument)
                        : new ServiceKey(argument, extra);
                yield new ServiceSign(key);
            }
            case "clear", "unassign" -> new ClearSign();
            case "indicator", "lamp" -> new IndicatorSign(Indicator.parse(third, fourth));
            default -> throw new IllegalArgumentException("Unknown ATCS sign '" + words[1] + "': use " + KINDS);
        };
    }
}
