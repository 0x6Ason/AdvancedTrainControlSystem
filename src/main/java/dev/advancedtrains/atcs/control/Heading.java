package dev.advancedtrains.atcs.control;

import org.jspecify.annotations.Nullable;

import java.util.Locale;

/** Where a train is sent from a station or a junction, in the words TrainCarts uses on its signs. */
public enum Heading {
    CONTINUE("continue", "forward", "straight", "c", "f"),
    REVERSE("reverse", "back", "backward", "b"),
    LEFT("left", "l"),
    RIGHT("right", "r"),
    NORTH("north", "n"),
    EAST("east", "e"),
    SOUTH("south", "s"),
    WEST("west", "w");

    private final String[] words;

    Heading(String... words) {
        this.words = words;
    }

    /** The word TrainCarts understands, for example {@code continue}. */
    public String word() {
        return words[0];
    }

    public static @Nullable Heading parse(String word) {
        String wanted = word.strip().toLowerCase(Locale.ROOT);
        for (Heading heading : values()) {
            for (String alias : heading.words) {
                if (alias.equals(wanted)) {
                    return heading;
                }
            }
        }
        return null;
    }
}
