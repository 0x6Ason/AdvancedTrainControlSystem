package dev.advancedtrains.atcs.config;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.ParsingException;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.configuration.ConfigurationSection;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Passenger-facing texts from {@code messages.yml}, rendered with MiniMessage. Keys missing from the
 * server's file fall back to the bundled defaults.
 */
public final class Messages {
    private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();

    private final Map<String, String> templates;
    private final Map<String, List<String>> layouts;
    private final Logger logger;

    private Messages(Map<String, String> templates, Map<String, List<String>> layouts, Logger logger) {
        this.templates = templates;
        this.layouts = layouts;
        this.logger = logger;
    }

    /** Reads all messages; {@code config} should have the bundled file set as its defaults. */
    public static Messages read(ConfigurationSection config, Logger logger) {
        Map<String, String> templates = new HashMap<>();
        Map<String, List<String>> layouts = new HashMap<>();
        for (String key : config.getKeys(true)) {
            if (config.isList(key)) {
                layouts.put(key, config.getStringList(key));
            } else if (config.isString(key)) {
                // getString(key) consults the bundled defaults; getString(key, "") would not.
                String template = config.getString(key);
                templates.put(key, template == null ? "" : template);
            }
        }
        return new Messages(templates, layouts, logger);
    }

    public boolean has(String key) {
        return !templates.getOrDefault(key, "").isEmpty();
    }

    public Component render(String key, TagResolver... resolvers) {
        return parse(key, templates.getOrDefault(key, ""), resolvers);
    }

    /** Renders a multi-line layout such as a board sign. */
    public List<Component> renderLines(String key, TagResolver... resolvers) {
        return layouts.getOrDefault(key, List.of()).stream()
                .map(line -> parse(key, line, resolvers))
                .toList();
    }

    /** Formats a duration in seconds with the {@code time.*} templates. */
    public Component duration(double totalSeconds) {
        long seconds = Math.max(0, Math.round(totalSeconds));
        if (seconds < 60) {
            return render("time.seconds", Placeholder.unparsed("seconds", Long.toString(seconds)));
        }
        return render("time.minutes",
                Placeholder.unparsed("minutes", Long.toString(seconds / 60)),
                Placeholder.unparsed("seconds", Long.toString(seconds % 60)),
                Placeholder.unparsed("seconds_padded", String.format("%02d", seconds % 60)));
    }

    private Component parse(String key, String template, TagResolver... resolvers) {
        if (template.isEmpty()) {
            return Component.empty();
        }
        try {
            return MINI_MESSAGE.deserialize(template, resolvers);
        } catch (ParsingException exception) {
            logger.warning("messages.yml '" + key + "' is not valid MiniMessage: " + exception.getMessage());
            return Component.text(template);
        }
    }
}
