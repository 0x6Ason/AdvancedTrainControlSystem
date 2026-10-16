package dev.advancedtrains.atcs.api;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

import java.util.ArrayList;
import java.util.List;

/**
 * Fired whenever ATCS works a fare out for another plugin, before it is returned. A listener may
 * change the fare (a discount, a daily cap) with {@link #setFare}; the website and
 * {@code /atcs fare check} show the configured fare and do not fire this event.
 */
public class FareQuoteEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();

    private final String from;
    private final String to;
    private final String currency;
    private final List<String> reasoning;
    private double fare;
    private String layer;

    public FareQuoteEvent(String from, String to, double fare, String currency, String layer, List<String> reasoning) {
        this.from = from;
        this.to = to;
        this.fare = fare;
        this.currency = currency;
        this.layer = layer;
        this.reasoning = new ArrayList<>(reasoning);
    }

    public String getFrom() {
        return from;
    }

    public String getTo() {
        return to;
    }

    public String getCurrency() {
        return currency;
    }

    public double getFare() {
        return fare;
    }

    /** The layer that produced the fare; {@code plugin} once a listener changed it. */
    public String getLayer() {
        return layer;
    }

    public List<String> getReasoning() {
        return List.copyOf(reasoning);
    }

    /** Replaces the fare, noting who did so and why in the reasoning. */
    public void setFare(double newFare, String reason) {
        fare = newFare;
        layer = "plugin";
        reasoning.add(reason);
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
