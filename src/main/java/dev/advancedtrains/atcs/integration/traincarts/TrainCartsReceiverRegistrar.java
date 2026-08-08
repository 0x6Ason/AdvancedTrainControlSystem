package dev.advancedtrains.atcs.integration.traincarts;

import com.bergerkiller.bukkit.tc.signactions.SignAction;
import dev.advancedtrains.atcs.receiver.ReceiverEventService;
import dev.advancedtrains.atcs.receiver.ReceiverRegistrar;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

public final class TrainCartsReceiverRegistrar implements ReceiverRegistrar {
    private final RailReceiverSignAction signAction;
    private final AtomicBoolean registered = new AtomicBoolean();

    public TrainCartsReceiverRegistrar(ReceiverEventService receiverEvents) {
        this.signAction = new RailReceiverSignAction(Objects.requireNonNull(receiverEvents, "receiverEvents"));
        SignAction.register(signAction);
        registered.set(true);
    }

    @Override
    public boolean registered() {
        return registered.get();
    }

    @Override
    public String statusMessage() {
        return registered() ? "railrcv is registered" : "railrcv is not registered";
    }

    @Override
    public void close() {
        if (registered.compareAndSet(true, false)) {
            SignAction.unregister(signAction);
        }
    }
}

