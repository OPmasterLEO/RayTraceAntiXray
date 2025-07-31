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
        
        // Only process packets we care about
        boolean process = false;
        for (PacketTypeCommon packetType : LISTENING_PACKETS) {
            if (type == packetType) {
                process = true;
                break;
            }
        }
        
        if (!process) return;
        
        Player player = (Player) event.getPlayer();
        World bukkitWorld = player.getWorld();

        if (type == PacketType.Play.Server.CHUNK_DATA) {
            // Use reflection to handle PacketEvents version differences
            Object packet;
            try {
                // Try modern PacketEvents method (2.0+)
                packet = event.getClass().getMethod("getPacket").invoke(event);
            } catch (Exception e1) {
                try {
                    // Try legacy PacketEvents method (1.8.x)
                    packet = event.getClass().getMethod("getNMSPacket").invoke(event);
                } catch (Exception e2) {
                    plugin.getLogger().warning("Failed to get packet object: " + e2.getMessage());
                    return;
                }
            }
            
            ChunkBlocks chunkBlocks = plugin.getPacketChunkBlocksCache().get(packet);

            if (chunkBlocks == null) {
                Location location = player.getEyeLocation();
                ConcurrentMap<UUID, PlayerData> playerDataMap = plugin.getPlayerData();
                UUID uniqueId = player.getUniqueId();
                PlayerData playerData = playerDataMap.get(uniqueId);

                if (!plugin.validatePlayerData(player, playerData, "onPacketSend")) {
                    return;
                }

                if (!location.getWorld().equals(playerData.getLocations()[0].getWorld())) {
                    playerData = new PlayerData(RayTraceAntiXray.getLocations(player, new VectorialLocation(location)));
                    playerData.setCallable(new RayTraceCallable(plugin, playerData));
                    playerDataMap.put(uniqueId, playerData);
                }
                return;
            }

            LevelChunk chunk = chunkBlocks.getChunk();
            if (chunk == null) {
                return;
            }

            ConcurrentMap<UUID, PlayerData> playerDataMap = plugin.getPlayerData();
            UUID uniqueId = player.getUniqueId();
            PlayerData playerData = playerDataMap.get(uniqueId);

            if (!plugin.validatePlayerData(player, playerData, "onPacketSend")) {
                return;
            }

            // Compare Bukkit worlds directly
            if (!bukkitWorld.equals(playerData.getLocations()[0].getWorld())) {
                Location location = player.getEyeLocation();
                if (!bukkitWorld.equals(location.getWorld())) {
                    return;
                }
                playerData = new PlayerData(RayTraceAntiXray.getLocations(player, new VectorialLocation(location)));
                playerData.setCallable(new RayTraceCallable(plugin, playerData));
                playerDataMap.put(uniqueId, playerData);
            }

            chunkBlocks = new ChunkBlocks(chunk, new HashMap<>(chunkBlocks.getBlocks()));
            playerData.getChunks().put(chunkBlocks.getKey(), chunkBlocks);
        } else if (type == PacketType.Play.Server.UNLOAD_CHUNK) {
            WrapperPlayServerUnloadChunk wrapper = new WrapperPlayServerUnloadChunk(event);
            PlayerData playerData = plugin.getPlayerData().get(player.getUniqueId());

            if (!plugin.validatePlayerData(player, playerData, "onPacketSend")) {
                return;
            }

            int chunkX = wrapper.getChunkX();
            int chunkZ = wrapper.getChunkZ();
            playerData.getChunks().remove(new LongWrapper(ChunkPos.asLong(chunkX, chunkZ)));
        } else if (type == PacketType.Play.Server.RESPAWN) {
            PlayerData playerData = plugin.getPlayerData().get(player.getUniqueId());

            if (!plugin.validatePlayerData(player, playerData, "onPacketSend")) {
                return;
            }

            playerData.getChunks().clear();
        }
    }
}