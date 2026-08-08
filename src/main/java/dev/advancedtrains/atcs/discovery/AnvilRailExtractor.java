package dev.advancedtrains.atcs.discovery;

import org.bukkit.block.data.Rail;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Decodes rail block states from a saved chunk without completing chunk generation. */
final class AnvilRailExtractor {
    Result extract(byte[] nbt, int chunkX, int chunkZ) throws IOException {
        Map<String, Object> root;
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(nbt))) {
            int rootType = input.readUnsignedByte();
            if (rootType != 10) {
                throw new IOException("Anvil chunk root is not a compound tag");
            }
            input.readUTF();
            root = readCompound(input);
        }
        Map<String, Object> chunk = compound(root.get("Level")).orElse(root);
        String status = string(chunk.get("Status")).orElse("");
        List<Object> sections = list(chunk.get("sections"))
                .or(() -> list(chunk.get("Sections")))
                .orElse(List.of());
        List<SavedRailChunkLocator.SavedRailBlock> rails = new ArrayList<>();
        long blockChecks = 0;
        for (Object value : sections) {
            Map<String, Object> section = compound(value).orElse(Map.of());
            Number sectionNumber = number(section.get("Y")).orElse(null);
            Map<String, Object> blockStates = compound(section.get("block_states")).orElse(null);
            if (sectionNumber == null || blockStates == null) {
                continue;
            }
            List<Object> paletteValues = list(blockStates.get("palette")).orElse(List.of());
            List<PaletteState> palette = paletteValues.stream()
                    .map(this::paletteState)
                    .toList();
            if (palette.stream().noneMatch(PaletteState::rail)) {
                continue;
            }
            blockChecks += 4096;
            long[] packed = blockStates.get("data") instanceof long[] longs ? longs : new long[0];
            decodeSection(chunkX, chunkZ, sectionNumber.intValue(), palette, packed, rails);
        }
        return new Result(status, List.copyOf(rails), blockChecks);
    }

    private void decodeSection(
            int chunkX,
            int chunkZ,
            int sectionY,
            List<PaletteState> palette,
            long[] packed,
            List<SavedRailChunkLocator.SavedRailBlock> output) {
        if (palette.isEmpty()) {
            return;
        }
        int bits = Math.max(4, 32 - Integer.numberOfLeadingZeros(palette.size() - 1));
        int valuesPerLong = 64 / bits;
        long mask = (1L << bits) - 1;
        for (int blockIndex = 0; blockIndex < 4096; blockIndex++) {
            int paletteIndex;
            if (palette.size() == 1 && packed.length == 0) {
                paletteIndex = 0;
            } else {
                int longIndex = blockIndex / valuesPerLong;
                if (longIndex >= packed.length) {
                    continue;
                }
                int shift = (blockIndex % valuesPerLong) * bits;
                paletteIndex = (int) ((packed[longIndex] >>> shift) & mask);
            }
            if (paletteIndex < 0 || paletteIndex >= palette.size()) {
                continue;
            }
            PaletteState state = palette.get(paletteIndex);
            if (!state.rail()) {
                continue;
            }
            int localX = blockIndex & 15;
            int localZ = (blockIndex >>> 4) & 15;
            int localY = (blockIndex >>> 8) & 15;
            output.add(new SavedRailChunkLocator.SavedRailBlock(
                    chunkX * 16 + localX,
                    sectionY * 16 + localY,
                    chunkZ * 16 + localZ,
                    state.materialName(),
                    state.shape()));
        }
    }

    private PaletteState paletteState(Object value) {
        Map<String, Object> state = compound(value).orElse(Map.of());
        String material = string(state.get("Name")).orElse("");
        boolean rail = material.equals("minecraft:rail")
                || material.equals("minecraft:powered_rail")
                || material.equals("minecraft:detector_rail")
                || material.equals("minecraft:activator_rail");
        if (!rail) {
            return new PaletteState(material, false, Rail.Shape.NORTH_SOUTH);
        }
        Map<String, Object> properties = compound(state.get("Properties")).orElse(Map.of());
        String shapeName = string(properties.get("shape")).orElse("north_south");
        Rail.Shape shape;
        try {
            shape = Rail.Shape.valueOf(shapeName.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            shape = Rail.Shape.NORTH_SOUTH;
        }
        return new PaletteState(material, true, shape);
    }

    private Map<String, Object> readCompound(DataInputStream input) throws IOException {
        Map<String, Object> values = new LinkedHashMap<>();
        while (true) {
            int type = input.readUnsignedByte();
            if (type == 0) {
                return values;
            }
            String name = input.readUTF();
            values.put(name, readPayload(input, type));
        }
    }

    private Object readPayload(DataInputStream input, int type) throws IOException {
        return switch (type) {
            case 1 -> input.readByte();
            case 2 -> input.readShort();
            case 3 -> input.readInt();
            case 4 -> input.readLong();
            case 5 -> input.readFloat();
            case 6 -> input.readDouble();
            case 7 -> input.readNBytes(requireLength(input.readInt()));
            case 8 -> input.readUTF();
            case 9 -> readList(input);
            case 10 -> readCompound(input);
            case 11 -> readIntArray(input);
            case 12 -> readLongArray(input);
            default -> throw new IOException("Unsupported NBT tag type " + type);
        };
    }

    private List<Object> readList(DataInputStream input) throws IOException {
        int elementType = input.readUnsignedByte();
        int length = requireLength(input.readInt());
        List<Object> values = new ArrayList<>(length);
        for (int index = 0; index < length; index++) {
            values.add(readPayload(input, elementType));
        }
        return values;
    }

    private int[] readIntArray(DataInputStream input) throws IOException {
        int length = requireLength(input.readInt());
        int[] values = new int[length];
        for (int index = 0; index < length; index++) {
            values[index] = input.readInt();
        }
        return values;
    }

    private long[] readLongArray(DataInputStream input) throws IOException {
        int length = requireLength(input.readInt());
        long[] values = new long[length];
        for (int index = 0; index < length; index++) {
            values[index] = input.readLong();
        }
        return values;
    }

    private int requireLength(int value) throws IOException {
        if (value < 0 || value > 16_777_216) {
            throw new IOException("NBT array length is invalid: " + value);
        }
        return value;
    }

    @SuppressWarnings("unchecked")
    private java.util.Optional<Map<String, Object>> compound(Object value) {
        return value instanceof Map<?, ?> map
                ? java.util.Optional.of((Map<String, Object>) map)
                : java.util.Optional.empty();
    }

    @SuppressWarnings("unchecked")
    private java.util.Optional<List<Object>> list(Object value) {
        return value instanceof List<?> list
                ? java.util.Optional.of((List<Object>) list)
                : java.util.Optional.empty();
    }

    private java.util.Optional<String> string(Object value) {
        return value instanceof String string
                ? java.util.Optional.of(string)
                : java.util.Optional.empty();
    }

    private java.util.Optional<Number> number(Object value) {
        return value instanceof Number number
                ? java.util.Optional.of(number)
                : java.util.Optional.empty();
    }

    record Result(
            String status,
            List<SavedRailChunkLocator.SavedRailBlock> rails,
            long blockChecks) {
    }

    private record PaletteState(
            String materialName,
            boolean rail,
            Rail.Shape shape) {
    }
}
