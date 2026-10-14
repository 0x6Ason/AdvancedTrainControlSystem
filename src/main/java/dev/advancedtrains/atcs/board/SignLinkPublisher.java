package dev.advancedtrains.atcs.board;

import com.bergerkiller.bukkit.sl.API.Variables;
import dev.advancedtrains.atcs.config.Messages;
import dev.advancedtrains.atcs.network.Network;
import dev.advancedtrains.atcs.operation.Departures;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Publishes upcoming departures as SignLink variables with names short enough to type on a sign,
 * for example {@code %gb.1t%} or {@code %gb.p3.1d%} (see {@link DepartureVariables}). Only loaded
 * when SignLink is installed.
 */
public final class SignLinkPublisher {
    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();
    private final Map<String, String> published = new HashMap<>();

    public void publish(Network network, Messages messages, int perList,
                        Function<String, List<Departures.Departure>> departures) {
        Map<String, String> values = DepartureVariables.values(network, perList, departures,
                seconds -> PLAIN.serialize(messages.duration(seconds)));
        values.forEach(this::set);
        // Variables of stations or platforms that no longer exist are emptied.
        for (Map.Entry<String, String> entry : Map.copyOf(published).entrySet()) {
            if (!values.containsKey(entry.getKey())) {
                set(entry.getKey(), "");
                published.remove(entry.getKey());
            }
        }
    }

    public void clear() {
        for (String name : published.keySet()) {
            Variables.get(name).set("");
        }
        published.clear();
    }

    private void set(String name, String value) {
        String previous = published.put(name, value);
        if (!value.equals(previous)) {
            Variables.get(name).set(value);
        }
    }
}
