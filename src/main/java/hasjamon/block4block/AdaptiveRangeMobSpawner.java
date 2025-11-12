package hasjamon.block4block;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.CreatureSpawner;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;

public class AdaptiveRangeMobSpawner extends JavaPlugin implements Listener {

    private int initialSpawnerRange;
    private int spawnerRangeHigh;
    private int spawnerRangeLow;
    private double tpsThresholdLow;
    private double tpsThresholdHigh;
    private int updateIntervalTicks;
    private boolean affectNaturallyGenerated;
    private boolean updatePreexistingSpawners;
    private int chunksPerTick;
    private int currentSpawnerRange;
    private boolean debugMode;
    private Set<String> disabledWorlds;
    private int playerChunkRadius;
    private boolean onlyUpdateNearPlayers;

    // Key for marking spawners as player-placed
    private static final String PLAYER_PLACED_KEY = "player_placed";

    // Cached NamespacedKey for better performance
    private org.bukkit.NamespacedKey playerPlacedKey;

    // Flag to track if we're running on Paper
    private boolean isPaper;

    // Cache recently processed chunks to avoid duplicate updates
    private final Set<Long> recentlyProcessedChunks = new HashSet<>();

    @Override
    public void onEnable() {
        // Initialize the cached key
        playerPlacedKey = new org.bukkit.NamespacedKey(this, PLAYER_PLACED_KEY);

        // Current range tracking
        currentSpawnerRange = -1;

        // Check if we're running on Paper
        isPaper = checkIfPaper();
        if (isPaper) {
            getLogger().info("Running on Paper - using Paper-specific optimizations");
        } else {
            getLogger().info("Running on Spigot/Bukkit - using compatibility mode");
        }

        loadConfig();
        Bukkit.getPluginManager().registerEvents(this, this);

        // Set initial current range
        currentSpawnerRange = initialSpawnerRange;

        // Schedule cache cleanup
        new BukkitRunnable() {
            @Override
            public void run() {
                recentlyProcessedChunks.clear();
                if (debugMode) {
                    getLogger().info("Cleared chunk processing cache");
                }
            }
        }.runTaskTimer(this, 1200L, 1200L);  // Run every minute

        // Update spawners in already loaded chunks at startup - spread across ticks
        updateChunksOnStartup();

        // Start TPS monitoring and spawner adjustment scheduler
        startTPSMonitoring();

        getLogger().info("Adaptive Range Mob Spawner enabled. Initial spawner range: " + initialSpawnerRange + " blocks.");
        getLogger().info("TPS thresholds: Low=" + tpsThresholdLow + ", High=" + tpsThresholdHigh);
        getLogger().info("Spawner ranges: Low=" + spawnerRangeLow + ", High=" + spawnerRangeHigh);
        getLogger().info("Affect naturally generated spawners: " + affectNaturallyGenerated);
        getLogger().info("Update preexisting spawners: " + updatePreexistingSpawners);
        getLogger().info("Only update spawners near players: " + onlyUpdateNearPlayers);
    }

    // Check if we're running on Paper
    private boolean checkIfPaper() {
        try {
            // Try to access a Paper-specific class or method
            Class.forName("io.papermc.paper.entity.TeleportFlag");
            return true;
        } catch (ClassNotFoundException e) {
            try {
                // Try another Paper-specific class (for older versions)
                Class.forName("com.destroystokyo.paper.event.server.ServerTickStartEvent");
                return true;
            } catch (ClassNotFoundException ex) {
                return false;
            }
        }
    }

    private void loadConfig() {
        saveDefaultConfig();
        reloadConfig();
        FileConfiguration config = getConfig();

        // Set the initial activation range for spawners (practical max is 128 due to vanilla limitations)
        initialSpawnerRange = config.getInt("initial-spawner-range", 64);
        spawnerRangeHigh = config.getInt("spawner-range-high", 128);
        spawnerRangeLow = config.getInt("spawner-range-low", 16);
        tpsThresholdLow = config.getDouble("tps-threshold-low", 15.0);
        tpsThresholdHigh = config.getDouble("tps-threshold-high", 18.0);
        updateIntervalTicks = config.getInt("update-interval-ticks", 200);
        affectNaturallyGenerated = config.getBoolean("affect-naturally-generated", false);
        updatePreexistingSpawners = config.getBoolean("update-preexisting-spawners", false);
        chunksPerTick = config.getInt("chunks-per-tick", 5);
        debugMode = config.getBoolean("debug-mode", false);
        onlyUpdateNearPlayers = config.getBoolean("only-update-near-players", true);
        playerChunkRadius = config.getInt("player-chunk-radius", 5);

        // Load disabled worlds
        disabledWorlds = new HashSet<>(config.getStringList("disabled-worlds"));

        // Create default config sections if they don't exist
        if (!config.contains("chunks-per-tick")) {
            config.set("chunks-per-tick", chunksPerTick);
            config.set("debug-mode", debugMode);
            config.set("only-update-near-players", onlyUpdateNearPlayers);
            config.set("player-chunk-radius", playerChunkRadius);
            config.set("disabled-worlds", new ArrayList<String>());
            saveConfig();
        }
    }

    // Spread chunk updates across multiple ticks to reduce startup lag
    private void updateChunksOnStartup() {
        new BukkitRunnable() {
            private final Chunk[] chunks = Bukkit.getWorlds().stream()
                    .filter(world -> !disabledWorlds.contains(world.getName()))
                    .flatMap(world -> Arrays.stream(world.getLoadedChunks()))
                    .toArray(Chunk[]::new);
            private int index = 0;

            @Override
            public void run() {
                for (int i = 0; i < chunksPerTick && index < chunks.length; i++, index++) {
                    updateSpawnersInChunk(chunks[index], initialSpawnerRange);
                }
                if (index >= chunks.length) {
                    cancel(); // Stop task when all chunks are processed
                    getLogger().info("All spawners updated on startup (" + chunks.length + " chunks processed).");
                }
            }
        }.runTaskTimer(this, 20L, 1L); // Run every tick, but start after 1 second to let server finish loading
    }

    private void startTPSMonitoring() {
        new BukkitRunnable() {
            @Override
            public void run() {
                double currentTPS = getTPS();
                int newRange;

                if (currentTPS <= tpsThresholdLow) {
                    newRange = spawnerRangeLow;
                    logDebug("TPS below threshold (" + currentTPS + "). Setting spawner range to " + newRange);
                } else if (currentTPS >= tpsThresholdHigh) {
                    newRange = spawnerRangeHigh;
                    logDebug("TPS above threshold (" + currentTPS + "). Setting spawner range to " + newRange);
                } else {
                    // TPS is between thresholds, maintain current range
                    return;
                }

                // Skip if range hasn't changed
                if (newRange == currentSpawnerRange) {
                    logDebug("Spawner range already at " + newRange + ", skipping update");
                    return;
                }

                currentSpawnerRange = newRange;

                // Update spawners in chunks based on configuration
                updateAllSpawners(newRange);
            }
        }.runTaskTimer(this, updateIntervalTicks, updateIntervalTicks);
    }

    private void updateAllSpawners(int newRange) {
        if (onlyUpdateNearPlayers) {
            // Only update chunks near players
            Set<Long> processedChunkKeys = new HashSet<>();

            for (Player player : Bukkit.getOnlinePlayers()) {
                World world = player.getWorld();

                // Skip disabled worlds
                if (disabledWorlds.contains(world.getName())) {
                    continue;
                }

                Chunk centerChunk = player.getLocation().getChunk();

                for (int dx = -playerChunkRadius; dx <= playerChunkRadius; dx++) {
                    for (int dz = -playerChunkRadius; dz <= playerChunkRadius; dz++) {
                        // Skip chunks outside circular radius
                        if (dx*dx + dz*dz > playerChunkRadius*playerChunkRadius) {
                            continue;
                        }

                        int x = centerChunk.getX() + dx;
                        int z = centerChunk.getZ() + dz;

                        // Create a unique key for this chunk to avoid processing duplicates
                        long chunkKey = (((long)world.hashCode()) << 32) | ((x & 0xFFFFFFFFL) << 16) | (z & 0xFFFFFFFFL);

                        if (processedChunkKeys.add(chunkKey)) {
                            if (world.isChunkLoaded(x, z)) {
                                Chunk chunk = world.getChunkAt(x, z);
                                updateSpawnersInChunk(chunk, newRange);
                            }
                        }
                    }
                }
            }

            logDebug("Updated spawners in " + processedChunkKeys.size() + " chunks near players");
        } else {
            // Update all loaded chunks
            int count = 0;
            for (World world : Bukkit.getWorlds()) {
                if (disabledWorlds.contains(world.getName())) {
                    continue;
                }

                for (Chunk chunk : world.getLoadedChunks()) {
                    updateSpawnersInChunk(chunk, newRange);
                    count++;
                }
            }
            logDebug("Updated spawners in all " + count + " loaded chunks");
        }
    }

    // Get server TPS (Ticks Per Second)
    private double getTPS() {
        if (isPaper) {
            try {
                // Use Paper's getTPS method (available in modern Paper versions)
                Method getTpsMethod = Bukkit.getServer().getClass().getMethod("getTPS");
                double[] tps = (double[]) getTpsMethod.invoke(Bukkit.getServer());
                return tps[0]; // Get the 1-minute TPS average
            } catch (Exception e) {
                logDebug("Failed to get TPS via Paper's getTPS method: " + e.getMessage());
            }

            try {
                // Try Paper's getTickTimes method as fallback
                Method getTickTimesMethod = Bukkit.getServer().getClass().getMethod("getTickTimes");
                double[] tickTimes = (double[]) getTickTimesMethod.invoke(Bukkit.getServer());

                // Calculate average MSPT from recent ticks
                double mspt = Arrays.stream(tickTimes)
                        .limit(100) // Use last 100 ticks
                        .average()
                        .orElse(50.0);

                // Convert MSPT to TPS (capped at 20)
                return Math.min(20.0, 1000.0 / Math.max(mspt, 1.0));
            } catch (Exception e) {
                logDebug("Failed to get TPS via Paper's getTickTimes method: " + e.getMessage());
            }
        }

        // Fall back to reflection method for Spigot servers
        try {
            Object serverInstance = Bukkit.getServer().getClass().getMethod("getServer").invoke(Bukkit.getServer());

            // Try "recentTps" field first (older versions)
            try {
                Field tpsField = serverInstance.getClass().getField("recentTps");
                double[] tps = (double[]) tpsField.get(serverInstance);
                return tps[0];
            } catch (NoSuchFieldException e) {
                // Try alternative field names or methods for newer versions
                try {
                    Method getTpsMethod = serverInstance.getClass().getMethod("getTPS");
                    double[] tps = (double[]) getTpsMethod.invoke(serverInstance);
                    return tps[0];
                } catch (Exception ex) {
                    logDebug("Could not find TPS method in server instance");
                }
            }
        } catch (Exception ex) {
            getLogger().warning("Failed to get server TPS: " + ex.getMessage());
        }

        // If all methods fail, return 20.0 as default
        getLogger().warning("Unable to retrieve server TPS - defaulting to 20.0. Plugin may not adjust spawner ranges correctly.");
        return 20.0;
    }

    // Update all spawners in a chunk with the given range
    private void updateSpawnersInChunk(Chunk chunk, int range) {
        // Skip if chunk is in a disabled world
        if (disabledWorlds.contains(chunk.getWorld().getName())) {
            return;
        }

        // Create a unique key for this chunk
        long chunkKey = (((long)chunk.getWorld().hashCode()) << 32) | ((chunk.getX() & 0xFFFFFFFFL) << 16) | (chunk.getZ() & 0xFFFFFFFFL);

        // Skip if recently processed to avoid unnecessary work
        if (recentlyProcessedChunks.contains(chunkKey)) {
            return;
        }

        recentlyProcessedChunks.add(chunkKey);

        if (isPaper) {
            // Paper-specific async chunk loading if needed
            CompletableFuture<Chunk> futureChunk = CompletableFuture.completedFuture(chunk);

            // If the chunk isn't loaded, load it asynchronously (Paper API)
            if (!chunk.isLoaded()) {
                try {
                    Method getChunkAtAsyncMethod = chunk.getWorld().getClass().getMethod("getChunkAtAsync", int.class, int.class);
                    //noinspection unchecked
                    futureChunk = (CompletableFuture<Chunk>) getChunkAtAsyncMethod.invoke(chunk.getWorld(), chunk.getX(), chunk.getZ());
                } catch (Exception e) {
                    logDebug("Failed to use Paper's async chunk loading: " + e.getMessage());
                }
            }

            // Process the chunk when it's available
            futureChunk.thenAccept(loadedChunk -> {
                if (loadedChunk != null && loadedChunk.isLoaded()) {
                    // We need to run on the main thread for block state updates
                    Bukkit.getScheduler().runTask(this, () -> {
                        int count = 0;
                        for (BlockState blockState : loadedChunk.getTileEntities()) {
                            if (blockState.getType() == Material.SPAWNER) {
                                if (processSpawner(blockState.getBlock(), range)) {
                                    count++;
                                }
                            }
                        }
                        if (count > 0 && debugMode) {
                            getLogger().info("Updated " + count + " spawners in chunk " +
                                    loadedChunk.getWorld().getName() + " [" + loadedChunk.getX() + "," + loadedChunk.getZ() + "]");
                        }
                    });
                }
            });
        } else {
            // Standard Bukkit/Spigot approach
            int count = 0;
            for (BlockState blockState : chunk.getTileEntities()) {
                if (blockState.getType() == Material.SPAWNER) {
                    if (processSpawner(blockState.getBlock(), range)) {
                        count++;
                    }
                }
            }

            if (count > 0 && debugMode) {
                getLogger().info("Updated " + count + " spawners in chunk " +
                        chunk.getWorld().getName() + " [" + chunk.getX() + "," + chunk.getZ() + "]");
            }
        }
    }

    // Process individual spawner - extracted to avoid duplicate code
    // Returns true if spawner was updated
    private boolean processSpawner(Block block, int range) {
        // Check if this is a player-placed spawner or if we should update it
        boolean shouldUpdate = isPlayerPlacedOrUpdateable(block);

        if (shouldUpdate) {
            updateSpawnerRange(block, range);
            return true;
        } else if (!affectNaturallyGenerated) {
            // If not a player-placed spawner and we shouldn't affect naturally generated ones,
            // reset to vanilla range (16)
            updateSpawnerRange(block, 16);
            return true;
        }

        return false;
    }

    // Check if a spawner is player-placed or if we should update it based on config
    private boolean isPlayerPlacedOrUpdateable(Block block) {
        // Get the block's PersistentDataContainer from its BlockState
        BlockState state = block.getState();
        if (state instanceof CreatureSpawner) {
            CreatureSpawner spawner = (CreatureSpawner) state;
            PersistentDataContainer pdc = spawner.getPersistentDataContainer();

            // Check if the spawner has our player-placed marker using cached key
            if (pdc.has(playerPlacedKey, PersistentDataType.INTEGER)) {
                return true;
            }

            // If updatePreexistingSpawners is true, we'll treat all spawners as updateable
            return updatePreexistingSpawners;
        }
        return false;
    }

    // Update spawner when a chunk is loaded
    @EventHandler
    public void onChunkLoad(ChunkLoadEvent event) {
        // Skip if in a disabled world
        if (disabledWorlds.contains(event.getWorld().getName())) {
            return;
        }

        // Use current range or initial range if current not set yet
        int range = currentSpawnerRange >= 0 ? currentSpawnerRange : initialSpawnerRange;

        // For Paper servers, consider using AsyncChunkLoadEvent instead
        if (isPaper) {
            try {
                // Dynamically check if we can use Paper's AsyncChunkLoadEvent
                Class<?> asyncEventClass = Class.forName("io.papermc.paper.event.world.AsyncChunkLoadEvent");
                if (asyncEventClass.isInstance(event)) {
                    // This is an async event, schedule spawner updates on the main thread
                    Bukkit.getScheduler().runTask(this, () -> updateSpawnersInChunk(event.getChunk(), range));
                    return;
                }
            } catch (ClassNotFoundException ignored) {
                // Paper's AsyncChunkLoadEvent not available, continue with normal handling
            }
        }

        // Normal handling for Bukkit/Spigot or older Paper versions
        updateSpawnersInChunk(event.getChunk(), range);
    }

    // Update spawner when a player places one and mark it as player-placed
    @EventHandler
    public void onSpawnerPlace(BlockPlaceEvent event) {
        if (event.getBlock().getType() == Material.SPAWNER) {
            // Skip if in a disabled world
            if (disabledWorlds.contains(event.getBlock().getWorld().getName())) {
                return;
            }

            Block block = event.getBlock();

            // Mark the spawner as player-placed using PersistentDataContainer
            BlockState state = block.getState();
            if (state instanceof CreatureSpawner) {
                CreatureSpawner spawner = (CreatureSpawner) state;

                // Store a marker in the PersistentDataContainer using cached key
                PersistentDataContainer pdc = spawner.getPersistentDataContainer();
                pdc.set(playerPlacedKey, PersistentDataType.INTEGER, 1);
                spawner.update();

                // Use current range or initial range if current not set yet
                int range = currentSpawnerRange >= 0 ? currentSpawnerRange : initialSpawnerRange;

                // Now update the range
                updateSpawnerRange(block, range);

                if (debugMode) {
                    getLogger().info("Player " + event.getPlayer().getName() +
                            " placed a spawner at " + block.getWorld().getName() +
                            " [" + block.getX() + "," + block.getY() + "," + block.getZ() + "]" +
                            " - Range set to " + range);
                }
            }
        }
    }

    // Helper method to update a spawner's required player range
    private void updateSpawnerRange(Block block, int range) {
        if (block.getType() == Material.SPAWNER) {
            CreatureSpawner spawner = (CreatureSpawner) block.getState();

            // Only update if the range is different
            if (spawner.getRequiredPlayerRange() != range) {
                spawner.setRequiredPlayerRange(range);
                spawner.update();
            }
        }
    }

    // Helper method for debug logging
    private void logDebug(String message) {
        if (debugMode) {
            getLogger().log(Level.INFO, "[Debug] " + message);
        }
    }

    // Add commands for reloading and status
    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("adaptivespawner")) {
            if (!sender.hasPermission("block4block.admin")) {
                sender.sendMessage("§cYou don't have permission to use this command.");
                return true;
            }

            if (args.length == 0) {
                // Show status
                sender.sendMessage("§e===== Adaptive Range Mob Spawner Status =====");
                sender.sendMessage("§7Current TPS: §f" + String.format("%.2f", getTPS()));
                sender.sendMessage("§7Current Spawner Range: §f" + currentSpawnerRange);
                sender.sendMessage("§7TPS Thresholds: §flow=" + tpsThresholdLow + ", high=" + tpsThresholdHigh);
                sender.sendMessage("§7Spawner Ranges: §flow=" + spawnerRangeLow + ", high=" + spawnerRangeHigh);
                sender.sendMessage("§7Debug Mode: §f" + (debugMode ? "Enabled" : "Disabled"));
                sender.sendMessage("§7Updating Near Players Only: §f" + (onlyUpdateNearPlayers ? "Yes" : "No"));
                return true;
            } else if (args[0].equalsIgnoreCase("reload")) {
                reloadConfig();
                loadConfig();
                sender.sendMessage("§aAdaptive Range Mob Spawner config reloaded!");
                return true;
            } else if (args[0].equalsIgnoreCase("debug")) {
                debugMode = !debugMode;
                getConfig().set("debug-mode", debugMode);
                saveConfig();
                sender.sendMessage("§aDebug mode " + (debugMode ? "enabled" : "disabled"));
                return true;
            } else if (args[0].equalsIgnoreCase("update")) {
                // Force an update of all spawners
                int range = currentSpawnerRange >= 0 ? currentSpawnerRange : initialSpawnerRange;
                sender.sendMessage("§aForcing update of all spawners to range: " + range);

                // Run slightly delayed to avoid blocking command response
                new BukkitRunnable() {
                    @Override
                    public void run() {
                        updateAllSpawners(range);
                        sender.sendMessage("§aSpawner update complete.");
                    }
                }.runTaskLater(this, 5L);
                return true;
            }
        }
        return false;
    }
}