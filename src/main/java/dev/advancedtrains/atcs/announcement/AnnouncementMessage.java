package dev.advancedtrains.atcs.announcement;

import java.util.Objects;

public record AnnouncementMessage(String chat, String actionBar) {
    public AnnouncementMessage {
        Objects.requireNonNull(chat, "chat");
        Objects.requireNonNull(actionBar, "actionBar");
    }
}

