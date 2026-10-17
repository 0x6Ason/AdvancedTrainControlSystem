package dev.advancedtrains.atcs.operation;

import dev.advancedtrains.atcs.control.StopOptions;
import dev.advancedtrains.atcs.control.SwitchRules;
import dev.advancedtrains.atcs.network.Ids;
import dev.advancedtrains.atcs.network.Service;
import dev.advancedtrains.atcs.network.ServiceKey;

import org.jspecify.annotations.Nullable;

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

    String KINDS = "'atcs stop', 'atcs station', 'atcs switch', 'atcs block', 'atcs speed', 'atcs service', 'atcs clear',"
            + " 'atcs indicator' or 'atcs depot'";

    /** Where a train sent to the depot ends: it is taken out of the world here, nobody aboard. */
    record DepotSign() implements AtcsSign {
    }

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

    /**
     * One end of a virtual block: trains wait at the entry sign while another train is between
     * it and the exit sign.
     *
     * @param entry whether this is where trains enter the block (else where they leave it)
     */
    record BlockSign(String blockId, boolean entry) implements AtcsSign {
    }

    /**
     * Sets the speed limit of passing trains, all of them or those of one line or service.
     *
     * @param selector a line id, {@code line/service}, or null for every train
     */
    record SpeedSign(double speed, @Nullable String selector) implements AtcsSign {
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
            case "depot", "stable", "yard" -> new DepotSign();
            case "indicator", "lamp" -> new IndicatorSign(Indicator.parse(third, fourth));
            case "block", "signal" -> {
                if (argument.isEmpty()) {
                    throw new IllegalArgumentException("Line 3 must name the block, for example 'tunnel-east'");
                }
                String end = extra.toLowerCase(Locale.ROOT);
                boolean entry = switch (end) {
                    case "", "enter", "entry", "in", "start", "begin" -> true;
                    case "exit", "leave", "out", "end", "free", "release" -> false;
                    default -> throw new IllegalArgumentException("Line 4 must be 'enter' or 'exit' (which end of block '"
                            + argument + "' this sign is)");
                };
                yield new BlockSign(Ids.normalize("block", argument), entry);
            }
            case "speed", "limit" -> {
                if (argument.isEmpty()) {
                    throw new IllegalArgumentException("Line 3 must give the speed in blocks per tick, for example '0.6'");
                }
                double speed;
                try {
                    speed = Double.parseDouble(argument);
                } catch (NumberFormatException exception) {
                    throw new IllegalArgumentException("'" + argument + "' is not a speed; write blocks per tick, for example '0.6'");
                }
                if (!(speed > 0 && speed <= Service.MAX_SPEED)) {
                    throw new IllegalArgumentException("The speed must be more than 0 and at most " + (int) Service.MAX_SPEED + " blocks per tick");
                }
                String selector = extra.isEmpty() || extra.equals("*") ? null : extra.toLowerCase(Locale.ROOT);
                if (selector != null) {
                    if (selector.contains("/")) {
                        ServiceKey.parse(selector);
                    } else {
                        Ids.normalize("line", selector);
                    }
                }
                yield new SpeedSign(speed, selector);
            }
            default -> throw new IllegalArgumentException("Unknown ATCS sign '" + words[1] + "': use " + KINDS);
        };
    }
}
