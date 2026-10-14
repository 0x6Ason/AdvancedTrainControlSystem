package dev.advancedtrains.atcs.network;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Short station codes for sign variables, so a variable fits on a sign line: {@code %gb.1t%} is
 * the next train's time at Golden Beach. A station may set its code; otherwise one is derived
 * from its id: the initials of a multi-word id ({@code golden-beach} is {@code gb}, numbers kept
 * whole, so {@code nerdy-estate-blk-1} is {@code neb1}), or a short single word as it is
 * ({@code main}) and a longer one cut to three letters ({@code liberty} is {@code lib}). Codes
 * that would clash are lengthened until every station has its own.
 */
public final class StationCodes {
    public static final int MAX_LENGTH = 8;
    private static final Pattern VALID = Pattern.compile("[a-z0-9]{1," + MAX_LENGTH + "}");

    private StationCodes() {
    }

    /** Lowercases and checks a code: letters and digits only, at most eight. */
    public static String normalize(String code) {
        String normalized = code.strip().toLowerCase(Locale.ROOT);
        if (!VALID.matcher(normalized).matches()) {
            throw new IllegalArgumentException("Invalid station code '" + code + "': use 1 to " + MAX_LENGTH
                    + " letters or digits, for example gb");
        }
        return normalized;
    }

    /** The code a station gets when it does not set one. */
    public static String derive(String id) {
        List<String> parts = new ArrayList<>();
        for (String part : id.split("[-_]+")) {
            if (!part.isEmpty()) {
                parts.add(part);
            }
        }
        if (parts.isEmpty()) {
            return "s";
        }
        String code;
        if (parts.size() == 1) {
            String word = parts.get(0);
            code = word.length() <= 4 ? word : word.substring(0, 3);
        } else {
            StringBuilder initials = new StringBuilder();
            for (String part : parts) {
                initials.append(part.chars().allMatch(Character::isDigit) ? part : part.substring(0, 1));
            }
            code = initials.toString();
        }
        return code.length() > MAX_LENGTH ? code.substring(0, MAX_LENGTH) : code;
    }

    /** Longer forms of a derived code, tried in order when it is taken. */
    private static List<String> alternatives(String id, String base) {
        List<String> result = new ArrayList<>();
        String letters = id.replaceAll("[^a-z0-9]", "");
        String[] parts = id.split("[-_]+");
        String last = parts[parts.length - 1];
        for (int extra = 1; extra < last.length(); extra++) {
            result.add(base + last.substring(1, 1 + extra));
        }
        for (int length = base.length() + 1; length <= letters.length(); length++) {
            result.add(letters.substring(0, length));
        }
        for (int number = 2; number < 100; number++) {
            result.add(base + number);
        }
        List<String> fitting = new ArrayList<>();
        for (String candidate : result) {
            if (candidate.length() <= MAX_LENGTH && VALID.matcher(candidate).matches()) {
                fitting.add(candidate);
            }
        }
        return fitting;
    }

    /**
     * Gives every station a code: stations that set one keep it (a clash is reported in
     * {@code problems} and the later station falls back to a derived code), then the others get
     * derived codes in id order.
     */
    public static Map<String, String> assign(Collection<Station> stations, List<String> problems) {
        Map<String, String> codes = new HashMap<>();
        Set<String> taken = new HashSet<>();
        List<Station> ordered = new ArrayList<>(stations);
        ordered.sort(Comparator.comparing(Station::id));
        for (Station station : ordered) {
            if (station.code() == null) {
                continue;
            }
            if (taken.add(station.code())) {
                codes.put(station.id(), station.code());
            } else {
                problems.add("Station " + station.id() + " uses the code '" + station.code()
                        + "' of another station; it gets a code of its own instead");
            }
        }
        for (Station station : ordered) {
            if (codes.containsKey(station.id())) {
                continue;
            }
            String code = derive(station.id());
            if (!taken.contains(code)) {
                taken.add(code);
                codes.put(station.id(), code);
                continue;
            }
            for (String candidate : alternatives(station.id(), code)) {
                if (taken.add(candidate)) {
                    codes.put(station.id(), candidate);
                    break;
                }
            }
        }
        return new LinkedHashMap<>(codes);
    }
}
