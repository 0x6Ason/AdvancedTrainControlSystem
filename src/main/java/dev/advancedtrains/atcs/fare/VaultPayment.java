package dev.advancedtrains.atcs.fare;

import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Server;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.jspecify.annotations.Nullable;

import java.util.UUID;

/**
 * Fares paid through Vault's economy, such as the one EssentialsX provides. The economy is looked
 * up on each use, as the plugin providing it may enable after ATCS or be reloaded. Only created
 * when Vault is installed, so its classes are never loaded otherwise.
 */
public final class VaultPayment implements FarePayment {
    private final Server server;

    public VaultPayment(Server server) {
        this.server = server;
    }

    private @Nullable Economy economy() {
        RegisteredServiceProvider<Economy> registration = server.getServicesManager().getRegistration(Economy.class);
        Economy economy = registration == null ? null : registration.getProvider();
        return economy != null && economy.isEnabled() ? economy : null;
    }

    @Override
    public @Nullable String economyName() {
        Economy economy = economy();
        return economy == null ? null : economy.getName();
    }

    @Override
    public boolean available() {
        return economy() != null;
    }

    @Override
    public boolean has(UUID player, double amount) {
        Economy economy = economy();
        return economy != null && economy.has(server.getOfflinePlayer(player), amount);
    }

    @Override
    public Result withdraw(UUID player, double amount) {
        Economy economy = economy();
        if (economy == null) {
            return Result.DECLINED;
        }
        EconomyResponse response = economy.withdrawPlayer(server.getOfflinePlayer(player), amount);
        if (response == null) {
            return Result.UNCERTAIN;
        }
        if (response.transactionSuccess()) {
            return Result.PAID;
        }
        return response.type == EconomyResponse.ResponseType.FAILURE || response.type == EconomyResponse.ResponseType.NOT_IMPLEMENTED
                ? Result.DECLINED : Result.UNCERTAIN;
    }
}
