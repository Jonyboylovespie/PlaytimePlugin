package jonyboylovespie.playtimeplugin.playtimePlugin;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.time.ZonedDateTime;

public final class PlaytimePlugin extends JavaPlugin implements Listener
{
    private Map<UUID, Long> joinTimes;
    private FileConfiguration config;
    private String currentDate;
    private BukkitTask timeCheckTask;
    private long maxDailyPlaytimeMinutes;

    @Override
    public void onEnable()
    {
        joinTimes = new HashMap<>();
        currentDate = LocalDate.now().toString();

        saveDefaultConfig();
        config = getConfig();

        if (!config.contains("date"))
        {
            config.set("date", currentDate);
        }

        if (!config.contains("maxDailyPlaytimeMinutes"))
        {
            config.set("maxDailyPlaytimeMinutes", 0);
        }

        saveConfig();

        maxDailyPlaytimeMinutes = config.getLong("maxDailyPlaytimeMinutes");

        String storedDate = config.getString("date", currentDate);
        if (!storedDate.equals(currentDate))
        {
            resetDailyPlaytime();
        }

        getServer().getPluginManager().registerEvents(this, this);
        scheduleDailyReset();
        startTimeCheckTask();
    }

    @Override
    public void onDisable()
    {
        if (timeCheckTask != null)
        {
            timeCheckTask.cancel();
        }

        for (Player player : Bukkit.getOnlinePlayers())
        {
            savePlayerPlaytime(player);
        }

        saveConfig();
    }

    private void startTimeCheckTask()
    {
        timeCheckTask = Bukkit.getScheduler().runTaskTimer(this, () ->
        {
            for (Player player : Bukkit.getOnlinePlayers())
            {
                checkPlayerTimeLimits(player);
            }
        }, 1200L, 1200L);
    }

    private void checkPlayerTimeLimits(Player player)
    {
        UUID playerId = player.getUniqueId();

        savePlayerPlaytime(player);
        joinTimes.put(playerId, System.currentTimeMillis());

        long dailyPlaytimeMinutes = config.getLong("players." + playerId + ".daily", 0) / (1000 * 60);
        if (maxDailyPlaytimeMinutes > 0 && dailyPlaytimeMinutes >= maxDailyPlaytimeMinutes)
        {
            player.kickPlayer(ChatColor.RED + "You have reached your daily playtime limit of " + maxDailyPlaytimeMinutes + " minutes.");
        }
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event)
    {
        Player player = event.getPlayer();
        joinTimes.put(player.getUniqueId(), System.currentTimeMillis());

        Bukkit.getScheduler().runTaskLater(this, () -> checkPlayerTimeLimits(player), 20L);
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event)
    {
        Player player = event.getPlayer();
        savePlayerPlaytime(player);
    }

    private void savePlayerPlaytime(Player player)
    {
        UUID playerId = player.getUniqueId();
        if (joinTimes.containsKey(playerId))
        {
            long sessionTime = System.currentTimeMillis() - joinTimes.get(playerId);
            joinTimes.remove(playerId);

            String dailyPath = "players." + playerId + ".daily";
            long dailyPlaytime = config.getLong(dailyPath, 0) + sessionTime;
            config.set(dailyPath, dailyPlaytime);

            String totalPath = "players." + playerId + ".total";
            long totalPlaytime = config.getLong(totalPath, 0) + sessionTime;
            config.set(totalPath, totalPlaytime);

            config.set("players." + playerId + ".name", player.getName());
        }
        saveConfig();
    }

    private void resetDailyPlaytime()
    {
        ConfigurationSection playersSection = config.getConfigurationSection("players");
        if (playersSection != null)
        {
            for (String key : playersSection.getKeys(false))
            {
                config.set("players." + key + ".daily", 0);
            }
        }

        config.set("date", currentDate);
        saveConfig();
        getLogger().info("Daily playtime reset performed.");
    }

    private void scheduleDailyReset()
    {
        long ticksUntilMidnight = getTicksUntilMidnight();
        getLogger().info("Scheduling daily reset in " + ticksUntilMidnight + " ticks.");
        Bukkit.getScheduler().runTaskLater(this, () ->
        {
            resetDailyPlaytime();
            currentDate = LocalDate.now().toString();
            scheduleDailyReset();
        }, ticksUntilMidnight);
    }

    private long getTicksUntilMidnight()
    {
        ZonedDateTime now = ZonedDateTime.now();
        ZonedDateTime nextMidnight = now.toLocalDate().plusDays(1).atStartOfDay(now.getZone());
        long millisecondsUntilMidnight = nextMidnight.toInstant().toEpochMilli() - now.toInstant().toEpochMilli();
        return Math.max(1, millisecondsUntilMidnight / 50);
    }

    private String formatPlaytime(long milliseconds)
    {
        long seconds = milliseconds / 1000;
        long hours = seconds / 3600;
        long minutes = (seconds % 3600) / 60;
        seconds = seconds % 60;
        return String.format("%02d hours, %02d minutes, %02d seconds", hours, minutes, seconds);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args)
    {
        if (label.equalsIgnoreCase("playtime") || label.equalsIgnoreCase("pt"))
        {
            if (args.length == 0)
            {
                if (!(sender instanceof Player player))
                {
                    sender.sendMessage(ChatColor.RED + "This command can only be used by players.");
                    return true;
                }

                UUID playerId = player.getUniqueId();

                savePlayerPlaytime(player);
                joinTimes.put(playerId, System.currentTimeMillis());

                long dailyPlaytime = config.getLong("players." + playerId + ".daily", 0);
                long totalPlaytime = config.getLong("players." + playerId + ".total", 0);

                player.sendMessage(ChatColor.GREEN + "Your playtime:");
                showData(player, dailyPlaytime, totalPlaytime);
                return true;
            }
            if (args.length == 1)
            {
                if (!sender.hasPermission("playtime.others"))
                {
                    sender.sendMessage(ChatColor.RED + "You don't have permission to check others' playtime.");
                    return true;
                }

                String targetName = args[0];
                UUID targetId = null;

                ConfigurationSection playersSection = config.getConfigurationSection("players");
                if (playersSection != null)
                {
                    for (String key : playersSection.getKeys(false))
                    {
                        String name = config.getString("players." + key + ".name");
                        if (name != null && name.equalsIgnoreCase(targetName))
                        {
                            targetId = UUID.fromString(key);
                            break;
                        }
                    }
                }

                if (targetId == null)
                {
                    sender.sendMessage(ChatColor.RED + "Player not found.");
                    return true;
                }

                Player targetPlayer = Bukkit.getPlayer(targetId);
                if (targetPlayer != null && targetPlayer.isOnline())
                {
                    savePlayerPlaytime(targetPlayer);
                    joinTimes.put(targetId, System.currentTimeMillis());
                }

                long dailyPlaytime = config.getLong("players." + targetId + ".daily", 0);
                long totalPlaytime = config.getLong("players." + targetId + ".total", 0);
                String name = config.getString("players." + targetId + ".name");

                sender.sendMessage(ChatColor.GREEN + name + "'s playtime:");
                showData(sender, dailyPlaytime, totalPlaytime);
                return true;
            }
        }
        if (label.equalsIgnoreCase("playLimit") || label.equalsIgnoreCase("pl"))
        {
            if (args.length == 2)
            {
                if (args[0].equalsIgnoreCase("set") && sender.hasPermission("playtime.admin"))
                {
                    try
                    {
                        int minutes = Integer.parseInt(args[1]);
                        if (minutes < 0)
                        {
                            sender.sendMessage(ChatColor.RED + "Time limit cannot be negative.");
                            return true;
                        }

                        config.set("maxDailyPlaytimeMinutes", minutes);
                        maxDailyPlaytimeMinutes = minutes;
                        saveConfig();

                        if (minutes == 0)
                        {
                            sender.sendMessage(ChatColor.GREEN + "Daily playtime limit disabled.");
                        }
                        else
                        {
                            sender.sendMessage(ChatColor.GREEN + "Daily playtime limit set to " + minutes + " minutes.");
                        }
                        return true;
                    }
                    catch (NumberFormatException e)
                    {
                        sender.sendMessage(ChatColor.RED + "Please enter a valid number of minutes.");
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private void showData(CommandSender sender, long dailyPlaytime, long totalPlaytime)
    {
        sender.sendMessage(ChatColor.YELLOW + "Today: " + ChatColor.WHITE + formatPlaytime(dailyPlaytime));
        sender.sendMessage(ChatColor.YELLOW + "Total: " + ChatColor.WHITE + formatPlaytime(totalPlaytime));
    }
}