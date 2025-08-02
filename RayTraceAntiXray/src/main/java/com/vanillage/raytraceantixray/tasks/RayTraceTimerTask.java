package com.vanillage.raytraceantixray.tasks;

import java.util.TimerTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ConcurrentMap;

import com.vanillage.raytraceantixray.RayTraceAntiXray;

public final class RayTraceTimerTask extends TimerTask {
    private final RayTraceAntiXray plugin;
    private static final ThreadLocal<java.util.ArrayList<java.util.concurrent.Callable<Object>>> TASKS_LOCAL =
        ThreadLocal.withInitial(() -> new java.util.ArrayList<>(64));

    public RayTraceTimerTask(RayTraceAntiXray plugin) {
        this.plugin = plugin;
    }

    @Override
    public void run() {
        boolean timingsEnabled = plugin.isTimingsEnabled();
        long start = timingsEnabled ? System.currentTimeMillis() : 0L;

        try {
            ConcurrentMap<java.util.UUID, com.vanillage.raytraceantixray.data.PlayerData> playerDataMap = plugin.getPlayerData();
            java.util.ArrayList<java.util.concurrent.Callable<Object>> tasks = TASKS_LOCAL.get();
            tasks.clear();
            plugin.getServer().getOnlinePlayers().forEach(player -> {
                com.vanillage.raytraceantixray.data.PlayerData data = playerDataMap.get(player.getUniqueId());
                if (data != null && !player.hasMetadata("vanished") && !data.getChunks().isEmpty()) {
                    tasks.add(data);
                }
            });
            if (!tasks.isEmpty()) {
                plugin.getExecutorService().invokeAll(tasks);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (RejectedExecutionException e) {
        }

        if (timingsEnabled) {
            long stop = System.currentTimeMillis();
            plugin.getLogger().info((stop - start) + "ms per ray trace tick.");
        }
    }
}
