package dev.advancedtrains.atcs.control;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Virtual block signals: a stretch of track between an {@code atcs block} entry sign and its exit
 * sign holds one train at a time. A train reaching the entry sign while another is inside waits
 * there; passing the exit sign frees the block. An occupancy older than {@link #STALE} is dropped,
 * so a train ATCS lost sight of can never close a block for good.
 */
public final class BlockControl {
    /** After this long an occupancy is no longer believed. */
    public static final Duration STALE = Duration.ofMinutes(15);

    /** Who holds a block and since when. */
    public record Occupancy(String trainId, Instant since) {
    }

    private final Map<String, Occupancy> blocks = new LinkedHashMap<>();

    /**
     * Takes the block for the train. Returns the train already inside when there is one (and it
     * is not this train), in which case the block is not taken.
     */
    public Optional<String> tryEnter(String blockId, String trainId, Instant now) {
        Occupancy inside = blocks.get(blockId);
        if (inside != null && !inside.trainId().equals(trainId) && Duration.between(inside.since(), now).compareTo(STALE) < 0) {
            return Optional.of(inside.trainId());
        }
        blocks.put(blockId, new Occupancy(trainId, now));
        return Optional.empty();
    }

    /** Takes the block whatever is inside, for a train that was let go after waiting too long. */
    public void force(String blockId, String trainId, Instant now) {
        blocks.put(blockId, new Occupancy(trainId, now));
    }

    /** Frees the block if the train holds it. Returns whether it did. */
    public boolean exit(String blockId, String trainId) {
        Occupancy inside = blocks.get(blockId);
        if (inside == null || !inside.trainId().equals(trainId)) {
            return false;
        }
        blocks.remove(blockId);
        return true;
    }

    /** The train is gone: every block it held is free. */
    public List<String> left(String trainId) {
        List<String> freed = new ArrayList<>();
        blocks.entrySet().removeIf(entry -> {
            if (entry.getValue().trainId().equals(trainId)) {
                freed.add(entry.getKey());
                return true;
            }
            return false;
        });
        return freed;
    }

    public Optional<Occupancy> occupant(String blockId) {
        return Optional.ofNullable(blocks.get(blockId));
    }

    public boolean occupied(String blockId, Instant now) {
        Occupancy inside = blocks.get(blockId);
        return inside != null && Duration.between(inside.since(), now).compareTo(STALE) < 0;
    }

    /** Every block with a train inside, in the order they were first taken. */
    public Map<String, Occupancy> all() {
        return Collections.unmodifiableMap(blocks);
    }

    public int size() {
        return blocks.size();
    }
}
