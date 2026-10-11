package dev.advancedtrains.atcs.network;

import java.util.List;

/**
 * A departure board made of one or more ordinary signs. Sign {@code n} shows the {@code n}-th
 * next departure.
 *
 * @param id        identifier
 * @param stationId the station whose departures are shown
 * @param platforms platform ids to include; empty for all platforms
 * @param signs     sign positions in display order
 */
public record Board(String id, String stationId, List<String> platforms, List<BlockPos> signs) {

    public Board {
        id = Ids.normalize("board", id);
        stationId = Ids.normalize("station", stationId);
        platforms = platforms.stream().map(platform -> Ids.normalize("platform", platform)).toList();
        signs = List.copyOf(signs);
    }

    public boolean includesPlatform(String platformId) {
        return platforms.isEmpty() || platforms.contains(platformId);
    }

    public Board withSign(BlockPos sign) {
        if (signs.contains(sign)) {
            return this;
        }
        List<BlockPos> updated = new java.util.ArrayList<>(signs);
        updated.add(sign);
        return new Board(id, stationId, platforms, updated);
    }

    public Board withoutSign(BlockPos sign) {
        List<BlockPos> updated = new java.util.ArrayList<>(signs);
        updated.remove(sign);
        return new Board(id, stationId, platforms, updated);
    }
}
