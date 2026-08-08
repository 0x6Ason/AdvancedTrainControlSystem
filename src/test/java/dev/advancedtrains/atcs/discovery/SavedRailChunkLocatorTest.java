package dev.advancedtrains.atcs.discovery;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.DeflaterOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SavedRailChunkLocatorTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void locatesOnlySavedChunksWhosePaletteContainsRail() throws IOException {
        Path world = temporaryDirectory.resolve("world");
        Path region = world.resolve("region");
        Files.createDirectories(region);
        writeRegion(region.resolve("r.-1.2.mca"), List.of(
                new TestChunk(1, "minecraft:stone".getBytes(java.nio.charset.StandardCharsets.US_ASCII)),
                new TestChunk(35, "minecraft:powered_rail".getBytes(java.nio.charset.StandardCharsets.US_ASCII))));

        List<SavedRailChunkLocator.LocatedChunk> result =
                new SavedRailChunkLocator().locate(world);

        assertEquals(1, result.size());
        assertEquals(new SavedRailChunkLocator.ChunkCoordinate(-29, 65), result.getFirst().coordinate());
        assertTrue(result.getFirst().loadWithBukkit());
        assertTrue(result.getFirst().directRails().isEmpty());
    }

    @Test
    void extractsRailsFromIncompleteChunksWithoutLoadingThemThroughBukkit() throws IOException {
        Path world = temporaryDirectory.resolve("world");
        Path region = world.resolve("region");
        Files.createDirectories(region);
        writeRegion(region.resolve("r.0.0.mca"), List.of(
                new TestChunk(0, incompleteRailChunk())));

        List<SavedRailChunkLocator.LocatedChunk> result =
                new SavedRailChunkLocator().locate(world);

        assertEquals(1, result.size());
        SavedRailChunkLocator.LocatedChunk chunk = result.getFirst();
        assertFalse(chunk.loadWithBukkit());
        assertEquals(4096, chunk.directBlockChecks());
        assertEquals(List.of(new SavedRailChunkLocator.SavedRailBlock(
                1,
                64,
                0,
                "minecraft:rail",
                org.bukkit.block.data.Rail.Shape.EAST_WEST)), chunk.directRails());
    }

    private void writeRegion(Path target, List<TestChunk> chunks) throws IOException {
        ByteBuffer file = ByteBuffer.allocate((2 + chunks.size()) * 4096);
        int sector = 2;
        for (TestChunk chunk : chunks) {
            byte[] compressed = compress(chunk.content());
            file.putInt(chunk.localIndex() * 4, (sector << 8) | 1);
            int offset = sector * 4096;
            file.putInt(offset, compressed.length + 1);
            file.put(offset + 4, (byte) 2);
            file.position(offset + 5);
            file.put(compressed);
            sector++;
        }
        Files.write(target, file.array());
    }

    private byte[] incompleteRailChunk() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            output.writeByte(10);
            output.writeUTF("");

            writeString(output, "Status", "minecraft:initialize_light");
            output.writeByte(9);
            output.writeUTF("sections");
            output.writeByte(10);
            output.writeInt(1);

            output.writeByte(1);
            output.writeUTF("Y");
            output.writeByte(4);
            output.writeByte(10);
            output.writeUTF("block_states");

            output.writeByte(9);
            output.writeUTF("palette");
            output.writeByte(10);
            output.writeInt(2);
            writeString(output, "Name", "minecraft:air");
            output.writeByte(0);
            writeString(output, "Name", "minecraft:rail");
            output.writeByte(10);
            output.writeUTF("Properties");
            writeString(output, "shape", "east_west");
            output.writeByte(0);
            output.writeByte(0);

            output.writeByte(12);
            output.writeUTF("data");
            output.writeInt(256);
            output.writeLong(1L << 4);
            for (int index = 1; index < 256; index++) {
                output.writeLong(0);
            }
            output.writeByte(0);
            output.writeByte(0);
            output.writeByte(0);
        }
        return bytes.toByteArray();
    }

    private void writeString(DataOutputStream output, String name, String value) throws IOException {
        output.writeByte(8);
        output.writeUTF(name);
        output.writeUTF(value);
    }

    private byte[] compress(byte[] value) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (DeflaterOutputStream compressed = new DeflaterOutputStream(output)) {
            compressed.write(value);
        }
        return output.toByteArray();
    }

    private record TestChunk(int localIndex, byte[] content) {
    }
}
