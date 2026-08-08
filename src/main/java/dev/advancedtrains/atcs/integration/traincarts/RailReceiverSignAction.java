package dev.advancedtrains.atcs.integration.traincarts;

import com.bergerkiller.bukkit.tc.events.SignActionEvent;
import com.bergerkiller.bukkit.tc.events.SignChangeActionEvent;
import com.bergerkiller.bukkit.tc.signactions.SignActionType;
import com.bergerkiller.bukkit.tc.signactions.TrainCartsSignAction;
import com.bergerkiller.bukkit.tc.utils.SignBuildOptions;
import dev.advancedtrains.atcs.receiver.ReceiverEventService;
import dev.advancedtrains.atcs.receiver.ReceiverReference;

import java.util.Locale;
import java.util.Objects;

/**
 * Implements the standardized TrainCarts railrcv platform receiver sign.
 */
final class RailReceiverSignAction extends TrainCartsSignAction {
    private final ReceiverEventService receiverEvents;

    RailReceiverSignAction(ReceiverEventService receiverEvents) {
        super("railrcv");
        this.receiverEvents = Objects.requireNonNull(receiverEvents, "receiverEvents");
    }

    @Override
    public void execute(SignActionEvent event) {
        if (!event.isTrainSign() || !event.hasGroup()) {
            return;
        }
        ReceiverReference receiver;
        try {
            receiver = ReceiverReference.parse(event.getLine(2));
        } catch (IllegalArgumentException exception) {
            return;
        }

        String trainName = event.getGroup().getProperties().getTrainName();
        if (event.isAction(SignActionType.GROUP_ENTER)) {
            receiverEvents.onEnter(receiver, trainName);
        } else if (event.isAction(SignActionType.GROUP_LEAVE)) {
            receiverEvents.onLeave(receiver, trainName);
        }
    }

    @Override
    public boolean build(SignChangeActionEvent event) {
        if (!event.isType("railrcv")) {
            return false;
        }
        try {
            ReceiverReference.parse(event.getLine(2));
            if (!"platform".equals(event.getLine(3).strip().toLowerCase(Locale.ROOT))) {
                throw new IllegalArgumentException("Line 4 must be 'platform'");
            }
        } catch (IllegalArgumentException exception) {
            event.getPlayer().sendMessage("[ATCS] Invalid rail receiver: " + exception.getMessage());
            event.setCancelled(true);
            return true;
        }

        return SignBuildOptions.create()
                .setPermission("atcs.receiver.build")
                .setName("ATCS platform receiver")
                .setDescription("publish authoritative platform train events")
                .handle(event);
    }
}

