package dev.advancedtrains.atcs.importer;

import dev.advancedtrains.atcs.network.BlockPos;
import dev.advancedtrains.atcs.network.Ids;
import dev.advancedtrains.atcs.network.Network;
import dev.advancedtrains.atcs.network.Platform;
import dev.advancedtrains.atcs.network.Station;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Drafts stations and platforms from signs that already exist in the world.
 *
 * <p>Every TrainCarts {@code station} sign becomes a candidate platform. Its station name comes
 * from the nearest arrival announcement (an {@code announce} sign starting with a configured prefix
 * such as {@code "Arrived:"}), or failing that from a nearby ordinary sign whose text ends like a
 * station name ({@code "... St."}). Candidates with the same name close together form one station.
 * Nothing is applied automatically: the result is a proposal for an operator to review.
 */
public final class StationImporter {

    /**
     * @param arrivalPrefixes      announcement prefixes that introduce a station name
     * @param stationNameSuffixes  endings that mark an ordinary sign line as a station name
     * @param idStripSuffixes      endings dropped from a name when deriving its id
     * @param searchRadius         horizontal distance from a stop sign to look for names and hints
     * @param verticalTolerance    vertical distance from a stop sign to look for names and hints
     * @param clusterRadius        stop signs with the same name further apart than this become
     *                             separate stations
     */
    public record Settings(
            List<String> arrivalPrefixes,
            List<String> stationNameSuffixes,
            List<String> idStripSuffixes,
            int searchRadius,
            int verticalTolerance,
            int clusterRadius) {

        public Settings {
            arrivalPrefixes = List.copyOf(arrivalPrefixes);
            stationNameSuffixes = List.copyOf(stationNameSuffixes);
            idStripSuffixes = idStripSuffixes.stream()
                    .sorted(Comparator.comparingInt(String::length).reversed())
                    .toList();
        }

        public static Settings defaults() {
            return new Settings(
                    List.of("Arrived:", "Arrive:", "Arriving:", "Arrival:", "This is", "Now at"),
                    List.of("St.", "Station", "Stn"),
                    List.of("St.", "St", "Station", "Stn"),
                    16, 6, 96);
        }
    }

    public record ProposedPlatform(String id, BlockPos trigger, List<String> hints) {
    }

    public record ProposedStation(String id, String name, boolean existing, List<ProposedPlatform> platforms) {

        public Station toStation() {
            Map<String, Platform> platformMap = new LinkedHashMap<>();
            for (ProposedPlatform platform : platforms) {
                platformMap.put(platform.id(), new Platform(platform.id(), "", List.of(platform.trigger())));
            }
            return new Station(id, name, platformMap, null);
        }
    }

    public record Proposal(List<ProposedStation> stations, List<String> notes, int signsScanned, int stopSigns) {

        public int platformCount() {
            return stations.stream().mapToInt(station -> station.platforms().size()).sum();
        }
    }

    private record NameSource(String name, BlockPos position, int priority) {
    }

    private record Candidate(TrainSign sign, String name) {
    }

    private final Settings settings;

    public StationImporter(Settings settings) {
        this.settings = settings;
    }

    public Proposal propose(Collection<ScannedSign> scanned, Network network) {
        List<TrainSign> trainSigns = new ArrayList<>();
        List<NameSource> names = new ArrayList<>();
        for (ScannedSign sign : scanned) {
            Optional<TrainSign> parsed = TrainSign.parse(sign);
            if (parsed.isPresent()) {
                trainSigns.add(parsed.get());
                if (parsed.get().action().equals("announce")) {
                    arrivalName(parsed.get().message())
                            .ifPresent(name -> names.add(new NameSource(name, sign.position(), 0)));
                }
            } else {
                plainStationName(sign).ifPresent(name -> names.add(new NameSource(name, sign.position(), 1)));
            }
        }

        List<String> notes = new ArrayList<>();
        List<TrainSign> unnamed = new ArrayList<>();
        int stopSigns = 0;
        int alreadyBound = 0;
        Set<BlockPos> seen = new HashSet<>();
        for (TrainSign sign : trainSigns) {
            if (!sign.action().equals("station") || !seen.add(sign.source().position())) {
                continue;
            }
            stopSigns++;
            if (network.platformAt(sign.source().position()).isPresent()) {
                alreadyBound++;
            } else {
                unnamed.add(sign);
            }
        }
        // Arrival announcements are the strongest evidence, ordinary name boards the weakest. Large
        // stations often announce their name at only a few platforms, so after each kind of
        // evidence, stop signs close to a platform named by it join that station.
        List<Candidate> candidates = new ArrayList<>();
        for (int priority = 0; priority <= 1; priority++) {
            int level = priority;
            List<Candidate> named = new ArrayList<>();
            for (java.util.Iterator<TrainSign> iterator = unnamed.iterator(); iterator.hasNext(); ) {
                TrainSign sign = iterator.next();
                BlockPos position = sign.source().position();
                Optional<NameSource> name = names.stream()
                        .filter(source -> source.priority() == level && near(position, source.position()))
                        .min(Comparator.comparingDouble(source -> source.position().distanceSquared(position)));
                if (name.isPresent()) {
                    named.add(new Candidate(sign, name.get().name()));
                    iterator.remove();
                }
            }
            for (java.util.Iterator<TrainSign> iterator = unnamed.iterator(); iterator.hasNext(); ) {
                TrainSign sign = iterator.next();
                BlockPos position = sign.source().position();
                Optional<Candidate> neighbour = named.stream()
                        .filter(candidate -> near(position, candidate.sign().source().position()))
                        .min(Comparator.comparingDouble(candidate ->
                                candidate.sign().source().position().distanceSquared(position)));
                if (neighbour.isPresent()) {
                    candidates.add(new Candidate(sign, neighbour.get().name()));
                    iterator.remove();
                }
            }
            candidates.addAll(named);
        }
        for (TrainSign sign : unnamed) {
            BlockPos position = sign.source().position();
            String detail = TrainSign.joinLines(sign.source().line(2), sign.source().line(3));
            notes.add("Unnamed station sign at " + position.world() + " " + position.coordinates()
                    + (detail.isEmpty() ? "" : " (" + detail + ")")
                    + ": no station name found nearby; bind it with /atcs station bind if it is a platform");
        }
        if (alreadyBound > 0) {
            notes.add(0, alreadyBound + " station sign(s) are already bound to platforms and were skipped");
        }

        Map<String, Station> existingByName = new HashMap<>();
        for (Station station : network.stations().values()) {
            existingByName.putIfAbsent(normalizeName(station.name()), station);
        }
        Set<String> usedIds = new HashSet<>(network.stations().keySet());
        List<ProposedStation> stations = new ArrayList<>();
        for (List<Candidate> group : groupByNameAndDistance(candidates)) {
            String name = mostCommonName(group);
            // Only one cluster may extend an existing station; another with the same name far away
            // becomes a separate proposal for the operator to judge.
            Station existing = existingByName.remove(normalizeName(name));
            String id;
            if (existing != null) {
                id = existing.id();
            } else {
                id = uniqueId(baseId(name), usedIds);
                usedIds.add(id);
            }
            List<ProposedPlatform> platforms = new ArrayList<>();
            int number = existing == null ? 1 : nextPlatformNumber(existing);
            for (Candidate candidate : orderAlongTrack(group)) {
                BlockPos position = candidate.sign().source().position();
                String platformId = Integer.toString(number++);
                platforms.add(new ProposedPlatform(platformId, position, hints(position, trainSigns)));
            }
            stations.add(new ProposedStation(id, existing != null ? existing.name() : name, existing != null, platforms));
        }
        stations.sort(Comparator.comparing(ProposedStation::name, String.CASE_INSENSITIVE_ORDER));
        return new Proposal(stations, notes, scanned.size(), stopSigns);
    }

    /** Extracts the station name from an arrival announcement such as {@code "Arrived: Main St."}. */
    Optional<String> arrivalName(String message) {
        for (String prefix : settings.arrivalPrefixes()) {
            if (message.regionMatches(true, 0, prefix, 0, prefix.length())) {
                String name = cleanName(message.substring(prefix.length()));
                if (!name.isEmpty()) {
                    return Optional.of(name);
                }
            }
        }
        return Optional.empty();
    }

    /**
     * Finds a station name on an ordinary sign: a line ending with a station suffix, joined with the
     * line above when that line continues the name (for example {@code "Overworld" / "Terminal St."}).
     */
    Optional<String> plainStationName(ScannedSign sign) {
        for (int index = 0; index < 4; index++) {
            String line = TrainSign.clean(sign.line(index));
            if (!hasSuffix(line, settings.stationNameSuffixes())) {
                continue;
            }
            String name = line;
            if (index > 0) {
                String above = TrainSign.clean(sign.line(index - 1));
                if (!above.isEmpty() && !looksLikeLineLabel(above) && !hasSuffix(above, settings.stationNameSuffixes())) {
                    name = above + " " + line;
                }
            }
            name = cleanName(name);
            if (!name.isEmpty() && !hasOnlySuffix(name)) {
                return Optional.of(name);
            }
        }
        return Optional.empty();
    }

    private boolean hasOnlySuffix(String name) {
        return settings.stationNameSuffixes().stream().anyMatch(suffix -> suffix.equalsIgnoreCase(name));
    }

    private static boolean looksLikeLineLabel(String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        return lower.startsWith("line ") || lower.equals("line") || lower.startsWith("platform");
    }

    private static boolean hasSuffix(String line, List<String> suffixes) {
        String lower = line.toLowerCase(Locale.ROOT);
        for (String suffix : suffixes) {
            if (lower.endsWith(suffix.toLowerCase(Locale.ROOT))) {
                int start = lower.length() - suffix.length();
                if (start == 0 || !Character.isLetterOrDigit(lower.charAt(start - 1))) {
                    return true;
                }
            }
        }
        return false;
    }

    private static String cleanName(String text) {
        return TrainSign.clean(text).replaceAll("^[\\s:,-]+", "");
    }

    static String normalizeName(String name) {
        return TrainSign.clean(name).toLowerCase(Locale.ROOT).replace(".", "").strip();
    }

    String baseId(String name) {
        String trimmed = name.strip();
        String lower = trimmed.toLowerCase(Locale.ROOT);
        for (String suffix : settings.idStripSuffixes()) {
            String ending = " " + suffix.toLowerCase(Locale.ROOT);
            if (lower.endsWith(ending) && lower.length() > ending.length()) {
                trimmed = trimmed.substring(0, trimmed.length() - ending.length());
                break;
            }
        }
        return Ids.slugify(trimmed);
    }

    private static String uniqueId(String base, Set<String> used) {
        if (!used.contains(base)) {
            return base;
        }
        for (int suffix = 2; ; suffix++) {
            String candidate = base + "-" + suffix;
            if (candidate.length() > Ids.MAX_LENGTH) {
                candidate = base.substring(0, Ids.MAX_LENGTH - String.valueOf(suffix).length() - 1) + "-" + suffix;
            }
            if (!used.contains(candidate)) {
                return candidate;
            }
        }
    }

    private static int nextPlatformNumber(Station station) {
        int max = 0;
        for (String id : station.platforms().keySet()) {
            try {
                max = Math.max(max, Integer.parseInt(id));
            } catch (NumberFormatException ignored) {
                // Named platforms do not affect numbering.
            }
        }
        return max + 1;
    }

    private boolean near(BlockPos a, BlockPos b) {
        return a.world().equals(b.world())
                && Math.abs(a.y() - b.y()) <= settings.verticalTolerance()
                && a.horizontalDistanceSquared(b) <= (double) settings.searchRadius() * settings.searchRadius();
    }

    private List<List<Candidate>> groupByNameAndDistance(List<Candidate> candidates) {
        Map<String, List<Candidate>> byName = new LinkedHashMap<>();
        for (Candidate candidate : candidates) {
            byName.computeIfAbsent(normalizeName(candidate.name()), ignored -> new ArrayList<>()).add(candidate);
        }
        double limit = (double) settings.clusterRadius() * settings.clusterRadius();
        List<List<Candidate>> groups = new ArrayList<>();
        for (List<Candidate> sameName : byName.values()) {
            int[] parent = new int[sameName.size()];
            for (int index = 0; index < parent.length; index++) {
                parent[index] = index;
            }
            for (int i = 0; i < sameName.size(); i++) {
                for (int j = i + 1; j < sameName.size(); j++) {
                    BlockPos a = sameName.get(i).sign().source().position();
                    BlockPos b = sameName.get(j).sign().source().position();
                    if (a.world().equals(b.world()) && a.horizontalDistanceSquared(b) <= limit) {
                        parent[find(parent, i)] = find(parent, j);
                    }
                }
            }
            Map<Integer, List<Candidate>> clusters = new LinkedHashMap<>();
            for (int index = 0; index < sameName.size(); index++) {
                clusters.computeIfAbsent(find(parent, index), ignored -> new ArrayList<>()).add(sameName.get(index));
            }
            groups.addAll(clusters.values());
        }
        return groups;
    }

    private static int find(int[] parent, int index) {
        while (parent[index] != index) {
            parent[index] = parent[parent[index]];
            index = parent[index];
        }
        return index;
    }

    private static String mostCommonName(List<Candidate> group) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Candidate candidate : group) {
            counts.merge(candidate.name(), 1, Integer::sum);
        }
        return counts.entrySet().stream()
                .max(Map.Entry.<String, Integer>comparingByValue()
                        .thenComparing(Map.Entry.comparingByKey(Comparator.comparingInt(String::length))))
                .orElseThrow()
                .getKey();
    }

    /** Orders platforms along the axis on which the station's stop signs are spread the most. */
    private static List<Candidate> orderAlongTrack(List<Candidate> group) {
        int minX = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxZ = Integer.MIN_VALUE;
        for (Candidate candidate : group) {
            BlockPos position = candidate.sign().source().position();
            minX = Math.min(minX, position.x());
            maxX = Math.max(maxX, position.x());
            minZ = Math.min(minZ, position.z());
            maxZ = Math.max(maxZ, position.z());
        }
        boolean alongX = maxX - minX >= maxZ - minZ;
        Comparator<Candidate> order = Comparator.comparingInt(candidate -> {
            BlockPos position = candidate.sign().source().position();
            return alongX ? position.x() : position.z();
        });
        order = order.thenComparingInt(candidate -> {
            BlockPos position = candidate.sign().source().position();
            return alongX ? position.z() : position.x();
        }).thenComparingInt(candidate -> candidate.sign().source().position().y());
        return group.stream().sorted(order).toList();
    }

    /** Text of every announcement and ordinary sign near a position, for example to find line names. */
    public List<String> nearbyTexts(BlockPos position, Collection<ScannedSign> signs) {
        List<String> texts = new ArrayList<>();
        for (ScannedSign sign : signs) {
            if (!near(position, sign.position())) {
                continue;
            }
            Optional<TrainSign> trainSign = TrainSign.parse(sign);
            String text = trainSign.isPresent()
                    ? (trainSign.get().action().equals("announce") ? trainSign.get().message() : "")
                    : TrainSign.joinLines(sign.line(0), sign.line(1), sign.line(2), sign.line(3));
            if (!text.isEmpty()) {
                texts.add(text);
            }
        }
        return texts;
    }

    /** Nearby announcements other than arrival names, to help an operator tell platforms apart. */
    private List<String> hints(BlockPos position, List<TrainSign> trainSigns) {
        return trainSigns.stream()
                .filter(sign -> sign.action().equals("announce"))
                .filter(sign -> near(position, sign.source().position()))
                .filter(sign -> arrivalName(sign.message()).isEmpty())
                .sorted(Comparator.comparingDouble(sign -> sign.source().position().distanceSquared(position)))
                .map(TrainSign::message)
                .filter(message -> !message.isEmpty())
                .distinct()
                .limit(4)
                .toList();
    }
}
