package dev.advancedtrains.atcs.display;

import java.awt.image.BufferedImage;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A picture in map colours: one byte per pixel, as a map item holds it and as the client is
 * sent it. Displays keep their pictures this way (a quarter of the memory of an RGB picture),
 * convert each drawing once, and build the departure board from pieces already converted.
 *
 * <p>The colours are the vanilla map colours: 62 base colours in four shades, byte 0 to 3 being
 * transparent. The table is kept here rather than read from Bukkit's {@code MapPalette}, whose
 * colour methods are marked for removal.
 */
public final class MapPixels {
    /** The size of one map item, in pixels. */
    public static final int TILE = 128;

    private static final int[] BASE = {
            0x000000, 0x7FB238, 0xF7E9A3, 0xC7C7C7, 0xFF0000, 0xA0A0FF, 0xA7A7A7, 0x007C00,
            0xFFFFFF, 0xA4A8B8, 0x976D4D, 0x707070, 0x4040FF, 0x8F7748, 0xFFFCF5, 0xD87F33,
            0xB24CD8, 0x6699D8, 0xE5E533, 0x7FCC19, 0xF27FA5, 0x4C4C4C, 0x999999, 0x4C7F99,
            0x7F3FB2, 0x334CB2, 0x664C33, 0x667F33, 0x993333, 0x191919, 0xFAEE4D, 0x5CDBD5,
            0x4A80FF, 0x00D93A, 0x815631, 0x700200, 0xD1B1A1, 0x9F5224, 0x95576C, 0x706C8A,
            0xBA8524, 0x677535, 0xA04D4E, 0x392923, 0x876B62, 0x575C5C, 0x7A4958, 0x4C3E5C,
            0x4C3223, 0x4C522A, 0x8E3C2E, 0x251610, 0xBD3031, 0x943F61, 0x5C191D, 0x167E86,
            0x3A8E8C, 0x562C3E, 0x14B485, 0x646464, 0xD8AF93, 0x7FA796};
    private static final int[] SHADES = {180, 220, 255, 135};
    private static final int[] PALETTE = new int[BASE.length * 4];
    /** Nearest map colour of each RGB colour met so far; drawings use few colours, so this stays small. */
    private static final Map<Integer, Byte> NEAREST = new ConcurrentHashMap<>();
    private static final int NEAREST_LIMIT = 1 << 16;

    static {
        for (int base = 0; base < BASE.length; base++) {
            for (int shade = 0; shade < 4; shade++) {
                int rgb = BASE[base];
                int r = (rgb >> 16 & 0xFF) * SHADES[shade] / 255;
                int g = (rgb >> 8 & 0xFF) * SHADES[shade] / 255;
                int b = (rgb & 0xFF) * SHADES[shade] / 255;
                PALETTE[base * 4 + shade] = r << 16 | g << 8 | b;
            }
        }
    }

    private final int width;
    private final int height;
    private final byte[] data;

    public MapPixels(int width, int height) {
        this(width, height, new byte[width * height]);
    }

    private MapPixels(int width, int height, byte[] data) {
        this.width = width;
        this.height = height;
        this.data = data;
    }

    /** A tile as a map holds it, 128 by 128 map colours; the array is used as it is. */
    static MapPixels ofTile(byte[] tile) {
        if (tile.length != TILE * TILE) {
            throw new IllegalArgumentException("A tile has " + TILE * TILE + " pixels, not " + tile.length);
        }
        return new MapPixels(TILE, TILE, tile);
    }

    /** The drawing in map colours; pixels less than half opaque become transparent. */
    public static MapPixels of(BufferedImage image) {
        int width = image.getWidth();
        int height = image.getHeight();
        int[] argb = image.getRGB(0, 0, width, height, null, 0, width);
        byte[] data = new byte[width * height];
        for (int index = 0; index < argb.length; index++) {
            int pixel = argb[index];
            data[index] = (pixel >>> 24) < 128 ? 0 : nearest(pixel & 0xFFFFFF);
        }
        return new MapPixels(width, height, data);
    }

    /** The map colour nearest an RGB colour, by the weighted distance Bukkit uses. */
    static byte nearest(int rgb) {
        Byte known = NEAREST.get(rgb);
        if (known != null) {
            return known;
        }
        int r = rgb >> 16 & 0xFF;
        int g = rgb >> 8 & 0xFF;
        int b = rgb & 0xFF;
        int best = 4;
        double bestDistance = Double.MAX_VALUE;
        for (int index = 4; index < PALETTE.length; index++) {
            int other = PALETTE[index];
            double mean = (r + (other >> 16 & 0xFF)) / 2.0;
            double dr = r - (other >> 16 & 0xFF);
            double dg = g - (other >> 8 & 0xFF);
            double db = b - (other & 0xFF);
            double distance = (2 + mean / 256) * dr * dr + 4 * dg * dg + (2 + (255 - mean) / 256) * db * db;
            if (distance < bestDistance) {
                bestDistance = distance;
                best = index;
            }
        }
        byte result = (byte) best;
        if (NEAREST.size() < NEAREST_LIMIT) {
            NEAREST.put(rgb, result);
        }
        return result;
    }

    /** The RGB colour a map colour byte shows. */
    public static int rgb(byte color) {
        return PALETTE[Math.min(PALETTE.length - 1, color & 0xFF)];
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    public byte get(int x, int y) {
        return data[y * width + x];
    }

    /** Copies the source in with its top left corner at (x, y); what falls outside is cut off. */
    public void blit(MapPixels source, int x, int y) {
        int fromX = Math.max(0, -x);
        int toX = Math.min(source.width, width - x);
        if (toX <= fromX) {
            return;
        }
        for (int row = Math.max(0, -y); row < source.height && y + row < height; row++) {
            System.arraycopy(source.data, row * source.width + fromX, data, (y + row) * width + x + fromX, toX - fromX);
        }
    }

    public MapPixels copy() {
        return new MapPixels(width, height, data.clone());
    }

    /** One 128 by 128 tile, as a map item shows it; outside the picture is transparent. */
    public byte[] tile(int column, int row) {
        byte[] tile = new byte[TILE * TILE];
        for (int y = 0; y < TILE; y++) {
            int sourceY = row * TILE + y;
            if (sourceY >= height) {
                break;
            }
            int sourceX = column * TILE;
            int length = Math.min(TILE, width - sourceX);
            if (length > 0) {
                System.arraycopy(data, sourceY * width + sourceX, tile, y * TILE, length);
            }
        }
        return tile;
    }

    /** The picture in RGB, as players see it: for previews, exports and tests. */
    public BufferedImage toImage() {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        int[] argb = new int[data.length];
        for (int index = 0; index < data.length; index++) {
            argb[index] = (data[index] & 0xFF) < 4 ? 0 : 0xFF000000 | rgb(data[index]);
        }
        image.setRGB(0, 0, width, height, argb, 0, width);
        return image;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof MapPixels pixels && pixels.width == width && pixels.height == height && Arrays.equals(pixels.data, data);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(data);
    }
}
