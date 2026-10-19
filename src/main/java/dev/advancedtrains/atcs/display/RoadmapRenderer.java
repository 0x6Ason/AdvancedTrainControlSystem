package dev.advancedtrains.atcs.display;

import dev.advancedtrains.atcs.network.Line;
import dev.advancedtrains.atcs.network.Network;
import dev.advancedtrains.atcs.network.Service;
import dev.advancedtrains.atcs.network.Stop;
import org.jspecify.annotations.Nullable;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.geom.Arc2D;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

/**
 * The line diagram of a service for a platform wall, in the manner of New York's and Hong Kong's
 * strip maps: a black heading with the route bullet and where the train goes, the line in its
 * colour with every stop, the stations already passed in grey, "You are here", the other lines
 * at each interchange, and chevrons along the line showing the way the train runs.
 *
 * <p>The frames decide the shape. A long strip (three or more times as wide as high) draws the
 * line once across; a wall closer to square folds it into rows that turn at the ends, as many as
 * give the largest names; a sign taller than wide draws it downwards with the names beside it.
 * The line runs to the right or to the left, to match the way the trains leave the platform.
 */
public final class RoadmapRenderer {
    /** Which way the trains run on the sign. */
    public enum Flow { RIGHT, LEFT }

    /**
     * The shape of the diagram: one strip across, the line folded into rows, or a column of
     * stops; {@code AUTO} picks by the frames (a strip when three times as wide as high, rows on
     * a landscape wall, a column on a square or upright sign).
     */
    public enum Shape { AUTO, STRIP, ROWS, COLUMN }

    enum State { PAST, HERE, AHEAD }

    record Badge(String text, int color) {
    }

    record Mark(String name, boolean pass, boolean terminus, List<Badge> connections, State state) {
        boolean big() {
            return terminus || !connections.isEmpty() || state == State.HERE;
        }
    }

    /** What the diagram shows, worked out from the network before anything is drawn. */
    record Plan(String badge, int color, String lineName, String serviceName, String toward, boolean loop,
                List<Mark> marks, @Nullable String loopsBackTo) {
    }

    static final Color PAPER = Color.WHITE;
    static final Color INK = new Color(0x111111);
    static final Color BAND = new Color(0x111111);
    static final Color SOFT = new Color(0xBDBDBD);
    static final Color PAST_INK = new Color(0xA6A6A6);
    static final Color PAST_LINE = new Color(0xD4D4D4);
    static final Color RED = new Color(0xD52B1E);

    private RoadmapRenderer() {
    }

    static Plan plan(Network network, Service service, @Nullable String hereStationId) {
        Line line = network.line(service.key().lineId()).orElse(Line.of(service.key().lineId(), service.key().lineId(), 0x808080));
        List<Stop> stops = service.stops();
        int n = stops.size();
        int here = -1;
        for (int index = 0; index < n; index++) {
            if (stops.get(index).stationId().equals(hereStationId)) {
                here = index;
                break;
            }
        }
        boolean loop = service.circular();
        List<Mark> marks = new ArrayList<>();
        for (int step = 0; step < n; step++) {
            int index = loop && here >= 0 ? (here + step) % n : step;
            Stop stop = stops.get(index);
            State state = here < 0 ? State.AHEAD
                    : index == here ? State.HERE
                    : !loop && index < here ? State.PAST : State.AHEAD;
            List<Badge> connections = new ArrayList<>();
            for (Line other : network.linesCallingAt(stop.stationId())) {
                if (!other.id().equals(line.id())) {
                    connections.add(new Badge(other.shortName(), other.color()));
                }
            }
            marks.add(new Mark(network.stationName(stop.stationId()), stop.pass(), !loop && index == n - 1, connections, state));
        }
        String toward = loop ? service.name() : n == 0 ? service.name() : network.stationName(stops.get(n - 1).stationId());
        String back = loop && !marks.isEmpty() ? marks.get(0).name() : null;
        return new Plan(line.shortName(), line.color(), line.name(), service.name(), toward, loop, marks, back);
    }

    public static BufferedImage draw(Network network, Service service, @Nullable String hereStationId, Flow flow, int width, int height) {
        return draw(network, service, hereStationId, flow, Shape.AUTO, width, height);
    }

    public static BufferedImage draw(Network network, Service service, @Nullable String hereStationId, Flow flow, Shape shape,
                                     int width, int height) {
        return draw(plan(network, service, hereStationId), flow, shape, width, height);
    }

    static BufferedImage draw(Plan plan, Flow flow, Shape shape, int width, int height) {
        BufferedImage image = Draw.canvas(width, height, PAPER);
        Graphics2D g = Draw.graphics(image);
        try {
            double aspect = (double) width / height;
            Shape chosen = shape != Shape.AUTO ? shape : aspect >= 3 ? Shape.STRIP : aspect >= 1.2 ? Shape.ROWS : Shape.COLUMN;
            switch (chosen) {
                case STRIP -> strip(g, plan, flow, width, height);
                case ROWS -> snake(g, plan, flow, width, height);
                default -> column(g, plan, width, height);
            }
            return image;
        } finally {
            g.dispose();
        }
    }

    // ---------------------------------------------------------------- headings

    /** The black heading across the top: bullet, where the train goes, line and service, arrow. */
    private static void band(Graphics2D g, Plan plan, double width, double h, boolean left, boolean down) {
        g.setColor(BAND);
        g.fill(new Rectangle2D.Double(0, 0, width, h));
        double pad = h * 0.28;
        double middle = h / 2;
        double d = h * 0.64;
        double arrowLength = h * 0.62;
        double x = pad;
        double end = width - pad;
        if (down) {
            downArrow(g, end - h * 0.22, middle, h * 0.44, h * 0.56, Color.WHITE);
            end -= h * 0.6;
        } else if (left) {
            Draw.arrow(g, x, middle, arrowLength, h * 0.46, true, Color.WHITE);
            x += arrowLength + pad * 0.8;
        } else {
            Draw.arrow(g, end, middle, arrowLength, h * 0.46, false, Color.WHITE);
            end -= arrowLength + pad * 0.8;
        }
        x += Draw.bullet(g, plan.badge(), plan.color(), x, middle, d) + pad * 0.8;
        double room = end - x;
        String to = plan.loop() ? "" : "to ";
        Font small = Draw.of(Fonts.SEMIBOLD, h * 0.3);
        double toWidth = Draw.width(g, small, to);
        Font big = Draw.of(Fonts.BOLD, Draw.fit(g, Fonts.BOLD, plan.toward(), Math.max(10, room - toWidth), h * 0.44));
        String detail = plan.loop() ? plan.lineName() + "  ·  loop" : plan.lineName() + "  ·  " + plan.serviceName();
        Font detailFont = Draw.of(Fonts.SEMIBOLD, h * 0.27);
        double used = toWidth + Draw.width(g, big, plan.toward());
        if (!to.isEmpty()) {
            Draw.textMiddle(g, to, x, middle, small, SOFT);
        }
        Draw.textMiddle(g, plan.toward(), x + toWidth, middle, big, Color.WHITE);
        double detailWidth = Draw.width(g, detailFont, detail);
        if (room - used > detailWidth + h) {
            Draw.textMiddle(g, detail, end - detailWidth, middle, detailFont, SOFT);
        }
    }

    /** The heading as a black panel at the end of a low strip the trains come from. */
    private static void panel(Graphics2D g, Plan plan, double x, double width, double height, boolean left) {
        g.setColor(BAND);
        g.fill(new Rectangle2D.Double(x, 0, width, height));
        double pad = Draw.clamp(height * 0.09, 6, 16);
        double inner = width - 2 * pad;
        double d = Draw.clamp(height * 0.24, 16, 40);
        double top = pad + d / 2;
        double after = x + pad + Draw.bullet(g, plan.badge(), plan.color(), x + pad, top, d) + d * 0.3;
        double arrowLength = d * 0.85;
        double arrowRight = x + width - pad;
        Draw.arrow(g, left ? arrowRight - arrowLength : arrowRight, top, arrowLength, d * 0.6, left, Color.WHITE);
        Font small = Draw.of(Fonts.SEMIBOLD, Draw.clamp(height * 0.105, 8, 16));
        if (!plan.loop() && arrowRight - arrowLength - after > Draw.width(g, small, "to") + 2) {
            Draw.textMiddle(g, "to", after, top, small, SOFT);
        }
        String detail = plan.loop() ? plan.lineName() : plan.serviceName();
        Font detailFont = Draw.of(Fonts.SEMIBOLD, Draw.fit(g, Fonts.SEMIBOLD, detail, inner, small.getSize2D()));
        double detailCap = Draw.cap(g, detailFont);
        double nameTop = pad + d + pad * 0.6;
        double nameBottom = height - pad - detailCap * 1.9;
        double room = nameBottom - nameTop;
        double size = Draw.fitWrapped(g, Fonts.BOLD, plan.toward(), inner, 2, Math.min(room / 2.2, height * 0.2), 7);
        Font big = Draw.of(Fonts.BOLD, size);
        List<String> lines = Draw.wrap(g, big, plan.toward(), inner, 2);
        if (lines == null) {
            lines = List.of(plan.toward());
        }
        double cap = Draw.cap(g, big);
        double lineHeight = cap * 1.4;
        double block = (lines.size() - 1) * lineHeight + cap;
        double y = nameTop + (room - block) / 2 + cap;
        for (String text : lines) {
            Draw.text(g, text, x + pad, y, big, Color.WHITE);
            y += lineHeight;
        }
        Draw.text(g, detail, x + pad, height - pad, detailFont, SOFT);
    }

    // ---------------------------------------------------------------- layouts

    private static void strip(Graphics2D g, Plan plan, Flow flow, int width, int height) {
        boolean left = flow == Flow.LEFT;
        if (height < 200) {
            double panelWidth = Draw.clamp(width * 0.15, 104, 220);
            panel(g, plan, left ? width - panelWidth : 0, panelWidth, height, left);
            double pad = Draw.clamp(height * 0.06, 5, 14);
            double x = left ? pad : panelWidth + pad;
            rows(g, plan, flow, x, pad * 0.3, width - panelWidth - 2 * pad, height - pad * 0.8, 1);
        } else {
            double h = Draw.clamp(height * 0.15, 26, 64);
            band(g, plan, width, h, left, false);
            double pad = Draw.clamp(height * 0.05, 6, 18);
            rows(g, plan, flow, pad, h + pad * 0.4, width - 2 * pad, height - h - pad * 1.2, 1);
        }
    }

    private static void snake(Graphics2D g, Plan plan, Flow flow, int width, int height) {
        double h = snakeBand(width, height);
        band(g, plan, width, h, flow == Flow.LEFT, false);
        double pad = Draw.clamp(Math.min(width, height) * 0.05, 6, 20);
        rows(g, plan, flow, pad, h + pad * 0.4, width - 2 * pad, height - h - pad * 1.1, bestRows(g, plan, flow, width, height));
    }

    private static double snakeBand(int width, int height) {
        return Draw.clamp(Math.min(height * 0.13, width * 0.09), 24, 56);
    }

    /**
     * How many rows to fold the line into: the fewest that still give names of a good size (14
     * pixels, or three quarters of the largest any count gives), so a wall shows a long line
     * rather than a zigzag; a count that leaves a stop or two alone on the last row counts less.
     */
    private static int bestRows(Graphics2D g, Plan plan, Flow flow, int width, int height) {
        double pad = Draw.clamp(Math.min(width, height) * 0.05, 6, 20);
        double w = width - 2 * pad;
        double bodyHeight = height - snakeBand(width, height) - pad * 1.1;
        int counts = Math.min(6, Math.max(1, plan.marks().size()));
        double[] score = new double[counts + 1];
        double best = 0;
        for (int rows = 1; rows <= counts; rows++) {
            Geometry geometry = geometry(g, plan, flow, pad, w, bodyHeight, rows);
            int last = plan.marks().size() - (rows - 1) * geometry.perRow();
            score[rows] = geometry.nameSize() * (rows > 1 && last * 2 < geometry.perRow() ? 0.8 : 1);
            best = Math.max(best, score[rows]);
        }
        for (int rows = 1; rows <= counts; rows++) {
            if (score[rows] >= Math.min(14, best * 0.75)) {
                return rows;
            }
        }
        return 1;
    }

    /**
     * Where everything goes in a layout of so many rows: each stop's position along its row, the
     * room its name may take (out to the half-way points to its neighbours, or the sign's edge),
     * and the one size all names are set in.
     */
    record Geometry(int rows, int perRow, double pitch, double thickness, double ring, double spacing, double turn,
                    double[] px, int[] row, boolean[] rightward, double[] nameLeft, double[] nameRight,
                    double nameSize, double lineFraction) {
    }

    static Geometry geometry(Graphics2D g, Plan plan, Flow flow, double x, double width, double height, int rows) {
        List<Mark> marks = plan.marks();
        int n = Math.max(1, marks.size());
        int perRow = (int) Math.ceil((double) n / rows);
        double pitch = height / rows;
        double thickness = Draw.clamp(pitch * 0.075, 4, 13);
        double spacing = 0;
        double turn = 0;
        double side = 0;
        for (int pass = 0; pass < 2; pass++) {
            double ring = thickness * 0.9;
            turn = rows > 1 ? Math.min(pitch / 2, Math.max(thickness * 2.2, 12)) : 0;
            side = ring + thickness * 0.8 + turn + (plan.loop() ? thickness * 2.4 : 0);
            double usable = Math.max(1, width - 2 * side);
            spacing = perRow > 1 ? usable / (perRow - 1) : usable;
            thickness = Math.min(thickness, Draw.clamp(spacing * 0.13, 4, 13));
        }
        double ring = thickness * 0.9;
        double[] px = new double[marks.size()];
        int[] rowOf = new int[marks.size()];
        boolean[] rightward = new boolean[marks.size()];
        for (int index = 0; index < marks.size(); index++) {
            int row = index / perRow;
            int column = index % perRow;
            boolean toRight = (row % 2 == 0) == (flow == Flow.RIGHT);
            px[index] = perRow == 1 ? x + width / 2 : (toRight ? x + side : x + width - side) + (toRight ? 1 : -1) * column * spacing;
            rowOf[index] = row;
            rightward[index] = toRight;
        }
        // A name may reach the half-way point to the next stop on its row, or the sign's edge; on
        // a row after the first, the bend down from the row above runs through the names at the
        // end the row starts from, so names stop short of it there.
        double bend = side - ring - thickness * 0.8 + thickness;
        double[] nameLeft = new double[marks.size()];
        double[] nameRight = new double[marks.size()];
        for (int index = 0; index < marks.size(); index++) {
            boolean entered = rowOf[index] > 0;
            double minX = entered && rightward[index] ? x + bend : x;
            double maxX = entered && !rightward[index] ? x + width - bend : x + width;
            double leftNeighbour = Double.NaN;
            double rightNeighbour = Double.NaN;
            for (int other = 0; other < marks.size(); other++) {
                if (other == index || rowOf[other] != rowOf[index]) {
                    continue;
                }
                if (px[other] < px[index] && (Double.isNaN(leftNeighbour) || px[other] > leftNeighbour)) {
                    leftNeighbour = px[other];
                }
                if (px[other] > px[index] && (Double.isNaN(rightNeighbour) || px[other] < rightNeighbour)) {
                    rightNeighbour = px[other];
                }
            }
            double gap = spacing * 0.03;
            nameLeft[index] = Double.isNaN(leftNeighbour) ? minX : (leftNeighbour + px[index]) / 2 + gap;
            nameRight[index] = Double.isNaN(rightNeighbour) ? maxX : (rightNeighbour + px[index]) / 2 - gap;
        }
        double lineFraction = rows == 1 ? 0.6 : 0.56;
        double zone = pitch * lineFraction - ring - thickness * 0.6;
        double cap = rows == 1 ? 30 : 24;
        double oneLine = Math.min(zone * 0.46, cap);
        double twoLines = Math.min(zone * 0.34, cap * 0.85);
        for (int index = 0; index < marks.size(); index++) {
            String name = marks.get(index).name();
            double room = nameRight[index] - nameLeft[index];
            oneLine = Math.min(oneLine, Draw.fit(g, Fonts.BOLD, name, room, oneLine));
            twoLines = Math.min(twoLines, Draw.fitWrapped(g, Fonts.BOLD, name, room, 2, twoLines, 4));
        }
        return new Geometry(rows, perRow, pitch, thickness, ring, spacing, turn, px, rowOf, rightward, nameLeft, nameRight,
                Math.max(oneLine, twoLines), lineFraction);
    }

    /** The line folded into rows across the box, each row turning back at the end of the one before. */
    private static void rows(Graphics2D g, Plan plan, Flow flow, double x, double y, double width, double height, int rows) {
        List<Mark> marks = plan.marks();
        int n = marks.size();
        if (n == 0) {
            return;
        }
        Geometry geometry = geometry(g, plan, flow, x, width, height, rows);
        double t = geometry.thickness();
        double ring = geometry.ring();
        double[] px = geometry.px();
        double[] py = new double[n];
        for (int index = 0; index < n; index++) {
            py[index] = y + geometry.row()[index] * geometry.pitch() + geometry.pitch() * geometry.lineFraction();
        }
        boolean[] rightward = geometry.rightward();
        Color colour = new Color(plan.color());
        double bullet = Draw.clamp(Math.min(t * 1.7, geometry.pitch() * 0.13), 10, 22);
        double tagHeight = Draw.clamp(t * 1.7, 14, 22);

        // The line: each stretch coloured by whether the train has already run it.
        g.setStroke(new BasicStroke((float) t, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        for (int index = 0; index + 1 < n; index++) {
            boolean passed = marks.get(index + 1).state() == State.PAST || marks.get(index + 1).state() == State.HERE;
            g.setColor(passed ? PAST_LINE : colour);
            if (py[index] == py[index + 1]) {
                g.draw(new Line2D.Double(px[index], py[index], px[index + 1], py[index + 1]));
                if (!passed && Math.abs(px[index + 1] - px[index]) > t * 4) {
                    chevron(g, (px[index] + px[index + 1]) / 2, py[index], t, !rightward[index]);
                }
            } else {
                turn(g, px[index], py[index], px[index + 1], py[index + 1], rightward[index], geometry.turn(), ring + t * 0.8);
            }
        }
        if (plan.loop()) {
            loopEnds(g, plan, geometry, px, py, x, width, colour, bullet);
        }
        // Where "You are here" goes: under the stop, or under the whole row's bullets when it is wider than a stop.
        double[] rowBottom = new double[geometry.rows()];
        for (int index = 0; index < n; index++) {
            double bottom = py[index] + ring + t * 0.5 + (marks.get(index).connections().isEmpty() ? 0 : bullet + t * 0.4);
            rowBottom[geometry.row()[index]] = Math.max(rowBottom[geometry.row()[index]], bottom);
        }
        Font regular = Draw.of(Fonts.SEMIBOLD, geometry.nameSize());
        Font bold = Draw.of(Fonts.BOLD, geometry.nameSize());
        for (int index = 0; index < n; index++) {
            Mark mark = marks.get(index);
            Font font = mark.state() == State.HERE || mark.terminus() ? bold : regular;
            Color ink = mark.state() == State.PAST || mark.pass() ? PAST_INK : INK;
            double top = py[index] - ring - t * 0.55;
            stacked(g, mark.name(), px[index], top, geometry.nameLeft()[index], geometry.nameRight()[index], font, ink);
            stopMark(g, mark, px[index], py[index], t);
            double below = py[index] + ring + t * 0.5;
            if (!mark.connections().isEmpty()) {
                below = badges(g, mark.connections(), px[index], below, bullet, geometry.nameLeft()[index],
                        geometry.nameRight()[index]) + t * 0.4;
            }
            if (mark.state() == State.HERE) {
                double tagTop = tagWidth(g, tagHeight) > geometry.nameRight()[index] - geometry.nameLeft()[index]
                        ? Math.max(below, rowBottom[geometry.row()[index]]) : below;
                hereTag(g, px[index], tagTop + tagHeight * 0.3, tagHeight, x, x + width);
            }
        }
    }

    /** A loop runs on past its last stop with an arrow, and comes in again before its first. */
    private static void loopEnds(Graphics2D g, Plan plan, Geometry geometry, double[] px, double[] py, double x, double width,
                                 Color colour, double bullet) {
        double t = geometry.thickness();
        double ring = geometry.ring();
        int last = px.length - 1;
        boolean right = geometry.rightward()[last];
        double dir = right ? 1 : -1;
        double tip = px[last] + dir * (ring + t * 3.6);
        g.setColor(colour);
        g.draw(new Line2D.Double(px[last], py[last], tip - dir * t * 1.6, py[last]));
        Draw.arrow(g, tip, py[last], t * 2.4, t * 2.4, !right, colour);
        boolean firstRight = geometry.rightward()[0];
        double stub = px[0] - (firstRight ? 1 : -1) * (ring + t * 2.4);
        g.draw(new Line2D.Double(stub, py[0], px[0], py[0]));
        if (plan.loopsBackTo() == null) {
            return;
        }
        // "then <first stop>", under the arrow, in the room past the last stop's bullets.
        double from = right ? px[last] + ring + bullet * 0.5 : x;
        double to = right ? x + width : px[last] - ring - bullet * 0.5;
        double room = to - from;
        String text = "then " + plan.loopsBackTo();
        double size = Draw.fitWrapped(g, Fonts.SEMIBOLD, text, room, 2, Draw.clamp(t * 1.1, 8, 14), 6);
        Font font = Draw.of(Fonts.SEMIBOLD, size);
        List<String> lines = Draw.wrap(g, font, text, room, 2);
        if (lines == null || size < 6.5) {
            return;
        }
        double cap = Draw.cap(g, font);
        double baseline = py[last] + t * 1.6 + cap;
        for (String line : lines) {
            double w = Draw.width(g, font, line);
            double left = right ? Math.max(from, Math.min(tip - w, to - w)) : Math.max(from, Math.min(tip, to - w));
            Draw.text(g, line, left, baseline, font, PAST_INK);
            baseline += cap * 1.45;
        }
    }

    /** The column's measures: band height, padding, line thickness, first stop, the step between, the names. */
    private record ColumnGeometry(double band, double pad, double thickness, double top, double step, double textX,
                                  double textWidth, double size) {
    }

    private static ColumnGeometry columnGeometry(Graphics2D g, Plan plan, int width, int height) {
        double h = Draw.clamp(Math.min(height * 0.12, width * 0.11), 24, 56);
        int n = Math.max(1, plan.marks().size());
        double pad = Draw.clamp(Math.min(width, height) * 0.06, 6, 18);
        double t = Draw.clamp(Math.min(width * 0.05, height * 0.035), 5, 14);
        double ring = t * 0.9;
        double lineX = pad + ring + t * 0.3;
        double top = h + pad + ring;
        double bottom = height - pad - ring - (plan.loop() ? t * 3 : 0);
        double step = n > 1 ? (bottom - top) / (n - 1) : 0;
        double textX = lineX + ring + t * 0.9;
        double textWidth = width - textX - pad;
        double size = Math.min(step * 0.42, 24);
        for (Mark mark : plan.marks()) {
            // Room is kept after each name for the other lines' bullets.
            size = Math.min(size, Draw.fitWrapped(g, Fonts.BOLD, mark.name(), textWidth * 0.8, step > size * 3 ? 2 : 1, size, 5));
        }
        return new ColumnGeometry(h, pad, t, top, step, textX, textWidth, size);
    }

    /** A sign taller than wide: the line downwards on the left, the names beside it. */
    private static void column(Graphics2D g, Plan plan, int width, int height) {
        ColumnGeometry geometry = columnGeometry(g, plan, width, height);
        double h = geometry.band();
        band(g, plan, width, h, false, true);
        List<Mark> marks = plan.marks();
        int n = marks.size();
        if (n == 0) {
            return;
        }
        double pad = geometry.pad();
        double t = geometry.thickness();
        double ring = t * 0.9;
        double lineX = pad + ring + t * 0.3;
        double top = geometry.top();
        double step = geometry.step();
        double textX = geometry.textX();
        double textWidth = geometry.textWidth();
        double size = geometry.size();
        Color colour = new Color(plan.color());
        g.setStroke(new BasicStroke((float) t, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        for (int index = 0; index + 1 < n; index++) {
            boolean passed = marks.get(index + 1).state() == State.PAST || marks.get(index + 1).state() == State.HERE;
            double y0 = top + index * step;
            g.setColor(passed ? PAST_LINE : colour);
            g.draw(new Line2D.Double(lineX, y0, lineX, y0 + step));
            if (!passed && step > t * 4) {
                downChevron(g, lineX, y0 + step / 2, t);
            }
        }
        if (plan.loop()) {
            double y0 = top + (n - 1) * step;
            g.setColor(colour);
            g.draw(new Line2D.Double(lineX, y0, lineX, y0 + t * 1.6));
            downArrow(g, lineX, y0 + t * 2.6, t * 2.2, t * 2, colour);
        }
        Font regular = Draw.of(Fonts.SEMIBOLD, size);
        Font bold = Draw.of(Fonts.BOLD, size);
        for (int index = 0; index < n; index++) {
            Mark mark = marks.get(index);
            double yy = top + index * step;
            stopMark(g, mark, lineX, yy, t);
            Font font = mark.state() == State.HERE || mark.terminus() ? bold : regular;
            Color ink = mark.state() == State.PAST || mark.pass() ? PAST_INK : INK;
            List<String> lines = Draw.wrap(g, font, mark.name(), textWidth, 2);
            if (lines == null) {
                lines = List.of(mark.name());
            }
            double cap = Draw.cap(g, font);
            double lineHeight = cap * 1.45;
            double first = yy + cap / 2 - (lines.size() - 1) * lineHeight / 2;
            double after = textX;
            for (int line = 0; line < lines.size(); line++) {
                Draw.text(g, lines.get(line), textX, first + line * lineHeight, font, ink);
                if (line == lines.size() - 1) {
                    after = textX + Draw.width(g, font, lines.get(line));
                }
            }
            double bulletSize = Draw.clamp(cap * 1.35, 10, 20);
            double bx = after + cap * 0.6;
            double by = first + (lines.size() - 1) * lineHeight - cap / 2;
            for (Badge badge : mark.connections()) {
                if (bx + bulletSize > width - pad) {
                    break;
                }
                bx += Draw.bullet(g, badge.text(), badge.color(), bx, by, bulletSize) + bulletSize * 0.18;
            }
            if (mark.state() == State.HERE) {
                double tag = Draw.clamp(cap * 1.7, 14, 20);
                double tagWidth = tagWidth(g, tag);
                boolean beside = bx + cap * 0.3 + tagWidth <= width - pad;
                tag(g, beside ? bx + cap * 0.3 : textX, beside ? by - tag / 2 : first + (lines.size() - 1) * lineHeight + cap * 0.7, tag);
            }
        }
    }

    // ---------------------------------------------------------------- pieces

    private static void stopMark(Graphics2D g, Mark mark, double x, double y, double t) {
        boolean past = mark.state() == State.PAST;
        if (mark.pass()) {
            g.setColor(Color.WHITE);
            g.fill(new Rectangle2D.Double(x - t * 0.14, y - t * 0.32, t * 0.28, t * 0.64));
            return;
        }
        if (mark.big()) {
            double r = t * 0.9;
            g.setColor(Color.WHITE);
            g.fill(new Ellipse2D.Double(x - r, y - r, 2 * r, 2 * r));
            g.setColor(past ? PAST_INK : INK);
            g.setStroke(new BasicStroke((float) (t * 0.26)));
            g.draw(new Ellipse2D.Double(x - r, y - r, 2 * r, 2 * r));
        } else {
            double r = t * 0.34;
            g.setColor(Color.WHITE);
            g.fill(new Ellipse2D.Double(x - r, y - r, 2 * r, 2 * r));
        }
        if (mark.state() == State.HERE) {
            double r = t * 1.45;
            g.setColor(RED);
            g.setStroke(new BasicStroke((float) Math.max(1.5, t * 0.3)));
            g.draw(new Ellipse2D.Double(x - r, y - r, 2 * r, 2 * r));
        }
        g.setStroke(new BasicStroke((float) t, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
    }

    /** A name set above its stop, centred where it can be, on one or two even lines, within its room. */
    private static void stacked(Graphics2D g, String name, double x, double bottom, double minX, double maxX, Font font, Color ink) {
        List<String> lines = Draw.wrap(g, font, name, maxX - minX, 2);
        if (lines == null) {
            lines = List.of(name);
        }
        if (lines.size() > 1 && Draw.width(g, font, name) <= maxX - minX) {
            lines = List.of(name);
        }
        double cap = Draw.cap(g, font);
        double lineHeight = cap * 1.42;
        double baseline = bottom - (lines.size() - 1) * lineHeight;
        for (String line : lines) {
            double w = Draw.width(g, font, line);
            double left = Math.max(minX, Math.min(x - w / 2, maxX - w));
            Draw.text(g, line, left, baseline, font, ink);
            baseline += lineHeight;
        }
    }

    /** The other lines at a stop, as small bullets under it; two rows when one is too wide. Returns the bottom. */
    private static double badges(Graphics2D g, List<Badge> badges, double x, double top, double d, double minX, double maxX) {
        double gap = d * 0.16;
        int perRow = Math.max(1, (int) Math.floor((maxX - minX + gap) / (d + gap)));
        double y = top + d / 2;
        for (int start = 0; start < badges.size(); start += perRow) {
            List<Badge> row = badges.subList(start, Math.min(badges.size(), start + perRow));
            double total = 0;
            for (Badge badge : row) {
                total += Draw.bulletWidth(g, badge.text(), d) + gap;
            }
            total -= gap;
            double bx = Math.max(minX, Math.min(x - total / 2, maxX - total));
            for (Badge badge : row) {
                bx += Draw.bullet(g, badge.text(), badge.color(), bx, y, d) + gap;
            }
            y += d + gap;
        }
        return y - d / 2 - gap;
    }

    private static double tagWidth(Graphics2D g, double height) {
        return Draw.width(g, Draw.of(Fonts.BOLD, height * 0.6), "You are here") + height * 0.8;
    }

    private static void hereTag(Graphics2D g, double x, double top, double height, double minX, double maxX) {
        double width = tagWidth(g, height);
        double left = Math.max(minX, Math.min(x - width / 2, maxX - width));
        Path2D.Double pointer = new Path2D.Double();
        double tipX = Math.max(left + height * 0.4, Math.min(x, left + width - height * 0.4));
        pointer.moveTo(tipX - height * 0.3, top + 1);
        pointer.lineTo(tipX, top - height * 0.32);
        pointer.lineTo(tipX + height * 0.3, top + 1);
        pointer.closePath();
        g.setColor(RED);
        g.fill(pointer);
        tag(g, left, top, height);
    }

    private static void tag(Graphics2D g, double left, double top, double height) {
        double width = tagWidth(g, height);
        g.setColor(RED);
        g.fill(new RoundRectangle2D.Double(left, top, width, height, height * 0.35, height * 0.35));
        Draw.textCentred(g, "You are here", left + width / 2, top + height / 2, Draw.of(Fonts.BOLD, height * 0.6), Color.WHITE);
    }

    /** A white chevron inside the band pointing the way the train runs. */
    private static void chevron(Graphics2D g, double x, double y, double t, boolean left) {
        double s = t * 0.32;
        double dir = left ? -1 : 1;
        Path2D.Double path = new Path2D.Double();
        path.moveTo(x - dir * s, y - s * 1.1);
        path.lineTo(x + dir * s * 0.9, y);
        path.lineTo(x - dir * s, y + s * 1.1);
        g.setColor(Color.WHITE);
        java.awt.Stroke saved = g.getStroke();
        g.setStroke(new BasicStroke((float) Math.max(1.2, t * 0.2), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.draw(path);
        g.setStroke(saved);
    }

    private static void downChevron(Graphics2D g, double x, double y, double t) {
        double s = t * 0.32;
        Path2D.Double path = new Path2D.Double();
        path.moveTo(x - s * 1.1, y - s);
        path.lineTo(x, y + s * 0.9);
        path.lineTo(x + s * 1.1, y - s);
        g.setColor(Color.WHITE);
        java.awt.Stroke saved = g.getStroke();
        g.setStroke(new BasicStroke((float) Math.max(1.2, t * 0.2), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.draw(path);
        g.setStroke(saved);
    }

    private static void downArrow(Graphics2D g, double x, double centreY, double length, double width, Color color) {
        Path2D.Double path = new Path2D.Double();
        double tip = centreY + length / 2;
        double head = length * 0.55;
        double shaft = width * 0.36;
        path.moveTo(x, tip);
        path.lineTo(x - width / 2, tip - head);
        path.lineTo(x - shaft / 2, tip - head);
        path.lineTo(x - shaft / 2, tip - length);
        path.lineTo(x + shaft / 2, tip - length);
        path.lineTo(x + shaft / 2, tip - head);
        path.lineTo(x + width / 2, tip - head);
        path.closePath();
        g.setColor(color);
        g.fill(path);
    }

    /**
     * The bend from the end of one row to the start of the next: out past the last stop, a rounded
     * corner down, straight down, a rounded corner back in.
     */
    private static void turn(Graphics2D g, double x0, double y0, double x1, double y1, boolean rightward, double radius, double out) {
        double r = Math.min(radius, Math.abs(y1 - y0) / 2);
        Path2D.Double path = new Path2D.Double();
        path.moveTo(x0, y0);
        if (rightward) {
            double edge = Math.max(x0, x1) + out;
            path.lineTo(edge - r, y0);
            path.append(new Arc2D.Double(edge - 2 * r, y0, 2 * r, 2 * r, 90, -90, Arc2D.OPEN), true);
            path.lineTo(edge, y1 - r);
            path.append(new Arc2D.Double(edge - 2 * r, y1 - 2 * r, 2 * r, 2 * r, 0, -90, Arc2D.OPEN), true);
        } else {
            double edge = Math.min(x0, x1) - out;
            path.lineTo(edge + r, y0);
            path.append(new Arc2D.Double(edge, y0, 2 * r, 2 * r, 90, 90, Arc2D.OPEN), true);
            path.lineTo(edge, y1 - r);
            path.append(new Arc2D.Double(edge, y1 - 2 * r, 2 * r, 2 * r, 180, 90, Arc2D.OPEN), true);
        }
        path.lineTo(x1, y1);
        g.draw(path);
    }
}
