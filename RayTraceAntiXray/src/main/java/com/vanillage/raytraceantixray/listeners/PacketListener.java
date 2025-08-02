package com.vanillage.raytraceantixray.listeners;

import java.util.HashMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentMap;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;

import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.packettype.PacketTypeCommon;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerUnloadChunk;

import com.vanillage.raytraceantixray.RayTraceAntiXray;
import com.vanillage.raytraceantixray.data.ChunkBlocks;
import com.vanillage.raytraceantixray.data.LongWrapper;
import com.vanillage.raytraceantixray.data.PlayerData;
import com.vanillage.raytraceantixray.data.VectorialLocation;
import com.vanillage.raytraceantixray.tasks.RayTraceCallable;

import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;

public final class PacketListener extends PacketListenerAbstract {
    private final RayTraceAntiXray plugin;
    private static final PacketTypeCommon[] LISTENING_PACKETS = {
        PacketType.Play.Server.CHUNK_DATA,
        PacketType.Play.Server.UNLOAD_CHUNK,
        PacketType.Play.Server.RESPAWN
    };

    public PacketListener(RayTraceAntiXray plugin) {
        super(PacketListenerPriority.NORMAL);
        this.plugin = plugin;
    }

    @Override
    public void onPacketSend(PacketSendEvent event) {
        PacketTypeCommon type = event.getPacketType();
        boolean process = false;
        for (PacketTypeCommon packetType : LISTENING_PACKETS) {
            if (type == packetType) {
                process = true;
                break;
            }
        }
        if (!process) return;

        Player player = (Player) event.getPlayer();
        UUID uniqueId = player.getUniqueId();
        World bukkitWorld = player.getWorld();
        ConcurrentMap<UUID, PlayerData> playerDataMap = plugin.getPlayerData();
        PlayerData playerData = playerDataMap.get(uniqueId);

        if (type == PacketType.Play.Server.CHUNK_DATA) {
            Object packet = null;
            // Try to get the packet object in a version-independent way
            try {
                // PacketEvents 2.x: getNMSPacket() is the standard method
                packet = event.getClass().getMethod("getNMSPacket").invoke(event);
            } catch (Exception e1) {
                try {
                    // Fallback: try getPacket() (older or custom PacketEvents)
                    packet = event.getClass().getMethod("getPacket").invoke(event);
                } catch (Exception e2) {
                    // Only log once per session to avoid spam
                    // Optionally: use a static boolean to suppress further logs
                    // plugin.getLogger().warning("Failed to get packet object: " + e2.getMessage());
                    return;
                }
            }

            if (packet == null) {
                // plugin.getLogger().warning("Failed to get packet object: packet is null");
                return;
            }

            ChunkBlocks chunkBlocks = plugin.getPacketChunkBlocksCache().get(packet);
            if (chunkBlocks == null) {
                if (playerData == null) return;
                Location location = player.getEyeLocation();
                if (!location.getWorld().equals(playerData.getLocations()[0].getWorld())) {
                    PlayerData newPlayerData = new PlayerData(plugin.getLocations(player, new VectorialLocation(location)));
                    newPlayerData.setCallable(new RayTraceCallable(plugin, newPlayerData));
                    playerDataMap.put(uniqueId, newPlayerData);
                }
                return;
            }

            LevelChunk chunk = chunkBlocks.getChunk();
            if (chunk == null || playerData == null) return;

            // Compare Bukkit worlds directly
            if (!bukkitWorld.equals(playerData.getLocations()[0].getWorld())) {
                Location location = player.getEyeLocation();
                if (!bukkitWorld.equals(location.getWorld())) return;
                PlayerData newPlayerData = new PlayerData(plugin.getLocations(player, new VectorialLocation(location)));
                newPlayerData.setCallable(new RayTraceCallable(plugin, newPlayerData));
                playerDataMap.put(uniqueId, newPlayerData);
            }

            chunkBlocks = new ChunkBlocks(chunk, new HashMap<>(chunkBlocks.getBlocks()));
            playerDataMap.get(uniqueId).getChunks().put(chunkBlocks.getKey(), chunkBlocks);
        } else if (type == PacketType.Play.Server.UNLOAD_CHUNK) {
            if (playerData == null) return;
            WrapperPlayServerUnloadChunk wrapper = new WrapperPlayServerUnloadChunk(event);
            int chunkX = wrapper.getChunkX();
            int chunkZ = wrapper.getChunkZ();
            playerData.getChunks().remove(new LongWrapper(ChunkPos.asLong(chunkX, chunkZ)));
        } else if (type == PacketType.Play.Server.RESPAWN) {
            if (playerData == null) return;
            playerData.getChunks().clear();
        }
    }
}