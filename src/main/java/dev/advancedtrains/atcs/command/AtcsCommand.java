package dev.advancedtrains.atcs.command;

import dev.advancedtrains.atcs.announcement.AnnouncementCatalog;
import dev.advancedtrains.atcs.announcement.AnnouncementMessage;
import dev.advancedtrains.atcs.announcement.AnnouncementType;
import dev.advancedtrains.atcs.discovery.InfrastructureDiscoveryCompletion;
import dev.advancedtrains.atcs.discovery.InfrastructureDiscoveryService;
import dev.advancedtrains.atcs.discovery.InfrastructureDiscoverySummary;
import dev.advancedtrains.atcs.discovery.RailDiscoveryCompletion;
import dev.advancedtrains.atcs.discovery.RailDiscoveryService;
import dev.advancedtrains.atcs.discovery.RailDiscoverySummary;
import dev.advancedtrains.atcs.integration.DependencyStatus;
import dev.advancedtrains.atcs.hud.PassengerHudService;
import dev.advancedtrains.atcs.legacy.LegacyInventorySummary;
import dev.advancedtrains.atcs.legacy.LegacyIssueCode;
import dev.advancedtrains.atcs.legacy.LegacyReviewIssue;
import dev.advancedtrains.atcs.legacy.LegacyReviewItem;
import dev.advancedtrains.atcs.legacy.LegacyReviewPage;
import dev.advancedtrains.atcs.legacy.LegacyReviewRepository;
import dev.advancedtrains.atcs.legacy.LegacyReviewStatus;
import dev.advancedtrains.atcs.legacy.LegacyReviewSummary;
import dev.advancedtrains.atcs.legacy.LegacyScanCompletion;
import dev.advancedtrains.atcs.legacy.LegacySignScanService;
import dev.advancedtrains.atcs.legacy.LegacySignType;
import dev.advancedtrains.atcs.receiver.ReceiverEventService;
import dev.advancedtrains.atcs.runtime.SystemRuntimeState;
import dev.advancedtrains.atcs.world.RailwayWorldPolicy;
import net.kyori.adventure.text.Component;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.sql.SQLException;
import java.util.logging.Level;

public final class AtcsCommand implements CommandExecutor, TabCompleter {
    private final Plugin plugin;
    private final SystemRuntimeState state;
    private final ReceiverEventService receiverEvents;
    private final AnnouncementCatalog announcements;
    private final LegacySignScanService signScanService;
    private final PassengerHudService passengerHudService;
    private final LegacyReviewRepository reviewRepository;
    private final InfrastructureDiscoveryService infrastructureDiscoveryService;
    private final RailDiscoveryService railDiscoveryService;
    private final String webStatus;
    private final RailwayWorldPolicy worldPolicy;

    public AtcsCommand(
            Plugin plugin,
            SystemRuntimeState state,
            ReceiverEventService receiverEvents,
            AnnouncementCatalog announcements,
            LegacySignScanService signScanService,
            PassengerHudService passengerHudService,
            LegacyReviewRepository reviewRepository,
            InfrastructureDiscoveryService infrastructureDiscoveryService,
            RailDiscoveryService railDiscoveryService,
            String webStatus,
            RailwayWorldPolicy worldPolicy) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.state = Objects.requireNonNull(state, "state");
        this.receiverEvents = Objects.requireNonNull(receiverEvents, "receiverEvents");
        this.announcements = Objects.requireNonNull(announcements, "announcements");
        this.signScanService = Objects.requireNonNull(signScanService, "signScanService");
        this.passengerHudService = Objects.requireNonNull(passengerHudService, "passengerHudService");
        this.reviewRepository = Objects.requireNonNull(reviewRepository, "reviewRepository");
        this.infrastructureDiscoveryService = Objects.requireNonNull(
                infrastructureDiscoveryService, "infrastructureDiscoveryService");
        this.railDiscoveryService = Objects.requireNonNull(railDiscoveryService, "railDiscoveryService");
        this.webStatus = Objects.requireNonNull(webStatus, "webStatus");
        this.worldPolicy = Objects.requireNonNull(worldPolicy, "worldPolicy");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] arguments) {
        if (arguments.length == 0 || "help".equalsIgnoreCase(arguments[0])) {
            sendHelp(sender, label);
            return true;
        }
        return switch (arguments[0].toLowerCase(Locale.ROOT)) {
            case "status" -> {
                sendStatus(sender);
                yield true;
            }
            case "announce" -> handleAnnouncement(sender, arguments);
            case "scan" -> handleScan(sender, arguments);
            case "review" -> handleReview(sender, arguments);
            case "discover" -> handleDiscovery(sender, arguments);
            default -> {
                sender.sendMessage("[ATCS] Unknown subcommand. Use /" + label + " help.");
                yield true;
            }
        };
    }

    private void sendHelp(CommandSender sender, String label) {
        sender.sendMessage("[ATCS] AdvancedTrainControlSystem commands:");
        sender.sendMessage("/" + label + " status - Show system and integration health.");
        sender.sendMessage("/" + label + " announce test <event> - Render a sample English announcement.");
        sender.sendMessage("/" + label + " scan loaded - Inventory TrainCarts signs in already loaded chunks.");
        sender.sendMessage("/" + label + " scan status - Show the most recent sign inventory summary.");
        sender.sendMessage("/" + label + " review status - Show the legacy review queue summary.");
        sender.sendMessage("/" + label + " review list [page] - List signs with configured review issues.");
        sender.sendMessage("/" + label + " review show <sign-id> - Show preserved sign evidence.");
        sender.sendMessage("/" + label + " review set <sign-id> <status> [note] - Record an audited review decision.");
        sender.sendMessage("/" + label + " discover candidates - Infer reviewable infrastructure candidates.");
        sender.sendMessage("/" + label + " discover rail - Inventory rails using the configured saved- or loaded-chunk scope.");
        sender.sendMessage("/" + label + " discover status - Show the latest discovery snapshots.");
        sender.sendMessage("/" + label + " help - Show this help.");
    }

    private void sendStatus(CommandSender sender) {
        sender.sendMessage("[ATCS] AdvancedTrainControlSystem " + plugin.getPluginMeta().getVersion());
        sender.sendMessage("Configured mode: " + state.configuredMode());
        sender.sendMessage("Control writes: " + (state.controlWritesEnabled() ? "ENABLED" : "DISABLED"));
        sender.sendMessage("Database: " + (state.databaseHealthy() ? "HEALTHY" : "UNHEALTHY")
                + " (schema V" + state.schemaVersion() + ")");
        sender.sendMessage("Database path: " + state.databasePath());
        sender.sendMessage(formatDependency(state.trainCarts()));
        sender.sendMessage(formatDependency(state.bkCommonLib()));
        sender.sendMessage(formatDependency(state.signLink()));
        sender.sendMessage("Receiver adapter: " + state.receiverRegistrar().statusMessage());
        sender.sendMessage("Passenger HUD: " + passengerHudService.statusMessage());
        sender.sendMessage("Legacy sign scan: " + (signScanService.scanRunning() ? "RUNNING" : "IDLE"));
        sender.sendMessage("Infrastructure discovery: "
                + (infrastructureDiscoveryService.running() ? "RUNNING" : "IDLE"));
        sender.sendMessage("Rail discovery: " + (railDiscoveryService.running() ? "RUNNING" : "IDLE"));
        sender.sendMessage("Web administration: " + webStatus);
        sender.sendMessage("TrainCarts active loaded worlds: " + worldPolicy.activeDescription());
        sender.sendMessage("Tracked receiver passages: " + receiverEvents.trackedPassageCount());
        sender.sendMessage("Recorded operational events: " + state.recordedEventCount());
    }

    private boolean handleDiscovery(CommandSender sender, String[] arguments) {
        if (!sender.hasPermission("atcs.discovery")) {
            sender.sendMessage("[ATCS] You do not have permission to run infrastructure discovery.");
            return true;
        }
        if (arguments.length != 2) {
            sender.sendMessage("[ATCS] Usage: /atcs discover <candidates|rail|status>");
            return true;
        }
        return switch (arguments[1].toLowerCase(Locale.ROOT)) {
            case "candidates" -> {
                boolean started = infrastructureDiscoveryService.startCandidateDiscovery(
                        completion -> sendInfrastructureDiscoveryCompletion(sender, completion));
                if (started) {
                    sender.sendMessage("[ATCS] Started infrastructure candidate inference from preserved sign evidence.");
                    sender.sendMessage("[ATCS] No candidate will be promoted into authoritative topology automatically.");
                } else {
                    sender.sendMessage("[ATCS] Infrastructure candidate discovery is already running.");
                }
                yield true;
            }
            case "rail" -> {
                boolean started = railDiscoveryService.startRailDiscovery(
                        completion -> sendRailDiscoveryCompletion(sender, completion));
                if (started) {
                    sender.sendMessage("[ATCS] Started rail inventory with scope "
                            + railDiscoveryService.scope() + ".");
                    sender.sendMessage("[ATCS] Signs never seed the map. Saved-chunk mode reads existing chunks only and never generates terrain.");
                } else {
                    sender.sendMessage("[ATCS] Rail candidate discovery is already running.");
                }
                yield true;
            }
            case "status" -> {
                sendInfrastructureDiscoverySummary(
                        sender, infrastructureDiscoveryService.lastSummary());
                sendRailDiscoverySummary(sender, railDiscoveryService.lastSummary());
                yield true;
            }
            default -> {
                sender.sendMessage("[ATCS] Usage: /atcs discover <candidates|rail|status>");
                yield true;
            }
        };
    }

    private void sendInfrastructureDiscoveryCompletion(
            CommandSender sender,
            InfrastructureDiscoveryCompletion completion) {
        sender.sendMessage("[ATCS] " + completion.message());
        if (completion.successful()) {
            sendInfrastructureDiscoverySummary(sender, completion.summary());
        }
    }

    private void sendInfrastructureDiscoverySummary(
            CommandSender sender,
            InfrastructureDiscoverySummary summary) {
        if (summary.runId().isEmpty()) {
            sender.sendMessage("[ATCS] No infrastructure candidate snapshot is stored yet.");
            return;
        }
        sender.sendMessage("[ATCS] Infrastructure candidates from "
                + summary.sourceStationSigns() + " station signs and "
                + summary.sourceSpawnSigns() + " spawn signs:");
        sender.sendMessage("[ATCS] " + summary.stationCandidates() + " passenger stations, "
                + summary.platformCandidates() + " platforms, "
                + summary.departureCandidates() + " departure points, and "
                + summary.trackControlCandidates() + " track motion controls.");
    }

    private void sendRailDiscoveryCompletion(
            CommandSender sender,
            RailDiscoveryCompletion completion) {
        sender.sendMessage("[ATCS] " + completion.message());
        if (completion.successful()) {
            sendRailDiscoverySummary(sender, completion.summary());
        }
    }

    private void sendRailDiscoverySummary(CommandSender sender, RailDiscoverySummary summary) {
        if (summary.runId().isEmpty()) {
            sender.sendMessage("[ATCS] No rail inventory snapshot is stored yet.");
            return;
        }
        sender.sendMessage("[ATCS] Rail inventory: " + summary.nodesDiscovered()
                + " nodes and " + summary.edgesDiscovered() + " edges from "
                + summary.chunksScanned() + "/" + summary.chunksConsidered()
                + " rail chunks (" + summary.discoveryMode() + ").");
        sender.sendMessage("[ATCS] Block checks: " + summary.blockChecks() + ".");
        sender.sendMessage("[ATCS] Unloaded chunk boundaries: " + summary.unloadedBoundaries()
                + "; node limit reached: " + (summary.nodeLimitReached() ? "YES" : "NO") + ".");
    }

    private boolean handleReview(CommandSender sender, String[] arguments) {
        if (!sender.hasPermission("atcs.review")) {
            sender.sendMessage("[ATCS] You do not have permission to inspect the legacy review queue.");
            return true;
        }
        if (arguments.length < 2) {
            sender.sendMessage("[ATCS] Usage: /atcs review <status|list|show|set>");
            return true;
        }
        try {
            return switch (arguments[1].toLowerCase(Locale.ROOT)) {
                case "status" -> {
                    if (arguments.length != 2) {
                        sender.sendMessage("[ATCS] Usage: /atcs review status");
                    } else {
                        sendReviewStatus(sender, reviewRepository.loadSummary());
                    }
                    yield true;
                }
                case "list" -> {
                    if (arguments.length > 3) {
                        sender.sendMessage("[ATCS] Usage: /atcs review list [page]");
                        yield true;
                    }
                    int page = arguments.length == 3 ? parsePage(arguments[2]) : 1;
                    sendReviewPage(sender, reviewRepository.listIssuePage(page));
                    yield true;
                }
                case "show" -> {
                    if (arguments.length != 3) {
                        sender.sendMessage("[ATCS] Usage: /atcs review show <sign-id>");
                        yield true;
                    }
                    Optional<LegacyReviewItem> item = reviewRepository.findByIdPrefix(arguments[2]);
                    if (item.isEmpty()) {
                        sender.sendMessage("[ATCS] No present legacy sign matches that ID.");
                    } else {
                        sendReviewItem(sender, item.orElseThrow());
                    }
                    yield true;
                }
                case "set" -> handleReviewSet(sender, arguments);
                default -> {
                    sender.sendMessage("[ATCS] Usage: /atcs review <status|list|show|set>");
                    yield true;
                }
            };
        } catch (IllegalArgumentException exception) {
            sender.sendMessage("[ATCS] " + exception.getMessage());
            return true;
        } catch (SQLException exception) {
            plugin.getLogger().log(Level.SEVERE, "Could not query the legacy review queue", exception);
            sender.sendMessage("[ATCS] The legacy review database query failed. Check the server log.");
            return true;
        }
    }

    private boolean handleReviewSet(CommandSender sender, String[] arguments) throws SQLException {
        if (!sender.hasPermission("atcs.review.manage")) {
            sender.sendMessage("[ATCS] You do not have permission to record legacy review decisions.");
            return true;
        }
        if (arguments.length < 4) {
            sender.sendMessage("[ATCS] Usage: /atcs review set <sign-id> <status> [note]");
            return true;
        }
        LegacyReviewStatus status = parseReviewStatus(arguments[3]);
        Optional<String> note = arguments.length > 4
                ? Optional.of(String.join(" ", Arrays.copyOfRange(arguments, 4, arguments.length)))
                : Optional.empty();
        String actorType;
        String actorId;
        if (sender instanceof Player player) {
            actorType = "PLAYER";
            actorId = player.getUniqueId().toString();
        } else {
            actorType = "CONSOLE";
            actorId = sender.getName();
        }
        Optional<LegacyReviewItem> updated = reviewRepository.updateStatus(
                arguments[2], status, actorType, actorId, note);
        if (updated.isEmpty()) {
            sender.sendMessage("[ATCS] No present legacy sign matches that ID.");
            return true;
        }
        LegacyReviewItem item = updated.orElseThrow();
        sender.sendMessage("[ATCS] Review status for " + item.shortId() + " is now " + item.status() + ".");
        return true;
    }

    private LegacyReviewStatus parseReviewStatus(String value) {
        String normalized = value.strip().replace('-', '_').toUpperCase(Locale.ROOT);
        LegacyReviewStatus status;
        try {
            status = LegacyReviewStatus.valueOf(normalized);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(
                    "Review status must be approved, rejected, needs-correction, or unreviewed", exception);
        }
        if (status == LegacyReviewStatus.ARCHIVED) {
            throw new IllegalArgumentException("ARCHIVED is controlled by TrainCarts world availability, not review commands");
        }
        return status;
    }

    private int parsePage(String value) {
        try {
            int page = Integer.parseInt(value);
            if (page <= 0) {
                throw new IllegalArgumentException("Review page number must be positive");
            }
            return page;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("Review page number must be a positive integer", exception);
        }
    }

    private void sendReviewStatus(CommandSender sender, LegacyReviewSummary summary) {
        sender.sendMessage("[ATCS] Legacy review: " + summary.presentSigns() + " present signs; "
                + summary.issueSigns() + " signs with " + summary.openIssues() + " open issues.");
        for (LegacyReviewStatus status : LegacyReviewStatus.values()) {
            int count = summary.statusCount(status);
            if (count > 0) {
                sender.sendMessage("[ATCS] " + status.name() + ": " + count);
            }
        }
        for (LegacyIssueCode code : LegacyIssueCode.values()) {
            int count = summary.issuesByCode().getOrDefault(code, 0);
            if (count > 0) {
                sender.sendMessage("[ATCS] " + code.name() + ": " + count);
            }
        }
    }

    private void sendReviewPage(CommandSender sender, LegacyReviewPage page) {
        if (page.items().isEmpty()) {
            sender.sendMessage("[ATCS] No open legacy sign issues are present on this page.");
            return;
        }
        sender.sendMessage("[ATCS] Legacy issues page " + page.page() + "/" + page.totalPages()
                + " (" + page.totalItems() + " signs):");
        for (LegacyReviewItem item : page.items()) {
            String issueCodes = String.join(", ", item.issues().stream()
                    .map(issue -> issue.code().name())
                    .toList());
            var position = item.subject().position();
            sender.sendMessage("[ATCS] " + item.shortId() + " " + position.worldName()
                    + " " + position.x() + " " + position.y() + " " + position.z()
                    + " " + item.subject().side() + " - " + issueCodes);
        }
        sender.sendMessage("[ATCS] Use /atcs review show <sign-id> for preserved evidence.");
    }

    private void sendReviewItem(CommandSender sender, LegacyReviewItem item) {
        var position = item.subject().position();
        sender.sendMessage("[ATCS] Legacy sign " + item.subject().signId());
        sender.sendMessage("[ATCS] Location: " + position.worldName() + " "
                + position.x() + " " + position.y() + " " + position.z()
                + " " + item.subject().side());
        sender.sendMessage("[ATCS] Type: " + item.subject().type()
                + "; action: " + sanitizeEvidence(item.subject().action())
                + "; review: " + item.status());
        for (int index = 0; index < item.subject().rawLines().size(); index++) {
            sender.sendMessage("[ATCS] Line " + (index + 1) + ": "
                    + sanitizeEvidence(item.subject().rawLines().get(index)));
        }
        if (item.issues().isEmpty()) {
            sender.sendMessage("[ATCS] No configured review issues.");
        } else {
            for (LegacyReviewIssue issue : item.issues()) {
                sender.sendMessage("[ATCS] " + issue.severity() + " " + issue.code()
                        + ": " + sanitizeEvidence(issue.details()));
            }
        }
        item.reviewNote().ifPresent(note ->
                sender.sendMessage("[ATCS] Review note: " + sanitizeEvidence(note)));
        item.reviewedBy().ifPresent(reviewer -> sender.sendMessage("[ATCS] Reviewed by: " + reviewer
                + item.reviewedAt().map(time -> " at " + time).orElse("")));
    }

    private String sanitizeEvidence(String value) {
        return value.replace('§', '&').replace('\n', ' ');
    }

    private boolean handleScan(CommandSender sender, String[] arguments) {
        if (!sender.hasPermission("atcs.scan")) {
            sender.sendMessage("[ATCS] You do not have permission to run inventory scans.");
            return true;
        }
        if (arguments.length != 2) {
            sender.sendMessage("[ATCS] Usage: /atcs scan <loaded|status>");
            return true;
        }
        if ("status".equalsIgnoreCase(arguments[1])) {
            sendScanSummary(sender, signScanService.lastSummary());
            return true;
        }
        if (!"loaded".equalsIgnoreCase(arguments[1])) {
            sender.sendMessage("[ATCS] Usage: /atcs scan <loaded|status>");
            return true;
        }

        boolean started = signScanService.startLoadedChunkScan(completion -> sendScanCompletion(sender, completion));
        if (!started) {
            sender.sendMessage("[ATCS] A loaded-chunk inventory scan is already running.");
            return true;
        }
        sender.sendMessage("[ATCS] Started a read-only scan of chunks that are already loaded.");
        sender.sendMessage("[ATCS] The scanner will not load or generate any additional chunks.");
        return true;
    }

    private void sendScanCompletion(CommandSender sender, LegacyScanCompletion completion) {
        sender.sendMessage("[ATCS] " + completion.message());
        completion.result().ifPresent(result -> sender.sendMessage(
                "[ATCS] Scanned " + result.scannedChunks().size()
                        + " chunks, skipped " + result.skippedChunks()
                        + ", and found " + result.signs().size() + " TrainCarts sign sides."));
        if (completion.successful()) {
            sendScanSummary(sender, completion.summary());
        }
    }

    private void sendScanSummary(CommandSender sender, LegacyInventorySummary summary) {
        if (summary.lastRunId().isEmpty()) {
            sender.sendMessage("[ATCS] No completed legacy sign inventory is stored yet.");
            return;
        }
        sender.sendMessage("[ATCS] Present legacy signs: " + summary.presentSigns());
        sender.sendMessage("[ATCS] Last scan: " + summary.lastCompletedAt().orElseThrow()
                + "; chunks " + summary.chunksScanned()
                + "; skipped " + summary.chunksSkipped());
        for (LegacySignType type : LegacySignType.values()) {
            Integer count = summary.signsByType().get(type);
            if (count != null && count > 0) {
                sender.sendMessage("[ATCS] " + type.name() + ": " + count);
            }
        }
    }

    private boolean handleAnnouncement(CommandSender sender, String[] arguments) {
        if (!sender.hasPermission("atcs.announcement.test")) {
            sender.sendMessage("[ATCS] You do not have permission to test announcements.");
            return true;
        }
        if (arguments.length != 3 || !"test".equalsIgnoreCase(arguments[1])) {
            sender.sendMessage("[ATCS] Usage: /atcs announce test <event>");
            return true;
        }

        AnnouncementType type;
        try {
            type = AnnouncementType.valueOf(arguments[2].toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            sender.sendMessage("[ATCS] Unknown announcement event. Available events: "
                    + String.join(", ", Arrays.stream(AnnouncementType.values()).map(Enum::name).toList()));
            return true;
        }

        AnnouncementMessage message = announcements.renderStrict(type, sampleVariables());
        sender.sendMessage("[ATCS] " + message.chat());
        if (sender instanceof Player player) {
            player.sendActionBar(Component.text(message.actionBar()));
        } else {
            sender.sendMessage("[ATCS action bar] " + message.actionBar());
        }
        return true;
    }

    private String formatDependency(DependencyStatus dependency) {
        return dependency.pluginName() + ": " + (dependency.present()
                ? "AVAILABLE (" + dependency.version().orElseThrow() + ")"
                : "UNAVAILABLE");
    }

    private Map<String, String> sampleVariables() {
        return Map.ofEntries(
                Map.entry("line", "Central Line"),
                Map.entry("service_type", "Express"),
                Map.entry("direction", "Clockwise"),
                Map.entry("destination", "Airport Terminal"),
                Map.entry("station", "Central Station"),
                Map.entry("next_station", "Harbour Station"),
                Map.entry("platform", "1"),
                Map.entry("skipped_stations", "Museum and Riverside"),
                Map.entry("delay", "five minutes"));
    }

    @Override
    public List<String> onTabComplete(
            CommandSender sender,
            Command command,
            String alias,
            String[] arguments) {
        if (arguments.length == 1) {
            return filterPrefix(List.of("status", "announce", "scan", "review", "discover", "help"), arguments[0]);
        }
        if (arguments.length == 2 && "announce".equalsIgnoreCase(arguments[0])) {
            return filterPrefix(List.of("test"), arguments[1]);
        }
        if (arguments.length == 3
                && "announce".equalsIgnoreCase(arguments[0])
                && "test".equalsIgnoreCase(arguments[1])) {
            return filterPrefix(Arrays.stream(AnnouncementType.values()).map(Enum::name).toList(), arguments[2]);
        }
        if (arguments.length == 2 && "scan".equalsIgnoreCase(arguments[0])) {
            return filterPrefix(List.of("loaded", "status"), arguments[1]);
        }
        if (arguments.length == 2 && "review".equalsIgnoreCase(arguments[0])) {
            return filterPrefix(List.of("status", "list", "show", "set"), arguments[1]);
        }
        if (arguments.length == 2 && "discover".equalsIgnoreCase(arguments[0])) {
            return filterPrefix(List.of("candidates", "rail", "status"), arguments[1]);
        }
        if (arguments.length == 4
                && "review".equalsIgnoreCase(arguments[0])
                && "set".equalsIgnoreCase(arguments[1])) {
            return filterPrefix(
                    List.of("approved", "rejected", "needs-correction", "unreviewed"), arguments[3]);
        }
        return List.of();
    }

    private List<String> filterPrefix(List<String> values, String prefix) {
        String normalized = prefix.toUpperCase(Locale.ROOT);
        return values.stream().filter(value -> value.toUpperCase(Locale.ROOT).startsWith(normalized)).toList();
    }
}
