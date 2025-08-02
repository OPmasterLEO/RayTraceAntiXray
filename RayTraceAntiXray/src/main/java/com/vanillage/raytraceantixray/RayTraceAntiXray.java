package com.vanillage.raytraceantixray;

import java.io.File;
import java.util.Timer;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.craftbukkit.entity.CraftEntity;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Vector;

import com.github.retrooper.packetevents.PacketEvents;
import io.github.retrooper.packetevents.factory.spigot.SpigotPacketEventsBuilder;
import com.google.common.collect.MapMaker;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.vanillage.raytraceantixray.antixray.ChunkPacketBlockControllerAntiXray;
import com.vanillage.raytraceantixray.commands.RayTraceAntiXrayTabExecutor;
import com.vanillage.raytraceantixray.data.ChunkBlocks;
import com.vanillage.raytraceantixray.data.PlayerData;
import com.vanillage.raytraceantixray.data.VectorialLocation;
import com.vanillage.raytraceantixray.listeners.PlayerListener;
import com.vanillage.raytraceantixray.listeners.WorldListener;
import com.vanillage.raytraceantixray.tasks.RayTraceTimerTask;
import com.vanillage.raytraceantixray.tasks.UpdateBukkitRunnable;

import io.papermc.paper.antixray.ChunkPacketBlockController;
import io.papermc.paper.configuration.WorldConfiguration.Anticheat.AntiXray;
import io.papermc.paper.configuration.type.EngineMode;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

public final class RayTraceAntiXray extends JavaPlugin {
    private volatile boolean folia = false;
    private volatile boolean running = false;
    private volatile boolean timingsEnabled = false;
    private volatile boolean rayTracingEnabled = true;
    private final ConcurrentMap<ClientboundLevelChunkWithLightPacket, ChunkBlocks> packetChunkBlocksCache = new MapMaker().weakKeys().makeMap();
    private final ConcurrentMap<UUID, PlayerData> playerData = new ConcurrentHashMap<>();
    private ExecutorService executorService;
    private Timer timer;
    private long updateTicks = 1L;
    private final Vec3[] cornerCache = new Vec3[8];

    @Override
    public void onLoad() {
        PacketEvents.setAPI(SpigotPacketEventsBuilder.build(this));
        PacketEvents.getAPI().load();
        initializeCornerCache();
    }

    private void initializeCornerCache() {
        for (int i = 0; i < 8; i++) {
            cornerCache[i] = new Vec3(
                (i & 1) * 2 - 1,
                (i >> 1 & 1) * 2 - 1,
                (i >> 2 & 1) * 2 - 1
            ).scale(0.1);
        }
    }

    @Override
    public void onEnable() {
        if (!new File(getDataFolder(), "README.txt").exists()) {
            saveResource("README.txt", false);
        }

        saveDefaultConfig();
        FileConfiguration config = getConfig();
        config.options().copyDefaults(true);

        folia = isFoliaPresent();
        running = true;
        
        int threadCount = Math.max(config.getInt("settings.anti-xray.ray-trace-threads", 4), 1);
        executorService = Executors.newFixedThreadPool(
            threadCount,
            new ThreadFactoryBuilder()
                .setNameFormat("RayTraceAntiXray-ray-trace-%d")
                .setDaemon(true)
                .build()
        );
        
        long tickInterval = Math.max(config.getLong("settings.anti-xray.ms-per-ray-trace-tick", 50L), 1L);
        timer = new Timer("RayTraceAntiXray-tick", true);
        timer.schedule(new RayTraceTimerTask(this), 0L, tickInterval);
        
        updateTicks = Math.max(config.getLong("settings.anti-xray.update-ticks", 1L), 1L);

        if (!folia) {
            new UpdateBukkitRunnable(this).runTaskTimerAsynchronously(this, 0L, updateTicks);
        }

        PluginManager pluginManager = getServer().getPluginManager();
        pluginManager.registerEvents(new WorldListener(this), this);
        pluginManager.registerEvents(new PlayerListener(this), this);
        
        getCommand("raytraceantixray").setExecutor(new RayTraceAntiXrayTabExecutor(this));
        getLogger().info(getPluginMeta().getDisplayName() + " enabled");
        
        Bukkit.getScheduler().runTask(this, () -> {
            PacketEvents.getAPI().getSettings().reEncodeByDefault(false);
            PacketEvents.getAPI().init();
            PacketEvents.getAPI().getEventManager().registerListener(new com.vanillage.raytraceantixray.listeners.PacketListener(this));
        });
    }

    private boolean isFoliaPresent() {
        try {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    @Override
    public void onDisable() {
        Throwable throwable = null;
        try {
            running = false;
            timer.cancel();
            executorService.shutdownNow();
            executorService.awaitTermination(1, TimeUnit.SECONDS);
            PacketEvents.getAPI().terminate();
        } catch (Throwable t) {
            throwable = t;
        } finally {
            packetChunkBlocksCache.clear();
            playerData.clear();
        }
        
        if (throwable != null) {
            getLogger().log(Level.SEVERE, "Error during disable", throwable);
        }
        getLogger().info(getPluginMeta().getDisplayName() + " disabled");
    }

    public synchronized void reload() {
        onDisable();
        onEnable();
        getLogger().info(getPluginMeta().getDisplayName() + " reloaded");
    }

    public boolean isFolia() {
        return folia;
    }

    public boolean isRunning() {
        return running;
    }

    public boolean isTimingsEnabled() {
        return timingsEnabled;
    }

    public void setTimingsEnabled(boolean timingsEnabled) {
        this.timingsEnabled = timingsEnabled;
    }

    public ConcurrentMap<ClientboundLevelChunkWithLightPacket, ChunkBlocks> getPacketChunkBlocksCache() {
        return packetChunkBlocksCache;
    }

    public ConcurrentMap<UUID, PlayerData> getPlayerData() {
        return playerData;
    }

    public ExecutorService getExecutorService() {
        return executorService;
    }

    public long getUpdateTicks() {
        return updateTicks;
    }

    public boolean toggleRayTracing() {
        rayTracingEnabled = !rayTracingEnabled;
        return rayTracingEnabled;
    }

    public boolean isRayTracingEnabled() {
        return rayTracingEnabled;
    }

    public boolean isEnabled(World world) {
        if (!rayTracingEnabled) {
            return false;
        }
        AntiXray antiXray = ((CraftWorld) world).getHandle().paperConfig().anticheat.antiXray;
        if (!antiXray.enabled || antiXray.engineMode != EngineMode.HIDE) {
            return false;
        }
        FileConfiguration config = getConfig();
        return config.getBoolean("world-settings." + world.getName() + ".anti-x-ray.ray-trace", 
                config.getBoolean("world-settings.default.anti-x-ray.ray-trace", true));
    }

    public boolean validatePlayer(Player player) {
        return !player.hasMetadata("NPC");
    }

    public boolean validatePlayerData(Player player, PlayerData playerData, String methodName) {
        if (playerData == null && validatePlayer(player)) {
            Logger logger = getLogger();
            logger.warning("Missing player data for " + player.getName() + " in " + methodName);
            logger.warning("Reloading is now supported. Please report if this persists.");
            return false;
        }
        return playerData != null;
    }

    public VectorialLocation[] getLocations(Entity entity, VectorialLocation location) {
        World world = location.getWorld();
        ChunkPacketBlockController controller = ((CraftWorld) world).getHandle().chunkPacketBlockController;
        
        if (!(controller instanceof ChunkPacketBlockControllerAntiXray) || 
            !((ChunkPacketBlockControllerAntiXray) controller).rayTraceThirdPerson) {
            return new VectorialLocation[]{location};
        }

        // Just create new VectorialLocation objects as needed
        VectorialLocation loc1 = new VectorialLocation(location);
        VectorialLocation loc2 = new VectorialLocation(
            world,
            location.getVector().clone(),
            location.getDirection().clone()
        );
        VectorialLocation loc3 = new VectorialLocation(
            world,
            location.getVector().clone(),
            location.getDirection().clone().multiply(-1)
        );
        
        move(entity, loc2);
        move(entity, loc3);
        
        return new VectorialLocation[]{loc1, loc2, loc3};
    }

    private void move(Entity entity, VectorialLocation location) {
        double zoom = getMaxZoom(entity, location, 4.0);
        location.getVector().subtract(location.getDirection().clone().multiply(zoom));
    }

    private double getMaxZoom(Entity entity, VectorialLocation location, double maxZoom) {
        Vector vector = location.getVector();
        double x = vector.getX();
        double y = vector.getY();
        double z = vector.getZ();
        Vector direction = location.getDirection();
        double dx = direction.getX();
        double dy = direction.getY();
        double dz = direction.getZ();
        
        ServerLevel serverLevel = ((CraftWorld) location.getWorld()).getHandle();
        net.minecraft.world.entity.Entity handle = ((CraftEntity) entity).getHandle();
        Vec3 position = new Vec3(x, y, z);
        
        double baseMovedX = x - dx * maxZoom;
        double baseMovedY = y - dy * maxZoom;
        double baseMovedZ = z - dz * maxZoom;
        
        for (int i = 0; i < 8; i++) {
            float cornerX = (float) ((i & 1) * 2 - 1) * 0.1f;
            float cornerY = (float) ((i >> 1 & 1) * 2 - 1) * 0.1f;
            float cornerZ = (float) ((i >> 2 & 1) * 2 - 1) * 0.1f;
            Vec3 corner = position.add(cornerX, cornerY, cornerZ);
            Vec3 cornerMoved = new Vec3(
                baseMovedX + cornerX,
                baseMovedY + cornerY,
                baseMovedZ + cornerZ
            );

            // Use the correct ClipContext constructor
            BlockHitResult result = serverLevel.clip(new ClipContext(
                corner,
                cornerMoved,
                ClipContext.Block.VISUAL,
                ClipContext.Fluid.NONE,
                handle
            ));

            if (result.getType() != HitResult.Type.MISS) {
                double zoom = result.getLocation().distanceTo(position);
                if (zoom < maxZoom) {
                    maxZoom = zoom;
                }
            }
        }
        
        return maxZoom;
    }
}