package dev.advancedtrains.atcs.runtime;

import dev.advancedtrains.atcs.network.PlatformRef;
import dev.advancedtrains.atcs.operation.JourneyEvent;
import org.jspecify.annotations.Nullable;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Locale;
import java.util.concurrent.Executor;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Appends every arrival, departure, hold and (optionally) train position to {@code data/events.log}
 * as one JSON object per line, for debugging after the fact and for replaying through the
 * estimator. The file is rotated to {@code events.1.log} when it grows past the limit, so it is
 * bounded. Writes happen off the server thread, in order, through one writer that is kept open
 * and flushed about once a second (opening the file for every line is far too slow on some
 * systems).
 */
public final class EventLog {
    private static final long FLUSH_MILLIS = 1000;

    private final Path file;
    private final long maxBytes;
    private final Executor executor;
    private final Logger logger;
    private volatile boolean enabled;
    private volatile boolean positions;
    /** Used on the executor's thread only. */
    private @Nullable BufferedWriter writer;
    private long written = -1;
    private long lastFlush;

    public EventLog(Path file, long maxBytes, Executor executor, Logger logger, boolean enabled, boolean positions) {
        this.file = file;
        this.maxBytes = maxBytes;
        this.executor = executor;
        this.logger = logger;
        this.enabled = enabled;
        this.positions = positions;
    }

    public void configure(boolean newEnabled, boolean newPositions) {
        enabled = newEnabled;
        positions = newPositions;
    }

    public boolean enabled() {
        return enabled;
    }

    public boolean positions() {
        return enabled && positions;
    }

    public void journey(JourneyEvent event) {
        if (!enabled) {
            return;
        }
        StringBuilder line = start(event.at(), kind(event)).append(",\"train\":").append(quote(event.trainId()));
        switch (event) {
            case JourneyEvent.Arrived arrived -> line.append(",\"service\":").append(quote(arrived.service().toString()))
                    .append(",\"stop\":").append(arrived.stopIndex()).append(platform(arrived.platform()))
                    .append(",\"from\":").append(arrived.fromIndex())
                    .append(",\"travel\":").append(arrived.travelTime() == null ? "null" : seconds(arrived.travelTime().toMillis()))
                    .append(",\"terminus\":").append(arrived.terminus());
            case JourneyEvent.Departed departed -> line.append(",\"service\":").append(quote(departed.service().toString()))
                    .append(",\"stop\":").append(departed.stopIndex()).append(platform(departed.platform()))
                    .append(",\"next\":").append(departed.nextIndex())
                    .append(",\"dwell\":").append(departed.dwell() == null ? "null" : seconds(departed.dwell().toMillis()));
            case JourneyEvent.Passed passed -> line.append(",\"service\":").append(quote(passed.service().toString()))
                    .append(",\"stop\":").append(passed.stopIndex()).append(platform(passed.platform()))
                    .append(",\"from\":").append(passed.fromIndex())
                    .append(",\"travel\":").append(passed.travelTime() == null ? "null" : seconds(passed.travelTime().toMillis()));
            case JourneyEvent.Approaching approaching -> line.append(",\"service\":").append(quote(approaching.service().toString()))
                    .append(",\"stop\":").append(approaching.stopIndex());
            case JourneyEvent.UnassignedArrived arrived -> line.append(platform(arrived.platform()));
            case JourneyEvent.UnassignedDeparted departed -> line.append(platform(departed.platform()));
            case JourneyEvent.ServiceChanged changed -> line.append(",\"from\":")
                    .append(changed.from() == null ? "null" : quote(changed.from().toString()))
                    .append(",\"to\":").append(changed.to() == null ? "null" : quote(changed.to().toString()));
            case JourneyEvent.TripCompleted completed -> line.append(",\"service\":").append(quote(completed.service().toString()));
        }
        append(line.append('}').toString());
    }

    /** A train's head position and speed (blocks per tick), logged while {@code positions} is on. */
    public void position(String trainId, @Nullable String service, String world, double x, double y, double z,
                         double speed, Instant at) {
        if (!positions()) {
            return;
        }
        append(start(at, "pos").append(",\"train\":").append(quote(trainId))
                .append(",\"service\":").append(service == null ? "null" : quote(service))
                .append(",\"world\":").append(quote(world))
                .append(",\"x\":").append(String.format(Locale.ROOT, "%.1f", x))
                .append(",\"y\":").append(String.format(Locale.ROOT, "%.1f", y))
                .append(",\"z\":").append(String.format(Locale.ROOT, "%.1f", z))
                .append(",\"speed\":").append(String.format(Locale.ROOT, "%.3f", speed)).append('}').toString());
    }

    /** ATCS started or stopped holding a train at a platform. */
    public void hold(String trainId, PlatformRef platform, @Nullable String reason, Instant at) {
        if (!enabled) {
            return;
        }
        append(start(at, reason == null ? "release" : "hold").append(",\"train\":").append(quote(trainId))
                .append(platform(platform))
                .append(reason == null ? "" : ",\"reason\":" + quote(reason)).append('}').toString());
    }

    /** A train took, waited for, was let into or left a virtual block. */
    public void block(String trainId, String blockId, String what, @Nullable String detail, Instant at) {
        if (!enabled) {
            return;
        }
        append(start(at, "block-" + what).append(",\"train\":").append(quote(trainId))
                .append(",\"block\":").append(quote(blockId))
                .append(detail == null ? "" : ",\"detail\":" + quote(detail)).append('}').toString());
    }

    /** Flushes and closes the file; queued lines written first. Call before the executor shuts down. */
    public void close() {
        executor.execute(() -> {
            try {
                if (writer != null) {
                    writer.close();
                }
            } catch (IOException exception) {
                logger.log(Level.WARNING, "Could not close the event log", exception);
            } finally {
                writer = null;
            }
        });
    }

    private static StringBuilder start(Instant at, String kind) {
        return new StringBuilder(160).append("{\"t\":\"").append(at).append("\",\"e\":\"").append(kind).append('"');
    }

    private static String kind(JourneyEvent event) {
        return switch (event) {
            case JourneyEvent.Arrived ignored -> "arrived";
            case JourneyEvent.Departed ignored -> "departed";
            case JourneyEvent.Passed ignored -> "passed";
            case JourneyEvent.Approaching ignored -> "approaching";
            case JourneyEvent.UnassignedArrived ignored -> "unassigned-arrived";
            case JourneyEvent.UnassignedDeparted ignored -> "unassigned-departed";
            case JourneyEvent.ServiceChanged ignored -> "service-changed";
            case JourneyEvent.TripCompleted ignored -> "trip-completed";
        };
    }

    private static String platform(PlatformRef platform) {
        return ",\"station\":" + quote(platform.stationId()) + ",\"platform\":" + quote(platform.platformId());
    }

    private static String seconds(long millis) {
        return String.format(Locale.ROOT, "%.2f", millis / 1000.0);
    }

    static String quote(String text) {
        StringBuilder out = new StringBuilder(text.length() + 2).append('"');
        for (int index = 0; index < text.length(); index++) {
            char c = text.charAt(index);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.append('"').toString();
    }

    private void append(String line) {
        executor.execute(() -> write(line));
    }

    private void write(String line) {
        try {
            if (written < 0) {
                written = Files.exists(file) ? Files.size(file) : 0;
            }
            if (written > maxBytes) {
                if (writer != null) {
                    writer.close();
                    writer = null;
                }
                Path previous = file.resolveSibling(file.getFileName().toString().replace(".log", ".1.log"));
                Files.move(file, previous, StandardCopyOption.REPLACE_EXISTING);
                written = 0;
            }
            if (writer == null) {
                Files.createDirectories(file.toAbsolutePath().getParent());
                writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                lastFlush = System.currentTimeMillis();
            }
            writer.write(line);
            writer.write('\n');
            written += line.length() + 1;
            long now = System.currentTimeMillis();
            if (now - lastFlush >= FLUSH_MILLIS) {
                writer.flush();
                lastFlush = now;
            }
        } catch (IOException exception) {
            logger.log(Level.WARNING, "Could not write the event log; it is switched off until reload", exception);
            enabled = false;
            writer = null;
        }
    }
}
