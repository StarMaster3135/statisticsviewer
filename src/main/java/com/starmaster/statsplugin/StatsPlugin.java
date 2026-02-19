package com.starmaster.statsplugin;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.Sound;
import org.bukkit.Statistic;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class StatsPlugin extends JavaPlugin implements Listener {

    private Map<UUID, Integer> currentPage = new HashMap<>();
    private Map<String, List<PlayerStat>> cachedStats = new ConcurrentHashMap<>();
    
    private final Map<UUID, ItemStack> headTextureCache = new ConcurrentHashMap<>();
    
    private long lastCacheUpdate = 0;
    private static final long CACHE_DURATION = 30000;
    
    private long lastSkinCacheClear = System.currentTimeMillis();
    private static final long SKIN_CACHE_DURATION = 3600000;

    @Override
    public void onEnable() {
        getLogger().info("StatsPlugin has been enabled!");
        getServer().getPluginManager().registerEvents(this, this);
        
        new BukkitRunnable() {
            @Override
            public void run() {
                updateStatsCache();
            }
        }.runTaskAsynchronously(this);
    }

    @Override
    public void onDisable() {
        headTextureCache.clear();
        getLogger().info("StatsPlugin has been disabled!");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage("This command can only be used by players!");
            return true;
        }

        Player player = (Player) sender;

        if (command.getName().equalsIgnoreCase("stats")) {
            openMainMenu(player);
            player.playSound(player.getLocation(), Sound.BLOCK_CHEST_OPEN, 1.0f, 1.0f);
            return true;
        }

        return false;
    }

    private void updateStatsCache() {
        long currentTime = System.currentTimeMillis();
        
        if (currentTime - lastSkinCacheClear > SKIN_CACHE_DURATION) {
            headTextureCache.clear();
            lastSkinCacheClear = currentTime;
            getLogger().info("Cleared player head cache (1 hour passed)");
        }

        if (currentTime - lastCacheUpdate < CACHE_DURATION) {
            return;
        }

        Map<String, List<PlayerStat>> newCache = new HashMap<>();
        
        List<PlayerStat> killStats = new ArrayList<>();
        List<PlayerStat> deathStats = new ArrayList<>();
        List<PlayerStat> playtimeStats = new ArrayList<>();

        for (OfflinePlayer offlinePlayer : Bukkit.getOfflinePlayers()) {
            if (offlinePlayer.hasPlayedBefore()) {
                try {
                    int kills = offlinePlayer.getStatistic(Statistic.PLAYER_KILLS);
                    int deaths = offlinePlayer.getStatistic(Statistic.DEATHS);
                    int playtime = offlinePlayer.getStatistic(Statistic.PLAY_ONE_MINUTE) / 20 / 60;

                    if (kills > 0) killStats.add(new PlayerStat(offlinePlayer, kills));
                    if (deaths > 0) deathStats.add(new PlayerStat(offlinePlayer, deaths));
                    if (playtime > 0) playtimeStats.add(new PlayerStat(offlinePlayer, playtime));
                } catch (Exception e) {
                }
            }
        }

        killStats.sort((a, b) -> Integer.compare(b.value, a.value));
        deathStats.sort((a, b) -> Integer.compare(b.value, a.value));
        playtimeStats.sort((a, b) -> Integer.compare(b.value, a.value));

        newCache.put("Kills", killStats);
        newCache.put("Deaths", deathStats);
        newCache.put("Playtime", playtimeStats);

        cachedStats = newCache;
        lastCacheUpdate = currentTime;
    }

    private void openMainMenu(Player player) {
        Inventory inv = Bukkit.createInventory(null, 27, "§6§lServer Statistics");

        ItemStack kills = new ItemStack(Material.DIAMOND_SWORD);
        ItemMeta killsMeta = kills.getItemMeta();
        killsMeta.setDisplayName("§c§lKills");
        killsMeta.setLore(Arrays.asList("§7Click to view top killers"));
        kills.setItemMeta(killsMeta);

        ItemStack deaths = new ItemStack(Material.SKELETON_SKULL);
        ItemMeta deathsMeta = deaths.getItemMeta();
        deathsMeta.setDisplayName("§4§lDeaths");
        deathsMeta.setLore(Arrays.asList("§7Click to view most deaths"));
        deaths.setItemMeta(deathsMeta);

        ItemStack playtime = new ItemStack(Material.CLOCK);
        ItemMeta playtimeMeta = playtime.getItemMeta();
        playtimeMeta.setDisplayName("§a§lPlaytime");
        playtimeMeta.setLore(Arrays.asList("§7Click to view top playtime"));
        playtime.setItemMeta(playtimeMeta);

        inv.setItem(11, kills);
        inv.setItem(13, deaths);
        inv.setItem(15, playtime);

        player.openInventory(inv);
    }

    private void openStatsMenu(Player player, String type, int page) {
        currentPage.put(player.getUniqueId(), page);
        
        new BukkitRunnable() {
            @Override
            public void run() {
                updateStatsCache();
                
                new BukkitRunnable() {
                    @Override
                    public void run() {
                        buildStatsInventory(player, type, page);
                    }
                }.runTask(StatsPlugin.this);
            }
        }.runTaskAsynchronously(this);
        
        showLoadingMenu(player, type);
    }

    private void showLoadingMenu(Player player, String type) {
        Inventory inv = Bukkit.createInventory(null, 54, "§6§l" + type + " Leaderboard");
        
        ItemStack loading = new ItemStack(Material.HOPPER);
        ItemMeta loadingMeta = loading.getItemMeta();
        loadingMeta.setDisplayName("§e§lLoading...");
        loadingMeta.setLore(Arrays.asList("§7Please wait"));
        loading.setItemMeta(loadingMeta);
        
        inv.setItem(22, loading);
        player.openInventory(inv);
    }

    private void buildStatsInventory(Player player, String type, int page) {
        Inventory inv = Bukkit.createInventory(null, 54, "§6§l" + type + " Leaderboard");

        List<PlayerStat> stats = cachedStats.getOrDefault(type, new ArrayList<>());

        int itemsPerPage = 45;
        int totalPages = Math.max(1, (int) Math.ceil((double) stats.size() / itemsPerPage));
        int startIndex = page * itemsPerPage;
        int endIndex = Math.min(startIndex + itemsPerPage, stats.size());

        int slot = 0;
        for (int i = startIndex; i < endIndex; i++) {
            PlayerStat stat = stats.get(i);
            
            ItemStack head = getCachedHead(stat.player);
            SkullMeta meta = (SkullMeta) head.getItemMeta();
            
            meta.setDisplayName("§e#" + (i + 1) + " §f" + stat.player.getName());
            
            List<String> lore = new ArrayList<>();
            if (type.equals("Playtime")) {
                lore.add("§7" + type + ": §a" + formatPlaytime(stat.value));
            } else {
                lore.add("§7" + type + ": §a" + stat.value);
            }
            meta.setLore(lore);
            
            head.setItemMeta(meta);
            inv.setItem(slot++, head);
        }

        if (page > 0) {
            ItemStack prevPage = new ItemStack(Material.ARROW);
            ItemMeta prevMeta = prevPage.getItemMeta();
            prevMeta.setDisplayName("§e§l← Previous Page");
            prevMeta.setLore(Arrays.asList("§7Page " + page + " of " + totalPages));
            prevPage.setItemMeta(prevMeta);
            inv.setItem(48, prevPage);
        }

        ItemStack back = new ItemStack(Material.BARRIER);
        ItemMeta backMeta = back.getItemMeta();
        backMeta.setDisplayName("§c§lBack to Menu");
        back.setItemMeta(backMeta);
        inv.setItem(49, back);

        if (page < totalPages - 1) {
            ItemStack nextPage = new ItemStack(Material.ARROW);
            ItemMeta nextMeta = nextPage.getItemMeta();
            nextMeta.setDisplayName("§e§lNext Page →");
            nextMeta.setLore(Arrays.asList("§7Page " + (page + 2) + " of " + totalPages));
            nextPage.setItemMeta(nextMeta);
            inv.setItem(50, nextPage);
        }

        ItemStack pageIndicator = new ItemStack(Material.PAPER);
        ItemMeta pageMeta = pageIndicator.getItemMeta();
        pageMeta.setDisplayName("§6§lPage " + (page + 1) + "/" + totalPages);
        pageMeta.setLore(Arrays.asList("§7Showing " + (endIndex - startIndex) + " of " + stats.size() + " players"));
        pageIndicator.setItemMeta(pageMeta);
        inv.setItem(53, pageIndicator);

        player.openInventory(inv);
    }
    
    private ItemStack getCachedHead(OfflinePlayer target) {
        ItemStack cached = headTextureCache.get(target.getUniqueId());
        
        if (cached != null) {
            return cached.clone();
        }
        
        ItemStack newHead = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) newHead.getItemMeta();
        meta.setOwningPlayer(target);
        newHead.setItemMeta(meta);
        
        headTextureCache.put(target.getUniqueId(), newHead);
        
        return newHead.clone();
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player)) return;
        
        Player player = (Player) e.getWhoClicked();
        String title = e.getView().getTitle();

        if (title.equals("§6§lServer Statistics")) {
            e.setCancelled(true);
            
            if (e.getCurrentItem() == null || e.getCurrentItem().getType() == Material.AIR) return;

            ItemStack clicked = e.getCurrentItem();
            String name = clicked.getItemMeta().getDisplayName();

            player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 1.0f, 1.0f);

            if (name.contains("Kills")) {
                openStatsMenu(player, "Kills", 0);
            } else if (name.contains("Deaths")) {
                openStatsMenu(player, "Deaths", 0);
            } else if (name.contains("Playtime")) {
                openStatsMenu(player, "Playtime", 0);
            }
        } else if (title.contains("Leaderboard")) {
            e.setCancelled(true);
            
            if (e.getCurrentItem() == null || e.getCurrentItem().getType() == Material.AIR) return;

            ItemStack clicked = e.getCurrentItem();
            Material type = clicked.getType();
            
            if (type == Material.BARRIER) {
                player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 1.0f, 1.0f);
                openMainMenu(player);
                currentPage.remove(player.getUniqueId());
            } else if (type == Material.ARROW) {
                player.playSound(player.getLocation(), Sound.ITEM_BOOK_PAGE_TURN, 1.0f, 1.0f);
                
                String displayName = clicked.getItemMeta().getDisplayName();
                String statType = title.replace("§6§l", "").replace(" Leaderboard", "");
                int currentPlayerPage = currentPage.getOrDefault(player.getUniqueId(), 0);
                
                if (displayName.contains("Previous")) {
                    openStatsMenu(player, statType, currentPlayerPage - 1);
                } else if (displayName.contains("Next")) {
                    openStatsMenu(player, statType, currentPlayerPage + 1);
                }
            }
        }
    }

    private static class PlayerStat {
        OfflinePlayer player;
        int value;

        PlayerStat(OfflinePlayer player, int value) {
            this.player = player;
            this.value = value;
        }
    }

    private String formatPlaytime(int totalMinutes) {
        int days = totalMinutes / 1440;
        int hours = (totalMinutes % 1440) / 60;
        int minutes = totalMinutes % 60;

        StringBuilder formatted = new StringBuilder();

        if (days > 0) {
            formatted.append(days).append("d ");
        }
        if (hours > 0 || days > 0) {
            formatted.append(hours).append("h ");
        }
        formatted.append(minutes).append("m");

        return formatted.toString().trim();
    }
}