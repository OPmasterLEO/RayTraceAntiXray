package com.vanillage.raytraceantixray.listeners;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.util.Vector;

import com.vanillage.raytraceantixray.RayTraceAntiXray;
import com.vanillage.raytraceantixray.data.PlayerData;
import com.vanillage.raytraceantixray.data.VectorialLocation;
import com.vanillage.raytraceantixray.tasks.RayTraceCallable;
import com.vanillage.raytraceantixray.tasks.UpdateBukkitRunnable;

public final class PlayerListener implements Listener {
    private final RayTraceAntiXray plugin;

    public PlayerListener(RayTraceAntiXray plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (!plugin.validatePlayer(player)) return;
        plugin.getPlayerData().computeIfAbsent(player.getUniqueId(), uuid -> {
            PlayerData pd = new PlayerData(plugin.getLocations(player, new VectorialLocation(player.getEyeLocation())));
            pd.setCallable(new RayTraceCallable(plugin, pd));
            return pd;
        });

        if (plugin.isFolia()) {
            player.getScheduler().runAtFixedRate(plugin, new UpdateBukkitRunnable(plugin, player), null, 1L, plugin.getUpdateTicks());
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        plugin.getPlayerData().remove(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        PlayerData playerData = plugin.getPlayerData().get(player.getUniqueId());
        if (playerData == null || !plugin.validatePlayerData(player, playerData, "onPlayerMove")) return;

        Location to = event.getTo();
        if (to == null || !to.getWorld().equals(playerData.getLocations()[0].getWorld())) return;

        VectorialLocation location = new VectorialLocation(to);
        Vector vector = location.getVector();
        vector.setY(vector.getY() + player.getEyeHeight());
        playerData.setLocations(plugin.getLocations(player, location));
    }
}
