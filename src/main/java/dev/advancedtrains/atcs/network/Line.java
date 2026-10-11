package dev.advancedtrains.atcs.network;

import org.jspecify.annotations.Nullable;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * A named, coloured line grouping one or more services.
 *
 * @param id         identifier, for example {@code a}
 * @param name       full name, for example {@code "Line A Upperground"}
 * @param shortName  compact badge text for boss bars and boards, for example {@code "A"}
 * @param color      RGB colour, for example {@code 0xE53935}
 * @param services   services keyed by service id, in display order
 * @param suspension passenger notice while the line is suspended, or {@code null} when running
 */
public record Line(
        String id,
        String name,
        String shortName,
        int color,
        Map<String, Service> services,
        @Nullable String suspension) {

    public Line {
        id = Ids.normalize("line", id);
        name = Objects.requireNonNull(name, "name").strip();
        if (name.isEmpty()) {
            throw new IllegalArgumentException("Line '" + id + "' needs a name");
        }
        shortName = Objects.requireNonNullElse(shortName, "").strip();
        if (shortName.isEmpty()) {
            shortName = id.toUpperCase(java.util.Locale.ROOT);
        }
        color &= 0xFFFFFF;
        for (Map.Entry<String, Service> entry : services.entrySet()) {
            ServiceKey key = entry.getValue().key();
            if (!key.lineId().equals(id) || !key.serviceId().equals(entry.getKey())) {
                throw new IllegalArgumentException("Service " + key + " is filed under line " + id
                        + " as '" + entry.getKey() + "'");
            }
        }
        services = Collections.unmodifiableMap(new LinkedHashMap<>(services));
        if (suspension != null) {
            suspension = suspension.strip();
        }
    }

    public static Line of(String id, String name, int color) {
        return new Line(id, name, "", color, Map.of(), null);
    }

    public Optional<Service> service(String serviceId) {
        return Optional.ofNullable(services.get(serviceId));
    }

    public boolean suspended() {
        return suspension != null;
    }

    public String colorHex() {
        return String.format("#%06X", color);
    }

    public Line withName(String newName) {
        return new Line(id, newName, shortName, color, services, suspension);
    }

    public Line withShortName(String newShortName) {
        return new Line(id, name, newShortName, color, services, suspension);
    }

    public Line withColor(int newColor) {
        return new Line(id, name, shortName, newColor, services, suspension);
    }

    public Line withSuspension(@Nullable String notice) {
        return new Line(id, name, shortName, color, services, notice);
    }

    public Line withService(Service service) {
        Map<String, Service> updated = new LinkedHashMap<>(services);
        updated.put(service.key().serviceId(), service);
        return new Line(id, name, shortName, color, updated, suspension);
    }

    public Line withoutService(String serviceId) {
        Map<String, Service> updated = new LinkedHashMap<>(services);
        updated.remove(serviceId);
        return new Line(id, name, shortName, color, updated, suspension);
    }
}
