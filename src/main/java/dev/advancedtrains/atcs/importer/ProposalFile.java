package dev.advancedtrains.atcs.importer;

import dev.advancedtrains.atcs.network.Line;
import dev.advancedtrains.atcs.network.NetworkCodec;
import dev.advancedtrains.atcs.network.Service;
import dev.advancedtrains.atcs.network.ServiceKey;
import dev.advancedtrains.atcs.network.Station;
import dev.advancedtrains.atcs.operation.RouteSuggester;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Writes and reads {@code import-proposal.yml}: drafted stations and suggested lines in the same
 * format as network.yml, for an operator to review before applying.
 */
public final class ProposalFile {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'")
            .withZone(ZoneOffset.UTC);

    /** The reviewed content of a proposal file. */
    public record Contents(List<Station> stations, List<Line> lines) {
        public Contents {
            stations = List.copyOf(stations);
            lines = List.copyOf(lines);
        }
    }

    private ProposalFile() {
    }

    public static String write(StationImporter.@Nullable Proposal stations,
                               RouteSuggester.@Nullable Suggestion services,
                               Instant generatedAt) {
        YamlConfiguration yaml = new YamlConfiguration();
        List<String> header = new ArrayList<>();
        header.add("ATCS import proposal, generated " + TIME.format(generatedAt) + ".");
        header.add("");
        header.add("Review it before applying: rename stations, lines and services, change ids and colours, merge");
        header.add("platforms by moving triggers into one platform, and delete anything you do not want.");
        header.add("Then run /atcs import apply. Existing stations, lines and services are never overwritten.");
        if (stations != null) {
            header.add("");
            header.add("Stations: drafted from " + stations.signsScanned() + " signs (" + stations.stopSigns()
                    + " TrainCarts station signs).");
            stations.notes().forEach(note -> header.add("  " + note));
        }
        if (services != null) {
            header.add("");
            header.add("Lines: suggested from " + services.tripsAnalysed() + " observed train trips"
                    + (services.alreadyDefined() > 0 ? "; " + services.alreadyDefined() + " route(s) are already defined" : "")
                    + ". Each service lists the spawn signs whose trains run it, so new trains are assigned automatically.");
        }
        yaml.options().setHeader(header);

        if (stations != null && !stations.stations().isEmpty()) {
            ConfigurationSection section = yaml.createSection("stations");
            new NetworkCodec().writeStations(section,
                    stations.stations().stream().map(StationImporter.ProposedStation::toStation).toList());
            for (StationImporter.ProposedStation station : stations.stations()) {
                if (station.existing()) {
                    yaml.setComments("stations." + station.id(), List.of("Adds platforms to the existing station."));
                }
                for (StationImporter.ProposedPlatform platform : station.platforms()) {
                    if (!platform.hints().isEmpty()) {
                        yaml.setComments("stations." + station.id() + ".platforms." + platform.id(),
                                List.of("Nearby: " + String.join(" | ", platform.hints())));
                    }
                }
            }
        }
        if (services != null && !services.lines().isEmpty()) {
            ConfigurationSection section = yaml.createSection("lines");
            new NetworkCodec().writeLines(section, services.lines());
            for (Line line : services.lines()) {
                for (Service service : line.services().values()) {
                    ServiceKey key = service.key();
                    yaml.setComments("lines." + line.id() + ".services." + key.serviceId(),
                            List.of("Seen " + services.observations().getOrDefault(key, 0) + " time(s)"));
                }
            }
        }
        return yaml.saveToString();
    }

    /**
     * @throws IllegalArgumentException when the file is not valid YAML or contains invalid entries
     */
    public static Contents read(String content) {
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.loadFromString(content);
        } catch (InvalidConfigurationException exception) {
            throw new IllegalArgumentException("import-proposal.yml is not valid YAML: " + exception.getMessage(), exception);
        }
        List<String> errors = new ArrayList<>();
        NetworkCodec codec = new NetworkCodec();
        List<Station> stations = codec.readStations(yaml.getConfigurationSection("stations"), "stations", errors);
        List<Line> lines = codec.readLines(yaml.getConfigurationSection("lines"), errors);
        if (!errors.isEmpty()) {
            throw new IllegalArgumentException("import-proposal.yml: " + String.join("; ", errors));
        }
        return new Contents(stations, lines);
    }
}
