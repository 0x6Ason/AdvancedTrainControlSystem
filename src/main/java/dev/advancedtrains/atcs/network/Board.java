package dev.advancedtrains.atcs.network;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A departure board made of one or more ordinary signs. Sign {@code n} shows the {@code n}-th
 * next departure. A mirror repeats one of those lines on another sign, so the same departure
 * can be read at both ends of a platform or by the entrance.
 *
 * @param id        identifier
 * @param stationId the station whose departures are shown
 * @param platforms platform ids to include; empty for all platforms
 * @param signs     sign positions in display order
 * @param mirrors   further signs, each with the line (1 for the first sign's departure) it repeats
 */
public record Board(String id, String stationId, List<String> platforms, List<BlockPos> signs, Map<BlockPos, Integer> mirrors) {

    public Board {
        id = Ids.normalize("board", id);
        stationId = Ids.normalize("station", stationId);
        platforms = platforms.stream().map(platform -> Ids.normalize("platform", platform)).toList();
        signs = List.copyOf(signs);
        Map<BlockPos, Integer> copy = new LinkedHashMap<>();
        for (Map.Entry<BlockPos, Integer> mirror : mirrors.entrySet()) {
            if (mirror.getValue() < 1) {
                throw new IllegalArgumentException("Board '" + id + "': mirror " + mirror.getKey().format()
                        + " must repeat line 1 or later, not " + mirror.getValue());
            }
            if (!signs.contains(mirror.getKey())) {
                copy.put(mirror.getKey(), mirror.getValue());
            }
        }
        mirrors = java.util.Collections.unmodifiableMap(copy);
    }

    public Board(String id, String stationId, List<String> platforms, List<BlockPos> signs) {
        this(id, stationId, platforms, signs, Map.of());
    }

    public boolean includesPlatform(String platformId) {
        return platforms.isEmpty() || platforms.contains(platformId);
    }

    /** Every sign the board writes: the ordered ones, then the mirrors. */
    public List<BlockPos> allSigns() {
        List<BlockPos> all = new ArrayList<>(signs);
        all.addAll(mirrors.keySet());
        return all;
    }

    /** How many departures the board needs: its signs, and the furthest line a mirror repeats. */
    public int lines() {
        int lines = signs.size();
        for (int line : mirrors.values()) {
            lines = Math.max(lines, line);
        }
        return lines;
    }

    public Board withSign(BlockPos sign) {
        if (signs.contains(sign)) {
            return this;
        }
        List<BlockPos> updated = new ArrayList<>(signs);
        updated.add(sign);
        Map<BlockPos, Integer> remaining = new LinkedHashMap<>(mirrors);
        remaining.remove(sign);
        return new Board(id, stationId, platforms, updated, remaining);
    }

    /** Makes the sign repeat the given line of the board (1 for the first departure). */
    public Board withMirror(BlockPos sign, int line) {
        List<BlockPos> ordered = new ArrayList<>(signs);
        ordered.remove(sign);
        Map<BlockPos, Integer> updated = new LinkedHashMap<>(mirrors);
        updated.put(sign, line);
        return new Board(id, stationId, platforms, ordered, updated);
    }

    public Board withoutSign(BlockPos sign) {
        List<BlockPos> updated = new ArrayList<>(signs);
        updated.remove(sign);
        Map<BlockPos, Integer> remaining = new LinkedHashMap<>(mirrors);
        remaining.remove(sign);
        return new Board(id, stationId, platforms, updated, remaining);
    }
}
