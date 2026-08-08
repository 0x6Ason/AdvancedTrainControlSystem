package dev.advancedtrains.atcs.legacy;

import java.util.Locale;

public enum LegacySignType {
    STATION,
    SPAWN,
    ANNOUNCE,
    SOUND,
    DESTINATION,
    SWITCHER,
    BLOCKER,
    DETECTOR,
    PROPERTY,
    LAUNCH,
    WAIT,
    MUTEX,
    RECEIVER,
    OTHER;

    public static LegacySignType fromAction(String action) {
        return switch (action.toLowerCase(Locale.ROOT)) {
            case "station" -> STATION;
            case "spawn" -> SPAWN;
            case "announce" -> ANNOUNCE;
            case "sound" -> SOUND;
            case "destination" -> DESTINATION;
            case "switcher" -> SWITCHER;
            case "blocker" -> BLOCKER;
            case "detector" -> DETECTOR;
            case "property" -> PROPERTY;
            case "launch", "launcher" -> LAUNCH;
            case "wait", "waiter" -> WAIT;
            case "mutex", "smartmutex", "pathmutex" -> MUTEX;
            case "railrcv" -> RECEIVER;
            default -> OTHER;
        };
    }
}

