package dev.advancedtrains.atcs.command;

import dev.advancedtrains.atcs.network.Network;
import dev.advancedtrains.atcs.network.Service;
import dev.advancedtrains.atcs.network.ServiceKey;
import dev.advancedtrains.atcs.operation.EtaSamples;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.CommandSender;

import java.util.List;
import java.util.Locale;

import static dev.advancedtrains.atcs.command.Node.group;
import static dev.advancedtrains.atcs.command.Node.leaf;
import static dev.advancedtrains.atcs.command.Support.MANAGE;

/** {@code /atcs timing ...}: how good the arrival estimates are. */
final class TimingCommands {
    private static final int[] SHOWN = {120, 60, 30};

    private TimingCommands() {
    }

    static Node build(Support support) {
        return group("timing", MANAGE, "How good the arrival estimates are, measured against real arrivals",
                leaf("report", MANAGE, "[line/service]", "Mean and worst estimate errors per service, or per stop of one service",
                        context -> report(support, context), (context, index) -> index == 0 ? support.serviceKeys() : List.of()),
                leaf("reset", MANAGE, "", "Forget the scored samples and start measuring afresh", context -> {
                    support.runtime.timing().clear();
                    Chat.success(context.sender(), "Estimate samples cleared");
                }));
    }

    private static void report(Support support, CommandContext context) throws CommandError {
        EtaSamples samples = support.runtime.timing();
        Network network = support.network();
        CommandSender sender = context.sender();
        if (context.size() == 0) {
            sender.sendMessage(Chat.header("Arrival estimates"));
            List<ServiceKey> services = samples.services();
            if (services.isEmpty()) {
                sender.sendMessage(Component.text("No arrivals scored yet; estimates are sampled while trains run.", NamedTextColor.GRAY));
                return;
            }
            sender.sendMessage(Component.text("Mean error of the estimate shown 2 min, 1 min and 30 s before arrival"
                    + " (+ late, - early), over " + samples.sampleCount() + " samples.", NamedTextColor.GRAY));
            for (ServiceKey key : services) {
                sender.sendMessage(Component.text()
                        .append(Component.text(key + ": ", NamedTextColor.WHITE))
                        .append(Component.text(horizons(samples, key, -1), NamedTextColor.GRAY)).build());
            }
            sender.sendMessage(Component.text("/atcs timing report <line/service> shows each stop.", NamedTextColor.DARK_GRAY));
            return;
        }
        Service service = support.service(context.arg(0));
        sender.sendMessage(Chat.header("Arrival estimates: " + service.key()));
        List<Integer> stops = samples.stops(service.key());
        if (stops.isEmpty()) {
            sender.sendMessage(Component.text("No arrivals scored yet for this service.", NamedTextColor.GRAY));
            return;
        }
        for (int index : stops) {
            String name = index < service.size() ? network.stationName(service.stop(index).stationId()) : "stop " + (index + 1);
            sender.sendMessage(Component.text()
                    .append(Component.text(name + ": ", NamedTextColor.WHITE))
                    .append(Component.text(horizons(samples, service.key(), index), NamedTextColor.GRAY)).build());
        }
        List<EtaSamples.Worst> worst = samples.worst(service.key());
        if (!worst.isEmpty()) {
            sender.sendMessage(Component.text("Worst:", NamedTextColor.GRAY));
            worst.stream().limit(5).forEach(entry -> {
                String name = entry.stopIndex() < service.size()
                        ? network.stationName(service.stop(entry.stopIndex()).stationId()) : "stop " + (entry.stopIndex() + 1);
                sender.sendMessage(Component.text("  " + name + " " + signed(entry.error()) + " on the " + horizon(entry.horizon())
                        + " estimate, " + entry.at().toString().substring(0, 16).replace('T', ' '), NamedTextColor.GOLD));
            });
        }
    }

    /** "2 min ±9 s (bias +3 s), 1 min ±5 s, 30 s ±2 s; n=12, worst 31 s" for a stop, or over a service with -1. */
    private static String horizons(EtaSamples samples, ServiceKey key, int stopIndex) {
        StringBuilder text = new StringBuilder();
        int count = 0;
        double worst = 0;
        for (int horizon : SHOWN) {
            EtaSamples.Stat stat = stopIndex < 0 ? samples.overall(key, horizon) : samples.stat(key, stopIndex, horizon);
            if (stat.count() == 0) {
                continue;
            }
            if (!text.isEmpty()) {
                text.append(", ");
            }
            text.append(horizon(horizon)).append(" ±").append(Math.round(stat.meanAbs())).append(" s");
            if (horizon == SHOWN[0] && Math.abs(stat.bias()) >= 2) {
                text.append(" (bias ").append(signed(stat.bias())).append(")");
            }
            count = Math.max(count, stat.count());
            worst = Math.max(worst, stat.maxAbs());
        }
        if (text.isEmpty()) {
            return "no samples at these horizons";
        }
        return text + "; n=" + count + ", worst " + Math.round(worst) + " s";
    }

    private static String horizon(int seconds) {
        return seconds >= 60 ? (seconds / 60) + " min" : seconds + " s";
    }

    private static String signed(double seconds) {
        return String.format(Locale.ROOT, "%+d s", Math.round(seconds));
    }
}
