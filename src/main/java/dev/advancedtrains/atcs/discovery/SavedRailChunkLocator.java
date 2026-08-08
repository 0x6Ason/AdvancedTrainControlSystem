package dev.advancedtrains.atcs.discovery;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;

import org.bukkit.block.data.Rail;

/**
 * Locates saved Anvil chunks whose block-state palette contains a rail material.
 * Complete chunks are handed to Bukkit for authoritative block snapshots. Incomplete
 * protochunks are decoded directly so discovery never completes or generates terrain.
 */
public final class SavedRailChunkLocator {
    private static final int SECTOR_BYTES = 4096;
    private static final int LOCATION_HEADER_BYTES = 4096;
    private static final int LOCATION_COUNT = 1024;
    private static final int EXTERNAL_STREAM_FLAG = 0x80;
    private static final Pattern REGION_NAME = Pattern.compile("r\\.(-?\\d+)\\.(-?\\d+)\\.mca");
    private static final byte[][] RAIL_MATERIALS = {
            "minecraft:rail".getBytes(StandardCharsets.US_ASCII),
            "minecraft:powered_rail".getBytes(StandardCharsets.US_ASCII),
            "minecraft:detector_rail".getBytes(StandardCharsets.US_ASCII),
            "minecraft:activator_rail".getBytes(StandardCharsets.US_ASCII)
    };
    private static final byte[] STATUS_TAG = {8, 0, 6, 'S', 't', 'a', 't', 'u', 's'};
    private final AnvilRailExtractor railExtractor = new AnvilRailExtractor();

    public List<LocatedChunk> locate(Path worldDirectory) throws IOException {
        Path regionDirectory = worldDirectory.resolve("region");
        if (!Files.isDirectory(regionDirectory)) {
            return List.of();
        }
        List<Path> regionFiles;
        try (Stream<Path> paths = Files.list(regionDirectory)) {
            regionFiles = paths
                    .filter(Files::isRegularFile)
                    .filter(path -> REGION_NAME.matcher(path.getFileName().toString()).matches())
                    .sorted()
                    .toList();
        }
        List<LocatedChunk> coordinates = new ArrayList<>();
        for (Path regionFile : regionFiles) {
            locateInRegion(regionFile, coordinates);
        }
        coordinates.sort(Comparator
                .comparingInt((LocatedChunk chunk) -> chunk.coordinate().x())
                .thenComparingInt(chunk -> chunk.coordinate().z()));
        return List.copyOf(coordinates);
    }

    private void locateInRegion(Path regionFile, List<LocatedChunk> output) throws IOException {
        Matcher matcher = REGION_NAME.matcher(regionFile.getFileName().toString());
        if (!matcher.matches()) {
            return;
        }
        int regionX = Integer.parseInt(matcher.group(1));
        int regionZ = Integer.parseInt(matcher.group(2));
        try (FileChannel channel = FileChannel.open(regionFile, StandardOpenOption.READ)) {
            ByteBuffer header = ByteBuffer.allocate(LOCATION_HEADER_BYTES);
            if (!readFully(channel, header, 0)) {
                return;
            }
            header.flip();
            for (int index = 0; index < LOCATION_COUNT; index++) {
                int location = header.getInt();
                int sectorOffset = location >>> 8;
                if (sectorOffset == 0) {
                    continue;
                }
                int chunkX = regionX * 32 + index % 32;
                int chunkZ = regionZ * 32 + index / 32;
                inspectChunk(channel, regionFile.getParent(), chunkX, chunkZ, sectorOffset)
                        .ifPresent(output::add);
            }
        }
    }

    private Optional<LocatedChunk> inspectChunk(
            FileChannel channel,
            Path regionDirectory,
            int chunkX,
            int chunkZ,
            int sectorOffset) throws IOException {
        long position = (long) sectorOffset * SECTOR_BYTES;
        ByteBuffer prefix = ByteBuffer.allocate(5);
        if (!readFully(channel, prefix, position)) {
            return Optional.empty();
        }
        prefix.flip();
        int length = prefix.getInt();
        int compression = Byte.toUnsignedInt(prefix.get());
        if (length <= 1) {
            return Optional.empty();
        }
        boolean external = (compression & EXTERNAL_STREAM_FLAG) != 0;
        int compressionType = compression & ~EXTERNAL_STREAM_FLAG;
        InputStream compressed;
        if (external) {
            Path externalFile = regionDirectory.resolve("c." + chunkX + "." + chunkZ + ".mcc");
            if (!Files.isRegularFile(externalFile)) {
                return Optional.empty();
            }
            compressed = Files.newInputStream(externalFile);
        } else {
            ByteBuffer payload = ByteBuffer.allocate(length - 1);
            if (!readFully(channel, payload, position + 5)) {
                return Optional.empty();
            }
            compressed = new ByteArrayInputStream(payload.array());
        }
        try (InputStream decoded = decodedStream(compressed, compressionType)) {
            byte[] nbt = decoded.readAllBytes();
            if (!containsRailMaterial(nbt)) {
                return Optional.empty();
            }
            ChunkCoordinate coordinate = new ChunkCoordinate(chunkX, chunkZ);
            String status = readStatus(nbt).orElse("");
            if (status.isEmpty() || status.equals("minecraft:full")) {
                return Optional.of(new LocatedChunk(coordinate, true, List.of(), 0));
            }
            AnvilRailExtractor.Result result = railExtractor.extract(nbt, chunkX, chunkZ);
            return Optional.of(new LocatedChunk(
                    coordinate,
                    false,
                    result.rails(),
                    result.blockChecks()));
        }
    }

    private Optional<String> readStatus(byte[] data) {
        for (int offset = 0; offset <= data.length - STATUS_TAG.length - 2; offset++) {
            if (!matchesAt(data, STATUS_TAG, offset)) {
                continue;
            }
            int lengthOffset = offset + STATUS_TAG.length;
            int length = (Byte.toUnsignedInt(data[lengthOffset]) << 8)
                    | Byte.toUnsignedInt(data[lengthOffset + 1]);
            int valueOffset = lengthOffset + 2;
            if (valueOffset + length <= data.length) {
                return Optional.of(new String(data, valueOffset, length, StandardCharsets.UTF_8));
            }
        }
        return Optional.empty();
    }

    private InputStream decodedStream(InputStream source, int compressionType) throws IOException {
        return switch (compressionType) {
            case 1 -> new GZIPInputStream(source);
            case 2 -> new InflaterInputStream(source);
            case 3 -> source;
            default -> {
                source.close();
                throw new IOException("Unsupported Anvil chunk compression type " + compressionType);
            }
        };
    }

    private boolean containsRailMaterial(byte[] data) {
        for (int index = 0; index < data.length; index++) {
            if (data[index] != 'm') {
                continue;
            }
            for (byte[] material : RAIL_MATERIALS) {
                if (matchesAt(data, material, index)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean matchesAt(byte[] data, byte[] value, int offset) {
        if (offset + value.length > data.length) {
            return false;
        }
        for (int index = 0; index < value.length; index++) {
            if (data[offset + index] != value[index]) {
                return false;
            }
        }
        return true;
    }

    private boolean readFully(FileChannel channel, ByteBuffer buffer, long position) throws IOException {
        while (buffer.hasRemaining()) {
            int read = channel.read(buffer, position + buffer.position());
            if (read < 0) {
                return false;
            }
        }
        return true;
    }

    public record ChunkCoordinate(int x, int z) {
    }

    public record LocatedChunk(
            ChunkCoordinate coordinate,
            boolean loadWithBukkit,
            List<SavedRailBlock> directRails,
            long directBlockChecks) {
        public LocatedChunk {
            Objects.requireNonNull(coordinate, "coordinate");
            directRails = List.copyOf(directRails);
            if (loadWithBukkit && !directRails.isEmpty()) {
                throw new IllegalArgumentException("Bukkit-loaded chunks cannot include direct rails");
            }
            if (directBlockChecks < 0) {
                throw new IllegalArgumentException("Direct block checks cannot be negative");
            }
        }
    }

    public record SavedRailBlock(
            int x,
            int y,
            int z,
            String materialName,
            Rail.Shape shape) {
        public SavedRailBlock {
            Objects.requireNonNull(materialName, "materialName");
            Objects.requireNonNull(shape, "shape");
        }
    }
}
