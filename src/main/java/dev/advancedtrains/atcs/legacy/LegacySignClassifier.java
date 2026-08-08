package dev.advancedtrains.atcs.legacy;

import dev.advancedtrains.atcs.domain.BlockPosition;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;

public final class LegacySignClassifier {
    public static final int PARSER_VERSION = 1;

    public Optional<LegacyTrainCartsSign> classify(
            BlockPosition position,
            String side,
            List<String> inputLines) {
        if (inputLines.size() != 4) {
            throw new IllegalArgumentException("A Minecraft sign must have exactly four lines");
        }
        List<String> rawLines = List.copyOf(inputLines);
        List<String> lines = rawLines.stream().map(LegacySignText::clean).toList();
        Optional<LegacySignHeader> parsedHeader = LegacySignHeader.parse(lines.get(0));
        if (parsedHeader.isEmpty()) {
            return Optional.empty();
        }

        LegacySignHeader header = parsedHeader.orElseThrow();
        String action = firstToken(lines.get(1));
        if (action.isEmpty()) {
            return Optional.empty();
        }
        LegacySignType type = LegacySignType.fromAction(action);
        Map<String, String> attributes = new LinkedHashMap<>();
        attributes.put("action", action);
        attributes.put("header_mode", header.mode().name());
        attributes.put("always_powered", Boolean.toString(header.alwaysPowered()));
        attributes.put("inverted", Boolean.toString(header.inverted()));
        if (!header.options().isEmpty()) {
            attributes.put("header_options", header.options());
        }

        switch (type) {
            case STATION -> parseStation(lines, attributes);
            case SPAWN -> parseSpawn(lines, attributes);
            case ANNOUNCE -> attributes.put("announcement_text", joinNonBlank(lines.get(2), lines.get(3)));
            case PROPERTY -> {
                putIfNotBlank(attributes, "property_name", lines.get(2));
                putIfNotBlank(attributes, "property_value", lines.get(3));
            }
            default -> {
                // Raw lines remain authoritative evidence for sign types not yet interpreted.
            }
        }

        String identity = String.join(":",
                position.worldName(),
                Integer.toString(position.x()),
                Integer.toString(position.y()),
                Integer.toString(position.z()),
                side.toUpperCase(Locale.ROOT));
        UUID id = UUID.nameUUIDFromBytes(identity.getBytes(StandardCharsets.UTF_8));
        return Optional.of(new LegacyTrainCartsSign(
                id,
                position,
                Math.floorDiv(position.x(), 16),
                Math.floorDiv(position.z(), 16),
                side.toUpperCase(Locale.ROOT),
                header,
                type,
                new ArrayList<>(rawLines),
                attributes,
                PARSER_VERSION));
    }

    private void parseStation(List<String> lines, Map<String, String> attributes) {
        List<String> arguments = argumentsAfterAction(lines.get(1));
        for (String argument : arguments) {
            if (argument.toLowerCase(Locale.ROOT).endsWith("m")) {
                putIfNotBlank(attributes, "center_offset", argument);
            } else if (!attributes.containsKey("launch_distance")) {
                putIfNotBlank(attributes, "launch_distance", argument);
            }
        }
        putIfNotBlank(attributes, "delay", lines.get(2));
        putIfNotBlank(attributes, "instruction", lines.get(3));
    }

    private void parseSpawn(List<String> lines, Map<String, String> attributes) {
        List<String> arguments = argumentsAfterAction(lines.get(1));
        if (arguments.size() >= 2) {
            if (arguments.get(0).contains(":")) {
                attributes.put("interval", arguments.get(0));
                attributes.put("launch_force", arguments.get(1));
            } else {
                attributes.put("launch_force", arguments.get(0));
                if (arguments.get(1).contains(":")) {
                    attributes.put("interval", arguments.get(1));
                }
            }
        } else if (arguments.size() == 1) {
            if (arguments.get(0).contains(":")) {
                attributes.put("interval", arguments.get(0));
            } else {
                attributes.put("launch_force", arguments.get(0));
            }
        }

        String spawnPattern = lines.get(2) + lines.get(3);
        putIfNotBlank(attributes, "spawn_pattern", spawnPattern);
        OptionalInt regularMinecarts = VanillaSpawnPatternAnalyzer.regularMinecartCount(spawnPattern);
        regularMinecarts.ifPresent(count -> attributes.put("regular_minecart_count", Integer.toString(count)));
    }

    private static List<String> argumentsAfterAction(String actionLine) {
        String stripped = actionLine.strip();
        int firstSpace = stripped.indexOf(' ');
        if (firstSpace < 0) {
            return List.of();
        }
        String arguments = stripped.substring(firstSpace + 1).strip();
        return arguments.isEmpty() ? List.of() : List.of(arguments.split("\\s+"));
    }

    private static String firstToken(String text) {
        String stripped = text.strip().toLowerCase(Locale.ROOT);
        int space = stripped.indexOf(' ');
        return space < 0 ? stripped : stripped.substring(0, space);
    }

    private static void putIfNotBlank(Map<String, String> attributes, String key, String value) {
        String stripped = value.strip();
        if (!stripped.isEmpty()) {
            attributes.put(key, stripped);
        }
    }

    private static String joinNonBlank(String first, String second) {
        if (first.isBlank()) {
            return second.strip();
        }
        if (second.isBlank()) {
            return first.strip();
        }
        return first.strip() + "\n" + second.strip();
    }
}
