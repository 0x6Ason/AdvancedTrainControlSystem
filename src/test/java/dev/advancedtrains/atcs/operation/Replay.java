package dev.advancedtrains.atcs.operation;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.advancedtrains.atcs.network.Network;
import dev.advancedtrains.atcs.network.PlatformRef;
import dev.advancedtrains.atcs.network.Service;
import dev.advancedtrains.atcs.network.ServiceKey;
import org.jspecify.annotations.Nullable;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.function.Function;

/**
 * Replays an event log (what the trains really did, with positions) through an estimator and
 * scores the estimates it would have shown, exactly as the server scores them: at the horizons of
 * {@link EtaSamples}. Model changes compete on the same data.
 *
 * <p>Journeys are rebuilt from the logged arrivals, departures and passes; learned times start
 * from a timings file and keep learning as the replay goes, as they do on the server.
 */
public final class Replay {

    /** How estimates are produced: {@code plain} uses learned times and the clock only. */
    public enum Model { PLAIN, POSITIONS, CONSISTENT }

    private record Position(String world, double x, double y, double z, double speed, Instant at, @Nullable Instant standingSince) {
    }

    private final Network network;
    private final TravelTimes times;
    private final TrackPaths paths;
    private final Map<String, JourneySnapshot> journeys = new HashMap<>();
    private final Map<String, Position> positions = new HashMap<>();
    private Instant now = Instant.EPOCH;

    public Replay(Network network, TravelTimes times, TrackPaths paths) {
        this.network = network;
        this.times = times;
        this.paths = paths;
    }

    /** Runs the log through the model and returns the scored samples. */
    public EtaSamples run(Path eventsJsonl, Model model) throws IOException {
        EtaSamples samples = new EtaSamples();
        EtaContext context = model == Model.PLAIN ? EtaContext.NONE : new EtaContext() {
            @Override
            public OptionalDouble segmentProgress(String trainId, Service service, int fromIndex) {
                Position position = positions.get(trainId);
                if (position == null || Duration.between(position.at(), now).toSeconds() > 5) {
                    return OptionalDouble.empty();
                }
                return paths.progress(service, fromIndex, position.world(), position.x(), position.y(), position.z());
            }

            @Override
            public boolean standing(String trainId) {
                Position position = positions.get(trainId);
                return position != null && position.standingSince() != null
                        && Duration.between(position.standingSince(), now).toSeconds() >= 2;
            }

            @Override
            public OptionalDouble remainingStop(String trainId, Instant now) {
                return OptionalDouble.empty();
            }
        };
        EtaCalculator plain = new EtaCalculator(times, 10, context);
        Function<Instant, Estimator> estimator = model == Model.CONSISTENT
                ? at -> ConsistentEstimates.compute(journeys.values(), network, plain, at)
                : at -> plain;
        try (BufferedReader reader = Files.newBufferedReader(eventsJsonl, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                JsonObject event = JsonParser.parseString(line).getAsJsonObject();
                now = Instant.parse(event.get("t").getAsString());
                String kind = event.get("e").getAsString();
                String train = event.get("train").getAsString();
                if (kind.equals("pos")) {
                    position(train, event);
                    sample(train, estimator.apply(now), samples);
                    continue;
                }
                JourneyEvent journeyEvent = apply(train, kind, event);
                if (journeyEvent != null) {
                    if (journeyEvent instanceof JourneyEvent.Arrived arrived) {
                        samples.arrived(train, arrived.service(), arrived.stopIndex(), now);
                    }
                    times.record(journeyEvent, network);
                }
                sample(train, estimator.apply(now), samples);
            }
        }
        return samples;
    }

    private void position(String train, JsonObject event) {
        double speed = event.get("speed").getAsDouble();
        Position previous = positions.get(train);
        Instant standingSince = speed >= 0.02 ? null : previous != null && previous.standingSince() != null ? previous.standingSince() : now;
        positions.put(train, new Position(event.get("world").getAsString(), event.get("x").getAsDouble(),
                event.get("y").getAsDouble(), event.get("z").getAsDouble(), speed, now, standingSince));
    }

    private void sample(String train, Estimator estimator, EtaSamples samples) {
        JourneySnapshot journey = journeys.get(train);
        if (journey == null || journey.service() == null || journey.phase() == JourneyPhase.COMPLETED) {
            return;
        }
        Service service = network.service(journey.service()).orElse(null);
        if (service == null) {
            return;
        }
        int next = journey.nextCallingIndex(service);
        if (next < 0) {
            return;
        }
        estimator.secondsUntil(journey, service, next, now)
                .ifPresent(seconds -> samples.predicted(train, service.key(), next, seconds, now));
    }

    /** Updates the train's journey from a logged event and rebuilds the journey event for learning. */
    private @Nullable JourneyEvent apply(String train, String kind, JsonObject event) {
        JourneySnapshot before = journeys.get(train);
        ServiceKey key = string(event, "service") == null ? null : ServiceKey.parse(string(event, "service"));
        switch (kind) {
            case "arrived" -> {
                PlatformRef platform = new PlatformRef(string(event, "station"), string(event, "platform"));
                int stop = event.get("stop").getAsInt();
                int from = event.get("from").getAsInt();
                Duration travel = duration(event.get("travel"));
                journeys.put(train, new JourneySnapshot(train, key, JourneyPhase.AT_STOP, stop, platform, platform, -1,
                        now, null, null, now));
                return new JourneyEvent.Arrived(train, now, key, stop, platform, event.get("terminus").getAsBoolean(), from, travel);
            }
            case "departed" -> {
                PlatformRef platform = new PlatformRef(string(event, "station"), string(event, "platform"));
                int stop = event.get("stop").getAsInt();
                journeys.put(train, new JourneySnapshot(train, key, JourneyPhase.IN_TRANSIT, stop, null, platform, -1,
                        before == null ? null : before.arrivedAt(), now, now, now));
                return new JourneyEvent.Departed(train, now, key, stop, platform, event.get("next").getAsInt(), duration(event.get("dwell")));
            }
            case "passed" -> {
                PlatformRef platform = new PlatformRef(string(event, "station"), string(event, "platform"));
                int stop = event.get("stop").getAsInt();
                int from = event.get("from").getAsInt();
                if (stop >= 0) {
                    journeys.put(train, new JourneySnapshot(train, key, JourneyPhase.IN_TRANSIT, stop, null, platform, -1,
                            null, now, now, now));
                }
                return new JourneyEvent.Passed(train, now, key, stop, platform, from, duration(event.get("travel")));
            }
            case "service-changed" -> {
                ServiceKey to = string(event, "to") == null ? null : ServiceKey.parse(string(event, "to"));
                Service service = to == null ? null : network.service(to).orElse(null);
                int index = -1;
                JourneyPhase phase = JourneyPhase.IDLE;
                PlatformRef platform = null;
                if (before != null && before.phase() == JourneyPhase.AT_STOP && before.platform() != null && service != null) {
                    index = JourneyTracker.locate(service, before.platform().stationId(), -1);
                    phase = index >= 0 ? JourneyPhase.AT_STOP : JourneyPhase.IDLE;
                    platform = index >= 0 ? before.platform() : null;
                }
                journeys.put(train, new JourneySnapshot(train, to, phase, index, platform, platform, -1,
                        before == null ? null : before.arrivedAt(), null, null, now));
                return null;
            }
            case "trip-completed" -> {
                if (before != null) {
                    journeys.put(train, new JourneySnapshot(train, before.service(), JourneyPhase.COMPLETED, before.lastIndex(),
                            before.platform(), before.lastPlatform(), -1, before.arrivedAt(), before.departedAt(), null, now));
                }
                return null;
            }
            default -> {
                return null;
            }
        }
    }

    private static @Nullable String string(JsonObject object, String name) {
        JsonElement element = object.get(name);
        return element == null || element.isJsonNull() ? null : element.getAsString();
    }

    private static @Nullable Duration duration(@Nullable JsonElement element) {
        return element == null || element.isJsonNull() ? null : Duration.ofMillis(Math.round(element.getAsDouble() * 1000));
    }

    /** A one-line summary of the scores at the horizons shown by the report. */
    public static String summary(EtaSamples samples) {
        List<String> parts = new ArrayList<>();
        for (int horizon : new int[] {120, 60, 30}) {
            EtaSamples.Stat total = EtaSamples.Stat.NONE;
            for (ServiceKey service : samples.services()) {
                EtaSamples.Stat stat = samples.overall(service, horizon);
                total = new EtaSamples.Stat(total.count() + stat.count(), total.sumAbs() + stat.sumAbs(),
                        total.sumSigned() + stat.sumSigned(), Math.max(total.maxAbs(), stat.maxAbs()));
            }
            parts.add(String.format(Locale.ROOT, "%ds: mae %.1f bias %+.1f max %.0f n=%d", horizon, total.meanAbs(),
                    total.bias(), total.maxAbs(), total.count()));
        }
        return String.join(" | ", parts);
    }

    /** Mean absolute error over every service at the horizon. */
    public static double meanAbs(EtaSamples samples, int horizon) {
        EtaSamples.Stat total = EtaSamples.Stat.NONE;
        for (ServiceKey service : samples.services()) {
            EtaSamples.Stat stat = samples.overall(service, horizon);
            total = new EtaSamples.Stat(total.count() + stat.count(), total.sumAbs() + stat.sumAbs(),
                    total.sumSigned() + stat.sumSigned(), Math.max(total.maxAbs(), stat.maxAbs()));
        }
        return total.meanAbs();
    }
}
