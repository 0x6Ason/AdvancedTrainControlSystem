package dev.advancedtrains.atcs.importer;

import dev.advancedtrains.atcs.network.BlockPos;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Plain text of one side of a sign found in the world.
 *
 * @param position the sign block
 * @param front    whether this is the front side
 * @param lines    exactly four lines of plain text
 */
public record ScannedSign(BlockPos position, boolean front, List<String> lines) {

    public ScannedSign {
        Objects.requireNonNull(position, "position");
        List<String> padded = new ArrayList<>(4);
        for (int index = 0; index < 4; index++) {
            padded.add(index < lines.size() && lines.get(index) != null ? lines.get(index) : "");
        }
        lines = List.copyOf(padded);
    }

    public String line(int index) {
        return lines.get(index);
    }

    public boolean blank() {
        return lines.stream().allMatch(String::isBlank);
    }
}
