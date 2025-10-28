package kaiakk.lagStabilizer;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.*;
import org.bukkit.plugin.Plugin;
import org.bukkit.event.Listener;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.BlockRedstoneEvent;
import org.bukkit.event.entity.EntitySpawnEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.Location;

import java.io.File;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.FileStore;
import java.nio.file.FileSystems;
import java.lang.management.*;
import java.net.NetworkInterface;
import java.net.InetAddress;
import java.net.Socket;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.text.DecimalFormat;
import java.util.*;
import java.util.function.Consumer;

public final class LagStabilizer extends JavaPlugin implements TabCompleter, Listener {

    private FileConfiguration config;
    private final DecimalFormat df = new DecimalFormat("#.##");
    private final List<Double> recentTPS = new ArrayList<>();
    private static final int MAX_TPS_SAMPLES = 900;

    private final Map<String, Deque<Long>> redstoneActivity = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<String, Long> redstoneThrottleUntil = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<String, Deque<Long>> tntActivity = new java.util.concurrent.ConcurrentHashMap<>();

    private String locKey(org.bukkit.Location loc) {
        return loc.getWorld().getName() + ":" + loc.getBlockX() + ":" + loc.getBlockY() + ":" + loc.getBlockZ();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onBlockRedstoneChange(BlockRedstoneEvent e) {
        try {
            String key = locKey(e.getBlock().getLocation());
            long now = System.currentTimeMillis();
            int window = config != null ? config.getInt("redstone-window-seconds", 10) : 10;
            int threshold = config != null ? config.getInt("redstone-threshold", 100) : 100;
            int duration = config != null ? config.getInt("redstone-throttle-duration-seconds", 30) : 30;

            Deque<Long> dq = redstoneActivity.computeIfAbsent(key, k -> new ArrayDeque<>());
            synchronized (dq) {
                dq.addLast(now);
                long cutoff = now - (window * 1000L);
                while (!dq.isEmpty() && dq.peekFirst() < cutoff) dq.removeFirst();
                int count = dq.size();
                Long until = redstoneThrottleUntil.getOrDefault(key, 0L);
                if (until > now) {

                    e.setNewCurrent(0);
                } else if (count >= threshold) {

                    redstoneThrottleUntil.put(key, now + (duration * 1000L));
                    e.setNewCurrent(0);
                    Bukkit.getConsoleSender().sendMessage(Component.text("[LagStabilizer] Throttling redstone at " + key + " (" + count + " events in " + window + "s)").color(NamedTextColor.RED));
                }
            }
        } catch (Throwable ignored) {}
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntitySpawn(EntitySpawnEvent e) {
        try {
            if (!(e.getEntity() instanceof TNTPrimed)) return;
            String key = locKey(e.getLocation());
            long now = System.currentTimeMillis();
            int window = config != null ? config.getInt("tnt-window-seconds", 10) : 10;
            int threshold = config != null ? config.getInt("tnt-threshold", 5) : 5;

            String action = config != null ? config.getString("tnt-throttle-action", "cancel") : "cancel";

            Deque<Long> dq = tntActivity.computeIfAbsent(key, k -> new ArrayDeque<>());
            synchronized (dq) {
                dq.addLast(now);
                long cutoff = now - (window * 1000L);
                while (!dq.isEmpty() && dq.peekFirst() < cutoff) dq.removeFirst();
                int count = dq.size();
                if (count >= threshold) {
                    if ("cancel".equalsIgnoreCase(action)) {
                        e.setCancelled(true);
                        Bukkit.getConsoleSender().sendMessage(Component.text("[LagStabilizer] Cancelled TNT spawn at " + key + " (" + count + " in " + window + "s)").color(NamedTextColor.RED));
                    } else if ("increase-fuse".equalsIgnoreCase(action)) {

                        new BukkitRunnable() {
                            @Override
                            public void run() {
                                for (Entity ent : e.getLocation().getWorld().getEntities()) {
                                    if (ent instanceof TNTPrimed) {
                                        TNTPrimed t = (TNTPrimed) ent;
                                        try {
                                            if (t.getLocation().distance(e.getLocation()) < 2.0) t.setFuseTicks(t.getFuseTicks() + 40);
                                        } catch (Throwable ignored) {}
                                    }
                                }
                            }
                        }.runTaskLater(this, 1L);
                        Bukkit.getConsoleSender().sendMessage(Component.text("[LagStabilizer] Slowing TNT at " + key + " (" + count + " in " + window + "s)").color(NamedTextColor.RED));
                    }

                    dq.clear();
                }
            }
        } catch (Throwable ignored) {}
    }

    @Override
    public void onEnable() {
        getLogger().info("LagStabilizer enabling...");
        saveDefaultConfig();
        config = getConfig();

        long interval = config.getLong("auto-clear-interval-seconds", 300L);
        if (interval > 0) {
            new BukkitRunnable() {
                @Override
                public void run() {
                    cleanEntities(Bukkit.getConsoleSender(), false);
                }
            }.runTaskTimer(this, interval * 20, interval * 20);
        }

        long monitorInterval = config.getLong("monitor-interval-minutes", 30L);
        if (monitorInterval > 0) {
            new BukkitRunnable() {
                @Override
                public void run() {
                    sendMonitorLog();
                }
            }.runTaskTimer(this, monitorInterval * 60 * 20, monitorInterval * 60 * 20);
        }

        double tpsThreshold = config.getDouble("tps-warning-threshold", 15.0);
        new BukkitRunnable() {
            @Override
            public void run() {
                checkTPS(tpsThreshold);
            }
        }.runTaskTimer(this, 20L, 100L);

        new BukkitRunnable() {
            @Override
            public void run() {
                recordTPS();
            }
        }.runTaskTimer(this, 20L, 20L);

        getCommand("stabilizer").setTabCompleter(this);
        getCommand("ping").setTabCompleter(this);

        getServer().getPluginManager().registerEvents(this, this);
    }

    @Override
    public void onDisable() {
        getLogger().info("LagStabilizer disabled.");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String cmd = command.getName().toLowerCase();

        if (cmd.equals("stabilizer")) return handleStabilizerCommand(sender, args);
        if (cmd.equals("ping")) return handlePingCommand(sender, args);

        return false;
    }

    private boolean handleStabilizerCommand(CommandSender sender, String[] args) {
        if (!sender.hasPermission("lagstabilizer.admin") && !sender.isOp()) {
            sender.sendMessage(Component.text("You do not have permission to use this command.").color(NamedTextColor.RED));
            return true;
        }

        if (args.length == 0) {
            sendStabilizerUsage(sender);
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "clean" -> {
                String[] cleanArgs = Arrays.copyOfRange(args, 1, args.length);
                cleanEntities(sender, true, cleanArgs);
                sender.sendMessage(Component.text("[LagStabilizer] Manual entity clean triggered.").color(NamedTextColor.GREEN));
            }
            case "tps" -> {
                sender.sendMessage(Component.text("=== TPS Monitor ===").color(NamedTextColor.AQUA));
                double currentTPS = getServerTPS();
                sender.sendMessage(Component.text()
                        .append(Component.text("Current TPS: ").color(NamedTextColor.GREEN))
                        .append(Component.text(df.format(currentTPS)).color(NamedTextColor.YELLOW))
                        .build());
                sender.sendMessage(Component.text("1m Avg: " + df.format(getAverageTPS(60)) +
                        " | 5m Avg: " + df.format(getAverageTPS(300)) +
                        " | 15m Avg: " + df.format(getAverageTPS(900))).color(NamedTextColor.GRAY));
                sender.sendMessage(getTPSGraph(currentTPS).color(NamedTextColor.GRAY));
            }
            case "ram" -> {
                sender.sendMessage(Component.text("=== RAM Monitor ===").color(NamedTextColor.AQUA));
                sender.sendMessage(Component.text(getRAMUsageDetailed()).color(NamedTextColor.GREEN));
            }
            case "cpu" -> {
                sender.sendMessage(Component.text("=== CPU Monitor ===").color(NamedTextColor.AQUA));
                sender.sendMessage(Component.text(getCPUUsageDetailed()).color(NamedTextColor.GREEN));
            }
            case "storage" -> {
                sender.sendMessage(Component.text("=== Storage Monitor ===").color(NamedTextColor.AQUA));
                sender.sendMessage(Component.text(getDiskUsageDetailed()).color(NamedTextColor.GREEN));
            }
            case "network" -> {
                sender.sendMessage(Component.text("=== Network Monitor ===").color(NamedTextColor.AQUA));
                sender.sendMessage(Component.text(getNetworkUsageDetailed()).color(NamedTextColor.GREEN));
            }
            case "gpu" -> {
                sender.sendMessage(Component.text("=== GPU Monitor ===").color(NamedTextColor.AQUA));
                sender.sendMessage(Component.text(getGPUUsageDetailed()).color(NamedTextColor.GREEN));
            }
            case "disk" -> {
                sender.sendMessage(Component.text("=== Disk I/O Monitor ===").color(NamedTextColor.AQUA));
                getDiskIOUsageAsync(info -> sender.sendMessage(Component.text(info).color(NamedTextColor.GREEN)));
            }
            case "world" -> {
                sender.sendMessage(Component.text("=== World Info ===").color(NamedTextColor.AQUA));
                getWorldsInfoAsync(info -> sender.sendMessage(Component.text(info).color(NamedTextColor.GREEN)));
            }
            case "debug" -> {
                sender.sendMessage(Component.text("[LagStabilizer] Debug info:").color(NamedTextColor.YELLOW));
                sender.sendMessage(Component.text(" - Plugin version: " + getPluginMeta().getVersion()).color(NamedTextColor.YELLOW));
                sender.sendMessage(Component.text(" - Auto-clear interval: " + config.getLong("auto-clear-interval-seconds")).color(NamedTextColor.YELLOW));
                sender.sendMessage(Component.text(" - Monitor interval: " + config.getLong("monitor-interval-minutes")).color(NamedTextColor.YELLOW));
                sender.sendMessage(Component.text(" - Blacklist: " + config.getStringList("blacklist")).color(NamedTextColor.YELLOW));
                sender.sendMessage(Component.text(" - TPS warning threshold: " + config.getDouble("tps-warning-threshold")).color(NamedTextColor.YELLOW));
                sender.sendMessage(Component.text(" - Server reset threshold: " + config.getDouble("tps-reset-threshold")).color(NamedTextColor.YELLOW));
            }
            case "info" -> {
                sender.sendMessage(Component.text("=== LagStabilizer Info ===").color(NamedTextColor.AQUA));
                sender.sendMessage(Component.text("Version: " + getPluginMeta().getVersion()).color(NamedTextColor.AQUA));
                sender.sendMessage(Component.text("Author: " + String.join(", ", getPluginMeta().getAuthors())).color(NamedTextColor.AQUA));
                sender.sendMessage(Component.text("Commands: /stabilizer clean|tps|ram|cpu|storage|network|gpu|disk|world|debug|info").color(NamedTextColor.AQUA));
            }
            default -> sender.sendMessage(Component.text("Unknown subcommand. Use /stabilizer (clean|tps|ram|cpu|storage|network|gpu|disk|world|debug|info)").color(NamedTextColor.RED));
        }
        return true;
    }

    private void sendStabilizerUsage(CommandSender sender) {
        sender.sendMessage(Component.text("LagStabilizer Usage:").color(NamedTextColor.AQUA));

        String[][] commands = {
                {"/stabilizer clean  ", "- Manually run entity cleaner"},
                {"/stabilizer tps    ", "- Show TPS details"},
                {"/stabilizer ram    ", "- Show detailed RAM usage"},
                {"/stabilizer cpu    ", "- Show detailed CPU usage"},
                {"/stabilizer storage", "- Show disk space info"},
                {"/stabilizer network", "- Show network info"},
                {"/stabilizer gpu    ", "- Show detailed GPU usage"},
                {"/stabilizer disk   ", "- Show disk read/write (B/s)"},
                {"/stabilizer world  ", "- Show world sizes and player count"},
                {"/stabilizer debug  ", "- Show debug info"},
                {"/stabilizer info   ", "- Plugin info"}
        };

        for (String[] cmd : commands) {
            sender.sendMessage(Component.text()
                    .append(Component.text(cmd[0]).color(NamedTextColor.AQUA))
                    .append(Component.text(cmd[1]).color(NamedTextColor.GRAY))
                    .build());
        }
    }

    private String getPluginUsageDetailed() {
        StringBuilder sb = new StringBuilder();
        Plugin[] plugins = Bukkit.getPluginManager().getPlugins();

        Map<String, Double> pluginMemory = new HashMap<>();
        Map<String, Integer> pluginTasks = new HashMap<>();

        Runtime runtime = Runtime.getRuntime();

        for (Plugin plugin : plugins) {
            long before = runtime.totalMemory() - runtime.freeMemory();

            int taskCount = Bukkit.getScheduler().getPendingTasks().stream()
                    .filter(t -> t.getOwner().equals(plugin))
                    .toList().size();

            long after = runtime.totalMemory() - runtime.freeMemory();
            double usedMB = (after - before) / (1024.0 * 1024.0);

            pluginMemory.put(plugin.getName(), usedMB);
            pluginTasks.put(plugin.getName(), taskCount);
        }

        plugins = Arrays.stream(plugins)
                .sorted(Comparator.comparingDouble(p -> -pluginMemory.getOrDefault(p.getName(), 0.0)))
                .toArray(Plugin[]::new);

        for (Plugin plugin : plugins) {
            sb.append(plugin.getName())
                    .append(" | RAM Impact: ").append(df.format(pluginMemory.getOrDefault(plugin.getName(), 0.0))).append("MB")
                    .append(" | Scheduled Tasks: ").append(pluginTasks.getOrDefault(plugin.getName(), 0))
                    .append("\n");
        }

        return sb.toString();
    }

    private void cleanEntities(CommandSender sender, boolean manual, String... args) {
        int removed = 0;
        int tntRemoved = 0;
        List<String> blacklist = config.getStringList("blacklist");

        for (World world : Bukkit.getWorlds()) {

            double x1 = Double.NEGATIVE_INFINITY, y1 = Double.NEGATIVE_INFINITY, z1 = Double.NEGATIVE_INFINITY;
            double x2 = Double.POSITIVE_INFINITY, y2 = Double.POSITIVE_INFINITY, z2 = Double.POSITIVE_INFINITY;

            if (args.length == 6) {
                try {
                    x1 = Double.parseDouble(args[0]);
                    y1 = Double.parseDouble(args[1]);
                    z1 = Double.parseDouble(args[2]);
                    x2 = Double.parseDouble(args[3]);
                    y2 = Double.parseDouble(args[4]);
                    z2 = Double.parseDouble(args[5]);
                } catch (NumberFormatException e) {
                    sender.sendMessage(Component.text("Invalid coordinates. Usage: /stabilizer clean [x1 y1 z1 x2 y2 z2]").color(NamedTextColor.RED));
                    return;
                }
            } else if (sender instanceof Player p) {
                Location loc = p.getLocation();
                int radius = 32;

                x1 = loc.getBlockX() - radius;
                y1 = Math.max(0, loc.getBlockY() - radius);
                z1 = loc.getBlockZ() - radius;

                x2 = loc.getBlockX() + radius;
                y2 = Math.min(world.getMaxHeight(), loc.getBlockY() + radius);
                z2 = loc.getBlockZ() + radius;
            }

            for (Entity e : world.getEntities()) {
                if (e instanceof Player) continue;
                if (e.customName() != null) continue;

                double ex = e.getLocation().getX();
                double ey = e.getLocation().getY();
                double ez = e.getLocation().getZ();

                if (ex < Math.min(x1, x2) || ex > Math.max(x1, x2)) continue;
                if (ey < Math.min(y1, y2) || ey > Math.max(y1, y2)) continue;
                if (ez < Math.min(z1, z2) || ez > Math.max(z1, z2)) continue;

                if (!manual && config.getBoolean("auto-pause-if-players-near", true)) {
                    boolean nearby = !world.getNearbyEntities(e.getLocation(), 32, 32, 32)
                            .stream().filter(en -> en instanceof Player).toList().isEmpty();
                    if (nearby) continue;
                }

                if (e instanceof TNTPrimed) {
                    e.remove();
                    tntRemoved++;
                    continue;
                }

                if (e instanceof Item item) {
                    if (item.getItemStack() == null) continue;
                    String type = item.getItemStack().getType().name();
                    if (manual || blacklist.contains(type)) {
                        item.remove();
                        removed++;
                    }
                } else if (e instanceof ExperienceOrb || e instanceof Projectile) {
                    e.remove();
                    removed++;
                }
            }
        }

        Component msg = Component.text()
                .append(Component.text("[LagStabilizer] Cleared " + removed + " entities"))
                .append(Component.text(tntRemoved > 0 ? " and " + tntRemoved + " explosives" : ""))
                .append(Component.text("."))
                .color(NamedTextColor.YELLOW)
                .build();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.isOp() || p.hasPermission("lagstabilizer.admin")) p.sendMessage(msg);
        }
        Bukkit.getConsoleSender().sendMessage(msg);
    }

    private void getWorldsInfoAsync(Consumer<String> callback) {
        new BukkitRunnable() {
            @Override
            public void run() {
                StringBuilder sb = new StringBuilder();
                int totalPlayers = 0;

                for (World world : Bukkit.getWorlds()) {
                    File worldFolder = world.getWorldFolder();
                    long sizeMB = getFolderSize(worldFolder) / (1024 * 1024);
                    int worldPlayers = world.getPlayers().size();
                    totalPlayers += worldPlayers;

                    sb.append(world.getName())
                            .append(" | Size: ").append(sizeMB).append(" MB")
                            .append(" | Players: ").append(worldPlayers)
                            .append("\n");
                }

                sb.append("Total Online Players: ").append(totalPlayers);

                new BukkitRunnable() {
                    @Override
                    public void run() {
                        callback.accept(sb.toString());
                    }
                }.runTask(LagStabilizer.this);
            }
        }.runTaskAsynchronously(this);
    }


    private long getFolderSize(File folder) {
        long length = 0;
        Deque<File> stack = new ArrayDeque<>();
        stack.push(folder);

        while (!stack.isEmpty()) {
            File current = stack.pop();
            File[] files = current.listFiles();
            if (files == null) continue;

            for (File f : files) {
                if (f.isFile()) {
                    length += f.length();
                } else if (f.isDirectory()) {
                    stack.push(f);
                }
            }
        }

        return length;
    }

    private boolean handlePingCommand(CommandSender sender, String[] args) {
        if (args.length == 0) {
            if (sender instanceof Player p) {
                int ping = getPlayerPing(p);
                p.sendMessage(Component.text()
                        .append(Component.text("Your ping: ").color(NamedTextColor.GREEN))
                        .append(Component.text(ping + "ms").color(getPingColor(ping)))
                        .build());
            } else {
                Bukkit.getOnlinePlayers().forEach(player -> {
                    int ping = getPlayerPing(player);
                    sender.sendMessage(Component.text()
                            .append(Component.text(player.getName() + "'s ping: ").color(NamedTextColor.GREEN))
                            .append(Component.text(ping + "ms").color(getPingColor(ping)))
                            .build());
                });
            }
            return true;
        }

        Player target = Bukkit.getPlayer(args[0]);
        if (target == null) {
            sender.sendMessage(Component.text("Player not found or offline: " + args[0]).color(NamedTextColor.RED));
            return true;
        }

        int targetPing = getPlayerPing(target);
        sender.sendMessage(Component.text()
                .append(Component.text(target.getName() + "'s ping: ").color(NamedTextColor.GREEN))
                .append(Component.text(targetPing + "ms").color(getPingColor(targetPing)))
                .build());
        return true;
    }

    private TextColor getPingColor(int ping) {
        int good = config != null ? config.getInt("ping-good-threshold", 50) : 50;
        int fair = config != null ? config.getInt("ping-fair-threshold", 150) : 150;
        if (ping < good) return NamedTextColor.GREEN;
        else if (ping < fair) return NamedTextColor.YELLOW;
        else return NamedTextColor.RED;
    }

    private int getPlayerPing(Player player) {
        try {
            return player.getPing();
        } catch (Throwable t) {
            try {
                Object handle = player.getClass().getMethod("getHandle").invoke(player);
                return (int) handle.getClass().getField("ping").get(handle);
            } catch (Throwable ignored) {
                return -1;
            }
        }
    }

    private double getServerTPS() {
        try {
            return Bukkit.getServer().getTPS()[0];
        } catch (Throwable ignored) {
            return 20.0;
        }
    }

    private String getRAMUsageDetailed() {
        Runtime runtime = Runtime.getRuntime();
        long used = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024);
        long free = runtime.freeMemory() / (1024 * 1024);
        long total = runtime.totalMemory() / (1024 * 1024);
        long max = runtime.maxMemory() / (1024 * 1024);
        double percent = (double) used / max * 100;

        List<GarbageCollectorMXBean> gcBeans = ManagementFactory.getGarbageCollectorMXBeans();
        long gcCount = gcBeans.stream().mapToLong(GarbageCollectorMXBean::getCollectionCount).sum();

        return "Used: " + used + "MB | Free: " + free + "MB | Total: " + total + "MB | Max: " + max + "MB (" + df.format(percent) + "%)\n"
                + "\nGC collections: " + gcCount;
    }

    private String getCPUUsageDetailed() {
        try {
            com.sun.management.OperatingSystemMXBean osBean =
                    (com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
            double sysCPU = osBean.getCpuLoad() * 100;
            double procCPU = osBean.getProcessCpuLoad() * 100;
            int logical = osBean.getAvailableProcessors();
            double loadAvg = osBean.getSystemLoadAverage();
            String os = System.getProperty("os.name") + " " + System.getProperty("os.version");

            StringBuilder sb = new StringBuilder();
            sb.append("System CPU: ").append(df.format(sysCPU)).append("% | Process CPU: ").append(df.format(procCPU)).append("%\n");

            String osName = System.getProperty("os.name").toLowerCase();
            String model = null;
            String physCores = "";
            String maxClock = "";

            if (osName.contains("win")) {
                try {
                    ProcessBuilder pb = new ProcessBuilder("wmic", "cpu", "get", "Name,NumberOfCores,NumberOfLogicalProcessors,MaxClockSpeed", "/format:csv");
                    pb.redirectErrorStream(true);
                    Process p = pb.start();
                    BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()));
                    String line;
                    String last = null;
                    while ((line = br.readLine()) != null) {
                        line = line.trim();
                        if (line.isEmpty()) continue;
                        if (line.toLowerCase().startsWith("node")) continue;
                        last = line;
                    }
                    p.waitFor();
                    if (last != null) {
                        String[] parts = last.split(",");

                        for (String part : parts) part = part.trim();
                        if (parts.length >= 5) {
                            model = parts[2].trim();
                            physCores = parts[3].trim();
                            maxClock = parts[1].trim() + " MHz";
                        }
                    }
                } catch (Throwable ignored) {}
            } else if (osName.contains("linux")) {
                try {
                    List<String> lines = Files.readAllLines(Paths.get("/proc/cpuinfo"));
                    for (String l : lines) {
                        if (model == null && l.toLowerCase().startsWith("model name")) {
                            int idx = l.indexOf(":");
                            if (idx >= 0) model = l.substring(idx + 1).trim();
                        }
                        if (physCores.isEmpty() && l.toLowerCase().startsWith("cpu cores")) {
                            int idx = l.indexOf(":");
                            if (idx >= 0) physCores = l.substring(idx + 1).trim();
                        }
                        if (maxClock.isEmpty() && l.toLowerCase().startsWith("cpu mhz")) {
                            int idx = l.indexOf(":");
                            if (idx >= 0) {
                                try { double mhz = Double.parseDouble(l.substring(idx + 1).trim()); maxClock = df.format(mhz) + " MHz"; } catch (Exception ignored) {}
                            }
                        }
                        if (model != null && !physCores.isEmpty() && !maxClock.isEmpty()) break;
                    }
                } catch (Throwable ignored) {}
            } else if (osName.contains("mac") || osName.contains("darwin")) {
                try {
                    ProcessBuilder pb = new ProcessBuilder("sysctl", "-n", "machdep.cpu.brand_string");
                    pb.redirectErrorStream(true);
                    Process p = pb.start();
                    BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()));
                    String line = br.readLine();
                    if (line != null) model = line.trim();
                    p.waitFor();

                    pb = new ProcessBuilder("sysctl", "-n", "hw.physicalcpu");
                    pb.redirectErrorStream(true);
                    p = pb.start();
                    br = new BufferedReader(new InputStreamReader(p.getInputStream()));
                    line = br.readLine();
                    if (line != null) physCores = line.trim();
                    p.waitFor();
                } catch (Throwable ignored) {}
            }

            if (model != null) sb.append("Model: ").append(model).append(" | ");
            if (!physCores.isEmpty()) sb.append("Cores: ").append(logical).append(" (phys ").append(physCores).append(") | ");
            else sb.append("Cores: ").append(logical).append(" | ");
            if (!maxClock.isEmpty()) sb.append("MaxClock: ").append(maxClock).append(" | ");
            sb.append("Load Avg: ").append(df.format(loadAvg)).append(" | OS: ").append(os);

            return sb.toString();
        } catch (Throwable e) {
            return "CPU Usage: Unknown";
        }
    }

    private String getDiskUsageDetailed() {
        StringBuilder sb = new StringBuilder();
        try {
            for (FileStore store : FileSystems.getDefault().getFileStores()) {
                try {
                    long total = store.getTotalSpace();
                    long free = store.getUsableSpace();
                    long used = total - free;
                    double percent = total > 0 ? ((double) used / total) * 100.0 : 0.0;
                    String name = store.name();
                    String type = store.type();
                    double usedGb = used / (1024.0 * 1024.0 * 1024.0);
                    double freeGb = free / (1024.0 * 1024.0 * 1024.0);
                    double totalGb = total / (1024.0 * 1024.0 * 1024.0);
                    if (name == null || name.isEmpty()) name = store.toString();
                    sb.append(name).append(" (").append(type).append(") - Used: ")
                            .append(df.format(usedGb)).append("GB | Free: ")
                            .append(df.format(freeGb)).append("GB | Total: ")
                            .append(df.format(totalGb)).append("GB (")
                            .append(df.format(percent)).append("%)\n");
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable e) {

            try {
                File root = new File(".");
                long total = root.getTotalSpace() / (1024 * 1024 * 1024);
                long free = root.getFreeSpace() / (1024 * 1024 * 1024);
                long used = total - free;
                double percent = ((double) used / total) * 100;
                return "Used: " + used + "GB | Free: " + free + "GB | Total: " + total + "GB (" + df.format(percent) + "%)";
            } catch (Throwable ignored) {}
        }
        String out = sb.toString().trim();
        return out.isEmpty() ? "No disk information available" : out;
    }

    private String getNetworkUsageDetailed() {
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            StringBuilder sb = new StringBuilder();
            int activeIfCount = 0;
            while (interfaces.hasMoreElements()) {
                NetworkInterface ni = interfaces.nextElement();
                try {
                    if (!ni.isUp() || ni.isLoopback() || ni.isVirtual()) continue;
                } catch (Throwable ignored) {
                    continue;
                }
                activeIfCount++;
                sb.append("Interface: ").append(ni.getDisplayName()).append("\n");
                sb.append(" - MTU: ").append(ni.getMTU()).append("\n");
                byte[] mac = null;
                try { mac = ni.getHardwareAddress(); } catch (Throwable ignored) {}
                sb.append(" - MAC: ").append(mac == null ? "N/A" : Arrays.toString(mac)).append("\n");
                sb.append(" - Index: ").append(ni.getIndex()).append("\n");
            }

            if (activeIfCount == 0) sb.append("No active network interfaces detected.\n");

            try {
                InetAddress local = InetAddress.getLocalHost();
                sb.append("Local Host: ").append(local.getHostName()).append(" (").append(local.getHostAddress()).append(")\n");
            } catch (Throwable ignored) {}

            sb.append("Active interfaces: ").append(activeIfCount).append("\n");

            Collection<? extends Player> players = Bukkit.getOnlinePlayers();
            if (players.isEmpty()) {
                sb.append("Players: 0\n");
            } else {
                int sum = 0;
                int min = Integer.MAX_VALUE;
                int max = Integer.MIN_VALUE;
                int cnt = 0;
                for (Player p : players) {
                    int ping = getPlayerPing(p);
                    if (ping < 0) continue;
                    sum += ping;
                    cnt++;
                    min = Math.min(min, ping);
                    max = Math.max(max, ping);
                }
                if (cnt > 0) sb.append("Players: ").append(players.size()).append(" | Avg ping: ").append(df.format((double) sum / cnt)).append(" ms (min ").append(min).append(" / max ").append(max).append(")\n");
                else sb.append("Players: ").append(players.size()).append(" | No ping data\n");
            }

            List<String> hosts = config != null ? config.getStringList("network-latency-hosts") : Arrays.asList("8.8.8.8", "1.1.1.1");
            int port = config != null ? config.getInt("network-connect-port", 53) : 53;
            int timeout = config != null ? config.getInt("network-timeout-ms", 500) : 500;
            List<Long> latencies = new ArrayList<>();
            for (String host : hosts) {
                if (host == null || host.isBlank()) continue;
                long start = System.nanoTime();
                try (Socket socket = new Socket()) {
                    SocketAddress addr = new InetSocketAddress(host.trim(), port);
                    socket.connect(addr, timeout);
                    long elapsed = (System.nanoTime() - start) / 1_000_000;
                    latencies.add(elapsed);
                    sb.append(" - Latency to ").append(host).append(": ").append(elapsed).append(" ms\n");
                } catch (Throwable e) {
                    sb.append(" - Latency to ").append(host).append(": unreachable\n");
                }
            }

            if (!latencies.isEmpty()) {
                double sum = 0;
                long min = Long.MAX_VALUE;
                long max = Long.MIN_VALUE;
                for (Long l : latencies) {
                    sum += l;
                    min = Math.min(min, l);
                    max = Math.max(max, l);
                }
                double avg = sum / latencies.size();
                sb.append("Outbound latency (min/avg/max): ").append(min).append("/").append(df.format(avg)).append("/").append(max).append(" ms\n");
            }

            return sb.toString();
        } catch (Exception e) {
            return "Unable to retrieve network data.";
        }
    }

    private String getTPSStatus(double tps) {
        double stable = config != null ? config.getDouble("tps-stable-threshold", 18.0) : 18.0;
        double moderate = config != null ? config.getDouble("tps-moderate-threshold", 14.0) : 14.0;
        if (tps >= stable) return Component.text("Stable").color(NamedTextColor.GREEN).toString();
        else if (tps >= moderate) return Component.text("Moderate").color(NamedTextColor.YELLOW).toString();
        else return Component.text("Unstable!").color(NamedTextColor.RED).toString();
    }

    private void checkTPS(double threshold) {
        double tps = getServerTPS();
        if (tps < threshold) {
            Bukkit.getConsoleSender().sendMessage(
                    Component.text("[LagStabilizer] Lag spike detected! TPS: " + df.format(tps))
                            .color(NamedTextColor.RED)
            );
            Component warning = Component.text("[LagStabilizer] Warning! Server TPS dropped below " + threshold + ". Current TPS: " + df.format(tps))
                    .color(NamedTextColor.RED);
            Bukkit.getOnlinePlayers().stream()
                    .filter(p -> p.isOp() || p.hasPermission("lagstabilizer.admin"))
                    .forEach(p -> p.sendMessage(warning));            if (config.getBoolean("enable-tps-auto-reset", false) && tps < config.getDouble("tps-reset-threshold", 5.0)) {
                Bukkit.getConsoleSender().sendMessage(Component.text("[LagStabilizer] TPS below reset threshold. Restarting server...").color(NamedTextColor.RED));
                Bukkit.shutdown();
            }
        }
    }

    private int tpsCounter = 0;

    private void recordTPS() {
        tpsCounter++;
        if (tpsCounter < 5) return;
        tpsCounter = 0;

        double tps = getServerTPS();
        recentTPS.add(tps);
        if (recentTPS.size() > MAX_TPS_SAMPLES) recentTPS.remove(0);
    }

    private double getAverageTPS(int seconds) {
        if (recentTPS.isEmpty()) return 20.0;

        int sampleRate = 20 / 5;
        int size = Math.min(seconds * sampleRate, recentTPS.size());
        double sum = 0;
        for (int i = recentTPS.size() - size; i < recentTPS.size(); i++) {
            sum += recentTPS.get(i);
        }
        return sum / size;
    }

    private Component getTPSGraph(double tps) {
        int bars = (int) Math.round((tps / 20.0) * 20);
        StringBuilder graph = new StringBuilder();
        for (int i = 0; i < 20; i++) {
            graph.append(i < bars ? "|" : " ");
        }

        String arrow = "→";
        if (recentTPS.size() > 1) {
            double diff = tps - recentTPS.get(recentTPS.size() - 2);
            if (diff > 0.05) arrow = "↑";
            else if (diff < -0.05) arrow = "↓";
        }

        return Component.text(graph + " " + df.format(tps) + " TPS " + arrow)
                .color(getTPSColor(tps));
    }

    private TextColor getTPSColor(double tps) {
        double stable = config != null ? config.getDouble("tps-stable-threshold", 18.0) : 18.0;
        double moderate = config != null ? config.getDouble("tps-moderate-threshold", 14.0) : 14.0;
        if (tps >= stable) return NamedTextColor.GREEN;
        else if (tps >= moderate) return NamedTextColor.YELLOW;
        else return NamedTextColor.RED;
    }

    private long lastFullMonitor = 0;

    private void sendMonitorLog() {
        long now = System.currentTimeMillis();

        double currentTPS = getServerTPS();
        double avgTPS = getAverageTPS(300);

        long monitorIntervalMillis = config.getLong("monitor-interval-minutes", 30L) * 60 * 1000;
        double tpsWarningThreshold = config.getDouble("tps-warning-threshold", 18.0);

        if (now - lastFullMonitor > monitorIntervalMillis) {
            lastFullMonitor = now;

            String ram = getRAMUsageDetailed();
            String cpu = getCPUUsageDetailed();
            String disk = getDiskUsageDetailed();
            String net = getNetworkUsageDetailed();
            String gpu = getGPUUsageDetailed();
            int online = Bukkit.getOnlinePlayers().size();

            var console = Bukkit.getConsoleSender();
            console.sendMessage(Component.text("========== [LagStabilizer Monitor] ==========").color(NamedTextColor.DARK_GRAY));
            console.sendMessage(Component.text("Current TPS: " + df.format(currentTPS)).color(NamedTextColor.GRAY));
            console.sendMessage(Component.text("Average TPS: " + df.format(avgTPS)).color(NamedTextColor.GRAY));
            console.sendMessage(getTPSGraph(avgTPS));
            console.sendMessage(Component.text(ram).color(NamedTextColor.GRAY));
            console.sendMessage(Component.text(cpu).color(NamedTextColor.GRAY));
            console.sendMessage(Component.text(disk).color(NamedTextColor.GRAY));
            console.sendMessage(Component.text(net).color(NamedTextColor.GRAY));
            console.sendMessage(Component.text(gpu).color(NamedTextColor.GRAY));
            console.sendMessage(Component.text("Online Players: " + online).color(NamedTextColor.GRAY));

            String hotspots = getHotspotsReport();
            if (hotspots != null && !hotspots.isEmpty()) {
                Bukkit.getConsoleSender().sendMessage(Component.text("Hotspots:\n" + hotspots).color(NamedTextColor.GRAY));
            }

            for (World world : Bukkit.getWorlds()) {
                long sizeMB = getFolderSize(world.getWorldFolder()) / (1024 * 1024);
                Bukkit.getConsoleSender().sendMessage(Component.text("World '" + world.getName() + "' size: " + sizeMB + " MB").color(NamedTextColor.GRAY));
            }
            Bukkit.getConsoleSender().sendMessage(Component.text("=============================================").color(NamedTextColor.DARK_GRAY));
        } else {

            if (currentTPS < tpsWarningThreshold) {
                Bukkit.getConsoleSender().sendMessage(
                        Component.text("[LagStabilizer] TPS Warning! Current: " + df.format(currentTPS) + " | Avg: " + df.format(avgTPS))
                                .color(NamedTextColor.RED)
                );
            }
        }
    }

    private String getHotspotsReport() {
        try {
            int reportCount = config != null ? config.getInt("hotspot-report-count", 5) : 5;
            int window = config != null ? config.getInt("redstone-window-seconds", 10) : 10;
            long cutoff = System.currentTimeMillis() - (window * 1000L);

            Map<String, Integer> counts = new HashMap<>();
            for (Map.Entry<String, Deque<Long>> e : redstoneActivity.entrySet()) {
                Deque<Long> dq = e.getValue();
                synchronized (dq) {
                    while (!dq.isEmpty() && dq.peekFirst() < cutoff) dq.removeFirst();
                    if (!dq.isEmpty()) counts.put(e.getKey(), dq.size());
                }
            }

            Map<String, Integer> tntCounts = new HashMap<>();
            for (Map.Entry<String, Deque<Long>> e : tntActivity.entrySet()) {
                Deque<Long> dq = e.getValue();
                synchronized (dq) {
                    while (!dq.isEmpty() && dq.peekFirst() < cutoff) dq.removeFirst();
                    if (!dq.isEmpty()) tntCounts.put(e.getKey(), dq.size());
                }
            }

            List<String> lines = new ArrayList<>();
            counts.entrySet().stream()
                    .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
                    .limit(reportCount)
                    .forEach(en -> lines.add("REDSTONE @ " + en.getKey() + " -> " + en.getValue() + " events/" + window + "s"));

            tntCounts.entrySet().stream()
                    .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
                    .limit(reportCount)
                    .forEach(en -> lines.add("TNT @ " + en.getKey() + " -> " + en.getValue() + " spawns/" + window + "s"));

            return String.join("\n", lines);
        } catch (Throwable ignored) {
            return "";
        }
    }

    private String getGPUUsageDetailed() {
        String os = System.getProperty("os.name").toLowerCase();
        StringBuilder sb = new StringBuilder();

        if (os.contains("win")) {
            try {
                ProcessBuilder pb = new ProcessBuilder("powershell.exe", "-NoProfile", "-Command",
                        "Get-CimInstance Win32_VideoController | Select-Object Name,AdapterRAM,DriverVersion | ConvertTo-Csv -NoTypeInformation");
                pb.redirectErrorStream(true);
                Process p = pb.start();
                BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()));
                String line;
                boolean first = true;
                int count = 0;
                while ((line = br.readLine()) != null) {
                    line = line.trim();
                    if (line.isEmpty()) continue;

                    if (first) { first = false; continue; }

                    List<String> parsed = new ArrayList<>();
                    StringBuilder cur = new StringBuilder();
                    boolean inQuotesField = false;
                    for (int i = 0; i < line.length(); i++) {
                        char c = line.charAt(i);
                        if (c == '"') {
                            inQuotesField = !inQuotesField;
                            continue;
                        }
                        if (c == ',' && !inQuotesField) {
                            parsed.add(cur.toString());
                            cur.setLength(0);
                            continue;
                        }
                        cur.append(c);
                    }
                    parsed.add(cur.toString());
                    for (int i = 0; i < parsed.size(); i++) parsed.set(i, parsed.get(i).trim());
                    String[] fields = parsed.toArray(new String[0]);
                    String name = fields.length > 0 ? fields[0] : "Unknown";
                    String ram = fields.length > 1 ? fields[1] : "";
                    String driver = fields.length > 2 ? fields[2] : "";
                    String ramMB = "";
                    try {
                        long ramBytes = Long.parseLong(ram);
                        ramMB = (ramBytes / (1024 * 1024)) + " MB";
                    } catch (Exception ignored) { ramMB = ram; }
                    sb.append("GPU: ").append(name).append(" | RAM: ").append(ramMB).append(" | Driver: ").append(driver).append("\n");
                    count++;
                }
                p.waitFor();
                if (count > 0) return sb.toString();
            } catch (Throwable ignored) {}
        }

        try {
            ProcessBuilder pb = new ProcessBuilder("nvidia-smi",
                    "--query-gpu=index,name,utilization.gpu,memory.total,memory.used,driver_version,temperature.gpu,power.draw,power.limit,fan.speed",
                    "--format=csv,noheader,nounits");
            pb.redirectErrorStream(true);
            Process p = pb.start();
            BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String line;
            int count = 0;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;
                String[] parts = line.split(",\\s*");
                if (parts.length >= 10) {
                    String idx = parts[0];
                    String name = parts[1];
                    String util = parts[2];
                    String memTotal = parts[3];
                    String memUsed = parts[4];
                    String driver = parts[5];
                    String temp = parts[6];
                    String power = parts[7];
                    String powerLimit = parts[8];
                    String fan = parts[9];
                    double memPerc = 0.0;
                    try {
                        double mt = Double.parseDouble(memTotal);
                        double mu = Double.parseDouble(memUsed);
                        memPerc = mt > 0 ? (mu / mt) * 100.0 : 0.0;
                    } catch (Exception ignored) {}
                    sb.append("GPU ").append(idx).append(" (").append(name).append(") | Util: ").append(util).append("% | Mem: ")
                            .append(memUsed).append("/").append(memTotal).append(" MB\n");
                } else {
                    sb.append(line).append("\n");
                }
                count++;
            }
            p.waitFor();
            if (count > 0) return sb.toString();
        } catch (Throwable ignored) {}

        try {
            ProcessBuilder pb = new ProcessBuilder("rocm-smi", "--showproductname", "--showuse", "--showtemp", "--showmeminfo");
            pb.redirectErrorStream(true);
            Process p = pb.start();
            BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String line;
            boolean has = false;
            while ((line = br.readLine()) != null) {
                has = true;

            }
            p.waitFor();
            if (has) return "AMD GPU(s) detected";
        } catch (Throwable ignored) {}

        if (!os.contains("win")) {
            try {
                ProcessBuilder pb = new ProcessBuilder("lspci", "-mm");
                pb.redirectErrorStream(true);
                Process p = pb.start();
                BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()));
                String line;
                int found = 0;
                while ((line = br.readLine()) != null) {
                    if (!line.toLowerCase().contains("vga") && !line.toLowerCase().contains("3d controller") && !line.toLowerCase().contains("display controller")) continue;

                    String cleaned = line.replaceAll("\"", "");
                    String[] q = cleaned.split("\t");

                    String vendorDevice = cleaned;
                    if (q.length >= 3) vendorDevice = q[2] + " " + (q.length >= 4 ? q[3] : "");
                    sb.append("GPU: ").append(vendorDevice).append("\n");
                    found++;
                }
                p.waitFor();
                if (found > 0) return sb.toString();
            } catch (Throwable ignored) {}
        }

        return "No GPU information available (tried nvidia-smi, rocm-smi, lspci, and WMI).";
    }

    private void getDiskIOUsageAsync(Consumer<String> callback) {
        new BukkitRunnable() {
            @Override
            public void run() {
                String os = System.getProperty("os.name").toLowerCase();
                StringBuilder out = new StringBuilder();
                try {
                    if (os.contains("win")) {
                        ProcessBuilder pb = new ProcessBuilder("powershell.exe", "-NoProfile", "-Command",
                                "Get-CimInstance Win32_PerfFormattedData_PerfDisk_PhysicalDisk | Where-Object {$_.Name -eq \"_Total\"} | Select-Object DiskReadBytesPersec,DiskWriteBytesPersec | ConvertTo-Csv -NoTypeInformation");
                        pb.redirectErrorStream(true);
                        Process p = pb.start();
                        BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()));
                        String line;
                        boolean first = true;
                        int count = 0;
                        while ((line = br.readLine()) != null) {
                            line = line.trim();
                            if (line.isEmpty()) continue;
                            if (first) { first = false; continue; }
                            String[] fields = line.split(",");
                            String read = fields.length > 0 ? fields[0].replaceAll("\"", "").trim() : "0";
                            String write = fields.length > 1 ? fields[1].replaceAll("\"", "").trim() : "0";
                            double r = 0, w = 0;
                            try { r = Double.parseDouble(read); } catch (Exception ignored) {}
                            try { w = Double.parseDouble(write); } catch (Exception ignored) {}
                            double rMB = r / (1024.0 * 1024.0);
                            double wMB = w / (1024.0 * 1024.0);
                            out.append("Disk Read: ").append(df.format(rMB)).append(" MB/s | Disk Write: ").append(df.format(wMB)).append(" MB/s\n");
                            count++;
                        }
                        p.waitFor();
                        if (count > 0) {
                            String s = out.toString();
                            new BukkitRunnable() { public void run() { callback.accept(s); } }.runTask(LagStabilizer.this);
                            return;
                        }
                    } else {
                        Map<String, long[]> snap1 = readDiskStats();
                        if (snap1 != null) {
                            try { Thread.sleep(1000); } catch (InterruptedException ignored) {}
                            Map<String, long[]> snap2 = readDiskStats();
                            if (snap2 != null) {
                                long totalRead = 0L;
                                long totalWrite = 0L;
                                for (String dev : snap2.keySet()) {
                                    long[] a = snap1.get(dev);
                                    long[] b = snap2.get(dev);
                                    if (a == null || b == null) continue;
                                    long deltaRead = Math.max(0L, b[0] - a[0]);
                                    long deltaWrite = Math.max(0L, b[1] - a[1]);
                                    long sectorSize = 512L;
                                    try {
                                        Path p = Paths.get("/sys/block", dev, "queue", "logical_block_size");
                                        if (Files.exists(p)) {
                                            String v = new String(Files.readAllBytes(p)).trim();
                                            sectorSize = Long.parseLong(v);
                                        }
                                    } catch (Exception ignored) {}
                                    long readBytes = deltaRead * sectorSize;
                                    long writeBytes = deltaWrite * sectorSize;
                                    totalRead += readBytes;
                                    totalWrite += writeBytes;
                                }
                                double totalReadMB = totalRead / (1024.0 * 1024.0);
                                double totalWriteMB = totalWrite / (1024.0 * 1024.0);
                                out.append("Disk Read: ").append(df.format(totalReadMB)).append(" MB/s | Disk Write: ").append(df.format(totalWriteMB)).append(" MB/s\n");
                                String s = out.toString();
                                new BukkitRunnable() { public void run() { callback.accept(s); } }.runTask(LagStabilizer.this);
                                return;
                            }
                        }
                    }
                } catch (Throwable ignored) {}

                try {
                    ProcessBuilder pb = new ProcessBuilder("iostat", "-d", "-k", "1", "2");
                    pb.redirectErrorStream(true);
                    Process p = pb.start();
                    BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()));
                    String line;
                    StringBuilder raw = new StringBuilder();
                    while ((line = br.readLine()) != null) raw.append(line).append("\n");
                    p.waitFor();
                    String s = raw.toString();
                    new BukkitRunnable() { public void run() { callback.accept(s); } }.runTask(LagStabilizer.this);
                    return;
                } catch (Throwable ignored) {}

                new BukkitRunnable() { public void run() { callback.accept("Disk I/O data not available on this system."); } }.runTask(LagStabilizer.this);
            }
        }.runTaskAsynchronously(LagStabilizer.this);
    }

    private Map<String, long[]> readDiskStats() {
        Map<String, long[]> map = new HashMap<>();
        try (BufferedReader br = Files.newBufferedReader(Paths.get("/proc/diskstats"))) {
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;
                String[] t = line.split("\\s+");
                if (t.length < 14) continue;
                String dev = t[2];
                long sectorsRead = 0L;
                long sectorsWritten = 0L;
                try { sectorsRead = Long.parseLong(t[5]); } catch (Exception ignored) {}
                try { sectorsWritten = Long.parseLong(t[9]); } catch (Exception ignored) {}
                map.put(dev, new long[]{sectorsRead, sectorsWritten});
            }
            return map;
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> completions = new ArrayList<>();
        if ("stabilizer".equalsIgnoreCase(command.getName())) {
            String[] subcommands = {"clean", "tps", "ram", "cpu", "storage", "network", "gpu", "disk", "world", "debug", "info"};
            if (args.length == 1) {
                for (String sub : subcommands)
                    if (sub.startsWith(args[0].toLowerCase())) completions.add(sub);
            }
            else if (args[0].equalsIgnoreCase("clean") && args.length <= 7 && sender instanceof Player p) {
                Location loc = p.getLocation();
                String[] coords = {
                        String.valueOf(loc.getBlockX()),
                        String.valueOf(loc.getBlockY()),
                        String.valueOf(loc.getBlockZ()),
                        String.valueOf(loc.getBlockX() + 15),
                        String.valueOf(loc.getBlockY() + 320),
                        String.valueOf(loc.getBlockZ() + 15)
                };
                if (args.length >= 2 && args.length <= 7) {
                    completions.add(coords[args.length - 2]);
                }
            }
        } else if ("ping".equalsIgnoreCase(command.getName())) {
            if (args.length == 1) {
                for (Player p : Bukkit.getOnlinePlayers())
                    if (p.getName().toLowerCase().startsWith(args[0].toLowerCase())) completions.add(p.getName());
            }
        }
        return completions;
    }
}