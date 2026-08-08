package dev.advancedtrains.atcs.hud;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

import java.util.Objects;

/**
 * Renders a compact continuous status bar above the player's hotbar.
 */
public final class PassengerHudRenderer {
    public Component render(PassengerHudSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        Component separator = Component.text("  ◆  ", NamedTextColor.DARK_GRAY);
        return Component.text()
                .append(Component.text(snapshot.line(), NamedTextColor.GOLD))
                .append(Component.text(" " + snapshot.serviceType(), NamedTextColor.YELLOW))
                .append(separator)
                .append(Component.text("To: ", NamedTextColor.GRAY))
                .append(Component.text(snapshot.destination(), NamedTextColor.WHITE))
                .append(separator)
                .append(Component.text("Next: ", NamedTextColor.GRAY))
                .append(Component.text(snapshot.nextStation(), NamedTextColor.AQUA))
                .append(separator)
                .append(Component.text(snapshot.operationalStatus(), NamedTextColor.GREEN))
                .build();
    }
}

