package dev.advancedtrains.atcs.operation;

import dev.advancedtrains.atcs.network.Ids;
import dev.advancedtrains.atcs.network.ServiceKey;

import java.util.Locale;

/**
 * The text of an ATCS TrainCarts sign:
 *
 * <pre>
 * [+train]        [+train]          [+train]      [+train]
 * atcs stop       atcs service      atcs clear    atcs indicator
 * main            a/local-north                   main:2
 * 2                                               approach a
 * </pre>
 *
 * A stop sign marks a platform without binding it in network.yml (line 3 {@code station} or
 * {@code station:platform}, line 4 an optional platform, default {@code 1}). A service sign assigns
 * passing trains to a service; a clear sign removes the assignment. An indicator sign drives the
 * levers on its block while a train is at, approaching or just past a station ({@link Indicator}).
 */
public sealed interface AtcsSign {

    record StopSign(String stationId, String platformId) implements AtcsSign {
    }

    record ServiceSign(ServiceKey service) implements AtcsSign {
    }

    record ClearSign() implements AtcsSign {
    }

    record IndicatorSign(Indicator indicator) implements AtcsSign {
    }

    /**
     * @param action the second sign line, starting with {@code atcs}
     * @throws IllegalArgumentException with a player-readable message when the text is invalid
     */
    static AtcsSign parse(String action, String third, String fourth) {
        String[] words = action.strip().toLowerCase(Locale.ROOT).split("\\s+");
        if (words.length < 2 || !words[0].equals("atcs")) {
            throw new IllegalArgumentException("Line 2 must be 'atcs stop', 'atcs service', 'atcs clear' or 'atcs indicator'");
        }
        String argument = third.strip();
        String extra = fourth.strip();
        return switch (words[1]) {
            case "stop", "station", "platform" -> {
                if (argument.isEmpty()) {
                    throw new IllegalArgumentException("Line 3 must name the station, for example 'main' or 'main:2'");
                }
                int colon = argument.indexOf(':');
                String station = colon < 0 ? argument : argument.substring(0, colon);
                String platform = colon < 0 ? (extra.isEmpty() ? "1" : extra) : argument.substring(colon + 1);
                yield new StopSign(Ids.normalize("station", station), Ids.normalize("platform", platform));
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
            default -> throw new IllegalArgumentException(
                    "Unknown ATCS sign '" + words[1] + "': use 'atcs stop', 'atcs service', 'atcs clear' or 'atcs indicator'");
        };
    }
}
