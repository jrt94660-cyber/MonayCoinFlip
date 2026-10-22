package com.monay.coinflip;

import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

import java.text.DecimalFormat;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

public final class MonayCoinFlip extends JavaPlugin implements Listener {
    private Economy economy;
    private final Map<UUID, Flip> flips = new LinkedHashMap<>();
    private final Set<UUID> notificationsOff = new HashSet<>();
    private final Map<UUID, UUID> spinning = new HashMap<>();
    private final DecimalFormat moneyFormat = new DecimalFormat("#,##0.##");
    private static final String PREFIX = ChatColor.translateAlternateColorCodes('&', "&8[&6CoinFlip&8] &r");
    private static final int[] BROWSE_SLOTS = {10,11,12,13,14,15,16,19,20,21,22,23,24,25,28,29,30,31,32,33,34,37,38,39,40,41,42,43};

    @Override public void onEnable() {
        saveDefaultConfig();
        if (!setupEconomy()) {
            getLogger().severe("Vault economy provider not found. Install Vault and an economy plugin.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        for (String id : getConfig().getStringList("notifications-disabled")) {
            try { notificationsOff.add(UUID.fromString(id)); } catch (IllegalArgumentException ignored) {}
        }
        getServer().getPluginManager().registerEvents(this, this);
        getLogger().info("MonayCoinFlip enabled.");
    }

    @Override public void onDisable() {
        // Refund any unjoined wagers so a restart cannot destroy players' money.
        for (Flip flip : flips.values()) economy.depositPlayer(Bukkit.getOfflinePlayer(flip.owner), flip.amount);
        flips.clear();
        saveNotificationPreferences();
    }

    private boolean setupEconomy() {
        if (getServer().getPluginManager().getPlugin("Vault") == null) return false;
        RegisteredServiceProvider<Economy> registration = getServer().getServicesManager().getRegistration(Economy.class);
        if (registration == null) return false;
        economy = registration.getProvider();
        return economy != null;
    }

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) { sender.sendMessage("This command is for players only."); return true; }
        if (!player.hasPermission("monaycoinflip.use")) { msg(player, "&cYou don't have permission."); return true; }
        if (args.length == 0) { openBrowse(player); return true; }
        if (args[0].equalsIgnoreCase("toggle")) {
            if (notificationsOff.remove(player.getUniqueId())) msg(player, "&aCoinflip notifications enabled.");
            else { notificationsOff.add(player.getUniqueId()); msg(player, "&eCoinflip notifications disabled."); }
            saveNotificationPreferences();
            return true;
        }
        if (args[0].equalsIgnoreCase("add")) {
            if (args.length < 2) { msg(player, "&cUsage: /coinflip add <amount> &7(e.g. 1k, 2.5m, 100,000)"); return true; }
            double amount;
            try { amount = parseAmount(String.join("", Arrays.copyOfRange(args, 1, args.length))); }
            catch (IllegalArgumentException ex) { msg(player, "&cInvalid amount. Examples: 500, 1k, 2.5m"); return true; }
            double min = getConfig().getDouble("minimum-bet", 1.0);
            double max = getConfig().getDouble("maximum-bet", 1000000000.0);
            if (!Double.isFinite(amount) || amount < min || amount > max) { msg(player, "&cBet must be between &f" + format(min) + " &cand &f" + format(max) + "&c."); return true; }
            if (flips.values().stream().anyMatch(f -> f.owner.equals(player.getUniqueId()))) { msg(player, "&cYou already have an active coinflip."); return true; }
            if (!economy.has(player, amount)) { msg(player, "&cYou don't have enough money. You need &f" + format(amount) + "&c."); return true; }
            economy.withdrawPlayer(player, amount);
            Flip flip = new Flip(player.getUniqueId(), amount);
            flips.put(flip.id, flip);
            announce(player, "&e" + player.getName() + " &7created a coinflip for &6" + format(amount) + "&7! &a/coinflip &7to join.");
            msg(player, "&aCoinflip created for &6" + format(amount) + "&a. Your wager is held until someone joins.");
            openBrowse(player);
            return true;
        }
        msg(player, "&e/coinflip &7- Browse coinflips");
        msg(player, "&e/coinflip add <amount> &7- Create a coinflip");
        msg(player, "&e/coinflip toggle &7- Toggle notifications");
        return true;
    }

    private void openBrowse(Player player) {
        Inventory inv = Bukkit.createInventory(null, 54, color("&8CoinFlip &7» &fBrowse"));
        ItemStack pane = item(Material.GRAY_STAINED_GLASS_PANE, "&7 ", List.of());
        for (int i = 0; i < 54; i++) inv.setItem(i, pane);
        inv.setItem(4, item(Material.GOLD_INGOT, "&6&lCOINFLIP", List.of("&7Pick a player's wager to join.", "&7Your chance is 50/50.", "&e/coinflip add <amount> &7to create one.")));
        int idx = 0;
        for (Flip flip : flips.values()) {
            if (idx >= BROWSE_SLOTS.length) break;
            if (flip.owner.equals(player.getUniqueId())) continue;
            Player owner = Bukkit.getPlayer(flip.owner);
            if (owner == null) continue;
            inv.setItem(BROWSE_SLOTS[idx++], head(flip.owner, "&e" + owner.getName(), List.of("&7Wager: &6" + format(flip.amount), "&7Winner receives: &a" + format(flip.amount * 2), "", "&aClick to challenge!")));
        }
        inv.setItem(45, item(Material.PAPER, "&eYour balance", List.of("&7Balance: &6" + format(economy.getBalance(player)))));
        inv.setItem(49, item(Material.SUNFLOWER, "&aCreate a coinflip", List.of("&7Use &f/coinflip add <amount>", "&7Money formats: &f1k, 1.5m, 2b")));
        inv.setItem(53, item(Material.BARRIER, "&cClose", List.of("&7Click to close this menu.")));
        player.openInventory(inv);
    }

    @EventHandler public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        String title = event.getView().getTitle();
        if (!ChatColor.stripColor(title).contains("CoinFlip")) return;
        event.setCancelled(true);
        if (event.getRawSlot() < 0 || event.getRawSlot() >= event.getInventory().getSize()) return;
        if (event.getRawSlot() == 53) { player.closeInventory(); return; }
        Flip selected = null;
        ItemStack clicked = event.getCurrentItem();
        if (clicked != null && clicked.getType() == Material.PLAYER_HEAD && clicked.getItemMeta() instanceof SkullMeta meta) {
            for (Flip flip : flips.values()) {
                if (meta.getOwningPlayer() != null && flip.owner.equals(meta.getOwningPlayer().getUniqueId())) { selected = flip; break; }
            }
        }
        if (selected != null && title.contains("Browse")) joinFlip(player, selected);
    }

    private void joinFlip(Player challenger, Flip flip) {
        if (!flips.containsKey(flip.id)) { msg(challenger, "&cThat coinflip is no longer available."); openBrowse(challenger); return; }
        if (flip.owner.equals(challenger.getUniqueId())) { msg(challenger, "&cYou can't join your own coinflip."); return; }
        Player owner = Bukkit.getPlayer(flip.owner);
        if (owner == null) { flips.remove(flip.id); economy.depositPlayer(Bukkit.getOfflinePlayer(flip.owner), flip.amount); msg(challenger, "&cThat player is offline; their wager was refunded."); openBrowse(challenger); return; }
        if (!economy.has(challenger, flip.amount)) { msg(challenger, "&cYou need &6" + format(flip.amount) + " &cto join."); return; }
        economy.withdrawPlayer(challenger, flip.amount);
        flips.remove(flip.id);
        spinning.put(owner.getUniqueId(), challenger.getUniqueId());
        spinning.put(challenger.getUniqueId(), owner.getUniqueId());
        startRoulette(owner, challenger, flip.amount);
    }

    private void startRoulette(Player owner, Player challenger, double amount) {
        String title = color("&8&lCOINFLIP &7» &eRoulette");
        Inventory inv = Bukkit.createInventory(null, 27, title);
        ItemStack filler = item(Material.BLACK_STAINED_GLASS_PANE, "&7 ", List.of());
        for (int i = 0; i < 27; i++) inv.setItem(i, filler);
        inv.setItem(4, item(Material.GOLD_INGOT, "&6&lCOINFLIP ROULETTE", List.of("&7Wager per player: &6" + format(amount), "&7Prize pool: &a" + format(amount * 2))));
        inv.setItem(11, head(owner.getUniqueId(), "&e" + owner.getName(), List.of("&7Player 1", "&7Wager: &6" + format(amount))));
        inv.setItem(13, item(Material.CLOCK, "&e&lSPINNING...", List.of("&7The coin is flipping!", "&7Good luck to both players.")));
        inv.setItem(15, head(challenger.getUniqueId(), "&b" + challenger.getName(), List.of("&7Player 2", "&7Wager: &6" + format(amount))));
        owner.openInventory(inv); challenger.openInventory(inv);
        final int[] tick = {0};
        Bukkit.getScheduler().runTaskTimer(this, task -> {
            if (!owner.isOnline() || !challenger.isOnline()) {
                task.cancel();
                Player remaining = owner.isOnline() ? owner : challenger;
                if (remaining.isOnline()) remaining.closeInventory();
                economy.depositPlayer(Bukkit.getOfflinePlayer(owner.getUniqueId()), amount);
                economy.depositPlayer(Bukkit.getOfflinePlayer(challenger.getUniqueId()), amount);
                spinning.remove(owner.getUniqueId()); spinning.remove(challenger.getUniqueId());
                msg(owner, "&cCoinflip cancelled; wagers refunded.");
                msg(challenger, "&cCoinflip cancelled; wagers refunded.");
                return;
            }
            tick[0]++;
            boolean showOwner = ThreadLocalRandom.current().nextBoolean();
            inv.setItem(13, head(showOwner ? owner.getUniqueId() : challenger.getUniqueId(),
                    "&e&l" + (showOwner ? owner.getName() : challenger.getName()),
                    List.of("&6&lROULETTE", "&7Player heads are spinning...", "&7Wager: &6" + format(amount))));
            owner.playSound(owner.getLocation(), Sound.UI_BUTTON_CLICK, 0.7f, 1.2f);
            challenger.playSound(challenger.getLocation(), Sound.UI_BUTTON_CLICK, 0.7f, 1.2f);
            if (tick[0] >= 40) {
                task.cancel();
                Player winner = ThreadLocalRandom.current().nextBoolean() ? owner : challenger;
                Player loser = winner.equals(owner) ? challenger : owner;
                economy.depositPlayer(winner, amount * 2);
                spinning.remove(owner.getUniqueId()); spinning.remove(challenger.getUniqueId());
                inv.setItem(13, head(winner.getUniqueId(), "&a&lWINNER: " + winner.getName(), List.of("&7Won the roulette!", "&7Prize: &6" + format(amount * 2))));
                winner.playSound(winner.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1f);
                loser.playSound(loser.getLocation(), Sound.ENTITY_VILLAGER_NO, 1f, 1f);
                msg(owner, winner.equals(owner) ? "&aYou won &6" + format(amount * 2) + "&a!" : "&cYou lost the coinflip. &7Winner: &e" + winner.getName());
                msg(challenger, winner.equals(challenger) ? "&aYou won &6" + format(amount * 2) + "&a!" : "&cYou lost the coinflip. &7Winner: &e" + winner.getName());
                Bukkit.broadcastMessage(PREFIX + color("&e" + winner.getName() + " &7won a coinflip against &e" + loser.getName() + " &7for &6" + format(amount * 2) + "&7!"));
                Bukkit.getScheduler().runTaskLater(this, () -> { if (owner.isOnline()) openBrowse(owner); if (challenger.isOnline()) openBrowse(challenger); }, 50L);
            }
        }, 0L, 2L);
    }

    @EventHandler public void onInventoryClose(InventoryCloseEvent event) {
        // Closing the roulette doesn't cancel a wager; the animation and settlement continue.
    }

    private void announce(Player creator, String message) {
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!p.getUniqueId().equals(creator.getUniqueId()) && !notificationsOff.contains(p.getUniqueId())) msg(p, message);
        }
    }
    private void saveNotificationPreferences() {
        getConfig().set("notifications-disabled", notificationsOff.stream().map(UUID::toString).toList());
        saveConfig();
    }
    private String format(double amount) {
        String symbol = getConfig().getString("currency-symbol", "$");
        return symbol + moneyFormat.format(amount);
    }
    private static double parseAmount(String input) {
        String s = input.trim().toLowerCase(Locale.ROOT).replace(",", "").replace("_", "").replace("$", "").replace("₱", "").replace(" ", "");
        if (s.isEmpty()) throw new IllegalArgumentException();
        double multiplier = 1;
        char last = s.charAt(s.length() - 1);
        if (last == 'k') { multiplier = 1_000; s = s.substring(0, s.length() - 1); }
        else if (last == 'm') { multiplier = 1_000_000; s = s.substring(0, s.length() - 1); }
        else if (last == 'b') { multiplier = 1_000_000_000; s = s.substring(0, s.length() - 1); }
        double value = Double.parseDouble(s) * multiplier;
        if (!Double.isFinite(value) || value <= 0) throw new IllegalArgumentException();
        return value;
    }
    private static ItemStack item(Material material, String name, List<String> lore) {
        ItemStack stack = new ItemStack(material);
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(color(name));
            meta.setLore(lore.stream().map(MonayCoinFlip::color).toList());
            stack.setItemMeta(meta);
        }
        return stack;
    }
    private static ItemStack head(UUID uuid, String name, List<String> lore) {
        ItemStack stack = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) stack.getItemMeta();
        OfflinePlayer offline = Bukkit.getOfflinePlayer(uuid);
        meta.setOwningPlayer(offline);
        meta.setDisplayName(color(name));
        meta.setLore(lore.stream().map(MonayCoinFlip::color).toList());
        stack.setItemMeta(meta);
        return stack;
    }
    private static String color(String s) { return ChatColor.translateAlternateColorCodes('&', s); }
    private static void msg(Player player, String message) { player.sendMessage(PREFIX + color(message)); }

    private static final class Flip {
        private final UUID id = UUID.randomUUID();
        private final UUID owner;
        private final double amount;
        private Flip(UUID owner, double amount) { this.owner = owner; this.amount = amount; }
    }
}
