package dev.advancedtrains.atcs.display;

import org.jspecify.annotations.Nullable;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A departure board in the manner of an airport's split-flap board: every character on its own
 * flap with the split across it, columns for the time, the line, the destination, the platform
 * and a remark, column headings in amber. The board's size decides how many columns it shows
 * and how many departures: a board one frame high still shows three.
 *
 * <p>The board is cheap to keep current. What never changes (the housing, the heading, the
 * column titles) is drawn once as the board's scene; every flap is drawn once per character,
 * colour and size into a shared {@link Cache}, already in map colours, and a refresh only copies
 * flaps onto a copy of the scene. Nothing is drawn with Java2D while the departures change.
 */
public final class SplitFlapRenderer {
    /** What the remark column says, in its colour. */
    public enum Remark {
        NONE(""), BOARDING("BOARDING"), ARRIVING("ARRIVING"), DELAYED("DELAYED"), SUSPENDED("SUSPENDED");

        private final String text;

        Remark(String text) {
            this.text = text;
        }

        public String text() {
            return text;
        }
    }

    /**
     * One departure.
     *
     * @param due         the time column, for example {@code 2 MIN} or {@code NOW}
     * @param line        the line badge, drawn on a flap in the line colour
     * @param destination where the train goes; cut to the cells there are
     * @param platform    the platform, or null
     * @param turning     while the flaps turn, what the remark column shows (no colour yet)
     */
    public record Row(String due, String line, int lineColor, String destination, @Nullable String platform, Remark remark,
                      @Nullable String turning) {
        public Row(String due, String line, int lineColor, String destination, @Nullable String platform, Remark remark) {
            this(due, line, lineColor, destination, platform, remark, null);
        }
    }

    static final Color HOUSING = new Color(0x0C0C0D);
    static final Color FLAP_TOP = new Color(0x2A2B2E);
    static final Color FLAP_BOTTOM = new Color(0x222326);
    static final Color FLAP_EDGE = new Color(0x34363A);
    static final Color SPLIT = new Color(0x070707);
    static final Color LETTER = new Color(0xF3EFE4);
    static final Color HEADING = new Color(0xF2B705);
    static final Color MUTED = new Color(0x8B8E94);
    static final Color GREEN = new Color(0x4CD964);
    static final Color AMBER = new Color(0xFFC21A);
    static final Color RED = new Color(0xFF4B3E);

    /** At most this many departures; a taller board gets larger flaps instead of empty rows. */
    static final int MAX_ROWS = 7;

    private SplitFlapRenderer() {
    }

    // ---------------------------------------------------------------- layout

    /** Where everything goes, in whole pixels, worked out from the board's size. */
    record Layout(int pad, int header, int labels, int cell, int flapHeight, int gap, double rowGap,
                  int dueCells, int lineCells, int destinationCells, int platformCells, int remarkCells, int groupGap,
                  int rows, int top) {

        int x(int column) {
            int x = pad;
            int[] cells = {dueCells, lineCells, destinationCells, platformCells, remarkCells};
            for (int index = 0; index < column; index++) {
                if (cells[index] > 0) {
                    x += cells[index] * (cell + gap) + groupGap;
                }
            }
            return x;
        }

        int rowY(int row) {
            return (int) Math.round(top + row * (flapHeight + rowGap));
        }
    }

    static Layout layout(int width, int height) {
        int pad = (int) Math.round(Draw.clamp(Math.min(width, height) * 0.04, 4, 12));
        int header = (int) Math.round(Draw.clamp(Math.min(height * 0.17, width * 0.075), 20, 46));
        boolean wide = width >= 640;
        boolean medium = width >= 384;
        double targetCells = wide ? 40 : medium ? 27 : 19;
        int minDestination = wide ? 16 : medium ? 12 : 8;
        Layout layout = layout(width, height, pad, header, Draw.clamp((width - 2.0 * pad) / targetCells, 9, 18), wide, medium);
        while (layout.rows() > MAX_ROWS) {
            Layout bigger = layout(width, height, pad, header, layout.cell() + layout.gap() + 1, wide, medium);
            if (bigger.destinationCells() < minDestination || bigger.rows() < MAX_ROWS) {
                break;
            }
            layout = bigger;
        }
        // Room left over on a tall board is shared out between the rows, as far as a half flap each.
        int rows = Math.min(layout.rows(), MAX_ROWS);
        double room = height - layout.top() - pad * 0.6;
        double used = rows * layout.flapHeight() + (rows - 1) * layout.rowGap();
        double rowGap = layout.rowGap() + (rows > 1 ? Math.min((room - used) / (rows - 1), layout.flapHeight() * 0.5) : 0);
        return new Layout(layout.pad(), layout.header(), layout.labels(), layout.cell(), layout.flapHeight(), layout.gap(),
                rowGap, layout.dueCells(), layout.lineCells(), layout.destinationCells(), layout.platformCells(),
                layout.remarkCells(), layout.groupGap(), rows, layout.top());
    }

    private static Layout layout(int width, int height, int pad, int header, double pitch, boolean wide, boolean medium) {
        int gap = (int) Math.max(1, Math.round(pitch * 0.12));
        int cell = (int) Math.round(pitch - gap);
        int flapHeight = (int) Math.round(cell * 1.5);
        int groupGap = (int) Math.round(cell * 0.7);
        int dueCells = medium ? 6 : 3;
        int lineCells = 2;
        int platformCells = medium ? 2 : 0;
        int remarkCells = wide ? 9 : 0;
        int groups = 3 + (platformCells > 0 ? 1 : 0) + (remarkCells > 0 ? 1 : 0);
        int fixed = (dueCells + lineCells + platformCells + remarkCells) * (cell + gap) + (groups - 1) * groupGap;
        int destinationCells = Math.max(4, (width - 2 * pad - fixed) / (cell + gap));
        int labels = height >= 160 || medium ? (int) Math.round(Draw.clamp(cell * 0.8, 8, 13)) : 0;
        int rowGap = (int) Math.max(2, Math.round(flapHeight * 0.16));
        int top = (int) Math.round(header + labels + pad * 0.8);
        int rows = Math.max(1, (int) Math.floor((height - top - pad * 0.6 + rowGap) / (flapHeight + rowGap)));
        return new Layout(pad, header, labels, cell, flapHeight, gap, rowGap, dueCells, lineCells, destinationCells,
                platformCells, remarkCells, groupGap, rows, top);
    }

    /** How many departures a board of this size shows. */
    public static int rows(int width, int height) {
        return layout(width, height).rows();
    }

    // ---------------------------------------------------------------- drawing

    /** Flaps already drawn, in map colours, shared by every board; a few hundred bytes each. */
    public static final class Cache {
        private static final int LIMIT = 4096;
        private final Map<FlapKey, MapPixels> flaps = new LinkedHashMap<>(256, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<FlapKey, MapPixels> eldest) {
                return size() > LIMIT;
            }
        };

        public synchronized int size() {
            return flaps.size();
        }

        /** The bytes the drawn flaps take. */
        public synchronized long bytes() {
            long total = 0;
            for (MapPixels flap : flaps.values()) {
                total += (long) flap.width() * flap.height();
            }
            return total;
        }

        synchronized MapPixels flap(FlapKey key) {
            MapPixels known = flaps.get(key);
            if (known == null) {
                known = draw(key);
                flaps.put(key, known);
            }
            return known;
        }
    }

    /** A flap as drawn: its size, character, ink and the colours of its two halves. */
    record FlapKey(int width, int height, String text, boolean badge, int ink, int top, int bottom) {
    }

    /**
     * What a board shows that does not change with the departures: the housing, the heading with
     * the station, the column titles. Drawn once per board and size.
     */
    public static MapPixels scene(String station, @Nullable String platforms, int clockLength, int width, int height) {
        BufferedImage image = Draw.canvas(width, height, HOUSING);
        Graphics2D g = Draw.graphics(image);
        try {
            Layout layout = layout(width, height);
            header(g, layout, station, platforms, clockLength, width);
            if (layout.labels() > 0) {
                labels(g, layout);
            }
            return MapPixels.of(image);
        } finally {
            g.dispose();
        }
    }

    /**
     * The board: the scene with the clock and the departures on their flaps.
     *
     * @param clock the time shown in the header, for example {@code 14:05}
     * @param empty what the first row says when no train is coming
     */
    public static MapPixels board(Cache cache, MapPixels scene, String clock, List<Row> rows, String empty) {
        MapPixels board = scene.copy();
        Layout layout = layout(scene.width(), scene.height());
        clock(cache, board, layout, clock);
        for (int index = 0; index < layout.rows(); index++) {
            int y = layout.rowY(index);
            if (index < rows.size()) {
                row(cache, board, layout, rows.get(index), y);
            } else if (index == 0 && rows.isEmpty()) {
                blankRow(cache, board, layout, y, flapText(empty));
            } else {
                blankRow(cache, board, layout, y, "");
            }
        }
        return board;
    }

    /** The board as a picture, for previews and tests. */
    public static BufferedImage image(String station, @Nullable String platforms, String clock, List<Row> rows, String empty,
                                      int width, int height) {
        return board(new Cache(), scene(station, platforms, clock.length(), width, height), clock, rows, empty).toImage();
    }

    private static int clockCell(Layout layout) {
        return (int) Math.round(layout.header() * 0.42);
    }

    private static int clockX(Layout layout, int length, int width) {
        return width - layout.pad() - length * (clockCell(layout) + 1);
    }

    private static void clock(Cache cache, MapPixels board, Layout layout, String clock) {
        int cell = clockCell(layout);
        int height = (int) Math.round(cell * 1.45);
        int x = clockX(layout, clock.length(), board.width());
        int y = (int) Math.round(layout.header() * 0.55 - height / 2.0);
        for (int index = 0; index < clock.length(); index++) {
            char c = clock.charAt(index);
            if (c != ':') {
                board.blit(cache.flap(new FlapKey(cell, height, String.valueOf(c), false, HEADING.getRGB(), FLAP_TOP.getRGB(),
                        FLAP_BOTTOM.getRGB())), x + index * (cell + 1), y);
            }
        }
    }

    private static void header(Graphics2D g, Layout layout, String station, @Nullable String platforms, int clockLength, int width) {
        double h = layout.header();
        double middle = h * 0.55;
        double pad = layout.pad();
        int clockX = clockX(layout, clockLength, width);
        int cell = clockCell(layout);
        // The colon of the clock; its digits are flaps, drawn with the departures.
        Font colon = flapFont(g, Math.round(cell * 1.45));
        Draw.textCentred(g, ":", clockX + 2 * (cell + 1) + cell / 2.0, middle, colon, HEADING);
        // A train seen from the front, then the heading.
        double icon = h * 0.5;
        trainIcon(g, pad, middle - icon / 2, icon, HEADING);
        double x = pad + icon * 1.35;
        Font title = Draw.of(Fonts.BOLD, h * 0.46);
        String heading = "Departures";
        Draw.textMiddle(g, heading, x, middle, title, Color.WHITE);
        x += Draw.width(g, title, heading) + h * 0.45;
        double room = clockX - x - h * 0.4;
        Font place = Draw.of(Fonts.SEMIBOLD, h * 0.36);
        String where = platforms == null || platforms.isBlank() ? station : station + "  ·  " + platforms;
        if (Draw.width(g, place, where) > room) {
            where = station;
        }
        if (room > h) {
            place = Draw.of(Fonts.SEMIBOLD, Draw.fit(g, Fonts.SEMIBOLD, where, room, h * 0.36));
            Draw.textMiddle(g, where, x, middle, place, MUTED);
        }
        g.setColor(new Color(0x1C1D20));
        g.fill(new Rectangle2D.Double(0, h - 1, width, 1));
    }

    private static void labels(Graphics2D g, Layout layout) {
        Font font = Draw.of(Fonts.FLAP, layout.labels() * 0.95);
        double middle = layout.header() + layout.labels() * 0.62 + layout.pad() * 0.3;
        String[] titles = {"DUE", "LINE", "DESTINATION", "PLAT", "REMARKS"};
        int[] cells = {layout.dueCells(), layout.lineCells(), layout.destinationCells(), layout.platformCells(), layout.remarkCells()};
        for (int column = 0; column < titles.length; column++) {
            if (cells[column] > 0) {
                Draw.textMiddle(g, titles[column], layout.x(column) + 1, middle, font, HEADING);
            }
        }
    }

    private static void row(Cache cache, MapPixels board, Layout layout, Row row, int y) {
        Color remarkColour = switch (row.remark()) {
            case BOARDING -> GREEN;
            case ARRIVING -> AMBER;
            case DELAYED, SUSPENDED -> RED;
            case NONE -> LETTER;
        };
        String due = layout.dueCells() < 6 ? row.due().replace(" MIN", "M").replace("MIN", "M") : row.due();
        cells(cache, board, layout, layout.x(0), y, layout.dueCells(), due, true, layout.remarkCells() > 0 ? LETTER : remarkColour);
        lineFlap(cache, board, layout, row.line(), row.lineColor(), layout.x(1), y);
        cells(cache, board, layout, layout.x(2), y, layout.destinationCells(), flapText(row.destination()), false, LETTER);
        if (layout.platformCells() > 0) {
            cells(cache, board, layout, layout.x(3), y, layout.platformCells(), row.platform() == null ? "" : row.platform(), true, LETTER);
        }
        if (layout.remarkCells() > 0) {
            cells(cache, board, layout, layout.x(4), y, layout.remarkCells(),
                    row.turning() != null ? row.turning() : row.remark().text(), false, remarkColour);
        }
    }

    private static void blankRow(Cache cache, MapPixels board, Layout layout, int y, String message) {
        cells(cache, board, layout, layout.x(0), y, layout.dueCells(), "", false, LETTER);
        lineFlap(cache, board, layout, "", 0, layout.x(1), y);
        cells(cache, board, layout, layout.x(2), y, layout.destinationCells(), message, false, MUTED);
        if (layout.platformCells() > 0) {
            cells(cache, board, layout, layout.x(3), y, layout.platformCells(), "", false, LETTER);
        }
        if (layout.remarkCells() > 0) {
            cells(cache, board, layout, layout.x(4), y, layout.remarkCells(), "", false, LETTER);
        }
    }

    private static void cells(Cache cache, MapPixels board, Layout layout, int x, int y, int count, String text,
                              boolean alignRight, Color ink) {
        String fitted = text.length() > count ? text.substring(0, count).stripTrailing() : text;
        int offset = alignRight ? count - fitted.length() : 0;
        for (int index = 0; index < count; index++) {
            int at = index - offset;
            String c = at >= 0 && at < fitted.length() ? String.valueOf(fitted.charAt(at)).strip() : "";
            board.blit(cache.flap(new FlapKey(layout.cell(), layout.flapHeight(), c, false, c.isEmpty() ? 0 : ink.getRGB(),
                    FLAP_TOP.getRGB(), FLAP_BOTTOM.getRGB())), x + index * (layout.cell() + layout.gap()), y);
        }
    }

    /** The line badge on one wide flap in the line's colour, like an airline's logo flap. */
    private static void lineFlap(Cache cache, MapPixels board, Layout layout, String badge, int rgb, int x, int y) {
        int width = layout.lineCells() * (layout.cell() + layout.gap()) - layout.gap();
        FlapKey key = badge.isEmpty()
                ? new FlapKey(width, layout.flapHeight(), "", false, 0, FLAP_TOP.getRGB(), FLAP_BOTTOM.getRGB())
                : new FlapKey(width, layout.flapHeight(), badge, true, Draw.on(rgb).getRGB(), 0xFF000000 | rgb,
                Draw.darker(new Color(rgb), 0.86).getRGB());
        board.blit(cache.flap(key), x, y);
    }

    /** Draws one flap on the housing colour and turns it into map colours. */
    private static MapPixels draw(FlapKey key) {
        BufferedImage image = Draw.canvas(key.width(), key.height(), HOUSING);
        Graphics2D g = Draw.graphics(image);
        try {
            Font font = null;
            if (!key.text().isEmpty()) {
                font = key.badge()
                        ? Draw.of(Fonts.BOLD, Draw.fit(g, Fonts.BOLD, key.text(), key.width() * 0.8, key.height() * 0.62))
                        : flapFont(g, key.height());
            }
            flap(g, 0, 0, key.width(), key.height(), key.text(), font, new Color(key.ink(), true),
                    new Color(key.top(), true), new Color(key.bottom(), true));
            return MapPixels.of(image);
        } finally {
            g.dispose();
        }
    }

    private static Font flapFont(Graphics2D g, double flapHeight) {
        Font font = Draw.of(Fonts.FLAP, flapHeight);
        double cap = Draw.cap(g, font);
        return Draw.of(Fonts.FLAP, flapHeight * flapHeight * 0.6 / cap);
    }

    /** One flap: two halves, the character across both, and the split between them. */
    static void flap(Graphics2D g, double x, double y, double width, double height, String text, @Nullable Font font,
                     Color ink, Color top, Color bottom) {
        double radius = Math.max(1.5, width * 0.14);
        double half = Math.round(height / 2);
        g.setColor(bottom);
        g.fill(new RoundRectangle2D.Double(x, y, width, height, radius, radius));
        g.setColor(top);
        g.fill(new RoundRectangle2D.Double(x, y, width, half, radius, radius));
        g.fill(new Rectangle2D.Double(x, y + half - radius, width, radius));
        g.setColor(top.equals(FLAP_TOP) ? FLAP_EDGE : top);
        g.fill(new Rectangle2D.Double(x + radius / 2, y, width - radius, 1));
        if (!text.isEmpty() && font != null) {
            Draw.inkCentred(g, text, x + width / 2, y + height / 2, font, ink);
        }
        g.setColor(SPLIT);
        g.fill(new Rectangle2D.Double(x, y + half - 0.5, width, Math.max(1, height * 0.05)));
        // The hinge pins at either end of the split.
        double pin = Math.max(1, height * 0.08);
        g.fill(new Rectangle2D.Double(x - 0.5, y + half - pin / 2, Math.max(1, width * 0.08), pin));
        g.fill(new Rectangle2D.Double(x + width - Math.max(1, width * 0.08) + 0.5, y + half - pin / 2, Math.max(1, width * 0.08), pin));
    }

    private static void trainIcon(Graphics2D g, double x, double y, double size, Color color) {
        double w = size * 0.78;
        double left = x + (size - w) / 2;
        g.setColor(color);
        g.fill(new RoundRectangle2D.Double(left, y, w, size * 0.82, size * 0.32, size * 0.32));
        g.setColor(HOUSING);
        g.fill(new RoundRectangle2D.Double(left + w * 0.16, y + size * 0.14, w * 0.68, size * 0.3, size * 0.08, size * 0.08));
        double light = size * 0.11;
        g.fill(new Ellipse2D.Double(left + w * 0.2, y + size * 0.56, light, light));
        g.fill(new Ellipse2D.Double(left + w * 0.8 - light, y + size * 0.56, light, light));
        g.setColor(color);
        double rail = Math.max(1, size * 0.08);
        g.fill(new Rectangle2D.Double(left + w * 0.1, y + size * 0.9, w * 0.8, rail));
    }

    // ---------------------------------------------------------------- turning

    /** Station names as the flaps carry them: capitals, without the full stops of abbreviations. */
    static String flapText(String text) {
        return text.toUpperCase(Locale.ROOT).replace(".", "").stripTrailing();
    }

    /** The characters on a flap drum, in the order the flaps turn. */
    static final String DRUM = " ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-'&/:~";

    /**
     * The board part-way through turning from one set of departures to the next: every flap
     * steps forwards through the drum until it shows its new character, so a long way round the
     * drum takes longer, as on a real board. {@code step} counts from 0; from {@link #steps()} on
     * the rows are the new ones.
     */
    public static List<Row> between(List<Row> from, List<Row> to, int step, int width, int height) {
        Layout layout = layout(width, height);
        List<Row> rows = new ArrayList<>();
        for (int index = 0; index < to.size() && index < layout.rows(); index++) {
            Row a = index < from.size() ? from.get(index) : null;
            Row b = to.get(index);
            String due = turn(a == null ? "" : a.due(), b.due(), layout.dueCells(), true, step);
            String destination = turn(a == null ? "" : flapText(a.destination()), flapText(b.destination()),
                    layout.destinationCells(), false, step);
            String platform = turn(a == null || a.platform() == null ? "" : a.platform(), b.platform() == null ? "" : b.platform(),
                    Math.max(1, layout.platformCells()), true, step);
            String remarkText = turn(a == null ? "" : a.remark().text(), b.remark().text(), Math.max(1, layout.remarkCells()), false, step);
            boolean lineTurned = a == null || step >= 2;
            Remark remark = remarkText.strip().equals(b.remark().text()) ? b.remark() : Remark.NONE;
            rows.add(new Row(due, lineTurned ? b.line() : a.line(), lineTurned ? b.lineColor() : a.lineColor(),
                    destination, platform, remark, remark == Remark.NONE ? remarkText : null));
        }
        return rows;
    }

    /** How many steps a full turn of the drum takes. */
    public static int steps() {
        return (DRUM.length() + 3) / 4;
    }

    private static String turn(String from, String to, int cells, boolean alignRight, int step) {
        String a = pad(from.toUpperCase(Locale.ROOT), cells, alignRight);
        String b = pad(to.toUpperCase(Locale.ROOT), cells, alignRight);
        StringBuilder out = new StringBuilder();
        for (int index = 0; index < cells; index++) {
            int ia = Math.max(0, DRUM.indexOf(a.charAt(index)));
            int ib = DRUM.indexOf(b.charAt(index));
            if (ib < 0) {
                out.append(b.charAt(index));
                continue;
            }
            int distance = (ib - ia + DRUM.length()) % DRUM.length();
            out.append(DRUM.charAt((ia + Math.min(distance, step * 4)) % DRUM.length()));
        }
        return alignRight ? out.toString() : out.toString().stripTrailing();
    }

    private static String pad(String text, int cells, boolean alignRight) {
        String cut = text.length() > cells ? text.substring(0, cells) : text;
        String spaces = " ".repeat(cells - cut.length());
        return alignRight ? spaces + cut : cut + spaces;
    }
}
