package dev.advancedtrains.atcs.selection;

import dev.advancedtrains.atcs.network.BlockPos;
import dev.advancedtrains.atcs.network.Box;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.jspecify.annotations.Nullable;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Keeps each player's wand selection: two corners clicked with the ATCS wand, WorldEdit-style.
 * When WorldEdit is installed and the player has a selection there, that one is used instead,
 * so operators keep the tool they know.
 */
public final class SelectionService implements Listener {
    /** Permission needed to use the wand. */
    public static final String PERMISSION = "atcs.manage";

    private final NamespacedKey wandKey;
    private final Logger logger;
    private final @Nullable WorldEditSelections worldEdit;
    private final Map<UUID, BlockPos[]> corners = new HashMap<>();

    /**
     * @param worldEditPresent whether the WorldEdit plugin is loaded; its classes are only touched when it is
     */
    public SelectionService(Plugin plugin, boolean worldEditPresent) {
        this.wandKey = new NamespacedKey(plugin, "wand");
        this.logger = plugin.getLogger();
        WorldEditSelections hook = null;
        if (worldEditPresent) {
            try {
                hook = new WorldEditSelections();
                logger.info("WorldEdit found: its selection is used by the ATCS wand commands when you have one");
            } catch (RuntimeException | LinkageError exception) {
                logger.log(Level.WARNING, "This WorldEdit version is not supported; using the ATCS wand only", exception);
            }
        }
        this.worldEdit = hook;
    }

    public boolean worldEditActive() {
        return worldEdit != null;
    }

    /** A new wand item. */
    public ItemStack wand() {
        ItemStack item = new ItemStack(Material.BLAZE_ROD);
        ItemMeta meta = item.getItemMeta();
        meta.itemName(Component.text("ATCS wand", NamedTextColor.GOLD));
        meta.lore(List.of(
                Component.text("Left click: first corner", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
                Component.text("Right click: second corner", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
                Component.text("Then /atcs display ... or /atcs station footprint", NamedTextColor.DARK_GRAY)
                        .decoration(TextDecoration.ITALIC, false)));
        meta.getPersistentDataContainer().set(wandKey, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        return item;
    }

    public boolean isWand(@Nullable ItemStack item) {
        if (item == null || item.getType() == Material.AIR || !item.hasItemMeta()) {
            return false;
        }
        return item.getItemMeta().getPersistentDataContainer().has(wandKey, PersistentDataType.BYTE);
    }

    /** Sets a corner: {@code 1} or {@code 2}. */
    public void setCorner(Player player, int which, BlockPos position) {
        BlockPos[] pair = corners.computeIfAbsent(player.getUniqueId(), ignored -> new BlockPos[2]);
        if (pair[0] != null && !pair[0].world().equals(position.world())) {
            pair[0] = null;
            pair[1] = null;
        }
        pair[which - 1] = position;
    }

    public Optional<BlockPos> corner(Player player, int which) {
        BlockPos[] pair = corners.get(player.getUniqueId());
        return pair == null ? Optional.empty() : Optional.ofNullable(pair[which - 1]);
    }

    public void clear(Player player) {
        corners.remove(player.getUniqueId());
    }

    /** The wand corners as a box, when both are set. */
    public Optional<Box> wandSelection(Player player) {
        BlockPos[] pair = corners.get(player.getUniqueId());
        if (pair == null || pair[0] == null || pair[1] == null) {
            return Optional.empty();
        }
        return Optional.of(Box.of(pair[0], pair[1]));
    }

    /** WorldEdit's selection of the player's world, when WorldEdit is installed and has one. */
    public Optional<Box> worldEditSelection(Player player) {
        if (worldEdit == null) {
            return Optional.empty();
        }
        try {
            return worldEdit.selection(player);
        } catch (RuntimeException | LinkageError exception) {
            logger.log(Level.WARNING, "Could not read the WorldEdit selection", exception);
            return Optional.empty();
        }
    }

    /** The selection to use: WorldEdit's when it has one in this world, otherwise the wand's. */
    public Optional<Box> selection(Player player) {
        return worldEditSelection(player).or(() -> wandSelection(player));
    }

    /** Where the current selection comes from, for messages: {@code WorldEdit} or {@code the wand}. */
    public String source(Player player) {
        return worldEditSelection(player).isPresent() ? "WorldEdit" : "the wand";
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = false)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND || !isWand(event.getItem())) {
            return;
        }
        Block block = event.getClickedBlock();
        if (block == null) {
            return;
        }
        Player player = event.getPlayer();
        if (!player.hasPermission(PERMISSION)) {
            return;
        }
        int which;
        if (event.getAction() == Action.LEFT_CLICK_BLOCK) {
            which = 1;
        } else if (event.getAction() == Action.RIGHT_CLICK_BLOCK) {
            which = 2;
        } else {
            return;
        }
        event.setCancelled(true);
        BlockPos position = new BlockPos(block.getWorld().getName(), block.getX(), block.getY(), block.getZ());
        setCorner(player, which, position);
        Component message = Component.text()
                .append(Component.text("ATCS ", NamedTextColor.GOLD))
                .append(Component.text((which == 1 ? "First" : "Second") + " corner set to " + position.coordinates(),
                        NamedTextColor.GRAY))
                .append(wandSelection(player)
                        .map(box -> Component.text(" (" + box.volume() + " blocks)", NamedTextColor.DARK_GRAY))
                        .orElse(Component.empty()))
                .build();
        player.sendMessage(message);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        corners.remove(event.getPlayer().getUniqueId());
    }
}
